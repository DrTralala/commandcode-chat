package com.commandcode.chat.data.database

import android.app.Application
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
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
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatRepositoryTest {
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
        assertEquals(
            listOf("USER", "ASSISTANT"),
            rows.map { it.role },
        )
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
        assertEquals(listOf(turn.userMessageId, turn.assistantMessageId, next.userMessageId, next.assistantMessageId), rows.map { it.id })
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

        assertEquals(
            listOf("first", "first answer", "second", ""),
            snapshot.map { it.content },
        )
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
        database.conversations().insert(ConversationEntity("boundary", "boundary", ChatModel.SOL.apiId, Long.MAX_VALUE, Long.MAX_VALUE))
        database.messages().insert(MessageEntity("existing", "boundary", "USER", "existing", null, Long.MAX_VALUE, "COMPLETE"))
        assertTrue(runCatching { repository.beginTurn("boundary", "overflow", ChatModel.SOL) }.exceptionOrNull() is IllegalStateException)
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

    @Test
    fun beginTurnWithImageStoresMetadataAndReactiveSizesExactly() = runTest {
        val (repository, database) = testRepository()
        val image = ImageAttachment("image-1", "Image.png", "image/png", 100, 133, 2, 2)

        val turn = repository.beginTurn(null, "é", ChatModel.SOL, listOf(image))

        val user = repository.messagesSnapshot(turn.conversationId).first { it.role == "USER" }
        assertEquals(listOf(image), user.attachments)
        val conversation = repository.observeConversations().first().single()
        assertEquals(2L, conversation.textBytes)
        assertEquals(133L, conversation.attachmentBytes)
        assertEquals(1, conversation.attachmentCount)
        assertEquals(135L, conversation.totalBytes)
        assertEquals(image.id, database.attachments().listForConversation(turn.conversationId).single().id)
    }

    @Test
    fun imageOnlyTurnUsesImageChatTitleAndBlankTextWithoutImagesIsRejected() = runTest {
        val (repository, database) = testRepository()
        val image = ImageAttachment("image-only", "Image.png", "image/png", 10, 43, 1, 1)

        val turn = repository.beginTurn(null, "   ", ChatModel.SOL, listOf(image))

        val conversation = repository.observeConversations().first().single()
        assertEquals("Image chat", conversation.title)
        val user = repository.messagesSnapshot(turn.conversationId).first { it.role == "USER" }
        assertEquals(listOf(image), user.attachments)
        assertTrue(user.content.isBlank())

        assertTrue(
            runCatching { repository.beginTurn(null, "   ", ChatModel.SOL) }.exceptionOrNull() is
                IllegalArgumentException,
        )
        assertEquals(1, database.conversations().observeAll().first().size)
    }

    @Test
    fun twentyImageSlotsSpanTurnsAndTheTwentyFirstIsRejectedAtomically() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL, (1..12).map { image(it.toString()) })
        assertEquals(12, repository.observeConversations().first().single().attachmentCount)

        val second = repository.beginTurn(first.conversationId, "second", ChatModel.SOL, (13..20).map { image(it.toString()) })
        assertEquals(second.conversationId, first.conversationId)
        val conversation = repository.observeConversations().first().single()
        assertEquals(20, conversation.attachmentCount)
        assertEquals(20L * 133L, conversation.attachmentBytes)

        val failure = runCatching {
            repository.beginTurn(first.conversationId, "overflow", ChatModel.SOL, listOf(image("21")))
        }.exceptionOrNull()
        assertTrue(failure is AttachmentException && failure.kind == AttachmentFailure.TOO_MANY)
        assertEquals(20, repository.observeConversations().first().single().attachmentCount)
        assertEquals(4, repository.observeMessages(first.conversationId).first().size)
        assertEquals(2, database.usageEvents().observeForConversation(first.conversationId).first().size)
    }

    @Test
    fun duplicateAttachmentIdsRollBackUserAssistantAndUsage() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "first", ChatModel.SOL, listOf(image("a-1")))
        val duplicate = image("duplicate")

        val error = runCatching {
            repository.beginTurn(turn.conversationId, "second", ChatModel.SOL, listOf(duplicate, duplicate))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(2, repository.observeMessages(turn.conversationId).first().size)
        assertEquals(1, database.attachments().countForConversation(turn.conversationId))
        assertEquals(1, database.usageEvents().observeForConversation(turn.conversationId).first().size)
    }

    @Test
    fun reusingAnOwnedAttachmentIdRollsBackTheWholeTurn() = runTest {
        val (repository, database) = testRepository()
        val owned = image("owned-1")
        val first = repository.beginTurn(null, "first", ChatModel.SOL, listOf(owned))

        val error = runCatching {
            repository.beginTurn(first.conversationId, "second", ChatModel.SOL, listOf(image("owned-1")))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(2, repository.observeMessages(first.conversationId).first().size)
        assertEquals(1, database.attachments().countForConversation(first.conversationId))
        assertEquals(1, repository.observeConversations().first().single().attachmentCount)
    }

    @Test
    fun unsupportedModelOrMimeTypeFailsBeforeAnyRowIsPersisted() = runTest {
        val (repository, database) = testRepository()
        val unknownModel = ChatModel("example/unknown", "Unknown", ChatModel.SOL.apiFamily)

        val modelError = runCatching {
            repository.beginTurn(null, "x", unknownModel, listOf(image("model-reject")))
        }.exceptionOrNull()
        assertTrue(modelError is AttachmentException && modelError.kind == AttachmentFailure.UNVERIFIED_MODEL)

        val gif = ImageAttachment("mime-reject", "Image.gif", "image/gif", 10, 43, 1, 1)
        val mimeError = runCatching {
            repository.beginTurn(null, "x", ChatModel.SOL, listOf(gif))
        }.exceptionOrNull()
        assertTrue(mimeError is AttachmentException && mimeError.kind == AttachmentFailure.INVALID_FORMAT)

        assertTrue(repository.observeConversations().first().isEmpty())
        assertTrue(database.usageEvents().observeForConversation("any").first().isEmpty())
    }

    @Test
    fun assistantCheckpointsUpdateReactiveTextSizes() = runTest {
        val (repository, _) = testRepository()
        val turn = repository.beginTurn(null, "hi", ChatModel.SOL)
        assertEquals(2L, repository.observeConversations().first().single().textBytes)

        repository.checkpointAssistant(turn.assistantMessageId, "streamed")

        assertEquals(10L, repository.observeConversations().first().single().textBytes)
        assertEquals(10L, repository.observeConversations().first().single().totalBytes)

        repository.completeTurn(turn.assistantMessageId, "done", null)

        assertEquals(6L, repository.observeConversations().first().single().textBytes)
    }

    @Test
    fun deletingConversationCascadesToAttachmentRowsAndUpdatesSizes() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(
            null,
            "delete me",
            ChatModel.SOL,
            listOf(image("cascade-1"), image("cascade-2")),
        )
        assertEquals(2, repository.observeConversations().first().single().attachmentCount)

        repository.deleteConversation(turn.conversationId)

        assertTrue(repository.observeConversations().first().isEmpty())
        assertEquals(0, database.attachments().countForConversation(turn.conversationId))
        assertTrue(database.attachments().listForConversation(turn.conversationId).isEmpty())
    }

    @Test
    fun attachmentSizesAreIsolatedPerConversation() = runTest {
        val (repository, _) = testRepository()
        val withImages = repository.beginTurn(null, "hi", ChatModel.SOL, listOf(image("chat-a-image")))
        val textOnly = repository.beginTurn(null, "hello", ChatModel.SOL)

        val conversations = repository.observeConversations().first().associateBy { it.id }
        val imagesConversation = conversations.getValue(withImages.conversationId)
        val textConversation = conversations.getValue(textOnly.conversationId)
        assertEquals(2L, imagesConversation.textBytes)
        assertEquals(133L, imagesConversation.attachmentBytes)
        assertEquals(1, imagesConversation.attachmentCount)
        assertEquals(5L, textConversation.textBytes)
        assertEquals(0L, textConversation.attachmentBytes)
        assertEquals(0, textConversation.attachmentCount)

        repository.deleteConversation(withImages.conversationId)

        val remaining = repository.observeConversations().first().single()
        assertEquals(5L, remaining.textBytes)
        assertEquals(0L, remaining.attachmentBytes)
        assertEquals(0, remaining.attachmentCount)
    }

    @Test
    fun observedMessagesKeepInsertionOrderAndRefreshAfterLaterTurns() = runTest {
        val (repository, database) = testRepository()
        val zebra = image("zebra")
        val apple = image("apple")
        val turn = repository.beginTurn(null, "order", ChatModel.SOL, listOf(zebra, apple))

        val snapshot = repository.messagesSnapshot(turn.conversationId).first { it.role == "USER" }
        assertEquals(listOf(zebra, apple), snapshot.attachments)
        assertEquals(
            listOf("zebra", "apple"),
            database.attachments().listForConversation(turn.conversationId).map { it.id },
        )

        val second = repository.beginTurn(turn.conversationId, "second", ChatModel.SOL, listOf(image("gamma")))

        val messages = repository.observeMessages(turn.conversationId).first()
        assertEquals(listOf(zebra, apple), messages[0].attachments)
        assertTrue(messages[1].attachments.isEmpty())
        assertEquals(listOf(image("gamma")), messages[2].attachments)
        assertEquals(second.conversationId, turn.conversationId)
    }

    private fun image(
        id: String,
        name: String = "Image.png",
        mime: String = "image/png",
        original: Long = 100,
        encrypted: Long = 133,
        width: Int = 2,
        height: Int = 2,
    ) = ImageAttachment(id, name, mime, original, encrypted, width, height)

    private fun testRepository(nowMillis: () -> Long = System::currentTimeMillis): Pair<ChatRepository, ChatDatabase> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        return ChatRepository(database, nowMillis) to database
    }
}
