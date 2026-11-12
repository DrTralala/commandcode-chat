package com.commandcode.chat.data.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ModelCatalogueSnapshotStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun savesOnlyTheEncodedCatalogueInItsIndependentPreferencesAndReloadsAfterRestart() = runTest {
        clearPreferences()
        try {
            val snapshot = ModelCatalogueSnapshot(
                2,
                "fixture",
                1_793_750_400_000L,
                listOf(ChatModel("vendor/messages", "Messages", ApiFamily.ANTHROPIC_MESSAGES, 200_000L)),
                CatalogueOrigin.LIVE,
            )

            withContext(Dispatchers.IO) { ModelCatalogueSnapshotStore(context).saveCatalogue(snapshot) }

            val preferences = context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE)
            val encoded = preferences.getString("snapshot", null)
            assertEquals(2, org.json.JSONObject(requireNotNull(encoded)).getInt("schemaVersion"))
            assertFalse(org.json.JSONObject(requireNotNull(encoded)).has("origin"))
            assertEquals(
                snapshot.models,
                withContext(Dispatchers.IO) { ModelCatalogueSnapshotStore(context).loadCatalogue() }?.models,
            )
            assertEquals(
                CatalogueOrigin.BUNDLED,
                withContext(Dispatchers.IO) { ModelCatalogueSnapshotStore(context).loadCatalogue() }?.origin,
            )
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun knownContextMetadataSurvivesCatalogueRetirementWithoutRestoringChoicesAfterRestart() = runTest {
        clearPreferences()
        try {
            val retired = ChatModel("vendor/retired", "Retired model", ApiFamily.OPENAI_CHAT, 32_000L)
            val unknown = ChatModel("vendor/unknown", "Unknown context", ApiFamily.OPENAI_CHAT)
            val current = ChatModel("vendor/current", "Current model", ApiFamily.ANTHROPIC_MESSAGES, 200_000L)
            val store = ModelCatalogueSnapshotStore(context)
            val preferences = context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE)

            preferences.edit().putString(
                "snapshot",
                ModelCatalogueCodec.encode(
                    ModelCatalogueSnapshot(
                        2,
                        "before-retirement",
                        1_793_750_400_000L,
                        listOf(retired, unknown),
                        CatalogueOrigin.LIVE,
                    ),
                ),
            ).commit()
            assertEquals(null, preferences.getString("context_windows", null))
            withContext(Dispatchers.IO) {
                store.saveCatalogue(
                    ModelCatalogueSnapshot(
                        2,
                        "after-retirement",
                        1_793_750_500_000L,
                        listOf(current),
                        CatalogueOrigin.LIVE,
                    ),
                )
            }

            val restartedStore = ModelCatalogueSnapshotStore(context)
            assertEquals(listOf(current.apiId), restartedStore.loadCatalogue()?.models?.map(ChatModel::apiId))

            val persistedMetadata = preferences.getString("context_windows", null)
            assertTrue("known metadata must be stored separately", persistedMetadata != null)
            val rows = org.json.JSONObject(requireNotNull(persistedMetadata)).getJSONArray("models")
            val contextLimits = (0 until rows.length()).associate { index ->
                val row = rows.getJSONObject(index)
                row.getString("id") to row.getLong("contextWindowTokens")
            }
            assertEquals(32_000L, contextLimits[retired.apiId])
            assertFalse(contextLimits.containsKey(unknown.apiId))
            assertEquals(32_000L, restartedStore.loadKnownContextWindowMetadata()[retired.apiId])
            assertFalse(restartedStore.loadKnownContextWindowMetadata().containsKey(unknown.apiId))
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun failedCommitDoesNotExposeCandidateToANewRepository() = runTest {
        clearPreferences()
        try {
            val backingPreferences = context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE)
            val failingPreferences = CommitFailingPreferences(backingPreferences)
            val storeContext = SnapshotPreferencesContext(context, failingPreferences)
            val previous = catalogue("vendor/previous", "Previous model")
            val candidate = catalogue("vendor/candidate", "Candidate model")

            withContext(Dispatchers.IO) { ModelCatalogueSnapshotStore(storeContext).saveCatalogue(previous) }
            failingPreferences.failNextCommit = true
            val repository = ModelCatalogueRepository(storeContext, ModelCatalogueApi { candidate })

            val failure = runCatching { repository.refresh() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(candidate.models, ModelCatalogueCodec.decode(checkNotNull(failingPreferences.valueAtFailedCommit)).models)
            assertEquals(ModelCatalogueCodec.encode(previous), backingPreferences.getString("snapshot", null))
            assertEquals(
                mapOf(previous.models.single().apiId to 200_000L),
                ModelCatalogueSnapshotStore(storeContext).loadKnownContextWindowMetadata(),
            )

            val loadedByNewRepository = ModelCatalogueRepository(
                storeContext,
                ModelCatalogueApi { error("offline") },
            ).loadLocal()
            assertEquals(previous.models, loadedByNewRepository.models)
            assertEquals(CatalogueOrigin.CACHED, loadedByNewRepository.origin)
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun refreshReplacesWrongTypedCachedPreference() = runTest {
        clearPreferences()
        try {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("snapshot", 42)
                    .commit()
            }
            val candidate = catalogue("vendor/refreshed", "Refreshed model")
            val repository = ModelCatalogueRepository(context, ModelCatalogueApi { candidate })

            val refreshed = repository.refresh()
            val loadedByNewRepository = ModelCatalogueRepository(
                context,
                ModelCatalogueApi { error("offline") },
            ).loadLocal()

            assertEquals(candidate.models, refreshed.models)
            assertEquals(CatalogueOrigin.LIVE, refreshed.origin)
            assertEquals(candidate.models, loadedByNewRepository.models)
            assertEquals(CatalogueOrigin.CACHED, loadedByNewRepository.origin)
        } finally {
            clearPreferences()
        }
    }

    @Test
    fun ignoresCorruptStoredJson() = runTest {
        clearPreferences()
        try {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE)
                    .edit()
                    .putString("snapshot", "not-json")
                    .commit()
            }

            assertNull(withContext(Dispatchers.IO) { ModelCatalogueSnapshotStore(context).loadCatalogue() })
        } finally {
            clearPreferences()
        }
    }

    private suspend fun clearPreferences() {
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private fun catalogue(id: String, name: String) = ModelCatalogueSnapshot(
        2,
        "fixture-$id",
        1_793_750_400_000L,
        listOf(ChatModel(id, name, ApiFamily.ANTHROPIC_MESSAGES, 200_000L)),
    )

    private class SnapshotPreferencesContext(
        base: Context,
        private val snapshotPreferences: SharedPreferences,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "model_catalogue") snapshotPreferences else super.getSharedPreferences(name, mode)
    }

    private class CommitFailingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        private val values = mutableMapOf<String, String>().apply {
            delegate.getString("snapshot", null)?.let { put("snapshot", it) }
            delegate.getString("context_windows", null)?.let { put("context_windows", it) }
        }
        var failNextCommit = false
        var valueAtFailedCommit: String? = null
            private set

        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue

        override fun edit(): SharedPreferences.Editor = MemoryPublishingEditor(delegate.edit())

        private inner class MemoryPublishingEditor(
            private val delegateEditor: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by delegateEditor {
            private val changes = linkedMapOf<String, String?>()
            private var clearValues = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearValues = true
                return this
            }

            override fun commit(): Boolean {
                publishInMemory()
                if (failNextCommit) {
                    failNextCommit = false
                    valueAtFailedCommit = values["snapshot"]
                    return false
                }
                publishToDelegate()
                return delegateEditor.commit()
            }

            override fun apply() {
                publishInMemory()
                publishToDelegate()
                delegateEditor.apply()
            }

            private fun publishInMemory() {
                if (clearValues) values.clear()
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }

            private fun publishToDelegate() {
                if (clearValues) delegateEditor.clear()
                changes.forEach { (key, value) ->
                    if (value == null) delegateEditor.remove(key) else delegateEditor.putString(key, value)
                }
            }
        }
    }
}
