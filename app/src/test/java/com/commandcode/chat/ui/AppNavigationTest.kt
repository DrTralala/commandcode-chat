package com.commandcode.chat.ui

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationTest {
    @Test
    fun freshConfiguredStateRoutesToChat() {
        assertEquals("chat", initialRoute(keyConfigured = true))
    }

    @Test
    fun freshUnconfiguredStateRoutesToSettings() {
        assertEquals("settings", initialRoute(keyConfigured = false))
    }

    @Test
    fun savingFirstKeyNavigatesToChat() {
        assertEquals("chat", navigationTarget(previouslyConfigured = false, keyConfigured = true))
    }

    @Test
    fun restoredConfiguredDestinationIsNotOverridden() {
        assertNull(navigationTarget(previouslyConfigured = true, keyConfigured = true))
    }

    @Test
    fun historicalUnknownModelUsesTheExactPersistedProviderId() {
        val models = listOf(
            ChatModel("active/model", "Active model", ApiFamily.OPENAI_CHAT),
        )

        assertEquals("retired/provider-model", modelDisplayName(models, "retired/provider-model"))
        assertEquals("Active model", modelDisplayName(models, "active/model"))
    }
}
