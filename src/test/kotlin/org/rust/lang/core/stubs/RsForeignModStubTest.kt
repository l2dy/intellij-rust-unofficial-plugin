/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.stubs

import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.stubs.StubTreeLoader
import org.intellij.lang.annotations.Language
import org.rust.RsTestBase
import org.rust.fileTreeFromText

class RsForeignModStubTest : RsTestBase() {

    fun `test unsafe extern block stub`() = doStubTreeTest("""
        unsafe extern "C" {
            fn foo();
        }
    """, """
        RsFileStub
          FOREIGN_MOD_ITEM:RsForeignModStub
            FUNCTION:RsFunctionStub
              VALUE_PARAMETER_LIST:RsPlaceholderStub
    """)

    fun `test safe extern block stub (legacy)`() = doStubTreeTest("""
        extern "C" {
            fn bar();
        }
    """, """
        RsFileStub
          FOREIGN_MOD_ITEM:RsForeignModStub
            FUNCTION:RsFunctionStub
              VALUE_PARAMETER_LIST:RsPlaceholderStub
    """)

    fun `test unsafe extern without ABI`() = doStubTreeTest("""
        unsafe extern {
            fn baz();
        }
    """, """
        RsFileStub
          FOREIGN_MOD_ITEM:RsForeignModStub
            FUNCTION:RsFunctionStub
              VALUE_PARAMETER_LIST:RsPlaceholderStub
    """)

    fun `test extern block with multiple items`() = doStubTreeTest("""
        unsafe extern "C" {
            fn foo();
            static BAR: i32;
            fn baz(x: i32);
        }
    """, """
        RsFileStub
          FOREIGN_MOD_ITEM:RsForeignModStub
            FUNCTION:RsFunctionStub
              VALUE_PARAMETER_LIST:RsPlaceholderStub
            CONSTANT:RsConstantStub
              PATH_TYPE:RsPathTypeStub
                PATH:RsPathStub
            FUNCTION:RsFunctionStub
              VALUE_PARAMETER_LIST:RsPlaceholderStub
                VALUE_PARAMETER:RsValueParameterStub
                  PATH_TYPE:RsPathTypeStub
                    PATH:RsPathStub
    """)

    fun `test extern block with inner attributes`() = doStubTreeTest("""
        unsafe extern "C" {
            #![allow(dead_code)]
            fn foo();
        }
    """, """
        RsFileStub
          FOREIGN_MOD_ITEM:RsForeignModStub
            INNER_ATTR:RsInnerAttrStub
              META_ITEM:RsMetaItemStub
                PATH:RsPathStub
                META_ITEM_ARGS:RsMetaItemArgsStub
                  META_ITEM:RsMetaItemStub
                    PATH:RsPathStub
            FUNCTION:RsFunctionStub
              VALUE_PARAMETER_LIST:RsPlaceholderStub
    """)

    private fun doStubTreeTest(@Language("Rust") code: String, expectedStubText: String) {
        val fileName = "main.rs"
        fileTreeFromText("//- $fileName\n$code").create()
        val vFile = myFixture.findFileInTempDir(fileName)
        val stubTree = StubTreeLoader.getInstance().readFromVFile(project, vFile)
            ?: error("Stub tree is null")
        val stubText = DebugUtil.stubTreeToString(stubTree.root)
        assertEquals(expectedStubText.trimIndent() + "\n", stubText)
    }
}
