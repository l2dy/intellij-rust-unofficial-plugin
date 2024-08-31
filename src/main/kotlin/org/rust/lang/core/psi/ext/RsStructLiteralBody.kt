/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.psi.RsStructLiteralBody
import org.rust.lang.core.psi.RsStructLiteralField

val RsStructLiteralBody.expandedFields: List<RsStructLiteralField>
    get() = structLiteralFieldList.filterNotNull().filter { it.isEnabledByCfgSelf(null) }
