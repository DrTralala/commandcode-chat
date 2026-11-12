package com.commandcode.chat.data.service

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCodeModelsClientTest {
    @Test
    fun fetchesTheFixedUnauthenticatedModelsEndpointAndClosesSuccessResponse() = runBlocking {
        val body = TrackingResponseBody(
            """{"data":[{"id":"vendor/messages","name":"Messages","context_length":200000,"supported_endpoints":["/messages"]}]}"""
                .toByteArray(Charsets.UTF_8),
        )
        val callFactory = ImmediateCallFactory { request, call, callback ->
            callback.onResponse(call, response(request, body))
        }

        val snapshot = CommandCodeModelsClient(callFactory, FIXED_CLOCK).fetchCatalogue()

        val request = callFactory.requests.single()
        assertEquals("https://api.commandcode.ai/provider/v1/models", request.url.toString())
        assertEquals("GET", request.method)
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.header("Authorization"))
        assertEquals("vendor/messages", snapshot.models.single().apiId)
        assertEquals(Instant.parse("2026-11-04T12:30:00Z").toEpochMilli(), snapshot.generatedAt)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun mapsTimeoutAndOtherTransportFailuresToSanitisedServiceKinds() {
        val timeout = assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeModelsClient(failingCallFactory(SocketTimeoutException("secret timeout")), FIXED_CLOCK)
                    .fetchCatalogue()
            }
        }
        assertEquals(ServiceException.Kind.TIMEOUT, timeout.kind)
        assertFalse(timeout.toString().contains("secret timeout"))

        val unavailable = assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeModelsClient(failingCallFactory(IOException("secret network")), FIXED_CLOCK)
                    .fetchCatalogue()
            }
        }
        assertEquals(ServiceException.Kind.UNAVAILABLE, unavailable.kind)
        assertFalse(unavailable.toString().contains("secret network"))
    }

    @Test
    fun mapsHttpStatusFailuresWithoutLeakingBodiesAndClosesEveryResponse() {
        val expectedKinds = mapOf(
            401 to ServiceException.Kind.UNAUTHORIZED,
            403 to ServiceException.Kind.FORBIDDEN,
            429 to ServiceException.Kind.RATE_LIMITED,
            500 to ServiceException.Kind.UNAVAILABLE,
            302 to ServiceException.Kind.BAD_RESPONSE,
        )

        expectedKinds.forEach { (status, expectedKind) ->
            val secretBody = "private status body".toByteArray(Charsets.UTF_8)
            val body = TrackingResponseBody(secretBody)
            val callFactory = ImmediateCallFactory { request, call, callback ->
                callback.onResponse(call, response(request, body, status))
            }
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking { CommandCodeModelsClient(callFactory, FIXED_CLOCK).fetchCatalogue() }
            }

            assertEquals(expectedKind, failure.kind)
            assertFalse(failure.toString().contains(String(secretBody, Charsets.UTF_8)))
            assertEquals(1, body.closeCount)
        }
    }

    @Test
    fun rejectsOversizedBodyAfterAtMostOneProbeByteAndClosesResponse() {
        val body = TrackingResponseBody(ByteArray(1_048_577) { ' '.code.toByte() }, contentLength = -1)
        val callFactory = ImmediateCallFactory { request, call, callback ->
            callback.onResponse(call, response(request, body))
        }

        val failure = assertThrows(ServiceException::class.java) {
            runBlocking { CommandCodeModelsClient(callFactory, FIXED_CLOCK).fetchCatalogue() }
        }

        assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
        assertEquals(1_048_577L, body.bytesRead)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun invalidModelPayloadIsAClosedSanitisedBadResponse() {
        val secret = "private upstream detail"
        val body = TrackingResponseBody(secret.toByteArray(Charsets.UTF_8))
        val callFactory = ImmediateCallFactory { request, call, callback ->
            callback.onResponse(call, response(request, body))
        }

        val failure = assertThrows(ServiceException::class.java) {
            runBlocking { CommandCodeModelsClient(callFactory, FIXED_CLOCK).fetchCatalogue() }
        }

        assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
        assertFalse(failure.toString().contains(secret))
        assertEquals(1, body.closeCount)
    }

    @Test
    fun cancellationCancelsCallAndClosesAnyLateResponse() = runBlocking {
        val callFactory = PendingCallFactory()
        val fetch = async(start = CoroutineStart.UNDISPATCHED) {
            CommandCodeModelsClient(callFactory, FIXED_CLOCK).fetchCatalogue()
        }
        val call = callFactory.call

        fetch.cancelAndJoin()

        assertTrue(fetch.isCancelled)
        assertEquals(1, call.cancelCount)
        assertTrue(runCatching { fetch.await() }.exceptionOrNull() is CancellationException)
        val lateBody = TrackingResponseBody("{}".toByteArray(Charsets.UTF_8))
        callFactory.respond(lateBody)
        assertEquals(1, lateBody.closeCount)
    }

    private fun response(request: Request, body: ResponseBody, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("scripted")
            .body(body)
            .build()

    private fun failingCallFactory(failure: IOException): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
            override fun request(): Request = request
            override fun enqueue(responseCallback: Callback) {
                responseCallback.onFailure(this, failure)
            }
        }
    }

    private class ImmediateCallFactory(
        private val respond: (Request, Call, Callback) -> Unit,
    ) : Call.Factory {
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
            override fun request(): Request = request
            override fun enqueue(responseCallback: Callback) {
                requests += request
                respond(request, this, responseCallback)
            }
        }
    }

    private class PendingCallFactory : Call.Factory {
        lateinit var call: PendingCall
            private set

        override fun newCall(request: Request): Call = PendingCall(request).also { call = it }

        fun respond(body: ResponseBody) {
            call.callback.onResponse(
                call,
                Response.Builder()
                    .request(call.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("late scripted")
                    .body(body)
                    .build(),
            )
        }
    }

    private class PendingCall(private val recordedRequest: Request) : Call by OkHttpClient().newCall(recordedRequest) {
        lateinit var callback: Callback
            private set
        var cancelCount = 0
            private set

        override fun request(): Request = recordedRequest
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelCount++ }
    }

    private class TrackingResponseBody(
        content: ByteArray,
        private val contentLength: Long = content.size.toLong(),
    ) : ResponseBody() {
        private val source = Buffer().write(content)
        private val trackingSource = object : ForwardingSource(source) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) bytesRead += it
            }
        }.buffer()

        var bytesRead = 0L
            private set
        var closeCount = 0
            private set

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = contentLength
        override fun source(): BufferedSource = trackingSource
        override fun close() {
            closeCount++
            trackingSource.close()
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-04T12:30:00Z"), ZoneOffset.UTC)
    }
}
