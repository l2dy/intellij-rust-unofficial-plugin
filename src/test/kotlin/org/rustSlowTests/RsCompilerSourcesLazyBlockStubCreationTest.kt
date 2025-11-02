/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustSlowTests

import com.intellij.openapi.vfs.VirtualFile
import org.rust.MinRustcVersion
import org.rust.ProjectDescriptor
import org.rust.WithStdlibRustProjectDescriptor
import org.rust.lang.core.stubs.RsLazyBlockStubCreationTestBase

@ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
class RsCompilerSourcesLazyBlockStubCreationTest : RsLazyBlockStubCreationTestBase() {

    @MinRustcVersion("1.87.0")
    fun `test stdlib source`() {
        val sources = rustSrcDir()
        checkRustFiles(
            sources,
            ignored = setOf(
                "tests", "test", "doc", "etc", "grammar",
                // Test crates from compiler-builtins
                "libm-test", "builtins-test", "builtins-test-intrinsics",
                // Math library crate that extensively uses attr proc macros
                "libm",
            )
        )
    }

    private fun rustSrcDir(): VirtualFile = WithStdlibRustProjectDescriptor.stdlib!!
}
