/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.ty

import org.rust.lang.core.types.consts.CtConstParameter
import org.rust.lang.core.types.infer.TypeFolder
import org.rust.lang.core.types.infer.TypeFoldable
import org.rust.lang.core.types.infer.TypeVisitor
import org.rust.lang.core.types.regions.Region

sealed class CapturedParameter : TypeFoldable<CapturedParameter> {

    data class Lifetime(val region: Region) : CapturedParameter() {
        override fun foldWith(folder: TypeFolder): CapturedParameter =
            Lifetime(region.foldWith(folder))

        override fun visitWith(visitor: TypeVisitor): Boolean =
            region.visitWith(visitor)

        override fun superFoldWith(folder: TypeFolder): CapturedParameter =
            Lifetime(region.foldWith(folder))

        override fun superVisitWith(visitor: TypeVisitor): Boolean =
            region.visitWith(visitor)
    }

    data class TypeParam(val param: TyTypeParameter) : CapturedParameter() {
        override fun foldWith(folder: TypeFolder): CapturedParameter {
            val folded = param.foldWith(folder)
            // When a type parameter is substituted with a non-type-parameter (e.g., TyInfer.TyVar
            // during type inference), keep the original captured parameter since captures describe
            // which generic parameters are in scope, not their instantiated values.
            return if (folded is TyTypeParameter) TypeParam(folded) else this
        }

        override fun visitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)

        override fun superFoldWith(folder: TypeFolder): CapturedParameter {
            val folded = param.foldWith(folder)
            return if (folded is TyTypeParameter) TypeParam(folded) else this
        }

        override fun superVisitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)
    }

    data class ConstParam(val param: CtConstParameter) : CapturedParameter() {
        override fun foldWith(folder: TypeFolder): CapturedParameter {
            val folded = param.foldWith(folder)
            // When a const parameter is substituted with a non-const-parameter (e.g., CtInferVar
            // during type inference), keep the original captured parameter since captures describe
            // which generic parameters are in scope, not their instantiated values.
            return if (folded is CtConstParameter) ConstParam(folded) else this
        }

        override fun visitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)

        override fun superFoldWith(folder: TypeFolder): CapturedParameter {
            val folded = param.foldWith(folder)
            return if (folded is CtConstParameter) ConstParam(folded) else this
        }

        override fun superVisitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)
    }
}
