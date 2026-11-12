package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CommandCodeClientTest {
    private val messagesModel = ChatModel("claude-test", "Claude test", ApiFamily.ANTHROPIC_MESSAGES)

    @Test fun `Messages request uses fixed endpoint headers and protocol body`() {
        val request = CommandCodeClient.requestForTesting(
            apiKey = charArrayOf('k'),
            model = messagesModel,
            messages = listOf(ApiMessage("system", "be precise"), ApiMessage("user", "hello")),
            zdr = true,
        )
        val json = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

        assertEquals("https://api.commandcode.ai/provider/v1/messages", request.url.toString())
        assertEquals("Bearer k", request.header("Authorization"))
        assertEquals("1", request.header("x-cmd-zdr"))
        assertEquals("2026-10-21", request.header("anthropic-version"))
        assertEquals("claude-test", json.getString("model"))
        assertEquals(4096, json.getInt("max_tokens"))
        assertEquals("be precise", json.getString("system"))
        assertTrue(json.getBoolean("stream"))
        assertEquals(1, json.getJSONArray("messages").length())
        assertEquals("user", json.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("hello", json.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun `Messages request keeps authentication and version while omitting disabled zdr`() {
        val request = CommandCodeClient.requestForTesting(
            apiKey = charArrayOf('s', 'e', 'c', 'r', 'e', 't'),
            model = messagesModel,
            messages = listOf(ApiMessage("user", "hello")),
            zdr = false,
        )

        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals(null, request.header("x-cmd-zdr"))
        assertEquals("2026-10-21", request.header("anthropic-version"))
    }

    @Test fun `default zdr and production request are sent through shared path`() = runBlocking {
        val factory = RecordingCallFactory()
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                CommandCodeClient(callFactory = factory)
                    .stream(charArrayOf('k'), ChatModel.LUNA, listOf(ApiMessage("user", "x")))
                    .toList()
            }
        }
        val call = factory.awaitCall()
        call.respond(response(call.request(), 200, "data: [DONE]\n\n".toResponseBody()))

        assertEquals("https://api.commandcode.ai/provider/v1/chat/completions", call.request().url.toString())
        assertEquals("1", call.request().header("x-cmd-zdr"))
        assertEquals(null, call.request().header("anthropic-version"))
        val requestBody = Buffer().also { call.request().body?.writeTo(it) }.readUtf8()
        assertTrue(requestBody.contains("gpt-5.6-luna"))
        assertEquals(listOf(StreamEvent.Done), events.await())
    }

    @Test fun `explicit false zdr omits header from production stream request`() = runBlocking {
        val factory = RecordingCallFactory()
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                CommandCodeClient(callFactory = factory)
                    .stream(charArrayOf('k'), ChatModel.SOL, emptyList(), zdr = false)
                    .toList()
            }
        }
        val call = factory.awaitCall()
        call.respond(response(call.request(), 200, "data: [DONE]\n\n".toResponseBody()))

        assertFalse(call.request().headers.names().contains("x-cmd-zdr"))
        assertEquals(listOf(StreamEvent.Done), events.await())
    }

    @Test fun `Messages stream uses its injected endpoint and parser through terminal notice`() = runBlocking {
        val factory = RecordingCallFactory()
        val client = CommandCodeClient(
            callFactory = factory,
            messagesEndpoint = "https://messages.test/v1/messages",
        )
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                client.stream(
                    charArrayOf('k'), messagesModel,
                    listOf(ApiMessage("user", "hello")),
                ).toList()
            }
        }
        val call = factory.awaitCall()
        val body = CloseTrackingResponseBody(messagesFrame("max_tokens"))
        call.respond(response(call.request(), 200, body))

        assertEquals("https://messages.test/v1/messages", call.request().url.toString())
        assertEquals("Bearer k", call.request().header("Authorization"))
        assertEquals("1", call.request().header("x-cmd-zdr"))
        assertEquals("2026-10-21", call.request().header("anthropic-version"))
        assertEquals(
            listOf(
                StreamEvent.Usage(TokenUsage(1, 0, 0)),
                StreamEvent.Delta("answer"),
                StreamEvent.Usage(TokenUsage(1, 0, 1)),
                StreamEvent.Notice(CompletionNotice.OUTPUT_LIMIT),
                StreamEvent.Done,
            ),
            events.await(),
        )
        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test fun `Messages end-turn stop emits Done without a completion notice`() = runBlocking {
        val factory = RecordingCallFactory()
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                CommandCodeClient(
                    callFactory = factory,
                    messagesEndpoint = "https://messages.test/v1/messages",
                ).stream(charArrayOf('k'), messagesModel, listOf(ApiMessage("user", "hello"))).toList()
            }
        }
        val call = factory.awaitCall()
        val body = CloseTrackingResponseBody(messagesFrame("end_turn"))
        call.respond(response(call.request(), 200, body))

        assertEquals(
            listOf(
                StreamEvent.Usage(TokenUsage(1, 0, 0)),
                StreamEvent.Delta("answer"),
                StreamEvent.Usage(TokenUsage(1, 0, 1)),
                StreamEvent.Done,
            ),
            events.await(),
        )
        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test fun `Messages stream error stops reading and closes the response once`() = runBlocking {
        val errorFrame = "data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"private detail\"}}\n\n"
        val body = BlockingAfterContentResponseBody(errorFrame)
        val factory = RecordingCallFactory { body.releaseRead() }
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                withTimeout(2_000) {
                    CommandCodeClient(
                        callFactory = factory,
                        messagesEndpoint = "https://messages.test/v1/messages",
                    ).stream(charArrayOf('k'), messagesModel, listOf(ApiMessage("user", "hello"))).toList()
                }
            }
        }
        val call = factory.awaitCall()
        call.respond(response(call.request(), 200, body))

        assertEquals(listOf(StreamEvent.Error("PROVIDER_ERROR", "Provider returned an error")), events.await().getOrThrow())
        assertFalse(body.readPastContent)
        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test fun `Messages EOF without message stop has no Done and closes the response once`() = runBlocking {
        val factory = RecordingCallFactory()
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                CommandCodeClient(
                    callFactory = factory,
                    messagesEndpoint = "https://messages.test/v1/messages",
                ).stream(charArrayOf('k'), messagesModel, listOf(ApiMessage("user", "hello"))).toList()
            }
        }
        val call = factory.awaitCall()
        val body = CloseTrackingResponseBody(
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"output_tokens\":0}}}\n\n",
        )
        call.respond(response(call.request(), 200, body))

        assertEquals(listOf(StreamEvent.Usage(TokenUsage(1, 0, 0))), events.await())
        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun messagesTransportRejectsOversizedUnterminatedLineBeforeReadingItFully() = runBlocking {
        val body = RepeatedBytesResponseBody(totalBytes = 1_048_576L)
        val factory = RecordingCallFactory()
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                CommandCodeClient(
                    callFactory = factory,
                    messagesEndpoint = "https://messages.test/v1/messages",
                ).stream(charArrayOf('k'), messagesModel, listOf(ApiMessage("user", "hello"))).toList()
            }
        }
        val call = factory.awaitCall()
        call.respond(response(call.request(), 200, body))

        assertEquals(
            listOf(StreamEvent.Error("MALFORMED_FRAME", "Malformed Messages stream")),
            events.await(),
        )
        assertTrue("read ${body.bytesRead} bytes", body.bytesRead <= 262_144L + 16_384L)
        assertEquals(1, body.closeCount)
    }

    @Test fun `Messages HTTP failures map safely and close each response once`() = runBlocking {
        val cases = listOf(
            Triple(401, "safe", CommandCodeException.Unauthorized::class.java),
            Triple(403, "safe", CommandCodeException.Forbidden::class.java),
            Triple(429, "safe", CommandCodeException.RateLimited::class.java),
            Triple(500, "safe", CommandCodeException.ServerFailure::class.java),
            Triple(422, "cmd_zdr_no_providers", CommandCodeException.ZdrUnavailable::class.java),
        )

        cases.forEach { (code, content, expectedType) ->
            val factory = RecordingCallFactory()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    withTimeout(2_000) {
                        CommandCodeClient(
                            callFactory = factory,
                            messagesEndpoint = "https://messages.test/v1/messages",
                        ).stream(charArrayOf('k'), messagesModel, listOf(ApiMessage("user", "hello"))).toList()
                    }
                }
            }
            val call = factory.awaitCall()
            val body = CloseTrackingResponseBody(content)
            call.respond(response(call.request(), code, body))

            assertEquals("HTTP $code", expectedType, result.await().exceptionOrNull()?.javaClass)
            assertEquals("HTTP $code", 1, call.cancelCount)
            assertEquals("HTTP $code", 1, body.closeCount)
        }
    }

    @Test fun `Messages cancellation before response callback closes a late response once`() = runBlocking {
        val factory = RecordingCallFactory()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CommandCodeClient(
                callFactory = factory,
                messagesEndpoint = "https://messages.test/v1/messages",
            ).stream(charArrayOf('k'), messagesModel, listOf(ApiMessage("user", "hello"))).collect { }
        }
        val call = factory.awaitCall()

        withTimeout(2_000) { job.cancelAndJoin() }
        val body = CloseTrackingResponseBody(messagesFrame("end_turn"))
        call.respond(response(call.request(), 200, body))
        assertTrue(body.awaitClosed())

        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test fun `Messages cancellation after response callback closes the active response once`() = runBlocking {
        val body = BlockingTrackingResponseBody()
        val factory = RecordingCallFactory { body.releaseRead() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CommandCodeClient(
                callFactory = factory,
                messagesEndpoint = "https://messages.test/v1/messages",
            ).stream(charArrayOf('k'), messagesModel, listOf(ApiMessage("user", "hello"))).collect { }
        }
        val call = factory.awaitCall()
        val responseDelivery = launch(Dispatchers.Default) {
            call.respond(response(call.request(), 200, body))
        }
        assertTrue(body.awaitReadStarted())

        withTimeout(2_000) { job.cancelAndJoin() }
        withTimeout(2_000) { responseDelivery.join() }

        assertEquals(1, call.cancelCount)
        assertTrue(body.closed)
        assertEquals(1, body.closeCount)
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
            val expected = (1..1_000).map { StreamEvent.Delta(it.toString()) } + StreamEvent.Done
            assertEquals(expected, events)
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

    @Test fun `image request uses the same direct mock server stream path`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = "data: [DONE]\n\n"))
        server.start()
        try {
            val image = ApiImage("image/png", 3) { it.write("AQID".toByteArray()) }
            val events = withTimeout(2_000) {
                CommandCodeClient(endpoint = server.url("/").toString())
                    .stream(charArrayOf('k'), ChatModel.SOL, listOf(ApiMessage("user", "look", listOf(image))))
                    .toList()
            }
            val request = server.takeRequest()
            val json = JSONObject(request.body!!.utf8())
            val content = json.getJSONArray("messages").getJSONObject(0).getJSONArray("content")

            assertEquals(listOf(StreamEvent.Done), events)
            assertEquals("data:image/png;base64,AQID", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertEquals("1", request.headers["x-cmd-zdr"])
        } finally { server.close() }
    }

    @Test fun `collector cancellation cancels the exact transport call`() = runBlocking {
        val factory = RecordingCallFactory()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CommandCodeClient(callFactory = factory)
                .stream(charArrayOf('k'), ChatModel.SOL, emptyList())
                .collect { }
        }
        val call = factory.awaitCall()

        withTimeout(2_000) { job.cancelAndJoin() }

        assertEquals(1, call.cancelCount)
        assertTrue(call.isCanceled())
    }

    @Test fun `response delivered after collector cancellation is closed exactly once`() = runBlocking {
        val body = CloseTrackingResponseBody("data: [DONE]\n\n")
        val factory = RecordingCallFactory()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CommandCodeClient(callFactory = factory)
                .stream(charArrayOf('k'), ChatModel.SOL, emptyList())
                .collect { }
        }
        val call = factory.awaitCall()

        withTimeout(2_000) { job.cancelAndJoin() }
        call.respond(response(call.request(), 200, body))
        assertTrue(body.awaitClosed())

        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test fun `cancellation after response delivery closes delivered response body`() = runBlocking {
        val body = BlockingTrackingResponseBody()
        val factory = RecordingCallFactory { body.releaseRead() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CommandCodeClient(callFactory = factory)
                .stream(charArrayOf('k'), ChatModel.SOL, emptyList())
                .collect { }
        }
        val call = factory.awaitCall()
        val responseDelivery = launch(Dispatchers.Default) {
            call.respond(response(call.request(), 200, body))
        }
        assertTrue(body.awaitReadStarted())

        withTimeout(2_000) { job.cancelAndJoin() }
        withTimeout(2_000) { responseDelivery.join() }

        assertEquals(1, call.cancelCount)
        assertTrue(body.closed)
        assertEquals(1, body.closeCount)
    }

    @Test fun `required statuses map to distinct exceptions`() = runBlocking {
        val cases = listOf(
            Triple(401, "safe", CommandCodeException.Unauthorized::class.java),
            Triple(403, "safe", CommandCodeException.Forbidden::class.java),
            Triple(422, "cmd_zdr_no_providers", CommandCodeException.ZdrUnavailable::class.java),
            Triple(429, "safe", CommandCodeException.RateLimited::class.java),
            Triple(503, "safe", CommandCodeException.ServerFailure::class.java),
        )

        cases.forEach { (code, body, expectedType) ->
            val factory = RecordingCallFactory()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    withTimeout(2_000) {
                        CommandCodeClient(callFactory = factory)
                            .stream(charArrayOf('k'), ChatModel.SOL, emptyList())
                            .toList()
                    }
                }
            }
            val call = factory.awaitCall()
            call.respond(response(call.request(), code, body.toResponseBody()))

            assertEquals(expectedType, result.await().exceptionOrNull()?.javaClass)
        }
    }

    @Test fun `non-zdr status does not read or leak error body content`() = runBlocking {
        val secret = "SENTINEL_SECRET_RESPONSE_BODY"
        val body = ThrowingTrackingResponseBody(secret)
        val factory = RecordingCallFactory()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                withTimeout(2_000) {
                    CommandCodeClient(callFactory = factory)
                        .stream(charArrayOf('k'), ChatModel.SOL, emptyList())
                        .toList()
                }
            }
        }
        val call = factory.awaitCall()
        call.respond(response(call.request(), 401, body))
        val failure = result.await().exceptionOrNull()

        assertEquals(CommandCodeException.Unauthorized::class.java, failure?.javaClass)
        assertFalse(failure.toString().contains(secret))
        assertTrue(body.closed)
        assertEquals(1, body.closeCount)
    }

    @Test fun `zdr error identification reads only bounded body prefix`() = runBlocking {
        val body = LargeTrackingResponseBody(
            "{\"code\":\"cmd_zdr_no_providers\"}" + "x".repeat(1_000_000),
        )
        val factory = RecordingCallFactory()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                withTimeout(2_000) {
                    CommandCodeClient(callFactory = factory)
                        .stream(charArrayOf('k'), ChatModel.SOL, emptyList())
                        .toList()
                }
            }
        }
        val call = factory.awaitCall()

        call.respond(response(call.request(), 422, body))

        assertEquals(CommandCodeException.ZdrUnavailable::class.java, result.await().exceptionOrNull()?.javaClass)
        assertTrue("read ${body.bytesRead} bytes", body.bytesRead <= 16_384L)
        assertEquals(1, body.closeCount)
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

    private class RecordingCallFactory(
        private val onCancel: () -> Unit = {},
    ) : Call.Factory {
        private val created = CountDownLatch(1)
        @Volatile private var recordedCall: RecordingCall? = null

        override fun newCall(request: Request): Call = RecordingCall(request, onCancel).also {
            recordedCall = it
            created.countDown()
        }

        suspend fun awaitCall(): RecordingCall = withContext(Dispatchers.IO) {
            assertTrue("Call was not created", created.await(2, TimeUnit.SECONDS))
            checkNotNull(recordedCall)
        }
    }

    private class RecordingCall(
        private val recordedRequest: Request,
        private val onCancel: () -> Unit,
    ) : Call by OkHttpClient().newCall(recordedRequest) {
        private val enqueued = CountDownLatch(1)
        @Volatile private var callback: Callback? = null
        @Volatile var cancelCount: Int = 0
            private set

        override fun request(): Request = recordedRequest

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued.countDown()
        }

        override fun cancel() {
            cancelCount++
            onCancel()
        }

        override fun isCanceled(): Boolean = cancelCount > 0

        fun respond(response: Response) {
            assertTrue("Call was not enqueued", enqueued.await(2, TimeUnit.SECONDS))
            checkNotNull(callback).onResponse(this, response)
        }
    }

    private class BlockingTrackingResponseBody : ResponseBody() {
        private val readStarted = CountDownLatch(1)
        private val readRelease = CountDownLatch(1)
        @Volatile var closed: Boolean = false
            private set
        @Volatile var closeCount: Int = 0
            private set
        private val responseSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted.countDown()
                try {
                    readRelease.await()
                } catch (e: InterruptedException) {
                    throw IOException("interrupted scripted read", e)
                }
                return -1
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = responseSource
        override fun close() {
            closeCount++
            super.close()
        }

        fun awaitReadStarted(): Boolean = readStarted.await(2, TimeUnit.SECONDS)
        fun releaseRead() = readRelease.countDown()
    }

    private class ThrowingTrackingResponseBody(
        private val secret: String,
    ) : ResponseBody() {
        @Volatile var closed: Boolean = false
            private set
        @Volatile var closeCount: Int = 0
            private set
        private val responseSource = object : Source {
            private var secretEmitted = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!secretEmitted) {
                    secretEmitted = true
                    sink.writeUtf8(secret)
                    return secret.toByteArray().size.toLong()
                }
                throw IOException("scripted response read failure")
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = secret.length.toLong()
        override fun source(): BufferedSource = responseSource
        override fun close() {
            closeCount++
            super.close()
        }
    }

    private class CloseTrackingResponseBody(content: String) : ResponseBody() {
        private val closedLatch = CountDownLatch(1)
        private val buffer = Buffer().writeUtf8(content)
        @Volatile var closeCount = 0
            private set

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = buffer.size
        override fun source(): BufferedSource = buffer
        override fun close() {
            closeCount++
            closedLatch.countDown()
            super.close()
        }

        fun awaitClosed(): Boolean = closedLatch.await(2, TimeUnit.SECONDS)
    }

    private class LargeTrackingResponseBody(content: String) : ResponseBody() {
        private val bytes = content.toByteArray()
        private var offset = 0
        @Volatile var bytesRead = 0L
            private set
        @Volatile var closeCount = 0
            private set
        private val responseSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (offset == bytes.size) return -1
                val count = minOf(byteCount.toInt(), bytes.size - offset)
                sink.write(bytes, offset, count)
                offset += count
                bytesRead += count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = responseSource
        override fun close() {
            closeCount++
            super.close()
        }
    }

    private class RepeatedBytesResponseBody(
        private val totalBytes: Long,
    ) : ResponseBody() {
        @Volatile var bytesRead = 0L
            private set
        @Volatile var closeCount = 0
            private set
        private val responseSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (bytesRead == totalBytes) return -1
                val count = minOf(byteCount, totalBytes - bytesRead)
                sink.write(ByteArray(count.toInt()) { 'x'.code.toByte() })
                bytesRead += count
                return count
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = responseSource
        override fun close() {
            closeCount++
            super.close()
        }
    }

    private fun response(request: Request, code: Int, body: ResponseBody): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("scripted")
            .body(body)
            .build()

    private fun messagesFrame(stopReason: String): String =
        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"output_tokens\":0}}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$stopReason\"},\"usage\":{\"output_tokens\":1}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n"

    private class BlockingAfterContentResponseBody(content: String) : ResponseBody() {
        private val bytes = content.toByteArray()
        private var offset = 0
        private val readRelease = CountDownLatch(1)
        @Volatile var readPastContent = false
            private set
        @Volatile var closeCount = 0
            private set
        private val responseSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (offset < bytes.size) {
                    val count = minOf(byteCount.toInt(), bytes.size - offset)
                    sink.write(bytes, offset, count)
                    offset += count
                    return count.toLong()
                }
                readPastContent = true
                try {
                    readRelease.await()
                } catch (e: InterruptedException) {
                    throw IOException("interrupted scripted read", e)
                }
                return -1
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = responseSource
        override fun close() {
            closeCount++
            readRelease.countDown()
            super.close()
        }

        fun releaseRead() = readRelease.countDown()
    }

}
