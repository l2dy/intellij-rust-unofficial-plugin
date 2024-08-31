/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.dfa

import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.dfa.CFGBuilder.ScopeCFKind.Break
import org.rust.lang.core.dfa.CFGBuilder.ScopeCFKind.Continue
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.regions.Scope
import org.rust.lang.core.types.regions.ScopeTree
import org.rust.lang.core.types.ty.TyNever
import org.rust.lang.core.types.ty.TyPrimitive
import org.rust.lang.core.types.type
import org.rust.lang.utils.Graph
import java.util.*

class CFGBuilder(
    private val regionScopeTree: ScopeTree,
    val graph: Graph<CFGNodeData, CFGEdgeData>,
    val entry: CFGNode,
    val exit: CFGNode,
    private val termination: CFGNode
) : RsVisitor() {
    data class BlockScope(val blockExpr: RsBlockExpr, val breakNode: CFGNode)

    data class LoopScope(val loop: RsLooplikeExpr, val continueNode: CFGNode, val breakNode: CFGNode)

    enum class ScopeCFKind {
        Break, Continue;

        fun select(breakNode: CFGNode, continueNode: CFGNode): CFGNode = when (this) {
            Break -> breakNode
            Continue -> continueNode
        }
    }

    private var result: CFGNode? = null
    private val preds: Deque<CFGNode> = ArrayDeque()
    private val pred: CFGNode get() = preds.peek()
    private val loopScopes: Deque<LoopScope> = ArrayDeque()
    private val breakableBlockScopes: Deque<BlockScope> = ArrayDeque()

    private inline fun finishWith(callable: () -> CFGNode) {
        result = callable()
    }

    private fun finishWith(value: CFGNode) {
        result = value
    }

    private fun finishWithAstNode(element: RsElement, vararg preds: CFGNode) =
        finishWith { addAstNode(element, *preds) }

    private fun finishWithUnreachableNode(end: CFGNode? = null) {
        if (end != null) {
            addTerminationEdge(end)
        }
        finishWith { addUnreachableNode() }
    }

    private inline fun withLoopScope(loopScope: LoopScope, callable: () -> Unit) {
        loopScopes.push(loopScope)
        callable()
        loopScopes.pop()
    }

    private inline fun withBlockScope(blockScope: BlockScope, callable: () -> Unit) {
        breakableBlockScopes.push(blockScope)
        callable()
        breakableBlockScopes.pop()
    }

    private fun addAstNode(element: RsElement, vararg preds: CFGNode): CFGNode =
        addNode(CFGNodeData.AST(element), *preds)

    private fun addDummyNode(vararg preds: CFGNode): CFGNode =
        addNode(CFGNodeData.Dummy, *preds)

    private fun addUnreachableNode(): CFGNode =
        addNode(CFGNodeData.Unreachable)

    private fun addNode(data: CFGNodeData, vararg preds: CFGNode): CFGNode {
        val newNode = graph.addNode(data)
        preds.forEach { addContainedEdge(it, newNode) }
        return newNode
    }

    fun addContainedEdge(source: CFGNode, target: CFGNode) {
        val data = CFGEdgeData(emptyList())
        graph.addEdge(source, target, data)
    }

    private fun addReturningEdge(fromNode: CFGNode) {
        val data = CFGEdgeData(loopScopes.map { it.loop })
        graph.addEdge(fromNode, exit, data)
    }

    private fun addTerminationEdge(fromNode: CFGNode) {
        val data = CFGEdgeData(loopScopes.map { it.loop })
        graph.addEdge(fromNode, termination, data)
    }

    private fun addExitingEdge(fromExpr: RsExpr, fromNode: CFGNode, targetScope: Scope, toNode: CFGNode) {
        val exitingScopes = mutableListOf<RsElement>()
        var scope: Scope = Scope.Node(fromExpr)
        while (scope != targetScope) {
            exitingScopes.add(scope.element)
            scope = regionScopeTree.getEnclosingScope(scope) ?: break
        }
        graph.addEdge(fromNode, toNode, CFGEdgeData(exitingScopes))
    }

    private fun findScopeEdge(expr: RsExpr, label: RsLabel?, kind: ScopeCFKind): Pair<Scope, CFGNode>? {
        if (label != null) {
            val labelDeclaration = label.reference.resolve() ?: return null

            // try to find the corresponding labeled block
            for ((blockExpr, breakNode) in breakableBlockScopes) {
                if (labelDeclaration == blockExpr.labelDecl) {
                    return Pair(Scope.Node(blockExpr), breakNode)
                }
            }
            // if no, try to find the corresponding labeled loop
            for ((loop, continueNode, breakNode) in loopScopes) {
                if (labelDeclaration == loop.labelDecl) {
                    val node = kind.select(breakNode, continueNode)
                    return Pair(Scope.Node(loop), node)
                }
            }
        } else {
            // otherwise, try to find the corresponding loop
            val exprBlock = expr.contexts.filterIsInstance<RsLooplikeExpr>().firstOrNull()?.block

            for ((loop, continueNode, breakNode) in loopScopes) {
                if (loop.block == exprBlock) {
                    val node = kind.select(breakNode, continueNode)
                    return Pair(Scope.Node(loop), node)
                }
            }
        }

        return null
    }

    private fun straightLine(expr: RsExpr, pred: CFGNode, subExprs: List<RsExpr?>): CFGNode {
        val subExprsExit = subExprs.fold(pred) { acc, subExpr -> process(subExpr, acc) }
        return addAstNode(expr, subExprsExit)
    }

    fun process(element: RsElement?, pred: CFGNode): CFGNode {
        if (element == null) return pred

        // Conditionally disabled code should be ignored since it does not affect the execution
        // https://doc.rust-lang.org/reference/expressions.html#expression-attributes
        if (element is RsOuterAttributeOwner && !element.existsAfterExpansionSelf) {
            return pred
        }

        result = null
        val oldPredsSize = preds.size
        preds.push(pred)
        element.accept(this)
        preds.pop()
        assert(preds.size == oldPredsSize)

        return checkNotNull(result) { "Processing ended inconclusively" }
    }

    private fun processSubPats(pat: RsPat, subPats: List<RsPat>): CFGNode {
        val patsExit = subPats.fold(pred) { acc, subPat -> process(subPat, acc) }
        return addAstNode(pat, patsExit)
    }

    private fun processConditionPats(letExpr: RsLetExpr, pred: CFGNode): CFGNode {
        val pats = letExpr.patList ?: return pred
        val conditionExit = addDummyNode()
        for (pat in pats) {
            val patExit = process(pat, pred)
            addContainedEdge(patExit, conditionExit)
        }
        return conditionExit
    }

    private fun processCall(callExpr: RsExpr, funcOrReceiver: RsExpr?, args: List<RsExpr?>): CFGNode {
        val funcOrReceiverExit = process(funcOrReceiver, pred)
        val callExit = straightLine(callExpr, funcOrReceiverExit, args)
        return if (callExpr.type is TyNever) {
            addTerminationEdge(callExit)
            addUnreachableNode()
        } else {
            callExit
        }
    }

    override fun visitBlock(block: RsBlock) {
        val (expandedStmts, tailExpr) = block.expandedStmtsAndTailExpr
        val stmtsExit = expandedStmts.fold(pred) { pred, stmt -> process(stmt, pred) }

        if (tailExpr != null) {
            val exprExit = process(tailExpr, stmtsExit)
            finishWithAstNode(block, exprExit)
        } else {
            finishWithAstNode(block, stmtsExit)
        }
    }

    override fun visitLetDecl(letDecl: RsLetDecl) {
        val initExit = process(letDecl.expr, pred)
        val exit = process(letDecl.pat, initExit)

        val elseBranch = letDecl.letElseBranch
        if (elseBranch != null) {
            val elseBranchExit = process(elseBranch.block, initExit)
            finishWithAstNode(letDecl, exit, elseBranchExit)
        } else {
            finishWithAstNode(letDecl, exit)
        }
    }

    override fun visitLetExpr(letExpr: RsLetExpr) {
        val initExit = process(letExpr.expr, pred)
        val exit = process(letExpr.pat, initExit)

        finishWithAstNode(letExpr, exit)
    }

    override fun visitNamedFieldDecl(fieldDecl: RsNamedFieldDecl) = finishWith(pred)

    override fun visitLabelDecl(labelDecl: RsLabelDecl) = finishWith(pred)

    override fun visitExprStmt(exprStmt: RsExprStmt) {
        val exprExit = process(exprStmt.expr, pred)
        finishWithAstNode(exprStmt, exprExit)
    }

    override fun visitPatIdent(patIdent: RsPatIdent) {
        val subPatExit = process(patIdent.pat, pred)
        val bindingExit = process(patIdent.patBinding, subPatExit)
        finishWithAstNode(patIdent, bindingExit)
    }

    override fun visitPatBinding(patBinding: RsPatBinding) =
        finishWithAstNode(patBinding, pred)

    override fun visitPatRange(patRange: RsPatRange) =
        finishWithAstNode(patRange, pred)

    override fun visitPatConst(patConst: RsPatConst) =
        finishWithAstNode(patConst, pred)

    override fun visitPatWild(patWild: RsPatWild) =
        finishWithAstNode(patWild, pred)

    override fun visitPathExpr(pathExpr: RsPathExpr) =
        finishWithAstNode(pathExpr, pred)

    override fun visitMacroBodyIdent(macroBodyIdent: RsMacroBodyIdent) =
        finishWithAstNode(macroBodyIdent, pred)

    override fun visitMacroExpr(macroExpr: RsMacroExpr) {
        val macroCallExit = process(macroExpr.macroCall, pred)

        if (macroCallExit != pred && macroExpr.type is TyNever) {
            finishWithUnreachableNode(macroCallExit)
        } else {
            finishWith(macroCallExit)
        }
    }

    override fun visitMacroCall(macroCall: RsMacroCall) {
        val subExprsExit = when (val argument = macroCall.macroArgumentElement) {
            is RsExprMacroArgument -> argument.expr?.let { process(it, pred) }
            is RsIncludeMacroArgument -> argument.expr?.let { process(it, pred) }

            is RsConcatMacroArgument -> argument.exprList.fold(pred) { acc, subExpr -> process(subExpr, acc) }
            is RsEnvMacroArgument -> argument.exprList.fold(pred) { acc, subExpr -> process(subExpr, acc) }
            is RsVecMacroArgument -> argument.exprList.fold(pred) { acc, subExpr -> process(subExpr, acc) }

            is RsFormatMacroArgument -> {
                val expansion = macroCall.expansion
                @Suppress("IfThenToElvis")
                if (expansion != null) {
                    expansion.elements.fold(pred) { pred, element -> process(element, pred) }
                } else {
                    argument.formatMacroArgList.map { it.expr }.fold(pred) { acc, subExpr -> process(subExpr, acc) }
                }
            }

            is RsAssertMacroArgument -> {
                listOfNotNull(argument.expr)
                    .plus(argument.formatMacroArgList.map { it.expr })
                    .fold(pred) { acc, subExpr -> process(subExpr, acc) }
            }

            is RsAsmMacroArgument -> {
                // TODO: Handle this case when type inference is implemented for `asm!` macro calls
                null
            }

            is RsMacroArgument -> {
                val expansion = macroCall.expansion
                val expandedElementsExit = expansion?.elements?.fold(pred) { pred, element -> process(element, pred) }
                expandedElementsExit
            }

            null -> null

            else -> error("unreachable")
        }

        val subElementsExit = subExprsExit ?: run {
            val subPathsIdents = PsiTreeUtil.findChildrenOfAnyType(
                macroCall,
                true,
                RsPathExpr::class.java,
                RsMacroBodyIdent::class.java
            )
            subPathsIdents.fold(pred) { acc, subExpr -> process(subExpr, acc) }
        }

        finishWithAstNode(macroCall, subElementsExit)
    }

    override fun visitRangeExpr(rangeExpr: RsRangeExpr) =
        finishWith { straightLine(rangeExpr, pred, rangeExpr.exprList) }

    override fun visitPatTup(patTup: RsPatTup) =
        finishWith { processSubPats(patTup, patTup.patList) }

    override fun visitPatTupleStruct(patTupleStruct: RsPatTupleStruct) =
        finishWith { processSubPats(patTupleStruct, patTupleStruct.patList) }

    override fun visitPatStruct(patStruct: RsPatStruct) {
        val patFieldsExit = patStruct.patFieldList.fold(pred) { acc, patField -> process(patField, acc) }
        finishWithAstNode(patStruct, patFieldsExit)
    }

    override fun visitPatField(patField: RsPatField) {
        val patFieldFull = patField.patFieldFull
        val subPatExit = if (patFieldFull != null) {
            process(patFieldFull.pat, pred)
        } else {
            process(patField.patBinding, pred)
        }
        finishWithAstNode(patField, subPatExit)
    }

    override fun visitPatSlice(patSlice: RsPatSlice) =
        finishWith { processSubPats(patSlice, patSlice.patList) }

    override fun visitBlockExpr(blockExpr: RsBlockExpr) {
        val labelDeclaration = blockExpr.labelDecl
        val isAsync = blockExpr.isAsync
        val exprExit = addAstNode(blockExpr)

        if (labelDeclaration != null) {
            withBlockScope(BlockScope(blockExpr, exprExit)) {
                val stmtsExit = blockExpr.block.stmtList.fold(pred) { pred, stmt -> process(stmt, pred) }
                val blockExprExit = process(blockExpr.block.expandedTailExpr, stmtsExit)
                addContainedEdge(blockExprExit, exprExit)
            }
        } else {
            val blockExit = process(blockExpr.block, pred)
            addContainedEdge(blockExit, exprExit)
        }

        if (isAsync) {
            addContainedEdge(pred, exprExit)
        }
        finishWith(exprExit)
    }

    override fun visitIfExpr(ifExpr: RsIfExpr) {
        //
        //      [pred]             [pred]
        //        |                  |
        //        v 1                v 1
        //      [cond]             [cond]
        //        |                  |
        //       / \                / \
        //      /   \              /   \
        //     v 2   *            v 2   *
        //   [pats]? |          [pats]? |
        //     |     |            |     |
        //     v     v 3          v     |
        //   [then][else]       [then]  |
        //     |     |            |     |
        //     v 4   v 5          v 3   v 4
        //     [ifExpr]          [ifExpr]
        //
        val condition = ifExpr.condition
        val expr = condition?.expr

        val (exprExit, conditionPatsExit) = if (expr is RsLetExpr) {
            val exprExit = process(expr.expr, pred)
            val conditionPatsExit = processConditionPats(expr, exprExit)
            exprExit to conditionPatsExit
        } else {
            val exprExit = process(expr, pred)
            exprExit to exprExit
        }

        val thenExit = process(ifExpr.block, conditionPatsExit)

        val elseBranch = ifExpr.elseBranch

        if (elseBranch != null) {
            val elseBranchBlock = elseBranch.block
            if (elseBranchBlock != null) {
                val elseExit = process(elseBranch.block, exprExit)
                finishWithAstNode(ifExpr, thenExit, elseExit)
            } else {
                val nestedIfExit = process(elseBranch.ifExpr, exprExit)
                finishWithAstNode(ifExpr, thenExit, nestedIfExit)
            }
        } else {
            finishWithAstNode(ifExpr, exprExit, thenExit)
        }
    }

    override fun visitWhileExpr(whileExpr: RsWhileExpr) {
        //
        //         [pred]
        //           |
        //           v 1
        //       [loopback] <--+ 5
        //           |         |
        //           v 2       |
        //   +-----[cond]      |
        //   |       |         |
        //   |       v         |
        //   |      [pat]?     |
        //   |       |         |
        //   |       v 4       |
        //   |     [body] -----+
        //   v 3
        // [whileExpr]
        //
        val loopBack = addDummyNode(pred)
        val whileExprExit = addAstNode(whileExpr)
        val loopScope = LoopScope(whileExpr, loopBack, whileExprExit)

        withLoopScope(loopScope) {
            val condition = whileExpr.condition
            val expr = condition?.expr

            val exprExit = if (expr is RsLetExpr) {
                val exprExit = process(expr.expr, loopBack)
                addContainedEdge(exprExit, whileExprExit)
                processConditionPats(expr, exprExit)
            } else {
                val exprExit = process(expr, loopBack)
                addContainedEdge(exprExit, whileExprExit)
                exprExit
            }

            val bodyExit = process(whileExpr.block, exprExit)

            addContainedEdge(bodyExit, loopBack)
        }

        finishWith(whileExprExit)
    }

    override fun visitLoopExpr(loopExpr: RsLoopExpr) {
        //
        //     [pred]
        //       |
        //       v 1
        //   [loopback] <---+
        //       |      4   |
        //       v 3        |
        //     [body] ------+
        //
        //   [loopExpr] 2
        //
        val loopBack = addDummyNode(pred)
        val exprExit = addAstNode(loopExpr)
        val loopScope = LoopScope(loopExpr, loopBack, exprExit)

        withLoopScope(loopScope) {
            val bodyExit = process(loopExpr.block, loopBack)
            addContainedEdge(bodyExit, loopBack)
        }

        addTerminationEdge(loopBack)
        finishWith(exprExit)
    }

    override fun visitForExpr(forExpr: RsForExpr) {
        //
        //         [pred]
        //           |
        //           v 1
        //         [iter]
        //           |
        //           v 2
        //   +----[loopback]<-+ 5
        //   |        |         |
        //   |        v         |
        //   |      [pat]?      |
        //   |       |          |
        //   |       v 4        |
        //   |     [body] ------+
        //   v 3
        // [forExpr]
        //
        val exprExit = addAstNode(forExpr)
        val iterExprExit = process(forExpr.expr, pred)
        val loopBack = addDummyNode(iterExprExit)
        addContainedEdge(loopBack, exprExit)

        val loopScope = LoopScope(forExpr, loopBack, exprExit)

        withLoopScope(loopScope) {
            val patExit = process(forExpr.pat, loopBack)
            val bodyExit = process(forExpr.block, patExit)
            addContainedEdge(bodyExit, loopBack)
        }

        finishWith(exprExit)
    }

    override fun visitBinaryExpr(binaryExpr: RsBinaryExpr) {
        if (binaryExpr.binaryOp.isLazy) {
            val leftExit = process(binaryExpr.left, pred)
            val rightExit = process(binaryExpr.right, leftExit)
            finishWithAstNode(binaryExpr, leftExit, rightExit)
        } else {
            if (binaryExpr.left.type is TyPrimitive) {
                finishWith { straightLine(binaryExpr, pred, listOf(binaryExpr.left, binaryExpr.right)) }
            } else {
                finishWith { processCall(binaryExpr, binaryExpr.left, listOf(binaryExpr.right)) }
            }
        }
    }

    override fun visitRetExpr(retExpr: RsRetExpr) {
        val valueExit = process(retExpr.expr, pred)
        val returnExit = addAstNode(retExpr, valueExit)
        addReturningEdge(returnExit)
        finishWithUnreachableNode()
    }

    override fun visitBreakExpr(breakExpr: RsBreakExpr) {
        val exprExit = process(breakExpr.expr, pred)
        val (targetScope, breakDestination) = findScopeEdge(breakExpr, breakExpr.label, Break)
            ?: return finishWithUnreachableNode(exprExit)
        val breakExit = addAstNode(breakExpr, exprExit)
        addExitingEdge(breakExpr, breakExit, targetScope, breakDestination)
        finishWithUnreachableNode(breakExit)
    }

    override fun visitContExpr(contExpr: RsContExpr) {
        val contExit = addAstNode(contExpr, pred)
        val (targetScope, contDestination) = findScopeEdge(contExpr, contExpr.label, Continue)
            ?: return finishWithUnreachableNode(contExit)
        addExitingEdge(contExpr, contExit, targetScope, contDestination)
        finishWithUnreachableNode(contExit)
    }

    override fun visitArrayExpr(arrayExpr: RsArrayExpr) =
        finishWith { straightLine(arrayExpr, pred, arrayExpr.exprList) }

    override fun visitCallExpr(callExpr: RsCallExpr) =
        finishWith { processCall(callExpr, callExpr.expr, callExpr.valueArgumentList.exprList) }

    override fun visitIndexExpr(indexExpr: RsIndexExpr) =
        finishWith { processCall(indexExpr, indexExpr.exprList.first(), indexExpr.exprList.drop(1)) }

    override fun visitUnaryExpr(unaryExpr: RsUnaryExpr) =
        finishWith { processCall(unaryExpr, unaryExpr.expr, emptyList()) }

    override fun visitTupleExpr(tupleExpr: RsTupleExpr) =
        finishWith { straightLine(tupleExpr, pred, tupleExpr.exprList) }

    override fun visitStructLiteral(structLiteral: RsStructLiteral) {
        val structLiteralBody = structLiteral.structLiteralBody
        val fields = structLiteralBody.expandedFields
        val fieldsExit = fields.fold(pred) { acc, subExpr -> process(subExpr, acc) }
        val exprExit = process(structLiteralBody.expr, fieldsExit)
        finishWithAstNode(structLiteral, exprExit)
    }

    override fun visitStructLiteralField(field: RsStructLiteralField) {
        val exprExit = process(field.expr, pred)
        finishWithAstNode(field, exprExit)
    }

    override fun visitCastExpr(castExpr: RsCastExpr) =
        finishWith { straightLine(castExpr, pred, listOf(castExpr.expr)) }

    override fun visitDotExpr(dotExpr: RsDotExpr) {
        val methodCall = dotExpr.methodCall
        if (methodCall == null) {
            finishWith { straightLine(dotExpr, pred, listOf(dotExpr.expr)) }
        } else {
            finishWith { processCall(dotExpr, dotExpr.expr, methodCall.valueArgumentList.exprList) }
        }
    }

    override fun visitLitExpr(litExpr: RsLitExpr) = finishWithAstNode(litExpr, pred)

    override fun visitUnitExpr(unitExpr: RsUnitExpr) = finishWithAstNode(unitExpr, pred)

    override fun visitMatchExpr(matchExpr: RsMatchExpr) {
        fun processGuard(guard: RsMatchArmGuard, prevGuards: ArrayDeque<CFGNode>, guardStart: CFGNode): CFGNode {
            val guardExit = process(guard, guardStart)

            prevGuards.forEach { addContainedEdge(it, guardStart) }
            prevGuards.clear()
            prevGuards.push(guardExit)

            return guardExit
        }

        val discriminantExit = process(matchExpr.expr, pred)
        val exprExit = addAstNode(matchExpr)

        val prevGuards = ArrayDeque<CFGNode>()

        val arms = matchExpr.arms
        if (arms.isEmpty()) {
            // The only case where a match with no arms is valid is when the discriminant is
            // an empty type (!, empty enum etc), but the unreachability in this case is handled
            // by the type-based CFG analysis/
            // In all other cases no match arms mean only that the user hasn't typed them yet, so
            // we add a dummy edge to avoid breaking the CFG (otherwise everything after the match
            // would be unreachable).
            addContainedEdge(discriminantExit, exprExit)
        } else arms.forEach { arm ->
            val armExit = addDummyNode()
            val guard = arm.matchArmGuard

            arm.patList.forEach { pat ->
                var patExit = process(pat, discriminantExit)
                if (guard != null) {
                    val guardStart = addDummyNode(patExit)
                    patExit = processGuard(guard, prevGuards, guardStart)
                }
                addContainedEdge(patExit, armExit)
            }

            val bodyExit = process(arm.expr, armExit)
            addContainedEdge(bodyExit, exprExit)
        }

        finishWith(exprExit)
    }

    override fun visitMatchArmGuard(guard: RsMatchArmGuard) {
        // TODO: support `if let guard` feature
        val conditionExit = process(guard.expr, pred)
        finishWithAstNode(guard, conditionExit)
    }

    override fun visitParenExpr(parenExpr: RsParenExpr) {
        val exprExit = process(parenExpr.expr, pred)
        finishWithAstNode(parenExpr, exprExit)
    }

    override fun visitTryExpr(tryExpr: RsTryExpr) {
        val tryExprExit = addAstNode(tryExpr)
        val exprExit = process(tryExpr.expr, pred)
        val checkExpr = addDummyNode(exprExit)
        addReturningEdge(checkExpr)
        addContainedEdge(exprExit, tryExprExit)
        finishWith(tryExprExit)
    }

    //
    //     [pred] -----
    //       |        |
    //       v 1      |
    //    [expr]      |
    //       |        |
    //       v 3      |
    // [lambdaExpr] <-+
    //
    override fun visitLambdaExpr(lambdaExpr: RsLambdaExpr) {
        val exprExit = process(lambdaExpr.expr, pred)
        val lambdaExprExit = addAstNode(lambdaExpr, exprExit)
        addContainedEdge(pred, lambdaExprExit)
        finishWith(lambdaExprExit)
    }

    override fun visitElement(element: RsElement) = finishWith(pred)
}
