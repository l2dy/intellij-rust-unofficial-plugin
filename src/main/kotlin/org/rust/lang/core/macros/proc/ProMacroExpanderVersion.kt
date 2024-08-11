/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros.proc

enum class ProMacroExpanderVersion {
    NO_VERSION_CHECK_VERSION,
    VERSION_CHECK_VERSION,
    ENCODE_CLOSE_SPAN_VERSION,
    HAS_GLOBAL_SPANS,
    RUST_ANALYZER_SPAN_SUPPORT,
    EXTENDED_LEAF_DATA,
    ;

    companion object {
        fun from(i: Int): ProMacroExpanderVersion? =
            values().getOrNull(i)
    }
}
