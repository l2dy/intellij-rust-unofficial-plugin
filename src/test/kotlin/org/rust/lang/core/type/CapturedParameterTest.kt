/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.type

import junit.framework.TestCase
import org.rust.lang.core.types.infer.TypeFolder
import org.rust.lang.core.types.ty.CapturedParameter
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyInfer
import org.rust.lang.core.types.ty.TyTypeParameter

class CapturedParameterTest : TestCase() {

    fun `test folding type param handles ty var`() {
        val typeParam = TyTypeParameter.self()
        val captured = CapturedParameter.TypeParam(typeParam)
        val folder = object : TypeFolder {
            override fun foldTy(ty: Ty): Ty = TyInfer.TyVar()
        }

        val result = captured.foldWith(folder) as CapturedParameter.TypeParam
        assertSame(typeParam, result.param)
    }
}
