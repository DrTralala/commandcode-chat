package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

internal object MessagesRequestFactory {
    const val MAX_OUTPUT_TOKENS = 4096
    const val ANTHROPIC_VERSION = "2026-10-21"

    fun create(model: ChatModel, messages: List<ApiMessage>): RequestBody {
        require(model.apiFamily == ApiFamily.ANTHROPIC_MESSAGES) { "Messages requests require the Messages API family" }
        require(messages.none { it.images.isNotEmpty() }) { "Messages requests do not support images" }

        val systemMessages = mutableListOf<String>()
        val conversation = mutableListOf<ApiMessage>()
        messages.forEach { message ->
            when (message.role) {
                "system" -> systemMessages += message.content
                "user", "assistant" -> conversation += message
                else -> throw IllegalArgumentException("Unsupported Messages role")
            }
        }
        require(conversation.isNotEmpty()) { "Messages requests require a user/assistant conversation" }

        val json = JSONObject().apply {
            put("model", model.apiId)
            if (systemMessages.isNotEmpty()) put("system", systemMessages.joinToString("\n\n"))
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("messages", JSONArray().apply {
                conversation.forEach { message ->
                    put(JSONObject().put("role", message.role).put("content", message.content))
                }
            })
            put("stream", true)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return object : RequestBody() {
            override fun contentType() = JSON_MEDIA_TYPE
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(bytes)
            }
        }
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
