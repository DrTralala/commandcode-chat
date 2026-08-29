package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCodeClientTest {
    @Test fun `default zdr and production request are sent through shared path`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = "data: [DONE]\n\n"))
        server.start()
        try {
            val events = CommandCodeClient(endpoint = server.url("/chat").toString()).stream(charArrayOf('k'), ChatModel.LUNA, listOf(ApiMessage("user", "x"))).toList()
            val request = server.takeRequest()
            assertEquals("1", request.headers["x-cmd-zdr"])
            assertTrue(request.body?.utf8()?.contains("gpt-5.6-luna") == true)
            assertEquals(listOf(StreamEvent.Done), events)
        } finally { server.close() }
    }

    @Test fun `many events are retained`() = runBlocking {
        val server = MockWebServer()
        val body = (1..100).joinToString("\n") { "data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}" } + "\ndata: [DONE]\n"
        server.enqueue(MockResponse(body = body))
        server.start()
        try {
            val events = withTimeout(2_000) { CommandCodeClient(endpoint = server.url("/").toString()).stream(charArrayOf('k'), ChatModel.SOL, emptyList()).toList() }
            assertEquals(101, events.size)
        } finally { server.close() }
    }

    @Test fun `required statuses map to distinct exceptions`() = runBlocking {
        val cases = listOf(401 to CommandCodeException.Unauthorized::class, 403 to CommandCodeException.Forbidden::class,
            422 to CommandCodeException.ZdrUnavailable::class, 429 to CommandCodeException.RateLimited::class, 500 to CommandCodeException.ServerFailure::class)
        val server = MockWebServer()
        cases.forEach { (code, _) -> server.enqueue(MockResponse(code = code, body = if (code == 422) "cmd_zdr_no_providers" else "safe")) }
        server.start()
        try {
            cases.forEach { (_, type) ->
                val failure = runCatching { withTimeout(2_000) { CommandCodeClient(endpoint = server.url("/").toString()).stream(charArrayOf('k'), ChatModel.SOL, emptyList()).toList() } }.exceptionOrNull()
                assertTrue(type.isInstance(failure))
            }
        } finally { server.close() }
    }

    @Test fun `done completes while response remains open`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("data: [DONE]\n\n").inTunnel().build())
        server.start()
        try {
            val events = withTimeout(2_000) { CommandCodeClient(endpoint = server.url("/").toString()).stream(charArrayOf('k'), ChatModel.SOL, emptyList()).toList() }
            assertEquals(listOf(StreamEvent.Done), events)
        } finally { server.close() }
    }
}
