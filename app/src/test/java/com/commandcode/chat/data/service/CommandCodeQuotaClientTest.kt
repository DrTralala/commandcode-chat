package com.commandcode.chat.data.service

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
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
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandCodeQuotaClientTest {
    @Test
    fun fetchesQuotaFromTheFixedEndpointWithTheBearerKey() = runBlocking {
        var recordedRequest: Request? = null
        val body = TrackingResponseBody(UPSTREAM_JSON.toByteArray(Charsets.UTF_8))
        val callFactory = scriptedCallFactory(body) { request -> recordedRequest = request }

        val quota = CommandCodeQuotaClient(callFactory, Clock.fixed(FETCHED_AT, ZoneOffset.UTC))
            .fetchQuota("test-key".toCharArray())

        assertEquals("https://api.commandcode.ai/alpha/billing/credits", recordedRequest?.url.toString())
        assertEquals("GET", recordedRequest?.method)
        assertEquals("application/json", recordedRequest?.header("Accept"))
        assertEquals("Bearer test-key", recordedRequest?.header("Authorization"))
        assertEquals(FETCHED_AT, quota.fetchedAt)
        assertTrue(body.closed)
    }

    @Test
    fun translatesTheUpstreamQuotaResponse() = runBlocking {
        val quota = clientFor(UPSTREAM_JSON).fetchQuota("test-key".toCharArray())

        assertEquals("goat", quota.planId)
        assertTrue(quota.limited)
        assertEquals(RemainingQuota(42.5, 70.0), quota.monthly)
        assertEquals(
            UsedQuota(4.5, 14.0, Instant.ofEpochMilli(1_800_000_001_234)),
            quota.fiveHour,
        )
        assertEquals(
            UsedQuota(22.75, 35.0, Instant.ofEpochMilli(1_800_000_005_678)),
            quota.weekly,
        )
        assertEquals(9.5, quota.purchasedCredits, 0.0)
        assertEquals(3.25, quota.freeCredits, 0.0)
    }

    @Test
    fun translatesTheCurrentCreditThresholdSchemaWithoutInventingAPlan() = runBlocking {
        val quota = clientFor(CURRENT_UPSTREAM_JSON).fetchQuota("test-key".toCharArray())

        assertEquals("unreported", quota.planId)
        assertEquals(RemainingQuota(42.5, 70.0), quota.monthly)
        assertEquals(4.5, quota.fiveHour.used, 0.0)
        assertEquals(22.75, quota.weekly.used, 0.0)
    }

    @Test
    fun preservesTheLegacyUpstreamPlanId() = runBlocking {
        val quota = clientFor(UPSTREAM_JSON).fetchQuota("test-key".toCharArray())

        assertEquals("goat", quota.planId)
    }

    @Test
    fun rejectsMixedPartialAndExtendedCurrentCreditSchemas() = runBlocking {
        val invalidBodies = listOf(
            JSONObject(CURRENT_UPSTREAM_JSON).apply {
                getJSONObject("credits").put("planId", "goat")
            }.toString(),
            JSONObject(CURRENT_UPSTREAM_JSON).apply {
                getJSONObject("credits").remove("creditThreshold")
            }.toString(),
            JSONObject(CURRENT_UPSTREAM_JSON).apply {
                getJSONObject("credits").put("extra", 1)
            }.toString(),
        )

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsInvalidCreditThresholdValues() = runBlocking {
        val invalidBodies = listOf(
            CURRENT_UPSTREAM_JSON.replace("\"creditThreshold\":10", "\"creditThreshold\":-1"),
            CURRENT_UPSTREAM_JSON.replace("\"creditThreshold\":10", "\"creditThreshold\":\"10\""),
            CURRENT_UPSTREAM_JSON.replace("\"creditThreshold\":10", "\"creditThreshold\":1e10000"),
        )

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsMissingAndExtraRootAndNestedFields() = runBlocking {
        val invalidBodies = buildList {
            add(JSONObject(UPSTREAM_JSON).apply { remove("credits") }.toString())
            add(JSONObject(UPSTREAM_JSON).apply { remove("windowLimits") }.toString())
            add(JSONObject(UPSTREAM_JSON).apply { put("extra", 1) }.toString())

            val credits = JSONObject(UPSTREAM_JSON).getJSONObject("credits")
            listOf("planId", "monthlyCredits", "purchasedCredits", "freeCredits").forEach { key ->
                add(JSONObject(UPSTREAM_JSON).apply {
                    getJSONObject("credits").remove(key)
                }.toString())
            }
            add(JSONObject(UPSTREAM_JSON).apply {
                getJSONObject("credits").put("extra", 1)
            }.toString())
            listOf("limited", "fiveHour", "weekly").forEach { key ->
                add(JSONObject(UPSTREAM_JSON).apply {
                    getJSONObject("windowLimits").remove(key)
                }.toString())
            }
            add(JSONObject(UPSTREAM_JSON).apply {
                getJSONObject("windowLimits").put("extra", 1)
            }.toString())
            listOf("fiveHour", "weekly").forEach { window ->
                listOf("used", "cap", "resetAt").forEach { key ->
                    add(JSONObject(UPSTREAM_JSON).apply {
                        getJSONObject("windowLimits").getJSONObject(window).remove(key)
                    }.toString())
                }
                add(JSONObject(UPSTREAM_JSON).apply {
                    getJSONObject("windowLimits").getJSONObject(window).put("extra", 1)
                }.toString())
            }
            check(credits.length() == 4)
        }

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsDuplicateEquivalentFields() = runBlocking {
        val duplicateRoot = UPSTREAM_JSON.replace(
            "\"windowLimits\":",
            "\"credits\":{\"planId\":\"goat\",\"monthlyCredits\":42.5,\"purchasedCredits\":9.5,\"freeCredits\":3.25},\"windowLimits\":",
        )
        val duplicateNested = UPSTREAM_JSON.replace(
            "\"monthlyCredits\":42.5,",
            "\"monthlyCredits\":42.5,\"monthlyCredits\":42.5,",
        )

        assertBadResponse(duplicateRoot)
        assertBadResponse(duplicateNested)
    }

    @Test
    fun rejectsBlankPlanIdsAndWrongPrimitiveTypes() = runBlocking {
        val invalidBodies = listOf(
            UPSTREAM_JSON.replace("\"planId\":\"goat\"", "\"planId\":\"\""),
            UPSTREAM_JSON.replace("\"planId\":\"goat\"", "\"planId\":\"   \""),
            UPSTREAM_JSON.replace("\"planId\":\"goat\"", "\"planId\":42"),
            UPSTREAM_JSON.replace("\"limited\":true", "\"limited\":\"true\""),
            UPSTREAM_JSON.replace("\"monthlyCredits\":42.5", "\"monthlyCredits\":\"42.5\""),
            UPSTREAM_JSON.replace("\"used\":4.5", "\"used\":\"4.5\""),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":\"14.0\""),
            UPSTREAM_JSON.replace("\"resetAt\":1800000001234", "\"resetAt\":\"1800000001234\""),
        )

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsInvalidAmountsIncludingNonFiniteOverflowAndCapUnderflow() = runBlocking {
        val invalidBodies = listOf(
            UPSTREAM_JSON.replace("\"monthlyCredits\":42.5", "\"monthlyCredits\":-1"),
            UPSTREAM_JSON.replace("\"used\":4.5", "\"used\":-1"),
            UPSTREAM_JSON.replace("\"purchasedCredits\":9.5", "\"purchasedCredits\":-1"),
            UPSTREAM_JSON.replace("\"freeCredits\":3.25", "\"freeCredits\":-1"),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":0"),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":-1"),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":NaN"),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":Infinity"),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":-Infinity"),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":1e10000"),
            UPSTREAM_JSON.replace("\"cap\":14.0", "\"cap\":1e-400"),
        )

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsNonIntegralNonPositiveAndOverflowingResetTimestamps() = runBlocking {
        val invalidTimestamps = listOf("1.5", "0", "-1", "9223372036854775808", "1e10000")
        invalidTimestamps.forEachIndexed { index, timestamp ->
            assertBadResponse(
                UPSTREAM_JSON.replace("1800000001234", timestamp),
                index.toString(),
            )
        }
    }

    @Test
    fun rejectsTrailingTextAndConcatenatedJson() = runBlocking {
        assertBadResponse("$UPSTREAM_JSON trailing")
        assertBadResponse(UPSTREAM_JSON + UPSTREAM_JSON)
    }

    @Test
    fun acceptsAnUnknownLengthResponseAtTheExactByteBound() = runBlocking {
        val body = UnknownLengthResponseBody(quotaJsonOfLength(QuotaSnapshotCodec.MAX_QUOTA_BYTES))

        val quota = CommandCodeQuotaClient(
            scriptedCallFactory(body),
            Clock.fixed(FETCHED_AT, ZoneOffset.UTC),
        ).fetchQuota("key".toCharArray())

        assertEquals(42.5, quota.monthly.remaining, 0.0)
        assertEquals(QuotaSnapshotCodec.MAX_QUOTA_BYTES.toLong(), body.bytesRead)
        assertTrue(body.closed)
    }

    @Test
    fun decodesThenRejectsMalformedContentAtTheExactUnknownLengthBound() = runBlocking {
        val body = UnknownLengthResponseBody(ByteArray(QuotaSnapshotCodec.MAX_QUOTA_BYTES) { 'x'.code.toByte() })

        val failure = assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeQuotaClient(scriptedCallFactory(body), FIXED_CLOCK)
                    .fetchQuota("key".toCharArray())
            }
        }

        assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
        assertEquals(QuotaSnapshotCodec.MAX_QUOTA_BYTES.toLong(), body.bytesRead)
        assertTrue(body.closed)
    }

    @Test
    fun rejectsKnownAndUnknownResponsesLargerThanTheByteBound() = runBlocking {
        val oversized = ByteArray(QuotaSnapshotCodec.MAX_QUOTA_BYTES + 1) { 'x'.code.toByte() }
        val knownBody = TrackingResponseBody(oversized)
        val knownFailure = assertThrows(ServiceException::class.java) {
            runBlocking { CommandCodeQuotaClient(scriptedCallFactory(knownBody), FIXED_CLOCK).fetchQuota(KEY) }
        }
        assertEquals(ServiceException.Kind.BAD_RESPONSE, knownFailure.kind)
        assertEquals(0L, knownBody.bytesRead)
        assertTrue(knownBody.closed)

        val unknownBody = UnknownLengthResponseBody(oversized)
        val unknownFailure = assertThrows(ServiceException::class.java) {
            runBlocking { CommandCodeQuotaClient(scriptedCallFactory(unknownBody), FIXED_CLOCK).fetchQuota(KEY) }
        }
        assertEquals(ServiceException.Kind.BAD_RESPONSE, unknownFailure.kind)
        assertEquals((QuotaSnapshotCodec.MAX_QUOTA_BYTES + 1).toLong(), unknownBody.bytesRead)
        assertTrue(unknownBody.closed)
    }

    @Test
    fun mapsHttpStatusesAndClosesEveryResponseBody() = runBlocking {
        val expectations = listOf(
            401 to ServiceException.Kind.UNAUTHORIZED,
            403 to ServiceException.Kind.FORBIDDEN,
            429 to ServiceException.Kind.RATE_LIMITED,
            500 to ServiceException.Kind.UNAVAILABLE,
            302 to ServiceException.Kind.BAD_RESPONSE,
        )

        expectations.forEach { (status, kind) ->
            val secret = "secret-upstream-detail"
            val body = TrackingResponseBody(secret.toByteArray(Charsets.UTF_8))
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking {
                    CommandCodeQuotaClient(scriptedCallFactory(body, code = status), FIXED_CLOCK)
                        .fetchQuota(KEY)
                }
            }
            assertEquals(kind, failure.kind)
            assertFalse(failure.toString().contains(secret))
            assertTrue(body.closed)
            assertEquals(1, body.closeCount)
        }
    }

    @Test
    fun mapsTimeoutsAndOtherIoToSafeFailures() = runBlocking {
        val timeoutFailure = assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeQuotaClient(
                    failingCallFactory(SocketTimeoutException("secret-timeout")),
                    FIXED_CLOCK,
                ).fetchQuota(KEY)
            }
        }
        assertEquals(ServiceException.Kind.TIMEOUT, timeoutFailure.kind)
        assertFalse(timeoutFailure.toString().contains("secret-timeout"))

        val interruptedFailure = assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeQuotaClient(
                    failingCallFactory(InterruptedIOException("secret-interrupted")),
                    FIXED_CLOCK,
                ).fetchQuota(KEY)
            }
        }
        assertEquals(ServiceException.Kind.TIMEOUT, interruptedFailure.kind)

        val unavailableFailure = assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeQuotaClient(
                    failingCallFactory(IOException("secret-network")),
                    FIXED_CLOCK,
                ).fetchQuota(KEY)
            }
        }
        assertEquals(ServiceException.Kind.UNAVAILABLE, unavailableFailure.kind)
        assertFalse(unavailableFailure.toString().contains("secret-network"))
    }

    @Test
    fun wipesClientOwnedKeyCopiesAfterSuccessAndFailureWithoutChangingCallerKey() = runBlocking {
        val successCallerKey = "test-command-code-key".toCharArray()
        val successOriginal = successCallerKey.copyOf()
        var successMaterial: ClientKeyMaterial? = null
        val successFactory = ClientKeyMaterialFactory { key ->
            ClientKeyMaterial.from(key).also { successMaterial = it }
        }

        CommandCodeQuotaClient(
            scriptedCallFactory(TrackingResponseBody(UPSTREAM_JSON.toByteArray(Charsets.UTF_8))),
            FIXED_CLOCK,
            successFactory,
        ).fetchQuota(successCallerKey)

        assertClientKeyMaterialWiped(requireNotNull(successMaterial))
        assertArrayEquals(successOriginal, successCallerKey)

        val failureCallerKey = "test-command-code-key".toCharArray()
        val failureOriginal = failureCallerKey.copyOf()
        var failureMaterial: ClientKeyMaterial? = null
        val failureFactory = ClientKeyMaterialFactory { key ->
            ClientKeyMaterial.from(key).also { failureMaterial = it }
        }

        assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeQuotaClient(
                    scriptedCallFactory(
                        TrackingResponseBody("failure".toByteArray(Charsets.UTF_8)),
                        code = 401,
                    ),
                    FIXED_CLOCK,
                    failureFactory,
                ).fetchQuota(failureCallerKey)
            }
        }

        assertClientKeyMaterialWiped(requireNotNull(failureMaterial))
        assertArrayEquals(failureOriginal, failureCallerKey)
    }

    @Test
    fun wipesClientOwnedKeyCopiesWhenTheRequestIsCancelled() = runBlocking {
        val callerKey = "test-command-code-key".toCharArray()
        val originalCallerKey = callerKey.copyOf()
        var material: ClientKeyMaterial? = null
        val factory = ClientKeyMaterialFactory { key ->
            ClientKeyMaterial.from(key).also { material = it }
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                CommandCodeQuotaClient(
                    failingCallFactory(CancellationException("cancelled")),
                    FIXED_CLOCK,
                    factory,
                ).fetchQuota(callerKey)
            }
        }

        assertClientKeyMaterialWiped(requireNotNull(material))
        assertArrayEquals(originalCallerKey, callerKey)
    }

    private fun clientFor(body: String): CommandCodeQuotaClient = CommandCodeQuotaClient(
        scriptedCallFactory(TrackingResponseBody(body.toByteArray(Charsets.UTF_8))),
        FIXED_CLOCK,
    )

    private fun assertBadResponse(body: String, message: String = "") {
        val failure = assertThrows(ServiceException::class.java) {
            runBlocking { clientFor(body).fetchQuota(KEY) }
        }
        assertEquals(message, ServiceException.Kind.BAD_RESPONSE, failure.kind)
    }

    private fun scriptedCallFactory(
        body: ResponseBody,
        code: Int = 200,
        onRequest: (Request) -> Unit = {},
    ): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
            override fun execute(): Response {
                onRequest(request)
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("scripted")
                    .body(body)
                    .build()
            }
        }
    }

    private fun failingCallFactory(failure: Throwable): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
            override fun execute(): Response = throw failure
        }
    }

    private fun assertClientKeyMaterialWiped(material: ClientKeyMaterial) {
        assertTrue(material.copiedChars.all { it == '\u0000' })
        assertTrue(material.copiedBytes.all { it == 0.toByte() })
    }

    private fun quotaJsonOfLength(length: Int): ByteArray {
        val json = UPSTREAM_JSON.toByteArray(Charsets.UTF_8)
        require(json.size < length)
        return json + ByteArray(length - json.size) { ' '.code.toByte() }
    }

    private class TrackingResponseBody(private val content: ByteArray) : ResponseBody() {
        private val source = Buffer().write(content)
        var closed = false
            private set
        var closeCount = 0
            private set
        var bytesRead = 0L
            private set

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = content.size.toLong()
        override fun source(): BufferedSource = object : ForwardingSource(source) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) bytesRead += it
            }
        }.buffer()
        override fun close() {
            closeCount++
            closed = true
            source.close()
            super.close()
        }
    }

    private class UnknownLengthResponseBody(content: ByteArray) : ResponseBody() {
        private val forwardingSource = object : ForwardingSource(Buffer().write(content)) {
            var bytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) bytesRead += it
            }
        }
        private val bufferedSource = forwardingSource.buffer()
        var closed = false
            private set

        val bytesRead: Long
            get() = forwardingSource.bytesRead

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = bufferedSource
        override fun close() {
            bufferedSource.close()
            closed = true
            super.close()
        }
    }

    private companion object {
        private val FETCHED_AT = Instant.ofEpochMilli(1_800_000_000_000)
        private val FIXED_CLOCK = Clock.fixed(FETCHED_AT, ZoneOffset.UTC)
        private val KEY = "test-command-code-key".toCharArray()
        private const val UPSTREAM_JSON =
            "{\"credits\":{\"planId\":\"goat\",\"monthlyCredits\":42.5,\"purchasedCredits\":9.5,\"freeCredits\":3.25}," +
                "\"windowLimits\":{\"limited\":true,\"fiveHour\":{\"used\":4.5,\"cap\":14.0,\"resetAt\":1800000001234}," +
                "\"weekly\":{\"used\":22.75,\"cap\":35.0,\"resetAt\":1800000005678}}}"
        private const val CURRENT_UPSTREAM_JSON =
            "{\"credits\":{\"creditThreshold\":10,\"monthlyCredits\":42.5,\"purchasedCredits\":9.5,\"freeCredits\":3.25}," +
                "\"windowLimits\":{\"limited\":true,\"fiveHour\":{\"used\":4.5,\"cap\":14.0,\"resetAt\":1800000001234}," +
                "\"weekly\":{\"used\":22.75,\"cap\":35.0,\"resetAt\":1800000005678}}}"
    }
}
