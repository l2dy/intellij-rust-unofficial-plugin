/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust

import com.intellij.ide.navbar.tests.contextNavBarPathStrings
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.actionSystem.DataContext

// BACKCOMPAT 2024.1: use directly in RsCombinedVisibilityAlphaSorterTest
val alphaSorterId = Sorter.ALPHA_SORTER_ID

// BACKCOMPAT 2024.1: move to RsNavBarTest
fun contextNavBarPathStringsCompat(ctx: DataContext): List<String> {
    return contextNavBarPathStrings(ctx)
}
