/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.rust.ide.colors.RsColor
import org.rust.lang.core.psi.RsPatBinding
import org.rust.lang.core.psi.RsPath
import org.rust.lang.core.psi.RsSelfParameter
import org.rust.lang.core.psi.RsValueParameter
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.lang.core.psi.ext.existsAfterExpansion
import org.rust.lang.core.psi.ext.mutability
import org.rust.lang.core.types.ty.TyReference
import org.rust.lang.core.types.type
import org.rust.openapiext.isUnitTestMode

class RsHighlightingMutableAnnotator : AnnotatorBase() {

    override fun annotateInternal(element: PsiElement, holder: AnnotationHolder) {
        if (holder.isBatchMode) return
        val ref = when (element) {
            is RsPath -> element.reference?.resolve() ?: return
            is RsSelfParameter -> element
            is RsPatBinding -> element
            else -> return
        }
        distinctAnnotation(element, ref, holder)
    }

    private fun annotationFor(ref: RsElement): RsColor? = when (ref) {
        is RsSelfParameter -> RsColor.MUT_PARAMETER
        is RsPatBinding -> if (ref.ancestorStrict<RsValueParameter>() != null) {
            RsColor.MUT_PARAMETER
        } else {
            RsColor.MUT_BINDING
        }
        else -> null
    }

    private fun distinctAnnotation(element: PsiElement, ref: RsElement, holder: AnnotationHolder) {
        if (!element.existsAfterExpansion) return
        val color = annotationFor(ref) ?: return
        if (ref.isMut) {
            @Suppress("NAME_SHADOWING")
            val element = partToHighlight(element)
            addHighlightingAnnotation(holder, element, color)
        }
    }

    private fun partToHighlight(element: PsiElement): PsiElement = when (element) {
        is RsSelfParameter -> element.self
        is RsPatBinding -> element.identifier
        else -> element
    }

    private fun addHighlightingAnnotation(holder: AnnotationHolder, target: PsiElement, key: RsColor) {
        val annotationSeverity = if (isUnitTestMode) key.testSeverity else HighlightSeverity.INFORMATION

        holder.newSilentAnnotation(annotationSeverity)
            .range(target.textRange)
            .textAttributes(key.textAttributesKey).create()
    }
}

private val RsElement.isMut: Boolean
    get() = when (this) {
        is RsPatBinding -> mutability.isMut || type.let { it is TyReference && it.mutability.isMut }
        is RsSelfParameter -> mutability.isMut
        else -> false
    }
