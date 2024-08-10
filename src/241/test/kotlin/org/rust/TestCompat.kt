/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust

import com.intellij.execution.process.ProcessOutputType
import com.intellij.ide.navbar.tests.contextNavBarPathStrings
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Key

// BACKCOMPAT 2023.2: move to the RsAnsiEscapeDecoderTest companion
val Key<*>.escapeSequence: String
    get() = (this as? ProcessOutputType)?.escapeSequence ?: toString()

val alphaSorterId = Sorter.ALPHA_SORTER_ID

fun contextNavBarPathStringsCompat(ctx: DataContext): List<String> {
    return contextNavBarPathStrings(ctx)
}
