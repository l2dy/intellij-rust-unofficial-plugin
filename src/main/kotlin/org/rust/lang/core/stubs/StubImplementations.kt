/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.stubs

import com.intellij.lang.*
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.StubBuilder
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.impl.source.tree.RecursiveTreeElementWalkingVisitor
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.*
import com.intellij.util.BitUtil
import com.intellij.util.CharTable
import com.intellij.util.diff.FlyweightCapableTreeStructure
import com.intellij.util.io.DataInputOutputUtil.readNullable
import com.intellij.util.io.DataInputOutputUtil.writeNullable
import org.jetbrains.annotations.VisibleForTesting
import org.rust.lang.RsLanguage
import org.rust.lang.core.lexer.RsLexer
import org.rust.lang.core.parser.RustParser
import org.rust.lang.core.parser.RustParserDefinition
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psi.impl.*
import org.rust.lang.core.stubs.BlockMayHaveStubsHeuristic.computeAndCache
import org.rust.lang.core.stubs.BlockMayHaveStubsHeuristic.getAndClearCached
import org.rust.lang.core.stubs.RsAttributeOwnerStub.*
import org.rust.lang.core.stubs.RsAttributeOwnerStub.CommonStubAttrFlags.HAS_ATTRS
import org.rust.lang.core.stubs.RsAttributeOwnerStub.CommonStubAttrFlags.HAS_CFG_ATTR
import org.rust.lang.core.stubs.RsAttributeOwnerStub.CommonStubAttrFlags.MAY_HAVE_CFG
import org.rust.lang.core.stubs.RsAttributeOwnerStub.CommonStubAttrFlags.MAY_HAVE_CUSTOM_ATTRS
import org.rust.lang.core.stubs.RsAttributeOwnerStub.CommonStubAttrFlags.MAY_HAVE_CUSTOM_DERIVE
import org.rust.lang.core.stubs.RsAttributeOwnerStub.FileStubAttrFlags.MAY_HAVE_RECURSION_LIMIT
import org.rust.lang.core.stubs.RsAttributeOwnerStub.FileStubAttrFlags.MAY_HAVE_STDLIB_ATTRIBUTES
import org.rust.lang.core.stubs.RsAttributeOwnerStub.FunctionStubAttrFlags.MAY_BE_PROC_MACRO_DEF
import org.rust.lang.core.stubs.RsAttributeOwnerStub.ConstantStubAttrFlags
import org.rust.lang.core.stubs.RsAttributeOwnerStub.ModStubAttrFlags.MAY_HAVE_MACRO_USE
import org.rust.lang.core.stubs.RsAttributeOwnerStub.UseItemStubAttrFlags.MAY_HAVE_PRELUDE_IMPORT
import org.rust.lang.core.stubs.RsEmptyStmtType.shouldCreateStub
import org.rust.lang.core.stubs.common.RsMetaItemArgsPsiOrStub
import org.rust.lang.core.stubs.common.RsMetaItemPsiOrStub
import org.rust.lang.core.stubs.common.RsPathPsiOrStub
import org.rust.lang.core.types.ty.TyFloat
import org.rust.lang.core.types.ty.TyInteger
import org.rust.openapiext.ancestors
import org.rust.stdext.*
import org.rust.stdext.BitFlagsBuilder.Limit.BYTE
import org.rust.stdext.BitFlagsBuilder.Limit.INT

class RsFileStub(
    file: RsFile?,
    private val flags: Int,
) : PsiFileStubImpl<RsFile>(file), RsAttributeOwnerStub {

    val mayHaveStdlibAttributes: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_STDLIB_ATTRIBUTES)
    val mayHaveRecursionLimitAttribute: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_RECURSION_LIMIT)

    override val rawMetaItems: Sequence<RsMetaItemStub>
        get() = RsInnerAttributeOwnerRegistry.rawMetaItems(this)

    override val hasAttrs: Boolean
        get() = BitUtil.isSet(flags, HAS_ATTRS)
    override val mayHaveCfg: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_CFG)
    override val hasCfgAttr: Boolean
        get() = BitUtil.isSet(flags, HAS_CFG_ATTR)
    val mayHaveMacroUse: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_MACRO_USE)
    override val mayHaveCustomDerive: Boolean
        get() = false
    override val mayHaveCustomAttrs: Boolean
        get() = false

    override fun getType() = Type

    object Type : IStubFileElementType<RsFileStub>(RsLanguage) {
        // Bump this number if Stub structure changes
        private const val STUB_VERSION = 235

        override fun getStubVersion(): Int =
            RustParserDefinition.PARSER_VERSION + RS_BUILTIN_ATTRIBUTES_VERSION + STUB_VERSION

        override fun getBuilder(): StubBuilder = object : DefaultStubBuilder() {
            override fun createStubForFile(file: PsiFile): StubElement<*> {
                TreeUtil.ensureParsed(file.node) // profiler hint

                // for tests related to rust console
                if (file is RsReplCodeFragment) {
                    return RsFileStub(null, flags = 0)
                }

                check(file is RsFile)
                val flags = RsAttributeOwnerStub.extractFlags(file, FileStubAttrFlags)
                return RsFileStub(file, flags)
            }

            override fun skipChildProcessingWhenBuildingStubs(parent: ASTNode, child: ASTNode): Boolean {
                val elementType = child.elementType
                return elementType == MACRO_ARGUMENT || elementType == MACRO_BODY ||
                    elementType in RS_DOC_COMMENTS ||
                    elementType == BLOCK && parent.elementType == FUNCTION && skipChildForFunctionBody(child)
            }

            /** Note: if returns `true` then [RsBlockStubType.shouldCreateStub] MUST return `false` for the [child] */
            private fun skipChildForFunctionBody(child: ASTNode): Boolean =
                !BlockMayHaveStubsHeuristic.getAndClearCached(child)
        }

        override fun serialize(stub: RsFileStub, dataStream: StubOutputStream) {
            dataStream.writeByte(stub.flags)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsFileStub =
            RsFileStub(null, dataStream.readUnsignedByte())

        override fun getExternalId(): String = "Rust.file"

//        Uncomment to find out what causes switch to the AST
//
//        private val PARESED = com.intellij.util.containers.ContainerUtil.newConcurrentSet<String>()
//        override fun doParseContents(chameleon: ASTNode, psi: com.intellij.psi.PsiElement): ASTNode? {
//            val path = psi.containingFile?.virtualFile?.path
//            if (path != null && PARESED.add(path)) {
//                println("Parsing (${PARESED.size}) $path")
//                val trace = java.io.StringWriter().also { writer ->
//                    Exception().printStackTrace(java.io.PrintWriter(writer))
//                    writer.toString()
//                }
//                println(trace)
//                println()
//            }
//            return super.doParseContents(chameleon, psi)
//        }
    }
}

/**
 * A utility used in the stub builder to detect that a code block [RsBlockStubType] should not be
 * traversed in order to find child stubs. Avoiding code blocks traversing is important because they
 * are lazy parseable and will not be parsed during indexing if not traversed.
 * The utility is heuristic based, i.e. it can strictly say that a block does NOT have stubs, but can't
 * strictly say that block has stubs.
 *
 * The value is used in two places, so there are some tricks with UserData (cache) to avoid double computation.
 * There are two functions that do mostly the same but differs in how they use the cache.
 * - [computeAndCache] computes the value and then puts it in the cache. Used in [RsFunctionStub.Type.createStub]
 * - [getAndClearCached] retrieves and removes the value from the cache or compute the value if it does not
 *   present. Expected to be called after [computeAndCache] during indexing (because a function is a parent of a
 *   block, so it is processed earlier). The value is then removed from the cache because it is no longer needed
 *   and should be invalidated after each PSI change.
 *
 * See tests in `RsCodeBlockStubCreationTest`
 */
private object BlockMayHaveStubsHeuristic {
    private val RS_HAS_ITEMS_OR_ATTRS: Key<Boolean> = Key.create("RS_HAS_ITEMS_OR_ATTRS")

    // TODO remove `USE`
    private val ITEM_DEF_KWS = tokenSetOf(STATIC, ENUM, IMPL, MACRO_KW, MOD, STRUCT, UNION, TRAIT, TYPE_KW, USE)
    private val UNEXPECTED_NEXT_CONST_TOKENS = tokenSetOf(LBRACE, MOVE, OR)

    fun computeAndCache(node: ASTNode): Boolean {
        assertIsBlock(node)
        val hasItemsOrAttrs = computeBlockMayHaveStubs(node)
        node.putUserData(RS_HAS_ITEMS_OR_ATTRS, hasItemsOrAttrs)
        return hasItemsOrAttrs
    }

    /** The value can be already computed and cached by [computeAndCache] */
    fun getAndClearCached(node: ASTNode): Boolean {
        assertIsBlock(node)
        val cachedHasItemsOrAttrs = node.getUserData(RS_HAS_ITEMS_OR_ATTRS)
        if (cachedHasItemsOrAttrs != null) {
            node.putUserData(RS_HAS_ITEMS_OR_ATTRS, null)
            return cachedHasItemsOrAttrs
        }
        return computeBlockMayHaveStubs(node)
    }

    private fun assertIsBlock(node: ASTNode) {
        check(node.elementType == BLOCK) { "Expected block, found: ${node.elementType}, text: `${node.text}`" }
    }

    private fun computeBlockMayHaveStubs(node: ASTNode): Boolean {
        // Use PsiBuilder instead of RsLexer b/c PsiBuilder reuses already lexed tokens.
        // See `RsBlockStubType.reuseCollapsedTokens`
        val b = PsiBuilderFactory.getInstance().createBuilder(node.psi.project, node, null, RsLanguage, node.chars)
        var prevToken: IElementType? = null
        var prevTokenText: String? = null
        while (true) {
            val token = b.tokenType ?: break
            val looksLikeStubElement = token in ITEM_DEF_KWS
                // `const` but not `*const`, `raw const`, const lambdas or `const {}`
                || (token == CONST && !(prevToken == MUL || prevToken == IDENTIFIER && prevTokenText == "raw")
                    && !UNEXPECTED_NEXT_CONST_TOKENS.contains(b.lookAhead(1)))
                // `#!`
                || token == EXCL && prevToken == SHA
                // `macro_rules!`
                || token == EXCL && prevToken == IDENTIFIER && prevTokenText == "macro_rules"
                // `fn foo` (but not `fn()`)
                || token == IDENTIFIER && prevToken == FN
                || token == IDENTIFIER && b.tokenText == "union" && b.lookAhead(1) == IDENTIFIER
            if (looksLikeStubElement) {
                return true
            }
            prevToken = token
            prevTokenText = if (token == IDENTIFIER) b.tokenText else null
            b.advanceLexer()
        }

        return false
    }
}

fun factory(name: String): RsStubElementType<*, *> = when (name) {
    "EXTERN_CRATE_ITEM" -> RsExternCrateItemStub.Type
    "USE_ITEM" -> RsUseItemStub.Type

    "STRUCT_ITEM" -> RsStructItemStub.Type
    "ENUM_ITEM" -> RsEnumItemStub.Type
    "ENUM_BODY" -> RsPlaceholderStub.Type("ENUM_BODY", ::RsEnumBodyImpl)
    "ENUM_VARIANT" -> RsEnumVariantStub.Type

    "MOD_DECL_ITEM" -> RsModDeclItemStub.Type
    "MOD_ITEM" -> RsModItemStub.Type

    "TRAIT_ITEM" -> RsTraitItemStub.Type
    "IMPL_ITEM" -> RsImplItemStub.Type
    "MEMBERS" -> RsPlaceholderStub.Type("MEMBERS", ::RsMembersImpl)
    "TRAIT_ALIAS" -> RsTraitAliasStub.Type
    "TRAIT_ALIAS_BOUNDS" -> RsPlaceholderStub.Type("TRAIT_ALIAS_BOUNDS", ::RsTraitAliasBoundsImpl)

    "FUNCTION" -> RsFunctionStub.Type
    "CONSTANT" -> RsConstantStub.Type
    "TYPE_ALIAS" -> RsTypeAliasStub.Type
    "FOREIGN_MOD_ITEM" -> RsForeignModStub.Type

    "BLOCK_FIELDS" -> RsPlaceholderStub.Type("BLOCK_FIELDS", ::RsBlockFieldsImpl)
    "TUPLE_FIELDS" -> RsPlaceholderStub.Type("TUPLE_FIELDS", ::RsTupleFieldsImpl)
    "TUPLE_FIELD_DECL" -> RsPlaceholderStub.Type("TUPLE_FIELD_DECL", ::RsTupleFieldDeclImpl)
    "NAMED_FIELD_DECL" -> RsNamedFieldDeclStub.Type
    "ALIAS" -> RsAliasStub.Type

    "USE_SPECK" -> RsUseSpeckStub.Type
    "USE_GROUP" -> RsPlaceholderStub.Type("USE_GROUP", ::RsUseGroupImpl)

    "PATH" -> RsPathStub.Type
    "TYPE_QUAL" -> RsPlaceholderStub.Type("TYPE_QUAL", ::RsTypeQualImpl)

    "TRAIT_REF" -> RsPlaceholderStub.Type("TRAIT_REF", ::RsTraitRefImpl)
    "TYPE_REFERENCE" -> RsPlaceholderStub.Type("TYPE_REFERENCE", ::RsTypeReferenceImpl)

    "ARRAY_TYPE" -> RsArrayTypeStub.Type
    "REF_LIKE_TYPE" -> RsRefLikeTypeStub.Type
    "FN_POINTER_TYPE" -> RsFnPointerTypeStub.Type
    "TUPLE_TYPE" -> RsPlaceholderStub.Type("TUPLE_TYPE", ::RsTupleTypeImpl)
    "PAREN_TYPE" -> RsPlaceholderStub.Type("PAREN_TYPE", ::RsParenTypeImpl)
    "UNIT_TYPE" -> RsPlaceholderStub.Type("UNIT_TYPE", ::RsUnitTypeImpl)
    "NEVER_TYPE" -> RsPlaceholderStub.Type("NEVER_TYPE", ::RsNeverTypeImpl)
    "INFER_TYPE" -> RsPlaceholderStub.Type("INFER_TYPE", ::RsInferTypeImpl)
    "PATH_TYPE" -> RsPathTypeStub.Type
    "FOR_IN_TYPE" -> RsPlaceholderStub.Type("FOR_IN_TYPE", ::RsForInTypeImpl)
    "TRAIT_TYPE" -> RsTraitTypeStub.Type
    "MACRO_TYPE" -> RsPlaceholderStub.Type("MACRO_TYPE", ::RsMacroTypeImpl)

    "VALUE_PARAMETER_LIST" -> RsPlaceholderStub.Type("VALUE_PARAMETER_LIST", ::RsValueParameterListImpl)
    "VALUE_PARAMETER" -> RsValueParameterStub.Type
    "SELF_PARAMETER" -> RsSelfParameterStub.Type
    "VARIADIC" -> RsPlaceholderStub.Type("VARIADIC", ::RsVariadicImpl)
    "TYPE_PARAMETER_LIST" -> RsPlaceholderStub.Type("TYPE_PARAMETER_LIST", ::RsTypeParameterListImpl)
    "TYPE_PARAMETER" -> RsTypeParameterStub.Type
    "CONST_PARAMETER" -> RsConstParameterStub.Type
    "LIFETIME" -> RsLifetimeStub.Type
    "LIFETIME_PARAMETER" -> RsLifetimeParameterStub.Type
    "FOR_LIFETIMES" -> RsPlaceholderStub.Type("FOR_LIFETIMES", ::RsForLifetimesImpl)
    "TYPE_ARGUMENT_LIST" -> RsPlaceholderStub.Type("TYPE_ARGUMENT_LIST", ::RsTypeArgumentListImpl)
    "ASSOC_TYPE_BINDING" -> RsPlaceholderStub.Type("ASSOC_TYPE_BINDING", ::RsAssocTypeBindingImpl)

    "TYPE_PARAM_BOUNDS" -> RsPlaceholderStub.Type("TYPE_PARAM_BOUNDS", ::RsTypeParamBoundsImpl)
    "POLYBOUND" -> RsPolyboundStub.Type
    "BOUND" -> RsPlaceholderStub.Type("BOUND", ::RsBoundImpl)
    "WHERE_CLAUSE" -> RsPlaceholderStub.Type("WHERE_CLAUSE", ::RsWhereClauseImpl)
    "WHERE_PRED" -> RsPlaceholderStub.Type("WHERE_PRED", ::RsWherePredImpl)

    "RET_TYPE" -> RsPlaceholderStub.Type("RET_TYPE", ::RsRetTypeImpl)

    "MACRO" -> RsMacroStub.Type
    "MACRO_2" -> RsMacro2Stub.Type
    "MACRO_CALL" -> RsMacroCallStub.Type

    "INCLUDE_MACRO_ARGUMENT" -> RsPlaceholderStub.Type("INCLUDE_MACRO_ARGUMENT", ::RsIncludeMacroArgumentImpl)
    "CONCAT_MACRO_ARGUMENT" -> RsPlaceholderStub.Type("CONCAT_MACRO_ARGUMENT", ::RsConcatMacroArgumentImpl)
    "ENV_MACRO_ARGUMENT" -> RsPlaceholderStub.Type("ENV_MACRO_ARGUMENT", ::RsEnvMacroArgumentImpl)

    "INNER_ATTR" -> RsInnerAttrStub.Type
    "OUTER_ATTR" -> RsPlaceholderStub.Type("OUTER_ATTR", ::RsOuterAttrImpl)

    "META_ITEM" -> RsMetaItemStub.Type
    "META_ITEM_ARGS" -> RsMetaItemArgsStub.Type

    "BLOCK" -> RsBlockStubType

    "BINARY_OP" -> RsBinaryOpStub.Type

    "EXPR_STMT" -> RsExprStmtStub.Type
    "LET_DECL" -> RsLetDeclStub.Type
    "EMPTY_STMT" -> RsEmptyStmtType

    "ARRAY_EXPR" -> RsExprStubType("ARRAY_EXPR", ::RsArrayExprImpl)
    "BINARY_EXPR" -> RsExprStubType("BINARY_EXPR", ::RsBinaryExprImpl)
    "BLOCK_EXPR" -> RsBlockExprStub.Type
    "BREAK_EXPR" -> RsExprStubType("BREAK_EXPR", ::RsBreakExprImpl)
    "CALL_EXPR" -> RsExprStubType("CALL_EXPR", ::RsCallExprImpl)
    "CAST_EXPR" -> RsExprStubType("CAST_EXPR", ::RsCastExprImpl)
    "CONT_EXPR" -> RsExprStubType("CONT_EXPR", ::RsContExprImpl)
    "DOT_EXPR" -> RsExprStubType("DOT_EXPR", ::RsDotExprImpl)
    "FOR_EXPR" -> RsExprStubType("FOR_EXPR", ::RsForExprImpl)
    "IF_EXPR" -> RsExprStubType("IF_EXPR", ::RsIfExprImpl)
    "LET_EXPR" -> RsExprStubType("LET_EXPR", ::RsLetExprImpl)
    "INDEX_EXPR" -> RsExprStubType("INDEX_EXPR", ::RsIndexExprImpl)
    "LAMBDA_EXPR" -> RsExprStubType("LAMBDA_EXPR", ::RsLambdaExprImpl)
    "LIT_EXPR" -> RsLitExprStub.Type
    "LOOP_EXPR" -> RsExprStubType("LOOP_EXPR", ::RsLoopExprImpl)
    "MACRO_EXPR" -> RsExprStubType("MACRO_EXPR", ::RsMacroExprImpl)
    "MATCH_EXPR" -> RsExprStubType("MATCH_EXPR", ::RsMatchExprImpl)
    "PAREN_EXPR" -> RsExprStubType("PAREN_EXPR", ::RsParenExprImpl)
    "PATH_EXPR" -> RsExprStubType("PATH_EXPR", ::RsPathExprImpl)
    "RANGE_EXPR" -> RsExprStubType("RANGE_EXPR", ::RsRangeExprImpl)
    "RET_EXPR" -> RsExprStubType("RET_EXPR", ::RsRetExprImpl)
    "YIELD_EXPR" -> RsExprStubType("YIELD_EXPR", ::RsYieldExprImpl)
    "STRUCT_LITERAL" -> RsExprStubType("STRUCT_LITERAL", ::RsStructLiteralImpl)
    "TRY_EXPR" -> RsExprStubType("TRY_EXPR", ::RsTryExprImpl)
    "TUPLE_EXPR" -> RsExprStubType("TUPLE_EXPR", ::RsTupleExprImpl)
    "UNARY_EXPR" -> RsUnaryExprStub.Type
    "UNIT_EXPR" -> RsExprStubType("UNIT_EXPR", ::RsUnitExprImpl)
    "WHILE_EXPR" -> RsExprStubType("WHILE_EXPR", ::RsWhileExprImpl)
    "UNDERSCORE_EXPR" -> RsExprStubType("UNDERSCORE_EXPR", ::RsUnderscoreExprImpl)
    "PREFIX_INC_EXPR" -> RsExprStubType("PREFIX_INC_EXPR", ::RsPrefixIncExprImpl)
    "POSTFIX_INC_EXPR" -> RsExprStubType("POSTFIX_INC_EXPR", ::RsPostfixIncExprImpl)
    "POSTFIX_DEC_EXPR" -> RsExprStubType("POSTFIX_DEC_EXPR", ::RsPostfixDecExprImpl)

    "INC" -> RsPlaceholderStub.Type("INC", ::RsIncImpl)
    "DEC" -> RsPlaceholderStub.Type("DEC", ::RsDecImpl)

    "VIS" -> RsVisStub.Type
    "VIS_RESTRICTION" -> RsPlaceholderStub.Type("VIS_RESTRICTION", ::RsVisRestrictionImpl)

    "DEFAULT_PARAMETER_VALUE" -> RsPlaceholderStub.Type("DEFAULT_PARAMETER_VALUE", ::RsDefaultParameterValueImpl)

    else -> error("Unknown element $name")
}


abstract class RsAttributeOwnerStubBase<T : RsElement>(
    parent: StubElement<*>?,
    elementType: IStubElementType<*, *>
) : StubBase<T>(parent, elementType),
    RsAttributeOwnerStub {

    override val rawMetaItems: Sequence<RsMetaItemStub>
        get() = RsInnerAttributeOwnerRegistry.rawMetaItems(this)

    override val rawOuterMetaItems: Sequence<RsMetaItemStub>
        get() = RsInnerAttributeOwnerRegistry.rawOuterMetaItems(this)

    override val hasAttrs: Boolean
        get() = BitUtil.isSet(flags, HAS_ATTRS)
    override val mayHaveCfg: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_CFG)
    override val hasCfgAttr: Boolean
        get() = BitUtil.isSet(flags, HAS_CFG_ATTR)
    override val mayHaveCustomDerive: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_CUSTOM_DERIVE)
    override val mayHaveCustomAttrs: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_CUSTOM_ATTRS)
    protected abstract val flags: Int
}

abstract class RsAttrProcMacroOwnerStubBase<T : RsElement>(
    parent: StubElement<*>?,
    elementType: IStubElementType<*, *>
) : RsAttributeOwnerStubBase<T>(parent, elementType), RsAttrProcMacroOwnerStub {
    override val stubbedText: String? get() = procMacroInfo?.stubbedText
    override val stubbedTextHash: HashCode? get() = procMacroInfo?.stubbedTextHash
    override val endOfAttrsOffset: Int get() = procMacroInfo?.endOfAttrsOffset ?: 0
    override val startOffset: Int get() = procMacroInfo?.startOffset ?: 0

    @get:VisibleForTesting
    abstract val procMacroInfo: RsProcMacroStubInfo?
}

class RsExternCrateItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsExternCrateItem>(parent, elementType),
    RsNamedStub {

    val mayHaveMacroUse: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_MACRO_USE)

    val alias: RsAliasStub?
        get() = findChildStubByType(RsAliasStub.Type)

    object Type : RsStubElementType<RsExternCrateItemStub, RsExternCrateItem>("EXTERN_CRATE_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsExternCrateItemStub(
                parentStub,
                this,
                dataStream.readNameAsString()!!,
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsExternCrateItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsExternCrateItemStub) =
            RsExternCrateItemImpl(stub, this)

        override fun createStub(psi: RsExternCrateItem, parentStub: StubElement<*>?): RsExternCrateItemStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi, ModStubAttrFlags)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsExternCrateItemStub(parentStub, this, psi.referenceName, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsExternCrateItemStub, sink: IndexSink) = sink.indexExternCrate(stub)
    }
}


class RsUseItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsUseItem>(parent, elementType) {

    val useSpeck: RsUseSpeckStub?
        get() = findChildStubByType(RsUseSpeckStub.Type)

    // stored in stub as an optimization
    val mayHavePreludeImport: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_PRELUDE_IMPORT)

    object Type : RsStubElementType<RsUseItemStub, RsUseItem>("USE_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsUseItemStub(
                parentStub,
                this,
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsUseItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsUseItemStub) =
            RsUseItemImpl(stub, this)

        override fun createStub(psi: RsUseItem, parentStub: StubElement<*>?): RsUseItemStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi, UseItemStubAttrFlags)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsUseItemStub(parentStub, this, flags, procMacroInfo)
        }
    }
}

class RsUseSpeckStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isStarImport: Boolean,
    /** Needed to distinguish `use {aaa, bbb};` and `use ::{aaa, bbb};` */
    val hasColonColon: Boolean,
) : RsElementStub<RsUseSpeck>(parent, elementType) {

    val path: RsPathStub?
        get() = findChildStubByType(RsPathStub.Type)
    val alias: RsAliasStub?
        get() = findChildStubByType(RsAliasStub.Type)
    val useGroup: StubElement<RsUseGroup>?
        get() = findChildStubByType(RsStubElementTypes.USE_GROUP)

    object Type : RsStubElementType<RsUseSpeckStub, RsUseSpeck>("USE_SPECK") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsUseSpeckStub {
            val flags = dataStream.readUnsignedByte()
            return RsUseSpeckStub(
                parentStub, this,
                BitUtil.isSet(flags, IS_STAR_IMPORT_MASK),
                BitUtil.isSet(flags, HAS_COLON_COLON_MASK),
            )
        }

        override fun serialize(stub: RsUseSpeckStub, dataStream: StubOutputStream) {
            var flags = 0
            flags = BitUtil.set(flags, IS_STAR_IMPORT_MASK, stub.isStarImport)
            flags = BitUtil.set(flags, HAS_COLON_COLON_MASK, stub.hasColonColon)
            dataStream.writeByte(flags)
        }

        override fun createPsi(stub: RsUseSpeckStub) =
            RsUseSpeckImpl(stub, this)

        override fun createStub(psi: RsUseSpeck, parentStub: StubElement<*>?) =
            RsUseSpeckStub(parentStub, this, psi.isStarImport, psi.hasColonColon)
    }

    companion object : BitFlagsBuilder(BYTE) {
        private val IS_STAR_IMPORT_MASK: Int = nextBitMask()
        private val HAS_COLON_COLON_MASK: Int = nextBitMask()
    }
}

class RsStructItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsStructItem>(parent, elementType),
    RsNamedStub  {

    val blockFields: StubElement<RsBlockFields>?
        get() = findChildStubByType(RsStubElementTypes.BLOCK_FIELDS)
    val isUnion: Boolean
        get() = BitUtil.isSet(flags, IS_UNION_MASK)

    object Type : RsStubElementType<RsStructItemStub, RsStructItem>("STRUCT_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsStructItemStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsStructItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsStructItemStub): RsStructItem =
            RsStructItemImpl(stub, this)

        override fun createStub(psi: RsStructItem, parentStub: StubElement<*>?): RsStructItemStub {
            var flags = RsAttributeOwnerStub.extractFlags(psi)
            flags = BitUtil.set(flags, IS_UNION_MASK, psi.kind == RsStructKind.UNION)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsStructItemStub(parentStub, this, psi.name, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsStructItemStub, sink: IndexSink) = sink.indexStructItem(stub)
    }

    companion object : BitFlagsBuilder(CommonStubAttrFlags, BYTE) {
        private val IS_UNION_MASK: Int = nextBitMask()
    }
}


class RsEnumItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsEnumItem>(parent, elementType),
    RsNamedStub {

    val enumBody: StubElement<RsEnumBody>?
        get() = findChildStubByType(RsStubElementTypes.ENUM_BODY)

    object Type : RsStubElementType<RsEnumItemStub, RsEnumItem>("ENUM_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsEnumItemStub =
            RsEnumItemStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsEnumItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsEnumItemStub) =
            RsEnumItemImpl(stub, this)

        override fun createStub(psi: RsEnumItem, parentStub: StubElement<*>?): RsEnumItemStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsEnumItemStub(parentStub, this, psi.name, flags, procMacroInfo)
        }


        override fun indexStub(stub: RsEnumItemStub, sink: IndexSink) = sink.indexEnumItem(stub)

    }
}


class RsEnumVariantStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int
) : RsAttributeOwnerStubBase<RsEnumVariant>(parent, elementType),
    RsNamedStub {

    val blockFields: StubElement<RsBlockFields>?
        get() = findChildStubByType(RsStubElementTypes.BLOCK_FIELDS)

    object Type : RsStubElementType<RsEnumVariantStub, RsEnumVariant>("ENUM_VARIANT") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsEnumVariantStub =
            RsEnumVariantStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte()
            )

        override fun serialize(stub: RsEnumVariantStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
            }

        override fun createPsi(stub: RsEnumVariantStub) =
            RsEnumVariantImpl(stub, this)

        override fun createStub(psi: RsEnumVariant, parentStub: StubElement<*>?) =
            RsEnumVariantStub(parentStub, this, psi.name, RsAttributeOwnerStub.extractFlags(psi))

        override fun indexStub(stub: RsEnumVariantStub, sink: IndexSink) {
            sink.indexEnumVariant(stub)
        }
    }
}


class RsModDeclItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int
) : RsAttributeOwnerStubBase<RsModDeclItem>(parent, elementType),
    RsNamedStub {

    val mayHaveMacroUse: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_MACRO_USE)

    object Type : RsStubElementType<RsModDeclItemStub, RsModDeclItem>("MOD_DECL_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsModDeclItemStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte()
            )

        override fun serialize(stub: RsModDeclItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
            }

        override fun createPsi(stub: RsModDeclItemStub) =
            RsModDeclItemImpl(stub, this)

        override fun createStub(psi: RsModDeclItem, parentStub: StubElement<*>?): RsModDeclItemStub =
            RsModDeclItemStub(parentStub, this, psi.name, RsAttributeOwnerStub.extractFlags(psi, ModStubAttrFlags))

        override fun indexStub(stub: RsModDeclItemStub, sink: IndexSink) = sink.indexModDeclItem(stub)
    }
}


class RsModItemStub(
    parent: StubElement<*>?,
    elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsModItem>(parent, elementType),
    RsNamedStub {

    val mayHaveMacroUse: Boolean
        get() = BitUtil.isSet(flags, MAY_HAVE_MACRO_USE)

    object Type : RsStubElementType<RsModItemStub, RsModItem>("MOD_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsModItemStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsModItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsModItemStub): RsModItem =
            RsModItemImpl(stub, this)

        override fun createStub(psi: RsModItem, parentStub: StubElement<*>?): RsModItemStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi, ModStubAttrFlags)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsModItemStub(parentStub, this, psi.name, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsModItemStub, sink: IndexSink) = sink.indexModItem(stub)
    }
}


class RsTraitItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsTraitItem>(parent, elementType),
    RsNamedStub {

    val isUnsafe: Boolean
        get() = BitUtil.isSet(flags, UNSAFE_MASK)
    val isAuto: Boolean
        get() = BitUtil.isSet(flags, AUTO_MASK)

    object Type : RsStubElementType<RsTraitItemStub, RsTraitItem>("TRAIT_ITEM") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsTraitItemStub {
            return RsTraitItemStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )
        }

        override fun serialize(stub: RsTraitItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsTraitItemStub): RsTraitItem =
            RsTraitItemImpl(stub, this)

        override fun createStub(psi: RsTraitItem, parentStub: StubElement<*>?): RsTraitItemStub {
            var flags = RsAttributeOwnerStub.extractFlags(psi)
            flags = BitUtil.set(flags, UNSAFE_MASK, psi.isUnsafe)
            flags = BitUtil.set(flags, AUTO_MASK, psi.isAuto)

            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsTraitItemStub(parentStub, this, psi.name, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsTraitItemStub, sink: IndexSink) = sink.indexTraitItem(stub)
    }

    companion object : BitFlagsBuilder(CommonStubAttrFlags, BYTE) {
        private val UNSAFE_MASK: Int = nextBitMask()
        private val AUTO_MASK: Int = nextBitMask()
    }
}


class RsImplItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsImplItem>(parent, elementType) {

    val mayBeReservationImpl: Boolean
        get() = BitUtil.isSet(flags, ImplStubAttrFlags.MAY_BE_RESERVATION_IMPL)

    val isNegativeImpl: Boolean
        get() = BitUtil.isSet(flags, NEGATIVE_IMPL_MASK)

    object Type : RsStubElementType<RsImplItemStub, RsImplItem>("IMPL_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsImplItemStub(parentStub, this,
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsImplItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsImplItemStub): RsImplItem =
            RsImplItemImpl(stub, this)

        override fun createStub(psi: RsImplItem, parentStub: StubElement<*>?): RsImplItemStub {
            var flags = RsAttributeOwnerStub.extractFlags(psi, ImplStubAttrFlags)
            flags = BitUtil.set(flags, NEGATIVE_IMPL_MASK, psi.isNegativeImpl)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsImplItemStub(parentStub, this, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsImplItemStub, sink: IndexSink) = sink.indexImplItem(stub)
    }

    companion object : BitFlagsBuilder(ImplStubAttrFlags, BYTE) {
        private val NEGATIVE_IMPL_MASK: Int = nextBitMask()
    }
}


class RsTraitAliasStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsTraitAlias>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsTraitAliasStub, RsTraitAlias>("TRAIT_ALIAS") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTraitAliasStub(parentStub, this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsTraitAliasStub, dataStream: StubOutputStream) {
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }
        }

        override fun createPsi(stub: RsTraitAliasStub): RsTraitAlias =
            RsTraitAliasImpl(stub, this)

        override fun createStub(psi: RsTraitAlias, parentStub: StubElement<*>?): RsTraitAliasStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)

            return RsTraitAliasStub(parentStub, this, psi.name, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsTraitAliasStub, sink: IndexSink) = sink.indexTraitAlias(stub)
    }
}


class RsFunctionStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    val abiName: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsFunction>(parent, elementType),
    RsNamedStub {

    val isAbstract: Boolean get() = BitUtil.isSet(flags, ABSTRACT_MASK)
    val isConst: Boolean get() = BitUtil.isSet(flags, CONST_MASK)
    val isUnsafe: Boolean get() = BitUtil.isSet(flags, UNSAFE_MASK)
    val isExtern: Boolean get() = BitUtil.isSet(flags, EXTERN_MASK)
    val isVariadic: Boolean get() = BitUtil.isSet(flags, VARIADIC_MASK)
    val isSafe: Boolean get() = BitUtil.isSet(flags, SAFE_MASK)
    val isAsync: Boolean get() = BitUtil.isSet(flags, ASYNC_MASK)

    // Method resolve optimization: stub field access is much faster than PSI traversing
    val hasSelfParameters: Boolean get() = BitUtil.isSet(flags, HAS_SELF_PARAMETER_MASK)
    val mayBeProcMacroDef: Boolean get() = BitUtil.isSet(flags, MAY_BE_PROC_MACRO_DEF)

    /** Only for proc macro definitions */
    val preferredBraces: MacroBraces get() = MacroBraces.values()[(flags shr PREFERRED_BRACES) and 3]

    object Type : RsStubElementType<RsFunctionStub, RsFunction>("FUNCTION") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsFunctionStub(
                parentStub,
                this,
                dataStream.readName()?.string,
                dataStream.readUTFFastAsNullable(),
                dataStream.readInt(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsFunctionStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeUTFFastAsNullable(stub.abiName)
                writeInt(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsFunctionStub) =
            RsFunctionImpl(stub, this)

        override fun createStub(psi: RsFunction, parentStub: StubElement<*>?): RsFunctionStub {
            val block = psi.block
            val useInnerAttrs = block != null && ((block.node as LazyParseableElement).isParsed ||
                BlockMayHaveStubsHeuristic.computeAndCache(block.node))
            val attrs = if (useInnerAttrs && block != null) {
                TreeUtil.ensureParsed(block.node) // profiler hint
                psi.getTraversedRawAttributes(withCfgAttrAttribute = true)
            } else {
                QueryAttributes(
                    psi.outerAttrList.asSequence()
                        .map { it.metaItem }
                        .withFlattenCfgAttrsAttributes(withCfgAttrAttribute = true)
                )
            }

            var flags = RsAttributeOwnerStub.extractFlags(attrs, FunctionStubAttrFlags)
            flags = BitUtil.set(flags, ABSTRACT_MASK, block == null)
            flags = BitUtil.set(flags, CONST_MASK, psi.isConst)
            flags = BitUtil.set(flags, UNSAFE_MASK, psi.isUnsafe)
            flags = BitUtil.set(flags, EXTERN_MASK, psi.isExtern)
            flags = BitUtil.set(flags, VARIADIC_MASK, psi.isVariadic)
            flags = BitUtil.set(flags, SAFE_MASK, psi.isSafe)
            flags = BitUtil.set(flags, ASYNC_MASK, psi.isAsync)
            flags = BitUtil.set(flags, HAS_SELF_PARAMETER_MASK, psi.hasSelfParameters)

            val preferredBraces = if (BitUtil.isSet(flags, MAY_BE_PROC_MACRO_DEF)) psi.guessPreferredBraces() else MacroBraces.PARENS
            flags = flags or (preferredBraces.ordinal shl PREFERRED_BRACES)

            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)

            return RsFunctionStub(
                parentStub,
                this,
                name = psi.name,
                abiName = psi.literalAbiName,
                flags = flags,
                procMacroInfo = procMacroInfo,
            )
        }

        override fun indexStub(stub: RsFunctionStub, sink: IndexSink) = sink.indexFunction(stub)
    }

    companion object : BitFlagsBuilder(FunctionStubAttrFlags, INT) {
        private val ABSTRACT_MASK: Int = nextBitMask()
        private val CONST_MASK: Int = nextBitMask()
        private val UNSAFE_MASK: Int = nextBitMask()
        private val EXTERN_MASK: Int = nextBitMask()
        private val VARIADIC_MASK: Int = nextBitMask()
        private val ASYNC_MASK: Int = nextBitMask()
        private val HAS_SELF_PARAMETER_MASK: Int = nextBitMask()
        private val PREFERRED_BRACES: Int = run {
            val mask = nextBitMask()
            nextBitMask()  // second bit
            mask.countTrailingZeroBits()
        }
        private val SAFE_MASK: Int = nextBitMask()
    }
}


class RsConstantStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsConstant>(parent, elementType),
    RsNamedStub {

    val isMut: Boolean
        get() = BitUtil.isSet(flags, IS_MUT_MASK)
    val isConst: Boolean
        get() = BitUtil.isSet(flags, IS_CONST_MASK)
    val isSafe: Boolean
        get() = BitUtil.isSet(flags, IS_SAFE_MASK)
    val isUnsafe: Boolean
        get() = BitUtil.isSet(flags, IS_UNSAFE_MASK)

    object Type : RsStubElementType<RsConstantStub, RsConstant>("CONSTANT") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsConstantStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsConstantStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsConstantStub) =
            RsConstantImpl(stub, this)

        override fun createStub(psi: RsConstant, parentStub: StubElement<*>?): RsConstantStub {
            var flags = RsAttributeOwnerStub.extractFlags(psi)
            flags = BitUtil.set(flags, IS_MUT_MASK, psi.isMut)
            flags = BitUtil.set(flags, IS_CONST_MASK, psi.isConst)
            flags = BitUtil.set(flags, IS_SAFE_MASK, psi.isSafe)
            flags = BitUtil.set(flags, IS_UNSAFE_MASK, psi.isUnsafe)

            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)

            return RsConstantStub(parentStub, this, psi.name, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsConstantStub, sink: IndexSink) = sink.indexConstant(stub)
    }

    companion object : BitFlagsBuilder(ConstantStubAttrFlags, INT) {
        private val IS_MUT_MASK: Int = nextBitMask()
        private val IS_CONST_MASK: Int = nextBitMask()
        private val IS_SAFE_MASK: Int = nextBitMask()
        private val IS_UNSAFE_MASK: Int = nextBitMask()
    }
}


class RsTypeAliasStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsTypeAlias>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsTypeAliasStub, RsTypeAlias>("TYPE_ALIAS") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTypeAliasStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsTypeAliasStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsTypeAliasStub) =
            RsTypeAliasImpl(stub, this)

        override fun createStub(psi: RsTypeAlias, parentStub: StubElement<*>?): RsTypeAliasStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsTypeAliasStub(parentStub, this, psi.name, flags, procMacroInfo)
        }

        override fun indexStub(stub: RsTypeAliasStub, sink: IndexSink) = sink.indexTypeAlias(stub)
    }
}


class RsForeignModStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
    val abi: String?,
    val isUnsafe: Boolean
) : RsAttrProcMacroOwnerStubBase<RsForeignModItem>(parent, elementType) {

    object Type : RsStubElementType<RsForeignModStub, RsForeignModItem>("FOREIGN_MOD_ITEM") {

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsForeignModStub(
                parentStub, this,
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
                abi = dataStream.readNameString(),
                isUnsafe = dataStream.readBoolean()
            )

        override fun serialize(stub: RsForeignModStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
                writeName(stub.abi)
                writeBoolean(stub.isUnsafe)
            }

        override fun createPsi(stub: RsForeignModStub) =
            RsForeignModItemImpl(stub, this)

        override fun createStub(psi: RsForeignModItem, parentStub: StubElement<*>?): RsForeignModStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsForeignModStub(
                parentStub, this,
                flags, procMacroInfo,
                abi = psi.abi,
                isUnsafe = psi.isUnsafe
            )
        }
    }
}


class RsNamedFieldDeclStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int
) : RsAttributeOwnerStubBase<RsNamedFieldDecl>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsNamedFieldDeclStub, RsNamedFieldDecl>("NAMED_FIELD_DECL") {
        override fun createPsi(stub: RsNamedFieldDeclStub) =
            RsNamedFieldDeclImpl(stub, this)

        override fun createStub(psi: RsNamedFieldDecl, parentStub: StubElement<*>?) =
            RsNamedFieldDeclStub(parentStub, this, psi.name, RsAttributeOwnerStub.extractFlags(psi))

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsNamedFieldDeclStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte()
            )

        override fun serialize(stub: RsNamedFieldDeclStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
            }

        override fun indexStub(stub: RsNamedFieldDeclStub, sink: IndexSink) = sink.indexNamedFieldDecl(stub)
    }
}


class RsAliasStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?
) : StubBase<RsAlias>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsAliasStub, RsAlias>("ALIAS") {
        override fun createPsi(stub: RsAliasStub) =
            RsAliasImpl(stub, this)

        override fun createStub(psi: RsAlias, parentStub: StubElement<*>?) =
            RsAliasStub(parentStub, this, psi.name)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsAliasStub(
                parentStub,
                this,
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsAliasStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
            }
    }
}


class RsPathStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val referenceName: String?,
    override val hasColonColon: Boolean,
    override val kind: PathKind,
    val startOffset: Int
) : StubBase<RsPath>(parent, elementType), RsPathPsiOrStub {

    override val path: RsPathStub?
        get() = findChildStubByType(Type)

    object Type : RsStubElementType<RsPathStub, RsPath>("PATH") {
        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun createPsi(stub: RsPathStub) =
            RsPathImpl(stub, this)

        override fun createStub(psi: RsPath, parentStub: StubElement<*>?) =
            RsPathStub(parentStub, this, psi.referenceName, psi.hasColonColon, psi.kind, psi.startOffset)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsPathStub(
                parentStub,
                this,
                dataStream.readName()?.string,
                dataStream.readBoolean(),
                dataStream.readEnum(),
                dataStream.readVarInt()
            )

        override fun serialize(stub: RsPathStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.referenceName)
                writeBoolean(stub.hasColonColon)
                writeEnum(stub.kind)
                writeVarInt(stub.startOffset)
            }
    }
}


class RsTypeParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int
) : RsAttributeOwnerStubBase<RsTypeParameter>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsTypeParameterStub, RsTypeParameter>("TYPE_PARAMETER") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTypeParameterStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte()
            )

        override fun serialize(stub: RsTypeParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
            }

        override fun createPsi(stub: RsTypeParameterStub): RsTypeParameter =
            RsTypeParameterImpl(stub, this)

        override fun createStub(psi: RsTypeParameter, parentStub: StubElement<*>?): RsTypeParameterStub {
            return RsTypeParameterStub(parentStub, this, psi.name, RsAttributeOwnerStub.extractFlags(psi))
        }
    }
}

class RsConstParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    override val flags: Int
) : RsAttributeOwnerStubBase<RsTypeParameter>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsConstParameterStub, RsConstParameter>("CONST_PARAMETER") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsConstParameterStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte()
            )

        override fun serialize(stub: RsConstParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeByte(stub.flags)
            }

        override fun createPsi(stub: RsConstParameterStub): RsConstParameter =
            RsConstParameterImpl(stub, this)

        override fun createStub(psi: RsConstParameter, parentStub: StubElement<*>?) =
            RsConstParameterStub(parentStub, this, psi.name, RsAttributeOwnerStub.extractFlags(psi))
    }
}

class RsValueParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val patText: String?,
    override val flags: Int
) : RsAttributeOwnerStubBase<RsValueParameter>(parent, elementType) {

    object Type : RsStubElementType<RsValueParameterStub, RsValueParameter>("VALUE_PARAMETER") {
        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsValueParameterStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUnsignedByte()
            )

        override fun serialize(stub: RsValueParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.patText)
                writeByte(stub.flags)
            }

        override fun createPsi(stub: RsValueParameterStub): RsValueParameter =
            RsValueParameterImpl(stub, this)

        override fun createStub(psi: RsValueParameter, parentStub: StubElement<*>?) =
            RsValueParameterStub(parentStub, this, psi.patText, RsAttributeOwnerStub.extractFlags(psi))
    }
}


class RsSelfParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val flags: Int
) : RsAttributeOwnerStubBase<RsSelfParameter>(parent, elementType) {

    val isMut: Boolean
        get() = BitUtil.isSet(flags, IS_MUT_MASK)
    val isRef: Boolean
        get() = BitUtil.isSet(flags, IS_REF_MASK)
    val isExplicitType: Boolean
        get() = BitUtil.isSet(flags, IS_EXPLICIT_TYPE_MASK)

    object Type : RsStubElementType<RsSelfParameterStub, RsSelfParameter>("SELF_PARAMETER") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsSelfParameterStub(parentStub, this, dataStream.readVarInt())

        override fun serialize(stub: RsSelfParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeVarInt(stub.flags)
            }

        override fun createPsi(stub: RsSelfParameterStub): RsSelfParameter =
            RsSelfParameterImpl(stub, this)

        override fun createStub(psi: RsSelfParameter, parentStub: StubElement<*>?): RsSelfParameterStub {
            var flags = RsAttributeOwnerStub.extractFlags(psi)
            flags = BitUtil.set(flags, IS_MUT_MASK, psi.mutability.isMut)
            flags = BitUtil.set(flags, IS_REF_MASK, psi.isRef)
            flags = BitUtil.set(flags, IS_EXPLICIT_TYPE_MASK, psi.isExplicitType)
            return RsSelfParameterStub(parentStub, this, flags)
        }
    }

    companion object : BitFlagsBuilder(CommonStubAttrFlags, BYTE) {
        private val IS_MUT_MASK: Int = nextBitMask()
        private val IS_REF_MASK: Int = nextBitMask()
        private val IS_EXPLICIT_TYPE_MASK: Int = nextBitMask()
    }
}


class RsRefLikeTypeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isMut: Boolean,
    val isRef: Boolean,
    val isPointer: Boolean
) : StubBase<RsRefLikeType>(parent, elementType) {

    object Type : RsStubElementType<RsRefLikeTypeStub, RsRefLikeType>("REF_LIKE_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsRefLikeTypeStub(
                parentStub,
                this,
                dataStream.readBoolean(),
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsRefLikeTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            dataStream.writeBoolean(stub.isMut)
            dataStream.writeBoolean(stub.isRef)
            dataStream.writeBoolean(stub.isPointer)
        }

        override fun createPsi(stub: RsRefLikeTypeStub) = RsRefLikeTypeImpl(stub, this)

        override fun createStub(psi: RsRefLikeType, parentStub: StubElement<*>?) =
            RsRefLikeTypeStub(
                parentStub,
                this,
                psi.mutability.isMut,
                psi.isRef,
                psi.isPointer
            )
    }
}


class RsFnPointerTypeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val abiName: String?,
    private val flags: Int
) : StubBase<RsFnPointerType>(parent, elementType) {

    val isUnsafe: Boolean get() = BitUtil.isSet(flags, UNSAFE_MASK)
    val isExtern: Boolean get() = BitUtil.isSet(flags, EXTERN_MASK)

    object Type : RsStubElementType<RsFnPointerTypeStub, RsFnPointerType>("FN_POINTER_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsFnPointerTypeStub(
                parentStub,
                this,
                dataStream.readUTFFastAsNullable(),
                dataStream.readUnsignedByte()
            )

        override fun serialize(stub: RsFnPointerTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            dataStream.writeUTFFastAsNullable(stub.abiName)
            dataStream.writeByte(stub.flags)
        }

        override fun createPsi(stub: RsFnPointerTypeStub) = RsFnPointerTypeImpl(stub, this)

        override fun createStub(psi: RsFnPointerType, parentStub: StubElement<*>?): RsFnPointerTypeStub {
            var flags = 0
            flags = BitUtil.set(flags, UNSAFE_MASK, psi.isUnsafe)
            flags = BitUtil.set(flags, EXTERN_MASK, psi.isExtern)

            return RsFnPointerTypeStub(
                parentStub,
                this,
                psi.abiName,
                flags
            )
        }
    }

    companion object : BitFlagsBuilder(CommonStubAttrFlags, BYTE) {
        private val UNSAFE_MASK: Int = nextBitMask()
        private val EXTERN_MASK: Int = nextBitMask()
    }
}


class RsTraitTypeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isImpl: Boolean
) : StubBase<RsTraitType>(parent, elementType) {

    object Type : RsStubElementType<RsTraitTypeStub, RsTraitType>("TRAIT_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsTraitTypeStub(
                parentStub,
                this,
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsTraitTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            writeBoolean(stub.isImpl)
        }

        override fun createPsi(stub: RsTraitTypeStub) = RsTraitTypeImpl(stub, this)

        override fun createStub(psi: RsTraitType, parentStub: StubElement<*>?) =
            RsTraitTypeStub(
                parentStub,
                this,
                psi.isImpl
            )
    }
}

class RsPathTypeStub private constructor(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
) : StubBase<RsPathType>(parent, elementType) {

    object Type : RsStubElementType<RsPathTypeStub, RsPathType>("PATH_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsPathTypeStub(parentStub, this)

        override fun serialize(stub: RsPathTypeStub, dataStream: StubOutputStream) = Unit

        override fun createPsi(stub: RsPathTypeStub) =
            RsPathTypeImpl(stub, this)

        override fun createStub(psi: RsPathType, parentStub: StubElement<*>?) =
            RsPathTypeStub(parentStub, this)
    }
}

class RsArrayTypeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val isSlice: Boolean
) : StubBase<RsArrayType>(parent, elementType) {

    object Type : RsStubElementType<RsArrayTypeStub, RsArrayType>("ARRAY_TYPE") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsArrayTypeStub(parentStub, this, dataStream.readBoolean())

        override fun serialize(stub: RsArrayTypeStub, dataStream: StubOutputStream) = with(dataStream) {
            writeBoolean(stub.isSlice)
        }

        override fun createPsi(stub: RsArrayTypeStub) =
            RsArrayTypeImpl(stub, this)

        override fun createStub(psi: RsArrayType, parentStub: StubElement<*>?) =
            RsArrayTypeStub(parentStub, this, psi.isSlice)
    }
}

class RsLifetimeStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?
) : StubBase<RsLifetime>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsLifetimeStub, RsLifetime>("LIFETIME") {
        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun createPsi(stub: RsLifetimeStub) =
            RsLifetimeImpl(stub, this)

        override fun createStub(psi: RsLifetime, parentStub: StubElement<*>?) =
            RsLifetimeStub(parentStub, this, psi.referenceName)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsLifetimeStub(
                parentStub,
                this,
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsLifetimeStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
            }
    }
}

class RsLifetimeParameterStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?
) : StubBase<RsLifetimeParameter>(parent, elementType),
    RsNamedStub {

    object Type : RsStubElementType<RsLifetimeParameterStub, RsLifetimeParameter>("LIFETIME_PARAMETER") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun createPsi(stub: RsLifetimeParameterStub) =
            RsLifetimeParameterImpl(stub, this)

        override fun createStub(psi: RsLifetimeParameter, parentStub: StubElement<*>?) =
            RsLifetimeParameterStub(parentStub, this, psi.name)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsLifetimeParameterStub(
                parentStub,
                this,
                dataStream.readNameAsString()
            )

        override fun serialize(stub: RsLifetimeParameterStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
            }
    }
}

class RsMacroStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    val macroBody: String?,
    val bodyHash: HashCode?,
    val preferredBraces: MacroBraces,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsMacro>(parent, elementType),
    RsNamedStub {

    // stored in stub as an optimization
    val mayHaveMacroExport: Boolean
        get() = BitUtil.isSet(flags, MacroStubAttrFlags.MAY_HAVE_MACRO_EXPORT)

    // stored in stub as an optimization
    val mayHaveMacroExportLocalInnerMacros: Boolean
        get() = BitUtil.isSet(flags, MacroStubAttrFlags.MAY_HAVE_MACRO_EXPORT_LOCAL_INNER_MACROS)

    // stored in stub as an optimization
    val mayHaveRustcBuiltinMacro: Boolean
        get() = BitUtil.isSet(flags, MacroStubAttrFlags.MAY_HAVE_RUSTC_BUILTIN_MACRO)

    object Type : RsStubElementType<RsMacroStub, RsMacro>("MACRO") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsMacroStub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUTFFastAsNullable(),
                dataStream.readHashCodeNullable(),
                dataStream.readEnum(),
                dataStream.readVarInt(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsMacroStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeUTFFastAsNullable(stub.macroBody)
                writeHashCodeNullable(stub.bodyHash)
                writeEnum(stub.preferredBraces)
                writeVarInt(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsMacroStub): RsMacro =
            RsMacroImpl(stub, this)

        override fun createStub(psi: RsMacro, parentStub: StubElement<*>?): RsMacroStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi, MacroStubAttrFlags)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsMacroStub(
                parentStub,
                this,
                psi.name,
                psi.macroBody?.text,
                psi.bodyHash,
                psi.preferredBraces,
                flags,
                procMacroInfo,
            )
        }

        override fun indexStub(stub: RsMacroStub, sink: IndexSink) = sink.indexMacro(stub)
    }
}

class RsMacro2Stub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    val macroBody: String,
    val bodyHash: HashCode,
    val preferredBraces: MacroBraces,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsMacro2>(parent, elementType),
    RsNamedStub {

    val mayHaveRustcBuiltinMacro: Boolean
        get() = BitUtil.isSet(flags, Macro2StubAttrFlags.MAY_HAVE_RUSTC_BUILTIN_MACRO)

    object Type : RsStubElementType<RsMacro2Stub, RsMacro2>("MACRO_2") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsMacro2Stub(
                parentStub,
                this,
                dataStream.readNameAsString(),
                dataStream.readUTFFast(),
                dataStream.readHashCode(),
                dataStream.readEnum(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsMacro2Stub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeName(stub.name)
                writeUTFFast(stub.macroBody)
                writeHashCode(stub.bodyHash)
                writeEnum(stub.preferredBraces)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsMacro2Stub): RsMacro2 =
            RsMacro2Impl(stub, this)

        override fun createStub(psi: RsMacro2, parentStub: StubElement<*>?): RsMacro2Stub {
            val flags = RsAttributeOwnerStub.extractFlags(psi, Macro2StubAttrFlags)
            val preferredBraces = psi.preferredBraces
            val body = psi.prepareMacroBody()
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsMacro2Stub(
                parentStub,
                this,
                psi.name,
                body,
                HashCode.compute(body),
                preferredBraces,
                flags,
                procMacroInfo
            )
        }

        override fun indexStub(stub: RsMacro2Stub, sink: IndexSink) = sink.indexMacroDef(stub)
    }
}

class RsMacroCallStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val macroBody: String?,
    val bodyHash: HashCode?,
    val bodyStartOffset: Int,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
) : RsAttrProcMacroOwnerStubBase<RsMacroCall>(parent, elementType) {

    val path: RsPathStub
        get() = findChildStubByType(RsPathStub.Type)!! // guaranteed to be non-null by the grammar

    object Type : RsStubElementType<RsMacroCallStub, RsMacroCall>("MACRO_CALL") {
        override fun shouldCreateStub(node: ASTNode): Boolean {
            val parent = node.treeParent.elementType
            return parent in RS_MOD_OR_FILE || parent == MEMBERS ||
                (parent == MACRO_EXPR || parent == MACRO_TYPE || parent == BLOCK) && createStubIfParentIsStub(node)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsMacroCallStub(
                parentStub,
                this,
                dataStream.readUTFFastAsNullable(),
                dataStream.readHashCodeNullable(),
                dataStream.readVarInt(),
                dataStream.readUnsignedByte(),
                RsProcMacroStubInfo.deserialize(dataStream),
            )

        override fun serialize(stub: RsMacroCallStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeUTFFastAsNullable(stub.macroBody)
                writeHashCodeNullable(stub.bodyHash)
                writeVarInt(stub.bodyStartOffset)
                writeByte(stub.flags)
                RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
            }

        override fun createPsi(stub: RsMacroCallStub): RsMacroCall =
            RsMacroCallImpl(stub, this)

        override fun createStub(psi: RsMacroCall, parentStub: StubElement<*>?): RsMacroCallStub {
            val flags = RsAttributeOwnerStub.extractFlags(psi)
            val procMacroInfo = RsAttrProcMacroOwnerStub.extractTextAndOffset(flags, psi)
            return RsMacroCallStub(
                parentStub,
                this,
                psi.macroBody,
                psi.bodyHash,
                psi.bodyTextRange?.startOffset ?: -1,
                flags,
                procMacroInfo,
            )
        }
    }
}

class RsInnerAttrStub(
    parent: StubElement<*>?,
    elementType: IStubElementType<*, *>
) : StubBase<RsInnerAttr>(parent, elementType) {

    object Type : RsStubElementType<RsInnerAttrStub, RsInnerAttr>("INNER_ATTR") {
        override fun shouldCreateStub(node: ASTNode): Boolean =
            node.treeParent.isFunctionBody() || createStubIfParentIsStub(node)

        override fun createPsi(stub: RsInnerAttrStub): RsInnerAttr = RsInnerAttrImpl(stub, this)

        override fun serialize(stub: RsInnerAttrStub, dataStream: StubOutputStream) {}

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsInnerAttrStub =
            RsInnerAttrStub(parentStub, this)

        override fun createStub(psi: RsInnerAttr, parentStub: StubElement<*>?): RsInnerAttrStub =
            RsInnerAttrStub(parentStub, this)

        override fun indexStub(stub: RsInnerAttrStub, sink: IndexSink) {
            sink.indexInnerAttr(stub)
        }
    }
}

class RsMetaItemStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val hasEq: Boolean
) : StubBase<RsMetaItem>(parent, elementType), RsMetaItemPsiOrStub {

    override val path: RsPathStub?
        get() = findChildStubByType(RsPathStub.Type)

    override val value: String?
        get() = (findChildStubByType(RsLitExprStub.Type)?.kind as? RsStubLiteralKind.String)?.value

    override val metaItemArgs: RsMetaItemArgsStub?
        get() = findChildStubByType(RsMetaItemArgsStub.Type)

    object Type : RsStubElementType<RsMetaItemStub, RsMetaItem>("META_ITEM") {
        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun createStub(psi: RsMetaItem, parentStub: StubElement<*>?): RsMetaItemStub =
            RsMetaItemStub(parentStub, this, psi.eq != null)

        override fun createPsi(stub: RsMetaItemStub): RsMetaItem = RsMetaItemImpl(stub, this)

        override fun serialize(stub: RsMetaItemStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeBoolean(stub.hasEq)
            }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsMetaItemStub =
            RsMetaItemStub(parentStub, this, dataStream.readBoolean())

        override fun indexStub(stub: RsMetaItemStub, sink: IndexSink) {
            sink.indexMetaItem(stub)
        }
    }
}

class RsMetaItemArgsStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>
) : StubBase<RsMetaItemArgs>(parent, elementType), RsMetaItemArgsPsiOrStub {

    override val metaItemList: MutableList<RsMetaItemStub>
        get() = childrenStubs.filterIsInstanceTo(mutableListOf())

    object Type : RsStubElementType<RsMetaItemArgsStub, RsMetaItemArgs>("META_ITEM_ARGS") {
        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun createStub(psi: RsMetaItemArgs, parentStub: StubElement<*>?): RsMetaItemArgsStub =
            RsMetaItemArgsStub(parentStub, this)

        override fun createPsi(stub: RsMetaItemArgsStub): RsMetaItemArgs = RsMetaItemArgsImpl(stub, this)

        override fun serialize(stub: RsMetaItemArgsStub, dataStream: StubOutputStream) {}

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsMetaItemArgsStub =
            RsMetaItemArgsStub(parentStub, this)
    }
}

class RsBinaryOpStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val op: String
) : StubBase<RsBinaryOp>(parent, elementType) {
    object Type : RsStubElementType<RsBinaryOpStub, RsBinaryOp>("BINARY_OP") {

        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun serialize(stub: RsBinaryOpStub, dataStream: StubOutputStream) {
            dataStream.writeUTFFast(stub.op)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsBinaryOpStub =
            RsBinaryOpStub(parentStub, this, dataStream.readUTFFast())

        override fun createStub(psi: RsBinaryOp, parentStub: StubElement<*>?): RsBinaryOpStub =
            RsBinaryOpStub(parentStub, this, psi.op)

        override fun createPsi(stub: RsBinaryOpStub): RsBinaryOp = RsBinaryOpImpl(stub, this)
    }
}

/**
 * [IReparseableElementTypeBase] and [ICustomParsingType] are implemented to provide lazy and incremental
 *  parsing of function bodies.
 * [ICompositeElementType] - to create AST of type [LazyParseableElement] in the case of non-lazy parsing
 *  (`if` bodies, `match` arms, etc), just to have the same AST class for all code blocks (I'm not sure
 *  if that makes sense; made just in case)
 * [ILightLazyParseableElementType] is needed to diff trees correctly (see `PsiBuilderImpl.MyComparator`).
 */
object RsBlockStubType : RsPlaceholderStub.Type<RsBlock>("BLOCK", ::RsBlockImpl),
                         ICustomParsingType,
                         ICompositeElementType,
                         IReparseableElementTypeBase,
                         ILightLazyParseableElementType {

    /** Note: must return `false` if [StubBuilder.skipChildProcessingWhenBuildingStubs] returns `true` for the [node] */
    override fun shouldCreateStub(node: ASTNode): Boolean {
        return if (node.treeParent.elementType == FUNCTION) {
            node.findChildByType(Holder.RS_ITEMS_AND_INNER_ATTR) != null || ItemSeekingVisitor.containsItems(node)
        } else {
            createStubIfParentIsStub(node) || node.findChildByType(RS_ITEMS) != null
        }
    }

    // Lazy parsed (function body)
    override fun parse(text: CharSequence, table: CharTable): ASTNode = LazyParseableElement(this, text)

    // Non-lazy case (`if` body, etc).
    override fun createCompositeNode(): ASTNode = LazyParseableElement(this, null)

    override fun parseContents(chameleon: ASTNode): ASTNode? {
        val project = chameleon.treeParent.psi.project
        val builder = PsiBuilderFactory.getInstance().createBuilder(project, chameleon, null, RsLanguage, chameleon.chars)
        parseBlock(builder)
        return builder.treeBuilt.firstChildNode
    }

    override fun parseContents(chameleon: LighterLazyParseableNode): FlyweightCapableTreeStructure<LighterASTNode> {
        val project = chameleon.containingFile?.project ?: error("`containingFile` must not be null: $chameleon")
        val builder = PsiBuilderFactory.getInstance().createBuilder(project, chameleon, null, RsLanguage, chameleon.text)
        parseBlock(builder)
        return builder.lightTree
    }

    private fun parseBlock(builder: PsiBuilder) {
        // Should be `RustParser().parseLight(BLOCK, builder)`, but we don't have parsing rule for `BLOCK`.
        // Here is a copy of `RustParser.parseLight` method with `RustParser.InnerAttrsAndBlock` parser.
        // Note: we can't use `RustParser().parseLight(INNER_ATTRS_AND_BLOCK, builder)` because the root
        // parsed node must be of BLOCK type. Otherwise, tree diff mechanizm works incorrectly
        // (see `BlockSupport.ReparsedSuccessfullyException`)
        val adaptBuilder = GeneratedParserUtilBase.adapt_builder_(BLOCK, builder, RustParser(), RustParser.EXTENDS_SETS_)
        val marker = GeneratedParserUtilBase.enter_section_(adaptBuilder, 0, GeneratedParserUtilBase._COLLAPSE_, null)
        val result = RustParser.InnerAttrsAndBlock(adaptBuilder, 0)
        GeneratedParserUtilBase.exit_section_(adaptBuilder, 0, marker, BLOCK, result, true, GeneratedParserUtilBase.TRUE_CONDITION)
    }

    // Restricted to a function body only because it is well tested case. May be unrestricted to any block in future
    override fun isReparseable(currentNode: ASTNode, newText: CharSequence, fileLanguage: Language, project: Project): Boolean =
        currentNode.treeParent?.elementType == FUNCTION && PsiBuilderUtil.hasProperBraceBalance(newText, RsLexer(), LBRACE, RBRACE)

    // Avoid double lexing
    override fun reuseCollapsedTokens(): Boolean = true

    private object Holder {
        val RS_ITEMS_AND_INNER_ATTR = TokenSet.orSet(RS_ITEMS, tokenSetOf(MACRO, INNER_ATTR))
    }
}

private class ItemSeekingVisitor private constructor() : RecursiveTreeElementWalkingVisitor() {
    private var hasItemsOrAttrs = false

    override fun visitNode(element: TreeElement) {
        val elementType = element.elementType
        if (elementType in RS_ITEMS || elementType == MACRO) {
            hasItemsOrAttrs = true
            stopWalking()
        } else {
            super.visitNode(element)
        }
    }

    companion object {
        fun containsItems(node: ASTNode): Boolean {
            val visitor = ItemSeekingVisitor()
            (node as TreeElement).acceptTree(visitor)
            return visitor.hasItemsOrAttrs
        }
    }
}

class RsExprStmtStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val hasSemicolon: Boolean
) : StubBase<RsExprStmt>(parent, elementType) {
    object Type : RsStubElementType<RsExprStmtStub, RsExprStmt>("EXPR_STMT") {

        override fun shouldCreateStub(node: ASTNode): Boolean = shouldCreateStmtStub(node)

        override fun serialize(stub: RsExprStmtStub, dataStream: StubOutputStream) {
            dataStream.writeBoolean(stub.hasSemicolon)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsExprStmtStub =
            RsExprStmtStub(parentStub, this, dataStream.readBoolean())

        override fun createStub(psi: RsExprStmt, parentStub: StubElement<*>?): RsExprStmtStub =
            RsExprStmtStub(parentStub, this, psi.hasSemicolon)

        override fun createPsi(stub: RsExprStmtStub): RsExprStmt = RsExprStmtImpl(stub, this)
    }
}

class RsLetDeclStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
) : StubBase<RsLetDecl>(parent, elementType) {
    object Type : RsStubElementType<RsLetDeclStub, RsLetDecl>("LET_DECL") {

        override fun shouldCreateStub(node: ASTNode): Boolean = shouldCreateStmtStub(node)

        override fun serialize(stub: RsLetDeclStub, dataStream: StubOutputStream) {}

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsLetDeclStub =
            RsLetDeclStub(parentStub, this)

        override fun createStub(psi: RsLetDecl, parentStub: StubElement<*>?): RsLetDeclStub =
            RsLetDeclStub(parentStub, this)

        override fun createPsi(stub: RsLetDeclStub): RsLetDecl = RsLetDeclImpl(stub, this)
    }
}

/**
 * This is a fake stub type. The actual stub does not exist and can't be created because [shouldCreateStub]
 * always returns `false`. This fake stub is needed in order to conform [RsStmt] signature
 */
object RsEmptyStmtType : RsStubElementType<RsPlaceholderStub<RsEmptyStmt>, RsEmptyStmt>("EMPTY_STMT") {

    override fun shouldCreateStub(node: ASTNode): Boolean = false

    override fun serialize(stub: RsPlaceholderStub<RsEmptyStmt>, dataStream: StubOutputStream) {
        error("EmptyStmtType stub must never be created")
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsPlaceholderStub<RsEmptyStmt> =
        error("EmptyStmtType stub must never be created")

    override fun createStub(psi: RsEmptyStmt, parentStub: StubElement<out PsiElement>?): RsPlaceholderStub<RsEmptyStmt> =
        error("EmptyStmtType stub must never be created")

    override fun createPsi(stub: RsPlaceholderStub<RsEmptyStmt>): RsEmptyStmt =
        error("EmptyStmtType stub must never be created")
}

class RsExprStubType<PsiT : RsElement>(
    debugName: String,
    psiCtor: (RsPlaceholderStub<*>, IStubElementType<*, *>) -> PsiT
) : RsPlaceholderStub.Type<PsiT>(debugName, psiCtor) {
    override fun shouldCreateStub(node: ASTNode): Boolean = shouldCreateExprStub(node)
}

class RsBlockExprStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    private val flags: Int
) : RsPlaceholderStub<RsBlockExpr>(parent, elementType) {
    val isUnsafe: Boolean get() = BitUtil.isSet(flags, UNSAFE_MASK)
    val isAsync: Boolean get() = BitUtil.isSet(flags, ASYNC_MASK)
    val isTry: Boolean get() = BitUtil.isSet(flags, TRY_MASK)
    val isConst: Boolean get() = BitUtil.isSet(flags, CONST_MASK)

    object Type : RsStubElementType<RsBlockExprStub, RsBlockExpr>("BLOCK_EXPR") {

        override fun shouldCreateStub(node: ASTNode): Boolean = shouldCreateExprStub(node)

        override fun serialize(stub: RsBlockExprStub, dataStream: StubOutputStream) {
            dataStream.writeInt(stub.flags)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsBlockExprStub =
            RsBlockExprStub(parentStub, this, dataStream.readInt())

        override fun createStub(psi: RsBlockExpr, parentStub: StubElement<*>?): RsBlockExprStub {
            var flags = 0
            flags = BitUtil.set(flags, UNSAFE_MASK, psi.isUnsafe)
            flags = BitUtil.set(flags, ASYNC_MASK, psi.isAsync)
            flags = BitUtil.set(flags, TRY_MASK, psi.isTry)
            flags = BitUtil.set(flags, CONST_MASK, psi.isConst)
            return RsBlockExprStub(parentStub, this, flags)
        }

        override fun createPsi(stub: RsBlockExprStub): RsBlockExpr = RsBlockExprImpl(stub, this)
    }

    companion object : BitFlagsBuilder(BYTE) {
        private val UNSAFE_MASK: Int = nextBitMask()
        private val ASYNC_MASK: Int = nextBitMask()
        private val TRY_MASK: Int = nextBitMask()
        private val CONST_MASK: Int = nextBitMask()
    }
}

class RsLitExprStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val kind: RsStubLiteralKind?
) : RsPlaceholderStub<RsLitExpr>(parent, elementType) {
    object Type : RsStubElementType<RsLitExprStub, RsLitExpr>("LIT_EXPR") {

        override fun shouldCreateStub(node: ASTNode): Boolean = shouldCreateExprStub(node)

        override fun serialize(stub: RsLitExprStub, dataStream: StubOutputStream) {
            stub.kind.serialize(dataStream)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsLitExprStub =
            RsLitExprStub(parentStub, this, RsStubLiteralKind.deserialize(dataStream))

        override fun createStub(psi: RsLitExpr, parentStub: StubElement<*>?): RsLitExprStub =
            RsLitExprStub(parentStub, this, psi.stubKind)

        override fun createPsi(stub: RsLitExprStub): RsLitExpr = RsLitExprImpl(stub, this)
    }
}

private fun shouldCreateExprStub(node: ASTNode): Boolean {
    val element = node.ancestors.firstOrNull {
        val parent = it.treeParent
        parent?.elementType in RS_ITEMS || parent is FileASTNode
    }
    return element != null && !element.isFunctionBody() && createStubIfParentIsStub(node)
}

private fun shouldCreateStmtStub(node: ASTNode): Boolean {
    return shouldCreateExprStub(node)
        || node.findChildByType(OUTER_ATTR) != null && ItemSeekingVisitor.containsItems(node)
}

private fun ASTNode.isFunctionBody() = this.elementType == BLOCK && treeParent?.elementType == FUNCTION

class RsUnaryExprStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val operatorType: UnaryOperator
) : RsPlaceholderStub<RsUnaryExpr>(parent, elementType) {
    object Type : RsStubElementType<RsUnaryExprStub, RsUnaryExpr>("UNARY_EXPR") {

        override fun shouldCreateStub(node: ASTNode): Boolean = shouldCreateExprStub(node)

        override fun serialize(stub: RsUnaryExprStub, dataStream: StubOutputStream) {
            dataStream.writeEnum(stub.operatorType)
        }

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): RsUnaryExprStub =
            RsUnaryExprStub(parentStub, this, dataStream.readEnum())

        override fun createStub(psi: RsUnaryExpr, parentStub: StubElement<*>?): RsUnaryExprStub =
            RsUnaryExprStub(parentStub, this, psi.operatorType)

        override fun createPsi(stub: RsUnaryExprStub): RsUnaryExpr = RsUnaryExprImpl(stub, this)
    }

}

sealed class RsStubLiteralKind(val kindOrdinal: Int) {
    class Boolean(val value: kotlin.Boolean) : RsStubLiteralKind(0)
    class Char(val value: kotlin.String?, val isByte: kotlin.Boolean) : RsStubLiteralKind(1)
    class String(val value: kotlin.String?, val isByte: kotlin.Boolean, val isCStr: kotlin.Boolean) : RsStubLiteralKind(2)
    class Integer(val value: Long?, val ty: TyInteger?) : RsStubLiteralKind(3)
    class Float(val value: Double?, val ty: TyFloat?) : RsStubLiteralKind(4)

    companion object {
        fun deserialize(dataStream: StubInputStream): RsStubLiteralKind? {
            with(dataStream) {
                return when (readByte().toInt()) {
                    0 -> Boolean(readBoolean())
                    1 -> Char(readUTFFastAsNullable(), readBoolean())
                    2 -> String(readUTFFastAsNullable(), readBoolean(), readBoolean())
                    3 -> Integer(readLongAsNullable(), TyInteger.VALUES.getOrNull(readByte().toInt()))
                    4 -> Float(readDoubleAsNullable(), TyFloat.VALUES.getOrNull(readByte().toInt()))
                    else -> null
                }
            }
        }
    }
}

private fun RsStubLiteralKind?.serialize(dataStream: StubOutputStream) {
    if (this == null) {
        dataStream.writeByte(-1)
        return
    }
    dataStream.writeByte(kindOrdinal)
    when (this) {
        is RsStubLiteralKind.Boolean -> dataStream.writeBoolean(value)
        is RsStubLiteralKind.Char -> {
            dataStream.writeUTFFastAsNullable(value)
            dataStream.writeBoolean(isByte)
        }
        is RsStubLiteralKind.String -> {
            dataStream.writeUTFFastAsNullable(value)
            dataStream.writeBoolean(isByte)
            dataStream.writeBoolean(isCStr)
        }
        is RsStubLiteralKind.Integer -> {
            dataStream.writeLongAsNullable(value)
            dataStream.writeByte(ty?.ordinal ?: -1)
        }
        is RsStubLiteralKind.Float -> {
            dataStream.writeDoubleAsNullable(value)
            dataStream.writeByte(ty?.ordinal ?: -1)
        }
    }
}

class RsPolyboundStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val hasQ: Boolean,
    val hasConst: Boolean
) : StubBase<RsPolybound>(parent, elementType) {

    object Type : RsStubElementType<RsPolyboundStub, RsPolybound>("POLYBOUND") {
        override fun shouldCreateStub(node: ASTNode): Boolean = createStubIfParentIsStub(node)

        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsPolyboundStub(
                parentStub, this,
                dataStream.readBoolean(),
                dataStream.readBoolean()
            )

        override fun serialize(stub: RsPolyboundStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeBoolean(stub.hasQ)
                writeBoolean(stub.hasConst)
            }

        override fun createPsi(stub: RsPolyboundStub): RsPolybound =
            RsPolyboundImpl(stub, this)

        override fun createStub(psi: RsPolybound, parentStub: StubElement<*>?) =
            RsPolyboundStub(parentStub, this, psi.hasQ, psi.hasConst)
    }
}

class RsVisStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    val kind: RsVisStubKind
) : StubBase<RsVis>(parent, elementType) {

    /** Equivalent of `RsVis.visRestriction.path` */
    val visRestrictionPath: RsPathStub?
        get() {
            val visRestriction = findChildStubByType(RsStubElementTypes.VIS_RESTRICTION)
            return visRestriction?.findChildStubByType(RsPathStub.Type)
        }

    object Type : RsStubElementType<RsVisStub, RsVis>("VIS") {
        override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
            RsVisStub(parentStub, this, dataStream.readEnum())

        override fun serialize(stub: RsVisStub, dataStream: StubOutputStream) =
            with(dataStream) {
                writeEnum(stub.kind)
            }

        override fun createPsi(stub: RsVisStub): RsVis =
            RsVisImpl(stub, this)

        override fun createStub(psi: RsVis, parentStub: StubElement<*>?) =
            RsVisStub(parentStub, this, psi.stubKind)
    }
}

private fun StubInputStream.readNameAsString(): String? = readName()?.string
private fun StubInputStream.readUTFFastAsNullable(): String? = readNullable(this, this::readUTFFast)
private fun StubOutputStream.writeUTFFastAsNullable(value: String?) = writeNullable(this, value, this::writeUTFFast)

private fun StubOutputStream.writeLongAsNullable(value: Long?) = writeNullable(this, value, this::writeLong)
private fun StubInputStream.readLongAsNullable(): Long? = readNullable(this, this::readLong)

private fun StubOutputStream.writeDoubleAsNullable(value: Double?) = writeNullable(this, value, this::writeDouble)
private fun StubInputStream.readDoubleAsNullable(): Double? = readNullable(this, this::readDouble)
