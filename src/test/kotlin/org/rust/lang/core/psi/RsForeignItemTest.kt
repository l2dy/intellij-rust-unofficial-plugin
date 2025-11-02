/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi

import org.rust.RsTestBase
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.psi.ext.isActuallyUnsafe
import org.rust.lang.core.psi.ext.isSafe
import org.rust.lang.core.psi.ext.isUnsafe

class RsForeignItemTest : RsTestBase() {

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

    fun `test unsafe foreign function`() {
        InlineFile("""
            unsafe extern "C" {
                pub unsafe fn unsafe_fn();
            }
        """)
        val fn = myFixture.file.descendantsOfType<RsFunction>().single()
        assertFalse("Function should not be marked as safe", fn.isSafe)
        assertTrue("Unsafe function should be actually unsafe", fn.isActuallyUnsafe)
    }

    fun `test default unsafe foreign function`() {
        InlineFile("""
            unsafe extern "C" {
                pub fn default_fn();
            }
        """)
        val fn = myFixture.file.descendantsOfType<RsFunction>().single()
        assertFalse("Function should not be marked as safe", fn.isSafe)
        assertTrue("Default foreign function should be actually unsafe", fn.isActuallyUnsafe)
    }

    fun `test safe foreign static`() {
        InlineFile("""
            unsafe extern "C" {
                pub safe static SAFE_STATIC: i32;
            }
        """)
        val constant = myFixture.file.descendantsOfType<RsConstant>().single()
        assertTrue("Static should be marked as safe", constant.isSafe)
        assertFalse("Static should not be marked as unsafe", constant.isUnsafe)
    }

    fun `test unsafe foreign static`() {
        InlineFile("""
            unsafe extern "C" {
                pub unsafe static UNSAFE_STATIC: *const u8;
            }
        """)
        val constant = myFixture.file.descendantsOfType<RsConstant>().single()
        assertFalse("Static should not be marked as safe", constant.isSafe)
        assertTrue("Static should be marked as unsafe", constant.isUnsafe)
    }

    fun `test default unsafe foreign static`() {
        InlineFile("""
            unsafe extern "C" {
                static DEFAULT_STATIC: *mut u8;
            }
        """)
        val constant = myFixture.file.descendantsOfType<RsConstant>().single()
        assertFalse("Static should not be marked as safe", constant.isSafe)
        assertFalse("Static should not be explicitly marked as unsafe", constant.isUnsafe)
    }
}
