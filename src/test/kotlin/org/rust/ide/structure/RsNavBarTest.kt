/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.structure

import com.intellij.openapi.editor.ex.EditorEx
import org.intellij.lang.annotations.Language
import org.rust.RsTestBase
import org.rust.contextNavBarPathStringsCompat

class RsNavBarTest : RsTestBase() {
    fun `test struct`() = doTest("""
        struct /*caret*/S;
    """, "S")

    fun `test struct field`() = doTest("""
        struct S {
            a/*caret*/: u32
        }
    """, "S", "a")

    fun `test enum`() = doTest("""
        enum E/*caret*/ {}
    """, "E")

    fun `test enum variant`() = doTest("""
        enum E {
            V1/*caret*/
        }
    """, "E", "V1")

    fun `test trait`() = doTest("""
        trait T/*caret*/ {}
    """, "T")

    fun `test trait constant`() = doTest("""
        trait T/*caret*/ {
            const C/*caret*/: u32;
        }
    """, "T", "C")

    fun `test trait function`() = doTest("""
        trait T/*caret*/ {
            fn foo/*caret*/();
        }
    """, "T", "foo()")

    fun `test trait type`() = doTest("""
        trait T {
            type FOO/*caret*/;
        }
    """, "T", "FOO")

    fun `test constant`() = doTest("""
        const C/*caret*/: u32 = 0;
    """, "C")

    fun `test function`() = doTest("""
        fn foo/*caret*/() {}
    """, "foo()")

    fun `test type alias`() = doTest("""
        type T/*caret*/ = u32;
    """, "T")

    fun `test trait alias`() = doTest("""
        trait T/*caret*/ = T2;
    """, "T")

    fun `test block`() = doTest("""
        fn foo() {
            let x = {/*caret*/
                42
            };
        }
    """, "foo()")

    fun `test let declaration`() = doTest("""
        fn foo() {
            let x/*caret*/ = 1;
        }
    """, "foo()")

    fun `test macro`() = doTest("""
        macro_rules! foo/*caret*/ {}
    """, "foo!")

    fun `test macro 2`() = doTest("""
        macro foo/*caret*/ {}
    """, "foo!")

    fun `test nested function`() = doTest("""
        fn foo() {
            fn bar() {
                /*caret*/
            }
        }
    """, "foo()", "bar()")

    fun `test mod`() = doTest("""
        mod foo {
            /*caret*/
        }
    """, "foo")

    fun `test nested mod`() = doTest("""
        mod foo {
            mod bar {
                fn baz() {
                    /*caret*/
                }
            }
        }
    """, "foo", "bar", "baz()")

    fun `test mod decl`() = doTest("""
        mod foo/*caret*/;
    """, "foo")

    fun `test use item`() = doTest("""
        use foo::bar/*caret*/;
    """)

    fun `test use item alias`() = doTest("""
        use foo::bar as /*caret*/baz;
    """)

    fun `test use extern crate`() = doTest("""
        extern crate foo/*caret*/;
    """, "foo")

    fun `test impl`() = doTest("""
        impl/*caret*/ S {}
    """, "impl S")

    fun doTest(@Language("Rust") code: String, vararg items: String) {
        InlineFile(code).withCaret()

        val dataContext = (myFixture.editor as EditorEx).dataContext

        val actualItems = contextNavBarPathStringsCompat(dataContext)
        val expected = listOf("src", "main.rs", *items)
        assertEquals(expected, actualItems)
    }
}
