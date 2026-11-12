package com.commandcode.chat.data.service

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

class CommandCodeModelsClient(
    private val callFactory: Call.Factory = DEFAULT_CALL_FACTORY,
    private val clock: Clock = Clock.systemUTC(),
) : ModelCatalogueApi {
    override suspend fun fetchCatalogue(): ModelCatalogueSnapshot {
        val responseText = execute(
            Request.Builder()
                .url(MODELS_ENDPOINT)
                .get()
                .header("Accept", "application/json")
                .build(),
        )
        return try {
            CommandCodeModelsResponseCodec.decode(responseText, clock.instant())
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
        }
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = try {
            callFactory.newCall(request)
        } catch (failure: Throwable) {
            continuation.resumeWith(Result.failure(mapTransportFailure(failure)))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { call.cancel() }

        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(mapTransportFailure(e)))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    val text = response.use { received ->
                        if (!received.isSuccessful) throw statusFailure(received.code)
                        val body = received.body
                        readResponse(body)
                    }
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(text))
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(mapResponseFailure(failure)))
                    }
                }
            }
        }

        try {
            call.enqueue(callback)
        } catch (failure: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(mapTransportFailure(failure)))
            }
        }
    }

    private fun readResponse(body: okhttp3.ResponseBody): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
        }
        val source = body.source()
        val output = Buffer()
        var total = 0L
        val probeLimit = MAX_RESPONSE_BYTES + 1
        while (total < probeLimit) {
            val count = source.read(output, minOf(BUFFER_SIZE.toLong(), probeLimit - total))
            if (count == -1L) break
            if (count == 0L) continue
            total += count
        }
        if (total > MAX_RESPONSE_BYTES) {
            throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
        }
        return output.readUtf8()
    }

    private fun mapResponseFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is ServiceException -> failure
        is SocketTimeoutException -> ServiceException(ServiceException.Kind.TIMEOUT)
        is InterruptedIOException -> ServiceException(ServiceException.Kind.TIMEOUT)
        is IOException -> ServiceException(ServiceException.Kind.UNAVAILABLE)
        else -> ServiceException(ServiceException.Kind.BAD_RESPONSE)
    }

    private fun mapTransportFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is ServiceException -> failure
        is SocketTimeoutException -> ServiceException(ServiceException.Kind.TIMEOUT)
        is InterruptedIOException -> ServiceException(ServiceException.Kind.TIMEOUT)
        is IOException -> ServiceException(ServiceException.Kind.UNAVAILABLE)
        else -> ServiceException(ServiceException.Kind.UNAVAILABLE)
    }

    private fun statusFailure(code: Int): ServiceException = when (code) {
        401 -> ServiceException(ServiceException.Kind.UNAUTHORIZED)
        403 -> ServiceException(ServiceException.Kind.FORBIDDEN)
        429 -> ServiceException(ServiceException.Kind.RATE_LIMITED)
        in 500..599 -> ServiceException(ServiceException.Kind.UNAVAILABLE)
        else -> ServiceException(ServiceException.Kind.BAD_RESPONSE)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val BUFFER_SIZE = 8_192
        val MODELS_ENDPOINT = "https://api.commandcode.ai/provider/v1/models".toHttpUrl()
        val DEFAULT_CALL_FACTORY: Call.Factory = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
