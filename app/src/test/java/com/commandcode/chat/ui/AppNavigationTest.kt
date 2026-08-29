package com.commandcode.chat.ui

import org.junit.Assert.assertEquals
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
}
