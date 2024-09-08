/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.typing.assist

import com.intellij.lang.SmartEnterProcessorWithFixers
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.elementType
import org.rust.lang.core.psi.ext.endOffset

class MissingSimpleBlockFixer : SmartEnterProcessorWithFixers.Fixer<RsSmartEnterProcessor>() {
    override fun apply(editor: Editor, processor: RsSmartEnterProcessor, element: PsiElement) {
        val document = editor.document

        when {
            element is RsLoopExpr -> {
                if (element.block is RsBlock) return
                document.insertString(element.endOffset, " {}")
            }

            element is RsWhileExpr -> {
                if (element.block is RsBlock) return
                val condition = element.condition ?: return
                document.insertString(condition.endOffset, " {}")
            }

            element is RsForExpr -> {
                if (element.block is RsBlock) return
                val expr = element.expr ?: return
                document.insertString(expr.endOffset, " {}")
            }

            element is RsIfExpr -> {
                val ifExpr = findMissingBlockIfExpr(element) ?: return
                if (ifExpr.block is RsBlock) return
                val condition = ifExpr.condition ?: return
                document.insertString(condition.endOffset, " {}")
            }

            element.elementType == RsElementTypes.ELSE -> {
                document.insertString(element.endOffset, " {}")
            }
        }
    }
}

private fun findMissingBlockIfExpr(element: RsIfExpr): RsIfExpr? {
    if (element.condition != null && element.block !is RsBlock) {
        return element
    }
    val elseIfExpr = element.elseBranch?.ifExpr ?: return null
    return findMissingBlockIfExpr(elseIfExpr)
}
