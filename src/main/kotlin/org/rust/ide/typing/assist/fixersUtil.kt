/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.typing.assist

import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.elementType

fun PsiElement.isLastChildBlockExpr(): Boolean =
    if (this is RsBlockExpr ||
        this is RsWhileExpr ||
        this is RsLoopExpr ||
        this is RsForExpr ||
        this is RsIfExpr ||
        this.elementType == RsElementTypes.ELSE
    ) {
        true
    } else {
        this.lastChild?.isLastChildBlockExpr() ?: false
    }
