package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
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

class CommandCodeClientTest {
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

    private fun response(request: Request, code: Int, body: ResponseBody): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("scripted")
            .body(body)
            .build()

}
