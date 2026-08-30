package com.commandcode.chat.data.service

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

class ServiceException(val kind: Kind) : IOException() {
    enum class Kind { UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, TIMEOUT, BAD_RESPONSE, UNAVAILABLE }
}

internal fun interface ClientKeyMaterialFactory {
    fun create(apiKey: CharArray): ClientKeyMaterial
}

internal class ClientKeyMaterial private constructor(
    internal val copiedChars: CharArray,
    internal val copiedBytes: ByteArray,
) {
    internal val bearer: String
        get() = "Bearer ${copiedBytes.toString(Charsets.UTF_8)}"

    internal fun wipe() {
        copiedBytes.fill(0)
        copiedChars.fill('\u0000')
    }

    companion object {
        internal fun from(apiKey: CharArray): ClientKeyMaterial {
            val copiedChars = apiKey.copyOf()
            var copiedBytes = ByteArray(0)
            return try {
                copiedBytes = copiedChars.concatToString().toByteArray(Charsets.UTF_8)
                ClientKeyMaterial(copiedChars, copiedBytes)
            } catch (failure: Throwable) {
                copiedBytes.fill(0)
                copiedChars.fill('\u0000')
                throw failure
            }
        }
    }
}

class CommandCodeQuotaClient(
    private val callFactory: Call.Factory = DEFAULT_CALL_FACTORY,
    private val clock: Clock = Clock.systemUTC(),
) : QuotaApi {
    private val endpoint = "https://api.commandcode.ai/alpha/billing/credits".toHttpUrl()
    private var keyMaterialFactory: ClientKeyMaterialFactory = ClientKeyMaterialFactory(ClientKeyMaterial::from)

    internal constructor(
        callFactory: Call.Factory,
        clock: Clock,
        keyMaterialFactory: ClientKeyMaterialFactory,
    ) : this(callFactory, clock) {
        this.keyMaterialFactory = keyMaterialFactory
    }

    override suspend fun fetchQuota(apiKey: CharArray): QuotaSnapshot {
        val keyMaterial = keyMaterialFactory.create(apiKey)
        try {
            return withContext(Dispatchers.IO) {
                execute(
                    Request.Builder()
                        .url(endpoint)
                        .get()
                        .header("Accept", "application/json")
                        .header("Authorization", keyMaterial.bearer)
                        .build(),
                )
            }
        } finally {
            keyMaterial.wipe()
        }
    }

    private suspend fun execute(request: Request): QuotaSnapshot = withContext(Dispatchers.IO) {
        try {
            callFactory.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw statusFailure(response.code)
                val body: okhttp3.ResponseBody? = response.body
                if (body == null) throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
                val text = readResponse(body)
                try {
                    CommandCodeQuotaResponseCodec.decode(text, clock.instant())
                } catch (_: Exception) {
                    throw ServiceException(ServiceException.Kind.BAD_RESPONSE)
                }
            }
        } catch (failure: ServiceException) {
            throw failure
        } catch (_: SocketTimeoutException) {
            throw ServiceException(ServiceException.Kind.TIMEOUT)
        } catch (_: InterruptedIOException) {
            throw ServiceException(ServiceException.Kind.TIMEOUT)
        } catch (_: IOException) {
            throw ServiceException(ServiceException.Kind.UNAVAILABLE)
        }
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
        val DEFAULT_CALL_FACTORY: Call.Factory = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
