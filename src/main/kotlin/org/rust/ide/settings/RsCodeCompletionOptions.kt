/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.settings

import com.intellij.application.options.CodeCompletionOptionsCustomSection
import com.intellij.openapi.options.BeanConfigurable
import org.rust.RsBundle

class RsCodeCompletionConfigurable :
    BeanConfigurable<RsCodeInsightSettings>(RsCodeInsightSettings.getInstance(), RsBundle.message("settings.rust.completion.title")),
    CodeCompletionOptionsCustomSection {

    init {
        val settings = instance
        if (settings != null) {
            checkBox(RsBundle.message("settings.rust.completion.suggest.out.of.scope.items"), settings::suggestOutOfScopeItems)
        }
    }
}
