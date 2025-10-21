/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import org.rust.ProjectDescriptor
import org.rust.WithStdlibAndDependencyRustProjectDescriptor
import org.rust.WithStdlibRustProjectDescriptor
import org.rust.ide.colors.RsColor

class RsUnsafeExpressionErrorAnnotatorTest : RsAnnotatorTestBase(RsUnsafeExpressionAnnotator::class) {
    override fun setUp() {
        super.setUp()
        annotationFixture.registerSeverities(listOf(RsColor.UNSAFE_CODE.testSeverity))
    }

    fun `test extern static requires unsafe`() = checkErrors("""
        extern {
            static C: i32;
        }

        fn main() {
            let a = <error descr="Use of extern static is unsafe and requires unsafe function or block [E0133]">C</error>;
        }
    """)

    fun `test need unsafe static mutable`() = checkErrors("""
        static mut test : u8 = 0;

        fn main() {
            <error descr="Use of mutable static is unsafe and requires unsafe function or block [E0133]">test</error> += 1;
        }
    """)

    fun `test need unsafe function`() = checkErrors("""
        struct S;

        impl S {
            unsafe fn foo(&self) { return; }
        }

        fn main() {
            let s = S;
            <error descr="Call to unsafe function requires unsafe function or block [E0133]">s.foo()</error>;
        }
    """)

    fun `test need unsafe block`() = checkErrors("""
        struct S;

        impl S {
            unsafe fn foo(&self) { return; }
        }

        fn main() {
            {
                let s = S;
                <error descr="Call to unsafe function requires unsafe function or block [E0133]">s.foo()</error>;
            }
        }
    """)

    fun `test need unsafe 2`() = checkErrors("""
        unsafe fn foo() { return; }

        fn main() {
            <error>foo()</error>;
        }
    """)

    fun `test external ABI is unsafe`() = checkErrors("""
        extern "C" { fn foo(); }

        fn main() {
            <error descr="Call to unsafe function requires unsafe function or block [E0133]">foo()</error>;
        }
    """)

    fun `test is unsafe block`() = checkErrors("""
        unsafe fn foo() {}

        fn main() {
            unsafe {
                {
                    <UNSAFE_CODE descr="Call to unsafe function">foo</UNSAFE_CODE>();
                }
            }
        }
    """)

    fun `test is unsafe function`() = checkErrors("""
        unsafe fn foo() {}

        fn main() {
            unsafe {
                fn bar() {
                    <error>foo()</error>;
                }
            }
        }
    """)

    fun `test pointer dereference`() = checkErrors("""
        fn main() {
            let char_ptr: *const char = 42 as *const _;
            let val = <error descr="Dereference of raw pointer requires unsafe function or block [E0133]">*char_ptr</error>;
        }
    """)

    fun `test pointer dereference in unsafe block`() = checkHighlighting("""
        fn main() {
            let char_ptr: *const char = 42 as *const _;
            let val = unsafe { <info descr="Unsafe dereference of raw pointer">*char_ptr</info> };
        }
    """)

    fun `test pointer dereference in unsafe fn`() = checkHighlighting("""
        fn main() {
        }
        unsafe fn foo() {
            let char_ptr: *const char = 42 as *const _;
            let val = <info descr="Unsafe dereference of raw pointer">*char_ptr</info>;
        }
    """)

    fun `test function defined in wasm_bindgen extern block is not unsafe`() = checkErrors("""
        #[wasm_bindgen]
        extern {
            fn foo();
        }

        fn main() {
            foo();
        }
    """)

    fun `test access union field outside unsafe block`() = checkErrors("""
        union Foo {
            x: u32,
            y: u64
        }

        fn main() {
            let foo = Foo { x: 22 };
            let a = /*error descr="Access to union field is unsafe and requires unsafe function or block [E0133]"*/foo.x/*error**/;
        }
    """
    )

    fun `test access union field inside unsafe block`() = checkErrors("""
        union Foo {
            x: u32,
            y: u64
        }

        fn main() {
            let foo = Foo { x: 22 };
            let a = unsafe { foo.x };
        }
    """
    )

    fun `test no error only when use union field in assignment as left operand`() = checkErrors("""
        union TTT {
            v1: [u8; 2],
            v2: u16,
        }

        fn test() {
            let ttt = TTT { v2: 10 };
            ttt.v1 = [1, 1];
            let foo;
            foo = /*error descr="Access to union field is unsafe and requires unsafe function or block [E0133]"*/ttt.v1/*error**/;
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test need unsafe asm macro call`() = checkErrors("""
       use std::arch::asm; // required since 1.59
       fn main() {
            <error descr="use of `asm!()` is unsafe and requires unsafe function or block [E0133]">asm!("nop")</error>;
       }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test need unsafe asm macro expr`() = checkErrors("""
       use std::arch::asm; // required since 1.59
       fn main() {
            <error descr="use of `asm!()` is unsafe and requires unsafe function or block [E0133]">asm!("nop")</error>
       }
    """)

    @ProjectDescriptor(WithStdlibAndDependencyRustProjectDescriptor::class)
    fun `test a repeatedly defined asm macro`() = checkErrors("""
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! asm {
            () => ()
        }

    //- lib.rs
        #[macro_use]
        extern crate dep_lib_target;

        fn main() {
            asm!();
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test unsafe intrinsics`() = checkErrors("""
        #![feature(core_intrinsics)]

        use std::intrinsics::transmute;

        fn main() {
            let _: bool = <error descr="Call to unsafe function requires unsafe function or block [E0133]">transmute::<u8, bool>(1u8)</error>;
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test safe intrinsics`() = checkErrors("""
        #![feature(core_intrinsics)]

        use std::intrinsics::{add_with_overflow, likely};

        fn main() {
            if likely(true) {}
            let x = add_with_overflow(1, 2);
        }
    """)
}
