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
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private val databases = CopyOnWriteArrayList<ChatDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
    }
    @Test
    fun beginTurnCreatesConversationAndOrderedPendingRows() = runTest {
        val (repository, database) = testRepository()

        val turn = repository.beginTurn(null, "  hello   world  ", ChatModel.SOL)

        val conversation = repository.observeConversations().first().single()
        assertEquals(turn.conversationId, conversation.id)
        assertEquals("hello world", conversation.title)
        assertEquals(ChatModel.SOL, conversation.defaultModel)
        val rows = repository.observeMessages(turn.conversationId).first()
        assertEquals(
            listOf("USER", "ASSISTANT"),
            rows.map { it.role },
        )
        assertEquals(turn.userMessageId, rows[0].id)
        assertEquals("  hello   world  ", rows[0].content)
        assertEquals("COMPLETE", rows[0].status)
        assertEquals(turn.assistantMessageId, rows[1].id)
        assertEquals("", rows[1].content)
        assertEquals(ChatModel.SOL, rows[1].model)
        assertEquals("PENDING", rows[1].status)
        assertTrue(rows[1].createdAt > rows[0].createdAt)
    }

    @Test
    fun completeTurnPersistsUsageWithCalculatedCostsAndNullUsageDoesNotCreateEvent() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.LUNA)
        val before = database.conversations().find(turn.conversationId)!!.updatedAt
        repository.checkpointAssistant(turn.assistantMessageId, "streamed")
        assertEquals("STREAMING", database.messages().find(turn.assistantMessageId)?.status)
        assertEquals("streamed", database.messages().find(turn.assistantMessageId)?.content)
        repository.completeTurn(turn.assistantMessageId, "answer", TokenUsage(1_000_000, 200_000, 1_000_000))
        val event = database.usageEvents().observeForConversation(turn.conversationId).first().single()
        assertEquals(1_000_000L, event.inputTokens)
        assertEquals(200_000L, event.cachedInputTokens)
        assertEquals(1_000_000L, event.outputTokens)
        assertEquals("1.364", event.estimatedModelCost)
        assertEquals("4.774", event.estimatedGoatCredits)
        assertEquals(turn.assistantMessageId, event.requestId)
        assertEquals(ChatModel.LUNA.apiId, event.modelId)
        assertEquals("answer", database.messages().find(turn.assistantMessageId)?.content)
        assertEquals("COMPLETE", database.messages().find(turn.assistantMessageId)?.status)
        assertTrue(database.conversations().find(turn.conversationId)!!.updatedAt > before)
        repository.completeTurn(turn.assistantMessageId, "duplicate", TokenUsage(9, 0, 9))
        assertEquals(1, database.usageEvents().observeForConversation(turn.conversationId).first().size)
        repository.checkpointAssistant(turn.assistantMessageId, "stale")
        repository.interruptTurn(turn.assistantMessageId, "stale", "stale")
        assertEquals("answer", database.messages().find(turn.assistantMessageId)?.content)

        val turnWithoutUsage = repository.beginTurn(turn.conversationId, "next", ChatModel.LUNA)
        repository.completeTurn(turnWithoutUsage.assistantMessageId, "done", null)
        assertEquals(1, database.usageEvents().observeForConversation(turn.conversationId).first().size)
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
        repository.completeTurn(turn.assistantMessageId, "late", TokenUsage(1, 0, 1))
        assertEquals("partial", database.messages().find(turn.assistantMessageId)?.content)
        assertEquals(0, database.usageEvents().observeForConversation(turn.conversationId).first().size)
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
        val next = repository.beginTurn(turn.conversationId, "next\u00a0turn\u2003now", ChatModel.SOL)
        val rows = repository.observeMessages(turn.conversationId).first()
        assertEquals(listOf(turn.userMessageId, turn.assistantMessageId, next.userMessageId, next.assistantMessageId), rows.map { it.id })
        assertTrue(rows.zipWithNext().all { (a, b) -> b.createdAt > a.createdAt })
        assertTrue(database.conversations().find(turn.conversationId)!!.updatedAt > conversation.updatedAt)
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
    }

    @Test
    fun seededEqualTimestampsUseIdTieBreakers() = runTest {
        val (_, database) = testRepository()
        database.conversations().insert(ConversationEntity("b", "B", ChatModel.SOL.apiId, 1, 10))
        database.conversations().insert(ConversationEntity("a", "A", ChatModel.SOL.apiId, 1, 10))
        assertEquals(listOf("a", "b"), database.conversations().observeAll().first().map { it.id })
        database.messages().insert(MessageEntity("b", "b", "USER", "b", null, 5, "COMPLETE"))
        database.messages().insert(MessageEntity("a", "b", "USER", "a", null, 5, "COMPLETE"))
        assertEquals(listOf("a", "b"), database.messages().observeForConversation("b").first().map { it.id })
    }

    @Test
    fun timestampOverflowFailsAtomically() = runTest {
        val (repository, database) = testRepository()
        database.conversations().insert(ConversationEntity("boundary", "boundary", ChatModel.SOL.apiId, Long.MAX_VALUE, Long.MAX_VALUE))
        database.messages().insert(MessageEntity("existing", "boundary", "USER", "existing", null, Long.MAX_VALUE, "COMPLETE"))
        assertTrue(runCatching { repository.beginTurn("boundary", "overflow", ChatModel.SOL) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, database.messages().observeForConversation("boundary").first().size)
        assertNull(database.messages().find("overflow"))
    }

    private fun testRepository(): Pair<ChatRepository, ChatDatabase> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        return ChatRepository(database) to database
    }
}
