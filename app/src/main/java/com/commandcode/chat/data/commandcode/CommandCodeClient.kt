package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed class CommandCodeException(message: String? = null) : IOException(message) {
    class Unauthorized : CommandCodeException("Invalid or expired API key")
    class Forbidden : CommandCodeException("API access is forbidden")
    class ZdrUnavailable : CommandCodeException("ZDR is unavailable")
    class RateLimited : CommandCodeException("Request rate or plan limit reached")
    class InsufficientCredits : CommandCodeException("Insufficient Command Code credits")
    class ServerFailure : CommandCodeException("Command Code service failure")
}

class CommandCodeClient(
    private val callFactory: Call.Factory = OkHttpClient(),
    private val endpoint: String = ENDPOINT,
    private val messagesEndpoint: String = MESSAGES_ENDPOINT,
) {
    fun stream(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean = true): Flow<StreamEvent> = callbackFlow {
        val keyChars = apiKey.copyOf()
        var keyBytes: ByteArray? = null
        val request = try {
            keyBytes = keyChars.concatToString().toByteArray(Charsets.UTF_8)
            buildRequest(keyBytes.toString(Charsets.UTF_8), model, messages, zdr, endpoint, messagesEndpoint)
        } finally {
            keyChars.fill('\u0000'); keyBytes?.fill(0)
        }
        val call = callFactory.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                val owner = ResponseOwner(response)
                val readerStarted = AtomicBoolean(false)
                val reader = launch(Dispatchers.IO) {
                    readerStarted.set(true)
                    owner.use { ownedResponse ->
                        if (!ownedResponse.isSuccessful) {
                            try {
                                val bodyPrefix = if (ownedResponse.code == 400 || ownedResponse.code == 422) {
                                    readBoundedErrorBody(ownedResponse)
                                } else {
                                    null
                                }
                                close(responseException(ownedResponse.code, bodyPrefix))
                            } catch (e: IOException) {
                                close(
                                    if (ownedResponse.code == 400) CommandCodeException.ServerFailure() else e,
                                )
                            }
                            return@use
                        }
                        val parser: StreamParser = when (model.apiFamily) {
                            ApiFamily.OPENAI_CHAT -> SseParser()
                            ApiFamily.ANTHROPIC_MESSAGES -> MessagesSseParser()
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                ownedResponse.body.source().use { source ->
                                    var done = false
                                    val messagesParser = parser as? MessagesSseParser
                                    while (!done) {
                                        val line = try {
                                            if (messagesParser != null) {
                                                readBoundedMessagesLine(source)
                                            } else {
                                                source.readUtf8Line()
                                            }
                                        } catch (_: MessagesLineTooLongException) {
                                            for (event in checkNotNull(messagesParser).rejectOversizedLine()) {
                                                send(event)
                                            }
                                            done = true
                                            break
                                        }
                                        if (line == null) break
                                        for (event in parser.acceptLine(line)) {
                                            send(event)
                                            if (event === StreamEvent.Done || event is StreamEvent.Error) {
                                                done = true
                                                break
                                            }
                                        }
                                    }
                                    if (!done) for (event in parser.finish()) send(event)
                                }
                            }
                            close()
                        } catch (e: IOException) { close(e) }
                    }
                }
                reader.invokeOnCompletion {
                    if (!readerStarted.get()) owner.close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    companion object {
        private const val ENDPOINT = "https://api.commandcode.ai/provider/v1/chat/completions"
        private const val MESSAGES_ENDPOINT = "https://api.commandcode.ai/provider/v1/messages"
        internal fun requestForTesting(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean = true): Request =
            buildRequest(apiKey.concatToString(), model, messages, zdr)

        private fun buildRequest(
            apiKey: String,
            model: ChatModel,
            messages: List<ApiMessage>,
            zdr: Boolean,
            endpoint: String = ENDPOINT,
            messagesEndpoint: String = MESSAGES_ENDPOINT,
        ): Request {
            val endpointForModel = when (model.apiFamily) {
                ApiFamily.OPENAI_CHAT -> endpoint
                ApiFamily.ANTHROPIC_MESSAGES -> messagesEndpoint
            }
            val body = when (model.apiFamily) {
                ApiFamily.OPENAI_CHAT -> CommandCodeRequestFactory.create(model, messages)
                ApiFamily.ANTHROPIC_MESSAGES -> MessagesRequestFactory.create(model, messages)
            }
            return Request.Builder().url(endpointForModel).post(body)
                .header("Authorization", "Bearer $apiKey")
                .apply {
                    if (zdr) header("x-cmd-zdr", "1")
                    if (model.apiFamily == ApiFamily.ANTHROPIC_MESSAGES) {
                        header("anthropic-version", MessagesRequestFactory.ANTHROPIC_VERSION)
                    }
                }
                .build()
        }
        private fun responseException(code: Int, body: BoundedErrorBody?): CommandCodeException = when {
            code == 401 -> CommandCodeException.Unauthorized()
            code == 403 -> CommandCodeException.Forbidden()
            code == 400 && body?.complete == true && isInsufficientCreditsError(body.text) ->
                CommandCodeException.InsufficientCredits()
            code == 422 && body?.text?.contains("cmd_zdr_no_providers") == true -> CommandCodeException.ZdrUnavailable()
            code == 429 -> CommandCodeException.RateLimited()
            code in 500..599 -> CommandCodeException.ServerFailure()
            else -> CommandCodeException.ServerFailure()
        }

        private fun isInsufficientCreditsError(body: String): Boolean {
            if (!StrictJsonValidator(body).isValid()) return false
            return try {
                val tokener = JSONTokener(body)
                val root = tokener.nextValue() as? JSONObject ?: return false
                if (tokener.nextClean() != '\u0000') return false
                val error = root.optJSONObject("error") ?: return false
                if (error.opt("type") != "invalid_request_error") return false
                val message = error.opt("message") as? String ?: return false
                val normalised = message.lowercase(Locale.ROOT)
                normalised.contains("insufficient credits") && normalised.contains("purchase more credits")
            } catch (_: JSONException) {
                false
            }
        }

        private class StrictJsonValidator(private val input: String) {
            private var offset = 0

            fun isValid(): Boolean {
                skipWhitespace()
                if (!readValue(0)) return false
                skipWhitespace()
                return offset == input.length
            }

            private fun readValue(depth: Int): Boolean {
                if (depth > MAX_JSON_NESTING) return false
                skipWhitespace()
                return when (input.getOrNull(offset)) {
                    '{' -> readObject(depth)
                    '[' -> readArray(depth)
                    '"' -> readString()
                    't' -> readLiteral("true")
                    'f' -> readLiteral("false")
                    'n' -> readLiteral("null")
                    else -> readNumber()
                }
            }

            private fun readObject(depth: Int): Boolean {
                if (!consume('{')) return false
                skipWhitespace()
                if (consume('}')) return true
                while (true) {
                    if (!readString()) return false
                    skipWhitespace()
                    if (!consume(':') || !readValue(depth + 1)) return false
                    skipWhitespace()
                    if (consume('}')) return true
                    if (!consume(',')) return false
                    skipWhitespace()
                }
            }

            private fun readArray(depth: Int): Boolean {
                if (!consume('[')) return false
                skipWhitespace()
                if (consume(']')) return true
                while (true) {
                    if (!readValue(depth + 1)) return false
                    skipWhitespace()
                    if (consume(']')) return true
                    if (!consume(',')) return false
                }
            }

            private fun readString(): Boolean {
                if (!consume('"')) return false
                while (offset < input.length) {
                    val character = input[offset++]
                    when {
                        character == '"' -> return true
                        character == '\\' -> {
                            if (offset >= input.length) return false
                            when (input[offset++]) {
                                '"', '\\', '/' , 'b', 'f', 'n', 'r', 't' -> Unit
                                'u' -> repeat(4) {
                                    if (offset >= input.length || input[offset].digitToIntOrNull(16) == null) return false
                                    offset++
                                }
                                else -> return false
                            }
                        }
                        character.code < 0x20 -> return false
                    }
                }
                return false
            }

            private fun readNumber(): Boolean {
                val start = offset
                consume('-')
                if (consume('0')) {
                    if (input.getOrNull(offset) in '0'..'9') return false
                } else {
                    if (input.getOrNull(offset) !in '1'..'9') return false
                    while (input.getOrNull(offset) in '0'..'9') offset++
                }
                if (consume('.')) {
                    if (input.getOrNull(offset) !in '0'..'9') return false
                    while (input.getOrNull(offset) in '0'..'9') offset++
                }
                if (consume('e') || consume('E')) {
                    if (!consume('+')) consume('-')
                    if (input.getOrNull(offset) !in '0'..'9') return false
                    while (input.getOrNull(offset) in '0'..'9') offset++
                }
                return offset > start
            }

            private fun readLiteral(value: String): Boolean {
                if (!input.startsWith(value, offset)) return false
                offset += value.length
                return true
            }

            private fun consume(character: Char): Boolean {
                if (input.getOrNull(offset) != character) return false
                offset++
                return true
            }

            private fun skipWhitespace() {
                while (true) {
                    when (input.getOrNull(offset)) {
                        ' ', '\t', '\n', '\r' -> offset++
                        else -> return
                    }
                }
            }
        }

        private fun readBoundedErrorBody(response: Response): BoundedErrorBody {
            val source = response.body.source()
            val buffer = Buffer()
            var remaining = MAX_ERROR_BODY_BYTES
            var complete = false
            while (remaining > 0L) {
                val read = source.read(buffer, minOf(remaining, 8_192L))
                if (read == -1L) {
                    complete = true
                    break
                }
                remaining -= read
            }
            return BoundedErrorBody(buffer.readUtf8(), complete)
        }

        private data class BoundedErrorBody(val text: String, val complete: Boolean)

        private fun readBoundedMessagesLine(source: BufferedSource): String? {
            val newline = source.indexOf('\n'.code.toByte(), 0L, MessagesSseParser.MAX_FRAME_BYTES + 1L)
            if (newline >= 0L) {
                val line = source.readUtf8(newline)
                source.skip(1L)
                return line.removeSuffix("\r")
            }
            if (source.request(MessagesSseParser.MAX_FRAME_BYTES + 1L)) {
                throw MessagesLineTooLongException()
            }
            if (source.exhausted()) return null
            return source.readUtf8().removeSuffix("\r")
        }

        private const val MAX_ERROR_BODY_BYTES = 16_384L
        private const val MAX_JSON_NESTING = 64
    }

    private class MessagesLineTooLongException : IOException()

    private class ResponseOwner(private val response: Response) {
        private val closed = AtomicBoolean(false)

        suspend fun <T> use(block: suspend (Response) -> T): T =
            try {
                block(response)
            } finally {
                close()
            }

        fun close() {
            if (closed.compareAndSet(false, true)) response.close()
        }
    }
}
