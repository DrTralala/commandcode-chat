package com.commandcode.chat.server

import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelsRouteTest {
    @Test
    fun modelCatalogueIsPublicJsonAndCacheable() = testApplication {
        val catalogue = GoatModelRegistry.load(
            requireNotNull(javaClass.classLoader.getResourceAsStream("goat-models.json"))
        )
        var quotaGatewayInvoked = false

        application {
            commandCodeChatModule(
                catalogue = catalogue,
                quotaGateway = QuotaGateway {
                    quotaGatewayInvoked = true
                    error("The model route must not invoke quota")
                },
            )
        }

        val response = client.get("/v1/goat/models")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals(44, Json.decodeFromString<ModelCatalogueResponse>(response.bodyAsText()).models.size)
        assertFalse(quotaGatewayInvoked)
    }

    @Test
    fun fixedLengthGetBodyIsRejectedBeforeRouteWork() = testApplication {
        var quotaGatewayInvoked = false
        application {
            commandCodeChatModule(
                catalogue = GoatModelRegistry.load(
                    requireNotNull(javaClass.classLoader.getResourceAsStream("goat-models.json"))
                ),
                quotaGateway = QuotaGateway {
                    quotaGatewayInvoked = true
                    error("gateway must not run")
                },
            )
        }

        val response = client.get("/v1/goat/models") {
            setBody(ByteArray(1_024) { 'x'.code.toByte() })
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(
            JsonObject(
                mapOf(
                    "code" to JsonPrimitive("request_body_not_allowed"),
                    "message" to JsonPrimitive("Request bodies are not allowed"),
                )
            ),
            Json.parseToJsonElement(response.bodyAsText()),
        )
        assertFalse(quotaGatewayInvoked)
    }
}
