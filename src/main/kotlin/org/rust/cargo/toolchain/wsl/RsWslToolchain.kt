/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.systemIndependentPath
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.stdext.toPath
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile

class RsWslToolchain(
    val wslPath: WslPath
) : RsToolchainBase(wslPath.distribution.getWindowsPathWithFix(wslPath.linuxPath).toPath()) {
    private val distribution: WSLDistribution get() = wslPath.distribution
    private val linuxPath: Path = wslPath.linuxPath.toPath()

    override val fileSeparator: String = "/"

    override val executionTimeoutInMilliseconds: Int = 5000

    override fun patchCommandLine(commandLine: GeneralCommandLine, withSudo: Boolean): GeneralCommandLine {
        commandLine.exePath = toRemotePath(commandLine.exePath)

        val parameters = commandLine.parametersList.list.map { toRemotePath(it) }
        commandLine.parametersList.clearAll()
        commandLine.parametersList.addAll(parameters)

        commandLine.environment.forEach { (k, v) ->
            val paths = v.split(File.pathSeparatorChar)
            commandLine.environment[k] = paths.joinToString(":") { toRemotePath(it) }
        }

        commandLine.workDirectory?.let {
            if (it.path.startsWith(fileSeparator)) {
                commandLine.workDirectory = File(toLocalPath(it.path))
            }
        }

        val remoteWorkDir = commandLine.workDirectory?.absolutePath
            ?.let { toRemotePath(it) }
        val options = WSLCommandLineOptions()
            .setSudo(withSudo)
            .setRemoteWorkingDirectory(remoteWorkDir)
            .addInitCommand("export PATH=\"${linuxPath.systemIndependentPath}:\$PATH\"")
        return distribution.patchCommandLine(commandLine, null, options)
    }

    override fun toLocalPath(remotePath: String): String =
        distribution.getWindowsPathWithFix(remotePath)

    override fun toRemotePath(localPath: String): String =
        distribution.getWslPath(localPath) ?: localPath

    override fun expandUserHome(remotePath: String): String =
        distribution.expandUserHome(remotePath)

    override fun getExecutableName(toolName: String): String = toolName

    override fun pathToExecutable(toolName: String): Path = linuxPath.pathToExecutableOnWsl(toolName)

    override fun hasExecutable(exec: String): Boolean =
        distribution.getWindowsPath(pathToExecutable(exec)).isRegularFile()

    override fun hasCargoExecutable(exec: String): Boolean =
        distribution.getWindowsPath(pathToCargoExecutable(exec)).isRegularFile()

    companion object {

        private fun WSLDistribution.getWindowsPathWithFix(wslPath: String): String {
            val systemIndependentPath = FileUtil.toSystemIndependentName(wslPath)
            @Suppress("UnstableApiUsage", "UsePropertyAccessSyntax")
            val uncRoot = getUNCRootPath().systemIndependentPath
            return when {
                systemIndependentPath.startsWith(uncRoot) || !systemIndependentPath.startsWith("/") -> systemIndependentPath
                systemIndependentPath.startsWith(mntRoot) -> WSLUtil.getWindowsPath(systemIndependentPath, mntRoot)
                else -> getWindowsPath(systemIndependentPath)
            } ?: systemIndependentPath
        }

        private fun WSLDistribution.getWindowsPath(wslPath: Path): Path =
            getWindowsPathWithFix(wslPath.toString()).toPath()
    }
}
