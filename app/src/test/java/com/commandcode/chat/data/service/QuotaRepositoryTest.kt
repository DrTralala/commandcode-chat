package com.commandcode.chat.data.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class QuotaRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun successfulRefreshIsSavedAndCanBeLoadedByANewRepository() = runTest {
        val store = ServiceSnapshotStore(context)
        clearPreferences()
        val expected = snapshot()

        try {
            val repository = QuotaRepository(context, FakeApi(Result.success(expected)), store)
            assertEquals(expected, repository.refresh(KEY.toCharArray()))

            val reloaded = QuotaRepository(context, FakeApi(Result.failure(IOException())), store)
            assertEquals(expected, reloaded.loadCached())
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun unavailableResetTimesSurviveHttpRefreshAndPersistentCacheRoundTrip() = runTest {
        val store = ServiceSnapshotStore(context)
        clearPreferences()
        val api = CommandCodeQuotaClient(
            scriptedQuotaResponses(UNAVAILABLE_RESET_CREDITS_JSON, SUBSCRIPTION_JSON),
            Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000), ZoneOffset.UTC),
        )

        try {
            val refreshed = QuotaRepository(context, api, store).refresh(KEY.toCharArray())

            assertEquals("individual-goat", refreshed.planId)
            assertEquals(RemainingQuota(42.5, 70.0), refreshed.monthly)
            assertEquals(4.5, refreshed.fiveHour?.used ?: -1.0, 0.0)
            assertEquals(14.0, refreshed.fiveHour?.cap ?: -1.0, 0.0)
            assertNull(refreshed.fiveHour?.resetAt)
            assertEquals(22.75, refreshed.weekly?.used ?: -1.0, 0.0)
            assertEquals(35.0, refreshed.weekly?.cap ?: -1.0, 0.0)
            assertNull(refreshed.weekly?.resetAt)
            assertEquals(9.5, refreshed.purchasedCredits, 0.0)
            assertEquals(3.25, refreshed.freeCredits, 0.0)

            val persisted = checkNotNull(
                context.getSharedPreferences(ServiceSnapshotStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getString(ServiceSnapshotStore.QUOTA_KEY, null),
            )
            val encoded = JSONObject(persisted)
            val windows = encoded.getJSONObject("windowLimits")
            assertEquals(4, encoded.getInt("schemaVersion"))
            assertTrue(windows.getJSONObject("fiveHour").has("resetAt"))
            assertEquals(JSONObject.NULL, windows.getJSONObject("fiveHour").get("resetAt"))
            assertTrue(windows.getJSONObject("weekly").has("resetAt"))
            assertEquals(JSONObject.NULL, windows.getJSONObject("weekly").get("resetAt"))

            val cached = QuotaRepository(context, FakeApi(Result.failure(IOException())), store).loadCached()
            assertEquals(refreshed, cached)
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun failedRefreshPreservesThePriorSnapshot() = runTest {
        val expected = snapshot()
        val store = MemoryStore(expected)
        val repository = QuotaRepository(context, FakeApi(Result.failure(IOException("network"))), store)

        val failure = runCatching { repository.refresh(KEY.toCharArray()) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(expected, store.snapshot)
        assertEquals(expected, repository.loadCached())
    }

    @Test
    fun invalidCacheJsonIsIgnoredWithoutThrowing() = runTest {
        val store = ServiceSnapshotStore(context)
        clearPreferences()
        try {
            context.getSharedPreferences(ServiceSnapshotStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(ServiceSnapshotStore.QUOTA_KEY, "not-json")
                .commit()

            val repository = QuotaRepository(context, FakeApi(Result.failure(IOException())), store)

            assertNull(repository.loadCached())
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun clearRemovesQuotaData() = runTest {
        val store = ServiceSnapshotStore(context)
        clearPreferences()
        try {
            store.saveQuota(snapshot())

            QuotaRepository(context, FakeApi(Result.failure(IOException())), store).clear()

            assertNull(store.loadQuota())
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun failedPersistentClearInvalidatesTheRepositorySnapshot() = runTest {
        val expected = snapshot()
        val store = MemoryStore(expected).apply { clearFailure = IOException("disk failure") }
        val repository = QuotaRepository(context, FakeApi(Result.failure(IOException())), store)
        assertEquals(expected, repository.loadCached())

        val failure = runCatching { repository.clear() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(expected, store.snapshot)
        assertNull(repository.loadCached())
    }

    private fun clearPreferences() {
        context.getSharedPreferences(ServiceSnapshotStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun snapshot() = QuotaSnapshot(
        fetchedAt = Instant.ofEpochMilli(1_800_000_000_000),
        planId = "goat-pro",
        limited = true,
        monthly = RemainingQuota(42.5, 70.0),
        fiveHour = UsedQuota(4.5, 12.0, Instant.ofEpochMilli(1_800_000_001_234)),
        weekly = UsedQuota(22.75, 80.0, Instant.ofEpochMilli(1_800_000_005_678)),
        purchasedCredits = 9.5,
        freeCredits = 3.25,
    )

    private fun scriptedQuotaResponses(credits: String, subscription: String): Call.Factory {
        val client = OkHttpClient()
        return object : Call.Factory {
            override fun newCall(request: Request): Call {
                val delegate = client.newCall(request)
                return object : Call by delegate {
                    override fun enqueue(responseCallback: Callback) {
                        val responseBody = when (request.url.encodedPath) {
                            "/alpha/billing/credits" -> credits
                            "/alpha/billing/subscriptions" -> subscription
                            else -> error("Unexpected quota endpoint")
                        }
                        responseCallback.onResponse(
                            this,
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("fixture")
                                .body(JsonResponseBody(responseBody))
                                .build(),
                        )
                    }
                }
            }
        }
    }

    private class JsonResponseBody(content: String) : ResponseBody() {
        private val contentBuffer = Buffer().writeUtf8(content)

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = contentBuffer.size
        override fun source(): BufferedSource = contentBuffer
    }

    private class FakeApi(private val result: Result<QuotaSnapshot>) : QuotaApi {
        override suspend fun fetchQuota(apiKey: CharArray): QuotaSnapshot = result.getOrThrow()
    }

    private class MemoryStore(initial: QuotaSnapshot? = null) : QuotaSnapshotStore {
        var snapshot: QuotaSnapshot? = initial
        var clearFailure: Exception? = null

        override fun loadQuota(): QuotaSnapshot? = snapshot
        override fun saveQuota(snapshot: QuotaSnapshot) { this.snapshot = snapshot }
        override fun clearQuota() {
            clearFailure?.let { throw it }
            snapshot = null
        }
    }

    private companion object {
        private val KEY = "test-command-code-key"
        private const val UNAVAILABLE_RESET_CREDITS_JSON =
            "{\"credits\":{\"monthlyCredits\":42.5,\"purchasedCredits\":9.5,\"freeCredits\":3.25}," +
                "\"windowLimits\":{\"limited\":true,\"fiveHour\":{\"used\":4.5,\"cap\":14.0,\"resetAt\":0}," +
                "\"weekly\":{\"used\":22.75,\"cap\":35.0,\"resetAt\":-1}}}"
        private const val SUBSCRIPTION_JSON =
            "{\"data\":{\"planId\":\"individual-goat\",\"status\":\"active\",\"currentPeriodEnd\":1801000000000}}"
    }
}
