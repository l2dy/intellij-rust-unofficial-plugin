/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros.proc

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.util.parseSemVer
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.macros.*
import org.rust.lang.core.macros.errors.ProcMacroExpansionError
import org.rust.lang.core.macros.errors.ProcMacroExpansionError.ExecutableNotFound
import org.rust.lang.core.macros.errors.ProcMacroExpansionError.ProcMacroExpansionIsDisabled
import org.rust.lang.core.macros.tt.*
import org.rust.lang.core.parser.createRustPsiBuilder
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsDocAndAttributeOwner
import org.rust.lang.core.psi.ext.childrenWithLeaves
import org.rust.lang.core.psi.ext.getNextNonCommentSibling
import org.rust.lang.core.psi.ext.needsSemicolon
import org.rust.openapiext.RsPathManager.INTELLIJ_RUST_NATIVE_HELPER
import org.rust.openapiext.isUnitTestMode
import org.rust.stdext.RsResult
import org.rust.stdext.RsResult.Err
import org.rust.stdext.toResult
import org.rust.stdext.unwrapOrElse

class ProcMacroExpander private constructor(
    private val project: Project,
    private val toolchain: RsToolchainBase?,
    private val server: ProcMacroServerPool?,
    private val timeout: Long = Registry.get("org.rust.macros.proc.timeout").asInteger().toLong(),
) : MacroExpander<RsProcMacroData, ProcMacroExpansionError>() {
    private val isEnabled: Boolean = if (server != null) true else ProcMacroApplicationService.isAnyEnabled()

    private fun serverOrErr(): RsResult<ProcMacroServerPool, ProcMacroExpansionError> =
        server.toResult().mapErr { if (isEnabled) ExecutableNotFound else ProcMacroExpansionIsDisabled }

    override fun expandMacroAsTextWithErr(
        def: RsProcMacroData,
        call: RsMacroCallData
    ): RsResult<Pair<CharSequence, RangeMap>, ProcMacroExpansionError> {
        val server = serverOrErr().unwrapOrElse { return Err(it) }

        if (call.macroBody is MacroCallBody.FunctionLike && !ProcMacroApplicationService.isFunctionLikeEnabled()) {
            return Err(ProcMacroExpansionIsDisabled)
        }

        val (macroCallBodyText, attrText) = when (val macroBody = call.macroBody) {
            is MacroCallBody.FunctionLike -> MappedText.single(macroBody.text, 0) to null
            is MacroCallBody.Derive -> macroBody.item to null
            is MacroCallBody.Attribute -> {
                val item = if (macroBody.fixupRustSyntaxErrors) {
                    fixupRustSyntaxErrors(macroBody.item)
                } else {
                    macroBody.item
                }
                item to macroBody.attr
            }
        }
        val (macroCallBodyLowered, rangesLowering) = project
            .createRustPsiBuilder(macroCallBodyText.text)
            .lowerDocCommentsToPsiBuilder(project)
        val loweredMacroCallBodyRanges = macroCallBodyText.ranges.mapAll(rangesLowering)
        val (macroCallBodyTt, macroCallBodyTokenMap) = macroCallBodyLowered.parseSubtree()

        val (attrSubtree, mergedTokenMap, mergedRanges) = if (attrText != null) {
            // TODO comment why we need this?
            val startOffset = (loweredMacroCallBodyRanges.ranges.maxByOrNull { it.dstOffset }?.dstEndOffset ?: -1) + 1
            val shiftedRanges = attrText.ranges.ranges.map { it.dstShiftRight(startOffset) }
            val (subtree, map) = project.createRustPsiBuilder(attrText.text).parseSubtree(startOffset, macroCallBodyTokenMap.map.size)
            Triple(
                subtree.copy(delimiter = null),
                // TODO try shift TokenMap offsets instead
                macroCallBodyTokenMap.merge(map),
                RangeMap(loweredMacroCallBodyRanges.ranges + shiftedRanges)
            )
        } else {
            Triple(null, macroCallBodyTokenMap, loweredMacroCallBodyRanges)
        }
        val lib = def.artifact.path.toString()
        val env = call.env
        return expandMacroAsTtWithErrInternal(server, macroCallBodyTt, attrSubtree, def.name, lib, env).map {
            val (text, ranges) = MappedSubtree(it, mergedTokenMap).toMappedText()
            text to mergedRanges.mapAll(ranges)
        }
    }

    fun expandMacroAsTtWithErr(
        macroCallBody: TokenTree.Subtree,
        attributes: TokenTree.Subtree?,
        macroName: String,
        lib: String,
        env: Map<String, String> = emptyMap()
    ): RsResult<TokenTree.Subtree, ProcMacroExpansionError> {
        val server = serverOrErr().unwrapOrElse { return Err(it) }
        return expandMacroAsTtWithErrInternal(server, macroCallBody, attributes, macroName, lib, env)
    }

    private fun expandMacroAsTtWithErrInternal(
        server: ProcMacroServerPool,
        macroCallBody: TokenTree.Subtree,
        attributes: TokenTree.Subtree?,
        macroName: String,
        lib: String,
        env: Map<String, String> = emptyMap()
    ): RsResult<TokenTree.Subtree, ProcMacroExpansionError> {
        val remoteLib = toolchain?.toRemotePath(lib) ?: lib
        val envMapped = env.mapValues { (_, v) -> toolchain?.toRemotePath(v) ?: v }
        val version = server.requestExpanderVersion().unwrapOrElse { return Err(it.toProcMacroExpansionError()) }
        val request = Request.ExpandMacro(
            FlatTree.fromSubtree(macroCallBody, version),
            macroName,
            attributes?.let { FlatTree.fromSubtree(it, version) },
            remoteLib,
            envMapped.map { listOf(it.key, it.value) },
            envMapped["CARGO_MANIFEST_DIR"],
            Request.ExpnGlobals(0, -1, 0),
            null,
        )
        val response = server.send(request, timeout).unwrapOrElse { return Err(it.toProcMacroExpansionError()) }
        check(response is Response.ExpandMacro)
        return response.expansion.map {
            it.toTokenTree(version)
        }.mapErr {
            ProcMacroExpansionError.ServerSideError(it.message)
        }
    }

    private fun RequestSendError.toProcMacroExpansionError(): ProcMacroExpansionError {
        return when (this) {
            is RequestSendError.Timeout -> ProcMacroExpansionError.Timeout(timeout)
            is RequestSendError.ProcessCreation -> {
                MACRO_LOG.warn("Failed to run `$INTELLIJ_RUST_NATIVE_HELPER` process", e)
                ProcMacroExpansionError.CantRunExpander
            }
            is RequestSendError.IO -> when (e) {
                is ProcessAbortedException -> ProcMacroExpansionError.ProcessAborted(e.exitCode)
                else -> {
                    if (!isUnitTestMode) {
                        MACRO_LOG.error("Error communicating with `$INTELLIJ_RUST_NATIVE_HELPER` process", e)
                    }
                    ProcMacroExpansionError.IOExceptionThrown
                }
            }
            is RequestSendError.UnknownVersion -> ProcMacroExpansionError.UnsupportedExpanderVersion(version)
        }
    }

    /**
     * Attribute proc macros usually expect a correct Rust syntax passed as an input, but in an IDE
     * a user usually has invalid syntax, especially during typing.
     * This function tries to fix up the syntax in the input
     */
    private fun fixupRustSyntaxErrors(macroCallBodyText: MappedText): MappedText {
        val sb = MutableMappedText(macroCallBodyText.text.length)
        val item = RsPsiFactory(project, markGenerated = false)
            .createFile(macroCallBodyText.text)
            .firstChild as? RsDocAndAttributeOwner
            ?: return macroCallBodyText

        item.accept(object : RsRecursiveVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is LeafPsiElement) {
                    val startOffset = element.startOffset
                    sb.appendMapped(element.text, startOffset)
                } else {
                    super.visitElement(element)
                }
            }

            override fun visitExpr(o: RsExpr) {
                if (hasErrorToHandle(o)) {
                    sb.appendUnmapped("__ij__fixup")
                } else {
                    super.visitExpr(o)
                }
            }

            override fun visitDotExpr(o: RsDotExpr) {
                super.visitDotExpr(o)
                if (o.fieldLookup == null && o.methodCall == null) {
                    sb.appendUnmapped("__ij__fixup")
                }
            }

            override fun visitExprStmt(o: RsExprStmt) {
                super.visitStmt(o)
                if (o.semicolon == null && o.needsSemicolon() && o.getNextNonCommentSibling() is RsStmt) {
                    sb.appendUnmapped(";")
                }
            }

            override fun visitLetDecl(o: RsLetDecl) {
                super.visitLetDecl(o)
                if (o.semicolon == null) {
                    sb.appendUnmapped(";")
                }
            }
        })

        return if (sb.length == macroCallBodyText.text.length) {
            macroCallBodyText
        } else {
            val (text, ranges) = sb.toMappedText()
            MappedText(text, macroCallBodyText.ranges.mapAll(ranges))
        }
    }

    fun hasErrorToHandle(psi: PsiElement): Boolean =
        psi !is RsDotExpr && psi.childrenWithLeaves.any { it is PsiErrorElement || it !is RsExpr && hasErrorToHandle(it) }

    companion object {
        const val EXPANDER_VERSION: Int = 11
        private val MIN_RUSTC_VERSION_WITH_EXPANDER_VERSION_CHECK = "1.70.0".parseSemVer()

        fun forCrate(crate: Crate): ProcMacroExpander {
            val project = crate.project
            val toolchain = project.toolchain
            val rustcVersion = crate.cargoProject?.rustcInfo?.realVersion?.semver
            val procMacroExpanderPath = crate.cargoProject?.procMacroExpanderPath
            val server = if (toolchain != null && rustcVersion != null && procMacroExpanderPath != null) {
                val needsVersionCheck = rustcVersion >= MIN_RUSTC_VERSION_WITH_EXPANDER_VERSION_CHECK
                ProcMacroApplicationService.getInstance().getServer(toolchain, needsVersionCheck, procMacroExpanderPath)
            } else {
                null
            }
            return ProcMacroExpander(project, toolchain, server)
        }

        fun new(project: Project, server: ProcMacroServerPool?): ProcMacroExpander {
            return ProcMacroExpander(project, project.toolchain, server)
        }
    }
}
