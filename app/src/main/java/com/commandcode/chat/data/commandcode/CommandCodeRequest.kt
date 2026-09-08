package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
import java.io.OutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

data class ApiImage(
    val mimeType: String,
    val byteCount: Long,
    val writeBase64: (OutputStream) -> Unit,
)

data class ApiMessage(
    val role: String,
    val content: String,
    val images: List<ApiImage> = emptyList(),
)

object CommandCodeRequestFactory {
    fun create(model: ChatModel, messages: List<ApiMessage>): RequestBody {
        validateImageMessages(messages)
        if (messages.none { it.images.isNotEmpty() }) return createTextOnly(model, messages)

        return StreamingRequestBody(model, messages)
    }

    private fun createTextOnly(model: ChatModel, messages: List<ApiMessage>): RequestBody {
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

    private fun validateImageMessages(messages: List<ApiMessage>) {
        messages.forEach { message ->
            if (message.images.isEmpty()) return@forEach
            require(message.role == USER_ROLE) { "Only user messages may carry images" }
            message.images.forEach { image ->
                require(image.mimeType in IMAGE_MIME_TYPES) { "Unsupported image MIME type" }
            }
        }
    }

    private class StreamingRequestBody(
        private val model: ChatModel,
        private val messages: List<ApiMessage>,
    ) : RequestBody() {
        override fun contentType() = JSON_MEDIA_TYPE

        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("{\"model\":")
            writeQuoted(sink, model.apiId)
            sink.writeUtf8(",\"messages\":[")
            val output = NonClosingOutputStream(sink.outputStream())
            messages.forEachIndexed { index, message ->
                if (index > 0) sink.writeUtf8(",")
                writeMessage(sink, message, output)
            }
            sink.writeUtf8("],\"stream\":true,\"stream_options\":{\"include_usage\":true}}")
        }

        private fun writeMessage(sink: BufferedSink, message: ApiMessage, output: OutputStream) {
            sink.writeUtf8("{\"role\":")
            writeQuoted(sink, message.role)
            sink.writeUtf8(",\"content\":")
            if (message.images.isEmpty()) {
                writeQuoted(sink, message.content)
            } else {
                sink.writeUtf8("[")
                var partIndex = 0
                if (message.content.isNotEmpty()) {
                    sink.writeUtf8("{\"type\":\"text\",\"text\":")
                    writeQuoted(sink, message.content)
                    sink.writeUtf8("}")
                    partIndex++
                }
                message.images.forEach { image ->
                    if (partIndex > 0) sink.writeUtf8(",")
                    sink.writeUtf8("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:")
                    sink.writeUtf8(image.mimeType)
                    sink.writeUtf8(";base64,")
                    image.writeBase64(output)
                    sink.writeUtf8("\"}}")
                    partIndex++
                }
                sink.writeUtf8("]")
            }
            sink.writeUtf8("}")
        }

        private fun writeQuoted(sink: BufferedSink, value: String) {
            sink.writeUtf8(JSONObject.quote(value))
        }
    }

    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)

        override fun write(bytes: ByteArray) = delegate.write(bytes)

        override fun write(bytes: ByteArray, offset: Int, length: Int) = delegate.write(bytes, offset, length)

        override fun flush() = delegate.flush()

        override fun close() = flush()
    }

    private const val USER_ROLE = "user"
    private val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}
