package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException

sealed class CommandCodeException(message: String? = null) : IOException(message) {
    class Unauthorized : CommandCodeException("Invalid or expired API key")
    class Forbidden : CommandCodeException("API access is forbidden")
    class ZdrUnavailable : CommandCodeException("ZDR is unavailable")
    class RateLimited : CommandCodeException("Request rate or plan limit reached")
    class ServerFailure : CommandCodeException("Command Code service failure")
}

class CommandCodeClient(private val httpClient: OkHttpClient = OkHttpClient()) {
    fun stream(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean): Flow<StreamEvent> = callbackFlow {
        val keyChars = apiKey.copyOf()
        var keyBytes: ByteArray? = null
        val request = try {
            keyBytes = keyChars.concatToString().toByteArray(Charsets.UTF_8)
            Request.Builder().url(ENDPOINT)
                .post(CommandCodeRequestFactory.create(model, messages))
                .header("Authorization", "Bearer ${keyBytes!!.toString(Charsets.UTF_8)}")
                .apply { if (zdr) header("x-cmd-zdr", "1") }
                .build()
        } finally {
            keyChars.fill('\u0000'); keyBytes?.fill(0)
        }
        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) { close(responseException(response.code, response.body?.string())); return }
                    val parser = SseParser()
                    try {
                        response.body!!.source().use { source ->
                            while (!source.exhausted()) parser.acceptLine(source.readUtf8Line() ?: "").forEach { trySend(it).isSuccess }
                            parser.finish().forEach { trySend(it).isSuccess }
                        }
                        close()
                    } catch (e: IOException) { close(e) }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    companion object {
        private const val ENDPOINT = "https://api.commandcode.ai/provider/v1/chat/completions"
        internal fun requestForTesting(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean): Request {
            val key = apiKey.concatToString()
            return Request.Builder().url(ENDPOINT).post(CommandCodeRequestFactory.create(model, messages))
                .header("Authorization", "Bearer $key").apply { if (zdr) header("x-cmd-zdr", "1") }.build()
        }
        private fun responseException(code: Int, body: String?): CommandCodeException = when {
            code == 401 -> CommandCodeException.Unauthorized()
            code == 403 -> CommandCodeException.Forbidden()
            code == 422 && body?.contains("cmd_zdr_no_providers") == true -> CommandCodeException.ZdrUnavailable()
            code == 429 -> CommandCodeException.RateLimited()
            code in 500..599 -> CommandCodeException.ServerFailure()
            else -> CommandCodeException.ServerFailure()
        }
    }
}
