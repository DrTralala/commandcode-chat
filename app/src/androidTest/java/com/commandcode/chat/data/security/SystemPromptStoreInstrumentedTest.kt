package com.commandcode.chat.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SystemPromptStoreInstrumentedTest {
    @Test fun roundTripRecreationAndClearDoNotStorePlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storageName = "test-prompt-secrets-$suffix"
        val alias = "commandcode-test-prompt-$suffix"
        val blobKey = "systemPrompt-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        val prompts = EncryptedSystemPromptStore(store, alias = alias, blobKey = blobKey)
        try {
            assertEquals("", prompts.read())
            prompts.save("Be kind.  Keep  formatting.")
            assertFalse(store.allRawValues().values.any { it.toString().contains("Be kind") })
            assertEquals("Be kind.  Keep  formatting.", prompts.read())
            // A reopened store over the same encrypted blob reads the same value.
            val reopened = EncryptedSystemPromptStore(store, alias = alias, blobKey = blobKey)
            assertEquals("Be kind.  Keep  formatting.", reopened.read())
            prompts.clear()
            assertNull(store.rawValue(blobKey))
            assertEquals("", prompts.read())
        } finally {
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    @Test fun ordinarySettingsNeverContainTheSystemPromptPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storageName = "test-prompt-secrets-$suffix"
        val alias = "commandcode-test-prompt-$suffix"
        val blobKey = "systemPrompt-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        val prompts = EncryptedSystemPromptStore(store, alias = alias, blobKey = blobKey)
        try {
            prompts.save("do not leak me")
            val ordinary = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).all
            assertFalse(ordinary.values.any { it.toString().contains("do not leak me") })
        } finally {
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    @Test fun corruptPromptBlobRequiresRecoveryWithoutReplacingAliasOrBlob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val storageName = "test-prompt-corrupt-$suffix"
        val alias = "commandcode-test-prompt-$suffix"
        val blobKey = "systemPrompt-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        try {
            store.put(blobKey, EncryptedBlob(1, "bad", "bad"))
            val prompts = EncryptedSystemPromptStore(store, alias = alias, blobKey = blobKey)
            assertThrows(PromptRecoveryRequired::class.java) { prompts.read() }
            assertNotNull(store.rawValue(blobKey))
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
