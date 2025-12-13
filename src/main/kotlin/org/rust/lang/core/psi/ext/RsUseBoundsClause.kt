/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.psi.RsLifetime
import org.rust.lang.core.psi.RsUseBoundsClause

val RsUseBoundsClause.capturedLifetimes: List<RsLifetime>
    get() = useBoundsElementList.mapNotNull { it.lifetime }

val RsUseBoundsClause.capturedIdentifiers: List<String>
    get() = useBoundsElementList.mapNotNull {
        it.identifier?.text ?: if (it.cself != null) "Self" else null
    }

val RsUseBoundsClause.isEmpty: Boolean
    get() = useBoundsElementList.isEmpty()

val RsUseBoundsClause.hasSelf: Boolean
    get() = useBoundsElementList.any { it.cself != null }
