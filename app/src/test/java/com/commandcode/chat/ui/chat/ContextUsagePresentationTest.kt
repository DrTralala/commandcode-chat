package com.commandcode.chat.ui.chat

import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.service.ContextWindowCatalogue
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ConversationTokenUsage
import com.commandcode.chat.domain.ReportedUsage
import com.commandcode.chat.ui.AppUiState
import org.junit.Assert.*
import org.junit.Test

class ContextUsagePresentationTest {
    private fun state(tokens: Long = 250) = AppUiState(
        currentConversationId = "chat",
        tokenUsage = ConversationTokenUsage.aggregate(listOf(
            ReportedUsage("event", "reply", ChatModel.SOL.apiId, 1, tokens, 0, 0, true),
        )),
        contextWindows = ContextWindowCatalogue(mapOf(ChatModel.SOL.apiId to 1000, ChatModel.LUNA.apiId to 2000)),
    )

    @Test fun modelSwitchKeepsMeasuredPercentageUntilNewModelUsage() {
        val switched = state().copy(selectedModel = ChatModel.LUNA)
        assertEquals("Context ≈25%", switched.contextUsage.label)
        assertTrue(switched.contextUsage.details.contains("selected model"))
        val next = switched.copy(tokenUsage = ConversationTokenUsage.aggregate(listOf(
            ReportedUsage("next", "reply2", ChatModel.LUNA.apiId, 2, 900, 100, 100, true),
        )))
        assertEquals("Context ≈50%", next.contextUsage.label)
        assertFalse(next.contextUsage.details.contains("selected model"))
    }

    @Test fun unknownMissingCapacityZeroTinyAndOverLimitAreDistinct() {
        assertEquals("Context —", AppUiState().contextUsage.label)
        assertEquals("Context unavailable", state().copy(contextWindows = ContextWindowCatalogue()).contextUsage.label)
        assertEquals("Context ≈0%", state(0).contextUsage.label)
        assertEquals("Context <1%", state(1).contextUsage.label)
        assertEquals("Context ≈125%", state(1250).contextUsage.label)
        assertEquals("Context ≈922337203685477581%", state(Long.MAX_VALUE).contextUsage.label)
        assertEquals("Context <1%", state(1).copy(
            contextWindows = ContextWindowCatalogue(mapOf(ChatModel.SOL.apiId to Long.MAX_VALUE)),
        ).contextUsage.label)
    }

    @Test fun streamingDraftsAndUnmeasuredAttemptsRetainLastPercentageWithCaveats() {
        val original = state()
        val current = original.copy(
            sending = true, activeConversationId = "chat", draftText = "new draft",
            tokenUsage = original.tokenUsage.copy(hasUnmeasuredAttempt = true),
        ).contextUsage
        assertEquals("Context ≈25%", current.label)
        assertTrue(current.details.contains("streaming"))
        assertTrue(current.details.contains("Drafts"))
        assertTrue(current.details.contains("unmeasured"))
        assertFalse(current.details.contains("250"))
        assertFalse(current.details.contains("1000"))
    }

    @Test fun historicalRetryWarnsThatLaterMessagesAreNotCovered() {
        val current = state().copy(messages = listOf(
            Message("reply", "chat", "ASSISTANT", "old retry", ChatModel.SOL.apiId, 1, "COMPLETE"),
            Message("later", "chat", "USER", "later question", null, 2, "COMPLETE"),
        )).contextUsage
        assertTrue(current.details.contains("earlier part"))
        assertEquals("Context ≈25%", current.label)
    }
}
