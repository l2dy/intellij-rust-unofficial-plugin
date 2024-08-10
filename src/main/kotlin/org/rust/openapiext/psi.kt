/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.openapiext

import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import org.rust.lang.core.psi.ProcMacroAttribute
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.RsMetaItem
import org.rust.lang.core.psi.ext.*


/**
 * Iterates all children of the `PsiElement` and invokes `action` for each one.
 */
inline fun PsiElement.forEachChild(action: (PsiElement) -> Unit) {
    var psiChild: PsiElement? = firstChild

    while (psiChild != null) {
        if (psiChild.node is CompositeElement) {
            action(psiChild)
        }
        psiChild = psiChild.nextSibling
    }
}

/** Behaves like [PsiTreeUtil.findChildrenOfAnyType], but also collects elements expanded from macros */
fun <T : PsiElement> findDescendantsWithMacrosOfAnyType(
    element: PsiElement?,
    strict: Boolean,
    vararg classes: Class<out T>
): Collection<T> {
    if (element == null) return ContainerUtil.emptyList()

    val processor = object : PsiElementProcessor.CollectElements<PsiElement>() {
        override fun execute(each: PsiElement): Boolean {
            if (strict && each === element) return true
            return if (PsiTreeUtil.instanceOf(each, *classes)) {
                super.execute(each)
            } else true
        }
    }
    processElementsWithMacrosVisitOrAbort(element, processor)
    @Suppress("UNCHECKED_CAST")
    return processor.collection as Collection<T>
}

fun interface PsiTreeProcessor {
    fun execute(element: PsiElement): TreeStatus
}
enum class TreeStatus {
    VISIT_CHILDREN, SKIP_CHILDREN, ABORT
}

private fun processElementsWithMacrosVisitOrAbort(element: PsiElement, processor: PsiElementProcessor<PsiElement>): Boolean =
    processElementsWithMacros(element) {
        if (processor.execute(it)) TreeStatus.VISIT_CHILDREN else TreeStatus.ABORT
    }

/** Behaves like [PsiTreeUtil.processElements], but also collects elements expanded from macros */
fun processElementsWithMacros(element: PsiElement, processor: PsiTreeProcessor): Boolean {
    if (element is PsiCompiledElement || !element.isPhysical) {
        // DummyHolders cannot be visited by walking visitors because children/parent relationship is broken there
        when (processor.execute(element)) {
            TreeStatus.VISIT_CHILDREN -> Unit
            TreeStatus.SKIP_CHILDREN -> return true
            TreeStatus.ABORT -> return false
        }
        for (child in element.children) {
            if (child is RsMacroCall && child.macroArgument != null) {
                child.expansion?.elements?.forEach {
                    if (!processElementsWithMacros(it, processor)) return false
                }
            } else if (!processElementsWithMacros(child, processor)) {
                return false
            }
        }
        return true
    }

    val visitor = RsWithMacrosRecursiveElementWalkingVisitor(processor)
    visitor.visitElement(element)

    return visitor.result
}

private class RsWithMacrosRecursiveElementWalkingVisitor(
    private val processor: PsiTreeProcessor,
) : PsiRecursiveElementWalkingVisitor() {

    var result: Boolean = true
        private set

    override fun visitElement(element: PsiElement) {
        /**
         * It is extremely important NOT to call `super.visitElement(element.child)` here,
         * because it will be equivalent to `super.visitElement(element)`
         */

        val procMacroAttribute = (element as? RsAttrProcMacroOwner)?.procMacroAttributeWithDerives
        if (tryProcessAttrProcMacro(procMacroAttribute)) return

        when (processor.execute(element)) {
            TreeStatus.VISIT_CHILDREN -> {
                if (element is RsMacroCall && shouldExpandMacro(element)) {
                    processMacro(element, element.path)
                } else {
                    super.visitElement(element)
                }
                tryProcessDeriveProcMacro(procMacroAttribute)
            }
            TreeStatus.SKIP_CHILDREN -> return
            TreeStatus.ABORT -> {
                stopWalking()
                result = false
            }
        }
    }

    private fun shouldExpandMacro(element: RsMacroCall): Boolean {
        // When adding other macros, we should also adjust
        // type inference in `org.rust.lang.core.types.infer.RsTypeInferenceWalker.inferFormatMacro`
        val isWriteMacro = element.formatMacroArgument != null
            && element.macroName.let { it == "write" || it == "writeln" }
        return element.macroArgument != null || isWriteMacro
    }

    private fun processMacro(element: RsPossibleMacroCall, path: RsElement?) {
        val visitor = RsWithMacrosRecursiveElementWalkingVisitor(processor)
        if (path != null) visitor.visitElement(path)

        val expansion = element.expansion ?: return
        for (expandedElement in expansion.elements) {
            visitor.visitElement(expandedElement)
        }
    }

    private fun tryProcessAttrProcMacro(procMacroAttribute: ProcMacroAttribute<RsMetaItem>?): Boolean {
        if (procMacroAttribute !is ProcMacroAttribute.Attr) return false
        processMacro(procMacroAttribute.attr, procMacroAttribute.attr.path)
        return true
    }

    private fun tryProcessDeriveProcMacro(procMacroAttribute: ProcMacroAttribute<RsMetaItem>?) {
        if (procMacroAttribute !is ProcMacroAttribute.Derive) return
        for (derive in procMacroAttribute.derives) {
            processMacro(derive, path = null)
        }
    }
}
