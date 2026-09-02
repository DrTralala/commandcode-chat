package com.commandcode.chat.data.service

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

class ServiceException(val kind: Kind) : IOException() {
    enum class Kind { UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, TIMEOUT, BAD_RESPONSE, UNAVAILABLE }
}

internal fun interface ClientKeyMaterialFactory {
    fun create(apiKey: CharArray): ClientKeyMaterial
}

internal fun interface BearerFactory {
    fun create(keyBytes: ByteArray): String
}

internal class ClientKeyMaterial private constructor(
    internal val copiedChars: CharArray,
    internal val copiedBytes: ByteArray,
) {
    internal fun wipe() {
        copiedBytes.fill(0)
        copiedChars.fill('\u0000')
    }

    companion object {
        internal fun from(apiKey: CharArray): ClientKeyMaterial {
            val copiedChars = apiKey.copyOf()
            var copiedBytes = ByteArray(0)
            var encoded: ByteBuffer? = null
            return try {
                encoded = Charsets.UTF_8.encode(CharBuffer.wrap(copiedChars))
                copiedBytes = ByteArray(encoded.remaining())
                encoded.get(copiedBytes)
                ClientKeyMaterial(copiedChars, copiedBytes)
            } catch (failure: Throwable) {
                copiedBytes.fill(0)
                copiedChars.fill('\u0000')
                throw failure
            } finally {
                encoded?.takeIf(ByteBuffer::hasArray)?.array()?.fill(0)
            }
        }
    }
}

class CommandCodeQuotaClient(
    private val callFactory: Call.Factory = DEFAULT_CALL_FACTORY,
    private val clock: Clock = Clock.systemUTC(),
) : QuotaApi {
    private val creditsEndpoint = "https://api.commandcode.ai/alpha/billing/credits".toHttpUrl()
    private val subscriptionEndpoint = "https://api.commandcode.ai/alpha/billing/subscriptions".toHttpUrl()
    private var keyMaterialFactory: ClientKeyMaterialFactory = ClientKeyMaterialFactory(ClientKeyMaterial::from)
    private var bearerFactory: BearerFactory = DEFAULT_BEARER_FACTORY

    internal constructor(
        callFactory: Call.Factory,
        clock: Clock,
        keyMaterialFactory: ClientKeyMaterialFactory,
        bearerFactory: BearerFactory = DEFAULT_BEARER_FACTORY,
    ) : this(callFactory, clock) {
        this.keyMaterialFactory = keyMaterialFactory
        this.bearerFactory = bearerFactory
    }

    override suspend fun fetchQuota(apiKey: CharArray): QuotaSnapshot {
        val keyMaterial = keyMaterialFactory.create(apiKey)
        try {
            // OkHttp requires an immutable header String; mutable app-owned copies are wiped below,
            // while this single per-fetch bearer value remains subject to garbage collection.
            val bearer = bearerFactory.create(keyMaterial.copiedBytes)
            val creditsRequest = request(creditsEndpoint, bearer)
            val subscriptionRequest = request(subscriptionEndpoint, bearer)
            return coroutineScope {
                val credits = async(Dispatchers.IO) {
                    execute(creditsRequest)
                }
                val planId = async(Dispatchers.IO) {
                    fetchOptionalPlanId(subscriptionRequest)
                }
                val creditsText = credits.await()
                val subscriptionPlanId = planId.await()
                try {
                    CommandCodeQuotaResponseCodec.decode(
                        text = creditsText,
                        fetchedAt = clock.instant(),
                        subscriptionPlanId = subscriptionPlanId,
                    )
                } catch (_: Exception) {
                    throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
                }
            }
        } finally {
            keyMaterial.wipe()
        }
    }

    private fun request(url: okhttp3.HttpUrl, bearer: String): Request = Request.Builder()
        .url(url)
        .get()
        .header("Accept", "application/json")
        .header("Authorization", bearer)
        .build()

    private suspend fun fetchOptionalPlanId(request: Request): String? = try {
        CommandCodeSubscriptionResponseCodec.decodePlanId(execute(request))
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = callFactory.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val mapped = mapTransportFailure(e)
                continuation.resumeWith(Result.failure(mapped))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw statusFailure(it.code)
                        val body: okhttp3.ResponseBody? = it.body
                        if (body == null) throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
                        readResponse(body)
                    }
                    continuation.resumeWith(Result.success(text))
                } catch (failure: Throwable) {
                    val mapped = mapTransportFailure(failure)
                    continuation.resumeWith(Result.failure(mapped))
                }
            }
        }
        try {
            call.enqueue(callback)
        } catch (failure: Throwable) {
            val mapped = mapTransportFailure(failure)
            continuation.resumeWith(Result.failure(mapped))
        }
    }

    private fun mapTransportFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is ServiceException -> failure
        is SocketTimeoutException -> ServiceException(ServiceException.Kind.TIMEOUT)
        is InterruptedIOException -> ServiceException(ServiceException.Kind.TIMEOUT)
        is IOException -> ServiceException(ServiceException.Kind.UNAVAILABLE)
        else -> failure
    }

    private fun readResponse(body: okhttp3.ResponseBody): String {
        val maxBytes = QuotaSnapshotCodec.MAX_QUOTA_BYTES
        if (body.contentLength() > maxBytes) {
            throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
        }
        val source = body.source()
        val output = Buffer()
        var total = 0L
        val probeLimit = maxBytes.toLong() + 1
        while (total < probeLimit) {
            val count = source.read(output, minOf(8_192L, probeLimit - total))
            if (count == -1L) break
            if (count == 0L) continue
            total += count
        }
        if (total > maxBytes) {
            throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
        }
        return output.readUtf8()
    }

    private fun statusFailure(code: Int): ServiceException = when (code) {
        401 -> ServiceException(ServiceException.Kind.UNAUTHORIZED)
        403 -> ServiceException(ServiceException.Kind.FORBIDDEN)
        429 -> ServiceException(ServiceException.Kind.RATE_LIMITED)
        in 500..599 -> ServiceException(ServiceException.Kind.UNAVAILABLE)
        else -> ServiceException(ServiceException.Kind.BAD_RESPONSE)
    }

    private companion object {
        val DEFAULT_BEARER_FACTORY = BearerFactory { bytes ->
            val bearerBytes = ByteArray(7 + bytes.size)
            bearerBytes[0] = 'B'.code.toByte()
            bearerBytes[1] = 'e'.code.toByte()
            bearerBytes[2] = 'a'.code.toByte()
            bearerBytes[3] = 'r'.code.toByte()
            bearerBytes[4] = 'e'.code.toByte()
            bearerBytes[5] = 'r'.code.toByte()
            bearerBytes[6] = ' '.code.toByte()
            bytes.copyInto(bearerBytes, destinationOffset = 7)
            try {
                bearerBytes.toString(Charsets.UTF_8)
            } finally {
                bearerBytes.fill(0)
            }
        }
        val DEFAULT_CALL_FACTORY: Call.Factory = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
