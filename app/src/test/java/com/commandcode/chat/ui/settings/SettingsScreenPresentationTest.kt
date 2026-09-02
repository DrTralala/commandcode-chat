package com.commandcode.chat.ui.settings

import com.commandcode.chat.ui.ZdrOnColour
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenPresentationTest {
    @Test
    fun configuredApiKeyStatusColoursOnlyConfiguredGreen() {
        val status = apiKeyConfiguredStatus()

        assertEquals("API key: configured", status.text)
        val greenSpan = status.spanStyles.single()
        assertEquals(9, greenSpan.start)
        assertEquals(19, greenSpan.end)
        assertEquals(ZdrOnColour, greenSpan.item.color)
    }
}
