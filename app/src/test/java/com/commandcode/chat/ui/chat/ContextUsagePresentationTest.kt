package com.commandcode.chat.ui.chat

import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.service.ContextWindowCatalogue
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ConversationTokenUsage
import com.commandcode.chat.domain.ReportedUsage
import com.commandcode.chat.ui.AppUiState
import com.commandcode.chat.ui.AmoledAppColours
import androidx.compose.ui.graphics.Color
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
        assertEquals("25%", switched.contextUsage.label)
        val next = switched.copy(tokenUsage = ConversationTokenUsage.aggregate(listOf(
            ReportedUsage("next", "reply2", ChatModel.LUNA.apiId, 2, 900, 100, 100, true),
        )))
        assertEquals("50%", next.contextUsage.label)
    }

    @Test fun unknownIsADashAndKnownUsageRoundsWithoutPrefixesOrClamping() {
        assertEquals("—", AppUiState().contextUsage.label)
        assertEquals("—", state().copy(contextWindows = ContextWindowCatalogue()).contextUsage.label)
        assertEquals("0%", state(0).contextUsage.label)
        assertEquals("0%", state(1).contextUsage.label)
        assertEquals("0%", state(4).contextUsage.label)
        assertEquals("1%", state(5).contextUsage.label)
        assertEquals("1%", state(9).contextUsage.label)
        assertEquals("1%", state(14).contextUsage.label)
        assertEquals("2%", state(15).contextUsage.label)
        assertEquals("125%", state(1250).contextUsage.label)
        assertEquals("922337203685477581%", state(Long.MAX_VALUE).contextUsage.label)
        assertEquals("0%", state(1).copy(
            contextWindows = ContextWindowCatalogue(mapOf(ChatModel.SOL.apiId to Long.MAX_VALUE)),
        ).contextUsage.label)
    }

    @Test fun streamingDraftsAndUnmeasuredAttemptsRetainLastPercentage() {
        val original = state()
        val current = original.copy(
            sending = true, activeConversationId = "chat", draftText = "new draft",
            tokenUsage = original.tokenUsage.copy(hasUnmeasuredAttempt = true),
        ).contextUsage
        assertEquals("25%", current.label)
    }

    @Test fun laterMessagesDoNotInventANewMeasurement() {
        val current = state().copy(messages = listOf(
            Message("reply", "chat", "ASSISTANT", "old retry", ChatModel.SOL.apiId, 1, "COMPLETE"),
            Message("later", "chat", "USER", "later question", null, 2, "COMPLETE"),
        )).contextUsage
        assertEquals("25%", current.label)
    }

    @Test fun colourBandsFollowTheRoundedDisplayedPercentage() {
        for (tokens in listOf(0L, 1L, 490L, 494L)) {
            assertEquals("tokens=$tokens", ContextUsageLevel.LOW, state(tokens).contextUsage.level)
        }
        for (tokens in listOf(495L, 500L, 790L, 794L)) {
            assertEquals("tokens=$tokens", ContextUsageLevel.MEDIUM, state(tokens).contextUsage.level)
        }
        for (tokens in listOf(795L, 800L, 1000L, 1250L, Long.MAX_VALUE)) {
            assertEquals("tokens=$tokens", ContextUsageLevel.HIGH, state(tokens).contextUsage.level)
        }
        assertEquals("49%", state(494).contextUsage.label)
        assertEquals("50%", state(495).contextUsage.label)
        assertEquals("79%", state(794).contextUsage.label)
        assertEquals("80%", state(795).contextUsage.label)
        assertEquals(ContextUsageLevel.UNKNOWN, AppUiState().contextUsage.level)
        assertEquals(ContextUsageLevel.UNKNOWN, state().copy(contextWindows = ContextWindowCatalogue()).contextUsage.level)
    }

    @Test fun indicatorUsesReadableBlueOrangeRedAndNeutralForUnknown() {
        assertEquals(Color(0xFF60A5FA), contextUsageColour(ContextUsageLevel.LOW, AmoledAppColours))
        assertEquals(Color(0xFFFB923C), contextUsageColour(ContextUsageLevel.MEDIUM, AmoledAppColours))
        assertEquals(Color(0xFFF87171), contextUsageColour(ContextUsageLevel.HIGH, AmoledAppColours))
        assertEquals(AmoledAppColours.onSurfaceVariant, contextUsageColour(ContextUsageLevel.UNKNOWN, AmoledAppColours))
    }
}
