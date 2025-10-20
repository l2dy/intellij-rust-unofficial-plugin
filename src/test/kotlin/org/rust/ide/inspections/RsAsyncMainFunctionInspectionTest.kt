/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import org.junit.Test

class RsAsyncMainFunctionInspectionTest: RsInspectionsTestBase(RsAsyncMainFunctionInspection::class) {
    fun `test main function is async`() = checkByText("""
        /*error descr="`main` function is not allowed to be `async` [E0752]"*/async/*error**/ fn main() {
            /*caret*/
        }
    """)

    fun `test do not show if main is not async`() = checkByText("""
        fn main() {
            /*caret*/
        }
    """)

    fun `test do not show if is part of hardcoded macros`() = checkByText("""
        #[tokio::main]
        async fn main() {
            /*caret*/
        }
    """)

    fun `test do not show if function is not entry point`() = checkByText("""
        async fn start() {
            /*caret*/
        }
    """)

    fun `test no error on other functions named main`() = checkByText("""
        mod foo {
            async fn main() {}
        }
        fn main() {
        /*caret*/
        }
    """)

    fun `test no error on nested async functions`() = checkByText("""
        fn foo() {
            async fn main() {}
        }
        fn main() {
        /*caret*/
        }
    """)

    fun `test do not show if no_main`() = checkByText("""
        #![no_main]/*caret*/
    """)

    fun `test custom bin`() = checkByFileTree("""
    //- bin/a.rs
        /*error descr="`main` function is not allowed to be `async` [E0752]"*/async/*error**/ fn main() {
            /*caret*/
        }
    """)

    fun `test example bin`() = checkByFileTree("""
    //- example/a.rs
        /*error descr="`main` function is not allowed to be `async` [E0752]"*/async/*error**/ fn main() {
        /*caret*/
        }
    """)

    fun `test build file`() = checkByFileTree("""
    //- build.rs
        /*error descr="`main` function is not allowed to be `async` [E0752]"*/async/*error**/ fn main() {
        /*caret*/
        }
    """)

    @Test(expected = Throwable::class) // Please remove once highlighting insde macro expansion is enabled back.
    fun `test main expanded from macro`() = checkByText("""
        macro_rules! id {
        ($($ tt:tt)*) => { $($ tt)* };
        }

        id! {
        /*error descr="`main` function is not allowed to be `async` [E0752]"*/async/*error**/ fn main() {}/*error**/
        }/*caret*/
    """)

    fun `test fix E0752 main cannot be async`() = checkFixByText("Remove", """
        /*error descr="`main` function is not allowed to be `async` [E0752]"*/async/*caret*//*error**/ fn main() {}
    """, """
        fn main() {}
    """)

}
