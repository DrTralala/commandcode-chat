package com.commandcode.chat.ui.settings

import com.commandcode.chat.data.security.effectiveSystemPrompt
import com.commandcode.chat.ui.SYSTEM_PROMPT_UNAVAILABLE_MESSAGE
import com.commandcode.chat.ui.SystemPromptFeedback
import com.commandcode.chat.ui.ZdrOnColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun effectiveSystemPromptNormalisesBlankToEmptyAndPreservesMeaningfulFormatting() {
        assertEquals("", effectiveSystemPrompt(""))
        assertEquals("", effectiveSystemPrompt("   \n\t "))
        assertEquals("be kind", effectiveSystemPrompt("be kind"))
        assertEquals("  spaced   intentionally  ", effectiveSystemPrompt("  spaced   intentionally  "))
    }

    @Test
    fun systemPromptFeedbackCopyMapsEveryStateToGenericTextWithoutPromptContent() {
        assertEquals("System prompt saved. It will apply to the next message sent in any chat.",
            systemPromptFeedbackText(SystemPromptFeedback.SAVED))
        assertEquals("System prompt cleared. Empty instructions will apply to the next message sent in any chat.",
            systemPromptFeedbackText(SystemPromptFeedback.CLEARED))
        assertEquals("System prompt could not be saved. Try again.",
            systemPromptFeedbackText(SystemPromptFeedback.SAVE_FAILED))
        assertEquals("System prompt could not be cleared. Try again.",
            systemPromptFeedbackText(SystemPromptFeedback.CLEAR_FAILED))
        assertEquals(
            "Saved system prompt could not be opened. Sending is disabled until a new prompt is saved or cleared.",
            systemPromptFeedbackText(SystemPromptFeedback.LOAD_FAILED),
        )
        assertFalse(systemPromptFeedbackText(SystemPromptFeedback.SAVE_FAILED).contains("secret"))
    }

    @Test
    fun promptLoadFailureFeedbackSharesTheBlockingBannerCopy() {
        // Single source of truth: Settings feedback and the ViewModel blocking
        // banner must render the identical sentence for an unreadable prompt.
        assertEquals(
            SYSTEM_PROMPT_UNAVAILABLE_MESSAGE,
            systemPromptFeedbackText(SystemPromptFeedback.LOAD_FAILED),
        )
    }
}
