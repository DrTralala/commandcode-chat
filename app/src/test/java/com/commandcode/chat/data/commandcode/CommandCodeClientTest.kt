package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
        val body = (1..1_000).joinToString("\n") { "data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}" } + "\ndata: [DONE]\n"
        server.enqueue(MockResponse(body = body))
        server.start()
        try {
            val events = withTimeout(5_000) {
                val received = mutableListOf<StreamEvent>()
                CommandCodeClient(endpoint = server.url("/").toString()).stream(charArrayOf('k'), ChatModel.SOL, emptyList()).collect { received += it; delay(1) }
                received
            }
            assertEquals(1_001, events.size)
        } finally { server.close() }
    }

    @Test fun `chunked response fragmentation preserves events`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().chunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n", 1).build())
        server.start()
        try {
            val events = withTimeout(2_000) { CommandCodeClient(endpoint = server.url("/").toString()).stream(charArrayOf('k'), ChatModel.SOL, emptyList()).toList() }
            assertEquals(listOf(StreamEvent.Delta("ok"), StreamEvent.Done), events)
        } finally { server.close() }
    }

    @Test fun `collector cancellation closes active request`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n").bodyDelay(10, java.util.concurrent.TimeUnit.SECONDS).build())
        server.start()
        try {
            val flow = CommandCodeClient(endpoint = server.url("/").toString()).stream(charArrayOf('k'), ChatModel.SOL, emptyList())
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { flow.collect { } }
            withTimeout(2_000) { server.takeRequest() }
            job.cancel(); job.join()
        } finally { server.close() }
    }

    @Test fun `required statuses map to distinct exceptions`() = runBlocking {
        val cases = listOf(401, 403, 422, 429, 500)
        val server = MockWebServer()
        cases.forEach { code -> server.enqueue(MockResponse(code = code, body = if (code == 422) "cmd_zdr_no_providers" else "safe")) }
        server.start()
        try {
            cases.forEach { code ->
                val failure = runCatching { withTimeout(2_000) { CommandCodeClient(endpoint = server.url("/").toString()).stream(charArrayOf('k'), ChatModel.SOL, emptyList()).toList() } }.exceptionOrNull()
                assertTrue(failure is CommandCodeException)
                assertTrue(failure!!.message!!.contains(if (code == 401) "Invalid" else if (code == 403) "forbidden" else if (code == 422) "ZDR" else if (code == 429) "limit" else "failure", ignoreCase = true))
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
