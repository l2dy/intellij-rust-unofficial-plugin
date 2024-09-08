/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.typing.assist

import com.intellij.lang.SmartEnterProcessorWithFixers
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.rust.lang.core.psi.ext.endOffset

class AfterSimpleBlockParentEnterProcessor : SmartEnterProcessorWithFixers.FixEnterProcessor() {
    override fun doEnter(atCaret: PsiElement, file: PsiFile, editor: Editor, modified: Boolean): Boolean {
        if (!modified) return false

        return if (atCaret.isLastChildBlockExpr()) {
            val elementEndOffset = atCaret.endOffset - 1
            editor.caretModel.moveToOffset(elementEndOffset)
            plainEnter(editor)
            modified
        } else {
            false
        }
    }

    override fun plainEnter(editor: Editor) {
        if (editor !is EditorEx) return

        val enterHandler = EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
        enterHandler.execute(editor, editor.caretModel.currentCaret, editor.dataContext)
    }
}
