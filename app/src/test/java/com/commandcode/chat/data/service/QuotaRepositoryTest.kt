package com.commandcode.chat.data.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
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
    }
}
