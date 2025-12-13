/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.psi.RsElementTypes
import org.rust.lang.core.psi.RsTraitType
import org.rust.lang.core.psi.RsUseBoundsClause
import org.rust.lang.core.stubs.RsTraitTypeStub

val RsTraitType.isImpl: Boolean get() = ((greenStub as? RsTraitTypeStub)?.isImpl) ?: (impl != null)

val RsTraitType.dyn: PsiElement? get() = node.findChildByType(RsElementTypes.DYN)?.psi

val RsTraitType.useBoundsClause: RsUseBoundsClause?
    get() = PsiTreeUtil.getChildOfType(this, RsUseBoundsClause::class.java)

val RsTraitType.hasExplicitCaptures: Boolean
    get() = useBoundsClause != null
