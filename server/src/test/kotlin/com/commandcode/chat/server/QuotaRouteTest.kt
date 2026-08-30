package com.commandcode.chat.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class QuotaRouteTest {
    @Test
    fun forwardsTheBearerKeyAndReturnsUncachedQuota() = testApplication {
        var receivedKey: String? = null
        val gateway = QuotaGateway { key ->
            receivedKey = key
            validQuota
        }
        application { commandCodeChatModule(catalogue, gateway) }

        val response = client.get("/v1/goat/quota") {
            header(HttpHeaders.Authorization, "Bearer test-command-code-key")
        }
        val body = response.bodyAsText()

        assertEquals("test-command-code-key", receivedKey)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(validQuota, Json.decodeFromString<QuotaResponse>(body))
        assertSensitiveValuesAbsent(body)
    }

    @Test
    fun rejectsMissingNonBearerBlankOverlongAndMultipleCredentials() {
        val cases = listOf(
            "missing" to emptyList(),
            "non-Bearer" to listOf("Basic dGVzdDp0ZXN0"),
            "blank" to listOf("Bearer "),
            "overlong" to listOf("Bearer ${"x".repeat(8_193)}"),
            "multiple" to listOf("Bearer first", "Bearer second"),
        )

        cases.forEach { (name, authorizationValues) ->
            testApplication {
                var gatewayInvoked = false
                application {
                    commandCodeChatModule(catalogue, QuotaGateway {
                        gatewayInvoked = true
                        validQuota
                    })
                }

                val response = client.get("/v1/goat/quota") {
                    authorizationValues.forEach { headers.append(HttpHeaders.Authorization, it) }
                }
                val body = response.bodyAsText()

                assertEquals(HttpStatusCode.Unauthorized, response.status, name)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl], name)
                assertError(body, "auth_required", "Bearer authorization is required")
                assertFalse(gatewayInvoked, name)
                assertSensitiveValuesAbsent(body)
            }
        }
    }

    @Test
    fun mapsEveryTypedGatewayFailureToStableUncachedJson() {
        val cases = listOf(
            FailureCase(
                QuotaFailure.Category.UNAUTHORIZED,
                HttpStatusCode.Unauthorized,
                "command_code_unauthorized",
                "Command Code rejected the API key",
            ),
            FailureCase(
                QuotaFailure.Category.FORBIDDEN,
                HttpStatusCode.Forbidden,
                "command_code_forbidden",
                "Command Code denied access",
            ),
            FailureCase(
                QuotaFailure.Category.RATE_LIMITED,
                HttpStatusCode.TooManyRequests,
                "command_code_rate_limited",
                "Command Code rate limit exceeded",
            ),
            FailureCase(
                QuotaFailure.Category.TIMEOUT,
                HttpStatusCode.GatewayTimeout,
                "command_code_timeout",
                "Command Code quota request timed out",
            ),
            FailureCase(
                QuotaFailure.Category.REDIRECT,
                HttpStatusCode.BadGateway,
                "command_code_bad_response",
                "Command Code returned an invalid response",
            ),
            FailureCase(
                QuotaFailure.Category.OVERSIZED,
                HttpStatusCode.BadGateway,
                "command_code_bad_response",
                "Command Code returned an invalid response",
            ),
            FailureCase(
                QuotaFailure.Category.MALFORMED,
                HttpStatusCode.BadGateway,
                "command_code_bad_response",
                "Command Code returned an invalid response",
            ),
            FailureCase(
                QuotaFailure.Category.UNAVAILABLE,
                HttpStatusCode.BadGateway,
                "command_code_unavailable",
                "Command Code quota service is unavailable",
            ),
        )

        cases.forEach { case ->
            testApplication {
                application {
                    commandCodeChatModule(catalogue, QuotaGateway {
                        throw QuotaFailure(case.category)
                    })
                }

                val response = client.get("/v1/goat/quota") {
                    header(HttpHeaders.Authorization, "Bearer test-command-code-key")
                }
                val body = response.bodyAsText()

                assertEquals(case.status, response.status, case.category.name)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl], case.category.name)
                assertError(body, case.code, case.message)
                assertSensitiveValuesAbsent(body)
            }
        }
    }

    @Test
    fun mapsUnexpectedExceptionsToStableUncachedInternalError() = testApplication {
        application {
            commandCodeChatModule(catalogue, QuotaGateway {
                error("secret-upstream-detail test-command-code-key")
            })
        }

        val response = client.get("/v1/goat/quota") {
            header(HttpHeaders.Authorization, "Bearer test-command-code-key")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertError(body, "internal_error", "Internal server error")
        assertSensitiveValuesAbsent(body)
    }

    @Test
    fun unknownLengthChunkedGetBodyIsRejectedBeforeCredentialsReachTheGateway() = testApplication {
        var gatewayInvoked = false
        application {
            commandCodeChatModule(catalogue, QuotaGateway {
                gatewayInvoked = true
                validQuota
            })
        }

        val response = client.get("/v1/goat/quota") {
            header(HttpHeaders.Authorization, "Bearer test-command-code-key")
            setBody(object : OutgoingContent.ReadChannelContent() {
                override fun readFrom(): ByteReadChannel =
                    ByteReadChannel(ByteArray(1_024) { 'x'.code.toByte() })
            })
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertError(body, "request_body_not_allowed", "Request bodies are not allowed")
        assertFalse(gatewayInvoked)
        assertSensitiveValuesAbsent(body)
    }

    @Test
    fun fixedLengthGetBodyIsRejectedBeforeCredentialsReachTheGateway() = testApplication {
        var gatewayInvoked = false
        application {
            commandCodeChatModule(catalogue, QuotaGateway {
                gatewayInvoked = true
                validQuota
            })
        }

        val response = client.get("/v1/goat/quota") {
            header(HttpHeaders.Authorization, "Bearer test-command-code-key")
            setBody(ByteArray(1_024) { 'x'.code.toByte() })
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertError(body, "request_body_not_allowed", "Request bodies are not allowed")
        assertFalse(gatewayInvoked)
        assertSensitiveValuesAbsent(body)
    }

    private fun assertError(body: String, code: String, message: String) {
        assertEquals(
            JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message))),
            Json.parseToJsonElement(body),
        )
    }

    private fun assertSensitiveValuesAbsent(body: String) {
        assertFalse(body.contains("test-command-code-key"))
        assertFalse(body.contains("secret-upstream-detail"))
    }

    private data class FailureCase(
        val category: QuotaFailure.Category,
        val status: HttpStatusCode,
        val code: String,
        val message: String,
    )

    private companion object {
        val catalogue = ModelCatalogueResponse(
            schemaVersion = 1,
            catalogueVersion = "test",
            generatedAt = 1,
            models = emptyList(),
        )

        val validQuota = QuotaResponse(
            fetchedAt = 1_777_777_777_000,
            planId = "goat-pro",
            limited = true,
            monthly = RemainingQuota(51.25, 70.0),
            fiveHour = UsedQuota(4.5, 12.0, 1_800_000_001_234),
            weekly = UsedQuota(22.75, 80.0, 1_800_000_005_678),
            purchasedCredits = 9.5,
            freeCredits = 3.25,
        )
    }
}
