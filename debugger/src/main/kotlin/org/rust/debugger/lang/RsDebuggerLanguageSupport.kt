/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.lang

import com.intellij.execution.configurations.RunProfile
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.jetbrains.cidr.execution.debugger.*
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver.StandardDebuggerLanguage.RUST
import com.jetbrains.cidr.execution.debugger.evaluation.CidrDebuggerTypesHelperBase
import org.rust.cargo.runconfig.command.CargoCommandConfiguration

class RsDebuggerLanguageSupport : CidrDebuggerLanguageSupport() {
    override fun getSupportedDebuggerLanguages() = setOf(RUST)

    override fun createEditor(profile: RunProfile?): XDebuggerEditorsProvider? {
        if (profile !is CargoCommandConfiguration) return null
        return createEditorProvider()
    }

    override fun createDebuggerTypesHelper(process: CidrDebugProcess): CidrDebuggerTypesHelperBase =
        RsDebuggerTypesHelper(process)

    override fun createEvaluator(frame: CidrStackFrame): CidrEvaluator =
        RsEvaluator(frame)

    override fun createFrameTypeDecorator(frame: CidrStackFrame): CidrFrameTypeDecorator {
        return RsFrameTypeDecorator(frame)
    }
}
