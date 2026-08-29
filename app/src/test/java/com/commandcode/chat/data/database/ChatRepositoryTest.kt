package com.commandcode.chat.data.database

import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    @Test
    fun beginTurnCreatesConversationAndOrderedPendingRows() = runTest {
        val (repository, database) = testRepository()

        val turn = repository.beginTurn(null, "  hello   world  ", ChatModel.SOL)

        assertEquals("hello world", repository.observeConversations().first().single().title)
        assertEquals(
            listOf("USER", "ASSISTANT"),
            repository.observeMessages(turn.conversationId).first().map { it.role },
        )
        assertEquals("PENDING", repository.observeMessages(turn.conversationId).first()[1].status)
        database.close()
    }

    @Test
    fun completeTurnPersistsUsageWithCalculatedCostsAndNullUsageDoesNotCreateEvent() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.LUNA)
        repository.completeTurn(turn.assistantMessageId, "answer", TokenUsage(1_000_000, 200_000, 1_000_000))
        val event = database.usageEvents().observeForConversation(turn.conversationId).first().single()
        assertEquals(1_000_000L, event.inputTokens)
        assertEquals(200_000L, event.cachedInputTokens)
        assertEquals(1_000_000L, event.outputTokens)
        assertEquals("1.364", event.estimatedModelCost)
        assertEquals("4.774", event.estimatedGoatCredits)
        assertEquals("COMPLETE", database.messages().find(turn.assistantMessageId)?.status)

        val turnWithoutUsage = repository.beginTurn(turn.conversationId, "next", ChatModel.LUNA)
        repository.completeTurn(turnWithoutUsage.assistantMessageId, "done", null)
        assertEquals(1, database.usageEvents().observeForConversation(turn.conversationId).first().size)
        database.close()
    }

    @Test
    fun interruptRetainsPartialContentAndUpdatesConversationTimestamp() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.SOL)
        val before = database.conversations().find(turn.conversationId)!!.updatedAt
        repository.interruptTurn(turn.assistantMessageId, "partial", "network failure")
        val assistant = repository.observeMessages(turn.conversationId).first()[1]
        assertEquals("INTERRUPTED", assistant.status)
        assertEquals("partial", assistant.content)
        assertTrue(database.conversations().find(turn.conversationId)!!.updatedAt > before)
        database.close()
    }

    @Test
    fun titleTruncatesTo48UnicodeCodePointsAndOrderingIsStable() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "  ${"😀".repeat(60)}  ", ChatModel.SOL)
        val conversation = repository.observeConversations().first().single()
        assertEquals(48, conversation.title.codePoints().count())
        assertEquals(48, conversation.title.length / 2)
        val first = repository.observeMessages(turn.conversationId).first().map { it.id }
        val second = repository.observeMessages(turn.conversationId).first().map { it.id }
        assertEquals(first, second)
        database.close()
    }

    @Test
    fun deletingConversationCascadesMessagesAndUsage() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.SOL)
        repository.completeTurn(turn.assistantMessageId, "answer", TokenUsage(1, 0, 1))
        database.conversations().delete(database.conversations().find(turn.conversationId)!!)
        assertTrue(database.messages().find(turn.userMessageId) == null)
        assertTrue(database.messages().find(turn.assistantMessageId) == null)
        assertTrue(database.usageEvents().observeForConversation(turn.conversationId).first().isEmpty())
        database.close()
    }

    private fun testRepository(): Pair<ChatRepository, ChatDatabase> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return ChatRepository(database) to database
    }
}
