package com.commandcode.chat.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecretRepositoryTest {
    @Test fun roundTripAndClearDoesNotStorePlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storageName = "test-secrets-$suffix"
        val alias = "commandcode-test-api-$suffix"
        val blobKey = "apiKey-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        val repository = SecretRepository(store, alias = alias, blobKey = blobKey)
        try {
            repository.saveApiKey("fake-test-key".toCharArray())
            assertFalse(store.allRawValues().values.any { it.toString().contains("fake-test-key") })
            assertArrayEquals("fake-test-key".toCharArray(), repository.readApiKey())
            repository.clearApiKey()
            assertNull(store.rawValue(blobKey))
        } finally {
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    @Test fun corruptBlobRequiresRecoveryAndIsRetained() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storageName = "test-corrupt-$suffix"
        val alias = "commandcode-test-api-$suffix"
        val blobKey = "apiKey-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        try {
            store.put(blobKey, EncryptedBlob(1, "bad", "bad"))
            val repository = SecretRepository(store, alias = alias, blobKey = blobKey)
            assertThrows(KeyRecoveryRequired::class.java) { repository.readApiKey() }
            assertNotNull(store.rawValue(blobKey))
            assertFalse(hasAlias(alias))
        } finally {
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    @Test fun deletingActualAliasAfterSaveRequiresRecoveryWithoutReplacingAliasOrBlob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storageName = "test-deleted-alias-$suffix"
        val alias = "commandcode-test-api-$suffix"
        val blobKey = "apiKey-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        val repository = SecretRepository(store, alias = alias, blobKey = blobKey)
        try {
            repository.saveApiKey("fake-test-key".toCharArray())
            val blobBefore = store.rawValue(blobKey)
            assertTrue(hasAlias(alias))
            deleteAlias(alias)

            assertThrows(KeyRecoveryRequired::class.java) { repository.readApiKey() }

            assertEquals(blobBefore, store.rawValue(blobKey))
            assertFalse(hasAlias(alias))
        } finally {
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    private fun hasAlias(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    private fun deleteAlias(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }
}
