/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust

import com.intellij.execution.process.ProcessOutputType
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Key
import com.intellij.platform.navbar.NavBarItemPresentationData
import com.intellij.platform.navbar.backend.NavBarItem
import com.intellij.platform.navbar.backend.impl.pathToItem
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.TestOnly

// BACKCOMPAT 2023.2: move to the RsAnsiEscapeDecoderTest companion
val Key<*>.escapeSequence: String
    get() = (this as? ProcessOutputType)?.escapeSequence ?: toString()

val alphaSorterId = Sorter.getAlphaSorterId()

@Suppress("UnstableApiUsage")
@TestOnly
@RequiresReadLock
fun contextNavBarPathStrings(ctx: DataContext): List<String> {
    // Navigation bar implementation was split into several modules, which made `navbar.testFramework` test scope only.
    //
    // See https://youtrack.jetbrains.com/issue/IJPL-850/Split-navigation-bar-implementation-into-several-modules,
    // https://github.com/JetBrains/intellij-community/commit/a9e1406257b330d17d2a3f78f47b2d2113eca97c and
    // https://github.com/JetBrains/intellij-community/commit/bfa6619891699658f86a7bf8bdf7726a67bc6efd

    // Code copied from [platform/navbar/testFramework/src/testFramework.kt](https://github.com/JetBrains/intellij-community/blob/d161fd043392998e10c4551df92634dbda5a06b5/platform/navbar/testFramework/src/testFramework.kt#L34).
    val contextItem = NavBarItem.NAVBAR_ITEM_KEY.getData(ctx)
        ?.dereference()
        ?: return emptyList()
    return contextItem.pathToItem().map {
        (it.presentation() as NavBarItemPresentationData).text
    }
}

fun contextNavBarPathStringsCompat(ctx: DataContext): List<String> {
    return contextNavBarPathStrings(ctx)
}
