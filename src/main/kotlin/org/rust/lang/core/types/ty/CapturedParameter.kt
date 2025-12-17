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
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyInfer.TyVar

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
        override fun foldWith(folder: TypeFolder): CapturedParameter =
            TypeParam(asTypeParameter(param.foldWith(folder), param))

        override fun visitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)

        override fun superFoldWith(folder: TypeFolder): CapturedParameter =
            TypeParam(asTypeParameter(param.foldWith(folder), param))

        override fun superVisitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)

        /**
         * Converts a folded type back into a [TyTypeParameter], falling back to the original parameter
         * when folding produces inference variables or other types. For [TyVar] we only reuse the
         * stored origin when it is a [TyTypeParameter].
         */
        private fun asTypeParameter(value: Ty, fallback: TyTypeParameter): TyTypeParameter = when (value) {
            is TyTypeParameter -> value
            is TyVar -> {
                val origin = value.origin
                if (origin is TyTypeParameter) origin else fallback
            }
            else -> fallback
        }
    }

    data class ConstParam(val param: CtConstParameter) : CapturedParameter() {
        override fun foldWith(folder: TypeFolder): CapturedParameter =
            ConstParam(param.foldWith(folder) as CtConstParameter)

        override fun visitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)

        override fun superFoldWith(folder: TypeFolder): CapturedParameter =
            ConstParam(param.foldWith(folder) as CtConstParameter)

        override fun superVisitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)
    }
}
