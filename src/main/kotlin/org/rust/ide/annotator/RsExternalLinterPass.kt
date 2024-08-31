/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.codeHighlighting.DirtyScopeTrackingHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.FileStatusMap
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.BackgroundTaskUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.externalLinterSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.toolchain.impl.Applicability
import org.rust.cargo.toolchain.tools.CargoCheckArgs
import org.rust.ide.notifications.RsExternalLinterSlowRunNotifier
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.containingCargoTarget
import org.rust.openapiext.isUnitTestMode

class RsExternalLinterPass(
    private val factory: RsExternalLinterPassFactory,
    private val file: PsiFile,
    private val editor: Editor
) : TextEditorHighlightingPass(file.project, editor.document), DumbAware {
    private val highlights: MutableList<HighlightInfo> = mutableListOf()
    @Volatile
    private var annotationInfo: Lazy<RsExternalLinterResult?>? = null
    private val annotationResult: RsExternalLinterResult? get() = annotationInfo?.value
    @Volatile
    private var disposable: Disposable = myProject

    override fun doCollectInformation(progress: ProgressIndicator) {
        highlights.clear()
        if (file !is RsFile || !isAnnotationPassEnabled) return

        val cargoTarget = file.containingCargoTarget ?: return
        if (cargoTarget.pkg.origin != PackageOrigin.WORKSPACE) return


        val moduleOrProject: Disposable = ModuleUtil.findModuleForFile(file) ?: myProject
        disposable = myProject.messageBus.createDisposableOnAnyPsiChange()
            .also { Disposer.register(moduleOrProject, it) }

        myProject.messageBus.connect(disposable).subscribe(
            CargoProjectsService.CARGO_PROJECTS_REFRESH_TOPIC,
            object : CargoProjectsService.CargoProjectsRefreshListener {
                override fun onRefreshStarted() {
                    Disposer.dispose(disposable)
                }

                override fun onRefreshFinished(status: CargoProjectsService.CargoRefreshStatus) {
                }
            }
        )

        if (myProject.cargoProjects.isRefreshing) {
            Disposer.dispose(disposable)
        } else {
            val args = CargoCheckArgs.forTarget(myProject, cargoTarget)
            annotationInfo = RsExternalLinterUtils.checkLazily(
                myProject.toolchain ?: return,
                myProject,
                disposable,
                cargoTarget.pkg.workspace.contentRoot,
                args
            )
        }
    }

    override fun doApplyInformationToEditor() {
        if (file !is RsFile) return

        if (annotationInfo == null || !isAnnotationPassEnabled) {
            disposable = myProject
            doFinish(emptyList())
            return
        }

        class RsUpdate: Update(file) {
            val updateFile: RsFile = file

            override fun setRejected() {
                super.setRejected()
                doFinish(highlights)
            }

            override fun run() {
                BackgroundTaskUtil.runUnderDisposeAwareIndicator(disposable, Runnable {
                    val annotationResult = annotationResult ?: return@Runnable
                    myProject.service<RsExternalLinterSlowRunNotifier>().reportDuration(annotationResult.executionTime)
                    runReadAction {
                        ProgressManager.checkCanceled()
                        doApply(annotationResult)
                        ProgressManager.checkCanceled()
                        doFinish(highlights)
                    }
                })
            }

            override fun canEat(update: Update): Boolean = updateFile == (update as? RsUpdate)?.updateFile
        }

        val update = RsUpdate()
        if (isUnitTestMode) {
            update.run()
        } else {
            factory.scheduleExternalActivity(update)
        }
    }

    private fun doApply(annotationResult: RsExternalLinterResult) {
        if (file !is RsFile || !file.isValid) return
        try {
            highlights.addHighlightsForFile(file, annotationResult, Applicability.UNSPECIFIED)
        } catch (t: Throwable) {
            if (t is ProcessCanceledException) throw t
            LOG.error(t)
        }
    }

    private fun doFinish(highlights: List<HighlightInfo>) {
        invokeLater(ModalityState.stateForComponent(editor.component)) {
            if (Disposer.isDisposed(disposable)) return@invokeLater
            UpdateHighlightersUtil.setHighlightersToEditor(
                myProject,
                document,
                0,
                file.textLength,
                highlights,
                colorsScheme,
                id
            )
            DaemonCodeAnalyzerEx.getInstanceEx(myProject).fileStatusMap.markFileUpToDate(document, id)
        }
    }

    private val isAnnotationPassEnabled: Boolean
        get() = myProject.externalLinterSettings.runOnTheFly

    companion object {
        private val LOG: Logger = logger<RsExternalLinterPass>()
    }
}

class RsExternalLinterPassFactory(
    project: Project,
    registrar: TextEditorHighlightingPassRegistrar
) : DirtyScopeTrackingHighlightingPassFactory {
    private val myPassId: Int = registrar.registerTextEditorHighlightingPass(
        this,
        null,
        null,
        false,
        -1
    )

    private val externalLinterQueue = MergingUpdateQueue(
        "RsExternalLinterQueue",
        TIME_SPAN,
        true,
        MergingUpdateQueue.ANY_COMPONENT,
        project,
        null,
        false
    )

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        FileStatusMap.getDirtyTextRange(editor, passId) ?: return null
        return RsExternalLinterPass(this, file, editor)
    }

    override fun getPassId(): Int = myPassId

    fun scheduleExternalActivity(update: Update) = externalLinterQueue.queue(update)

    companion object {
        private const val TIME_SPAN: Int = 300
    }
}
