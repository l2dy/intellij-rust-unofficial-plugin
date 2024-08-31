/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.resolve.KnownItems
import org.rust.lang.core.stubs.RsPlaceholderStub
import org.rust.lang.core.stubs.RsUnaryExprStub
import org.rust.stdext.buildList

enum class UnaryOperator {
    REF, // `&a`
    REF_MUT, // `&mut a`
    DEREF, // `*a`
    MINUS, // `-a`
    NOT, // `!a`
    BOX, // `box a`,
    RAW_REF_CONST, // &raw const
    RAW_REF_MUT // &raw mut
}

val RsUnaryExpr.operatorType: UnaryOperator
    get() {
        val stub = greenStub as? RsUnaryExprStub
        if (stub != null) return stub.operatorType
        return when {
            raw != null -> when {
                const != null -> UnaryOperator.RAW_REF_CONST
                mut != null -> UnaryOperator.RAW_REF_MUT
                else -> error("Unknown unary operator type: `$text`")
            }
            mut != null -> UnaryOperator.REF_MUT
            and != null -> UnaryOperator.REF
            mul != null -> UnaryOperator.DEREF
            minus != null -> UnaryOperator.MINUS
            excl != null -> UnaryOperator.NOT
            box != null -> UnaryOperator.BOX
            else -> error("Unknown unary operator type: `$text`")
        }
    }

private val REF_OPERATORS: Set<UnaryOperator> = setOf(UnaryOperator.REF, UnaryOperator.REF_MUT)

/**
 * `x` => `x`
 * `&x` => `x`
 * `&mut x` => `x`
 */
fun RsExpr.unwrapReference(): RsExpr {
    val unwrapped = (this as? RsUnaryExpr)
        ?.takeIf { it.operatorType in REF_OPERATORS }
        ?.expr
    return unwrapped ?: this
}

interface OverloadableBinaryOperator {
    val traitName: String
    val itemName: String
    val fnName: String
    val sign: String

    operator fun component1(): String = traitName
    operator fun component2(): String = itemName
    operator fun component3(): String = fnName
    operator fun component4(): String = sign

    fun findTrait(items: KnownItems): RsTraitItem? =
        items.findLangItem(itemName)

    companion object {
        fun values(): List<OverloadableBinaryOperator> = buildList {
            addAll(ArithmeticOp.values())
            addAll(EqualityOp.values())
            addAll(ComparisonOp.values())
            addAll(ArithmeticAssignmentOp.values())
        }
    }
}

sealed class BinaryOperator

@Suppress("ClassName")
sealed class ArithmeticOp(
    override val traitName: String,
    override val itemName: String,
    override val sign: String
) : BinaryOperator(), OverloadableBinaryOperator {
    object ADD : ArithmeticOp("Add", "add", "+") // `a + b`
    object SUB : ArithmeticOp("Sub", "sub", "-") // `a - b`
    object MUL : ArithmeticOp("Mul", "mul", "*") // `a * b`
    object DIV : ArithmeticOp("Div", "div", "/") // `a / b`
    object REM : ArithmeticOp("Rem", "rem", "%") // `a % b`
    object BIT_AND : ArithmeticOp("BitAnd", "bitand", "&") // `a & b`
    object BIT_OR : ArithmeticOp("BitOr", "bitor", "|") // `a | b`
    object BIT_XOR : ArithmeticOp("BitXor", "bitxor", "^") // `a ^ b`
    object SHL : ArithmeticOp("Shl", "shl", "<<") // `a << b`
    object SHR : ArithmeticOp("Shr", "shr", ">>") // `a >> b

    override val fnName: String get() = itemName

    companion object {
        fun values(): List<ArithmeticOp> = listOf(ADD, SUB, MUL, DIV, REM, BIT_AND, BIT_OR, BIT_XOR, SHL, SHR)
    }
}

sealed class BoolOp : BinaryOperator()

sealed class LogicOp : BoolOp() {
    object AND : LogicOp() // `a && b`
    object OR : LogicOp() // `a || b`
}

sealed class EqualityOp(
    override val sign: String
) : BoolOp(), OverloadableBinaryOperator {
    object EQ : EqualityOp("==") // `a == b`
    object EXCLEQ : EqualityOp("!=") // `a != b`

    override val traitName: String = "PartialEq"
    override val itemName: String = "eq"
    override val fnName: String = "eq"

    override fun findTrait(items: KnownItems): RsTraitItem? = items.PartialEq

    companion object {
        fun values(): List<EqualityOp> = listOf(EQ, EXCLEQ)
    }
}

sealed class ComparisonOp(
    override val sign: String
) : BoolOp(), OverloadableBinaryOperator {
    object LT : ComparisonOp("<") // `a < b`
    object LTEQ : ComparisonOp("<=") // `a <= b`
    object GT : ComparisonOp(">") // `a > b`
    object GTEQ : ComparisonOp(">=") // `a >= b`

    override val traitName: String = "PartialOrd"
    override val itemName: String = "partial_ord"
    override val fnName: String = "partial_cmp"

    override fun findTrait(items: KnownItems): RsTraitItem? = items.PartialOrd

    companion object {
        fun values(): List<ComparisonOp> = listOf(LT, LTEQ, GT, GTEQ)
    }
}

sealed class AssignmentOp : BinaryOperator() {
    object EQ : AssignmentOp() // `a = b`
}

sealed class ArithmeticAssignmentOp(
    override val traitName: String,
    override val itemName: String,
    override val sign: String
) : AssignmentOp(), OverloadableBinaryOperator {
    object ANDEQ : ArithmeticAssignmentOp("BitAndAssign", "bitand_assign", "&=") // `a &= b`
    object OREQ : ArithmeticAssignmentOp("BitOrAssign", "bitor_assign", "|=") // `a |= b`
    object PLUSEQ : ArithmeticAssignmentOp("AddAssign", "add_assign", "+=") // `a += b`
    object MINUSEQ : ArithmeticAssignmentOp("SubAssign", "sub_assign", "-=") // `a -= b`
    object MULEQ : ArithmeticAssignmentOp("MulAssign", "mul_assign", "*=") // `a *= b`
    object DIVEQ : ArithmeticAssignmentOp("DivAssign", "div_assign", "/=") // `a /= b`
    object REMEQ : ArithmeticAssignmentOp("RemAssign", "rem_assign", "%=") // `a %= b`
    object XOREQ : ArithmeticAssignmentOp("BitXorAssign", "bitxor_assign", "^=") // `a ^= b`
    object GTGTEQ : ArithmeticAssignmentOp("ShrAssign", "shr_assign", ">>=") // `a >>= b`
    object LTLTEQ : ArithmeticAssignmentOp("ShlAssign", "shl_assign", "<<=") // `a <<= b`

    override val fnName: String get() = itemName

    companion object {
        fun values(): List<ArithmeticAssignmentOp> = listOf(ANDEQ, OREQ, PLUSEQ, MINUSEQ, MULEQ, DIVEQ, REMEQ, XOREQ, GTGTEQ, LTLTEQ)
    }
}

val ArithmeticAssignmentOp.nonAssignEquivalent: BinaryOperator
    get() = when (this) {
        ArithmeticAssignmentOp.ANDEQ -> LogicOp.AND
        ArithmeticAssignmentOp.OREQ -> LogicOp.OR
        ArithmeticAssignmentOp.PLUSEQ -> ArithmeticOp.ADD
        ArithmeticAssignmentOp.MINUSEQ -> ArithmeticOp.SUB
        ArithmeticAssignmentOp.MULEQ -> ArithmeticOp.MUL
        ArithmeticAssignmentOp.DIVEQ -> ArithmeticOp.DIV
        ArithmeticAssignmentOp.REMEQ -> ArithmeticOp.REM
        ArithmeticAssignmentOp.XOREQ -> ArithmeticOp.BIT_XOR
        ArithmeticAssignmentOp.GTGTEQ -> ArithmeticOp.SHR
        ArithmeticAssignmentOp.LTLTEQ -> ArithmeticOp.SHL
    }

/**
 * Binary operator categories. These categories summarize the behavior
 * with respect to the builtin operations supported.
 * Inspired by rustc
 */
enum class BinOpCategory {
    /** &&, || -- cannot be overridden */
    Shortcircuit,

    /** <<, >> -- when shifting a single integer, rhs can be any integer type. For simd, types must match */
    Shift,

    /** +, -, etc -- takes equal types, produces same type as input, applicable to ints/floats/simd */
    Math,

    /** &, |, ^ -- takes equal types, produces same type as input, applicable to ints/floats/simd/bool */
    Bitwise,

    /** ==, !=, etc -- takes equal types, produces bools, except for simd, which produce the input type */
    Comparison
}

val BinaryOperator.category: BinOpCategory
    get() = when (this) {
        is ArithmeticOp -> when (this) {
            ArithmeticOp.SHL, ArithmeticOp.SHR -> BinOpCategory.Shift

            ArithmeticOp.ADD, ArithmeticOp.SUB, ArithmeticOp.MUL,
            ArithmeticOp.DIV, ArithmeticOp.REM -> BinOpCategory.Math

            ArithmeticOp.BIT_AND, ArithmeticOp.BIT_OR, ArithmeticOp.BIT_XOR -> BinOpCategory.Bitwise
        }
        LogicOp.AND, LogicOp.OR -> BinOpCategory.Shortcircuit

        EqualityOp.EQ, EqualityOp.EXCLEQ, ComparisonOp.LT,
        ComparisonOp.LTEQ, ComparisonOp.GT, ComparisonOp.GTEQ -> BinOpCategory.Comparison

        is ArithmeticAssignmentOp -> nonAssignEquivalent.category
        AssignmentOp.EQ -> error("Cannot take a category for assignment op")
    }

val BinaryOperator.isComparisonOrEq: Boolean get() = this is ComparisonOp || this is EqualityOp

/** Returns `true` if the binary operator takes its arguments by value */
val BinaryOperator.isByValue: Boolean get() = !isComparisonOrEq

val RsBinaryOp.operatorType: BinaryOperator
    get() = when (op) {
        "+" -> ArithmeticOp.ADD
        "-" -> ArithmeticOp.SUB
        "*" -> ArithmeticOp.MUL
        "/" -> ArithmeticOp.DIV
        "%" -> ArithmeticOp.REM
        "&" -> ArithmeticOp.BIT_AND
        "|" -> ArithmeticOp.BIT_OR
        "^" -> ArithmeticOp.BIT_XOR
        "<<" -> ArithmeticOp.SHL
        ">>" -> ArithmeticOp.SHR

        "&&" -> LogicOp.AND
        "||" -> LogicOp.OR

        "==" -> EqualityOp.EQ
        "!=" -> EqualityOp.EXCLEQ

        ">" -> ComparisonOp.GT
        "<" -> ComparisonOp.LT
        "<=" -> ComparisonOp.LTEQ
        ">=" -> ComparisonOp.GTEQ

        "=" -> AssignmentOp.EQ
        "&=" -> ArithmeticAssignmentOp.ANDEQ
        "|=" -> ArithmeticAssignmentOp.OREQ
        "+=" -> ArithmeticAssignmentOp.PLUSEQ
        "-=" -> ArithmeticAssignmentOp.MINUSEQ
        "*=" -> ArithmeticAssignmentOp.MULEQ
        "/=" -> ArithmeticAssignmentOp.DIVEQ
        "%=" -> ArithmeticAssignmentOp.REMEQ
        "^=" -> ArithmeticAssignmentOp.XOREQ
        ">>=" -> ArithmeticAssignmentOp.GTGTEQ
        "<<=" -> ArithmeticAssignmentOp.LTLTEQ

        else -> error("Unknown binary operator type: `$text`")
    }

val RsBinaryExpr.operator: PsiElement get() = binaryOp.operator
val RsBinaryExpr.operatorType: BinaryOperator get() = binaryOp.operatorType

val RsExpr.isInConstContext: Boolean
    get() = classifyConstContext != null

val RsExpr.classifyConstContext: RsConstContextKind?
    get() {
        for (it in contexts) {
            when (it) {
                is RsConstant -> return if (it.isConst) RsConstContextKind.Constant(it) else null
                is RsFunction -> return if (it.isConst) RsConstContextKind.ConstFn(it) else null
                is RsVariantDiscriminant -> return RsConstContextKind.EnumVariantDiscriminant(it.parent as RsEnumVariant)
                is RsExpr -> {
                    when (val parent = it.parent) {
                        is RsArrayType -> if (it == parent.expr) return RsConstContextKind.ArraySize
                        is RsArrayExpr -> if (it == parent.sizeExpr) return RsConstContextKind.ArraySize
                        is RsTypeArgumentList -> return RsConstContextKind.ConstGenericArgument
                    }
                }
                is RsItemElement -> return null
            }
        }

        return null
    }

sealed class RsConstContextKind {
    class Constant(val psi: RsConstant) : RsConstContextKind()
    class ConstFn(val psi: RsFunction) : RsConstContextKind()
    class EnumVariantDiscriminant(val psi: RsEnumVariant) : RsConstContextKind()
    object ArraySize : RsConstContextKind()
    object ConstGenericArgument : RsConstContextKind()
}

val RsExpr.isInUnsafeContext: Boolean
    get() {
        val parent = contexts.find {
            when (it) {
                is RsBlockExpr -> it.isUnsafe
                is RsFunction -> true
                else -> false
            }
        } ?: return false

        return parent is RsBlockExpr || (parent is RsFunction && parent.isActuallyUnsafe)
    }

abstract class RsExprMixin : RsStubbedElementImpl<RsPlaceholderStub<*>>, RsExpr {
    constructor(node: ASTNode) : super(node)
    constructor(stub: RsPlaceholderStub<*>, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    override fun getContext(): PsiElement? = RsExpandedElement.getContextImpl(this)
}

tailrec fun unwrapParenExprs(expr: RsExpr): RsExpr {
    return if (expr is RsParenExpr) {
        val wrapped = expr.expr ?: return expr
        unwrapParenExprs(wrapped)
    } else {
        expr
    }
}

val RsExpr.isAssignBinaryExpr: Boolean
    get() = this is RsBinaryExpr && this.operatorType is AssignmentOp

val RsExpr.isTailExpr: Boolean
    get() {
        val parent = parent
        return parent is RsExprStmt && parent.isTailStmt
    }

val RsExpr.hasSideEffects: Boolean
    get() = when (this) {
        is RsUnitExpr, is RsLitExpr, is RsPathExpr -> false
        is RsParenExpr -> expr?.hasSideEffects ?: false
        is RsCastExpr -> expr.hasSideEffects
        is RsDotExpr -> expr.hasSideEffects || methodCall != null
        is RsTupleExpr -> exprList.any { it.hasSideEffects }
        is RsStructLiteral -> structLiteralBody.expandedFields.any { it.expr?.hasSideEffects ?: false }
        is RsBinaryExpr -> exprList.any { it.hasSideEffects }
        is RsUnaryExpr -> expr?.hasSideEffects ?: false
        else -> true
    }
