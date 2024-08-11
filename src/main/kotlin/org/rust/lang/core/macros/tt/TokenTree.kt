/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros.tt

import org.rust.lang.core.macros.tt.Spacing.Alone
import org.rust.lang.core.psi.MacroBraces

/**
 * [TokenTree] is a kind of AST used to communicate with Rust procedural macros.
 *
 * A procedural macro (defined in terms of [TokenTree]s) is a function that accepts a [TokenTree] and
 * returns a [TokenTree].
 */
sealed class TokenTree {
    sealed class Leaf: TokenTree() {
        abstract val id: TokenId

        data class Literal(
            val text: String,
            val kind: LitKind,
            override val id: TokenId
        ): Leaf()

        data class Punct(
            val char: String,
            val spacing: Spacing,
            override val id: TokenId
        ): Leaf()

        data class Ident(
            val text: String,
            val isRaw: IdentIsRaw,
            override val id: TokenId
        ): Leaf()
    }

    data class Subtree(
        val delimiter: Delimiter?,
        val tokenTrees: List<TokenTree>
    ): TokenTree()
}

typealias TokenId = Int

/**
 * Specifies whether there is a space **after** a [TokenTree.Leaf.Punct].
 * The last token is always [Alone]
 */
enum class Spacing {
    Alone, Joint
}

enum class IdentIsRaw(val value: Boolean) {
    No(false) {
        override fun toString(): String {
            return ""
        }
    },
    Yes(true) {
        override fun toString(): String {
            return "r#"
        }
    };

    fun toInt(): Int {
        return when (this) {
            Yes -> 1
            No -> 0
        }
    }

    companion object {
        fun fromBoolean(b: Boolean): IdentIsRaw {
            return if (b) Yes else No
        }
    }
}

enum class LitKind {
    Byte,
    Char,
    Integer,
    Float,
    Str,
    StrRaw,
    ByteStr,
    ByteStrRaw,
    CStr,
    CStrRaw,
    Err;

    companion object {
        fun fromInt(i: Int): LitKind {
            return when (i) {
                0 -> Err
                1 -> Byte
                2 -> Char
                3 -> Integer
                4 -> Float
                5 -> Str
                6 -> StrRaw
                7 -> ByteStr
                8 -> ByteStrRaw
                9 -> CStr
                10 -> CStrRaw
                else -> Err
            }
        }
    }
}

data class Delimiter(
    val id: TokenId,
    val kind: MacroBraces
)

fun TokenTree.toDebugString(): String {
    val sb = StringBuilder()
    debugPrintTokenTree(sb, 0)
    return sb.toString()
}

private fun TokenTree.debugPrintTokenTree(sb: StringBuilder, level: Int) {
    sb.append("  ".repeat(level)) // Alignment
    when (this) {
        is TokenTree.Leaf -> debugPrintLeaf(sb)
        is TokenTree.Subtree -> debugPrintSubtree(sb, level)
    }
}

private fun TokenTree.Subtree.debugPrintSubtree(sb: StringBuilder, level: Int) {
    val aux = if (delimiter == null) {
        "$"
    } else {
        "${delimiter.kind.openText}${delimiter.kind.closeText} ${delimiter.id}"
    }
    sb.append("SUBTREE $aux")
    for (tokenTree in this.tokenTrees) {
        sb.append("\n")
        tokenTree.debugPrintTokenTree(sb, level + 1)
    }
}

private fun TokenTree.Leaf.debugPrintLeaf(sb: StringBuilder) {
    when (this) {
        is TokenTree.Leaf.Literal -> sb.append("LITERAL $text $id")
        is TokenTree.Leaf.Punct -> sb.append("PUNCT   $char [${spacing.toString().lowercase()}] $id")
        is TokenTree.Leaf.Ident -> sb.append("IDENT   $isRaw$text $id")
    }
}
