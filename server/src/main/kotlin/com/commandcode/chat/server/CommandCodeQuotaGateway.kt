package com.commandcode.chat.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class RawUpstreamResponse(val status: Int, val body: ByteArray)

internal fun interface QuotaTransport {
    suspend fun get(apiKey: String): RawUpstreamResponse
}

internal class QuotaFailure(
    val category: Category,
) : RuntimeException("Command Code quota request failed") {
    enum class Category {
        UNAUTHORIZED,
        FORBIDDEN,
        RATE_LIMITED,
        TIMEOUT,
        REDIRECT,
        OVERSIZED,
        MALFORMED,
        UNAVAILABLE,
    }
}

class CommandCodeQuotaGateway internal constructor(
    private val transport: QuotaTransport,
    private val clock: Clock,
) : QuotaGateway {
    override suspend fun fetch(apiKey: String): QuotaResponse {
        val response = try {
            transport.get(apiKey)
        } catch (failure: QuotaFailure) {
            throw failure
        } catch (_: HttpTimeoutException) {
            throw QuotaFailure(QuotaFailure.Category.TIMEOUT)
        } catch (_: IOException) {
            throw QuotaFailure(QuotaFailure.Category.UNAVAILABLE)
        }

        if (response.body.size > MAX_RESPONSE_BYTES) {
            throw QuotaFailure(QuotaFailure.Category.OVERSIZED)
        }
        rejectFailureStatus(response.status)

        val parsed = try {
            parseQuota(response.body)
        } catch (failure: QuotaFailure) {
            throw failure
        } catch (_: Exception) {
            throw QuotaFailure(QuotaFailure.Category.MALFORMED)
        }

        return QuotaResponse(
            fetchedAt = clock.millis(),
            planId = parsed.planId,
            limited = parsed.limited,
            monthly = RemainingQuota(remaining = parsed.monthlyCredits, cap = MONTHLY_CAP),
            fiveHour = parsed.fiveHour,
            weekly = parsed.weekly,
            purchasedCredits = parsed.purchasedCredits,
            freeCredits = parsed.freeCredits,
        )
    }

    private fun rejectFailureStatus(status: Int) {
        val category = when {
            status in 200..299 -> return
            status in 300..399 -> QuotaFailure.Category.REDIRECT
            status == 401 -> QuotaFailure.Category.UNAUTHORIZED
            status == 403 -> QuotaFailure.Category.FORBIDDEN
            status == 429 -> QuotaFailure.Category.RATE_LIMITED
            else -> QuotaFailure.Category.UNAVAILABLE
        }
        throw QuotaFailure(category)
    }

    private fun parseQuota(body: ByteArray): ParsedQuota {
        val root = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).requiredObject()
        val credits = root.required("credits").requiredObject()
        val windows = root.required("windowLimits").requiredObject()
        val fiveHour = windows.required("fiveHour").requiredObject()
        val weekly = windows.required("weekly").requiredObject()

        return ParsedQuota(
            planId = credits.requiredString("planId"),
            monthlyCredits = credits.requiredAmount("monthlyCredits"),
            purchasedCredits = credits.requiredAmount("purchasedCredits"),
            freeCredits = credits.requiredAmount("freeCredits"),
            limited = windows.requiredBoolean("limited"),
            fiveHour = UsedQuota(
                used = fiveHour.requiredAmount("used"),
                cap = fiveHour.requiredCap("cap"),
                resetAt = fiveHour.requiredReset("resetAt"),
            ),
            weekly = UsedQuota(
                used = weekly.requiredAmount("used"),
                cap = weekly.requiredCap("cap"),
                resetAt = weekly.requiredReset("resetAt"),
            ),
        )
    }

    private fun JsonElement.requiredObject(): JsonObject =
        this as? JsonObject ?: malformed()

    private fun JsonObject.required(name: String): JsonElement =
        this[name] ?: malformed()

    private fun JsonObject.requiredString(name: String): String {
        val primitive = required(name) as? JsonPrimitive ?: malformed()
        if (!primitive.isString || primitive.content.isBlank()) malformed()
        return primitive.content
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val primitive = required(name) as? JsonPrimitive ?: malformed()
        if (primitive.isString) malformed()
        return primitive.booleanOrNull ?: malformed()
    }

    private fun JsonObject.requiredAmount(name: String): Double =
        requiredDecimal(name).toValidatedDouble(allowZero = true)

    private fun JsonObject.requiredCap(name: String): Double =
        requiredDecimal(name).toValidatedDouble(allowZero = false)

    private fun JsonObject.requiredReset(name: String): Long {
        val value = try {
            requiredDecimal(name).longValueExact()
        } catch (_: ArithmeticException) {
            malformed()
        }
        if (value <= 0) malformed()
        return value
    }

    private fun JsonObject.requiredDecimal(name: String): BigDecimal {
        val primitive = required(name) as? JsonPrimitive ?: malformed()
        if (primitive.isString) malformed()
        return try {
            BigDecimal(primitive.content)
        } catch (_: NumberFormatException) {
            malformed()
        }
    }

    private fun BigDecimal.toValidatedDouble(allowZero: Boolean): Double {
        if (signum() < 0 || (!allowZero && signum() == 0)) malformed()
        val value = toDouble()
        if (!value.isFinite() || (!allowZero && value <= 0.0)) malformed()
        return value
    }

    private fun malformed(): Nothing = throw QuotaFailure(QuotaFailure.Category.MALFORMED)

    private data class ParsedQuota(
        val planId: String,
        val monthlyCredits: Double,
        val purchasedCredits: Double,
        val freeCredits: Double,
        val limited: Boolean,
        val fiveHour: UsedQuota,
        val weekly: UsedQuota,
    )

    companion object {
        private const val MONTHLY_CAP = 70.0
        internal const val MAX_RESPONSE_BYTES = 65_536

        fun createDefault(clock: Clock = Clock.systemUTC()): QuotaGateway =
            CommandCodeQuotaGateway(JavaHttpQuotaTransport.create(), clock)
    }
}

private class JavaHttpQuotaTransport(
    private val client: HttpClient,
) : QuotaTransport {
    override suspend fun get(apiKey: String): RawUpstreamResponse = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(QUOTA_URI)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .GET()
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val body = response.body().use(::readBounded)
            RawUpstreamResponse(response.statusCode(), body)
        } catch (failure: QuotaFailure) {
            throw failure
        } catch (_: HttpTimeoutException) {
            throw QuotaFailure(QuotaFailure.Category.TIMEOUT)
        } catch (_: IOException) {
            throw QuotaFailure(QuotaFailure.Category.UNAVAILABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw QuotaFailure(QuotaFailure.Category.UNAVAILABLE)
        }
    }

    companion object {
        private val QUOTA_URI = URI.create("https://api.commandcode.ai/alpha/billing/credits")

        fun create(): JavaHttpQuotaTransport = JavaHttpQuotaTransport(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        )

        private fun readBounded(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream(CommandCodeQuotaGateway.MAX_RESPONSE_BYTES + 1)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (total <= CommandCodeQuotaGateway.MAX_RESPONSE_BYTES) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size, CommandCodeQuotaGateway.MAX_RESPONSE_BYTES + 1 - total),
                )
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                total += count
                if (total > CommandCodeQuotaGateway.MAX_RESPONSE_BYTES) {
                    throw QuotaFailure(QuotaFailure.Category.OVERSIZED)
                }
            }
            return output.toByteArray()
        }
    }
}
