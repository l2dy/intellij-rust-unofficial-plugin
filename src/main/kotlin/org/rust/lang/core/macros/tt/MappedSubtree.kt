/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros.tt

import com.intellij.util.SmartList
import org.rust.lang.core.macros.MappedTextRange
import org.rust.lang.core.macros.RangeMap
import org.rust.lang.core.macros.mergeAdd
import org.rust.lang.core.psi.MacroBraces
import org.rust.stdext.withNext

data class MappedSubtree(val subtree: TokenTree.Subtree, val tokenMap: TokenMap)

class TokenMap(val map: List<TokenMetadata>) {
    fun get(id: TokenId): TokenMetadata? = map.getOrNull(id)
    fun merge(other: TokenMap): TokenMap = TokenMap(map + other.map)
}

sealed class TokenMetadata {
    data class Token(
        val startOffset: Int,
        val rightTrivia: CharSequence,
        val origin: TokenTree.Leaf
    ): TokenMetadata()

    data class Delimiter(
        val open: DelimiterPart,
        val close: DelimiterPart?,
        val originKind: MacroBraces
    ): TokenMetadata() {
        data class DelimiterPart(val startOffset: Int, val rightTrivia: CharSequence)
    }
}

fun MappedSubtree.toMappedText(): Pair<CharSequence, RangeMap> {
    val recoveredSubtree = SubtreeIdRecovery(subtree, tokenMap).recover()
    return SubtreeTextBuilder(recoveredSubtree, tokenMap).toText()
}

private class SubtreeTextBuilder(
    private val subtree: TokenTree.Subtree,
    private val tokenMap: TokenMap
) {
    private val sb = StringBuilder()
    private val ranges = SmartList<MappedTextRange>()

    fun toText(): Pair<CharSequence, RangeMap> {
        subtree.appendSubtree(isRootSubtree = true)
        return sb to RangeMap(ranges)
    }

    private fun TokenTree.Subtree.appendSubtree(isRootSubtree: Boolean = false) {
        delimiter?.let { appendDelimiterPart(it, DelimiterBracePart.OPEN) }
        for ((tokenTree, nextTokenTree) in tokenTrees.asSequence().withNext()) {
            when (tokenTree) {
                is TokenTree.Leaf -> tokenTree.appendLeaf(nextTokenTree, isRootSubtree || delimiter != null)
                is TokenTree.Subtree -> tokenTree.appendSubtree()
            }
        }
        delimiter?.let { appendDelimiterPart(it, DelimiterBracePart.CLOSE) }
    }

    private fun TokenTree.Leaf.appendLeaf(nextTokenTree: TokenTree?, hasDelimiter: Boolean) {
        val (text, spacing) = when (this) {
            is TokenTree.Leaf.Literal -> text to Spacing.Alone
            is TokenTree.Leaf.Ident -> (isRaw.toString() + text) to Spacing.Alone
            is TokenTree.Leaf.Punct -> char to spacing
        }

        val meta = (tokenMap.get(id) as? TokenMetadata.Token)
            ?.takeIf { it.origin == this }
        if (meta != null) {
            ranges.mergeAdd(MappedTextRange(meta.startOffset, sb.length, text.length + meta.rightTrivia.length))
        }
        sb.append(text)
        if (meta != null) {
            sb.append(meta.rightTrivia)

            val canOmitSpace = when (nextTokenTree) {
                // `let x = 0;` - don't add space between `0` and `;` since they are adjacent
                // note that [id] is negative for tokens generated from macro def body
                is TokenTree.Leaf -> id >= 0 && id + 1 == nextTokenTree.id
                // `func()` - don't add space before `(`
                is TokenTree.Subtree -> true
                // `func(1)` - don't add space after `1`
                null -> hasDelimiter
            }
            if (meta.rightTrivia.isEmpty() && spacing == Spacing.Alone && !canOmitSpace) {
                sb.append(" ")
            }
        } else if (spacing == Spacing.Alone) {
            sb.append(" ")
        }
    }

    private fun appendDelimiterPart(delimiter: Delimiter, part: DelimiterBracePart) {
        val meta = (tokenMap.get(delimiter.id) as? TokenMetadata.Delimiter)?.let { part.metaGetter(it) }
        if (meta != null) {
            ranges.mergeAdd(MappedTextRange(meta.startOffset, sb.length, 1 + meta.rightTrivia.length))
        }
        sb.append(part.textGetter(delimiter.kind))
        if (meta != null) {
            sb.append(meta.rightTrivia)
        }
    }

    private enum class DelimiterBracePart(
        val metaGetter: (TokenMetadata.Delimiter) -> TokenMetadata.Delimiter.DelimiterPart?,
        val textGetter: (MacroBraces) -> String
    ) {
        OPEN(TokenMetadata.Delimiter::open, MacroBraces::openText),
        CLOSE(TokenMetadata.Delimiter::close, MacroBraces::closeText)
    }
}

/**
 * Real procedural macros tend to discard spans (token ids) from [TokenTree.Leaf.Punct] tokens
 * and subtree [Delimiter]s. This routine tries to recover them using a simple heuristic:
 * if a token without a mapping (with id == -1) follows a token *with* a mapping, then it most likely
 * should be mapped with the next token in the source. The only thing should be checked is that
 * its type and text is equal to the token in the source.
 */
private class SubtreeIdRecovery(
    private val subtree: TokenTree.Subtree,
    private val tokenMap: TokenMap
) {
    private var previousLeafId: Int = -1

    fun recover(): TokenTree.Subtree {
        return subtree.processSubtree()
    }

    private fun TokenTree.Subtree.processSubtree(): TokenTree.Subtree {
        val adjustedDelimiter = delimiter?.let { processDelimiter(it) }
        if (adjustedDelimiter != null) {
            previousLeafId = adjustedDelimiter.id
        }
        val adjustedTokenTrees = tokenTrees.map { tokenTree ->
            when (tokenTree) {
                is TokenTree.Leaf -> {
                    val newLeaf = if (tokenTree is TokenTree.Leaf.Punct) processLeaf(tokenTree) else tokenTree
                    previousLeafId = newLeaf.id
                    newLeaf
                }
                is TokenTree.Subtree -> tokenTree.processSubtree()
            }
        }
        return TokenTree.Subtree(adjustedDelimiter, adjustedTokenTrees)
    }

    private fun processLeaf(leaf: TokenTree.Leaf.Punct): TokenTree.Leaf {
        if (leaf.id != -1 || previousLeafId == -1) {
            return leaf
        }

        val recoveredId = previousLeafId + 1
        val recoveredMeta = tokenMap.get(recoveredId) as? TokenMetadata.Token ?: return leaf
        val newLeaf = leaf.copy(id = recoveredId)
        if (newLeaf != recoveredMeta.origin) {
            return leaf
        }
        return newLeaf
    }

    private fun processDelimiter(delimiter: Delimiter): Delimiter {
        if (delimiter.id != -1 || previousLeafId == -1) {
            return delimiter
        }
        val recoveredId = previousLeafId + 1
        val recoveredMeta = tokenMap.get(recoveredId) as? TokenMetadata.Delimiter ?: return delimiter
        if (delimiter.kind != recoveredMeta.originKind) {
            return delimiter
        }
        return delimiter.copy(id = recoveredId)
    }
}
