package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

data class ApiMessage(val role: String, val content: String)

object CommandCodeRequestFactory {
    fun create(model: ChatModel, messages: List<ApiMessage>): RequestBody {
        val json = JSONObject().apply {
            put("model", model.apiId)
            put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
    }
}
