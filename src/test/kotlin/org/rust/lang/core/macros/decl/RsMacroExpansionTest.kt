/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros.decl

import com.intellij.psi.tree.TokenSet
import org.rust.*
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace.Edition.EDITION_2021
import org.rust.cargo.util.parseSemVer
import org.rust.lang.core.macros.RsMacroExpansionTestBase
import org.rust.lang.core.psi.RS_KEYWORDS
import org.rust.lang.core.psi.RsElementTypes.CRATE
import org.rust.lang.core.psi.tokenSetOf
import org.rust.lang.core.resolve.NameResolutionTestmarks
import org.rust.stdext.BothEditions

class RsMacroExpansionTest : RsMacroExpansionTestBase() {
    fun `test ident`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => (
                fn $ i() {}
            )
        }
        foo! { bar }
    """, """
        fn bar() {}
    """)

    fun `test any rust keyword may be matched as an identifier`() {
        val keywords = RS_KEYWORDS.types.map { it.toString() } +
            listOf("true", "false")
        doTest("""
            macro_rules! foo {
                ($ i:ident) => (
                    bar! { $ i }
                )
            }
            ${keywords.joinToString("\n") { "foo! { $it }" }}
        """, *keywords.map { "bar! { $it }" }.toTypedArray())
    }

    fun `test any rust keyword may be used as a metavar name`() {
        val keywords = TokenSet.andNot(RS_KEYWORDS, tokenSetOf(CRATE)).types.map { it.toString() } +
            listOf("true", "false")
        doTest(keywords.joinToString("\n") {"""
            macro_rules! ${it}1 {
                ($ $it:ident) => (
                    use $ $it;
                )
            }
            ${it}1! { bar }
        """}, *keywords.map { "use bar;" }.toTypedArray())
    }

    // This test doesn't check result of '$crate' expansion because it's implementation detail.
    // For example rustc expands '$crate' to synthetic token without text representation
    fun `test '$crate' metavar is matched as identifier`() = doTest("""
        macro_rules! foo {
            () => { bar!($ crate); } // $ crate consumed as a single identifier by `bar`
        }
        macro_rules! bar {
            ($ i:ident) => { mod a {} }
        }
        foo! {}
    """, """
        mod a {}
    """)

    fun `test path`() = doTest("""
        macro_rules! foo {
            ($ i:path) => {
                fn foo() { let a = $ i; }
            }
        }
        foo! { foo }
        foo! { bar::<u8> }
        foo! { bar::baz::<u8> }
        foo! { bar::<u8>::baz::<u8> }
    """, """
        fn foo() { let a = foo; }
    """, """
        fn foo() { let a = bar::<u8>; }
    """, """
        fn foo() { let a = bar::baz::<u8>; }
    """, """
        fn foo() { let a = bar::<u8>::baz::<u8>; }
    """)

    fun `test type-like path`() = doTest("""
        macro_rules! foo {
            ($ i:path) => {
                struct Foo<F: $ i> { inner: F }
            }
        }
        foo! { Bar<Baz> }
    """, """
        struct Foo<F: Bar<Baz>> { inner: F }
    """)

    fun `test expr`() = doTest("""
        macro_rules! foo {
            ($ i:expr) => ( fn bar() { $ i; } )
        }
        foo! { 2 + 2 * baz(3).quux() }
    """, """
         fn bar() { (2 + 2 * baz(3).quux()); }
    """)

    fun `test ty`() = doTest("""
        macro_rules! foo {
            ($ i:ty) => (
                fn bar() -> $ i { unimplemented!() }
            )
        }
        foo! { Baz<u8> }
    """, """
        fn bar() -> Baz<u8> { unimplemented!() }
    """)

    fun `test pat`() = doTest("""
        macro_rules! foo {
            ($ i:pat) => { fn foo() { let $ i; } }
        }
        foo! { (a, b) }
    """, """
        fn foo() { let (a, b); }
    """)

    fun `test pat_param`() = doTest("""
        macro_rules! foo {
            ($ i1:pat_param | $ i2:pat_param | $ i3:pat_param) => { fn foo() { let $ i1; let $ i2; let $ i3; } }
        }
        foo! { a | b | Some(1 | 2) }
    """, """
        fn foo() { let a; let b; let Some(1 | 2); }
    """)

    fun `test pat 2018 edition`() = doTest("""
        macro_rules! foo {
            ($ i1:pat | $ i2:pat) => { fn foo() { let $ i1; let $ i2; } }
        }
        foo! { a | b }
    """, """
        fn foo() { let a; let b; }
    """)

    @MockEdition(EDITION_2021)
    fun `test pat 2021 edition`() = expect<IllegalStateException> {
    doTest("""
        macro_rules! foo {
            ($ i:pat) => { fn foo() { let $ i; } }
        }
        foo! { a | b }
    """, """
        fn foo() { let a | b; }
    """)
    }

    fun `test stmt`() = doTest("""
        macro_rules! foo {
            ($ i:stmt) => (
                fn bar() { $ i; }
            )
        }
        foo! { 2 }
        foo! { let a = 0 }
    """, """
         fn bar() { 2; }
    """, """
         fn bar() { let a = 0; }
    """)

    fun `test block`() = doTest("""
        macro_rules! foo {
            ($ i:block) => { fn foo() $ i }
        }
        foo! { { 1; } }
    """, """
        fn foo() { 1; }
    """)

    fun `test meta`() = doTest("""
        macro_rules! foo {
            ($ i:meta) => (
                #[$ i]
                fn bar() {}
            )
        }
        foo! { cfg(target_os = "windows") }
    """, """
        #[cfg(target_os = "windows")]
         fn bar() {}
    """)

    fun `test meta with a macro call`() = doTest("""
        macro_rules! foo {
            ($ i:meta) => (
                #[$ i]
                fn bar() {}
            )
        }
        foo! { doc = concat!("foo", "bar") }
    """, """
        #[doc = concat!("foo", "bar")]
         fn bar() {}
    """)

    fun `test tt block`() = doTest("""
        macro_rules! foo {
            ($ i:tt) => { fn foo() $ i }
        }
        foo! { { 1; } }
    """, """
        fn foo() { 1; }
    """)

    fun `test tt collapsed token`() = doTest("""
        macro_rules! foo {
            ($ i:tt) => { fn foo() { true $ i false; } }
        }
        foo! { && }
    """, """
        fn foo() { true && false; }
    """)

    fun `test tt collapsed token 2`() = doTest("""
        macro_rules! foo {
            ($ i:tt) => { fn foo() { $ i; } }
        }
        foo! { 0.0 }
        foo! { 0. }
        foo! { 0.0f32 }
        foo! { 0.1e-3f32 }
    """, """
        fn foo() { 0.0; }
    """, """
        fn foo() { 0.; }
    """, """
        fn foo() { 0.0f32; }
    """, """
        fn foo() { 0.1e-3f32; }
    """)

    fun `test vis matcher`() = doTest("""
        macro_rules! foo {
            ($ vis:vis $ name:ident) => { $ vis fn $ name() {}};
        }
        foo!(pub foo);
        foo!(pub(crate) bar);
        foo!(pub(in a::b) baz);
        foo!(baz);
    """, """
        pub fn foo() {}
    """, """
        pub(crate) fn bar() {}
    """, """
        pub(in a::b) fn baz() {}
    """, """
        fn baz() {}
    """)

    fun `test lifetime matcher`() = doTest("""
        macro_rules! foo {
            ($ lt:lifetime) => { struct Ref<$ lt>(&$ lt str);};
        }
        foo!('a);
        foo!('lifetime);
    """, """
        struct Ref<'a>(&'a str);
    """, """
        struct Ref<'lifetime>(&'lifetime str);
    """)

    fun `test literal matcher`() = doTest("""
        macro_rules! foo {
            ($ type:ty $ lit:literal) => { const VALUE: $ type = $ lit;};
        }
        foo!(u8 0);
        foo!(&'static str "value");
        foo!(f64 0.0);
        foo!(f64 0.);
        foo!(f32 0.0f32);
        foo!(f32 0.1e-3f32);
        foo!(i32 -123);
        foo!(f64 -123.456);
    """, """
        const VALUE: u8 = 0;
    """, """
        const VALUE: &'static str = "value";
    """, """
        const VALUE: f64 = 0.0;
    """, """
        const VALUE: f64 = 0.;
    """, """
        const VALUE: f32 = 0.0f32;
    """, """
        const VALUE: f32 = 0.1e-3f32;
    """, """
        const VALUE: i32 = -123;
    """, """
        const VALUE: f64 = -123.456;
    """)

    fun `test tt group`() = doTest("""
        macro_rules! foo {
            ($($ i:tt)*) => { $($ i)* }
        }
        foo! { fn foo() {} }
    """, """
        fn foo() {}
    """)

    @CheckTestmarkHit(MacroExpansionMarks.GroupInputEnd1::class)
    fun `test empty group`() = doTest("""
        macro_rules! foo {
            ($ ($ i:item)*) => ($ ( $ i )*)
        }
        foo! {}
    """, "")

    fun `test group after empty group`() = doTest("""
        macro_rules! foo {
            ($($ i:meta)* ; $($ j:item)*) => {
                $($ j)*
            }
        }

        foo!{ ; fn foo() {} }
    """, """
        fn foo() {}
    """)

    @CheckTestmarkHit(MacroExpansionMarks.GroupInputEnd1::class)
    fun `test group with $crate usage`() = doTest("""
        macro_rules! foo {
            ($ ($ i:item)*; $ ($ j:item)*) => ($ ( use $ crate::$ i; )* $ ( use $ crate::$ i; )*)
        }
        foo! {;}
    """, "")

    fun `test all items`() = doTest("""
        macro_rules! foo {
            ($ ($ i:item)*) => ($ (
                $ i
            )*)
        }
        foo! {
            extern crate a;
            mod b;
            mod c {}
            use d;
            const E: i32 = 0;
            static F: i32 = 0;
            impl G {}
            struct H;
            enum I { Foo }
            trait J {}
            fn h() {}
            extern {}
            type T = u8;
        }
    """, """
        extern crate a;
        mod b;
        mod c {}
        use d;
        const E: i32 = 0;
        static F: i32 = 0;
        impl G {}
        struct H;
        enum I { Foo }
        trait J {}
        fn h() {}
        extern {}
        type T = u8;
    """)

    fun `test match complex pattern`() = doTest("""
        macro_rules! foo {
            (=/ $ i1:item #%*=> $ i2:item 0.0) => (
                $ i1
                $ i2
            )
        }
        foo! {
            =/
            fn foo() {}
            #%*=>
            fn bar() {}
            0.0
        }
    """, """
        fn foo() {}
        fn bar() {}
    """)

    @CheckTestmarkHit(MacroExpansionMarks.FailMatchPatternByToken::class)
    fun `test match pattern by first token`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => (
                mod $ i {}
            );
            (= $ i:ident) => (
                fn $ i() {}
            );
            (+ $ i:ident) => (
                struct $ i;
            )
        }
        foo! {   foo }
        foo! { = bar }
        foo! { + Baz }
    """, """
        mod foo {}
    """, """
        fn bar() {}
    """, """
        struct Baz;
    """)

    @CheckTestmarkHit(MacroExpansionMarks.FailMatchPatternByToken::class)
    fun `test match pattern by last token`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => (
                mod $ i {}
            );
            ($ i:ident =) => (
                fn $ i() {}
            );
            ($ i:ident +) => (
                struct $ i;
            )
        }
        foo! { foo }
        foo! { bar = }
        foo! { Baz + }
    """, """
        mod foo {}
    """, """
        fn bar() {}
    """, """
        struct Baz;
    """)

    @CheckTestmarkHit(MacroExpansionMarks.FailMatchPatternByToken::class)
    fun `test match pattern by word token 1`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => (
                mod $ i {}
            );
            (spam $ i:ident) => (
                fn $ i() {}
            );
            (eggs $ i:ident) => (
                struct $ i;
            )
        }
        foo! { foo }
        foo! { spam bar }
        foo! { eggs Baz }
    """, """
        mod foo {}
    """, """
        fn bar() {}
    """, """
        struct Baz;
    """)

    @CheckTestmarkHit(MacroExpansionMarks.FailMatchPatternByToken::class)
    fun `test match pattern by word token 2`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => (
                mod $ i {}
            );
            ('spam $ i:ident) => (
                fn $ i() {}
            );
            ('eggs $ i:ident) => (
                struct $ i;
            )
        }
        foo! { foo }
        foo! { 'spam bar }
        foo! { 'eggs Baz }
    """, """
        mod foo {}
    """, """
        fn bar() {}
    """, """
        struct Baz;
    """)

    @CheckTestmarkHit(MacroExpansionMarks.FailMatchPatternByToken::class)
    fun `test match pattern by word token 3`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => (
                mod $ i {}
            );
            ("spam" $ i:ident) => (
                fn $ i() {}
            );
            ("eggs" $ i:ident) => (
                struct $ i;
            )
        }
        foo! { foo }
        foo! { "spam" bar }
        foo! { "eggs" Baz }
    """, """
        mod foo {}
    """, """
        fn bar() {}
    """, """
        struct Baz;
    """)

    @CheckTestmarkHit(MacroExpansionMarks.FailMatchPatternByExtraInput::class)
    fun `test match pattern by binding type 1`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => (
                fn $ i() {}
            );
            ($ i:ty) => (
                struct Bar { field: $ i }
            )
        }
        foo! { foo }
        foo! { Baz<u8> }
    """, """
        fn foo() {}
    """, """
        struct Bar { field: Baz<u8> }
    """)

    @CheckTestmarkHit(MacroExpansionMarks.FailMatchPatternByBindingType::class)
    fun `test match pattern by binding type 2`() = doTest("""
        macro_rules! foo {
            ($ i:item) => (
                $ i
            );
            ($ i:ty) => (
                struct Bar { field: $ i }
            )
        }
        foo! { fn foo() {} }
        foo! { Baz<u8> }
    """, """
        fn foo() {}
    """, """
        struct Bar { field: Baz<u8> }
    """)

    fun `test match group pattern by separator token`() = doTest("""
        macro_rules! foo {
            ($ ($ i:ident),*) => ($ (
                mod $ i {}
            )*);
            ($ ($ i:ident)#*) => ($ (
                fn $ i() {}
            )*);
            ($ i:ident ,# $ j:ident) => (
                struct $ i;
                struct $ j;
            )
        }
        foo! { foo, bar }
        foo! { foo# bar }
        foo! { Foo,# Bar }
    """, """
        mod foo {}
        mod bar {}
    """ to null, """
        fn foo() {}
        fn bar() {}
    """ to MacroExpansionMarks.FailMatchGroupBySeparator, """
        struct Foo;
        struct Bar;
    """ to MacroExpansionMarks.FailMatchPatternByExtraInput)

    fun `test match 'asterisk' vs 'plus' group pattern`() = doTest("""
        macro_rules! foo {
            ($ ($ i:ident)+) => (
                mod plus_matched {}
            );
            ($ ($ i:ident)*) => (
                mod asterisk_matched {}
            );
        }
        foo! {  }
        foo! { foo }
    """, """
        mod asterisk_matched {}
    """ to MacroExpansionMarks.FailMatchGroupTooFewElements, """
        mod plus_matched {}
    """ to null)

    // TODO should work only on 2018 edition
    @CheckTestmarkHit(MacroExpansionMarks.QuestionMarkGroupEnd::class)
    fun `test match 'asterisk' vs 'q' group pattern`() = doTest("""
        macro_rules! foo {
            ($ ($ i:ident)?) => (
                mod question_matched {}
            );
            ($ ($ i:ident)*) => (
                mod asterisk_matched {}
            );
        }

        foo! {  }
        foo! { foo }
        foo! { foo bar }
    """, """
        mod question_matched {}
    """, """
        mod question_matched {}
    """, """
        mod asterisk_matched {}
    """)

    fun `test group pattern with collapsed token as a separator`() = doTest("""
        macro_rules! foo {
            ($ ($ i:ident)&&*) => ($ (
                mod $ i {}
            )*)
        }
        foo! { foo && bar }
    """, """
        mod foo {}
        mod bar {}
    """)

    fun `test insert group with separator token`() = doTest("""
        macro_rules! foo {
            ($ ($ i:expr),*) => {
                fn foo() { $ ($ i);*; }
            }
        }
        foo! { 1, 2, 3, 4 }
    """, """
        fn foo() { 1; 2; 3; 4; }
    """)

    fun `test match non-group pattern with asterisk`() = doTest("""
        macro_rules! foo {
            ($ ($ i:ident),*) => ($ (
                mod $ i {}
            )*);
            ($ i:ident ,* $ j:ident) => (
                struct $ i;
                struct $ j;
            )
        }
        foo! { foo, bar }
        foo! { Foo,* Bar }
    """, """
        mod foo {}
        mod bar {}
    """ to MacroExpansionMarks.GroupInputEnd3, """
        struct Foo;
        struct Bar;
    """ to MacroExpansionMarks.FailMatchPatternByExtraInput)

    fun `test multiple groups with reversed variables order`() = doTest("""
        macro_rules! foo {
            ($($ i:item),*; $($ e:expr),*) => {
                fn foo() { $($ e);*; }
                $($ i)*
            }
        }
        foo! { mod a {}, mod b {}; 1, 2 }
    """, """
        fn foo() { 1; 2; }
        mod a {}
        mod b {}
    """)

    fun `test nested groups`() = doTest("""
        macro_rules! foo {
            ($($ i:ident $($ e:expr),*);*) => {
                $(fn $ i() { $($ e);*; })*
            }
        }
        foo! { foo 1,2,3; bar 4,5,6 }
    """, """
        fn foo() { 1; 2; 3; }
        fn bar() { 4; 5; 6; }
    """)

    fun `test nested groups that uses vars from outer group`() = doTest("""
        macro_rules! foo {
            ($($ i:expr, $($ e:ident),*);*) => {
                $($( fn $ e() { $ i; } )*)*
            }
        }
        foo! { 1, foo, bar, baz; 2, quux, eggs }
    """, """
        fn foo() { 1; }
        fn bar() { 1; }
        fn baz() { 1; }
        fn quux() { 2; }
        fn eggs() { 2; }
    """)

    fun `test group in braces`() = doTest("""
        macro_rules! foo {
            ( { $($ i:item)* } $ j:expr) => {
                $( $ i )*
                fn foo() { $ j; }
            };
        }
        foo! {
            { mod a {} mod b {} }
            2
        }
    """, """
         mod a {}
         mod b {}
         fn foo() { 2; }
    """)

    @CheckTestmarkHit(MacroExpansionMarks.GroupInputEnd1::class)
    fun `test group with the separator the same as the next token 1`() = doTest("""
        macro_rules! foo {
            ($($ i:item)=* =) => {
                $($ i)*
            }
        }

        foo! {
            fn foo() {} =
        }
    """, """
         fn foo() {}
    """)

    @CheckTestmarkHit(MacroExpansionMarks.GroupInputEnd2::class)
    fun `test group with the separator the same as the next token 2`() = doTest("""
        macro_rules! foo {
            ($($ i:item)=* = #) => {
                $($ i)*
            }
        }

        foo! {
            fn foo() {} = #
        }
    """, """
         fn foo() {}
    """)

    fun `test distinguish between comma-ending groups`() = doTest("""
        macro_rules! foo {
            ($($ e:expr,)+) => { fn foo() { $( $ e; )+ } };
            ($($ e:expr),*) => { fn bar() { $( $ e; )* } };
        }
        foo!{ 1, 2, }
        foo!{ 1, 2 }
    """, """
        fn foo() { 1; 2; }
    """, """
        fn bar() { 1; 2; }
    """)

    fun `test merged groups`() = doTest("""
        macro_rules! foo {
            (($($ a:ident)*) ; ($($ b:block)*)) => {
                $(
                    fn $ a () $ b
                )*
            };
        }

        foo! {
            (bar baz) ;
            ({ 0; } { 1; })
        }
    """, """
        fn bar() { 0; }
        fn baz() { 1; }
    """)

    fun `test impl members context`() = checkSingleMacro("""
        macro_rules! foo {
            () => {
                fn foo() {}
                type Bar = u8;
                const BAZ: u8 = 0;
            }
        }

        struct S;
        impl S {
            foo!();
        }  //^
    """, """
        fn foo() {}
        type Bar = u8;
        const BAZ: u8 = 0;
    """)

    fun `test pattern context`() = checkSingleMacro("""
        macro_rules! foo {
            ($ i:ident, $ j:ident) => {
                ($ i, $ j)
            }
        }

        fn main() {
            let (foo!(a, b), c) = ((1, 2), 3);
        }      //^
    """, """
        (a, b)
    """)

    fun `test type context`() = checkSingleMacro("""
        macro_rules! foo {
            ($ i:ident, $ j:ident) => {
                $ i<$ j>
            }
        }

        fn bar() -> foo!(Option, i32) { unimplemented!() }
                  //^
    """, """
        Option<i32>
    """)

    fun `test stmt context`() = checkSingleMacro("""
        macro_rules! foo {
            ($ i:ident, $ j:ident) => {
                struct $ i;
                let $ j = 0;
                ($ i, $ j)
            }
        }

        fn main() {
            foo!(S, a);
        } //^
    """, """
        struct S;
        let a = 0;
        (S, a)
    """)

    // There was a problem with "debug" macro related to the fact that we parse macro call
    // with such name as a specific syntax construction
    fun `test macro with name 'debug'`() = doTest("""
        macro_rules! debug {
            ($ t:ty) => { fn foo() -> $ t {} }
        }
        debug!(i32);
    """, """
        fn foo() -> i32 {}
    """)

    fun `test macro with name 'vec'`() = doTest("""
       macro_rules! vec {
           ($ t:ty) => { fn foo() -> $ t {} }
       }
       vec!(i32);
    """, """
        fn foo() -> i32 {}
    """)

    @MinRustcVersion("1.82.0-nightly")
    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test standard 'vec!'`() {
        val rustcVersion = project.cargoProjects.singleProject().rustcInfo?.version?.semver!!
        // language=Rust
        val expansion = when {
            rustcVersion < RUST_1_63 -> "(<[_]>::into_vec(box [1, 2, 3]))"
            rustcVersion < RUST_1_86 -> """<[_]>::into_vec(
                #[rustc_box]
                    IntellijRustDollarCrate::boxed::Box::new([1, 2, 3])
            )"""
            else -> """<[_]>::into_vec(
                IntellijRustDollarCrate::boxed::box_new([1, 2, 3])
            )"""
        }
        checkSingleMacro("""
            fn main() {
                vec![1, 2, 3];
            } //^
        """, expansion)
    }

    fun `test expend macro definition`() = doTest("""
        macro_rules! foo {
            () => {
                macro_rules! bar { () => {} }
            }
        }
        foo!();
    """, """
        macro_rules! bar { () => {} }
    """)

    fun `test macro defined with a macro`() = doTest("""
        macro_rules! foo {
            () => {
                macro_rules! bar { () => { fn foo() {} } }
                bar!();
            }
        }
        foo!();
    """, """
        macro_rules! bar { () => { fn foo() {} } }
        fn foo() {}
    """)

    fun `test macro (with arguments) defined with a macro`() = doTest("""
        macro_rules! foo {
            ($ a:item) => {
                macro_rules! bar { ($ b:item) => { $ b }; }
                bar!($ a);
            }
        }
        foo!(fn foo() {});
    """, """
        macro_rules! bar { ($ b:item) => { $ b }; }
        fn foo() {}
    """ to MacroExpansionMarks.SubstMetaVarNotFound)

    fun `test expand macro defined in function`() = doTest("""
        fn main() {
            macro_rules! foo {
                () => { 2 + 2 };
            }
            foo!();
        }
    """, """
        2 + 2
    """)

    fun `test expand macro qualified with $crate`() = doTest("""
        macro_rules! foo {
            () => { $ crate::bar!(); };
        }
        #[macro_export]
        macro_rules! bar {
            () => { fn foo() {} };
        }
        foo!();
    """, """
        fn foo() {}
    """ to NameResolutionTestmarks.DollarCrateMagicIdentifier)

    @CheckTestmarkHit(MacroExpansionMarks.GroupMatchedEmptyTT::class)
    fun `test incorrect 'vis' group does not cause OOM`() = doErrorTest("""
        // error: repetition matches empty token tree
        macro_rules! foo {
            ($($ p:vis)*) => {}
        }
        foo!(a);
        //^
    """)

    fun `test two sequential groups starting with the same token`() = doTest("""
        macro_rules! foobar {
            (
                $(: foo)?
                $(: bar)?
                $ name:ident
            ) => {
                fn $ name(){}
            }
        }

        foobar!(: foo a);
        foobar!(: bar b);
        foobar!(c);
    """, """
        fn a(){}
    """, """
        fn b(){}
    """, """
        fn c(){}
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    @BothEditions
    fun `test local_inner_macros 1`() = checkSingleMacroByTree("""
    //- main.rs
        extern crate test_package;
        use test_package::foo;
        foo!();
        //^
    //- lib.rs
        #[macro_export(local_inner_macros)]
        macro_rules! foo {
            () => { bar!{} };
        }

        #[macro_export]
        macro_rules! bar {
            () => { fn bar() {} };
        }
    """, """
        fn bar() {}
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    @BothEditions
    fun `test local_inner_macros 2`() = checkSingleMacroByTree("""
    //- main.rs
        extern crate test_package;
        use test_package::foo;
        foo!();
        //^
    //- lib.rs
        #[macro_export(local_inner_macros)]
        macro_rules! foo {
            () => {
                macro_rules! bar {
                    () => { fn fake_bar() {} };
                }
                bar!{}
            };
        }

        #[macro_export]
        macro_rules! bar {
            () => { fn bar() {} };
        }
    """, """
        macro_rules! bar {
            () => { fn fake_bar() {} };
        }
        fn bar() {}
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    @BothEditions
    fun `test local_inner_macros 3`() = checkSingleMacroByTree("""
    //- main.rs
        extern crate test_package;
        use test_package::foo;
        macro_rules! bar {
            () => { fn local_bar() {} };
        }

        foo! { bar!{} }
        //^
    //- lib.rs
        #[macro_export(local_inner_macros)]
        macro_rules! foo {
            ($ i:item) => { bar!{} $ i };
        }

        #[macro_export]
        macro_rules! bar {
            () => { fn bar() {} };
        }
    """, """
        fn bar() {}
        fn local_bar() {}
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    @BothEditions
    fun `test local_inner_macros 4`() = checkSingleMacroByTree("""
    //- main.rs
        extern crate test_package;
        use test_package::foo;
        macro_rules! bar {
            () => { fn local_bar() {} };
        }
        macro_rules! baz {
            () => { foo! { bar!{} } };
        }

        baz!();
        //^
    //- lib.rs
        #[macro_export(local_inner_macros)]
        macro_rules! foo {
            ($ i:item) => { bar!{} $ i };
        }

        #[macro_export]
        macro_rules! bar {
            () => { fn bar() {} };
        }
    """, """
        fn bar() {}
        fn local_bar() {}
    """)

    fun `test item with docs`() = doTest("""
        macro_rules! foo {
            ($ i:item) => { $ i }
        }
        foo! {
            /// Some docs
            fn foo() {}
        }
    """, """
        #[doc = " Some docs"]
        fn foo() {}
    """ to MacroExpansionMarks.DocsLowering)

    fun `test docs lowering`() = doTest("""
        macro_rules! foo {
            (#[$ i:meta]) => {
                #[$ i]
                fn foo() {}
            }
        }
        foo! {
            /// Some docs
        }
    """, """
        #[doc = " Some docs"]
        fn foo() {}
    """ to MacroExpansionMarks.DocsLowering)

    fun `test comment in macro definition`() = doTest("""
        macro_rules! foobar {
            ($ name:ident) => {
                // Say hello
                pub fn $ name() {
                    /* Yet another comment */
                    println!("Hello!")
                }
            }
        }

        foobar!(foo);
    """, """
        pub fn foo() {
            println!("Hello!")
        }
    """)

    fun `test doc comment in macro definition`() = doTest("""
        macro_rules! foobar {
            ($ name:ident) => {
                /// outer doc comment
                pub mod $ name {
                    //! inner doc comment
                    /*!  - Inner block doc */

                    /** outer block comment */
                    pub fn foobar() {
                        println!("Hello!")
                    }
                }
            }
        }

        foobar!(foo);
    """, """
        /// outer doc comment
        pub mod foo {
            //! inner doc comment
            /*!  - Inner block doc */

            /** outer block comment */
            pub fn foobar() {
                println!("Hello!")
            }
        }
    """)

    fun `test literal expr is not wrapped into parens`() = doTest("""
        macro_rules! foo {
            ($ e:expr) => {
                #[cfg(feature = $ e)]
                fn bar() {}
            }
        }
        foo!("bar");
    """, """
        #[cfg(feature = "bar")]
        fn bar() {}
    """)

    fun `test macro call expr is not wrapped into parens`() = checkSingleMacro("""
        macro_rules! foo {
            ($ e:expr) => {
                $ e
            }
        }
        fn main() {
            let a = foo!(foo!(foo!(2 + 2)));
        }         //^
    """, """
        (2 + 2)
    """)

    fun `test path expr is not wrapped into parens`() = checkSingleMacro("""
        macro_rules! foo {
            ($ e:expr) => {
                $ e
            }
        }
        fn main() {
            let a = foo!(Vec::new);
        }         //^
    """, """
        Vec::new
    """)

    fun `test parens expr is not wrapped into parens`() = checkSingleMacro("""
        macro_rules! foo {
            ($ e:expr) => {
                $ e
            }
        }
        fn main() {
            let a = foo!((1 + 2));
        }         //^
    """, """
        (1 + 2)
    """)

    fun `test tuple expr is not wrapped into parens`() = checkSingleMacro("""
        macro_rules! foo {
            ($ e:expr) => {
                $ e
            }
        }
        fn main() {
            let a = foo!((1, 2));
        }         //^
    """, """
        (1, 2)
    """)

    fun `test array expr is not wrapped into parens`() = checkSingleMacro("""
        macro_rules! foo {
            ($ e:expr) => {
                $ e
            }
        }
        fn main() {
            let a = foo!([1, 2]);
        }         //^
    """, """
        [1, 2]
    """)

    fun `test unit expr is not wrapped into parens`() = checkSingleMacro("""
        macro_rules! foo {
            ($ e:expr) => {
                $ e
            }
        }
        fn main() {
            let a = foo!(());
        }         //^
    """, """
        ()
    """)

    fun `test block expr is not wrapped into parens`() = checkSingleMacro("""
        macro_rules! foo {
            ($ e:expr) => {
                $ e
            }
        }
        fn main() {
            let a = foo!({ 1234 });
        }         //^
    """, """
        { 1234 }
    """)

    fun `test matches! macro`() = checkSingleMacro("""
        macro_rules! matches {
            ($ expression:expr, $( $ pattern:pat_param )|+ $( if $ guard: expr )? $(,)?) => {
                match $ expression {
                    $( $ pattern )|+ $( if $ guard )? => true,
                    _ => false
                }
            }
        }
        fn main() {
            let _ = matches!(Some(1), Some(2) | Some(3));
        }           //^
    """, """
        match (Some(1)) {
            Some(2) | Some(3) => true,
            _ => false
        }
    """)

    fun `test 1-char macro call body`() = doTest(
    """
        macro_rules! foo {
            () => { fn bar() {} }
        }
        foo!{""", // '{' must be the LAST character in the file
    """
        fn bar() {}
    """)

    fun `test reserved keywords`() = doTest("""
        macro_rules! foo {
            ($ i:ident) => { fn foo () { let _ = stringify!($ i); } };
        }

        foo!(abstract);
        foo!(become);
        foo!(do);
        foo!(final);
        foo!(override);
        foo!(priv);
        foo!(typeof);
        foo!(unsized);
        foo!(virtual);
    """, """
        fn foo () { let _ = stringify!(abstract); }
    """, """
        fn foo () { let _ = stringify!(become); }
    """, """
        fn foo () { let _ = stringify!(do); }
    """, """
        fn foo () { let _ = stringify!(final); }
    """, """
        fn foo () { let _ = stringify!(override); }
    """, """
        fn foo () { let _ = stringify!(priv); }
    """, """
        fn foo () { let _ = stringify!(typeof); }
    """, """
        fn foo () { let _ = stringify!(unsized); }
    """, """
        fn foo () { let _ = stringify!(virtual); }
    """)

    fun `test macro call in an attribute`() = checkSingleMacro("""
        macro_rules! foo {
            () => {"bar"}
        }

        #[doc = foo!()]
              //^
        fn foo() {}
    """, """
        "bar"
    """)

    fun `test marker_impls using macro 2`() = doTest("""
        macro marker_impls {
            ( $(#[$($ meta:tt)*])* $ Trait:ident for $({$($ bounds:tt)*})? $ T:ty $(, $($ rest:tt)*)? ) => {
                    $(#[$($ meta)*])* impl< $($($ bounds)*)? > $ Trait for $ T {}
                    marker_impls! { $(#[$($ meta)*])* $ Trait for $($($ rest)*)? }
                },
            ( $(#[$($ meta:tt)*])* $ Trait:ident for ) => {},

            ( $(#[$($ meta:tt)*])* unsafe $ Trait:ident for $({$($ bounds:tt)*})? $ T:ty $(, $($ rest:tt)*)? ) => {
                    $(#[$($ meta)*])* unsafe impl< $($($ bounds)*)? > $ Trait for $ T {}
                    marker_impls! { $(#[$($ meta)*])* unsafe $ Trait for $($($ rest)*)? }
                },
            ( $(#[$($ meta:tt)*])* unsafe $ Trait:ident for ) => {},
        }
        marker_impls! {
            #[stable(feature = "rust1", since = "1.0.0")]
            Copy for
                usize, u8, u16, u32, u64, u128,
                isize, i8, i16, i32, i64, i128,
                f32, f64,
                bool, char,
                {T: ?Sized} *const T,
                {T: ?Sized} *mut T,

        }
    """, """
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for usize {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for u8 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for u16 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for u32 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for u64 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for u128 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for isize {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for i8 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for i16 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for i32 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for i64 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for i128 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for f32 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for f64 {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for bool {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<> Copy for char {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<T: ?Sized> Copy for *const T {}
        #[stable(feature = "rust1", since = "1.0.0")]
        impl<T: ?Sized> Copy for *mut T {}
    """)

    companion object {
        // BACKCOMPAT: Rust 1.62
        private val RUST_1_63 = "1.63.0".parseSemVer()
        // BACKCOMPAT: Rust 1.85
        private val RUST_1_86 = "1.86.0".parseSemVer()
    }
}
