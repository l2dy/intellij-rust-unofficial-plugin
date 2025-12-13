/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.ElementPattern
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import org.rust.lang.core.RsPsiPattern.psiElement
import org.rust.lang.core.psi.RsElementTypes
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.RsTraitType
import org.rust.lang.core.psi.RsUseBoundsClause
import org.rust.lang.core.psi.ext.*

object RsUseBoundsCompletionProvider : RsCompletionProvider() {

    override val elementPattern: ElementPattern<PsiElement> =
        psiElement(RsElementTypes.IDENTIFIER)
            .inside(psiElement<RsUseBoundsClause>())

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position
        val useBoundsClause = position.ancestorOrSelf<RsUseBoundsClause>() ?: return
        val traitType = useBoundsClause.parent as? RsTraitType ?: return

        for (scope in traitType.contexts.filterIsInstance<RsGenericDeclaration>()) {
            val typeParamList = scope.typeParameterList ?: continue

            typeParamList.typeParameterList.forEach { param ->
                val name = param.name ?: return@forEach
                result.addElement(
                    LookupElementBuilder.create(name)
                        .withIcon(param.getIcon(0))
                        .withTypeText("type parameter")
                )
            }

            typeParamList.lifetimeParameterList.forEach { param ->
                val name = param.name ?: return@forEach
                result.addElement(
                    LookupElementBuilder.create(name)
                        .withIcon(param.getIcon(0))
                        .withTypeText("lifetime")
                )
            }

            typeParamList.constParameterList.forEach { param ->
                val name = param.name ?: return@forEach
                result.addElement(
                    LookupElementBuilder.create(name)
                        .withIcon(param.getIcon(0))
                        .withTypeText("const parameter")
                )
            }
        }

        val inTraitOrImpl = traitType.contexts.any { it is RsImplItem || it is RsTraitItem }
        if (inTraitOrImpl) {
            result.addElement(
                LookupElementBuilder.create("Self")
                    .withTypeText("self type")
                    .bold()
            )
        }
    }
}
