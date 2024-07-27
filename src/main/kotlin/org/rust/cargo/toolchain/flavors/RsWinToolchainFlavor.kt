/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.flavors

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import kotlin.io.path.isDirectory
import org.rust.stdext.list
import org.rust.stdext.toPath
import java.nio.file.Path
import kotlin.io.path.exists

class RsWinToolchainFlavor : RsToolchainFlavor() {

    override fun getHomePathCandidates(): Sequence<Path> {
        val programFiles = System.getenv("ProgramFiles")?.toPath() ?: return emptySequence()
        if (!programFiles.exists() || !programFiles.isDirectory()) return emptySequence()
        return programFiles.list()
            .filter { it.isDirectory() }
            .filter {
                val name = FileUtil.getNameWithoutExtension(it.fileName.toString())
                name.lowercase().startsWith("rust")
            }
            .map { it.resolve("bin") }
            .filter { it.isDirectory() }
    }

    override fun isApplicable(): Boolean = SystemInfo.isWindows
}
