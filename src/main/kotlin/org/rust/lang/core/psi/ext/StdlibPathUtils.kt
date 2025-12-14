/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

private const val RUSTLIB_SRC_SEGMENT = "/rustlib/src/rust/library/"

fun PsiFile?.isRustLibraryFile(): Boolean = this?.virtualFile.isRustLibraryVirtualFile()

fun VirtualFile?.isRustLibraryVirtualFile(): Boolean = this?.path?.contains(RUSTLIB_SRC_SEGMENT) == true
