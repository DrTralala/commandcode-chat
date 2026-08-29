package com.commandcode.chat.data.commandcode

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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed class CommandCodeException(message: String? = null) : IOException(message) {
    class Unauthorized : CommandCodeException("Invalid or expired API key")
    class Forbidden : CommandCodeException("API access is forbidden")
    class ZdrUnavailable : CommandCodeException("ZDR is unavailable")
    class RateLimited : CommandCodeException("Request rate or plan limit reached")
    class ServerFailure : CommandCodeException("Command Code service failure")
}

class CommandCodeClient(
    private val callFactory: Call.Factory = OkHttpClient(),
    private val endpoint: String = ENDPOINT,
) {
    fun stream(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean = true): Flow<StreamEvent> = callbackFlow {
        val keyChars = apiKey.copyOf()
        var keyBytes: ByteArray? = null
        val request = try {
            keyBytes = keyChars.concatToString().toByteArray(Charsets.UTF_8)
            buildRequest(keyBytes.toString(Charsets.UTF_8), model, messages, zdr, endpoint)
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
                                val bodyPrefix = if (ownedResponse.code == 422) {
                                    readBoundedErrorBody(ownedResponse)
                                } else {
                                    null
                                }
                                close(responseException(ownedResponse.code, bodyPrefix))
                            } catch (e: IOException) {
                                close(e)
                            }
                            return@use
                        }
                        val parser = SseParser()
                        try {
                            withContext(Dispatchers.IO) {
                                ownedResponse.body.source().use { source ->
                                    var done = false
                                    while (!done && !source.exhausted()) {
                                        for (event in parser.acceptLine(source.readUtf8Line() ?: "")) {
                                            send(event)
                                            if (event === StreamEvent.Done) { done = true; break }
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
        internal fun requestForTesting(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean = true): Request =
            buildRequest(apiKey.concatToString(), model, messages, zdr)

        private fun buildRequest(apiKey: String, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean, endpoint: String = ENDPOINT): Request =
            Request.Builder().url(endpoint).post(CommandCodeRequestFactory.create(model, messages))
                .header("Authorization", "Bearer $apiKey").apply { if (zdr) header("x-cmd-zdr", "1") }.build()
        private fun responseException(code: Int, body: String?): CommandCodeException = when {
            code == 401 -> CommandCodeException.Unauthorized()
            code == 403 -> CommandCodeException.Forbidden()
            code == 422 && body?.contains("cmd_zdr_no_providers") == true -> CommandCodeException.ZdrUnavailable()
            code == 429 -> CommandCodeException.RateLimited()
            code in 500..599 -> CommandCodeException.ServerFailure()
            else -> CommandCodeException.ServerFailure()
        }

        private fun readBoundedErrorBody(response: Response): String? {
            val source = response.body.source()
            val buffer = Buffer()
            var remaining = MAX_ERROR_BODY_BYTES
            while (remaining > 0L) {
                val read = source.read(buffer, minOf(remaining, 8_192L))
                if (read == -1L) break
                remaining -= read
            }
            return buffer.readUtf8()
        }

        private const val MAX_ERROR_BODY_BYTES = 16_384L
    }

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
