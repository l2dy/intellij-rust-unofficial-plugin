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
        override fun foldWith(folder: TypeFolder): CapturedParameter =
            TypeParam(param.foldWith(folder) as TyTypeParameter)

        override fun visitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)

        override fun superFoldWith(folder: TypeFolder): CapturedParameter =
            TypeParam(param.foldWith(folder) as TyTypeParameter)

        override fun superVisitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)
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
