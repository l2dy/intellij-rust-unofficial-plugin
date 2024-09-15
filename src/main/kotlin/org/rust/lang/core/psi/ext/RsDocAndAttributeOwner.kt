/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.asNotFake
import org.rust.lang.core.psi.*
import org.rust.lang.core.stubs.RsAttributeOwnerStub
import org.rust.lang.core.stubs.RsFileStub
import org.rust.lang.core.stubs.RsInnerAttrStub
import org.rust.lang.core.stubs.RsMetaItemStub
import org.rust.lang.core.stubs.common.RsAttributeOwnerPsiOrStub
import org.rust.lang.core.stubs.common.RsMetaItemPsiOrStub
import org.rust.lang.utils.evaluation.CfgEvaluator
import org.rust.lang.utils.evaluation.ThreeValuedLogic
import org.rust.openapiext.testAssert
import org.rust.stdext.nextOrNull

interface RsDocAndAttributeOwner : RsElement, NavigatablePsiElement, RsAttributeOwnerPsiOrStub<RsMetaItem> {
    override val rawMetaItems: Sequence<RsMetaItem>
        get() = RsInnerAttributeOwnerRegistry.rawMetaItems(this)
}

interface RsInnerAttributeOwner : RsDocAndAttributeOwner {
    /**
     * Outer attributes are always children of the owning node.
     * In contrast, inner attributes can be either direct
     * children or grandchildren.
     */
    val innerAttrList: List<RsInnerAttr>
        get() = RsInnerAttributeOwnerRegistry.innerAttrs(this)
}

/**
 * An element with attached outer attributes and documentation comments.
 * Such elements should use left edge binder to properly wrap preceding comments.
 *
 * Fun fact: in Rust, documentation comments are a syntactic sugar for attribute syntax.
 *
 * ```
 * /// docs
 * fn foo() {}
 * ```
 *
 * is equivalent to
 *
 * ```
 * #[doc="docs"]
 * fn foo() {}
 * ```
 */
interface RsOuterAttributeOwner : RsDocAndAttributeOwner {
    val outerAttrList: List<RsOuterAttr>
        get() = stubChildrenOfType()

    override val rawOuterMetaItems: Sequence<RsMetaItem>
        get() = outerAttrList.asSequence().map { it.metaItem }
}

object RsInnerAttributeOwnerRegistry {
    private val reg: Map<IElementType, AttrInfo> = hashMapOf(
        RsElementTypes.FOREIGN_MOD_ITEM to AttrInfo.Direct,
        RsElementTypes.MOD_ITEM to AttrInfo.Direct,
        RsFileStub.Type to AttrInfo.Direct,
        RsElementTypes.FUNCTION to AttrInfo.Nested(RsElementTypes.BLOCK),
        RsElementTypes.IMPL_ITEM to AttrInfo.Nested(RsElementTypes.MEMBERS),
        RsElementTypes.TRAIT_ITEM to AttrInfo.Nested(RsElementTypes.MEMBERS),
    )

    private val attrsElementSet = tokenSetOf(RsElementTypes.OUTER_ATTR, RsElementTypes.INNER_ATTR)

    fun innerAttrs(psi: RsInnerAttributeOwner): List<RsInnerAttr> = when (val info = reg[psi.elementType]) {
        AttrInfo.Direct -> psi.stubChildrenOfType()
        is AttrInfo.Nested -> psi.stubChildOfElementType(info.elementType)?.stubChildrenOfType<RsInnerAttr>().orEmpty()
        null -> error("Inner attributes for type $psi are not registered")
    }

    private fun allAttrs(psi: RsDocAndAttributeOwner): Sequence<RsAttr> = when (reg[psi.elementType]) {
        null, AttrInfo.Direct -> {
            psi.stubChildrenOfType<RsAttr>().asSequence()
        }
        is AttrInfo.Nested -> {
            (psi as? RsOuterAttributeOwner)?.outerAttrList.orEmpty().asSequence() +
                (psi as? RsInnerAttributeOwner)?.innerAttrList.orEmpty().asSequence()
        }
    }

    fun rawMetaItems(psi: RsDocAndAttributeOwner): Sequence<RsMetaItem> =
        allAttrs(psi).map { it.metaItem }

    private fun <S> allAttrs(stub: S): Sequence<StubElement<*>> where S : StubBase<*>,
                                                                      S : RsAttributeOwnerStub {
        return when (val info = reg[stub.stubType]) {
            null, AttrInfo.Direct -> {
                stub.childrenStubs.asSequence().filter { it.stubType in attrsElementSet }
            }
            is AttrInfo.Nested -> {
                val childrenStubs = stub.childrenStubs
                val outer = filterOuterAttrs(childrenStubs)
                val inner = childrenStubs
                    .find { it.stubType == info.elementType }
                    ?.childrenStubs
                    ?.asSequence()
                    ?.filterIsInstance<RsInnerAttrStub>()
                    ?: emptySequence()
                outer + inner
            }
        }
    }

    fun <S> rawMetaItems(stub: S): Sequence<RsMetaItemStub> where S : StubBase<*>,
                                                                  S : RsAttributeOwnerStub {
        return allAttrs(stub).mapToMetaItems()
    }

    fun <S> rawOuterMetaItems(stub: S): Sequence<RsMetaItemStub> where S : StubBase<*>,
                                                                       S : RsAttributeOwnerStub {
        return filterOuterAttrs(stub.childrenStubs).mapToMetaItems()
    }

    private fun filterOuterAttrs(childrenStubs: List<StubElement<*>>): Sequence<StubElement<*>> =
        childrenStubs.asSequence().filter { it.stubType == RsElementTypes.OUTER_ATTR }

    private fun Sequence<StubElement<*>>.mapToMetaItems(): Sequence<RsMetaItemStub> =
        mapNotNull { it.findChildStubByType(RsMetaItemStub.Type) }

    private sealed class AttrInfo {
        object Direct : AttrInfo()
        class Nested(val elementType: IElementType) : AttrInfo()
    }
}

fun RsDocAndAttributeOwner.findFirstMetaItem(name: String): RsMetaItem? =
    rawMetaItems.find { it.name == name }

/**
 * Find the first outer attribute with the given identifier.
 */
fun RsOuterAttributeOwner.findOuterAttr(name: String): RsOuterAttr? =
    outerAttrList.find { it.metaItem.name == name }


fun RsOuterAttributeOwner.addOuterAttribute(attribute: Attribute, anchor: PsiElement): RsOuterAttr {
    val attr = RsPsiFactory(project).createOuterAttr(attribute.text)
    return addBefore(attr, anchor) as RsOuterAttr
}

fun RsInnerAttributeOwner.addInnerAttribute(attribute: Attribute, anchor: PsiElement): RsInnerAttr {
    val attr = RsPsiFactory(project).createInnerAttr(attribute.text)
    return addBefore(attr, anchor) as RsInnerAttr
}

data class Attribute(val name: String, val argText: String? = null) {
    val text: String get() = if (argText == null) name else "$name($argText)"
}

inline val RsDocAndAttributeOwner.attributeStub: RsAttributeOwnerStub?
    get() = when (this) {
        is StubBasedPsiElementBase<*> -> greenStub
        is RsFile -> greenStub
        else -> null
    } as? RsAttributeOwnerStub

/**
 * Returns [QueryAttributes] for given PSI element after `#[cfg_attr()]` expansion
 */
val RsDocAndAttributeOwner.queryAttributes: QueryAttributes<RsMetaItem>
    get() = getQueryAttributes(null)

/** [explicitCrate] is passed to avoid resolve triggering */
fun RsDocAndAttributeOwner.getQueryAttributes(
    explicitCrate: Crate?,
    stub: RsAttributeOwnerStub? = attributeStub,
    outerAttrsOnly: Boolean = false
): QueryAttributes<RsMetaItem> {
    return if (stub != null) {
        QueryAttributes(stub.getQueryAttributes(explicitCrate, outerAttrsOnly).metaItems.map { it.psi })
    } else {
        getExpandedAttributesNoStub(explicitCrate, outerAttrsOnly)
    }
}

/** [explicitCrate] is passed to avoid resolve triggering */
fun <T : RsMetaItemPsiOrStub> RsAttributeOwnerPsiOrStub<T>.getQueryAttributes(
    explicitCrate: Crate?,
    stub: RsAttributeOwnerStub?,
    outerAttrsOnly: Boolean = false
): QueryAttributes<T> {
    @Suppress("UNCHECKED_CAST")
    return if (this is RsAttributeOwnerStub) {
        getQueryAttributes(explicitCrate, outerAttrsOnly)
    } else {
        (this as RsDocAndAttributeOwner).getQueryAttributes(explicitCrate, stub, outerAttrsOnly)
    } as QueryAttributes<T>
}

/** [explicitCrate] is passed to avoid resolve triggering */
fun RsAttributeOwnerStub.getQueryAttributes(
    explicitCrate: Crate?,
    outerAttrsOnly: Boolean = false
): QueryAttributes<RsMetaItemStub> {
    return when {
        // No attributes - return empty sequence
        !hasAttrs -> QueryAttributes.empty()

        // No `#[cfg_attr()]` attributes - return attributes without `cfg_attr` expansion
        !hasCfgAttr -> QueryAttributes(if (outerAttrsOnly) rawOuterMetaItems else rawMetaItems)

        // Slow path. There are `#[cfg_attr()]` attributes - expand them and return expanded attributes
        else -> getExpandedAttributesNoStub(explicitCrate, outerAttrsOnly)
    }
}

/** [explicitCrate] is passed to avoid resolve triggering */
private fun <T : RsMetaItemPsiOrStub> RsAttributeOwnerPsiOrStub<T>.getExpandedAttributesNoStub(
    explicitCrate: Crate?,
    outerAttrsOnly: Boolean = false
): QueryAttributes<T> {
    val rawMetaItems = if (outerAttrsOnly) {
        rawOuterMetaItems
    } else {
        rawMetaItems
    }
    if (!CFG_ATTRIBUTES_ENABLED_KEY.asBoolean()) return QueryAttributes(rawMetaItems)
    val crate = explicitCrate ?: containingCrate.asNotFake ?: return QueryAttributes(rawMetaItems)
    val evaluator = CfgEvaluator.forCrate(crate)
    return QueryAttributes(evaluator.expandCfgAttrs(rawMetaItems))
}

fun <T : RsMetaItemPsiOrStub> CfgEvaluator.expandCfgAttrs(rawMetaItems: Sequence<T>): Sequence<T> {
    return rawMetaItems.flatMap {
        if (it.name == "cfg_attr") {
            @Suppress("UNCHECKED_CAST")
            val list = it.metaItemArgsList.iterator() as Iterator<T>
            val condition = list.nextOrNull() ?: return@flatMap emptySequence()
            if (evaluateCondition(condition) != ThreeValuedLogic.False) {
                expandCfgAttrs(list.asSequence())
            } else {
                emptySequence()
            }
        } else {
            sequenceOf(it)
        }
    }
}

fun RsDocAndAttributeOwner.getTraversedRawAttributes(withCfgAttrAttribute: Boolean = false): QueryAttributes<RsMetaItem> {
    return QueryAttributes(rawMetaItems.withFlattenCfgAttrsAttributes(withCfgAttrAttribute))
}

fun <T : RsMetaItemPsiOrStub> Sequence<T>.withFlattenCfgAttrsAttributes(withCfgAttrAttribute: Boolean): Sequence<T> =
    flatMap {
        if (it.name == "cfg_attr") {
            val metaItems = it.metaItemArgsList
            @Suppress("UNCHECKED_CAST")
            val nested = metaItems.asSequence().drop(1) as Sequence<T>
            if (withCfgAttrAttribute) {
                sequenceOf(it) + nested
            } else {
                nested
            }
        } else {
            sequenceOf(it)
        }
    }

val <T : RsMetaItemPsiOrStub> RsAttributeOwnerPsiOrStub<T>.rawAttributes: QueryAttributes<T>
    get() = QueryAttributes(rawMetaItems)

class StubbedAttributeProperty<P, S>(
    private val fromQuery: (QueryAttributes<*>) -> Boolean,
    private val mayHave: (S) -> Boolean
) where P : RsDocAndAttributeOwner,
        P : StubBasedPsiElement<out S>,
        S : StubElement<P>,
        S : RsAttributeOwnerStub {
    fun getByStub(stub: S, crate: Crate?): Boolean {
        if (!stub.hasAttrs) return false

        return if (!mayHave(stub)) {
            // The attribute name doesn't noticed in root attributes (including nested `#[cfg_attr()]`)
            false
        } else {
            // There is such attribute name in root attributes (maybe under `#[cfg_attr()]`)
            if (!stub.hasCfgAttr) {
                // No `#[cfg_attr()]` attributes
                true
            } else {
                // Slow path. There are `#[cfg_attr()]` attributes, so we should expand them
                fromQuery(stub.getExpandedAttributesNoStub(crate))
            }
        }
    }

    fun getByPsi(psi: P): Boolean {
        val stub = psi.greenStub
        return if (stub !== null) {
            getByStub(stub, null)
        } else {
            fromQuery(psi.getExpandedAttributesNoStub(null))
        }
    }

    @Suppress("unused")
    fun getDuringIndexing(psi: P): Boolean =
        fromQuery(psi.getTraversedRawAttributes())
}

/**
 * Allows for easy querying [RsDocAndAttributeOwner] for specific attributes.
 *
 * **Do not instantiate directly**, use [RsDocAndAttributeOwner.queryAttributes] instead.
 */
class QueryAttributes<out T: RsMetaItemPsiOrStub>(
    val metaItems: Sequence<T>
) {
    // #[doc(hidden)]
    val isDocHidden: Boolean get() = hasAttributeWithArg("doc", "hidden")

    // #[cfg(test)], #[cfg(target_has_atomic = "ptr")], #[cfg(all(target_arch = "wasm32", not(target_os = "emscripten")))]
    fun hasCfgAttr(): Boolean =
        hasAttribute("cfg")

    // `#[attributeName]`, `#[attributeName(arg)]`, `#[attributeName = "Xxx"]`
    fun hasAttribute(attributeName: String): Boolean {
        val attrs = attrsByName(attributeName)
        return attrs.any()
    }

    fun hasAnyOfAttributes(vararg names: String): Boolean =
        metaItems.any { it.name in names }

    // `#[attributeName]`
    fun hasAtomAttribute(attributeName: String): Boolean {
        val attrs = attrsByName(attributeName)
        return attrs.any { !it.hasEq && it.metaItemArgs == null }
    }

    // `#[attributeName(arg)]`
    fun hasAttributeWithArg(attributeName: String, arg: String): Boolean {
        val attrs = attrsByName(attributeName)
        return attrs.any { it.metaItemArgsList.any { item -> item.name == arg } }
    }

    // `#[attributeName = "value"]`
    fun hasAttributeWithValue(attributeName: String, value: String): Boolean {
        val attrs = attrsByName(attributeName)
        return attrs.any { it.value == value }
    }

    // `#[attributeName(arg)]`
    fun getFirstArgOfSingularAttribute(attributeName: String): String? {
        return attrsByName(attributeName).singleOrNull()
            ?.metaItemArgsList?.firstOrNull()
            ?.name
    }

    // `#[attributeName(key = "value")]`
    fun hasAttributeWithKeyValue(attributeName: String, key: String, value: String): Boolean {
        val attrs = attrsByName(attributeName)
        return attrs.any {
            it.metaItemArgsList.any { item -> item.name == key && item.value == value }
        }
    }

    fun lookupStringValueForKey(key: String): String? =
        metaItems
            .filter { it.name == key }
            .mapNotNull { it.value }
            .singleOrNull()

    // #[lang = "copy"]
    val langAttribute: String?
        get() = getStringAttributes("lang").firstOrNull()

    // #[lang = "copy"]
    val langAttributes: Sequence<String>
        get() = getStringAttributes("lang")

    // #[derive(Clone)], #[derive(Copy, Clone, Debug)]
    val deriveAttributes: Sequence<T>
        get() = attrsByName("derive")

    // #[repr(u16)], #[repr(C, packed)], #[repr(simd)], #[repr(align(8))]
    val reprAttributes: Sequence<T>
        get() = attrsByName("repr")

    // #[deprecated(since, note)]
    val deprecatedAttribute: T?
        get() = attrsByName("deprecated").firstOrNull()

    val cfgAttributes: Sequence<T>
        get() = attrsByName("cfg")

    val unstableAttributes: Sequence<T>
        get() = attrsByName("unstable")

    // `#[attributeName = "Xxx"]`
    private fun getStringAttributes(attributeName: String): Sequence<String> =
        attrsByName(attributeName).mapNotNull { it.value }

    /**
     * Get a sequence of all attributes named [name]
     */
    fun attrsByName(name: String): Sequence<T> = metaItems.filter { it.name == name }

    override fun toString(): String =
        "QueryAttributes(${metaItems.joinToString {
            when (it) {
                is RsMetaItem -> it.text
                is RsMetaItemStub -> it.psi.text
                else -> error("unreachable")
            }
        }})"

    companion object {
        private val EMPTY: QueryAttributes<*> = QueryAttributes(emptySequence())

        @Suppress("UNCHECKED_CAST")
        fun <T : RsMetaItemPsiOrStub> empty(): QueryAttributes<T> = EMPTY as QueryAttributes<T>
    }
}

val QueryAttributes<RsMetaItem>.stability: RsStability?
    get() {
        for (meta in metaItems) {
            when (meta.name) {
                "stable" -> return RsStability.Stable
                "unstable" -> return RsStability.Unstable
            }
        }
        return null
    }

/**
 * Checks if there are any #[cfg()] attributes that disable this element
 *
 * HACK: do not check on [RsFile] as [RsFile.queryAttributes] would access the PSI
 */
val RsDocAndAttributeOwner.isEnabledByCfgSelfOrInAttrProcMacroBody: Boolean
    get() = isEnabledByCfgSelfOrInAttrProcMacroBody(null)

fun RsDocAndAttributeOwner.isEnabledByCfgSelfOrInAttrProcMacroBody(crate: Crate?): Boolean {
    if (getCodeStatus(crate) == RsCodeStatus.ATTR_PROC_MACRO_CALL) return true
    return evaluateCfg() != ThreeValuedLogic.False
}

/**
 * Returns `true` if it [isEnabledByCfgSelfOrInAttrProcMacroBody] and is not under attribute procedural macro
 */
val RsDocAndAttributeOwner.existsAfterExpansionSelf: Boolean
    get() = existsAfterExpansionSelf(null)

fun RsDocAndAttributeOwner.existsAfterExpansionSelf(crate: Crate?): Boolean =
    evaluateCfg(crate) != ThreeValuedLogic.False &&
        (this !is RsAttrProcMacroOwner ||
            ProcMacroAttribute.getProcMacroAttribute(this, explicitCrate = crate) !is ProcMacroAttribute.Attr)

fun RsDocAndAttributeOwner.isEnabledByCfgSelf(crate: Crate?): Boolean =
    evaluateCfg(crate) != ThreeValuedLogic.False

val RsDocAndAttributeOwner.isCfgUnknownSelf: Boolean
    get() = evaluateCfg() == ThreeValuedLogic.Unknown

/** [crate] is passed to avoid trigger resolve */
fun RsAttributeOwnerStub.isEnabledByCfgSelf(crate: Crate): Boolean {
    if (!mayHaveCfg) return true
    return evaluateCfg(crate) != ThreeValuedLogic.False
}

/** [crateOrNull] is passed to avoid trigger resolve */
fun RsAttributeOwnerPsiOrStub<*>.evaluateCfg(crateOrNull: Crate? = null): ThreeValuedLogic {
    val lazyEvaluator = if (crateOrNull != null) LazyCfgEvaluator.LazyForCrate(crateOrNull) else LazyCfgEvaluator.Lazy
    return evaluateCfg(lazyEvaluator)
}

fun RsAttributeOwnerPsiOrStub<*>.evaluateCfg(lazyEvaluator: LazyCfgEvaluator): ThreeValuedLogic {
    if (!CFG_ATTRIBUTES_ENABLED_KEY.asBoolean()) return ThreeValuedLogic.True

    // We return true because otherwise we have recursion cycle:
    // [RsFile.crate] -> [RsFile.cachedData] -> [RsFile.declaration] ->
    //  -> [RsModDeclItem.resolve] -> [RsFile.isEnabledByCfg] -> [RsFile.crate]
    if (lazyEvaluator is LazyCfgEvaluator.Lazy && this is RsFile) return ThreeValuedLogic.True

    val attributeStub = if (this is RsDocAndAttributeOwner) attributeStub else this as RsAttributeOwnerStub
    if (attributeStub?.mayHaveCfg == false) return ThreeValuedLogic.True

    val evaluator = lazyEvaluator.createEvaluator(this) ?: return ThreeValuedLogic.True // TODO: maybe unknown?

    val rawMetaItems = attributeStub?.rawMetaItems ?: rawMetaItems

    val cfgAttributes = QueryAttributes(
        if (attributeStub?.hasCfgAttr == false) {
            rawMetaItems
        } else {
            evaluator.expandCfgAttrs(rawMetaItems)
        }
    ).cfgAttributes

    return evaluator.evaluate(cfgAttributes)
}

sealed class LazyCfgEvaluator {
    abstract fun createEvaluator(element: RsAttributeOwnerPsiOrStub<*>): CfgEvaluator?

    object Lazy: LazyCfgEvaluator() {
        override fun createEvaluator(element: RsAttributeOwnerPsiOrStub<*>): CfgEvaluator? {
            val crate = element.containingCrate.asNotFake ?: return null
            return CfgEvaluator.forCrate(crate)
        }
    }

    class LazyForCrate(private val crate: Crate): LazyCfgEvaluator() {
        override fun createEvaluator(element: RsAttributeOwnerPsiOrStub<*>): CfgEvaluator {
            return CfgEvaluator.forCrate(crate)
        }
    }

    class NonLazy(private val evaluator: CfgEvaluator): LazyCfgEvaluator() {
        override fun createEvaluator(element: RsAttributeOwnerPsiOrStub<*>): CfgEvaluator {
            return evaluator
        }
    }
}

private val CFG_ATTRIBUTES_ENABLED_KEY = Registry.get("org.rust.lang.cfg.attributes")
