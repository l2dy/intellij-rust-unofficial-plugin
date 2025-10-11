/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.newProject

import com.intellij.ide.projectWizard.NewProjectWizardConstants
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GitNewProjectWizardData.Companion.gitData
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import org.rust.ide.icons.RsIcons
import org.rust.ide.module.RsModuleBuilder
import org.rust.stdext.toPathOrNull
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.Icon

class RsNewProjectWizard : LanguageGeneratorNewProjectWizard {
    @Suppress("UnstableApiUsage")
    override val name: String = NewProjectWizardConstants.Language.RUST
    override val icon: Icon get() = RsIcons.RUST

    override val ordinal: Int = 900

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = Step(parent)

    private class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
        private val peer: RsProjectGeneratorPeer = RsProjectGeneratorPeer(parent.baseData?.path?.toPathOrNull() ?: Paths.get("."))

        override fun setupUI(builder: Panel) {
            with(builder) {
                row {
                    cell(peer.wizardComponent)
                        .align(AlignX.FILL)
                        .validationRequestor { peer.checkValid = Runnable(it) }
                        .validationInfo { peer.validate() }
                }
            }
        }

        override fun setupProject(project: Project) {
            val builder = RsModuleBuilder()
            val module = builder.commit(project)?.firstOrNull() ?: return
            ModuleRootModificationUtil.updateModel(module) { rootModel ->
                builder.configurationData = peer.settings
                builder.createProject(rootModel, vcs = "none")
                if (gitData?.git == true) createGitIgnoreFile(context.projectDirectory, module)
            }
        }

        companion object {
            private const val GITIGNORE: String = ".gitignore"

            private fun createGitIgnoreFile(projectDir: Path, module: Module) {
                val directory = VfsUtil.createDirectoryIfMissing(projectDir.toString()) ?: return
                val existingFile = directory.findChild(GITIGNORE)
                if (existingFile != null) return
                val file = directory.createChildData(module, GITIGNORE)
                VfsUtil.saveText(file, "/target\n")
            }
        }
    }
}
