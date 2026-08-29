package com.commandcode.chat.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretRepositoryTest {
    @Test fun roundTripAndClearDoesNotStorePlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedBlobStore(context, "test-secrets")
        val repository = SecretRepository(store)
        repository.clearApiKey()
        repository.saveApiKey("fake-test-key".toCharArray())
        assertFalse(store.allRawValues().values.any { it.toString().contains("fake-test-key") })
        assertArrayEquals("fake-test-key".toCharArray(), repository.readApiKey())
        repository.clearApiKey()
        assertNull(store.rawValue("apiKey"))
    }

    @Test fun corruptBlobRequiresRecoveryAndIsRetained() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedBlobStore(context, "test-corrupt")
        store.put("apiKey", EncryptedBlob(1, "bad", "bad"))
        val repository = SecretRepository(store)
        assertThrows(KeyRecoveryRequired::class.java) { repository.readApiKey() }
        assertNotNull(store.rawValue("apiKey"))
    }
}
