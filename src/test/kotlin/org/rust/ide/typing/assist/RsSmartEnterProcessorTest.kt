/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.typing.assist

import com.intellij.openapi.actionSystem.IdeActions
import org.intellij.lang.annotations.Language
import org.rust.RsTestBase

/**
 * Unit tests for [RsSmartEnterProcessor]
 */
class RsSmartEnterProcessorTest : RsTestBase() {

    fun `test fix simple method call`() = doTest("""
        fn f() -> i32 {
            /*caret*/f(
        }
    """, """
        fn f() -> i32 {
            f();/*caret*/
        }
    """)

    fun `test fix nested method call`() = doTest("""
        fn double(x: i32) -> i32 {
        /*caret*/double(double(x
        }
    """, """
        fn double(x: i32) -> i32 {
            double(double(x));/*caret*/
        }
    """)

    fun `test fix method call with string literal`() = doTest("""
        fn f(s: String) -> String {
            f(f(f("((")/*caret*/
        }
    """, """
        fn f(s: String) -> String {
            f(f(f("((")));/*caret*/
        }
    """)

    fun `test fix method call multiple lines`() = doTest("""
        fn f(s: String) -> String {
            f("");
            f(
                f("(("/*caret*/
        }
    """, """
        fn f(s: String) -> String {
            f("");
            f(
                f("(("));/*caret*/
        }
    """)

    fun `test fix whitespace and semicolon`() = doTest("""
        fn f(x: i32) -> i32 {
            f(f(x))/*caret*/  ;
        }
    """, """
        fn f(x: i32) -> i32 {
            f(f(x));/*caret*/
        }
    """)

    fun `test fix semicolon after declaration`() = doTest("""
        struct Point {
            x: i32,
            y: i32,
        }

        fn main() {
            let origin = Point { x: 0, y: 0 }/*caret*/
        }
    """, """
        struct Point {
            x: i32,
            y: i32,
        }

        fn main() {
            let origin = Point { x: 0, y: 0 };/*caret*/
        }
    """)

    fun `test fix declaration with call`() = doTest("""
        fn f() -> i32 {
            return 42;
        }

        fn main() {
            let x = f(/*caret*/
        }
    """, """
        fn f() -> i32 {
            return 42;
        }

        fn main() {
            let x = f();/*caret*/
        }
    """)

    fun `test fix match in let`() = doTest("""
        fn main() {
            let version_req = match version {
                Some(v) => try!(VersionReq::parse(v)),
                None => VersionReq::any()
            }/*caret*/
        }
    """, """
        fn main() {
            let version_req = match version {
                Some(v) => try!(VersionReq::parse(v)),
                None => VersionReq::any()
            };/*caret*/
        }
    """)

    fun `test fix call in stmt`() = doTest("""
        fn f(s: String) {
            /*caret*/f(
            let x = 5;
        }
    """, """
        fn f(s: String) {
            f();/*caret*/
            let x = 5;
        }
    """)

    fun `test fix current line call only`() = doTest("""
        fn main() {
            let a = {
                1
            };

            println!()

            println!()/*caret*/

            let b = {
                1
            };
        }
    """, """
        fn main() {
            let a = {
                1
            };

            println!()

            println!();/*caret*/

            let b = {
                1
            };
        }
    """)

    fun `test fix on left brace`() = doTest("""
        fn main() {
            let a = {
                1
            }/*caret*/
        }
    """, """
        fn main() {
            let a = {
                1
            };/*caret*/
        }
    """)

    fun `test fix on right brace`() = doTest("""
        fn main() {
            let a = /*caret*/{
                1
            }
        }
    """, """
        fn main() {
            let a = {
                1
            };/*caret*/
        }
    """)

    fun `test empty expression in match`() = doTest("""
        fn main() {
            let a = true;
            match a {
                true =>/*caret*/
            }
        }
    """, """
        fn main() {
            let a = true;
            match a {
                true =>,/*caret*/
            }
        }
    """)

    fun `test call expression in match`() = doTest("""
        fn test() {}

        fn main() {
            let a = true;
            match a {
                true => test()/*caret*/
            }
        }
    """, """
        fn test() {}

        fn main() {
            let a = true;
            match a {
                true => test(),/*caret*/
            }
        }
    """)

    fun `test dot expression in match`() = doTest("""
        fn main() {
            let a = true;
            match a {
                true => "test".as_bytes()/*caret*/
            }
        }
    """, """
        fn main() {
            let a = true;
            match a {
                true => "test".as_bytes(),/*caret*/
            }
        }
    """)

    fun `test unit expression in match`() = doTest("""
        fn main() {
            let a = true;
            match a {
                true => ()/*caret*/
            }
        }
    """, """
        fn main() {
            let a = true;
            match a {
                true => (),/*caret*/
            }
        }
    """)

    fun `test macro expression in match`() = doTest("""
        fn main() {
            let a = true;
            match a {
                true => println!("test")/*caret*/
            }
        }
    """, """
        fn main() {
            let a = true;
            match a {
                true => println!("test"),/*caret*/
            }
        }
    """)

    fun `test block expression in match`() = doTest("""
        fn main() {
            let a = true;
            match a {
                true => {}/*caret*/
            }
        }
    """, """
        fn main() {
            let a = true;
            match a {
                true => {}
                /*caret*/
            }
        }
    """)

    fun `test fix function`() = doTest("""
        fn foo/*caret*/
    """, """
        fn foo() {
            /*caret*/
        }
    """)

    fun `test fix function with parameters`() = doTest("""
        fn foo(a: i32, b: i32/*caret*/)
    """, """
        fn foo(a: i32, b: i32) {
            /*caret*/
        }
    """)

    fun `test fix function with return type`() = doTest("""
        fn foo() -> i32/*caret*/
    """, """
        fn foo() -> i32 {
            /*caret*/
        }
    """)

    fun `test fix function with parameters and return type`() = doTest("""
        fn foo(a: i32) -> i32/*caret*/
    """, """
        fn foo(a: i32) -> i32 {
            /*caret*/
        }
    """)

    fun `test fix struct`() = doTest("""
        struct s/*caret*/
    """, """
        struct s {
            /*caret*/
        }
    """)

    fun `test fix union`() = doTest("""
        union u/*caret*/
    """, """
        union u {
            /*caret*/
        }
    """)

    fun `test fix nested function definition`() = doTest("""
        fn foo() {
            fn bar(/*caret*/)
        }
    """, """
        fn foo() {
            fn bar() {
                /*caret*/
            }
        }
    """)

    fun `test fix if`() = doTest("""
        fn main() {
            if a/*caret*/
        }
    """, """
        fn main() {
            if a {
                /*caret*/
            }
        }
    """)

    fun `test fix else if`() = doTest("""
        fn main() {
            if a {
                1
            } else if b {
                2
            } else if c/*caret*/
        }
    """, """
        fn main() {
            if a {
                1
            } else if b {
                2
            } else if c {
                /*caret*/
            }
        }
    """)

    fun `test block`() = doTest("""
        fn main() {
            {
                1
            }/*caret*/
        }
    """, """
        fn main() {
            {
                1
            }
            /*caret*/
        }
    """)

    fun `test let loop block`() = doTest("""
        fn main() {
            let i = loop {
                break 1;
            }/*caret*/
        }
    """, """
        fn main() {
            let i = loop {
                break 1;
            };/*caret*/
        }
    """)

    fun `test empty line`() = doTest("""
        fn main() {
            let a = 123;
            /*caret*/
        }
    """, """
        fn main() {
            let a = 123;
            
            /*caret*/
        }
    """)

    fun `test top-level type alias`() = doTest("""
        type Foo = /*caret*/i32
    """, """
        type Foo = i32;/*caret*/
    """)

    fun `test top-level type alias with semicolon`() = doTest("""
        type Foo = /*caret*/i32;
    """, """
        type Foo = i32;
        /*caret*/
    """)

    fun `test top-level trait alias`() = doTest("""
        trait Foo = /*caret*/Bar
    """, """
        trait Foo = Bar;/*caret*/
    """)

    fun `test type alias inside trait (no default value)`() = doTest("""
        trait Trait {
            type /*caret*/Foo
        }
    """, """
        trait Trait {
            type Foo;/*caret*/
        }
    """)

    fun `test type alias inside trait (with default value)`() = doTest("""
        trait Trait {
            type Foo = /*caret*/i32
        }
    """, """
        trait Trait {
            type Foo = i32;/*caret*/
        }
    """)

    fun `test type alias inside trait (with where clause)`() = doTest("""
        trait Iterable {
            type Iter<'a> where /*caret*/Self: 'a
        }
    """, """
        trait Iterable {
            type Iter<'a> where Self: 'a;/*caret*/
        }
    """)

    fun `test top-level constant`() = doTest("""
        const C: i32 = /*caret*/0
    """, """
        const C: i32 = 0;/*caret*/
    """)

    fun `test top-level constant (with block expr) 1`() = doTest("""
        const C: i32 = /*caret*/{ 0 }
    """, """
        const C: i32 = { 0 };/*caret*/
    """)

    fun `test top-level constant (with block expr) 2`() = doTest("""
        const C: i32 = { 0/*caret*/ }
    """, """
        const C: i32 = { 0;/*caret*/ }
    """)

    fun `test top-level extern crate`() = doTest("""
        extern crate /*caret*/std
    """, """
        extern crate std;/*caret*/
    """)

    private fun doTest(@Language("Rust") before: String, @Language("Rust") after: String) =
        checkEditorAction(before, after, IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
}
