package com.commandcode.chat.data.service

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
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
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandCodeQuotaClientTest {
    @Test
    fun fetchesCreditsAndSubscriptionFromFixedEndpointsWithTheSameBearerKey() = runBlocking {
        val requests = mutableListOf<Request>()
        var bearerConstructionCount = 0
        val bearerFactory = BearerFactory { bytes ->
            bearerConstructionCount++
            "Bearer ${bytes.toString(Charsets.UTF_8)}"
        }
        val creditsBody = TrackingResponseBody(CURRENT_CREDITS_JSON.toByteArray(Charsets.UTF_8))
        val subscriptionBody = TrackingResponseBody(SUBSCRIPTION_JSON.toByteArray(Charsets.UTF_8))
        val callFactory = routedCallFactory(
            mapOf(
                CREDITS_PATH to ScriptedResponse(body = creditsBody),
                SUBSCRIPTION_PATH to ScriptedResponse(body = subscriptionBody),
            ),
            requests,
        )

        val quota = CommandCodeQuotaClient(
            callFactory,
            Clock.fixed(FETCHED_AT, ZoneOffset.UTC),
            ClientKeyMaterialFactory(ClientKeyMaterial::from),
            bearerFactory,
        )
            .fetchQuota("test-key".toCharArray())

        assertEquals(1, bearerConstructionCount)
        assertEquals(setOf(CREDITS_PATH, SUBSCRIPTION_PATH), requests.map { it.url.encodedPath }.toSet())
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("GET", request.method)
            assertEquals("application/json", request.header("Accept"))
            assertEquals("Bearer test-key", request.header("Authorization"))
        }
        assertEquals(FETCHED_AT, quota.fetchedAt)
        assertTrue(creditsBody.closed)
        assertTrue(subscriptionBody.closed)
        assertEquals(1, creditsBody.closeCount)
        assertEquals(1, subscriptionBody.closeCount)
    }

    @Test
    fun translatesCurrentCreditsAndEnrichesThemWithTheSubscriptionPlan() = runBlocking {
        val quota = clientFor(CURRENT_CREDITS_JSON).fetchQuota("test-key".toCharArray())

        assertEquals("individual-goat", quota.planId)
        assertEquals(true, quota.limited)
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
        assertEquals(0.0, quota.freeCredits, 0.0)
    }

    @Test
    fun acceptsMissingAndNullWindowLimits() = runBlocking {
        val absent = "{\"credits\":{\"monthlyCredits\":42.5}}"
        val explicitNull = "{\"credits\":{\"monthlyCredits\":42.5},\"windowLimits\":null}"

        listOf(absent, explicitNull).forEach { body ->
            val quota = clientFor(body, subscriptionBody = NO_SUBSCRIPTION_JSON).fetchQuota(KEY)
            assertNull(quota.planId)
            assertEquals(RemainingQuota(42.5, null), quota.monthly)
            assertNull(quota.limited)
            assertNull(quota.fiveHour)
            assertNull(quota.weekly)
            assertEquals(0.0, quota.purchasedCredits, 0.0)
            assertEquals(0.0, quota.freeCredits, 0.0)
        }
    }

    @Test
    fun acceptsAdditiveFieldsAtEveryUpstreamObjectLevel() = runBlocking {
        val credits = JSONObject(CURRENT_CREDITS_JSON).apply {
            put("futureRoot", true)
            getJSONObject("credits").put("futureCredits", JSONObject().put("state", "ok"))
            getJSONObject("windowLimits").apply {
                put("futureLimits", 1)
                getJSONObject("fiveHour").put("futureWindow", false)
                getJSONObject("weekly").put("futureWindow", false)
            }
        }.toString()
        val subscription = JSONObject(SUBSCRIPTION_JSON).apply {
            put("futureRoot", true)
            getJSONObject("data").put("futureSubscription", "value")
        }.toString()

        val quota = clientFor(credits, subscription).fetchQuota(KEY)

        assertEquals("individual-goat", quota.planId)
        assertEquals(42.5, quota.monthly.remaining, 0.0)
    }

    @Test
    fun subscriptionFailuresDoNotHideValidCredits() = runBlocking {
        val unavailable = clientFor(
            CURRENT_CREDITS_JSON,
            subscriptionBody = "service unavailable",
            subscriptionCode = 500,
        ).fetchQuota(KEY)
        val malformed = clientFor(
            CURRENT_CREDITS_JSON,
            subscriptionBody = "not-json",
        ).fetchQuota(KEY)
        val wrongPlanType = clientFor(
            CURRENT_CREDITS_JSON,
            subscriptionBody = "{\"data\":{\"planId\":42}}",
        ).fetchQuota(KEY)

        listOf(unavailable, malformed, wrongPlanType).forEach { quota ->
            assertNull(quota.planId)
            assertNull(quota.monthly.cap)
            assertEquals(42.5, quota.monthly.remaining, 0.0)
        }
    }

    @Test
    fun unknownSubscriptionPlanIsRetainedWithoutInventingACap() = runBlocking {
        val quota = clientFor(
            CURRENT_CREDITS_JSON,
            subscriptionBody = "{\"data\":{\"planId\":\"future-plan\"}}",
        ).fetchQuota(KEY)

        assertEquals("future-plan", quota.planId)
        assertEquals(RemainingQuota(42.5, null), quota.monthly)
    }

    @Test
    fun rejectsMissingConsumedFieldsWhileAllowingOptionalAndAdditiveFields() = runBlocking {
        val invalidBodies = buildList {
            add(JSONObject(CURRENT_CREDITS_JSON).apply { remove("credits") }.toString())
            add(JSONObject(CURRENT_CREDITS_JSON).apply {
                getJSONObject("credits").remove("monthlyCredits")
            }.toString())
            listOf("limited", "fiveHour", "weekly").forEach { key ->
                add(JSONObject(CURRENT_CREDITS_JSON).apply {
                    getJSONObject("windowLimits").remove(key)
                }.toString())
            }
            listOf("fiveHour", "weekly").forEach { window ->
                listOf("used", "cap", "resetAt").forEach { key ->
                    add(JSONObject(CURRENT_CREDITS_JSON).apply {
                        getJSONObject("windowLimits").getJSONObject(window).remove(key)
                    }.toString())
                }
            }
        }

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsDuplicateEquivalentFields() = runBlocking {
        val duplicateRoot = CURRENT_CREDITS_JSON.replace(
            "\"windowLimits\":",
            "\"credits\":{\"monthlyCredits\":42.5},\"windowLimits\":",
        )
        val duplicateNested = CURRENT_CREDITS_JSON.replace(
            "\"monthlyCredits\":42.5,",
            "\"monthlyCredits\":42.5,\"monthlyCredits\":42.5,",
        )

        assertBadResponse(duplicateRoot)
        assertBadResponse(duplicateNested)
    }

    @Test
    fun rejectsWrongPrimitiveTypesForConsumedCreditAndWindowFields() = runBlocking {
        val invalidBodies = listOf(
            CURRENT_CREDITS_JSON.replace("\"limited\":true", "\"limited\":\"true\""),
            CURRENT_CREDITS_JSON.replace("\"monthlyCredits\":42.5", "\"monthlyCredits\":\"42.5\""),
            CURRENT_CREDITS_JSON.replace("\"used\":4.5", "\"used\":\"4.5\""),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":\"14.0\""),
            CURRENT_CREDITS_JSON.replace("\"resetAt\":1800000001234", "\"resetAt\":\"1800000001234\""),
        )

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsInvalidAmountsIncludingNonFiniteOverflowAndCapUnderflow() = runBlocking {
        val invalidBodies = listOf(
            CURRENT_CREDITS_JSON.replace("\"monthlyCredits\":42.5", "\"monthlyCredits\":-1"),
            CURRENT_CREDITS_JSON.replace("\"used\":4.5", "\"used\":-1"),
            CURRENT_CREDITS_JSON.replace("\"purchasedCredits\":9.5", "\"purchasedCredits\":-1"),
            JSONObject(CURRENT_CREDITS_JSON).apply {
                getJSONObject("credits").put("freeCredits", -1)
            }.toString(),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":0"),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":-1"),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":NaN"),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":Infinity"),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":-Infinity"),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":1e10000"),
            CURRENT_CREDITS_JSON.replace("\"cap\":14.0", "\"cap\":1e-400"),
        )

        invalidBodies.forEachIndexed { index, body -> assertBadResponse(body, index.toString()) }
    }

    @Test
    fun rejectsNonIntegralNonPositiveAndOverflowingResetTimestamps() = runBlocking {
        val invalidTimestamps = listOf("1.5", "0", "-1", "9223372036854775808", "1e10000")
        invalidTimestamps.forEachIndexed { index, timestamp ->
            assertBadResponse(
                CURRENT_CREDITS_JSON.replace("1800000001234", timestamp),
                index.toString(),
            )
        }
    }

    @Test
    fun rejectsTrailingTextAndConcatenatedJson() = runBlocking {
        assertBadResponse("$CURRENT_CREDITS_JSON trailing")
        assertBadResponse(CURRENT_CREDITS_JSON + CURRENT_CREDITS_JSON)
    }

    @Test
    fun acceptsAnUnknownLengthResponseAtTheExactByteBound() = runBlocking {
        val body = UnknownLengthResponseBody(quotaJsonOfLength(QuotaSnapshotCodec.MAX_QUOTA_BYTES))

        val quota = CommandCodeQuotaClient(
            creditsCallFactory(body),
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
                CommandCodeQuotaClient(creditsCallFactory(body), FIXED_CLOCK)
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
            runBlocking { CommandCodeQuotaClient(creditsCallFactory(knownBody), FIXED_CLOCK).fetchQuota(KEY) }
        }
        assertEquals(ServiceException.Kind.BAD_RESPONSE, knownFailure.kind)
        assertEquals(0L, knownBody.bytesRead)
        assertTrue(knownBody.closed)

        val unknownBody = UnknownLengthResponseBody(oversized)
        val unknownFailure = assertThrows(ServiceException::class.java) {
            runBlocking { CommandCodeQuotaClient(creditsCallFactory(unknownBody), FIXED_CLOCK).fetchQuota(KEY) }
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
                    CommandCodeQuotaClient(creditsCallFactory(body, code = status), FIXED_CLOCK)
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
            creditsCallFactory(TrackingResponseBody(CURRENT_CREDITS_JSON.toByteArray(Charsets.UTF_8))),
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
                    creditsCallFactory(
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
    fun parentCancellationCancelsBothCallsAndPromptlyWipesClientOwnedKeyCopies() = runBlocking {
        val callerKey = "test-command-code-key".toCharArray()
        val originalCallerKey = callerKey.copyOf()
        var material: ClientKeyMaterial? = null
        val factory = ClientKeyMaterialFactory { key ->
            ClientKeyMaterial.from(key).also { material = it }
        }

        val callFactory = ControllableAsyncCallFactory()
        val fetch = async {
            CommandCodeQuotaClient(callFactory, FIXED_CLOCK, factory).fetchQuota(callerKey)
        }

        callFactory.bothCallsStarted.await()
        fetch.cancel()
        fetch.join()

        assertTrue(fetch.isCancelled)
        assertThrows(CancellationException::class.java) { runBlocking { fetch.await() } }
        assertEquals(1, callFactory.callFor(CREDITS_PATH).cancelCount)
        assertEquals(1, callFactory.callFor(SUBSCRIPTION_PATH).cancelCount)
        assertClientKeyMaterialWiped(requireNotNull(material))
        assertArrayEquals(originalCallerKey, callerKey)

        val lateCreditsBody = TrackingResponseBody(CURRENT_CREDITS_JSON.toByteArray(Charsets.UTF_8))
        val lateSubscriptionBody = TrackingResponseBody(SUBSCRIPTION_JSON.toByteArray(Charsets.UTF_8))
        callFactory.callFor(CREDITS_PATH).respond(lateCreditsBody)
        callFactory.callFor(SUBSCRIPTION_PATH).respond(lateSubscriptionBody)
        assertEquals(1, lateCreditsBody.closeCount)
        assertEquals(1, lateSubscriptionBody.closeCount)
    }

    private fun clientFor(
        body: String,
        subscriptionBody: String = SUBSCRIPTION_JSON,
        subscriptionCode: Int = 200,
    ): CommandCodeQuotaClient = CommandCodeQuotaClient(
        routedCallFactory(
            mapOf(
                CREDITS_PATH to ScriptedResponse(
                    body = TrackingResponseBody(body.toByteArray(Charsets.UTF_8)),
                ),
                SUBSCRIPTION_PATH to ScriptedResponse(
                    code = subscriptionCode,
                    body = TrackingResponseBody(subscriptionBody.toByteArray(Charsets.UTF_8)),
                ),
            ),
            mutableListOf(),
        ),
        FIXED_CLOCK,
    )

    private fun assertBadResponse(body: String, message: String = "") {
        val failure = assertThrows(ServiceException::class.java) {
            runBlocking { clientFor(body).fetchQuota(KEY) }
        }
        assertEquals(message, ServiceException.Kind.BAD_RESPONSE, failure.kind)
    }

    private fun creditsCallFactory(
        body: ResponseBody,
        code: Int = 200,
    ): Call.Factory = routedCallFactory(
        mapOf(
            CREDITS_PATH to ScriptedResponse(code = code, body = body),
            SUBSCRIPTION_PATH to ScriptedResponse(
                body = TrackingResponseBody(SUBSCRIPTION_JSON.toByteArray(Charsets.UTF_8)),
            ),
        ),
        mutableListOf(),
    )

    private fun routedCallFactory(
        responses: Map<String, ScriptedResponse>,
        requests: MutableList<Request>,
    ): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
            override fun enqueue(responseCallback: Callback) {
                synchronized(requests) { requests += request }
                val scripted = checkNotNull(responses[request.url.encodedPath])
                val response = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(scripted.code)
                    .message("scripted")
                    .body(scripted.body)
                    .build()
                responseCallback.onResponse(this, response)
            }
        }
    }

    private fun failingCallFactory(failure: IOException): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
            override fun enqueue(responseCallback: Callback) {
                responseCallback.onFailure(this, failure)
            }
        }
    }

    private class ControllableAsyncCallFactory : Call.Factory {
        private val calls = mutableMapOf<String, ControllableAsyncCall>()
        val bothCallsStarted = CompletableDeferred<Unit>()

        override fun newCall(request: Request): Call = ControllableAsyncCall(request) {
            synchronized(calls) {
                calls[request.url.encodedPath] = it
                if (calls.keys.containsAll(setOf(CREDITS_PATH, SUBSCRIPTION_PATH))) {
                    bothCallsStarted.complete(Unit)
                }
            }
        }

        fun callFor(path: String): ControllableAsyncCall = synchronized(calls) {
            checkNotNull(calls[path])
        }
    }

    private class ControllableAsyncCall(
        private val request: Request,
        private val onStarted: (ControllableAsyncCall) -> Unit,
    ) : Call by OkHttpClient().newCall(request) {
        private lateinit var responseCallback: Callback
        var cancelCount = 0
            private set

        override fun enqueue(responseCallback: Callback) {
            this.responseCallback = responseCallback
            onStarted(this)
        }

        override fun execute(): Response = error("Quota transport must use asynchronous Call.enqueue")

        override fun cancel() {
            cancelCount++
            responseCallback.onFailure(this, IOException("scripted cancellation"))
        }

        fun respond(body: ResponseBody) {
            responseCallback.onResponse(
                this,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("late scripted response")
                    .body(body)
                    .build(),
            )
        }
    }

    private fun assertClientKeyMaterialWiped(material: ClientKeyMaterial) {
        assertTrue(material.copiedChars.all { it == '\u0000' })
        assertTrue(material.copiedBytes.all { it == 0.toByte() })
    }

    private fun quotaJsonOfLength(length: Int): ByteArray {
        val json = CURRENT_CREDITS_JSON.toByteArray(Charsets.UTF_8)
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

    private data class ScriptedResponse(
        val code: Int = 200,
        val body: ResponseBody,
    )

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
        private const val CURRENT_CREDITS_JSON =
            "{\"credits\":{\"monthlyCredits\":42.5,\"purchasedCredits\":9.5}," +
                "\"windowLimits\":{\"limited\":true,\"fiveHour\":{\"used\":4.5,\"cap\":14.0,\"resetAt\":1800000001234}," +
                "\"weekly\":{\"used\":22.75,\"cap\":35.0,\"resetAt\":1800000005678}}}"
        private const val SUBSCRIPTION_JSON =
            "{\"success\":true,\"data\":{\"planId\":\"individual-goat\",\"status\":\"active\",\"currentPeriodEnd\":1801000000000}}"
        private const val NO_SUBSCRIPTION_JSON = "{\"success\":true,\"data\":null}"
        private const val CREDITS_PATH = "/alpha/billing/credits"
        private const val SUBSCRIPTION_PATH = "/alpha/billing/subscriptions"
    }
}
