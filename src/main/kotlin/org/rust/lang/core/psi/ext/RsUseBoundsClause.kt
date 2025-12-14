/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.psi.RsLifetime
import org.rust.lang.core.psi.RsUseBoundsClause
import org.rust.lang.core.stubs.RsUseBoundsElementStub

val RsUseBoundsClause.capturedLifetimes: List<RsLifetime>
    get() = useBoundsElementList.mapNotNull { it.lifetime }

val RsUseBoundsClause.capturedIdentifiers: List<String>
    get() = useBoundsElementList.mapNotNull {
        val stub = it.greenStub as? RsUseBoundsElementStub
        stub?.identifier
            ?: if (stub?.hasSelfKeyword == true || it.cself != null) {
                "Self"
            } else {
                it.identifier?.text
            }
    }

val RsUseBoundsClause.isEmpty: Boolean
    get() = useBoundsElementList.isEmpty()

val RsUseBoundsClause.hasSelf: Boolean
    get() = useBoundsElementList.any {
        val stub = it.greenStub as? RsUseBoundsElementStub
        stub?.hasSelfKeyword == true || it.cself != null
    }
