package com.commandcode.chat.server

import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CommandCodeQuotaGatewayTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_777_777_777_000), ZoneOffset.UTC)

    @Test
    fun mapsEveryRequiredAlphaQuotaField() = runBlocking {
        val gateway = gateway(validBody)

        assertEquals(
            QuotaResponse(
                schemaVersion = 1,
                fetchedAt = 1_777_777_777_000,
                planId = "goat-pro",
                limited = true,
                monthly = RemainingQuota(remaining = 51.25, cap = 70.0),
                fiveHour = UsedQuota(used = 4.5, cap = 12.0, resetAt = 1_800_000_001_234),
                weekly = UsedQuota(used = 22.75, cap = 80.0, resetAt = 1_800_000_005_678),
                purchasedCredits = 9.5,
                freeCredits = 3.25,
            ),
            gateway.fetch("test-key"),
        )
    }

    @Test
    fun rejectsEveryMissingRequiredPath() {
        val paths = listOf(
            "credits.planId",
            "credits.monthlyCredits",
            "credits.purchasedCredits",
            "credits.freeCredits",
            "windowLimits.limited",
            "windowLimits.fiveHour.used",
            "windowLimits.fiveHour.cap",
            "windowLimits.fiveHour.resetAt",
            "windowLimits.weekly.used",
            "windowLimits.weekly.cap",
            "windowLimits.weekly.resetAt",
        )

        paths.forEach { path ->
            val failure = fetchFailure(gateway(removingPath(validBody, path)))
            assertEquals(QuotaFailure.Category.MALFORMED, failure.category, path)
        }
    }

    @Test
    fun rejectsMalformedJsonAndWrongPrimitiveTypes() {
        val malformedBodies = listOf(
            "not-json",
            validBody.replace("\"planId\": \"goat-pro\"", "\"planId\": 42"),
            validBody.replace("\"planId\": \"goat-pro\"", "\"planId\": \" \""),
            validBody.replace("\"limited\": true", "\"limited\": \"true\""),
            validBody.replace("\"monthlyCredits\": 51.25", "\"monthlyCredits\": \"51.25\""),
        )

        malformedBodies.forEach { body ->
            assertEquals(QuotaFailure.Category.MALFORMED, fetchFailure(gateway(body)).category)
        }
    }

    @Test
    fun rejectsNegativeAndNonFiniteAmounts() {
        val malformedBodies = listOf(
            validBody.replace("\"monthlyCredits\": 51.25", "\"monthlyCredits\": -1"),
            validBody.replace("\"purchasedCredits\": 9.5", "\"purchasedCredits\": -1"),
            validBody.replace("\"freeCredits\": 3.25", "\"freeCredits\": -1"),
            validBody.replaceFirst("\"used\": 4.5", "\"used\": -1"),
            validBody.replaceFirst("\"used\": 22.75", "\"used\": -1"),
            validBody.replace("\"monthlyCredits\": 51.25", "\"monthlyCredits\": 1e10000"),
        )

        malformedBodies.forEach { body ->
            assertEquals(QuotaFailure.Category.MALFORMED, fetchFailure(gateway(body)).category)
        }
    }

    @Test
    fun rejectsNonPositiveCapsAndInvalidResetTimestamps() {
        val malformedBodies = listOf(
            validBody.replaceFirst("\"cap\": 12.0", "\"cap\": 0"),
            validBody.replaceFirst("\"cap\": 80.0", "\"cap\": -1"),
            validBody.replace("\"resetAt\": 1800000001234", "\"resetAt\": 0"),
            validBody.replace("\"resetAt\": 1800000005678", "\"resetAt\": -1"),
            validBody.replace("\"resetAt\": 1800000001234", "\"resetAt\": 1.5"),
        )

        malformedBodies.forEach { body ->
            assertEquals(QuotaFailure.Category.MALFORMED, fetchFailure(gateway(body)).category)
        }
    }

    @Test
    fun rejectsPositiveCapThatUnderflowsToZeroAsDouble() {
        val body = validBody.replaceFirst("\"cap\": 12.0", "\"cap\": 1e-10000")

        assertEquals(QuotaFailure.Category.MALFORMED, fetchFailure(gateway(body)).category)
    }

    @Test
    fun mapsUpstreamStatusesWithoutLeakingTheBody() {
        val expectations = listOf(
            401 to QuotaFailure.Category.UNAUTHORIZED,
            403 to QuotaFailure.Category.FORBIDDEN,
            429 to QuotaFailure.Category.RATE_LIMITED,
            302 to QuotaFailure.Category.REDIRECT,
            418 to QuotaFailure.Category.UNAVAILABLE,
            500 to QuotaFailure.Category.UNAVAILABLE,
        )

        expectations.forEach { (status, category) ->
            val failure = fetchFailure(gateway("secret-upstream-detail", status))
            assertEquals(category, failure.category, status.toString())
            assertEquals("Command Code quota request failed", failure.message)
            assertFalse(failure.message.orEmpty().contains("secret-upstream-detail"))
        }
    }

    @Test
    fun mapsTransportTimeoutWithoutLeakingItsMessage() {
        val gateway = CommandCodeQuotaGateway(
            transport = QuotaTransport { throw HttpTimeoutException("secret-upstream-detail") },
            clock = clock,
        )

        val failure = fetchFailure(gateway)

        assertEquals(QuotaFailure.Category.TIMEOUT, failure.category)
        assertEquals("Command Code quota request failed", failure.message)
        assertFalse(failure.message.orEmpty().contains("secret-upstream-detail"))
    }

    @Test
    fun rejectsA65537ByteResponse() {
        val gateway = CommandCodeQuotaGateway(
            transport = QuotaTransport { RawUpstreamResponse(200, ByteArray(65_537)) },
            clock = clock,
        )

        assertEquals(QuotaFailure.Category.OVERSIZED, fetchFailure(gateway).category)
    }

    private fun gateway(body: String, status: Int = 200) = CommandCodeQuotaGateway(
        transport = QuotaTransport { RawUpstreamResponse(status, body.toByteArray()) },
        clock = clock,
    )

    private fun fetchFailure(gateway: QuotaGateway): QuotaFailure =
        assertFailsWith { runBlocking { gateway.fetch("test-key") } }

    private fun removingPath(body: String, path: String): String {
        val names = path.split('.')
        fun remove(element: JsonElement, depth: Int): JsonElement {
            val objectValue = element as JsonObject
            if (depth == names.lastIndex) return JsonObject(objectValue - names[depth])
            return JsonObject(objectValue + (names[depth] to remove(objectValue.getValue(names[depth]), depth + 1)))
        }
        return remove(Json.parseToJsonElement(body), 0).toString()
    }

    private companion object {
        val validBody = """
            {
              "credits": {
                "planId": "goat-pro",
                "monthlyCredits": 51.25,
                "purchasedCredits": 9.5,
                "freeCredits": 3.25
              },
              "windowLimits": {
                "limited": true,
                "fiveHour": {
                  "used": 4.5,
                  "cap": 12.0,
                  "resetAt": 1800000001234
                },
                "weekly": {
                  "used": 22.75,
                  "cap": 80.0,
                  "resetAt": 1800000005678
                }
              }
            }
        """.trimIndent()
    }
}
