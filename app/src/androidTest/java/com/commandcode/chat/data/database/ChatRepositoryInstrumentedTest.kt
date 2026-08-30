package com.commandcode.chat.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ChatRepositoryInstrumentedTest {
    private val databases = CopyOnWriteArrayList<ChatDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
    }

    @Test
    fun beginTurnCreatesConversationPendingRowsAndIncompleteUsageAnchor() = runTest {
        val startedAt = 1_777_777L
        val (repository, database) = testRepository(nowMillis = { startedAt })

        val turn = repository.beginTurn(null, "  hello   world  ", ChatModel.SOL)

        val conversation = repository.observeConversations().first().single()
        assertEquals(turn.conversationId, conversation.id)
        assertEquals("hello world", conversation.title)
        assertEquals(ChatModel.SOL.apiId, conversation.defaultModelId)
        val rows = repository.observeMessages(turn.conversationId).first()
        assertEquals(listOf("USER", "ASSISTANT"), rows.map { it.role })
        assertEquals(turn.userMessageId, rows[0].id)
        assertEquals("  hello   world  ", rows[0].content)
        assertEquals("COMPLETE", rows[0].status)
        assertEquals(turn.assistantMessageId, rows[1].id)
        assertEquals("", rows[1].content)
        assertEquals(ChatModel.SOL.apiId, rows[1].modelId)
        assertEquals("PENDING", rows[1].status)
        assertTrue(rows[1].createdAt > rows[0].createdAt)
        val event = database.usageEvents().observeForConversation(turn.conversationId).first().single()
        assertEquals(turn.assistantMessageId, event.requestId)
        assertEquals(turn.conversationId, event.conversationId)
        assertEquals(ChatModel.SOL.apiId, event.modelId)
        assertEquals(startedAt, event.timestamp)
        assertNull(event.inputTokens)
        assertNull(event.cachedInputTokens)
        assertNull(event.outputTokens)
        assertNull(event.estimatedModelCost)
        assertNull(event.estimatedGoatCredits)
        assertTrue(!event.usageComplete)
    }

    @Test
    fun completeTurnPreservesDetailedRawUsageWithoutLocalEstimates() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.LUNA)
        val before = database.conversations().find(turn.conversationId)!!.updatedAt
        repository.checkpointAssistant(turn.assistantMessageId, "streamed")
        assertEquals("STREAMING", database.messages().find(turn.assistantMessageId)?.status)
        assertEquals("streamed", database.messages().find(turn.assistantMessageId)?.content)
        repository.completeTurn(turn.assistantMessageId, "answer", TokenUsage(1_000_000, 200_000, 1_000_000))
        assertTrue(repository.isTurnComplete(turn.assistantMessageId))
        val event = database.usageEvents().observeForConversation(turn.conversationId).first().single()
        assertEquals(1_000_000L, event.inputTokens)
        assertEquals(200_000L, event.cachedInputTokens)
        assertEquals(1_000_000L, event.outputTokens)
        assertNull(event.estimatedModelCost)
        assertNull(event.estimatedGoatCredits)
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
        assertTrue(event.usageComplete)
    }

    @Test
    fun doneWithoutUsageCompletesMessageAndAnchorWithUnknownCosts() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.SOL)

        repository.completeTurn(turn.assistantMessageId, "done", null)

        val assistant = database.messages().find(turn.assistantMessageId)!!
        val event = database.usageEvents().observeForConversation(turn.conversationId).first().single()
        assertEquals("COMPLETE", assistant.status)
        assertEquals("done", assistant.content)
        assertTrue(event.usageComplete)
        assertNull(event.inputTokens)
        assertNull(event.cachedInputTokens)
        assertNull(event.outputTokens)
        assertNull(event.estimatedModelCost)
        assertNull(event.estimatedGoatCredits)
    }

    @Test
    fun partialUsageRetainsRawValuesButLeavesCostsUnknown() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.LUNA)

        repository.completeTurn(turn.assistantMessageId, "done", TokenUsage(11, null, 7))

        val event = database.usageEvents().observeForConversation(turn.conversationId).first().single()
        assertTrue(event.usageComplete)
        assertEquals(11L, event.inputTokens)
        assertNull(event.cachedInputTokens)
        assertEquals(7L, event.outputTokens)
        assertNull(event.estimatedModelCost)
        assertNull(event.estimatedGoatCredits)
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
        assertTrue(!repository.isTurnComplete(turn.assistantMessageId))
        val event = database.usageEvents().observeForConversation(turn.conversationId).first().single()
        assertTrue(!event.usageComplete)
        assertNull(event.estimatedModelCost)
        assertNull(event.estimatedGoatCredits)
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
        assertEquals(
            listOf(turn.userMessageId, turn.assistantMessageId, next.userMessageId, next.assistantMessageId),
            rows.map { it.id },
        )
        assertTrue(rows.zipWithNext().all { (a, b) -> b.createdAt > a.createdAt })
        assertTrue(database.conversations().find(turn.conversationId)!!.updatedAt > conversation.updatedAt)
    }

    @Test
    fun deletingSettledConversationCascadesInterruptedMessagesAndUsage() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "hello", ChatModel.SOL)
        repository.interruptTurn(turn.assistantMessageId, "partial", "cancelled before delete")
        repository.deleteConversation(turn.conversationId)
        assertTrue(database.messages().find(turn.userMessageId) == null)
        assertTrue(database.messages().find(turn.assistantMessageId) == null)
        assertTrue(database.usageEvents().observeForConversation(turn.conversationId).first().isEmpty())
    }

    @Test
    fun messagesSnapshotImmediatelyReflectsCommittedAssistantWithoutFlowCollection() = runTest {
        val (repository, _) = testRepository()
        val turn = repository.beginTurn(null, "first", ChatModel.SOL)
        repository.completeTurn(turn.assistantMessageId, "first answer", TokenUsage(1, 0, 1))
        val second = repository.beginTurn(turn.conversationId, "second", ChatModel.SOL)

        val snapshot = repository.messagesSnapshot(turn.conversationId)

        assertEquals(listOf("first", "first answer", "second", ""), snapshot.map { it.content })
        assertEquals(second.assistantMessageId, snapshot.last().id)
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
        database.conversations().insert(
            ConversationEntity("boundary", "boundary", ChatModel.SOL.apiId, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        database.messages().insert(
            MessageEntity("existing", "boundary", "USER", "existing", null, Long.MAX_VALUE, "COMPLETE"),
        )
        assertTrue(
            runCatching { repository.beginTurn("boundary", "overflow", ChatModel.SOL) }.exceptionOrNull() is
                IllegalStateException,
        )
        assertEquals(1, database.messages().observeForConversation("boundary").first().size)
        assertTrue(database.usageEvents().observeForConversation("boundary").first().isEmpty())
    }

    @Test
    fun startupReconciliationInterruptsPendingAndStreamingTogetherWithoutLosingText() = runTest {
        val (repository, database) = testRepository()
        val pending = repository.beginTurn(null, "pending", ChatModel.SOL)
        val streaming = repository.beginTurn(pending.conversationId, "streaming", ChatModel.LUNA)
        repository.checkpointAssistant(streaming.assistantMessageId, "checkpoint text")

        database.reconcileUnfinishedTurnsForStartup()

        assertEquals("INTERRUPTED", database.messages().find(pending.assistantMessageId)?.status)
        assertEquals("", database.messages().find(pending.assistantMessageId)?.content)
        assertEquals("INTERRUPTED", database.messages().find(streaming.assistantMessageId)?.status)
        assertEquals("checkpoint text", database.messages().find(streaming.assistantMessageId)?.content)
        assertTrue(database.usageEvents().observeForConversation(pending.conversationId).first().all { !it.usageComplete })
    }

    private fun testRepository(nowMillis: () -> Long = System::currentTimeMillis): Pair<ChatRepository, ChatDatabase> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        return ChatRepository(database, nowMillis) to database
    }
}
