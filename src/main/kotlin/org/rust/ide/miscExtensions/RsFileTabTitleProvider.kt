/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.miscExtensions

import com.intellij.openapi.fileEditor.impl.UniqueNameEditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.rust.cargo.CargoConstants
import org.rust.lang.RsConstants
import org.rust.lang.core.psi.isRustFile

class RsFileTabTitleProvider : UniqueNameEditorTabTitleProvider() {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        if (!((file.isRustFile && file.name in EXPLICIT_FILES) || file.name == CargoConstants.MANIFEST_FILE)) {
            return null
        }

        return super.getEditorTabTitle(project, file)
    }

    companion object {
        private val EXPLICIT_FILES = setOf(
            RsConstants.MOD_RS_FILE,
            RsConstants.LIB_RS_FILE,
            RsConstants.MAIN_RS_FILE
        )
    }
}
