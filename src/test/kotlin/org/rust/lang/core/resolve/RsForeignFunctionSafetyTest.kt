/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve

import org.rust.RsTestBase
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.psi.ext.isActuallyUnsafe

class RsForeignFunctionSafetyTest : RsTestBase() {

    fun `test safe foreign function is safe`() {
        InlineFile("""
            unsafe extern "C" {
                pub safe fn sqrt(x: f64) -> f64;
            }

            fn main() {
                sqrt(4.0);  // Should not require unsafe block
            }
        """)
        val fns = myFixture.file.descendantsOfType<RsFunction>()
        val sqrtFn = fns.single { it.name == "sqrt" }
        assertFalse("Safe foreign function should not be actually unsafe", sqrtFn.isActuallyUnsafe)
    }

    fun `test unsafe foreign function is unsafe`() {
        InlineFile("""
            unsafe extern "C" {
                pub unsafe fn dangerous();
            }

            fn main() {
                dangerous();  // Should require unsafe block
            }
        """)
        val fns = myFixture.file.descendantsOfType<RsFunction>()
        val dangerousFn = fns.single { it.name == "dangerous" }
        assertTrue("Unsafe foreign function should be actually unsafe", dangerousFn.isActuallyUnsafe)
    }

    fun `test default foreign function is unsafe`() {
        InlineFile("""
            unsafe extern "C" {
                pub fn implicit_unsafe();
            }

            fn main() {
                implicit_unsafe();  // Should require unsafe block
            }
        """)
        val fns = myFixture.file.descendantsOfType<RsFunction>()
        val implicitUnsafeFn = fns.single { it.name == "implicit_unsafe" }
        assertTrue("Default foreign function should be actually unsafe", implicitUnsafeFn.isActuallyUnsafe)
    }

    fun `test wasm_bindgen special case still works`() {
        InlineFile("""
            #[wasm_bindgen]
            extern "C" {
                pub fn console_log();
            }

            fn main() {
                console_log();  // Should not require unsafe
            }
        """)
        val fns = myFixture.file.descendantsOfType<RsFunction>()
        val consoleLogFn = fns.single { it.name == "console_log" }
        assertFalse("wasm_bindgen function should not be actually unsafe", consoleLogFn.isActuallyUnsafe)
    }

    fun `test explicit unsafe modifier always makes function unsafe`() {
        InlineFile("""
            unsafe extern "C" {
                pub unsafe fn explicit_unsafe();
            }
        """)
        val fn = myFixture.file.descendantsOfType<RsFunction>().single()
        assertTrue("Explicit unsafe modifier should make function actually unsafe", fn.isActuallyUnsafe)
    }

    fun `test safe marker in legacy extern block`() {
        InlineFile("""
            extern "C" {
                pub safe fn safe_in_legacy();
            }
        """)
        val fn = myFixture.file.descendantsOfType<RsFunction>().single()
        assertFalse("Safe function in legacy extern block should not be actually unsafe", fn.isActuallyUnsafe)
    }
}
