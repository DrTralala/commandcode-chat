package com.commandcode.chat.data.service

import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandCodeServiceClientTest {
    @Test
    fun requestsTheFixedCataloguePathWithGet() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = VALID_JSON))
        server.start()
        try {
            CommandCodeServiceClient(server.url("/configured/base").toString()).fetchModels()

            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals("GET", request?.method)
            assertEquals("/v1/goat/models", request?.target)
        } finally {
            server.close()
        }
    }

    @Test
    fun parsesAValidCatalogueResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = VALID_JSON))
        server.start()
        try {
            val snapshot = CommandCodeServiceClient(server.url("/").toString()).fetchModels()

            assertEquals(1, snapshot.schemaVersion)
            assertEquals("test", snapshot.catalogueVersion)
            assertEquals(1788048000001L, snapshot.generatedAt)
            val remote = snapshot.models.first { it.apiId == "remote/model" }
            assertEquals("Remote model", remote.displayName)
        } finally {
            server.close()
        }
    }

    @Test
    fun mapsNonSuccessfulResponsesToSafeTypedFailures() = runBlocking {
        val server = MockWebServer()
        val secret = "server-body-secret-${server.hashCode()}"
        server.enqueue(MockResponse.Builder().code(401).body(secret).build())
        server.start()
        try {
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking { CommandCodeServiceClient(server.url("/").toString()).fetchModels() }
            }

            assertEquals(ServiceException.Kind.UNAUTHORIZED, failure.kind)
            assertFalse(failure.toString().contains(secret))
            assertFalse(failure.toString().contains(server.url("/").toString()))
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsRedirectResponsesWithoutFollowingThem() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", server.url("/v1/goat/models"))
                .build(),
        )
        try {
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking { CommandCodeServiceClient(server.url("/").toString()).fetchModels() }
            }

            assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsResponsesLargerThanTheCatalogueLimit() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = "x".repeat(131_073)))
        server.start()
        try {
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking { CommandCodeServiceClient(server.url("/").toString()).fetchModels() }
            }

            assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsMalformedJson() = runBlocking {
        assertInvalidResponse("{")
    }

    @Test
    fun rejectsDuplicateModelIds() = runBlocking {
        assertInvalidResponse(
            catalogueJson(
                "{\"id\":\"same\",\"displayName\":\"One\",\"apiFamily\":\"openai-chat\"}," +
                    "{\"id\":\"same\",\"displayName\":\"Two\",\"apiFamily\":\"openai-chat\"}",
            ),
        )
    }

    @Test
    fun rejectsEmptyAndSolLessCatalogueResponses() = runBlocking {
        assertInvalidResponse(catalogueJson(""))
        assertInvalidResponse(
            catalogueJson("{\"id\":\"remote/model\",\"displayName\":\"Remote model\",\"apiFamily\":\"openai-chat\"}"),
        )
    }

    @Test
    fun rejectsUnsupportedApiFamilies() = runBlocking {
        assertInvalidResponse(
            catalogueJson("{\"id\":\"id\",\"displayName\":\"Name\",\"apiFamily\":\"other\"}"),
        )
    }

    @Test
    fun rejectsBlankAndWhitespaceOnlyModelIdsAndDisplayNames() = runBlocking {
        val invalidModels = listOf(
            "{\"id\":\"\",\"displayName\":\"Name\",\"apiFamily\":\"openai-chat\"}",
            "{\"id\":\"   \",\"displayName\":\"Name\",\"apiFamily\":\"openai-chat\"}",
            "{\"id\":\"id\",\"displayName\":\"\",\"apiFamily\":\"openai-chat\"}",
            "{\"id\":\"id\",\"displayName\":\"   \",\"apiFamily\":\"openai-chat\"}",
        )
        val canonicalSol =
            "{\"id\":\"gpt-5.6-sol\",\"displayName\":\"GPT-5.6 Sol\",\"apiFamily\":\"openai-chat\"}"

        invalidModels.forEach { invalid ->
            assertInvalidResponse(catalogueJson("$canonicalSol,$invalid"))
        }
    }

    @Test
    fun closesTheResponseBodyAfterParsing() = runBlocking {
        val body = TrackingResponseBody(VALID_JSON)
        val callFactory = object : Call.Factory {
            override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
                override fun execute(): Response = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("scripted")
                    .body(body)
                    .build()
            }
        }

        CommandCodeServiceClient("https://service.invalid", callFactory).fetchModels()

        assertTrue(body.closed)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun fetchesQuotaWithTheFixedPathAndBearerKey() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = VALID_QUOTA_JSON))
        server.start()
        val key = "test-command-code-key".toCharArray()
        try {
            val quota = CommandCodeServiceClient(server.url("/configured/base").toString()).fetchQuota(key)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)

            assertEquals("GET", recorded?.method)
            assertEquals("/v1/goat/quota", recorded?.url?.encodedPath)
            assertEquals("Bearer test-command-code-key", recorded?.headers?.get("Authorization"))
            assertEquals(42.5, quota.monthly.remaining, 0.0)
            assertEquals(70.0, quota.monthly.cap, 0.0)
            assertEquals(Instant.ofEpochMilli(1_800_000_000_000), quota.fetchedAt)
            assertEquals("goat-pro", quota.planId)
            assertTrue(quota.limited)
            assertEquals(4.5, quota.fiveHour.used, 0.0)
            assertEquals(12.0, quota.fiveHour.cap, 0.0)
            assertEquals(Instant.ofEpochMilli(1_800_000_001_234), quota.fiveHour.resetAt)
            assertEquals(22.75, quota.weekly.used, 0.0)
            assertEquals(80.0, quota.weekly.cap, 0.0)
            assertEquals(Instant.ofEpochMilli(1_800_000_005_678), quota.weekly.resetAt)
            assertEquals(9.5, quota.purchasedCredits, 0.0)
            assertEquals(3.25, quota.freeCredits, 0.0)
            assertArrayEquals("test-command-code-key".toCharArray(), key)
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsMissingQuotaFieldsAndSchemaMismatch() = runBlocking {
        val missingFields = listOf(
            "schemaVersion",
            "fetchedAt",
            "planId",
            "limited",
            "monthly.remaining",
            "monthly.cap",
            "fiveHour.used",
            "fiveHour.cap",
            "fiveHour.resetAt",
            "weekly.used",
            "weekly.cap",
            "weekly.resetAt",
            "purchasedCredits",
            "freeCredits",
        )

        missingFields.forEachIndexed { index, name ->
            assertBadQuotaResponse(withoutQuotaField(name), index.toString())
        }
        assertBadQuotaResponse(VALID_QUOTA_JSON.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2"))
    }

    @Test
    fun rejectsInvalidQuotaValuesAndMalformedJson() = runBlocking {
        val malformedBodies = listOf(
            "not-json",
            "{",
            "$VALID_QUOTA_JSON trailing",
            VALID_QUOTA_JSON + VALID_QUOTA_JSON,
            VALID_QUOTA_JSON.replace("\"monthly\":{", "\"monthly\":{\"extra\":1,"),
            VALID_QUOTA_JSON.replace("\"monthly\":{", "\"monthly\":\"bad\""),
            VALID_QUOTA_JSON.replaceFirst("\"planId\":\"goat-pro\"", "\"planId\":42"),
            VALID_QUOTA_JSON.replaceFirst("\"limited\":true", "\"limited\":\"true\""),
            VALID_QUOTA_JSON.replace("\"remaining\":42.5", "\"remaining\":\"42.5\""),
            VALID_QUOTA_JSON.replace("\"remaining\":42.5", "\"remaining\":-1"),
            VALID_QUOTA_JSON.replace("\"used\":4.5", "\"used\":-1"),
            VALID_QUOTA_JSON.replace("\"used\":22.75", "\"used\":-1"),
            VALID_QUOTA_JSON.replace("\"purchasedCredits\":9.5", "\"purchasedCredits\":-1"),
            VALID_QUOTA_JSON.replace("\"freeCredits\":3.25", "\"freeCredits\":-1"),
            VALID_QUOTA_JSON.replace("\"cap\":70.0", "\"cap\":0"),
            VALID_QUOTA_JSON.replace("\"cap\":12.0", "\"cap\":-1"),
            VALID_QUOTA_JSON.replace("\"cap\":80.0", "\"cap\":0"),
            VALID_QUOTA_JSON.replace("\"resetAt\":1800000001234", "\"resetAt\":1.5"),
            VALID_QUOTA_JSON.replace("\"resetAt\":1800000005678", "\"resetAt\":-1"),
            VALID_QUOTA_JSON.replace("\"freeCredits\":3.25", "\"freeCredits\":1e10000"),
        )

        malformedBodies.forEachIndexed { index, body -> assertBadQuotaResponse(body, index.toString()) }
    }

    @Test
    fun mapsQuotaStatusesAndRejectsRedirects() = runBlocking {
        val expectations = listOf(
            401 to ServiceException.Kind.UNAUTHORIZED,
            403 to ServiceException.Kind.FORBIDDEN,
            429 to ServiceException.Kind.RATE_LIMITED,
            500 to ServiceException.Kind.UNAVAILABLE,
            302 to ServiceException.Kind.BAD_RESPONSE,
        )

        expectations.forEach { (status, kind) ->
            val server = MockWebServer()
            server.enqueue(MockResponse.Builder().code(status).body("secret-upstream-detail").build())
            server.start()
            try {
                val failure = assertThrows(ServiceException::class.java) {
                    runBlocking { CommandCodeServiceClient(server.url("/").toString()).fetchQuota("key".toCharArray()) }
                }
                assertEquals(kind, failure.kind)
                assertFalse(failure.toString().contains("secret-upstream-detail"))
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun rejectsQuotaResponsesLargerThanTheBound() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = "x".repeat(65_537)))
        server.start()
        try {
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking { CommandCodeServiceClient(server.url("/").toString()).fetchQuota("key".toCharArray()) }
            }
            assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
        } finally {
            server.close()
        }
    }

    @Test
    fun acceptsAnUnknownLengthQuotaResponseAtTheExactBound() = runBlocking {
        val body = UnknownLengthResponseBody(quotaJsonOfLength(65_536))

        val quota = CommandCodeServiceClient(
            "https://service.invalid",
            scriptedCallFactory(body),
        ).fetchQuota("key".toCharArray())

        assertEquals(42.5, quota.monthly.remaining, 0.0)
        assertEquals(65_536L, body.bytesRead)
        assertTrue(body.closed)
    }

    @Test
    fun probesOneBytePastAnUnknownLengthQuotaResponseBeforeRejectingIt() = runBlocking {
        val body = UnknownLengthResponseBody(quotaJsonOfLength(65_537))

        val failure = assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeServiceClient(
                    "https://service.invalid",
                    scriptedCallFactory(body),
                ).fetchQuota("key".toCharArray())
            }
        }

        assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
        assertEquals(65_537L, body.bytesRead)
        assertTrue(body.closed)
    }

    @Test
    fun wipesClientOwnedKeyCopiesAfterASuccessfulQuotaRequest() = runBlocking {
        val callerKey = "test-command-code-key".toCharArray()
        val originalCallerKey = callerKey.copyOf()
        var material: ClientKeyMaterial? = null
        val factory = ClientKeyMaterialFactory { key ->
            ClientKeyMaterial.from(key).also { material = it }
        }

        CommandCodeServiceClient(
            "https://service.invalid",
            scriptedCallFactory(TrackingResponseBody(VALID_QUOTA_JSON)),
            factory,
        ).fetchQuota(callerKey)

        assertClientKeyMaterialWiped(requireNotNull(material))
        assertArrayEquals(originalCallerKey, callerKey)
    }

    @Test
    fun wipesClientOwnedKeyCopiesAfterAFailedQuotaRequest() = runBlocking {
        val callerKey = "test-command-code-key".toCharArray()
        val originalCallerKey = callerKey.copyOf()
        var material: ClientKeyMaterial? = null
        val factory = ClientKeyMaterialFactory { key ->
            ClientKeyMaterial.from(key).also { material = it }
        }

        assertThrows(ServiceException::class.java) {
            runBlocking {
                CommandCodeServiceClient(
                    "https://service.invalid",
                    scriptedCallFactory(TrackingResponseBody("failure"), code = 401),
                    factory,
                ).fetchQuota(callerKey)
            }
        }

        assertClientKeyMaterialWiped(requireNotNull(material))
        assertArrayEquals(originalCallerKey, callerKey)
    }

    private suspend fun assertBadQuotaResponse(body: String, message: String = "") {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = body))
        server.start()
        try {
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking { CommandCodeServiceClient(server.url("/").toString()).fetchQuota("key".toCharArray()) }
            }
            assertEquals(message, ServiceException.Kind.BAD_RESPONSE, failure.kind)
        } finally {
            server.close()
        }
    }

    private fun withoutQuotaField(path: String): String {
        val names = path.split('.')
        val root = JSONObject(VALID_QUOTA_JSON)
        if (names.size == 1) {
            root.remove(names.single())
        } else {
            root.getJSONObject(names.first()).remove(names.last())
        }
        return root.toString()
    }

    private fun scriptedCallFactory(body: ResponseBody, code: Int = 200): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = object : Call by OkHttpClient().newCall(request) {
            override fun execute(): Response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("scripted")
                .body(body)
                .build()
        }
    }

    private fun assertClientKeyMaterialWiped(material: ClientKeyMaterial) {
        assertTrue(material.copiedChars.all { it == '\u0000' })
        assertTrue(material.copiedBytes.all { it == 0.toByte() })
    }

    private fun quotaJsonOfLength(length: Int): ByteArray {
        val json = VALID_QUOTA_JSON.toByteArray(Charsets.UTF_8)
        require(json.size < length)
        return json + ByteArray(length - json.size) { ' '.code.toByte() }
    }

    private suspend fun assertInvalidResponse(body: String) {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = body))
        server.start()
        try {
            val failure = assertThrows(ServiceException::class.java) {
                runBlocking { CommandCodeServiceClient(server.url("/").toString()).fetchModels() }
            }

            assertEquals(ServiceException.Kind.BAD_RESPONSE, failure.kind)
        } finally {
            server.close()
        }
    }

    private fun catalogueJson(models: String) =
        "{\"schemaVersion\":1,\"catalogueVersion\":\"test\",\"generatedAt\":1788048000001,\"models\":[${models}]}"

    private class TrackingResponseBody(content: String) : ResponseBody() {
        private val source = Buffer().writeUtf8(content)
        var closed = false
            private set
        var closeCount = 0
            private set

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = source.size
        override fun source(): BufferedSource = source
        override fun close() {
            closeCount++
            closed = true
            super.close()
        }
    }

    private class UnknownLengthResponseBody(content: ByteArray) : ResponseBody() {
        private val forwardingSource = object : ForwardingSource(Buffer().write(content)) {
            var bytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count > 0) bytesRead += count
                return count
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

    companion object {
        private const val VALID_JSON =
            "{\"schemaVersion\":1,\"catalogueVersion\":\"test\",\"generatedAt\":1788048000001,\"models\":[" +
                "{\"id\":\"gpt-5.6-sol\",\"displayName\":\"GPT-5.6 Sol\",\"apiFamily\":\"openai-chat\"}," +
                "{\"id\":\"remote/model\",\"displayName\":\"Remote model\",\"apiFamily\":\"openai-chat\"}]}"

        private const val VALID_QUOTA_JSON =
            "{\"schemaVersion\":1,\"fetchedAt\":1800000000000,\"planId\":\"goat-pro\",\"limited\":true," +
                "\"monthly\":{\"remaining\":42.5,\"cap\":70.0}," +
                "\"fiveHour\":{\"used\":4.5,\"cap\":12.0,\"resetAt\":1800000001234}," +
                "\"weekly\":{\"used\":22.75,\"cap\":80.0,\"resetAt\":1800000005678}," +
                "\"purchasedCredits\":9.5,\"freeCredits\":3.25}"
    }
}
