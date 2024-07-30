/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import org.rust.*
import org.rust.cargo.project.workspace.CargoWorkspace.Edition

class RsCompletionTest : RsCompletionTestBase() {
    fun `test local variable`() = doSingleCompletion("""
        fn foo(quux: i32) { qu/*caret*/ }
    """, """
        fn foo(quux: i32) { quux/*caret*/ }
    """)

    fun `test function call zero args`() = doSingleCompletion("""
        fn frobnicate() {}
        fn main() { frob/*caret*/ }
    """, """
        fn frobnicate() {}
        fn main() { frobnicate()/*caret*/ }
    """)

    fun `test function call one arg`() = doSingleCompletion("""
        fn frobnicate(foo: i32) {}
        fn main() { frob/*caret*/ }
    """, """
        fn frobnicate(foo: i32) {}
        fn main() { frobnicate(/*caret*/) }
    """)

    fun `test function call with parens`() = doSingleCompletion("""
        fn frobnicate() {}
        fn main() { frob/*caret*/() }
    """, """
        fn frobnicate() {}
        fn main() { frobnicate()/*caret*/ }
    """)

    fun `test tuple struct with parens`() = doSingleCompletion("""
        struct Frobnicate(i32, String);
        fn main() { Frob/*caret*/() }
    """, """
        struct Frobnicate(i32, String);
        fn main() { Frobnicate/*caret*/() }
    """)

    fun `test tuple enum with parens`() = doSingleCompletion("""
        enum E { Frobnicate(i32, String) }
        fn main() { E::Frob/*caret*/() }
    """, """
        enum E { Frobnicate(i32, String) }
        fn main() { E::Frobnicate(/*caret*/) }
    """)

    fun `test tuple enum match`() = doSingleCompletion("""
        enum E { Frobnicate(i32, String) }
        fn foo(f: E) { match f {
            E::Frob/*caret*/() => {}
        }}
    """, """
        enum E { Frobnicate(i32, String) }
        fn foo(f: E) { match f {
            E::Frobnicate(/*caret*/) => {}
        }}
    """)

    fun `test struct-like enum with braces`() = doSingleCompletion("""
        enum E { Frobnicate { f: i32 } }
        fn main() { E::Frob/*caret*/ {} }
    """, """
        enum E { Frobnicate { f: i32 } }
        fn main() { E::Frobnicate {/*caret*/} }
    """)

    fun `test function call with parens with arg`() = doSingleCompletion("""
        fn frobnicate(foo: i32) {}
        fn main() { frob/*caret*/() }
    """, """
        fn frobnicate(foo: i32) {}
        fn main() { frobnicate(/*caret*/) }
    """)

    fun `test function call with parens overwrite`() = doSingleCompletion("""
        fn frobnicate(foo: i32) {}
        fn main() { frob/*caret*/transmog() }
    """, """
        fn frobnicate(foo: i32) {}
        fn main() { frobnicate(/*caret*/)transmog() }
    """)

    fun `test path`() = doSingleCompletion("""
        mod foo {
            pub mod bar { pub fn frobnicate() {} }
        }
        fn frobfrobfrob() {}

        fn main() {
            foo::bar::frob/*caret*/
        }
    """, """
        mod foo {
            pub mod bar { pub fn frobnicate() {} }
        }
        fn frobfrobfrob() {}

        fn main() {
            foo::bar::frobnicate()/*caret*/
        }
    """)

    fun `test anonymous item does not break completion`() = doSingleCompletion("""
        extern "C" { }
        fn frobnicate() {}

        fn main() {
            frob/*caret*/
        }
    """, """
        extern "C" { }
        fn frobnicate() {}

        fn main() {
            frobnicate()/*caret*/
        }
    """)

    fun `test use self`() = doSingleCompletion("""
        use se/*caret*/
    """, """
        use self::/*caret*/
    """)

    fun `test use super`() = doSingleCompletion("""
        mod m { use su/*caret*/ }
    """, """
        mod m { use super::/*caret*/ }
    """)

    fun `test use glob`() = doSingleCompletion("""
        mod foo { pub fn quux() {} }

        use self::foo::q/*caret*/;
    """, """
        mod foo { pub fn quux() {} }

        use self::foo::quux/*caret*/;
    """)

    fun `test use glob self`() = doSingleCompletion("""
        mod foo { }

        use self::foo::{sel/*caret*/};
    """, """
        mod foo { }

        use self::foo::{self/*caret*/};
    """)

    fun `test use glob global`() = doSingleCompletion("""
        pub struct Foo;

        mod m {
            use crate::F/*caret*/;
        }
    """, """
        pub struct Foo;

        mod m {
            use crate::Foo/*caret*/;
        }
    """)

    fun `test use item`() = doSingleCompletion("""
        mod foo { pub fn quux() {} }
        use self::foo::q/*caret*/;
    """, """
        mod foo { pub fn quux() {} }
        use self::foo::quux/*caret*/;
    """)

    fun `test use glob function no semicolon`() = doSingleCompletion("""
        mod foo { pub fn quux() {} }
        use self::foo::{q/*caret*/};
    """, """
        mod foo { pub fn quux() {} }
        use self::foo::{quux/*caret*/};
    """)

    fun `test use function semicolon`() = doSingleCompletion("""
        use self::f/*caret*/
        fn foo() {}
    """, """
        use self::foo;/*caret*/
        fn foo() {}
    """)

    fun `test use struct semicolon`() = doSingleCompletion("""
        use self::F/*caret*/
        struct Foo;
    """, """
        use self::Foo;/*caret*/
        struct Foo;
    """)

    fun `test use const semicolon`() = doSingleCompletion("""
        use self::F/*caret*/
        const Foo: str = "foo";
    """, """
        use self::Foo;/*caret*/
        const Foo: str = "foo";
    """)

    fun `test use static semicolon`() = doSingleCompletion("""
        use self::F/*caret*/
        static Foo: str = "foo";
    """, """
        use self::Foo;/*caret*/
        static Foo: str = "foo";
    """)

    fun `test use trait semicolon`() = doSingleCompletion("""
        use self::f/*caret*/
        trait foo{}
    """, """
        use self::foo;/*caret*/
        trait foo{}
    """)

    fun `test wildcard imports`() = doSingleCompletion("""
        mod foo { pub fn transmogrify() {} }

        fn main() {
            use self::foo::*;
            trans/*caret*/
        }
    """, """
        mod foo { pub fn transmogrify() {} }

        fn main() {
            use self::foo::*;
            transmogrify()/*caret*/
        }
    """)

    fun `test shadowing`() = doSingleCompletion("""
        fn main() {
            let foobar = "foobar";
            let foobar = foobar.to_string();
            foo/*caret*/
        }
    """, """
        fn main() {
            let foobar = "foobar";
            let foobar = foobar.to_string();
            foobar/*caret*/
        }
    """)

    fun `test complete alias`() = doSingleCompletion("""
        mod m { pub fn transmogrify() {} }
        use self::m::transmogrify as frobnicate;

        fn main() {
            frob/*caret*/
        }
    """, """
        mod m { pub fn transmogrify() {} }
        use self::m::transmogrify as frobnicate;

        fn main() {
            frobnicate()/*caret*/
        }
    """)

    fun `test complete self type`() = doSingleCompletion("""
        trait T { fn foo() -> Se/*caret*/ }
    """, """
        trait T { fn foo() -> Self/*caret*/ }
    """)

    fun `test complete Self type in nested impls`() = doSingleCompletion("""
        struct A;
        impl A {
            fn foo() {
                struct E {
                    value: i32
                }
                impl E {
                    fn new() -> Se/*caret*/ {}
                }
            }
        }
    """, """
        struct A;
        impl A {
            fn foo() {
                struct E {
                    value: i32
                }
                impl E {
                    fn new() -> Self/*caret*/ {}
                }
            }
        }
    """)

    fun `test complete self method`() = doFirstCompletion("""
        struct S;
        impl S { fn foo(&se/*caret*/) {}}
    """, """
        struct S;
        impl S { fn foo(&self/*caret*/) {}}
    """)

    fun `test complete self with double colon path method`() = doSingleCompletion("""
        struct S;
        impl S { fn foo(test: &se/*caret*/) {}}
    """, """
        struct S;
        impl S { fn foo(test: &self::/*caret*/) {}}
    """)

    fun `test struct field`() = doSingleCompletion("""
        struct S { foobarbaz: i32 }
        fn main() {
            let _ = S { foo/*caret*/ };
        }
    """, """
        struct S { foobarbaz: i32 }
        fn main() {
            let _ = S { foobarbaz/*caret*/ };
        }
    """)

    fun `test struct field pattern`() = doSingleCompletion("""
        struct S { foobarbaz: i32 }
        fn main() {
            let S { foo/*caret*/ } = S{ foobarbaz: 0 };
        }
    """, """
        struct S { foobarbaz: i32 }
        fn main() {
            let S { foobarbaz/*caret*/ } = S{ foobarbaz: 0 };
        }
    """)

    fun `test no outside type in struct field pattern`() = checkNotContainsCompletion("T", """
        struct T;
        struct S { a: i32 }
        fn main() {
            let S { /*caret*/ } = S{ a: 0 };
        }
    """)

    fun `test enum field`() = doSingleCompletion("""
        enum E { X { bazbarfoo: i32 } }
        fn main() {
            let _ = E::X { baz/*caret*/ }
        }
    """, """
        enum E { X { bazbarfoo: i32 } }
        fn main() {
            let _ = E::X { bazbarfoo/*caret*/ }
        }
    """)

    fun `test local scope`() = checkNoCompletion("""
        fn foo() {
            let x = spam/*caret*/;
            let spamlot = 92;
        }
    """)

    fun `test while let`() = checkNoCompletion("""
        fn main() {
            while let Some(quazimagnitron) = quaz/*caret*/ { }
        }
    """)

    fun `test completion respects namespaces`() = checkNoCompletion("""
        fn foobar() {}

        fn main() {
            let _: foo/*caret*/ = unimplemented!();
        }
    """)

    fun `test child file`() = doSingleCompletionByFileTree("""
    //- main.rs
        use foo::Spam;
        mod foo;
        fn main() { let _ = Spam::Q/*caret*/; }

    //- foo.rs
        pub enum Spam { Quux, Eggs }
    """, """
        use foo::Spam;
        mod foo;
        fn main() { let _ = Spam::Quux/*caret*/; }
    """)

    fun `test parent file`() = doSingleCompletionByFileTree("""
    //- main.rs
        mod foo;
        pub enum Spam { Quux, Eggs }

    //- foo.rs
        use super::Spam;
        fn foo() { let _ = Spam::Q/*caret*/; }
    """, """
        use super::Spam;
        fn foo() { let _ = Spam::Quux/*caret*/; }
    """)

    fun `test parent file 2`() = doSingleCompletionByFileTree("""
    //- main.rs
        mod foo;
        pub enum Spam { Quux, Eggs }

    //- foo/mod.rs
        use crate::Spam::Qu/*caret*/;
    """, """
        use crate::Spam::Quux/*caret*/;
    """)

    fun `test enum variant`() = doSingleCompletion("""
        enum Foo { BARBOO, BAZBAR }
        fn main() { let _ = Foo::BAZ/*caret*/ }
    """, """
        enum Foo { BARBOO, BAZBAR }
        fn main() { let _ = Foo::BAZBAR/*caret*/ }
    """)

    fun `test enum variant with tuple fields`() = doSingleCompletion("""
        enum Foo { BARBAZ(f64) }
        fn main() { let _ = Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ(f64) }
        fn main() { let _ = Foo::BARBAZ(/*caret*/) }
    """)

    fun `test enum variant with tuple fields in use block`() = doSingleCompletion("""
        enum Foo { BARBAZ(f64) }
        fn main() { use Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ(f64) }
        fn main() { use Foo::BARBAZ/*caret*/ }
    """)

    fun `test enum variant with block fields`() = doSingleCompletion("""
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { let _ = Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { let _ = Foo::BARBAZ {/*caret*/} }
    """)

    fun `test enum variant with block fields in use block`() = doSingleCompletion("""
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { use Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { use Foo::BARBAZ/*caret*/ }
    """)

    fun `test type namespace is completed for path head`() = doSingleCompletion("""
        struct FooBar { f: i32 }

        fn main() { Foo/*caret*/ }
    """, """
        struct FooBar { f: i32 }

        fn main() { FooBar/*caret*/ }
    """)

    // issue #1182
    fun `test associated type completion`() = doSingleCompletion("""
        trait Foo {
            type Bar;
            fn foo(bar: Self::Ba/*caret*/);
        }
    """, """
        trait Foo {
            type Bar;
            fn foo(bar: Self::Bar/*caret*/);
        }
    """)

    fun `test associated type completion without Self`() = doSingleCompletion("""
        trait Foo {
            type Bar;
            fn foo(bar: Ba/*caret*/);
        }
    """, """
        trait Foo {
            type Bar;
            fn foo(bar: Self::Bar/*caret*/);
        }
    """)

    fun `test associated type completion without Self as type argument`() = doSingleCompletion("""
        struct O<T>;
        trait Foo {
            type Bar;
            fn foo(bar: O<Ba/*caret*/>);
        }
    """, """
        struct O<T>;
        trait Foo {
            type Bar;
            fn foo(bar: O<Self::Bar/*caret*/>);
        }
    """)

    fun `test associated type completion without Self in impl`() = doSingleCompletion("""
        trait Foo {
            type Bar;
            fn foo(bar: Self::Bar);
        }
        struct Struct;
        impl Foo for Struct {
            type Bar = ();
            fn foo(bar: Ba/*caret*/) { todo!() }
        }
    """, """
        trait Foo {
            type Bar;
            fn foo(bar: Self::Bar);
        }
        struct Struct;
        impl Foo for Struct {
            type Bar = ();
            fn foo(bar: Self::Bar/*caret*/) { todo!() }
        }
    """)

    fun `test associated type completion without Self in from parent trait`() = doSingleCompletion("""
        trait Foo {
            type Bar;
        }
        trait Qux : Foo {
            fn baz() -> Ba/*caret*/;
        }
    """, """
        trait Foo {
            type Bar;
        }
        trait Qux : Foo {
            fn baz() -> Self::Bar/*caret*/;
        }
    """)

    fun `test complete enum variants 1`() = doFirstCompletion("""
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                Bi/*caret*/
            }
        }
    """, """
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                BinOp(/*caret*/)
            }
        }
    """)

    fun `test complete enum variants 2`() = doFirstCompletion("""
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                Un/*caret*/
            }
        }
    """, """
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                Unit/*caret*/
            }
        }
    """)

    fun `test complete enum variants after Self`() = doSingleCompletion("""
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        impl Expr {
            fn new() -> Self {
                Self::B/*caret*/
            }
        }
    """, """
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        impl Expr {
            fn new() -> Self {
                Self::BinOp(/*caret*/)
            }
        }
    """)

    fun `test should not complete for test functions`() = checkNoCompletion("""
        #[test]
        fn foobar() {}

        fn main() {
            foo/*caret*/
        }
    """)

    fun `test complete macro 1`() = doSingleCompletion("""
        macro_rules! foo_bar { () => () }
        fn main() {
            fo/*caret*/
        }
    """, """
        macro_rules! foo_bar { () => () }
        fn main() {
            foo_bar!(/*caret*/)
        }
    """)

    fun `test complete macro 2`() = doSingleCompletion("""
        macro_rules! foo_bar { () => () }
        fn main() {
            fo/*caret*/!()
        }
    """, """
        macro_rules! foo_bar { () => () }
        fn main() {
            foo_bar!(/*caret*/)
        }
    """)

    fun `test complete macro 3`() = checkContainsCompletion("foobar1", """
        mod inner {
            macro_rules! foobar1 { () => {} }
            macro_rules! foobar2 { () => {} }
            fn test() {
                foo/*caret*/ba!();
            }
        }
    """)

    fun `test complete outer macro`() = doSingleCompletion("""
        macro_rules! foo_bar { () => () }
        fo/*caret*/
        fn main() {
        }
    """, """
        macro_rules! foo_bar { () => () }
        foo_bar!(/*caret*/)
        fn main() {
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete macro with qualified path`() = doSingleCompletionByFileTree("""
    //- lib.rs
        fn bar() {
            dep_lib_target::fo/*caret*/
        }
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """, """
        fn bar() {
            dep_lib_target::foo_bar!(/*caret*/)
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete outer macro with qualified path 1`() = doSingleCompletionByFileTree("""
    //- lib.rs
        dep_lib_target::fo/*caret*/
        fn foo(){}
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """, """
        dep_lib_target::foo_bar!(/*caret*/)
        fn foo(){}
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete outer macro with qualified path 2`() = doSingleCompletionByFileTree("""
    //- lib.rs
        ::dep_lib_target::fo/*caret*/
        fn foo(){}
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """, """
        ::dep_lib_target::foo_bar!(/*caret*/)
        fn foo(){}
    """)

    fun `test complete top-level unqualified macro from other module`() = doSingleCompletion("""
        mod inner {
            pub macro foo() {}
        }
        fo/*caret*/
    """, """
        use crate::inner::foo;

        mod inner {
            pub macro foo() {}
        }
        foo!(/*caret*/)
    """)

    fun `test complete top-level unqualified macro from other module 2`() = doSingleCompletion("""
        mod mod1 {
            mod mod2 {
                #[macro_export]
                macro_rules! foo {
                    () => {};
                }
            }
            fo/*caret*/
        }
    """, """
        mod mod1 {
            use crate::foo;

            mod mod2 {
                #[macro_export]
                macro_rules! foo {
                    () => {};
                }
            }
            foo!(/*caret*/)
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete top-level unqualified macro from other crate`() = doSingleCompletionByFileTree("""
    //- main.rs
        fo/*caret*/
    //- lib.rs
        pub macro foo() {}
    """, """
        use test_package::foo;
        foo!(/*caret*/)
    """)

    fun `test complete top-level 3-segment macro from other module`() = doSingleCompletion("""
        mod mod1 {
            pub mod mod2 {
                pub macro foo() {}
            }
        }
        mod1::mod2::fo/*caret*/
    """, """
        mod mod1 {
            pub mod mod2 {
                pub macro foo() {}
            }
        }
        mod1::mod2::foo!(/*caret*/)
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test no attr proc macro completion inside block`() = checkNoCompletionByFileTree("""
    //- dep-proc-macro/lib.rs
        #[proc_macro_attribute]
        pub fn macro_attr(_attr: TokenStream, item: TokenStream) -> TokenStream { item }
        #[proc_macro_derive(macro_derive)]
        pub fn macro_derive(_item: TokenStream) -> TokenStream { "".parse().unwrap() }
    //- lib.rs
        use dep_proc_macro::*;
        fn main() {
            macro_/*caret*/
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test no attr proc macro completion at top-level`() = checkNoCompletionByFileTree("""
    //- dep-proc-macro/lib.rs
        #[proc_macro_attribute]
        pub fn macro_attr(_attr: TokenStream, item: TokenStream) -> TokenStream { item }
        #[proc_macro_derive(macro_derive)]
        pub fn macro_derive(_item: TokenStream) -> TokenStream { "".parse().unwrap() }
    //- lib.rs
        use dep_proc_macro::*;
        macro_/*caret*/
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete proc macro in use item`() = checkContainsCompletionByFileTree(
        listOf("macro_function", "macro_attr", "MacroDerive", "MacroDeriveHidden"), """
    //- dep-proc-macro/lib.rs
        #[proc_macro]
        pub fn macro_function(input: TokenStream) -> TokenStream { input }
        #[proc_macro_attribute]
        pub fn macro_attr(_attr: TokenStream, item: TokenStream) -> TokenStream { item }
        #[proc_macro_derive(MacroDerive)]
        pub fn macro_derive(_item: TokenStream) -> TokenStream { "".parse().unwrap() }

        #[doc(hidden)]
        #[proc_macro_derive(MacroDeriveHidden)]
        pub fn macro_derive_hidden(_item: TokenStream) -> TokenStream { "".parse().unwrap() }
    //- lib.rs
        use dep_proc_macro::/*caret*/
    """)

    fun `test no items completion at top-level`() = checkNoCompletion("""
        mod inner {
            pub mod foo1 {}
            pub fn foo2() {}
        }
        mod foo3 {}
        fn foo4() {}

        fo/*caret*/
    """)

    fun `test no unqualified macro completion at qualified position`() = checkNoCompletion("""
        macro_rules! foo1 { () => {} }
        mod mod1 {
            pub macro foo2() {}
            pub mod mod2 {}
        }

        mod1::mod2::fo/*caret*/
    """)

    fun `test no macro completion if absolute path (top-level)`() = checkNoCompletion("""
        macro_rules! foo1 { () => {} }
        mod mod1 {
            pub macro foo2() {}
        }

        ::fo/*caret*/
    """)

    fun `test no macro completion if absolute path (inside function)`() = checkNoCompletion("""
        macro_rules! foo1 { () => {} }
        mod mod1 {
            pub macro foo2() {}
        }

        fn main() {
            ::fo/*caret*/
        }
    """)

    @MockEdition(Edition.EDITION_2015)
    fun `test complete macro if absolute path in 2015 edition`() = checkContainsCompletion("foo", """
        #[macro_export]
        macro_rules! foo { () => {} }
        fn main() {
            ::fo/*caret*/
        }
    """)

    fun `test macro don't suggests as function name`() = checkNoCompletion("""
        macro_rules! foo_bar { () => () }
        fn foo/*caret*/() {
        }
    """)

    fun `test macro don't suggests as mod name`() = checkNoCompletion("""
        macro_rules! foo_bar { () => () }
        mod foo/*caret*/ {}
    """)

    fun `test complete macro2 unqualified`() = doSingleCompletion("""
        macro foo() {}
        fn main() {
            fo/*caret*/
        }
    """, """
        macro foo() {}
        fn main() {
            foo!(/*caret*/)
        }
    """)

    fun `test complete macro2 qualified`() = doSingleCompletion("""
        mod inner {
            pub macro foo() {}
        }
        fn main() {
            inner::fo/*caret*/
        }
    """, """
        mod inner {
            pub macro foo() {}
        }
        fn main() {
            inner::foo!(/*caret*/)
        }
    """)

    fun `test complete macro2 in use statement`() = doSingleCompletion("""
        pub mod bar {
            pub macro foo() {}
        }

        use bar::fo/*caret*/
    """, """
        pub mod bar {
            pub macro foo() {}
        }

        use bar::foo;/*caret*/
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete bang proc macro unqualified`() = doSingleCompletionByFileTree("""
    //- dep-proc-macro/lib.rs
        #[proc_macro]
        pub fn function_like_as_is(input: TokenStream) -> TokenStream { input }
    //- lib.rs
        use dep_proc_macro::*;
        function_like/*caret*/
    """, """
        use dep_proc_macro::*;
        function_like_as_is!(/*caret*/)
    """)

    // https://github.com/intellij-rust/intellij-rust/issues/1598
    fun `test no macro completion in item element definition`() {
        for (itemKeyword in listOf("fn", "struct", "enum", "union", "trait", "type", "impl")) {
            checkNoCompletion("""
                macro_rules! foo_bar { () => () }
                $itemKeyword Foo f/*caret*/
            """)
        }
    }

    // https://github.com/intellij-rust/intellij-rust/issues/1598
    fun `test no macro completion after path segment`() = checkNoCompletion("""
        struct Foo;
        macro_rules! foo_bar { () => () }
        fn main() {
            Foo::f/*caret*/
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test no completion for outer macro with qualified path of 3 segments`() = checkNoCompletionByFileTree("""
    //- lib.rs
        quux::dep_lib_target::fo/*caret*/
        fn foo(){}
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """)

    fun `test hidden macro completes in the same module`() = doSingleCompletion("""
        #[doc(hidden)]
        macro_rules! private_macro {}

        fn main() { private_m/*caret*/ }
    """, """
        #[doc(hidden)]
        macro_rules! private_macro {}

        fn main() { private_macro!(/*caret*/) }
    """)

    fun `test hidden macro is hidden in other module`() = checkNoCompletion("""
        #[doc(hidden)]
        macro_rules! private_macro {}

        mod inner {
            fn main() { private_m/*caret*/ }
        }
    """)

    fun `test hidden macro is hidden in other module multiple doc attributes`() = checkNoCompletion("""
        #[doc="No problems with"]
        #[doc(hidden)]
        #[doc="explicit docs"]
        macro_rules! private_macro {}

        mod inner {
            fn main() { private_m/*caret*/ }
        }
    """)

    fun `test explicit associated type binding`() = doSingleCompletion("""
        trait Tr { type Item; }
        type T = Tr<It/*caret*/=u8>;
    """, """
        trait Tr { type Item; }
        type T = Tr<Item/*caret*/=u8>;
    """)

    fun `test possible associated type binding`() = doSingleCompletion("""
        trait Tr { type Item; }
        type T = Tr<It/*caret*/>;
    """, """
        trait Tr { type Item; }
        type T = Tr<Item/*caret*/>;
    """)

    fun `test complete crate with double colon in use path`() = doSingleCompletion("""
        use cra/*caret*/
    """, """
        use crate::/*caret*/
    """)

    fun `test complete crate with double colon in general path`() = doSingleCompletion("""
       fn main() {
            let x = cra/*caret*/
       }
    """, """
       fn main() {
            let x = crate::/*caret*/
       }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test private extern crate`() = checkNoCompletion("""
        mod foo { extern crate std; }
        pub use foo::st/*caret*/
    """)

    @ProjectDescriptor(WithStdlibAndDependencyRustProjectDescriptor::class)
    fun `test no std completion`() = checkNoCompletion("""
        extern crate dep_lib_target;
        pub use dep_lib_target::st/*caret*/
    """)

    fun `test complete with identifier escaping (keyword)`() = doSingleCompletion("""
        fn r#else() {}
        fn main() {
            els/*caret*/
        }
    """, """
        fn r#else() {}
        fn main() {
            r#else()/*caret*/
        }
    """)

    fun `test complete with identifier escaping (reserved keyword)`() = doSingleCompletion("""
        fn r#become() {}
        fn main() {
            bec/*caret*/
        }
    """, """
        fn r#become() {}
        fn main() {
            r#become()/*caret*/
        }
    """)


    fun `test complete lifetime`() = doSingleCompletion("""
        fn foo<'aaaaaa>(x:&'a/*caret*/ str) {}
    """, """
        fn foo<'aaaaaa>(x:&'aaaaaa/*caret*/ str) {}
    """)

    fun `test super completion`() = doSingleCompletion("""
        pub mod foo {
            fn bar() {
                self::su/*caret*/
            }
        }
    """, """
        pub mod foo {
            fn bar() {
                self::super::/*caret*/
            }
        }
    """)

    fun `test not super completion after named path segment`() = checkNoCompletion("""
        pub mod foo {}
        fn main() {
            foo::su/*caret*/
        }
    """)

    fun `test self completion with extern crate self without alias`() = doSingleCompletion("""
        extern crate self;

        mod foo {
            use sel/*caret*/
        }
    """, """
        extern crate self;

        mod foo {
            use self::/*caret*/
        }
    """)

    fun `test tuple struct field completion`() = checkContainsCompletion("1", """
        struct Foo(i32, i32);
        fn main() {
            let foo = Foo(1, 2);
            foo./*caret*/
        }
    """)

    fun `test tuple field completion`() = checkContainsCompletion("1", """
        fn main() {
            let foo = (1, 2);
            foo./*caret*/
        }
    """)

    fun `test completion after tuple field expr`() = doSingleCompletion("""
        struct S { field: i32 }
        fn main() {
            let x = (0, S { field: 0 });
            x.1./*caret*/
        }
    """, """
        struct S { field: i32 }
        fn main() {
            let x = (0, S { field: 0 });
            x.1.field/*caret*/
        }
    """)

    fun `test const generics completion`() = doSingleCompletion("""
        fn f<const AAA: usize>() { A/*caret*/; }
        struct S<const AAA: usize>([usize; A/*caret*/]);
        trait T<const AAA: usize> { const BBB: usize = A/*caret*/; }
        enum E<const AAA: usize> { V([usize; A/*caret*/]) }
    """, """
        fn f<const AAA: usize>() { AAA/*caret*/; }
        struct S<const AAA: usize>([usize; AAA/*caret*/]);
        trait T<const AAA: usize> { const BBB: usize = AAA/*caret*/; }
        enum E<const AAA: usize> { V([usize; AAA/*caret*/]) }
    """)

    fun `test caret navigation in UFCS`() = doSingleCompletion("""
        struct Foo;
        impl Foo {
            fn foo(&self) {}
        }

        fn main() {
            Foo::fo/*caret*/
        }
    """, """
        struct Foo;
        impl Foo {
            fn foo(&self) {}
        }

        fn main() {
            Foo::foo(/*caret*/)
        }
    """)

    fun `test caret navigation for method with &self parameter in dot syntax call`() = doSingleCompletion("""
        struct Foo;
        impl Foo {
            fn foo(&self) {}
        }

        fn main() {
            Foo.fo/*caret*/
        }
    """, """
        struct Foo;
        impl Foo {
            fn foo(&self) {}
        }

        fn main() {
            Foo.foo()/*caret*/
        }
    """)

    fun `test complete type parameters in let binding`() = doSingleCompletion("""
        struct Frobnicate<T>(T);
        fn main() {
            let x: Frob/*caret*/
        }
    """, """
        struct Frobnicate<T>(T);
        fn main() {
            let x: Frobnicate</*caret*/>
        }
    """)

    fun `test complete const parameters in let binding`() = doSingleCompletion("""
        struct Frobnicate<const N: u32>(u32);
        fn main() {
            let x: Frob/*caret*/
        }
    """, """
        struct Frobnicate<const N: u32>(u32);
        fn main() {
            let x: Frobnicate</*caret*/>
        }
    """)

    fun `test complete type parameters in parameter`() = doSingleCompletion("""
        struct Frobnicate<T>(T);
        fn foo(a: Frob/*caret*/) {}
    """, """
        struct Frobnicate<T>(T);
        fn foo(a: Frobnicate</*caret*/>) {}
    """)

    fun `test complete type parameters in generic function call`() = doSingleCompletion("""
        struct Frobnicate<T>(T);
        fn gen<T>(t: T) {}
        fn foo() {
            gen::<Frob/*caret*/
        }
    """, """
        struct Frobnicate<T>(T);
        fn gen<T>(t: T) {}
        fn foo() {
            gen::<Frobnicate</*caret*/>
        }
    """)

    fun `test move cursor if angle brackets already exist`() = doSingleCompletion("""
        struct Frobnicate<T>(T);
        fn main() {
            let x: Frob/*caret*/<>
        }
    """, """
        struct Frobnicate<T>(T);
        fn main() {
            let x: Frobnicate</*caret*/>
        }
    """)

    fun `test don't complete type arguments in expression context 1`() = doSingleCompletion("""
        struct Frobnicate<T>(T);
        fn main() {
            let x = Frob/*caret*/
        }
    """, """
        struct Frobnicate<T>(T);
        fn main() {
            let x = Frobnicate/*caret*/
        }
    """)

    fun `test don't complete type arguments in expression context 2`() = doSingleCompletion("""
        struct Frobnicate<T>(T);
        fn main() {
            if (Frob/*caret*/
        }
    """, """
        struct Frobnicate<T>(T);
        fn main() {
            if (Frobnicate/*caret*/
        }
    """)

    fun `test don't complete type arguments in use item`() = doSingleCompletion("""
        mod a {
            pub struct Frobnicate<T>(T);
        }
        use a::Frob/*caret*/
    """, """
        mod a {
            pub struct Frobnicate<T>(T);
        }
        use a::Frobnicate;/*caret*/
    """)

    fun `test don't complete type arguments if all generic parameters have a default 1`() = doSingleCompletion("""
        struct Frobnicate<T = u32, R = i32, const N: u32 = 0, const M: u32 = 1>(T, R);
        fn main() {
            let x: Frob/*caret*/
        }
    """, """
        struct Frobnicate<T = u32, R = i32, const N: u32 = 0, const M: u32 = 1>(T, R);
        fn main() {
            let x: Frobnicate/*caret*/
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test complete parentheses for Fn trait`() = checkCompletion("Fn", """
        fn foo(f: &Fn/*caret*/) {}
    """, """
        fn foo(f: &Fn(/*caret*/)) {}
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test complete parentheses for FnMut trait`() = doFirstCompletion("""
        fn foo(f: &FnMut/*caret*/) {}
    """, """
        fn foo(f: &FnMut(/*caret*/)) {}
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test complete parentheses for FnOnce trait`() = doFirstCompletion("""
        fn foo(f: &FnOnce/*caret*/) {}
    """, """
        fn foo(f: &FnOnce(/*caret*/)) {}
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test move cursor if parentheses of Fn trait already exist`() = doFirstCompletion("""
        fn foo(f: &FnOnce/*caret*/()) {}
    """, """
        fn foo(f: &FnOnce(/*caret*/)) {}
    """)

    @CheckTestmarkHit(Testmarks.DoNotAddOpenParenCompletionChar::class)
    fun `test do not insert second parenthesis 1`() = checkCompletion("foo", """
        fn foo() {}
        fn foo2() {}
        fn main() {
            foo/*caret*/
        }
    """, """
        fn foo() {}
        fn foo2() {}
        fn main() {
            foo()/*caret*/
        }
    """, completionChar = '(')

    @CheckTestmarkHit(Testmarks.DoNotAddOpenParenCompletionChar::class)
    fun `test do not insert second parenthesis 2`() = checkCompletion("V1", """
        enum E {
            V1(i32),
            V2(i32)
        }
        fn main() {
            E::V/*caret*/
        }
    """, """
        enum E {
            V1(i32),
            V2(i32)
        }
        fn main() {
            E::V1(/*caret*/)
        }
    """, completionChar = '(')

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    @CheckTestmarkHit(Testmarks.DoNotAddOpenParenCompletionChar::class)
    fun `test do not insert second parenthesis 3`() = checkCompletion("FnOnce", """
        struct FnOnceStruct;
        fn foo(f: FnOnce/*caret*/) {}
    """, """
        struct FnOnceStruct;
        fn foo(f: FnOnce(/*caret*/)) {}
    """, completionChar = '(')

    @MockAdditionalCfgOptions("intellij_rust")
    fun `test completion cfg-disabled item 1`() = checkNoCompletionByFileTree("""
    //- main.rs
        #[cfg(not(intellij_rust))]
        mod foo;
        fn main() {
            foo::/*caret*/
        }
    //- foo.rs
        pub fn func() {}
    """)

    @MockAdditionalCfgOptions("intellij_rust")
    fun `test completion cfg-disabled item 2`() = doSingleCompletionByFileTree("""
    //- main.rs
        #[cfg(not(intellij_rust))]
        mod foo;
        #[cfg(not(intellij_rust))]
        fn main() {
            foo::/*caret*/
        }
    //- foo.rs
        pub fn func() {}
    """, """
        #[cfg(not(intellij_rust))]
        mod foo;
        #[cfg(not(intellij_rust))]
        fn main() {
            foo::func()
        }
    """)

    fun `test enum with variants completion when it is expected`() = checkContainsCompletion(
        listOf("MyEnum", "MyEnum::A", "MyEnum::B", "MyEnum::C"),
    """
        enum MyEnum { A, B, C }
        fn foo(e: MyEnum) {}
        fn main() { foo(My/*caret*/); }
    """)

    fun `test enum with variants completion when it is expected 2`() = checkContainsCompletion(
        listOf("MySecondEnum", "MySecondEnum::D", "MySecondEnum::E", "MySecondEnum::F"),
    """
        enum MyEnum { A, B, C }
        enum MySecondEnum { D, E, F }
        fn foo(e: MyEnum, e2: MySecondEnum) {}
        fn main() { foo(MyEnum::A, My/*caret*/); }
    """)

    fun `test enum with variant completion when its type is not expected`() = checkNotContainsCompletion(
        "MySecondEnum::D",
    """
        enum MyEnum { A, B, C }
        enum MySecondEnum { D, E, F }
        fn foo(e: MyEnum, e2: MySecondEnum) {}
        fn main() { foo(My/*caret*/); }
    """)

    fun `test enum with variants completion when it is expected from another module`()
        = checkContainsCompletionByFileTree(
        listOf("MyOtherEnum", "MyOtherEnum::A", "MyOtherEnum::B", "MyOtherEnum::C"),
    """
        //- anothermod.rs
        pub enum MyOtherEnum { A(i32), B, C }
        pub fn foo(e: MyOtherEnum) {}

        //- main.rs
        mod anothermod;
        use anothermod::foo;

        fn main() { foo(MyOther/*caret*/) }
    """)

    fun `test enum with variants completion with escaping enum or variant name if it's rust keyword`() = doFirstCompletion(
        """
        enum r#struct { r#type }
        fn foo(e: r#struct) {}
        fn main() { foo(stru/*caret*/); }
    ""","""
        enum r#struct { r#type }
        fn foo(e: r#struct) {}
        fn main() { foo(r#struct::r#type/*caret*/); }
    """)

    fun `test enum with variants completion with autoimport`() = doFirstCompletion("""
        mod anothermod {
            pub enum MyOtherEnum { Variant(i32) }
            pub fn foo(e: MyOtherEnum) {}
        }
        use crate::anothermod::foo;
        fn main() { foo(MyOther/*caret*/) }
    """, """
        mod anothermod {
            pub enum MyOtherEnum { Variant(i32) }
            pub fn foo(e: MyOtherEnum) {}
        }
        use crate::anothermod::{foo, MyOtherEnum};
        fn main() { foo(MyOtherEnum::Variant(/*caret*/)) }
    """)

    fun `test enum with variants in match arm pattern`() = checkContainsCompletion(listOf("E", "E::A", "E::B"), """
        enum E { A, B }
        fn test(e: E) {
            match e {
                /*caret*/
            }
        }
    """)

    fun `test enum with variants in if let pattern`() = checkContainsCompletion(listOf("E", "E::A", "E::B"), """
        enum E { A, B }
        fn test(e: E) {
            if let /*caret*/
        }
    """)

    fun `test enum with variants in pat tuple struct 1`() = checkContainsCompletion(listOf("E", "E::B"), """
        enum E { A, B(i32), C { f: i32 } }
        fn test(e: E) {
            match e {
                E/*caret*/() => {},
            }
        }
    """)

    fun `test enum with variants in pat tuple struct 2`() = checkNotContainsCompletion(listOf("E::A", "E::C"), """
        enum E { A, B(i32), C { f: i32 } }
        fn test(e: E) {
            match e {
                E/*caret*/() => {},
            }
        }
    """)

    fun `test enum with variants in pat struct 1`() = checkContainsCompletion(listOf("E", "E::C"), """
        enum E { A, B(i32), C { f: i32 } }
        fn test(e: E) {
            match e {
                E/*caret*/ {} => {},
            }
        }
    """)

    fun `test enum with variants in pat struct 2`() = checkNotContainsCompletion(listOf("E::A", "E::B"), """
        enum E { A, B(i32), C { f: i32 } }
        fn test(e: E) {
            match e {
                E/*caret*/ {} => {},
            }
        }
    """)

    fun `test do not complete non-mod items in vis restriction path`() = checkNoCompletion("""
        pub mod bar {
            pub mod foo {
                pub(in crate::It/*caret*/) struct S;
            }
        }
        pub struct Item;
    """)

    fun `test do not complete non-ancestor mods in vis restriction path`() = checkNoCompletion("""
        pub mod bar {
            pub mod foo {
                pub(in crate::fo/*caret*/) struct S;
            }
        }
        pub mod foo {}
    """)

    fun `test complete ancestor module in vis restriction path`() = doFirstCompletion("""
        pub mod bar {
            pub mod foo {
                pub(in crate::b/*caret*/) struct S;
            }
        }
    """, """
        pub mod bar {
            pub mod foo {
                pub(in crate::bar/*caret*/) struct S;
            }
        }
    """)

    fun `test do not complete non-traits in trait impl trait ref`() = checkNotContainsCompletion("Foo", """
        struct S;
        struct Foo;

        impl /*caret*/ for S {}
    """)

    fun `test complete traits in trait impl trait ref`() = checkContainsCompletion("Trait", """
        struct S;

        trait Trait {}

        impl /*caret*/ for S {}
    """)

    fun `test complete modules in trait impl trait ref`() = checkContainsCompletion("foo", """
        struct S;

        mod foo {}

        impl /*caret*/ for S {}
    """)

    fun `test complete non-traits in trait impl type ref`() = checkContainsCompletion("Foo", """
        struct Foo;

        trait Trait {}

        impl Trait for /*caret*/ {}
    """)

    fun `test complete non-traits in impl type ref`() = checkContainsCompletion("Foo", """
        struct Foo;

        impl /*caret*/ {}
    """)

    fun `test complete traits in generic bound`() = checkContainsCompletion("Trait", """
        trait Trait {}

        fn foo<T: /*caret*/>() {}
    """)

    fun `test complete non-traits in generic bound`() = checkNotContainsCompletion("Foo", """
        struct Foo;

        fn foo<T: /*caret*/>() {}
    """)

    fun `test complete the raw identifier in use`() = checkCompletion("break", """
        mod foo {
            pub fn r#break() {}
        }

        fn main() {
            use self::foo::b/*caret*/

            r#break();
        }
    """, """
        mod foo {
            pub fn r#break() {}
        }

        fn main() {
            use self::foo::r#break;/*caret*/

            r#break();
        }
    """)

    fun `test don't suggest labels from blocks`() {
        val code = """
            fn f() {
                'b1: loop {
                    'b2: {
                        'b3: loop {
                            continue 'b/*caret*/;
                        }
                    }
                }
            }
        """
        checkNotContainsCompletion("'b2", code)
        checkContainsCompletion(listOf("'b1", "'b3"), code)
    }

    fun `test fn main 1`() = checkContainsCompletionPrefixes(listOf("fn main() {"), """
        /*caret*/
    """)

    fun `test fn main 2`() = checkContainsCompletionPrefixes(listOf("fn main() {"), """
        f/*caret*/
    """)

    fun `test fn main 3`() = checkContainsCompletionPrefixes(listOf("fn main() {"), """
        fn/*caret*/
    """)

    fun `test fn main 4`() = doFirstCompletion("""
        fn /*caret*/
    """, """
        fn main() {
            /*caret*/
        }
    """)

    fun `test fn main 5`() = doFirstCompletion("""
        fn m/*caret*/
    """, """
        fn main() {
            /*caret*/
        }
    """)

    fun `test no fn main inside impl`() = checkNoCompletion("""
        impl Foo {
            fn ma/*caret*/
        }
    """)

    fun `test no fn main inside library crate`() = checkNoCompletionByFileTree("""
    //- lib.rs
        fn ma/*caret*/
    """)

    fun `test no fn main if already have fn main`() = checkNoCompletion("""
        fn main() {}
        fn ma/*caret*/
    """)

    fun `test no fn main inside other function 1`() = checkNoCompletion("""
        fn /*caret*/func() {}
    """)

    fun `test no fn main inside other function 2`() = checkNoCompletion("""
        fn func()/*caret*/ {}
    """)

    fun `test no fn main inside other function 3`() = checkNoCompletion("""
        fn func() /*caret*/{}
    """)

    fun `test match completion 1`() = doSingleCompletionWithLiveTemplate("""
        enum E { A, B }
        fn main() {
            let x = E::A;
            mat/*caret*/
        }
    """, "x\t", """
        enum E { A, B }
        fn main() {
            let x = E::A;
            match x {
                E::A => {/*caret*/}
                E::B => {}
            }
        }
    """)

    fun `test match completion 2`() = doSingleCompletionWithLiveTemplate("""
        enum E { A, B(i32) }
        fn main() {
            let x = E::A;
            mat/*caret*/
        }
    """, "x\ty\t", """
        enum E { A, B(i32) }
        fn main() {
            let x = E::A;
            match x {
                E::A => {/*caret*/}
                E::B(y) => {}
            }
        }
    """)

    fun `test match completion 3`() = doSingleCompletionWithLiveTemplate("""
        enum E { A, B(i32) }
        fn main() {
            let x = E::A;
            let a = mat/*caret*/
        }
    """, "x\ty\t", """
        enum E { A, B(i32) }
        fn main() {
            let x = E::A;
            let a = match x {
                E::A => {/*caret*/}
                E::B(y) => {}
            };
        }
    """)
}
