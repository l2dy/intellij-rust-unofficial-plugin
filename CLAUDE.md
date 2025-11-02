# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a community fork of the IntelliJ Rust plugin - an open-source plugin providing Rust language support for JetBrains IDEs. The plugin is built with Kotlin and targets IntelliJ Platform 2025.2 (platform version 252). It does not rely on JetBrains' proprietary "rust-capable" plugins, making it installable on any JetBrains IDE.

**Important**: This should NOT be installed simultaneously with other Rust plugins or used in RustRover.

## Build and Development Commands

### Essential Commands

```bash
# Build the plugin (creates distributable ZIP)
./gradlew :plugin:buildPlugin
# Output: plugin/build/distributions/intellij-rust-*.zip

# Run development IDE with plugin installed
./gradlew :plugin:runIde
# By default runs IntelliJ IDEA (baseIDE=IU in gradle.properties)

# Run all tests (5000+ tests)
./gradlew :test

# Run tests for a specific module
./gradlew :idea:test
./gradlew :plugin:test

# Generate lexer and parser (run after modifying .flex or .bnf files)
./gradlew :generateLexer
./gradlew :generateParser
```

### Testing

```bash
# Run a single test class
./gradlew :test --tests "org.rust.lang.core.parser.RustParserTest"

# Run tests with detailed output
./gradlew :test -PshowTestStatus=true

# Run tests with standard streams visible
./gradlew :test -PshowStandardStreams=true
```

### Native Code Compilation

```bash
# Build native helper (procedural macro expander)
# This is automatically triggered by prepareSandbox, but can be run manually:
cd native-helper && cargo build --release
# Disable with -PcompileNativeCode=false if needed
```

### Other Useful Tasks

```bash
# Verify plugin compatibility
./gradlew :plugin:verifyPlugin

# Resolve all dependencies (useful after gradle.properties changes)
./gradlew resolveDependencies
```

## Architecture Overview

### Module Structure

The plugin is organized into multiple Gradle submodules to separate platform-specific and feature-specific code:

- **`:` (root)** - Core module with Rust language support (parser, PSI, type inference, name resolution)
- **`:plugin`** - Aggregator module for building/publishing the final plugin artifact
- **`:idea`** - IDEA-specific functionality (Java integration)
- **`:copyright`** - Integration with copyright plugin
- **`:coverage`** - Code coverage integration
- **`:duplicates`** - Duplicate code detection
- **`:grazie`** - Grammar checking integration
- **`:js`** - JavaScript interop
- **`:ml-completion`** - ML-based code completion integration
- **`:grammar-kit-fake-psi-deps`** - Support module for parser generation

### Core Package Organization

- **`org.rust.lang`** - Language core: parser, PSI, name resolution, type inference
  - `lang.core.lexer` - Generated lexer from RustLexer.flex
  - `lang.core.parser` - Generated parser from RustParser.bnf
  - `lang.core.psi` - PSI (Program Structure Interface) elements
  - `lang.core.stubs` - Stub-based indexes for fast navigation
  - `lang.core.types.infer` - Type inference engine (modeled after rustc)
  - `lang.core.resolve` - Name resolution (lazy, upward-walking, see NameResolution.kt)

- **`org.rust.cargo`** - Cargo and rustup integration
  - `cargo.project.model` - Project model (CargoProject, CargoWorkspace, Package, Target)
  - `cargo.project.workspace` - Workspace management and cargo metadata parsing
  - `cargo.toolchain` - Rustc/cargo toolchain interaction

- **`org.rust.ide`** - IDE features built on lang/cargo
  - `ide.intentions` - Quick actions (Alt+Enter)
  - `ide.inspections` - Warnings and quick fixes
  - `ide.navigation.goto` - Go to Symbol/Class functionality

### Platform Version Support

The plugin uses version-specific source directories to handle IntelliJ Platform API changes:
- `src/252/main/kotlin` - Platform 252 (2025.2) specific code
- `src/main/kotlin` - Platform-independent code

Only one version's code is compiled based on `platformVersion` in gradle.properties.

### Parser and PSI Generation

The lexer and parser are generated from grammar files:
- **Lexer**: `src/main/grammars/RustLexer.flex` → generates `RustLexer.java` via JFlex
- **Parser**: `src/main/grammars/RustParser.bnf` → generates `RustParser.java` + PSI interfaces via Grammar-Kit

**After modifying grammar files, you MUST run the generator tasks:**
```bash
./gradlew :generateLexer :generateParser
```

PSI elements use a mixin pattern:
- Generated interface: `RsStructItem`
- Generated implementation: `RsStructItemImpl`
- Hand-written mixin: `RsStructItemImplMixin` (for custom logic)

### Project Model Concepts

Understanding the Cargo project model is critical for many features:

```
[CargoProject] (corresponds to Cargo.toml)
    ↓
[CargoWorkspace] (from `cargo metadata`)
    ↓
[Package] (dependencies, name/version)
    ↓
[Target] (lib.rs, main.rs, etc. - the compilable units)
```

- **CargoProject**: Represents a Cargo.toml linked to the IDE project
- **CargoWorkspace**: Contains packages, acquired via `cargo metadata`
- **Package**: A dependency unit (has name/version, found in [dependencies])
- **Target**: Compilable artifact (binary, lib, test) with a crate root file

Key distinction: Dependencies in Cargo.toml are **Packages**. The `extern crate foo` refers to a library **Target**.

### Name Resolution

Differs from rustc's top-down approach:
- **Lazy resolution**: Walks PSI tree upwards from reference point
- **Caching**: Results cached via `CachedValuesManager`, invalidated on PSI changes
- Only resolves currently opened file and its dependencies (ignores most of crate)
- See `NameResolution.kt` for implementation

### Type Inference

Located in `org.rust.lang.core.types.infer`:
- Happens at function/constant level (`RsInferenceContextOwner`)
- Top-down walk constructs expression → type mapping (`RsInferenceResult`)
- Uses type variables and `UnificationTable` for deferred inference
- Handles generics/traits via `ObligationForest` constraint solving
- Modeled after rustc's type checking

### Indexing and Stubs

Stub trees enable fast navigation without parsing:
- Condensed AST with only resolve-critical info (declarations, not bodies)
- Binary format persisted to disk
- PSI dynamically switches between stub-based and AST-based implementations
- See `org.rust.lang.core.stubs` package

Example: `RsModulesIndex` maps files to parent modules, handling #[path] attributes.

### Native Helper

The `native-helper` directory contains a Rust binary that provides procedural macro expansion support using rust-analyzer's proc-macro-srv. It's compiled during the build process and bundled with the plugin.

## Testing Patterns

### Test Structure

Tests use fixture-driven approach:
1. Load initial state (fixture file or inline string)
2. Execute action
3. Verify final state

Prefer triple-quoted Kotlin strings over separate fixture files:
```kotlin
fun `test something`() = checkFixByText("""
    fn foo() {
        /*caret*/
    }
""", """
    fn foo() {
        // fixed
    }
""")
```

Use `<caret>` marker for cursor position in fixtures.

### Running Tests

- Test files: `src/test/kotlin/`
- Test resources: `src/test/resources/` (less preferred)
- IntelliJ run configurations: `Test`, `RunIDEA`, `RunCLion`

## Commit Message Conventions

Prefix commits with tags describing the change area:

- `GRAM` - Grammar (.bnf) changes
- `PSI` - PSI changes
- `RES` - Name resolution
- `TY` - Type inference
- `COMP` - Code completion
- `STUB` - Stubs/indexes
- `MACRO` - Macro expansion
- `FMT` - Formatter
- `ANN` - Annotators/error highlighting
- `INSP` - Inspections
- `INT` - Intentions
- `CARGO` - Cargo integration
- `T` - Tests
- `GRD` - Gradle/build

Keep summary under 72 characters.

## Important Development Notes

### Java Version
- Requires Java 21 for development (VERSION_21 in build.gradle.kts)
- Kotlin 2.2.20 with API version 2.1

### Resource Variants
The plugin has channel-specific resources:
- `src/main/resources-stable/` - Stable releases
- `src/main/resources-nightly/` - Nightly/dev builds

Controlled by `publishChannel` property (dev, nightly, stable).

### When Making PSI Changes
1. Modify `RustParser.bnf` and/or `RustLexer.flex`
2. Run `./gradlew :generateLexer :generateParser`
3. Implement mixins if custom logic needed
4. Add stub support if needed for indexing
5. Add tests

### When Adding a New Inspection
- Extend from base inspection classes in `org.rust.ide.inspections`
- Add tests in `src/test/kotlin/org/rust/ide/inspections/`
- See PR #713 for example

### When Adding a New Intention
- Extend from base intention classes in `org.rust.ide.intentions`
- Add tests in `src/test/kotlin/org/rust/ide/intentions/`
- See PR #318 for example

## Key Configuration Files

- `gradle.properties` - Platform version, IDE selection, build settings
- `gradle-252.properties` - Platform 252 specific versions and plugin dependencies
- `settings.gradle.kts` - Multi-module project structure
- `build.gradle.kts` - Main build logic, tasks, and dependencies

## Documentation Resources

- [ARCHITECTURE.md](ARCHITECTURE.md) - Detailed architecture documentation
- [CONTRIBUTING.md](CONTRIBUTING.md) - Development environment setup and contribution guidelines
- [MAINTAINING.md](MAINTAINING.md) - Maintainer-specific information
- IntelliJ Platform SDK: https://www.jetbrains.org/intellij/sdk/docs/
- Custom Language Support: https://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support.html
- Grammar-Kit HOWTO: https://github.com/JetBrains/Grammar-Kit/blob/master/HOWTO.md
