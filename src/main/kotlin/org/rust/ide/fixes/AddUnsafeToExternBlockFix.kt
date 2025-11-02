/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.fixes

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.rust.RsBundle
import org.rust.lang.core.psi.RsForeignModItem
import org.rust.lang.core.psi.RsPsiFactory

/**
 * Quick fix to add `unsafe` keyword to extern blocks (required in Edition 2024).
 */
class AddUnsafeToExternBlockFix(element: RsForeignModItem) : RsQuickFixBase<RsForeignModItem>(element) {

    override fun getFamilyName() = text
    override fun getText() = RsBundle.message("intention.name.add.unsafe.to", "extern block")

    override fun invoke(project: Project, editor: Editor?, element: RsForeignModItem) {
        val externKeyword = element.externAbi.extern
        val unsafeKeyword = RsPsiFactory(project).createUnsafeKeyword()

        element.addBefore(unsafeKeyword, externKeyword)
        element.addAfter(RsPsiFactory(project).createWhitespace(" "), unsafeKeyword)
    }
}
