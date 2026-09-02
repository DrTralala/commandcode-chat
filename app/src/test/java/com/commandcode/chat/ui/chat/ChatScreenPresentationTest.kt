package com.commandcode.chat.ui.chat

import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.ui.AmoledAppColours
import com.commandcode.chat.ui.AppUiState
import com.commandcode.chat.ui.StandardAppColours
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenPresentationTest {
    @Test
    fun userAndAssistantMessagesUseOppositeSides() {
        assertEquals(MessageSide.END, messageSide("USER"))
        assertEquals(MessageSide.START, messageSide("ASSISTANT"))
    }

    @Test
    fun userUsesGreenContainerAndAssistantUsesBlueContainerInBothThemes() {
        listOf(StandardAppColours, AmoledAppColours).forEach { colours ->
            assertEquals(colours.secondaryContainer, messageContainerColour("USER", colours))
            assertEquals(colours.onSecondaryContainer, messageContentColour("USER", colours))
            assertEquals(colours.primaryContainer, messageContainerColour("ASSISTANT", colours))
            assertEquals(colours.onPrimaryContainer, messageContentColour("ASSISTANT", colours))
        }
    }

    @Test
    fun modelOptionTagSanitisesEveryNonLetterOrDigitAndPreservesCase() {
        assertEquals(
            "model_option_Qwen_Qwen3_8_Flash",
            modelOptionTestTag("Qwen/Qwen3.8-Flash"),
        )
    }

    @Test
    fun headingUsesNewChatUntilTheSelectedConversationIsAvailable() {
        assertEquals("New Chat", chatHeading(AppUiState(currentConversationId = null)))
        assertEquals("New Chat", chatHeading(AppUiState(currentConversationId = "pending")))
        assertEquals(
            "Saved conversation",
            chatHeading(
                AppUiState(
                    currentConversationId = "conversation",
                    conversations = listOf(
                        Conversation(
                            id = "conversation",
                            title = "Saved conversation",
                            defaultModelId = "gpt-5.6-sol",
                            createdAt = 1L,
                            updatedAt = 2L,
                        ),
                    ),
                ),
            ),
        )
    }
}
