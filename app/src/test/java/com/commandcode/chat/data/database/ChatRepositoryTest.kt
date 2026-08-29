package com.commandcode.chat.data.database

import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {
    @Test
    fun beginTurnCreatesConversationAndOrderedPendingRows() = runTest {
        val repository = testRepository()

        val turn = repository.beginTurn(null, "  hello   world  ", ChatModel.SOL)

        assertEquals("hello world", repository.observeConversations().first().single().title)
        assertEquals(
            listOf("USER", "ASSISTANT"),
            repository.observeMessages(turn.conversationId).first().map { it.role },
        )
        assertEquals("PENDING", repository.observeMessages(turn.conversationId).first()[1].status)
    }

    private fun testRepository(): ChatRepository = error("repository test fixture is supplied by the implementation")
}
