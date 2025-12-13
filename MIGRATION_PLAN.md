# Rust 2024 `impl Trait` Precise Capturing Migration Plan

This document outlines the implementation plan for supporting RFC 3498 (Lifetime Capture Rules 2024) and RFC 3617 (`use<..>` precise capturing syntax) in the IntelliJ Rust plugin.

## Executive Summary

| Aspect | Details |
|--------|---------|
| **Feature** | `use<..>` precise capturing syntax and Rust 2024 implicit capture rules |
| **RFCs** | [RFC 3498](https://rust-lang.github.io/rfcs/3498-lifetime-capture-rules-2024.html), [RFC 3617](https://rust-lang.github.io/rfcs/3617-precise-capturing.html) |
| **Branch** | `feature/precise-capturing` |
| **Total Phases** | 7 |

---

## Table of Contents

1. [Background](#background)
2. [Phase 1: Grammar Updates](#phase-1-grammar-updates)
3. [Phase 2: PSI Extensions](#phase-2-psi-extensions)
4. [Phase 3: Type System Updates](#phase-3-type-system-updates)
5. [Phase 4: Type Rendering](#phase-4-type-rendering)
6. [Phase 5: IDE Completion](#phase-5-ide-completion)
7. [Phase 6: Parser Tests](#phase-6-parser-tests)
8. [Phase 7: Type Inference Tests](#phase-7-type-inference-tests)
9. [Dependency Graph](#dependency-graph)
10. [Risk Assessment](#risk-assessment)
11. [Deferred Work](#deferred-work)
12. [Progress Tracking](#progress-tracking)

---

## Background

### What Changed in Rust 2024

**Rust 2021 and earlier (RPIT on bare functions and inherent methods):**
- Type and const parameters are captured implicitly
- Lifetime parameters are captured **only if syntactically mentioned** in the bounds
- Note: `async fn` already captures ALL lifetimes in Rust 2021

**Rust 2024:**
- RPIT captures **all** in-scope generic parameters (lifetimes, types, consts) implicitly
- This makes RPIT consistent with `async fn`, RPITIT, TAIT, ATPIT, and AFIT

**Features that already capture all parameters (all editions):**
- `async fn` - captures all lifetimes and type parameters
- RPITIT (return position impl Trait in trait) - captures all from trait + method
- TAIT (type alias impl Trait) - captures all parameters in the alias
- ATPIT (associated type position impl Trait) - captures all from GAT + impl
- AFIT (async fn in trait) - captures all from trait + method

**New `use<..>` syntax (all editions, RFC 3617):**
- `impl use<'a, T> Trait` - explicitly capture only `'a` and `T`
- `impl use<> Trait` - capture nothing
- `impl use<'_> Trait` - capture elided lifetime
- `impl use<Self> Trait` - capture Self (in trait definitions only)

**In-scope parameters include:**
- Generic parameters from the function/method signature
- Generic parameters from outer `impl` blocks
- Higher-ranked lifetimes from `for<..>` binders on outer opaque types
- Anonymous type parameters from APIT (but cannot be named in `use<..>`)

**Initial stabilization restrictions (Rust 1.82+):**
- If `use<..>` is provided, it must include ALL in-scope type and const parameters
- In RPIT within trait definitions, `use<..>` must include ALL in-scope generics
- These restrictions may be lifted in future Rust versions

**Const generic semantics:**
- Const parameters must be captured to use them in the hidden *type* (e.g., `[(); C]`)
- Const parameters do NOT need to be captured to use them as *values* (e.g., `C + 1`)

### Current Plugin State

- `TyAnon` only stores trait bounds, no lifetime/region information
- Grammar parses `+ 'a` bounds but they're discarded for `impl Trait`
- No `use<..>` syntax support exists

---

## Phase 1: Grammar Updates

### Objective
Add `use<..>` syntax to the parser grammar.

### Files to Modify

| File | Action |
|------|--------|
| `src/main/grammars/RustParser.bnf` | Add grammar rules |

### Detailed Changes

**1.1 Add new PSI element definitions** (after line ~1068):

```bnf
UseBoundsClause ::= use '<' [ UseBoundsElement (',' UseBoundsElement)* ','? ] '>' {
  extends = "org.rust.lang.core.psi.ext.RsStubbedElementImpl<?>"
  stubClass = "org.rust.lang.core.stubs.RsPlaceholderStub<?>"
  elementTypeFactory = "org.rust.lang.core.stubs.StubImplementationsKt.factory"
}

UseBoundsElement ::= Lifetime | identifier | 'Self' {
  extends = "org.rust.lang.core.psi.ext.RsStubbedElementImpl<?>"
  stubClass = "org.rust.lang.core.stubs.RsPlaceholderStub<?>"
  elementTypeFactory = "org.rust.lang.core.stubs.StubImplementationsKt.factory"
}
```

**1.2 Modify ExplicitTraitTypeInner** (line 1076):

```bnf
// Before
private ExplicitTraitTypeInner ::= (impl | dyn) Polybound ('+' Polybound)* { pin = 1 }

// After
private ExplicitTraitTypeInner ::= (impl | dyn) UseBoundsClause? Polybound ('+' Polybound)* { pin = 1 }
```

### Commands to Run

```bash
./gradlew :generateParser
./gradlew :generateLexer  # If lexer changes needed
```

### Success Criteria

- [ ] Parser accepts `impl use<'a, T> Trait` syntax
- [ ] Parser accepts `impl use<> Trait` (empty)
- [ ] Parser accepts trailing comma: `impl use<'a,> Trait`
- [ ] Parser rejects `dyn use<> Trait` (validation deferred)
- [ ] No regressions in existing parser tests

### Estimated Effort
**Small** - Grammar changes are straightforward

### Checkpoint
Run: `./gradlew :test --tests "org.rust.lang.core.parser.RsParserTest"`

---

## Phase 2: PSI Extensions

### Objective
Create PSI extension files for accessing `use<..>` clause elements.

### Files to Create/Modify

| File | Action |
|------|--------|
| `src/main/kotlin/org/rust/lang/core/psi/ext/RsUseBoundsClause.kt` | **New file** |
| `src/main/kotlin/org/rust/lang/core/psi/ext/RsTraitType.kt` | Add accessor |

### Detailed Changes

**2.1 Create RsUseBoundsClause.kt:**

```kotlin
package org.rust.lang.core.psi.ext

import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.psi.RsLifetime
import org.rust.lang.core.psi.RsUseBoundsClause
import org.rust.lang.core.psi.RsUseBoundsElement

val RsUseBoundsClause.capturedLifetimes: List<RsLifetime>
    get() = useBoundsElementList.mapNotNull { it.lifetime }

val RsUseBoundsClause.capturedIdentifiers: List<String>
    get() = useBoundsElementList.mapNotNull {
        it.identifier?.text ?: if (it.cself != null) "Self" else null
    }

val RsUseBoundsClause.isEmpty: Boolean
    get() = useBoundsElementList.isEmpty()

val RsUseBoundsClause.hasSelf: Boolean
    get() = useBoundsElementList.any { it.cself != null }
```

**2.2 Add to RsTraitType.kt:**

```kotlin
val RsTraitType.useBoundsClause: RsUseBoundsClause?
    get() = PsiTreeUtil.getChildOfType(this, RsUseBoundsClause::class.java)

val RsTraitType.hasExplicitCaptures: Boolean
    get() = useBoundsClause != null
```

### Success Criteria

- [ ] `RsTraitType.useBoundsClause` returns the clause when present
- [ ] `RsUseBoundsClause.capturedLifetimes` extracts lifetime elements
- [ ] `RsUseBoundsClause.capturedIdentifiers` extracts identifier names
- [ ] `RsUseBoundsClause.isEmpty` returns true for `use<>`

### Estimated Effort
**Small** - Simple accessor extensions

### Checkpoint
Verify with PSI viewer in development IDE instance.

---

## Phase 3: Type System Updates

### Objective
Extend `TyAnon` to store captured parameters and implement edition-aware capture inference.

### Files to Create/Modify

| File | Action |
|------|--------|
| `src/main/kotlin/org/rust/lang/core/types/ty/CapturedParameter.kt` | **New file** |
| `src/main/kotlin/org/rust/lang/core/types/ty/TyAnon.kt` | Extend data class |
| `src/main/kotlin/org/rust/lang/core/types/infer/TyLowering.kt` | Add capture extraction |

### Detailed Changes

**3.1 Create CapturedParameter.kt:**

```kotlin
package org.rust.lang.core.types.ty

import org.rust.lang.core.types.consts.CtConstParameter
import org.rust.lang.core.types.infer.TypeFolder
import org.rust.lang.core.types.infer.TypeFoldable
import org.rust.lang.core.types.infer.TypeVisitor
import org.rust.lang.core.types.regions.Region

sealed class CapturedParameter : TypeFoldable<CapturedParameter> {

    data class Lifetime(val region: Region) : CapturedParameter() {
        override fun foldWith(folder: TypeFolder): CapturedParameter =
            Lifetime(region.foldWith(folder))

        override fun visitWith(visitor: TypeVisitor): Boolean =
            region.visitWith(visitor)
    }

    data class TypeParam(val param: TyTypeParameter) : CapturedParameter() {
        override fun foldWith(folder: TypeFolder): CapturedParameter =
            TypeParam(param.foldWith(folder) as TyTypeParameter)

        override fun visitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)
    }

    data class ConstParam(val param: CtConstParameter) : CapturedParameter() {
        override fun foldWith(folder: TypeFolder): CapturedParameter =
            ConstParam(param.foldWith(folder) as CtConstParameter)

        override fun visitWith(visitor: TypeVisitor): Boolean =
            param.visitWith(visitor)
    }
}
```

**3.2 Modify TyAnon.kt:**

```kotlin
data class TyAnon(
    val definition: RsTraitType?,
    val traits: List<BoundElement<RsTraitItem>>,
    val capturedParams: List<CapturedParameter> = emptyList(),
    val hasExplicitCaptures: Boolean = false
) : Ty(mergeElementFlags(traits) or HAS_TY_OPAQUE_MASK) {

    init {
        require(definition == null || definition.isImpl) {
            "Can't construct TyAnon from non `impl Trait` definition $definition"
        }
    }

    override fun superFoldWith(folder: TypeFolder): Ty =
        TyAnon(
            definition,
            traits.map { it.foldWith(folder) },
            capturedParams.map { it.foldWith(folder) },
            hasExplicitCaptures
        )

    override fun superVisitWith(visitor: TypeVisitor): Boolean =
        traits.any { it.visitWith(visitor) } ||
        capturedParams.any { it.visitWith(visitor) }

    fun getTraitBoundsTransitively(): Collection<BoundElement<RsTraitItem>> =
        traits.flatMap { it.getFlattenHierarchy(this) }
}
```

**3.3 Modify TyLowering.kt** (around lines 167-174):

```kotlin
if (type.isImpl) {
    val sized = type.knownItems.Sized
    val traitBoundsWithImplicitSized = if (!hasSizedUnbound && sized != null) {
        traitBounds + BoundElement(sized)
    } else {
        traitBounds
    }

    // Extract captured parameters
    val (capturedParams, hasExplicitCaptures) = extractCapturedParams(type)

    return TyAnon(type, traitBoundsWithImplicitSized, capturedParams, hasExplicitCaptures)
}
```

**3.4 Add helper functions to TyLowering.kt:**

```kotlin
private fun extractCapturedParams(type: RsTraitType): Pair<List<CapturedParameter>, Boolean> {
    val useBounds = type.useBoundsClause

    return when {
        // Explicit use<..> clause
        useBounds != null -> {
            val params = extractExplicitCaptures(useBounds, type)
            Pair(params, true)
        }
        // Rust 2024+: capture all in-scope parameters
        type.isAtLeastEdition2024 -> {
            val params = collectInScopeParams(type)
            Pair(params, false)
        }
        // Rust 2021 and earlier: no implicit lifetime capture tracking
        else -> Pair(emptyList(), false)
    }
}

private fun extractExplicitCaptures(
    useBounds: RsUseBoundsClause,
    type: RsTraitType
): List<CapturedParameter> {
    val result = mutableListOf<CapturedParameter>()

    for (element in useBounds.useBoundsElementList) {
        when {
            element.lifetime != null -> {
                val resolved = element.lifetime?.resolve()
                if (resolved != null) {
                    result.add(CapturedParameter.Lifetime(resolved))
                }
            }
            element.identifier != null -> {
                val name = element.identifier!!.text
                val typeParam = resolveTypeParamByName(name, type)
                if (typeParam != null) {
                    result.add(CapturedParameter.TypeParam(typeParam))
                }
                // TODO: Handle const parameters
            }
            element.cself != null -> {
                // Handle Self - resolve from enclosing impl/trait
                val selfType = resolveSelfType(type)
                if (selfType is TyTypeParameter) {
                    result.add(CapturedParameter.TypeParam(selfType))
                }
            }
        }
    }

    return result
}

private fun collectInScopeParams(type: RsTraitType): List<CapturedParameter> {
    val result = mutableListOf<CapturedParameter>()

    for (scope in type.contexts.filterIsInstance<RsGenericDeclaration>()) {
        val typeParamList = scope.typeParameterList ?: continue

        // Collect lifetime parameters
        typeParamList.lifetimeParameterList.forEach { param ->
            result.add(CapturedParameter.Lifetime(ReEarlyBound(param)))
        }

        // Collect type parameters
        typeParamList.typeParameterList.forEach { param ->
            result.add(CapturedParameter.TypeParam(TyTypeParameter.named(param)))
        }

        // Collect const parameters
        typeParamList.constParameterList.forEach { param ->
            result.add(CapturedParameter.ConstParam(CtConstParameter(param)))
        }
    }

    return result
}

private fun resolveTypeParamByName(name: String, context: RsElement): TyTypeParameter? {
    for (scope in context.contexts.filterIsInstance<RsGenericDeclaration>()) {
        val param = scope.typeParameterList?.typeParameterList
            ?.find { it.name == name }
        if (param != null) {
            return TyTypeParameter.named(param)
        }
    }
    return null
}

private fun resolveSelfType(context: RsElement): Ty? {
    val implOrTrait = context.contexts.find { it is RsImplItem || it is RsTraitItem }
    return when (implOrTrait) {
        is RsImplItem -> implOrTrait.typeReference?.rawType
        is RsTraitItem -> TyTypeParameter.self(implOrTrait)
        else -> null
    }
}
```

### Success Criteria

- [ ] `TyAnon` stores captured parameters
- [ ] Explicit `use<'a, T>` correctly extracts parameters
- [ ] Empty `use<>` results in empty captured params list
- [ ] Rust 2024 edition implicitly captures all in-scope params
- [ ] Rust 2021 edition returns empty captures (legacy behavior)
- [ ] Type folding works correctly with captured params

### Estimated Effort
**Medium** - Core type system changes with resolution logic

### Checkpoint
```bash
./gradlew :test --tests "org.rust.lang.core.type.*"
```

---

## Phase 4: Type Rendering

### Objective
Display `use<..>` bounds in type hints and tooltips.

### Files to Modify

| File | Action |
|------|--------|
| `src/main/kotlin/org/rust/ide/presentation/TypeRendering.kt` | Update rendering logic |

### Detailed Changes

**4.1 Modify TypeRendering.kt** (around line 178):

```kotlin
is TyAnon -> buildString {
    append("impl ")
    if (ty.hasExplicitCaptures) {
        append("use<")
        append(ty.capturedParams.joinToString(", ") { renderCapturedParam(it, render) })
        append("> ")
    }
    append(ty.traits.joinToString("+") { formatTrait(it, render) })
}
```

**4.2 Add helper function:**

```kotlin
private fun renderCapturedParam(param: CapturedParameter, render: (Ty) -> String): String =
    when (param) {
        is CapturedParameter.Lifetime -> {
            when (val region = param.region) {
                is ReStatic -> "'static"
                is ReEarlyBound -> region.parameter.name ?: "'_"
                else -> "'_"
            }
        }
        is CapturedParameter.TypeParam -> param.param.name ?: "<unknown>"
        is CapturedParameter.ConstParam -> param.param.parameter.name ?: "<unknown>"
    }
```

### Success Criteria

- [ ] Type hints show `impl use<'a, T> Trait` when explicit captures present
- [ ] Empty captures show as `impl use<> Trait`
- [ ] No `use<..>` shown when captures are implicit (edition 2024)
- [ ] Lifetimes render with `'` prefix

### Estimated Effort
**Small** - Straightforward rendering logic

### Checkpoint
Verify in development IDE with type inlay hints enabled.

---

## Phase 5: IDE Completion

### Objective
Provide code completion for `use` keyword and generic parameters inside `use<..>`.

### Files to Create/Modify

| File | Action |
|------|--------|
| `src/main/kotlin/org/rust/lang/core/completion/RsKeywordCompletionContributor.kt` | Add `use` keyword |
| `src/main/kotlin/org/rust/lang/core/completion/RsUseBoundsCompletionProvider.kt` | **New file** |
| `src/main/kotlin/org/rust/lang/core/completion/RsCompletionContributor.kt` | Register provider |

### Detailed Changes

**5.1 Add to RsKeywordCompletionContributor.kt:**

```kotlin
// Add pattern function
private fun useInImplTraitPattern(): PsiElementPattern.Capture<PsiElement> =
    psiElement()
        .withParent(psiElement<RsPath>())
        .inside(psiElement<RsTraitType>().with("isImpl") { it.isImpl })

// Add in init block
extend(CompletionType.BASIC, useInImplTraitPattern(),
    RsKeywordCompletionProvider("use"))
```

**5.2 Create RsUseBoundsCompletionProvider.kt:**

```kotlin
package org.rust.lang.core.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.ElementPattern
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.RsPsiPattern.psiElement

object RsUseBoundsCompletionProvider : RsCompletionProvider() {

    override val elementPattern: ElementPattern<PsiElement> =
        psiElement(RsElementTypes.IDENTIFIER)
            .inside(psiElement<RsUseBoundsClause>())

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position
        val useBoundsClause = position.ancestorOrSelf<RsUseBoundsClause>() ?: return
        val traitType = useBoundsClause.parent as? RsTraitType ?: return

        // Collect in-scope generic parameters from enclosing declarations
        for (scope in traitType.contexts.filterIsInstance<RsGenericDeclaration>()) {
            val typeParamList = scope.typeParameterList ?: continue

            // Add type parameters
            typeParamList.typeParameterList.forEach { param ->
                val name = param.name ?: return@forEach
                result.addElement(
                    LookupElementBuilder.create(name)
                        .withIcon(param.getIcon(0))
                        .withTypeText("type parameter")
                )
            }

            // Add lifetime parameters
            typeParamList.lifetimeParameterList.forEach { param ->
                val name = param.name ?: return@forEach
                result.addElement(
                    LookupElementBuilder.create(name)
                        .withIcon(param.getIcon(0))
                        .withTypeText("lifetime")
                )
            }

            // Add const parameters
            typeParamList.constParameterList.forEach { param ->
                val name = param.name ?: return@forEach
                result.addElement(
                    LookupElementBuilder.create(name)
                        .withIcon(param.getIcon(0))
                        .withTypeText("const parameter")
                )
            }
        }

        // Add 'Self' in trait/impl context
        val inTraitOrImpl = traitType.contexts.any { it is RsImplItem || it is RsTraitItem }
        if (inTraitOrImpl) {
            result.addElement(
                LookupElementBuilder.create("Self")
                    .withTypeText("self type")
                    .bold()
            )
        }
    }
}
```

**5.3 Register in RsCompletionContributor.kt:**

```kotlin
// Add in init block
extend(CompletionType.BASIC, RsUseBoundsCompletionProvider)
```

### Success Criteria

- [ ] Typing `impl u` suggests `use` keyword
- [ ] Inside `use<|>`, completion suggests in-scope type parameters
- [ ] Inside `use<|>`, completion suggests in-scope lifetime parameters
- [ ] Inside `use<|>`, completion suggests `Self` when in trait/impl context
- [ ] Parameters already in the clause are not suggested again (optional enhancement)

### Estimated Effort
**Medium** - New completion provider with scope analysis

### Checkpoint
Test in development IDE by triggering completion in test Rust files.

---

## Phase 6: Parser Tests

### Objective
Add parser test fixtures to verify correct AST generation.

### Files to Create

| File | Action |
|------|--------|
| `src/test/resources/org/rust/lang/core/parser/fixtures/complete/use_capturing.rs` | **New file** |

### Test Content

```rust
// =============================================================================
// Basic use bounds
// =============================================================================
fn empty() -> impl use<> Sized {}
fn one_lifetime<'a>() -> impl use<'a> Sized {}
fn one_type<T>() -> impl use<T> Sized {}
fn one_const<const N: usize>() -> impl use<N> Sized {}

// Multiple captures
fn multiple<'a, 'b, T, U>() -> impl use<'a, 'b, T, U> Iterator<Item = &'a T> {}

// With trait bounds
fn with_bounds<'a, T>() -> impl use<'a, T> Clone + Send + 'static {}

// Trailing comma (RFC 3617: optional trailing comma)
fn trailing_comma<'a, T,>() -> impl use<'a, T,> Sized {}

// =============================================================================
// Self in trait definitions (RFC 3617: Self only valid in trait definitions)
// =============================================================================
trait Foo {
    fn with_self() -> impl use<Self> Clone;
}

// =============================================================================
// Elided lifetime (RFC 3617: use<'_> captures elided lifetime)
// =============================================================================
fn elided(x: &()) -> impl use<'_> Sized { x }

// =============================================================================
// Outer impl generic parameters (in scope per RFC 3498)
// =============================================================================
struct S<'s, T>(&'s T);
impl<'s, T> S<'s, T> {
    fn method() -> impl use<'s, T> Sized {}
    fn method_with_own<'m, U>() -> impl use<'s, 'm, T, U> Sized {}
}

// =============================================================================
// Capturing from outer inherent impl (RFC 3617 example)
// =============================================================================
struct Ty<'a, 'b>(&'a (), &'b ());
impl<'a, 'b> Ty<'a, 'b> {
    fn foo(x: &'a (), _: &'b ()) -> impl use<'a> Sized { x }
}

// =============================================================================
// Nested in complex types
// =============================================================================
fn nested<'a, T>() -> Option<impl use<'a, T> Iterator<Item = &'a T>> { None }

// =============================================================================
// APIT context (RFC 3617: anonymous params cannot be named in use<..>)
// To capture APIT, must convert to named parameter
// =============================================================================
fn apit_no_capture(_: impl Sized) -> impl use<> Sized {}
// To capture the APIT type, name it:
fn apit_with_capture<T: Sized>(_: T) -> impl use<T> Sized {}

// =============================================================================
// Const generic semantics (RFC 3617)
// Must capture const to use in TYPE, but not to use as VALUE
// =============================================================================
fn const_as_type<const C: usize>() -> impl use<C> Sized { [(); C] }
fn const_as_value<const C: usize>() -> impl use<> Sized { C + 1 }

// =============================================================================
// Combining with for<..> (RFC 3617: use<..> applies to entire impl Trait)
// =============================================================================
fn with_hrtb<T>(_: T) -> impl use<T> for<'a> FnOnce(&'a ()) { |_| () }

// =============================================================================
// Higher-ranked lifetime in nested opaque (RFC 3617)
// Note: capturing higher-ranked lifetimes in nested opaques not yet supported
// =============================================================================
trait Tr<'a> { type Ty; }
impl Tr<'_> for () { type Ty = (); }
// This avoids capturing 'a from outer for<'a>:
fn avoid_hrtb_capture() -> impl for<'a> Tr<'a, Ty = impl use<> Sized> { () }
```

### Success Criteria

- [ ] All test cases parse without errors
- [ ] PSI tree structure is correct (verify with gold files)
- [ ] `RsTraitType.useBoundsClause` is present in AST

### Estimated Effort
**Small** - Writing test fixtures

### Checkpoint
```bash
./gradlew :test --tests "org.rust.lang.core.parser.RsParserTest"
```

---

## Phase 7: Type Inference Tests

### Objective
Add tests for type inference with captured parameters.

### Files to Create/Modify

| File | Action |
|------|--------|
| `src/test/kotlin/org/rust/lang/core/type/RsExpressionTypeInferenceTest.kt` | Add test methods |
| `src/test/kotlin/org/rust/lang/core/type/RsImplTraitTypeInferenceTest.kt` | Add test methods (or create new) |

### Test Cases

```kotlin
fun `test explicit use captures lifetimes`() = testExpr("""
    fn foo<'a>(x: &'a i32) -> impl use<'a> Sized { *x }
    fn main() {
        let x = 1;
        let y = foo(&x);
        y;
      //^ impl use<'a> Sized
    }
""")

fun `test use empty captures nothing`() = testExpr("""
    fn foo<'a>(_: &'a i32) -> impl use<> Sized { () }
    fn main() {
        let y = foo(&1);
        y;
      //^ impl use<> Sized
    }
""")

fun `test edition 2024 captures all implicitly`() = testExpr("""
    // edition: 2024
    fn foo<'a, T>(x: &'a T) -> impl Sized { x }
    fn main() {
        let y = foo(&1i32);
        y;
      //^ impl Sized  // Internal: captures 'a, T
    }
""")

fun `test edition 2021 no implicit lifetime capture`() = testExpr("""
    // edition: 2021
    fn foo<'a>(x: &'a i32) -> impl Sized { *x }
    fn main() {
        let y = foo(&1);
        y;
      //^ impl Sized  // No captured params
    }
""")
```

### Success Criteria

- [ ] Explicit `use<..>` captures resolve correctly
- [ ] Edition 2024 implicit captures work
- [ ] Edition 2021 has no implicit capture tracking
- [ ] Type rendering shows captures when explicit

### Estimated Effort
**Medium** - Writing comprehensive test coverage

### Checkpoint
```bash
./gradlew :test --tests "org.rust.lang.core.type.*"
```

---

## Dependency Graph

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  Phase 1: Grammar                                                   │
│  ├── Add UseBoundsClause rule                                       │
│  ├── Add UseBoundsElement rule                                      │
│  └── Modify ExplicitTraitTypeInner                                  │
│                           │                                         │
│                           ▼                                         │
│  Phase 2: PSI Extensions                                            │
│  ├── Create RsUseBoundsClause.kt                                    │
│  └── Modify RsTraitType.kt                                          │
│                           │                                         │
│                           ▼                                         │
│  Phase 3: Type System                                               │
│  ├── Create CapturedParameter.kt                                    │
│  ├── Modify TyAnon.kt                                               │
│  └── Modify TyLowering.kt                                           │
│                           │                                         │
│           ┌───────────────┼───────────────┐                         │
│           ▼               ▼               ▼                         │
│  Phase 4: Rendering  Phase 5: Completion  Phase 6: Parser Tests     │
│  └── TypeRendering   ├── Keyword          └── use_capturing.rs      │
│                      └── Provider                                   │
│                                               │                     │
│                                               ▼                     │
│                                  Phase 7: Type Inference Tests      │
│                                  └── Test methods                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Phase Dependencies

| Phase | Depends On | Can Run In Parallel With |
|-------|------------|--------------------------|
| 1. Grammar | None | None |
| 2. PSI | Phase 1 | None |
| 3. Type System | Phase 2 | None |
| 4. Rendering | Phase 3 | Phases 5, 6 |
| 5. Completion | Phase 3 | Phases 4, 6 |
| 6. Parser Tests | Phase 1 | Phases 4, 5 |
| 7. Type Tests | Phases 3, 4 | None |

---

## Risk Assessment

### High Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| Breaking existing `impl Trait` behavior | Users see incorrect types | Comprehensive test suite; run all existing tests |
| Parser ambiguity with `use` keyword | Parsing errors in valid code | Test edge cases; `use` is contextual |

### Medium Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| TypeFoldable implementation incorrect | Subtle type inference bugs | Unit tests for folding behavior |
| Edition detection unreliable | Wrong capture behavior | Use existing `isAtLeastEdition2024` helper |
| Completion suggests wrong parameters | Poor UX | Test with various scope configurations |

### Low Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| Performance regression from TyAnon changes | Slower analysis | Profile if issues arise; captures list is small |
| Rendering clutters type hints | Visual noise | Only show explicit captures |

---

## Deferred Work

The following items are intentionally deferred to future phases:

### 1. Syntax Error Annotations
- Validate `use<..>` only appears with `impl`, not `dyn`
- Validate captured parameters are in scope
- Error for duplicate captures
- **Stabilization restrictions** (Rust 1.82):
  - Warn if `use<..>` doesn't include all in-scope type/const params
  - Warn if RPITIT `use<..>` doesn't include all in-scope generics
- Validate `Self` only appears in trait definitions, not elsewhere

### 2. Migration Quick-Fixes
- Detect `Captures` trick pattern (`+ Captures<&'a ()>`) and suggest `use<'a>`
- Detect outlives trick (`+ 'a` with unnecessary `T: 'a` bound) and suggest migration
- `impl_trait_overcaptures` equivalent lint for Rust 2024 migration
- Auto-convert APIT to named parameter when needed for `use<..>`

### 3. Advanced IDE Features
- Inlay hints for implicit captures in Rust 2024
- Refactoring: Convert between editions
- Find usages for captured parameters
- Navigate from `use<..>` element to parameter declaration

### 4. Advanced Semantic Analysis (from RFC 3617)
- **Refinement detection**: Warn when RPITIT impl captures less than trait definition
- **Lifetime equality**: Handle cases like `fn foo<'a: 'b, 'b: 'a>() -> impl use<'b> Sized`
- **Reparameterization**: Handle impls with different generic params than trait
- **Projection types**: Error when trying to capture from projected associated types

### 5. Higher-Ranked Lifetime Support
- Capturing lifetimes from `for<..>` binders on outer opaque types
- Note: Rust doesn't yet support HRTB on nested opaque types (#104288)

---

## Progress Tracking

Use this checklist to track implementation progress across sessions:

### Phase 1: Grammar Updates
- [ ] Add `UseBoundsClause` grammar rule
- [ ] Add `UseBoundsElement` grammar rule
- [ ] Modify `ExplicitTraitTypeInner` to accept optional `UseBoundsClause`
- [ ] Run `./gradlew :generateParser`
- [ ] Verify basic parsing works

### Phase 2: PSI Extensions
- [ ] Create `RsUseBoundsClause.kt`
- [ ] Add `useBoundsClause` accessor to `RsTraitType.kt`
- [ ] Verify PSI tree in development IDE

### Phase 3: Type System Updates
- [ ] Create `CapturedParameter.kt` sealed class
- [ ] Extend `TyAnon` with `capturedParams` and `hasExplicitCaptures`
- [ ] Update `TyAnon.superFoldWith()` and `superVisitWith()`
- [ ] Add `extractCapturedParams()` to TyLowering
- [ ] Add `extractExplicitCaptures()` helper
- [ ] Add `collectInScopeParams()` for edition 2024
- [ ] Add `resolveTypeParamByName()` helper
- [ ] Verify existing type tests pass

### Phase 4: Type Rendering
- [ ] Update `TypeRendering.kt` to display `use<..>`
- [ ] Add `renderCapturedParam()` helper
- [ ] Verify in development IDE

### Phase 5: IDE Completion
- [ ] Add `use` keyword completion pattern
- [ ] Create `RsUseBoundsCompletionProvider.kt`
- [ ] Register provider in `RsCompletionContributor.kt`
- [ ] Test completion in development IDE

### Phase 6: Parser Tests
- [ ] Create `use_capturing.rs` test fixture
- [ ] Run parser tests
- [ ] Verify PSI structure with gold files

### Phase 7: Type Inference Tests
- [ ] Add explicit capture tests
- [ ] Add empty capture tests
- [ ] Add edition 2024 implicit capture tests
- [ ] Add edition 2021 no-capture tests
- [ ] Run all type tests

### Final Verification
- [ ] Run full test suite: `./gradlew :test`
- [ ] Manual testing in development IDE
- [ ] Code review
- [ ] Update CHANGELOG

---

## Notes

- **Branch**: All work should be on `feature/precise-capturing`
- **Testing**: Run `./gradlew :test` frequently to catch regressions
- **IDE Testing**: Use `./gradlew :plugin:runIde` for manual verification
- **Documentation**: Update CLAUDE.md if new patterns established

---

## References

### External Links

- [RFC 3498 (official)](https://rust-lang.github.io/rfcs/3498-lifetime-capture-rules-2024.html)
- [RFC 3617 (official)](https://rust-lang.github.io/rfcs/3617-precise-capturing.html)
- [Edition Guide: RPIT lifetime capture](https://doc.rust-lang.org/edition-guide/rust-2024/rpit-lifetime-capture.html)
- [Tracking Issue: RFC 3498](https://github.com/rust-lang/rust/issues/117587)
- [Tracking Issue: RFC 3617](https://github.com/rust-lang/rust/issues/123432)
- [Rust Blog: Changes to impl Trait in Rust 2024](https://blog.rust-lang.org/2024/09/05/impl-trait-capture-rules.html)
