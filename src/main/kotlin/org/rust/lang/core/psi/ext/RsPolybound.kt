/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.psi.RsPolybound

/**
 * Return true if there is a question mark before a bound:
 * ```
 * fn foo<T: ?Sized>() {}
 *         //^
 * ```
 */
val RsPolybound.hasQ: Boolean
    get() = greenStub?.hasQ ?: (q != null)

/**
 * Return true if there is the tilde const before a bound:
 * ```
 * fn foo<T: ~const A>() {}
 *            //^
 * ```
 */
val RsPolybound.hasConst: Boolean
    get() = greenStub?.hasConst ?: (tildeConst != null)

/**
 * Return true if there is the bracket const before a bound:
 * ```
 * fn foo<T: [const] A>() {}
 *              //^
 * ```
 */
val RsPolybound.hasBracketConst: Boolean
    get() = greenStub?.hasBracketConst ?: (bracketConst != null)
