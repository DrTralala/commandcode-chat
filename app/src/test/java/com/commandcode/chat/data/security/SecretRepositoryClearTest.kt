package com.commandcode.chat.data.security

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecretRepositoryClearTest {
    @Test
    fun clearingApiKeyRemovesOnlyTheApiKeyBlob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storageName = "test-independent-clear-$suffix"
        val apiKey = "apiKey-$suffix"
        val systemPrompt = "systemPrompt-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        val apiBlob = EncryptedBlob(1, "api-nonce", "api-ciphertext")
        val promptBlob = EncryptedBlob(1, "prompt-nonce", "prompt-ciphertext")
        store.put(apiKey, apiBlob)
        store.put(systemPrompt, promptBlob)
        val repository = SecretRepository(store, alias = "unused-alias-$suffix", blobKey = apiKey)

        try {
            repository.clearApiKey()

            assertNull(store.rawValue(apiKey))
            assertEquals(promptBlob.encode(), store.rawValue(systemPrompt))
        } finally {
            context.deleteSharedPreferences(storageName)
        }
    }
}
