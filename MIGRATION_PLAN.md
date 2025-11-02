# Migration Plan: Unsafe Extern Blocks Support (RFC 3484)

**Feature:** Support for `unsafe extern` blocks introduced in Rust 1.82
**RFC:** [RFC 3484 - unsafe_extern_blocks](https://github.com/rust-lang/rfcs/blob/master/text/3484-unsafe-extern-blocks.md)
**Status:** Not Started
**Target Completion:** TBD

## Executive Summary

This migration adds support for Rust 1.82's `unsafe extern` blocks feature, which:
- Requires `unsafe` keyword on extern blocks in Edition 2024
- Allows `safe`/`unsafe` qualifiers on items within extern blocks
- Maintains backward compatibility with pre-2024 editions

**Total Estimated Effort:** 3.5-4.5 days (26.5-36.5 hours)

**Note:** Effort estimates reflect extending existing PSI types (following the plugin's 2016 architectural pattern) rather than creating new dedicated types.

---

## Phase 1: Grammar Changes (GRAM)

**Estimated Effort:** 2.5-3.5 hours
**Prerequisites:** None
**Branch Suggestion:** `feature/unsafe-extern-grammar`

### 1.1 Handle `safe` as Contextual Keyword

**Important:** The `safe` keyword is a **contextual keyword** in Rust, meaning it's only treated as a keyword in specific grammar contexts (inside `extern` blocks) and can still be used as a regular identifier elsewhere in code.

**Implementation Approach:**

In Grammar-Kit based parsers, contextual keywords are handled by:
1. **NOT adding them as hard keywords to the lexer** - they remain `IDENTIFIER` tokens
2. Using the identifier token in grammar rules and matching specific text values where needed
3. The parser generator handles the context-specific recognition

**Why No Lexer Changes:**
- Hard keywords (like `fn`, `let`, `unsafe`) are always keywords and cannot be identifiers
- Contextual keywords (like `safe`) are identifiers that have special meaning only in certain contexts
- If we made `safe` a hard keyword, existing code using `safe` as a variable/function name would break

**No Changes Required to RustLexer.flex**

**Effort:** 5 minutes (understanding only)

---

### 1.2 Update Parser Grammar

**File:** `src/main/grammars/RustParser.bnf`

#### 1.2.1 Understanding Contextual Keywords in Grammar

**Approach:** Use identifier matching for `safe` keyword in grammar rules.

In Grammar-Kit, contextual keywords are referenced using quoted strings `'safe'` in the BNF grammar. The parser generator creates rules that match an IDENTIFIER token with the specific text "safe" only in the contexts where the rule is used.

**Example Pattern:**
```bnf
// This matches the identifier "safe" only in this specific rule
safe ::= 'safe'
```

**No Token Section Changes Needed** - The token will be an IDENTIFIER at lexer level, but grammar rules will match it specifically by text.

**Effort:** 5 minutes (understanding)

---

#### 1.2.2 Update ForeignModItem Rule (NO CHANGES NEEDED)

**Location:** Line 755

**Current:**
```bnf
upper ForeignModItem ::= unsafe? ExternAbi ForeignModBody
```

**Analysis:** Grammar already supports optional `unsafe` prefix. No structural changes needed, only semantic interpretation changes.

**Effort:** 0 minutes

---

#### 1.2.3 Add Safety Qualifier Support to Existing Function/Constant Rules

**Location:** Line 536 (Function rule) and Line 653 (Constant rule)

**Approach:** Extend existing `RsFunction` and `RsConstant` types with safety qualifier support.

This follows the plugin's established architectural pattern from 2016:
- Commit `8adc969be` (Dec 29, 2016): "(GRAM): foreign_static is constant" - unified foreign statics with RsConstant
- Commit `76e26cd09` (Dec 28, 2016): "(GRAM): add function element" - explicitly states: "The plan is to use it for **all kinds of functions**: freestanding, foreign, both types of methods"

This same pattern has been consistently applied to all function modifiers (`async`, `const`, `unsafe`) and similar variants (`struct/union`, `const/static/mut static`).

**Grammar Changes:**

First, add a helper rule for the contextual keyword (near other contextual keyword rules like `try`, `gen`, `raw`):
```bnf
private safe ::= <<safeKeyword>>
```

This invokes a meta-rule function that will be defined in `RustParserUtil.kt`.

Then update the Function and Constant rules:
```bnf
// Line 536: Update existing Function rule
upper Function ::= async? const? unsafe? safe? ExternAbi?
                   fn identifier
                   TypeParameterList?
                   FnParameters
                   RetType?
                   WhereClause?
                   (';' | ShallowBlock)

// Line 653: Update existing Constant rule
upper Constant ::= (('safe' | 'unsafe') static mut? | static mut? | const)
                   (identifier | '_')
                   TypeAscription?
                   [ '=' AnyExpr ] ';'
```

**Note:** In the Constant rule, we use literal `'safe'` and `'unsafe'` because the first appearance in the alternative handles the contextual matching. For Function, we use the `safe` rule which applies the contextual keyword logic.

**Grammar Notes:**
- The `safe` rule uses Grammar-Kit's meta-rule syntax `<<safeKeyword>>` to call a parser utility function
- In the Constant rule, safety qualifiers only apply to `static` items (not `const`)
- This is semantically correct: `safe const` and `unsafe const` are invalid in Rust
- Pin point remains at `pin = 2` to allow backtracking when parsing `const fn` (tries Constant first, backtracks to Function)
- The contextual keyword approach ensures `safe` can still be used as a regular identifier in other contexts

**Architecture:** Foreign items continue using `RsFunction` and `RsConstant` PSI types. Differentiation happens via:
1. **Parent context**: Check if `parent is RsForeignModItem`
2. **Owner pattern**: Existing `RsAbstractableOwner.Foreign` enum case
3. **Bit flags**: Add `IS_SAFE_MASK` to existing stub flags field (32-bit flags with 22 bits still available)

**Benefits of This Approach:**
- Consistent with plugin's 2016 architectural foundation
- Matches existing modifier patterns throughout the codebase
- Minimal code addition (~300 lines vs ~2,000 for separate types)
- No breaking changes to existing APIs
- Centralized maintenance - changes apply to all function contexts
- Zero-cost stub storage - fits in existing flags field

**Effort:** 30-40 minutes (grammar changes only)

---

### 1.2.4 Add Parser Utility Function for Contextual Keyword

**File:** `src/main/kotlin/org/rust/lang/core/parser/RustParserUtil.kt`
**Location:** Near other contextual keyword functions (search for `tryKeyword`, `rawKeyword`)

**Add Function:**
```kotlin
@JvmStatic
fun safeKeyword(b: PsiBuilder, level: Int): Boolean = contextualKeyword(b, "safe", SAFE)
```

**How It Works:**
- When the parser encounters an `IDENTIFIER` token with text "safe" in a position where the `safe` grammar rule is used
- The `contextualKeyword()` function checks the token type and text
- It remaps the token from `IDENTIFIER` to `SAFE` element type using `b.remapCurrentToken(SAFE)`
- This allows `safe` to be recognized as a keyword in specific grammar contexts while remaining an identifier elsewhere

**Required Token Type:**
The `SAFE` element type needs to exist. Add it to `RsElementTypes.kt` or ensure it's available:
```kotlin
@JvmField val SAFE = RsTokenType("SAFE")
```

**Effort:** 15 minutes

---

### 1.2.5 Architectural Rationale: The "Owner Pattern"

**Background:** Understanding this pattern is critical for implementing safety qualifiers correctly.

The plugin uses a **context-over-type** architecture throughout:

**Pattern 1: RsAbstractableOwner for Context Detection**

```kotlin
// From: src/main/kotlin/org/rust/lang/core/psi/ext/RsAbstractable.kt
sealed class RsAbstractableOwner {
    object Free : RsAbstractableOwner()
    object Foreign : RsAbstractableOwner()  // ← Used for extern blocks
    class Trait(val trait: RsTraitItem) : RsAbstractableOwner()
    class Impl(val impl: RsImplItem, val isInherent: Boolean) : RsAbstractableOwner()
}
```

**Pattern 2: Contextual Properties Using Owner**

```kotlin
// From: RsFunction.kt:165-183 (already checks parent context)
val RsFunction.isActuallyUnsafe: Boolean
    get() {
        if (isUnsafe) return true
        val context = context
        return if (context is RsForeignModItem) {  // ← Parent check
            // functions inside extern blocks are unsafe by default
            when {
                context.queryAttributes.hasAttribute("wasm_bindgen") -> false
                else -> true
            }
        } else {
            false
        }
    }
```

**Pattern 3: Consistent Use of Bit Flags for Modifiers**

| Rust Feature | PSI Type | Differentiation Method |
|--------------|----------|------------------------|
| `fn` / `async fn` / `const fn` / `unsafe fn` | `RsFunction` | Bit flags: `ASYNC_MASK`, `CONST_MASK`, `UNSAFE_MASK` |
| `struct` / `union` | `RsStructItem` | Bit flag: `IS_UNION_MASK` |
| `const` / `static` / `static mut` | `RsConstant` | Bit flags + kind enum |
| Free fn / trait fn / impl fn / foreign fn | `RsFunction` | `owner` property (sealed class) |
| `safe fn` / `unsafe fn` in extern | `RsFunction` | Bit flag: `SAFE_MASK` + owner context |
| `safe static` / `unsafe static` in extern | `RsConstant` | Bit flags: `IS_SAFE_MASK`, `IS_UNSAFE_MASK` |

**Historical Example:** The 2018 unification of trait types (commit `c7d2cd2c4`):
- **Before:** Three separate PSI types (`ImplicitDynTraitType`, `ExplicitDynTraitType`, `ImplTraitType`)
- **After:** Single `TraitType` with contextual detection
- **Reason:** Semantic similarity + code duplication

**Implementation for This Feature:**

The new `isSafe` property will integrate seamlessly:

```kotlin
val RsFunction.isSafe: Boolean
    get() = greenStub?.isSafe ?: (safe != null)

val RsFunction.effectiveSafety: Safety
    get() = when (owner) {
        is RsAbstractableOwner.Foreign -> {
            when {
                isSafe -> Safety.Safe      // ← Explicit safe
                isUnsafe -> Safety.Unsafe  // ← Explicit unsafe
                else -> Safety.Unsafe      // ← Default in foreign blocks
            }
        }
        else -> if (isUnsafe) Safety.Unsafe else Safety.Safe
    }
```

**Result:** Zero changes needed to:
- Type inference infrastructure
- Name resolution
- Completion
- Navigation
- Most IDE features

---

### 1.3 Generate Parser and Lexer

**Command:**
```bash
./gradlew :generateLexer :generateParser
```

**Success Criteria:**
- [ ] Task completes without errors
- [ ] Generated files updated in `src/gen/`
- [ ] No compilation errors after generation
- [ ] The `SAFE` token type is available in generated code

**Effort:** 15 minutes (including build time)

---

### Phase 1 Checkpoint

**Verification Steps:**
1. Run `./gradlew :generateLexer :generateParser`
2. Run `./gradlew :plugin:buildPlugin` - should compile
3. Manually inspect generated parser code
4. Test that `safe` keyword is recognized in extern contexts while remaining usable as identifier elsewhere

**Success Criteria:**
- [ ] All generation tasks complete successfully
- [ ] Project compiles without errors
- [ ] Parser accepts `safe` as contextual keyword in extern item contexts
- [ ] Parser accepts `unsafe extern {}` syntax
- [ ] `safe` can still be used as a regular identifier (variable names, etc.)

**Blocking Issues:** None expected

---

## Phase 2: PSI & Stub Changes (PSI, STUB)

**Estimated Effort:** 4-5 hours
**Prerequisites:** Phase 1 complete
**Branch Suggestion:** `feature/unsafe-extern-psi` (or continue from Phase 1)

⚠️ **CRITICAL:** This phase includes stub format changes requiring version bump and full re-indexing.

---

### 2.1 Extend RsForeignModStub

**File:** `src/main/kotlin/org/rust/lang/core/stubs/StubImplementations.kt`
**Location:** Search for `class RsForeignModStub`

#### 2.1.1 Update Stub Class

**Current:**
```kotlin
class RsForeignModStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
    val abi: String?,
) : RsAttrProcMacroOwnerStubBase<RsForeignModItem>(parent, elementType)
```

**Updated:**
```kotlin
class RsForeignModStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val flags: Int,
    override val procMacroInfo: RsProcMacroStubInfo?,
    val abi: String?,
    val isUnsafe: Boolean  // NEW FIELD
) : RsAttrProcMacroOwnerStubBase<RsForeignModItem>(parent, elementType)
```

**Effort:** 10 minutes

---

#### 2.1.2 Update Serialization (deserialize)

**Location:** In RsForeignModStub.Type object, deserialize method

**Updated:**
```kotlin
override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
    RsForeignModStub(
        parentStub, this,
        dataStream.readUnsignedByte(),
        RsProcMacroStubInfo.deserialize(dataStream),
        abi = dataStream.readNameString(),
        isUnsafe = dataStream.readBoolean()  // NEW
    )
```

**Effort:** 5 minutes

---

#### 2.1.3 Update Serialization (serialize)

**Location:** In RsForeignModStub.Type object, serialize method

**Updated:**
```kotlin
override fun serialize(stub: RsForeignModStub, dataStream: StubOutputStream) =
    with(dataStream) {
        writeByte(stub.flags)
        RsProcMacroStubInfo.serialize(stub.procMacroInfo, dataStream)
        writeName(stub.abi)
        writeBoolean(stub.isUnsafe)  // NEW
    }
```

**Effort:** 5 minutes

---

#### 2.1.4 Update Stub Creation

**Location:** In RsForeignModStub.Type object, createStub method

**Updated:**
```kotlin
override fun createStub(psi: RsForeignModItem, parentStub: StubElement<*>?): RsForeignModStub {
    val flags = RsAttributeOwnerStub.extractFlags(psi)
    val procMacroInfo = RsAttrProcMacroOwner Stub.extractTextAndOffset(flags, psi)
    return RsForeignModStub(
        parentStub, this,
        flags, procMacroInfo,
        abi = psi.abi,
        isUnsafe = psi.unsafe != null  // NEW
    )
}
```

**Effort:** 5 minutes

---

### 2.2 Bump Stub Version

**File:** `src/main/kotlin/org/rust/lang/core/stubs/StubImplementations.kt`
**Location:** Top of file, search for `private const val STUB_VERSION`

**Current:**
```kotlin
private const val STUB_VERSION = 234
```

**Updated:**
```kotlin
private const val STUB_VERSION = 235  // Bump for unsafe extern blocks support
```

**Impact:** Forces complete re-indexing of all Rust projects

**Effort:** 2 minutes

---

### 2.3 Update RsForeignModItem PSI Extension

**File:** `src/main/kotlin/org/rust/lang/core/psi/ext/RsForeignModItem.kt`
**Location:** After existing properties (after abi property, before RsForeignModItemImplMixin class)

**Add Property:**
```kotlin
val RsForeignModItem.isUnsafe: Boolean
    get() = greenStub?.isUnsafe ?: (unsafe != null)
```

**Effort:** 10 minutes

---

### 2.4 Add Safety Qualifier Support for Foreign Items

**Approach:** Extend existing `RsFunction` with `isSafe` flag (following the plugin's 2016 architectural pattern).

#### 2.4.1 Update RsFunctionStub with SAFE_MASK Flag

**File:** `src/main/kotlin/org/rust/lang/core/stubs/StubImplementations.kt`
**Location:** Search for `class RsFunctionStub`

**Current Structure:**
```kotlin
class RsFunctionStub(
    parent: StubElement<*>?, elementType: IStubElementType<*, *>,
    override val name: String?,
    val abiName: String?,
    override val flags: Int,  // ← Uses bit flags (32 bits available)
    override val procMacroInfo: RsProcMacroStubInfo?,
)
```

**Add Property (in RsFunctionStub class):**
```kotlin
val isSafe: Boolean get() = BitUtil.isSet(flags, SAFE_MASK)
```

**Add to Companion Object:**
```kotlin
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
    private val SAFE_MASK: Int = nextBitMask()  // ← NEW (add after PREFERRED_BRACES)
}
```

**Note:** SAFE_MASK must be added after PREFERRED_BRACES (which uses 2 bits) to avoid conflicts with existing bit assignments.

**Update createStub Method (in RsFunctionStub.Type object):**
```kotlin
override fun createStub(psi: RsFunction, parentStub: StubElement<*>?): RsFunctionStub {
    // ... attribute extraction logic ...

    var flags = RsAttributeOwnerStub.extractFlags(attrs, FunctionStubAttrFlags)
    flags = BitUtil.set(flags, ABSTRACT_MASK, block == null)
    flags = BitUtil.set(flags, CONST_MASK, psi.isConst)
    flags = BitUtil.set(flags, UNSAFE_MASK, psi.isUnsafe)
    flags = BitUtil.set(flags, EXTERN_MASK, psi.isExtern)
    flags = BitUtil.set(flags, VARIADIC_MASK, psi.isVariadic)
    flags = BitUtil.set(flags, SAFE_MASK, psi.isSafe)  // ← NEW (add with other flags)
    flags = BitUtil.set(flags, ASYNC_MASK, psi.isAsync)
    flags = BitUtil.set(flags, HAS_SELF_PARAMETER_MASK, psi.hasSelfParameters)

    // ... rest of method including PREFERRED_BRACES handling ...
}
```

**Effort:** 15 minutes

---

#### 2.4.2 Add Safe Keyword Accessor to RsFunction

**File:** `src/main/kotlin/org/rust/lang/core/psi/ext/RsFunction.kt`
**Location:** After existing keyword properties (near rawReturnType, before valueParameters)

**Add:**
```kotlin
val RsFunction.isSafe: Boolean
    get() = greenStub?.isSafe ?: (safe != null)
```

**Effort:** 5 minutes

---

### 2.5 Add Safety Qualifier Support for Foreign Statics

**Approach:** Extend existing `RsConstant` with `isSafe` and `isUnsafe` flags (mirrors RsFunction approach).

#### 2.5.1 Update RsConstantStub with Safety Flags

**File:** `src/main/kotlin/org/rust/lang/core/stubs/StubImplementations.kt`
**Location:** Search for `class RsConstantStub`

**Add Properties (in RsConstantStub class):**
```kotlin
val isSafe: Boolean get() = BitUtil.isSet(flags, IS_SAFE_MASK)
val isUnsafe: Boolean get() = BitUtil.isSet(flags, IS_UNSAFE_MASK)
```

**Add to Companion Object:**
```kotlin
companion object : BitFlagsBuilder(ConstantStubAttrFlags, INT) {
    private val IS_MUT_MASK: Int = nextBitMask()
    private val IS_CONST_MASK: Int = nextBitMask()
    private val IS_SAFE_MASK: Int = nextBitMask()     // ← NEW
    private val IS_UNSAFE_MASK: Int = nextBitMask()   // ← NEW
}
```

**Important:** Use `INT` as the storage limit (not `BYTE`) because RsConstantStub needs more than 8 bits total:
- 5 flags from CommonStubAttrFlags (inherited via ConstantStubAttrFlags)
- 2 existing flags (IS_MUT_MASK, IS_CONST_MASK)
- 2 new safety flags (IS_SAFE_MASK, IS_UNSAFE_MASK)
- Total: 9 flags requiring INT (32-bit) storage

This follows the same pattern as RsFunctionStub where attribute-level flags use BYTE limit while stub-level storage uses INT.

**Update createStub Method (in RsConstantStub.Type object):**
```kotlin
override fun createStub(psi: RsConstant, parentStub: StubElement<*>?): RsConstantStub {
    var flags = RsAttributeOwnerStub.extractFlags(psi)
    flags = BitUtil.set(flags, IS_MUT_MASK, psi.isMut)
    flags = BitUtil.set(flags, IS_CONST_MASK, psi.isConst)
    flags = BitUtil.set(flags, IS_SAFE_MASK, psi.safe != null)    // ← NEW
    flags = BitUtil.set(flags, IS_UNSAFE_MASK, psi.unsafe != null) // ← NEW
    // ... rest of method
}
```

**Effort:** 15 minutes

---

#### 2.5.2 Add Safety Accessors to RsConstant

**File:** `src/main/kotlin/org/rust/lang/core/psi/ext/RsConstant.kt`
**Location:** After existing properties (near isConst, before kind property)

**Add:**
```kotlin
val RsConstant.isSafe: Boolean get() = greenStub?.isSafe ?: (safe != null)

val RsConstant.isUnsafe: Boolean get() = greenStub?.isUnsafe ?: (unsafe != null)
```

**Effort:** 5 minutes

---

### Phase 2 Checkpoint

**Verification Steps:**
1. Run `./gradlew :plugin:buildPlugin` - should compile
2. Run unit tests: `./gradlew :test --tests "*Stub*"`
3. Verify stub version incremented
4. Check that old stub files are invalidated

**Success Criteria:**
- [ ] RsForeignModStub includes `isUnsafe` field
- [ ] Serialization/deserialization updated
- [ ] Stub version bumped to 235
- [ ] RsForeignModItem.isUnsafe property works
- [ ] All stub tests pass
- [ ] Project compiles without errors

**Blocking Issues:**
- May need to handle stub migration for existing indexed projects

---

## Phase 3: Parser & Stub Tests (T)

**Estimated Effort:** 4-5 hours
**Prerequisites:** Phase 1 & 2 complete
**Branch:** Continue from Phase 2

**Note:** Following TDD, these tests should ideally be written BEFORE implementation, but documenting here for tracking.

---

### 3.1 Create Parser Test Fixture

**File:** `src/test/resources/org/rust/lang/core/parser/fixtures/complete/unsafe_extern_blocks.rs`

**Content:**
```rust
// Edition 2024 syntax - extern blocks require unsafe
unsafe extern "C" {
    pub safe fn sqrt(x: f64) -> f64;
    pub unsafe fn strlen(p: *const u8) -> usize;
    pub fn default_unsafe(ptr: *mut u8);  // defaults to unsafe

    pub safe static SAFE_CONSTANT: i32;
    pub unsafe static UNSAFE_PTR: *const u8;
    static DEFAULT_UNSAFE: *mut u8;  // defaults to unsafe

    pub type OpaqueType;
}

// Multiple ABIs
unsafe extern {
    safe fn no_abi_safe();
}

unsafe extern "system" {
    unsafe fn system_unsafe();
}

unsafe extern "Rust" {
    fn rust_abi_fn();
}

// Legacy syntax (pre-2024) - still valid without unsafe
extern "C" {
    fn legacy_function();
    static LEGACY_STATIC: i32;
}

// Nested in modules
mod ffi {
    unsafe extern "C" {
        pub safe fn nested_safe();
    }
}

// With attributes
#[link(name = "mylib")]
unsafe extern "C" {
    #[link_name = "actual_name"]
    safe fn attributed_fn();
}
```

**Effort:** 30 minutes

---

### 3.2 Add Parser Test Case

**File:** `src/test/kotlin/org/rust/lang/core/parser/RsCompleteParsingTestCase.kt`
**Location:** Add to test class (around line 29)

**Add:**
```kotlin
fun `test unsafe extern blocks`() = doTest(true)
```

**Effort:** 5 minutes

---

### 3.3 Update Existing Parser Fixture

**File:** `src/test/resources/org/rust/lang/core/parser/fixtures/complete/extern_block.rs`
**Location:** Lines 20-21

**Current:**
```rust
unsafe extern {} // semantically invalid
pub unsafe extern {} // semantically invalid
```

**Updated:**
```rust
unsafe extern {} // semantically valid in edition 2024
pub unsafe extern {} // semantically valid in edition 2024
```

**Effort:** 5 minutes

---

### 3.4 Create Stub Test

**File:** `src/test/kotlin/org/rust/lang/core/stubs/RsForeignModStubTest.kt` (NEW FILE)

**Test Structure:** Inherit from `RsTestBase` and use the `doStubTreeTest()` helper pattern for stub validation.

**Implementation Guidance:**

1. **Create a helper method** `doStubTreeTest()` that:
   - Takes Rust code and expected stub tree structure as strings
   - Uses `fileTreeFromText()` to create test file
   - Loads stub tree via `StubTreeLoader`
   - Compares actual stub tree with expected structure using `DebugUtil.stubTreeToString()`

2. **Write test cases covering:**
   - **Basic unsafe extern block** - verify stub structure for `unsafe extern "C" { fn foo(); }`
   - **Legacy safe extern block** - verify stub structure for `extern "C" { fn bar(); }`
   - **Unsafe extern without explicit ABI** - verify `unsafe extern { fn baz(); }` works
   - **Multiple items** - test extern block with multiple functions and statics
   - **Inner attributes** - test extern block containing `#![allow(...)]` style attributes

3. **Expected stub tree format:**
```
RsFileStub
  FOREIGN_MOD_ITEM:RsForeignModStub
    FUNCTION:RsFunctionStub
      VALUE_PARAMETER_LIST:RsPlaceholderStub
```

**Key Testing Points:**
- Verify stubs are created for all extern block variants
- Ensure inner attributes are properly captured in stub tree
- Test that multiple items (functions, statics) all appear in stub hierarchy
- Validate stub tree structure matches PSI hierarchy

**Effort:** 1 hour

---

### 3.5 Create Tests for Safe/Unsafe Function Qualifiers

**File:** `src/test/kotlin/org/rust/lang/core/psi/RsForeignItemTest.kt` (NEW FILE)

**Test Structure:** Inherit from `RsTestBase` and use the `InlineFile()` helper for creating test files.

**Implementation Guidance:**

1. **Use InlineFile() pattern** for test setup:
   - Call `InlineFile("""...""")` with Rust code
   - Access the file via `myFixture.file`
   - Use `descendantsOfType<T>()` to find elements

2. **Write 6 test cases covering:**
   - **Safe foreign function** - verify `isSafe` is true, `isActuallyUnsafe` is false
   - **Unsafe foreign function** - verify explicit `unsafe` qualifier is recognized
   - **Default (no qualifier) foreign function** - verify defaults to unsafe semantics
   - **Safe foreign static** - verify `isSafe` is true, `isUnsafe` is false
   - **Unsafe foreign static** - verify explicit `unsafe` qualifier on static
   - **Default foreign static** - verify neither `isSafe` nor `isUnsafe` are true

3. **Test Pattern Example:**
```kotlin
fun `test safe foreign function`() {
    InlineFile("""
        unsafe extern "C" {
            pub safe fn safe_fn();
        }
    """)
    val fn = myFixture.file.descendantsOfType<RsFunction>().single()
    assertTrue("Function should be marked as safe", fn.isSafe)
    assertFalse("Safe function should not be actually unsafe", fn.isActuallyUnsafe)
}
```

**Key Testing Points:**
- Verify PSI properties (`isSafe`, `isUnsafe`) correctly detect keywords
- Test semantic analysis (`isActuallyUnsafe`) - note this may require Phase 4 implementation
- Cover both functions and statics with all three qualifier states (safe, unsafe, none)

**Note:** The `test safe foreign function` case requires Phase 4's `isActuallyUnsafe` implementation to pass correctly.

**Effort:** 1 hour

---

### 3.6 Run Parser Tests

**Command:**
```bash
./gradlew :test --tests "org.rust.lang.core.parser.RsCompleteParsingTestCase"
```

**Success Criteria:**
- [ ] `test unsafe extern blocks` passes
- [ ] `test extern block` passes with updated fixture
- [ ] No regressions in other parsing tests

**Effort:** 15 minutes + fixes

---

### 3.7 Run Stub Tests

**Command:**
```bash
./gradlew :test --tests "org.rust.lang.core.stubs.RsForeignModStubTest"
./gradlew :test --tests "org.rust.lang.core.psi.RsForeignItemTest"
```

**Success Criteria:**
- [ ] RsForeignModStubTest: 4/4 tests pass
- [ ] RsForeignItemTest: 5/6 tests pass (1 blocked on Phase 4's `isActuallyUnsafe` implementation)
- [ ] Total: 9/10 tests passing
- [ ] No regressions in existing stub tests
- [ ] Stub serialization/deserialization works correctly

**Effort:** 15 minutes + fixes

---

### Phase 3 Checkpoint

**Verification Steps:**
1. Parser test passes: `test unsafe extern blocks`
2. Stub tests pass: RsForeignModStubTest (4/4), RsForeignItemTest (5/6)
3. Test coverage includes edge cases (no ABI, various qualifiers)
4. Legacy syntax still parses correctly
5. Total test results: 9/10 passing, 1 requires Phase 4

**Success Criteria:**
- [ ] Parser accepts all unsafe extern syntax variants
- [ ] Stubs correctly store isUnsafe flag
- [ ] Safe/unsafe qualifiers on items are recognized
- [ ] 9/10 tests passing (1 test properly blocked on Phase 4 semantic analysis)
- [ ] No test regressions

**Blocking Issues:**
- Parser test failures may indicate grammar issues (return to Phase 1)
- Stub test failures may indicate serialization issues (return to Phase 2)
- Expected: 1 test in RsForeignItemTest requires Phase 4's semantic analysis

---

## Phase 4: Semantic Analysis (ANN, RES, TY)

**Estimated Effort:** 6-8 hours
**Prerequisites:** Phase 1-3 complete
**Branch Suggestion:** `feature/unsafe-extern-semantics` or continue

---

### 4.1 Update Function Unsafety Logic

**File:** `src/main/kotlin/org/rust/lang/core/psi/ext/RsFunction.kt`
**Location:** Line 165 (isActuallyUnsafe property)

**Current:**
```kotlin
val RsFunction.isActuallyUnsafe: Boolean
    get() {
        if (isUnsafe) return true
        val context = context
        return if (context is RsForeignModItem) {
            // functions inside `extern` block are unsafe in most cases
            when {
                // #[wasm_bindgen] special case
                context.queryAttributes.hasAttrWithName("wasm_bindgen") -> false
                else -> true
            }
        } else {
            false
        }
    }
```

**Updated:**
```kotlin
/**
 * A function is unsafe if defined with `unsafe` modifier or if defined inside a certain `extern`
 * block. But [RsFunction.isUnsafe] takes into account only `unsafe` modifier. [isActuallyUnsafe]
 * takes into account both cases.
 *
 * For functions in unsafe extern blocks, items marked with `safe` are safe to call.
 * Items without explicit marker default to unsafe.
 */
val RsFunction.isActuallyUnsafe: Boolean
    get() {
        // Explicit unsafe modifier always makes it unsafe
        if (isUnsafe) return true

        val context = context
        return if (context is RsForeignModItem) {
            // Explicit safe marker makes it safe
            if (isSafe) return false

            // If in unsafe extern block, default to unsafe
            if (context.isUnsafe) return true

            // Legacy extern blocks (pre-2024): functions are unsafe unless special case
            when {
                // #[wasm_bindgen] is a procedural macro that removes the following
                // extern block, so all functions inside it become safe.
                context.queryAttributes.hasAttrWithName("wasm_bindgen") -> false
                else -> true
            }
        } else {
            false
        }
    }
```

**Effort:** 30 minutes

---

### 4.2 Create Semantic Tests for Unsafety

**File:** `src/test/kotlin/org/rust/lang/core/resolve/RsForeignFunctionSafetyTest.kt` (NEW FILE)

**Content:**
```kotlin
package org.rust.lang.core.resolve

import org.rust.lang.core.psi.ext.isActuallyUnsafe

class RsForeignFunctionSafetyTest : RsCodeInsightTestBase() {

    fun `test safe foreign function is safe`() = checkByCode("""
        unsafe extern "C" {
            pub safe fn sqrt(x: f64) -> f64;
        }

        fn main() {
            sqrt(4.0);  // Should not require unsafe block
        }
    """) {
        val fn = findElementInEditor<RsFunction>("sqrt")
        assertFalse(fn.isActuallyUnsafe)
    }

    fun `test unsafe foreign function is unsafe`() = checkByCode("""
        unsafe extern "C" {
            pub unsafe fn dangerous();
        }

        fn main() {
            dangerous();  // Should require unsafe block
        }
    """) {
        val fn = findElementInEditor<RsFunction>("dangerous")
        assertTrue(fn.isActuallyUnsafe)
    }

    fun `test default foreign function is unsafe`() = checkByCode("""
        unsafe extern "C" {
            pub fn implicit_unsafe();
        }

        fn main() {
            implicit_unsafe();  // Should require unsafe block
        }
    """) {
        val fn = findElementInEditor<RsFunction>("implicit_unsafe")
        assertTrue(fn.isActuallyUnsafe)
    }

    fun `test wasm_bindgen special case still works`() = checkByCode("""
        #[wasm_bindgen]
        extern "C" {
            pub fn console_log();
        }

        fn main() {
            console_log();  // Should not require unsafe
        }
    """) {
        val fn = findElementInEditor<RsFunction>("console_log")
        assertFalse(fn.isActuallyUnsafe)
    }
}
```

**Effort:** 1.5 hours

---

### 4.3 Update Unsafe Expression Annotator

**File:** `src/main/kotlin/org/rust/ide/annotator/RsUnsafeExpressionErrorAnnotator.kt`

**Analysis Required:** Verify that annotator already uses `isActuallyUnsafe` - if so, no changes needed. If it uses a different check, update to use `isActuallyUnsafe`.

**Search for:** Call expression checking logic

**Expected:** Should automatically work with updated `isActuallyUnsafe` logic

**Effort:** 30 minutes (investigation + potential fix)

---

### 4.4 Create Tests for Unsafe Call Checking

**File:** `src/test/kotlin/org/rust/ide/annotator/RsUnsafeExpressionErrorAnnotatorTest.kt`
**Location:** Add to existing test class

**Add:**
```kotlin
fun `test safe foreign function call without unsafe block`() = checkByCode("""
    unsafe extern "C" {
        pub safe fn sqrt(x: f64) -> f64;
    }

    fn main() {
        sqrt(4.0);  // OK - no error
    }
""")

fun `test unsafe foreign function call requires unsafe block`() = checkByCode("""
    unsafe extern "C" {
        pub unsafe fn dangerous();
    }

    fn main() {
        <error descr="Call to unsafe function requires unsafe function or block [E0133]">dangerous</error>();
    }
""")

fun `test default foreign function call requires unsafe block`() = checkByCode("""
    unsafe extern "C" {
        pub fn implicit_unsafe();
    }

    fn main() {
        <error descr="Call to unsafe function requires unsafe function or block [E0133]">implicit_unsafe</error>();
    }
""")

fun `test safe foreign function call in unsafe block`() = checkByCode("""
    unsafe extern "C" {
        pub safe fn sqrt(x: f64) -> f64;
    }

    fn main() {
        unsafe { sqrt(4.0); }  // OK - safe functions don't need unsafe, but allowed
    }
""")
```

**Effort:** 1 hour

---

### 4.5 Run Semantic Tests

**Command:**
```bash
./gradlew :test --tests "*RsUnsafe*"
./gradlew :test --tests "*RsForeignFunctionSafety*"
```

**Success Criteria:**
- [ ] Safe foreign functions can be called without unsafe
- [ ] Unsafe foreign functions require unsafe blocks
- [ ] Default (unmarked) foreign functions require unsafe blocks
- [ ] wasm_bindgen special case preserved

**Effort:** 30 minutes + fixes

---

### Phase 4 Checkpoint

**Verification Steps:**
1. Run unsafe expression tests
2. Run foreign function safety tests
3. Manually test in RunIde
4. Check no regressions in existing unsafe tests

**Success Criteria:**
- [ ] `isActuallyUnsafe` logic correctly handles all cases
- [ ] Annotator properly highlights unsafe calls
- [ ] Safe functions don't trigger unsafe errors
- [ ] Unsafe/default functions trigger appropriate errors
- [ ] No test regressions

**Blocking Issues:**
- May need to update multiple annotators if logic is duplicated

---

## Phase 5: Edition-Based Validation (ANN, INSP)

**Estimated Effort:** 3.5-5.5 hours
**Prerequisites:** Phase 1-4 complete
**Branch:** Continue from Phase 4

---

### 5.1 Add Missing Unsafe on Extern Block Error

**File:** `src/main/kotlin/org/rust/ide/annotator/RsErrorAnnotator.kt`

**Location:** Find the annotator method handling `RsForeignModItem` (search for a visitor method or add to existing checks)

**Implementation Note:** Use the standard `isAtLeastEdition2024` property (defined in `RsElement.kt`) for edition detection. This follows the established pattern used throughout the codebase (e.g., `RsEdition2024KeywordsAnnotator`).

**Add:**
```kotlin
private fun checkForeignModItem(holder: RsAnnotationHolder, element: RsForeignModItem) {
    checkMissingUnsafeOnExternBlock(holder, element)
}

private fun checkMissingUnsafeOnExternBlock(holder: RsAnnotationHolder, element: RsForeignModItem) {
    // Only check for edition 2024+
    if (!element.isAtLeastEdition2024) return

    if (!element.isUnsafe) {
        val externKeyword = element.externAbi.extern
        holder.createErrorAnnotation(
            externKeyword,
            RsBundle.message("inspection.message.extern.blocks.must.be.unsafe.in.edition.2024"),
            AddUnsafeToExternBlockFix(element)
        )
    }
}
```

**Effort:** 30 minutes

---

### 5.2 Create Quick Fix: Add Unsafe to Extern Block

**File:** `src/main/kotlin/org/rust/ide/annotator/fixes/AddUnsafeToExternBlockFix.kt` (NEW FILE)

**Content:**
```kotlin
package org.rust.ide.annotator.fixes

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.rust.lang.core.psi.RsForeignModItem
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.ext.startOffset

class AddUnsafeToExternBlockFix(
    element: RsForeignModItem
) : LocalQuickFixAndIntentionActionOnPsiElement(element) {

    override fun getText(): String = "Add `unsafe` keyword to extern block"

    override fun getFamilyName(): String = text

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement
    ) {
        val foreignMod = startElement as? RsForeignModItem ?: return
        val externKeyword = foreignMod.extern ?: return

        val unsafeKeyword = RsPsiFactory(project).createUnsafeKeyword()
        foreignMod.addBefore(unsafeKeyword, externKeyword)
        foreignMod.addAfter(RsPsiFactory(project).createWhitespace(" "), unsafeKeyword)
    }
}
```

**Note:** May need to create helper in RsPsiFactory for creating keyword elements.

**Effort:** 1 hour

---

### 5.3 Add Inspection: Missing Unsafe on Extern (Opt-in for pre-2024)

**File:** `src/main/kotlin/org/rust/ide/inspections/lints/RsMissingUnsafeOnExternInspection.kt` (NEW FILE)

**Content:**
```kotlin
package org.rust.ide.inspections.lints

import com.intellij.codeInspection.ProblemsHolder
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.ide.annotator.fixes.AddUnsafeToExternBlockFix
import org.rust.ide.inspections.RsProblemsHolder
import org.rust.lang.core.psi.RsForeignModItem
import org.rust.lang.core.psi.RsVisitor

/**
 * Inspection for extern blocks missing unsafe keyword.
 * This is an error in Edition 2024, but a warning/suggestion in earlier editions
 * to prepare for migration.
 */
class RsMissingUnsafeOnExternInspection : RsLintInspection() {

    override fun getLint() = Lints.MissingUnsafeOnExtern

    override fun buildVisitor(holder: RsProblemsHolder, isOnTheFly: Boolean): RsVisitor =
        object : RsVisitor() {
            override fun visitForeignModItem(mod: RsForeignModItem) {
                if (mod.isUnsafe) return

                // In edition 2024, this is handled as an error by RsErrorAnnotator
                // Only show warning for pre-2024 editions
                if (mod.isAtLeastEdition2024) return

                val externKeyword = mod.externAbi.extern
                holder.registerProblem(
                    externKeyword,
                    "Extern block should be marked unsafe (required in Edition 2024)",
                    AddUnsafeToExternBlockFix(mod)
                )
            }
        }

    companion object Lints {
        val MissingUnsafeOnExtern = RsLint(
            "missing_unsafe_on_extern",
            "Extern blocks should be marked unsafe",
            RsLintLevel.WARN,
            defaultLevel = RsLintLevel.ALLOW  // Opt-in for pre-2024
        )
    }
}
```

**Effort:** 1.5 hours

---

### 5.4 Register Inspection

**File:** `src/main/resources/META-INF/plugin.xml` (or appropriate XML)

**Add:**
```xml
<localInspection
    language="Rust"
    shortName="MissingUnsafeOnExtern"
    bundle="messages.RsBundle"
    key="inspections.missing.unsafe.on.extern.display.name"
    groupKey="inspections.group.name"
    enabledByDefault="false"
    level="WARNING"
    implementationClass="org.rust.ide.inspections.lints.RsMissingUnsafeOnExternInspection"/>
```

**Effort:** 15 minutes

---

### 5.5 Add Safe Mutable Static Validation

**File:** `src/main/kotlin/org/rust/ide/annotator/RsErrorAnnotator.kt`

**Add Method:**
```kotlin
private fun checkSafeMutableStatic(constant: RsConstant) {
    // Only check statics inside extern blocks
    val foreignMod = constant.context as? RsForeignModItem ?: return

    // Mutable statics cannot be marked safe
    if (constant.isMut && constant.isSafe) {
        val safeKeyword = constant.safe ?: return
        holder.newAnnotation(HighlightSeverity.ERROR,
            "Mutable static items in extern blocks cannot be marked safe")
            .range(safeKeyword)
            .withFix(RemoveSafeKeywordFix(constant))
            .create()
    }
}
```

**Call from:** `visitConstant` method

**Effort:** 30 minutes

---

### 5.6 Create Edition-Based Tests

**File:** `src/test/kotlin/org/rust/ide/annotator/RsErrorAnnotatorTest.kt`
**Location:** Add to existing test class

**Add:**
```kotlin
@MockEdition(CargoWorkspace.Edition.EDITION_2024)
fun `test missing unsafe on extern in edition 2024`() = checkByCode("""
    <error descr="Extern blocks must be unsafe in Edition 2024">extern</error> "C" {
        fn foo();
    }
""")

@MockEdition(CargoWorkspace.Edition.EDITION_2024)
fun `test unsafe extern in edition 2024`() = checkByCode("""
    unsafe extern "C" {
        fn foo();
    }
""")

@MockEdition(CargoWorkspace.Edition.EDITION_2021)
fun `test extern without unsafe in edition 2021 is allowed`() = checkByCode("""
    extern "C" {
        fn foo();
    }
""")

fun `test safe mutable static in extern block is error`() = checkByCode("""
    unsafe extern "C" {
        pub <error descr="Mutable static items in extern blocks cannot be marked safe">safe</error> static mut FOO: i32;
    }
""")

fun `test safe immutable static in extern block is ok`() = checkByCode("""
    unsafe extern "C" {
        pub safe static BAR: i32;
    }
""")

fun `test unsafe mutable static in extern block is ok`() = checkByCode("""
    unsafe extern "C" {
        pub unsafe static mut FOO: i32;
    }
""")
```

**Note:** Verify `@MockEdition` annotation exists, or adapt to existing edition mocking mechanism.

**Effort:** 1 hour

---

### 5.7 Run Edition Tests

**Command:**
```bash
./gradlew :test --tests "org.rust.ide.annotator.RsErrorAnnotatorTest"
```

**Success Criteria:**
- [ ] Edition 2024 requires unsafe on extern blocks
- [ ] Pre-2024 editions allow extern without unsafe
- [ ] Inspection properly warns in pre-2024 editions
- [ ] Safe mutable statics are rejected
- [ ] Quick fix adds unsafe keyword correctly

**Effort:** 30 minutes + fixes

---

### Phase 5 Checkpoint

**Verification Steps:**
1. Run error annotator tests
2. Test in RunIde with edition 2024 project
3. Test in RunIde with edition 2021 project
4. Verify quick fix works correctly

**Success Criteria:**
- [ ] Edition 2024 enforces unsafe on extern blocks
- [ ] Pre-2024 editions show optional warning
- [ ] Quick fix adds unsafe keyword
- [ ] Safe mutable statics produce errors
- [ ] All tests pass

**Blocking Issues:**
- Edition detection may vary by configuration - test with Cargo projects

---

## Phase 6: IDE Features & Polish (COMP, FMT)

**Estimated Effort:** 2-2.5 hours
**Prerequisites:** Phase 1-5 complete
**Branch:** Continue from Phase 5

---

### 6.1 Update Keyword Completion

**File:** `src/main/kotlin/org/rust/lang/core/completion/RsKeywordCompletionContributor.kt`

**Analysis Required:** Find where `unsafe` is suggested for extern blocks

**Add:** Suggest `safe` and `unsafe` for items inside extern blocks

**Expected Location:** Completion provider for modifiers

**Effort:** 1 hour

---

### 6.2 Update Formatter (if needed)

**Analysis Required:** Check if formatter needs updates for spacing around `safe` keyword

**File:** `src/main/kotlin/org/rust/ide/formatter/impl/spacing.kt`

**Expected:** Should handle automatically, but verify

**Effort:** 30 minutes (investigation)

---

### 6.3 Update Structure View (optional)

**File:** Check structure view implementation

**Consideration:** Should safe/unsafe qualifiers appear in structure view?

**Effort:** 30 minutes (investigation + implementation if desired)

---

### 6.4 Run IDE Feature Tests

**Command:**
```bash
./gradlew :test --tests "*Completion*"
```

**Success Criteria:**
- [ ] Keyword completion includes safe/unsafe
- [ ] All IDE feature tests pass

**Effort:** 30 minutes + fixes

---

### Phase 6 Checkpoint

**Verification Steps:**
1. Test code completion in RunIde
2. Verify formatter handles safe keyword
3. Check structure view

**Success Criteria:**
- [ ] Completion suggests appropriate keywords
- [ ] Formatter handles safe keyword correctly
- [ ] All IDE feature tests pass

**Blocking Issues:** None expected

---

## Phase 7: Integration & Final Testing

**Estimated Effort:** 4-6 hours
**Prerequisites:** All previous phases complete
**Branch:** Continue or create `feature/unsafe-extern-final`

---

### 7.1 Run Full Test Suite

**Command:**
```bash
./gradlew :test
```

**Expected:** 5000+ tests should pass

**Effort:** 30 minutes (test execution time) + fixes

---

### 7.2 Manual Testing in RunIde

**Command:**
```bash
./gradlew :plugin:runIde
```

**Test Cases:**
1. Create edition 2024 project
2. Write extern block without unsafe - verify error
3. Apply quick fix - verify adds unsafe
4. Write safe foreign function - verify no unsafe required for calls
5. Write unsafe foreign function - verify unsafe required for calls
6. Test completion
7. Switch project to edition 2021 - verify no errors

**Effort:** 2 hours

---

### 7.3 Performance Testing

**Considerations:**
- Stub version bump forces re-indexing
- Check indexing performance on large projects
- Verify no performance regression in name resolution

**Effort:** 1 hour

---

### 7.4 Update Documentation

**Files to update:**
- `CHANGELOG.md` (if exists)
- `ARCHITECTURE.md` - document new PSI elements
- Update CLAUDE.md if needed

**Effort:** 1 hour

---

### 7.5 Verify Build Plugin

**Command:**
```bash
./gradlew :plugin:buildPlugin
```

**Success Criteria:**
- [ ] Build succeeds
- [ ] Plugin ZIP created
- [ ] Can install in test IDE

**Effort:** 15 minutes

---

### Phase 7 Checkpoint

**Verification Steps:**
1. All tests pass
2. Manual testing complete
3. No performance regressions
4. Documentation updated
5. Plugin builds successfully

**Success Criteria:**
- [ ] All 5000+ tests pass
- [ ] Manual testing scenarios work
- [ ] No performance issues
- [ ] Documentation complete
- [ ] Plugin builds and installs

**Blocking Issues:** Any test failures must be resolved

---

## Final Checklist

Before marking complete, verify:

### Code Quality
- [ ] All new code follows project conventions
- [ ] Commit messages use appropriate tags (GRAM, PSI, STUB, etc.)
- [ ] No commented-out code
- [ ] No debug print statements

### Testing
- [ ] All parser tests pass
- [ ] All stub tests pass
- [ ] All semantic tests pass
- [ ] All annotator tests pass
- [ ] All IDE feature tests pass
- [ ] Full test suite passes (5000+ tests)

### Documentation
- [ ] CLAUDE.md updated if needed
- [ ] ARCHITECTURE.md updated if needed
- [ ] Code comments explain complex logic
- [ ] This MIGRATION_PLAN.md marked complete

### Functionality
- [ ] Grammar accepts all unsafe extern syntax
- [ ] Stubs store isUnsafe flag correctly
- [ ] Edition 2024 enforces unsafe on extern blocks
- [ ] Safe functions don't require unsafe blocks for calls
- [ ] Unsafe functions require unsafe blocks for calls
- [ ] Quick fixes work correctly
- [ ] Completion suggests appropriate keywords

### Compatibility
- [ ] Pre-2024 editions still work
- [ ] wasm_bindgen special case preserved
- [ ] No breaking changes to existing correct code
- [ ] Stub version bumped (forces re-index)

---

## Risk Matrix

| Risk | Likelihood | Impact | Mitigation | Status |
|------|-----------|--------|------------|--------|
| Stub format change breaks existing indexes | High | High | Bump stub version, communicate to users | ⚠️ Expected |
| Edition detection fails in some configs | Medium | Medium | Extensive testing with various project types | Monitor |
| Performance regression from additional checks | Low | Low | Profile before/after, optimize if needed | Minimal overhead |
| Breaking existing extern block code | Low | High | Comprehensive regression testing | Backward compatible |
| wasm_bindgen special case breaks | Low | High | Dedicated test coverage | Covered in tests |

**Risks Mitigated by Architecture:**
- ✓ **Grammar conflicts** - Minimal: Simple flag additions to existing rules
- ✓ **API fragmentation** - None: No new PSI types, no breaking changes
- ✓ **Code duplication bugs** - None: Centralized implementation in existing types
- ✓ **Maintenance burden** - Low: Single update path for function/constant changes

---

## Dependencies Between Phases

```
Phase 1 (Grammar)
    ↓
Phase 2 (PSI/Stubs) ← Requires generated parser from Phase 1
    ↓
Phase 3 (Tests) ← Tests verify Phase 1 & 2 implementation
    ↓
Phase 4 (Semantics) ← Requires PSI from Phase 2
    ↓
Phase 5 (Edition Validation) ← Requires semantics from Phase 4
    ↓
Phase 6 (IDE Features) ← Requires all previous phases
    ↓
Phase 7 (Integration) ← Final validation of all phases
```

**Critical Path:** Phase 1 → Phase 2 → Phase 4 → Phase 5
**Can be parallelized:** Some tests in Phase 3 can be written during Phase 1/2

---

## Effort Summary

| Phase | Estimated Effort | Complexity | Notes |
|-------|------------------|------------|-------|
| Phase 1: Grammar Changes | 2.5-3.5 hours | Low | Grammar + parser util changes |
| Phase 2: PSI & Stub Changes | 4-5 hours | Medium-High (stub changes) | Adding flags to existing stubs |
| Phase 3: Parser & Stub Tests | 4-5 hours | Medium | Standard test patterns |
| Phase 4: Semantic Analysis | 6-8 hours | Medium-High | Update existing safety logic |
| Phase 5: Edition Validation | 3.5-5.5 hours | Medium | Edition-specific checks |
| Phase 6: IDE Features | 2-2.5 hours | Low-Medium | Completion |
| Phase 7: Integration & Testing | 4-6 hours | Medium | Full suite validation |
| **Total** | **26.5-36.5 hours** | **3.5-4.5 days** | |

**Note:** Estimates assume familiarity with codebase and no major blockers. Effort reflects extending existing PSI infrastructure rather than creating new types.

---

## Success Metrics

### Functional Success
- All unsafe extern syntax variants parse correctly
- Edition 2024 projects require unsafe on extern blocks
- Safe foreign functions can be called without unsafe blocks
- Unsafe foreign functions require unsafe blocks
- Quick fixes and intentions work as expected

### Quality Success
- Zero test regressions
- All new tests pass
- Code coverage maintained or improved
- No performance regressions

### User Success
- Migration path clear for edition 2024 users
- Helpful error messages and quick fixes
- Documentation explains new syntax
- Existing code continues to work

---

## Post-Implementation Tasks

After completing all phases:

1. **Code Review**
   - Request review from maintainers
   - Address feedback

2. **Release Planning**
   - Coordinate stub version bump with release
   - Prepare release notes
   - Test with beta users if available

3. **User Communication**
   - Announce feature in changelog
   - Explain stub re-indexing requirement
   - Provide migration guide for edition 2024

4. **Monitoring**
   - Watch for bug reports
   - Monitor performance metrics
   - Gather user feedback

---

## Notes & Open Questions

1. **Stub Field Storage:**
   - RsForeignModStub uses boolean for isUnsafe - this is acceptable and consistent with similar cases
   - RsFunctionStub and RsConstantStub use bit flags - adding IS_SAFE_MASK fits existing pattern
   - **Conclusion:** Current approach is consistent, no changes needed

2. **Edition Detection:** The standard `isAtLeastEdition2024` property (from `RsElement.kt`) is used for edition checks, following the established pattern throughout the codebase.
   - **Action:** Test in Phase 5 with various project types to ensure edition detection works correctly

3. **Formatter:** Need to verify spacing around `safe` keyword matches existing formatter conventions.
   - **Expected:** Should handle automatically like `unsafe`, `const`, `async`
   - **Action:** Verify in Phase 6

4. **Structure View:** Should safe/unsafe qualifiers be displayed? Consider UX implications.
   - **Defer:** Can be added in future enhancement if user feedback requests it
   - **Not blocking:** Core functionality works without structure view changes

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 1.0 | TBD | Initial migration plan created | - |
| 1.1 | 2025-10-31 | Finalized architectural approach based on 2016 patterns; added historical evidence and rationale; updated effort estimates (26-36h); added Architectural Rationale section | Claude Code |
| 1.2 | 2025-11-02 | Corrected Phase 1 to use proper Grammar-Kit contextual keyword pattern (`<<safeKeyword>>`); added RustParserUtil function step; clarified token remapping mechanism; updated effort to 26.5-36.5h | Claude Code |

---

**Status:** Ready for Implementation (Planning Complete)
**Last Updated:** 2025-11-02
**Assigned To:** TBD

**Key Architectural Decisions:**
- ✅ Extend existing Function/Constant types (following 2016 architectural pattern)
- ✅ Use bit flags (SAFE_MASK for RsFunction, IS_SAFE_MASK/IS_UNSAFE_MASK for RsConstant) in existing stub infrastructure
- ✅ Leverage owner-based context detection (RsAbstractableOwner.Foreign)
- ✅ Implement `safe` as contextual keyword using Grammar-Kit meta-rules and token remapping
- ✅ Total effort: 26.5-36.5 hours
