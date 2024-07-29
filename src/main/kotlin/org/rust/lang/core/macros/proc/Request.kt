/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros.proc

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import it.unimi.dsi.fastutil.ints.IntArrayList
import org.rust.lang.core.macros.tt.FlatTree
import org.rust.lang.core.macros.tt.FlatTreeJsonSerializer
import org.rust.util.RsJacksonSerializer

// This is a sealed class because there is `ListMacro` request kind which we don't use for now
sealed class Request {
    // data class ListMacro(...)
    data class ExpandMacro(
        val macroBody: FlatTree,
        val macroName: String,
        val attributes: FlatTree?,
        val lib: String,
        val env: List<List<String>>,
        val currentDir: String?,
        val hasGlobalSpans: ExpnGlobals,
        val spanDataTable: IntArrayList?,
    ) : Request()

    /**
     * See https://github.com/rust-lang/rust-analyzer/blob/5761b50ed899ca9c9ba9cab672d30b68725b3c18/crates/proc-macro-api/src/msg.rs#L108
     */
    data class ExpnGlobals(
        val defSite: Int,
        val callSite: Int,
        val mixedSite: Int,
    )

    object ApiVersionCheck : Request()
}

class RequestJsonSerializer : RsJacksonSerializer<Request>(Request::class.java) {
    override fun serialize(request: Request, gen: JsonGenerator, provider: SerializerProvider) {
        when (request) {
            is Request.ExpandMacro -> gen.writeJsonObjectWithSingleField("ExpandMacro") {
                writeJsonObject {
                    writeField("macro_body") { FlatTreeJsonSerializer.serialize(request.macroBody, gen, provider) }
                    writeStringField("macro_name", request.macroName)
                    writeNullableField("attributes", request.attributes) { attributes ->
                        FlatTreeJsonSerializer.serialize(attributes, gen, provider)
                    }
                    writeStringField("lib", request.lib)
                    writeArrayField("env", request.env) { list ->
                        writeArray(list) { writeString(it) }
                    }
                    writeStringField("current_dir", request.currentDir)
                    writeField("has_global_spans") {
                        writeJsonObject {
                            writeNumberField("def_site", request.hasGlobalSpans.defSite.toLong() and 0xFFFFFFFFL)
                            writeNumberField("call_site", request.hasGlobalSpans.callSite.toLong() and 0xFFFFFFFFL)
                            writeNumberField("mixed_site", request.hasGlobalSpans.mixedSite.toLong() and 0xFFFFFFFFL)
                        }
                    }
                    if (request.spanDataTable != null) {
                        writeArrayField("span_data_table", request.spanDataTable) { writeNumber(it.toLong() and 0xFFFFFFFFL) }
                    }
                }
            }

            Request.ApiVersionCheck -> gen.writeJsonObjectWithSingleField("ApiVersionCheck") {
                writeJsonObject {}
            }
        }
    }

}
