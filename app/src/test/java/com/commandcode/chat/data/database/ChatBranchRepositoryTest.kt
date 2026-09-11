package com.commandcode.chat.data.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.MessageState
import com.commandcode.chat.domain.TokenUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatBranchRepositoryTest {
    private val databases = CopyOnWriteArrayList<ChatDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
    }

    @Test
    fun editingAnEarlierTurnCreatesASelectedSiblingWithoutOverwritingItsContinuation() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "original", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "answer one", null)
        val later = repository.beginTurn(first.conversationId, "later", ChatModel.SOL)
        repository.completeTurn(later.assistantMessageId, "later answer", null)

        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val edit = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.completeTurn(edit.assistantMessageId, "answer two", null)

        assertEquals(
            listOf("edited", "answer two"),
            repository.messagesSnapshot(first.conversationId)
                .filter { it.role == "USER" || it.role == "ASSISTANT" }
                .map { it.content },
        )
        assertEquals("original", database.messages().find(first.userMessageId)!!.content)

        repository.selectAlternative(first.conversationId, first.userMessageId)
        assertEquals(later.assistantMessageId, repository.messagesSnapshot(first.conversationId).last().id)
    }

    @Test
    fun retryingAnAssistantCreatesAResponseSiblingWhileSharingTheUser() = runTest {
        val (repository, _) = testRepository()
        val first = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "first answer", null)

        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val retry = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.LUNA,
            "",
            source.expectedPathIds,
        )

        assertEquals(first.userMessageId, retry.userMessageId)
        assertTrue(first.assistantMessageId != retry.assistantMessageId)
        repository.completeTurn(retry.assistantMessageId, "second answer", null)
        assertEquals(
            listOf(first.userMessageId, retry.assistantMessageId),
            repository.messagesSnapshot(first.conversationId).filter {
                it.role == "USER" || it.role == "ASSISTANT"
            }.map { it.id },
        )
    }

    @Test
    fun selectedResponseScopesContextMeasurementWithoutDroppingOtherAttemptTotals() = runTest {
        val (repository, _) = testRepository()
        val first = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "first answer", TokenUsage(100, 0, 20))

        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val retry = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.completeTurn(retry.assistantMessageId, "second answer", TokenUsage(500, 0, 50))

        val retryUsage = repository.observeTokenUsage(first.conversationId).first()
        assertEquals(670L, retryUsage.totalProcessedTokens)
        assertEquals(retry.assistantMessageId, retryUsage.measurement?.requestId)
        assertEquals(550L, retryUsage.measurement?.currentContextTokens)

        repository.selectAlternative(first.conversationId, first.assistantMessageId)

        val firstUsage = repository.observeTokenUsage(first.conversationId).first()
        assertEquals(670L, firstUsage.totalProcessedTokens)
        assertEquals(first.assistantMessageId, firstUsage.measurement?.requestId)
        assertEquals(120L, firstUsage.measurement?.currentContextTokens)
    }

    @Test
    fun longLivedUsageCollectorRecomputesMeasurementWhenResponseBranchSwitches() = runTest {
        val (repository, _) = testRepository()
        val first = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "first answer", TokenUsage(100, 0, 20))

        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val retry = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.completeTurn(retry.assistantMessageId, "second answer", TokenUsage(500, 0, 50))

        val updates = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                repository.observeTokenUsage(first.conversationId)
                    .onEach { usage ->
                        if (usage.measurement?.requestId == retry.assistantMessageId) {
                            repository.selectAlternative(first.conversationId, first.assistantMessageId)
                        }
                    }
                    .take(2)
                    .toList()
            }
        }

        val retryUsage = updates[0]
        assertEquals(retry.assistantMessageId, retryUsage.measurement?.requestId)
        assertEquals(550L, retryUsage.measurement?.currentContextTokens)

        val firstUsage = updates[1]
        assertEquals(first.assistantMessageId, firstUsage.measurement?.requestId)
        assertEquals(120L, firstUsage.measurement?.currentContextTokens)
        assertEquals(670L, firstUsage.totalProcessedTokens)
    }

    @Test
    fun longLivedMessageCollectorEmitsEmptyWhenObservedConversationIsDeleted() = runTest {
        val (repository, _) = testRepository()
        val turn = repository.beginTurn(null, "observed", ChatModel.SOL)
        repository.completeTurn(turn.assistantMessageId, "answer", null)
        val firstEmission = CompletableDeferred<List<Message>>()
        val emptyEmission = CompletableDeferred<List<Message>>()
        val collector = launch(Dispatchers.Default) {
            repository.observeMessages(turn.conversationId).collect { messages ->
                if (messages.isEmpty()) {
                    if (firstEmission.isCompleted) emptyEmission.complete(messages)
                } else {
                    firstEmission.complete(messages)
                }
            }
        }

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { firstEmission.await() }
        }
        repository.deleteConversation(turn.conversationId)

        val afterDelete = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { emptyEmission.await() }
        }
        assertTrue(afterDelete.isEmpty())
        collector.cancelAndJoin()
    }

    @Test
    fun observingAnAlreadyDeletedConversationEmitsAnEmptyTimeline() = runTest {
        val (repository, _) = testRepository()
        val turn = repository.beginTurn(null, "deleted", ChatModel.SOL)
        repository.deleteConversation(turn.conversationId)

        assertTrue(repository.observeMessages(turn.conversationId).first().isEmpty())
    }

    @Test
    fun selectedUserIdentityCannotBecomeAContextMeasurement() = runTest {
        val (repository, database) = testRepository()
        val turn = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(turn.assistantMessageId, "answer", TokenUsage(100, 0, 20))
        val timestamp = requireNotNull(database.usageEvents().latestTimestamp(turn.conversationId))
        database.usageEvents().insert(
            UsageEventEntity(
                id = "user-linked-legacy-event",
                requestId = turn.userMessageId,
                conversationId = turn.conversationId,
                modelId = ChatModel.SOL.apiId,
                timestamp = timestamp + 1,
                inputTokens = 999,
                cachedInputTokens = 0,
                outputTokens = 1,
                estimatedModelCost = null,
                estimatedGoatCredits = null,
                usageComplete = true,
            ),
        )

        val usage = repository.observeTokenUsage(turn.conversationId).first()
        assertEquals(1120L, usage.totalProcessedTokens)
        assertEquals(turn.assistantMessageId, usage.measurement?.requestId)
        assertEquals(120L, usage.measurement?.currentContextTokens)
    }

    @Test
    fun selectedInterruptedResponseKeepsEarlierSelectedMeasurementAndAllAttemptTotals() = runTest {
        val (repository, _) = testRepository()
        val initial = repository.beginTurn(null, "initial", ChatModel.SOL)
        repository.completeTurn(initial.assistantMessageId, "initial answer", TokenUsage(100, 0, 20))
        val first = repository.beginTurn(initial.conversationId, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "first answer", TokenUsage(200, 0, 30))
        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val retry = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )

        repository.interruptTurn(retry.assistantMessageId, "partial", "cancelled", TokenUsage(500, 0, 50))

        val usage = repository.observeTokenUsage(first.conversationId).first()
        assertEquals(900L, usage.totalProcessedTokens)
        assertEquals(initial.assistantMessageId, usage.measurement?.requestId)
        assertEquals(120L, usage.measurement?.currentContextTokens)
        assertTrue(usage.accountingIncomplete)
        assertTrue(usage.hasUnmeasuredAttempt)
    }

    @Test
    fun deletedFailedAnchorRetainsUsageAndSelectedContinuationMeasurementAcrossSwitchBack() = runTest {
        val (repository, _) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "first answer", TokenUsage(100, 0, 20))
        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val failed = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.failZdrTurn(failed.assistantMessageId, TokenUsage(30, 0, 4))
        val later = repository.beginTurn(first.conversationId, "later", ChatModel.SOL)
        repository.completeTurn(later.assistantMessageId, "later answer", TokenUsage(500, 0, 50))

        repository.deleteFailedTurn(failed.userMessageId)

        val continuationUsage = repository.observeTokenUsage(first.conversationId).first()
        assertEquals(704L, continuationUsage.totalProcessedTokens)
        assertEquals(later.assistantMessageId, continuationUsage.measurement?.requestId)
        assertEquals(550L, continuationUsage.measurement?.currentContextTokens)
        assertTrue(continuationUsage.accountingIncomplete)
        assertTrue(!continuationUsage.hasUnmeasuredAttempt)

        repository.selectAlternative(first.conversationId, first.assistantMessageId)

        val firstUsage = repository.observeTokenUsage(first.conversationId).first()
        assertEquals(704L, firstUsage.totalProcessedTokens)
        assertEquals(first.assistantMessageId, firstUsage.measurement?.requestId)
        assertEquals(120L, firstUsage.measurement?.currentContextTokens)
    }

    @Test
    fun staleAlternativePreparationChangesNoGraphMessagesOrUsage() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "first answer", null)
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val initialMessageIds = database.messages().listForConversation(first.conversationId).map { it.id }
        val initialNodeIds = database.branches().listNodes(first.conversationId).map { it.id }
        val initialUsageCount = database.usageEvents().observeForConversation(first.conversationId)
            .first().size

        repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )

        val error = runCatching {
            repository.createAlternative(
                first.conversationId,
                AlternativeRequest.Edit(first.userMessageId, "stale"),
                ChatModel.SOL,
                "",
                source.expectedPathIds,
            )
        }.exceptionOrNull()

        assertTrue(error != null)
        assertEquals(initialMessageIds.size + 2, database.messages().listForConversation(first.conversationId).size)
        assertEquals(initialNodeIds.size + 2, database.branches().listNodes(first.conversationId).size)
        assertEquals(
            initialUsageCount + 1,
            database.usageEvents().observeForConversation(first.conversationId).first().size,
        )
    }

    @Test
    fun alternativeSettingsAreComparedWithTheAncestorPrefixAndNotTargetNotices() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL, systemPrompt = "A")
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val later = repository.beginTurn(first.conversationId, "later", ChatModel.SOL, systemPrompt = "B")
        repository.completeTurn(later.assistantMessageId, "later answer", null)

        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val fork = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "forked"),
            ChatModel.LUNA,
            "B",
            source.expectedPathIds,
        )

        val rows = repository.messagesSnapshot(first.conversationId)
        assertEquals(
            listOf(MessageState.MODEL_CHANGE, MessageState.SYSTEM_PROMPT_CHANGE, "USER", "ASSISTANT"),
            rows.takeLast(4).map { it.role },
        )
        assertEquals(fork.userMessageId, rows[rows.size - 2].id)
        assertEquals(fork.assistantMessageId, rows.last().id)
        assertEquals("first", database.messages().find(first.userMessageId)!!.content)
    }

    @Test
    fun sharedAttachmentLinksDoNotMultiplyConversationBytesWhenOneBranchIsRemoved() = runTest {
        val image = ImageAttachment("shared", "Image.png", "image/png", 100, 133, 2, 2)
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "original", ChatModel.SOL, listOf(image))
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val fork = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )

        val withBoth = repository.observeConversations().first().single()
        assertEquals(1, withBoth.attachmentCount)
        assertEquals(133L, withBoth.attachmentBytes)
        assertEquals(listOf(image.id), database.messageAttachments().listForMessage(first.userMessageId).map { it.attachmentId })
        assertEquals(listOf(image.id), database.messageAttachments().listForMessage(fork.userMessageId).map { it.attachmentId })

        repository.failZdrTurn(fork.assistantMessageId)
        repository.deleteFailedTurn(fork.userMessageId)
        assertEquals(listOf(image.id), database.attachments().listIds())
        assertEquals(listOf(image.id), database.messageAttachments().listForMessage(first.userMessageId).map { it.attachmentId })
        assertEquals(133L, repository.observeConversations().first().single().attachmentBytes)
    }

    @Test
    fun deletingASelectedFailedAssistantWithSiblingsLeavesAnEmptySelectableResponseBranch() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "successful", null)
        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val failed = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.failZdrTurn(failed.assistantMessageId)

        repository.deleteFailedTurn(failed.userMessageId)

        val visible = repository.messagesSnapshot(first.conversationId)
        val user = visible.single { it.id == first.userMessageId }
        assertEquals("COMPLETE", user.status)
        assertEquals(listOf(first.assistantMessageId, failed.assistantMessageId), user.responseBranchChoice!!.messageIds)
        assertTrue(database.branches().findNode(failed.assistantMessageId)!!.anchorDeleted)
        assertEquals(null, database.messages().find(failed.assistantMessageId))

        repository.selectAlternative(first.conversationId, first.assistantMessageId)
        assertEquals(
            listOf(first.userMessageId, first.assistantMessageId),
            repository.messagesSnapshot(first.conversationId).filter { it.role == "USER" || it.role == "ASSISTANT" }.map { it.id },
        )
        repository.selectAlternative(first.conversationId, failed.assistantMessageId)
        assertEquals(
            listOf(first.userMessageId),
            repository.messagesSnapshot(first.conversationId).filter { it.role == "USER" || it.role == "ASSISTANT" }.map { it.id },
        )

        val reopened = ChatRepository(database)
        assertEquals(
            listOf(first.userMessageId),
            reopened.messagesSnapshot(first.conversationId)
                .filter { it.role == "USER" || it.role == "ASSISTANT" }
                .map { it.id },
        )
    }

    @Test
    fun deletingTheOnlyFailedResponseSplicesLaterTurnsUnderItsParentAndPreservesUserSiblings() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL)
        repository.failZdrTurn(first.assistantMessageId)
        val later = repository.beginTurn(first.conversationId, "later", ChatModel.SOL)
        repository.completeTurn(later.assistantMessageId, "later answer", null)

        repository.deleteFailedTurn(first.userMessageId)

        assertEquals(
            listOf(later.userMessageId, later.assistantMessageId),
            repository.messagesSnapshot(first.conversationId).filter {
                it.role == "USER" || it.role == "ASSISTANT"
            }.map { it.id },
        )
        assertEquals(null, database.messages().find(first.assistantMessageId))
        assertEquals(null, database.messages().find(first.userMessageId))
        assertEquals(
            later.userMessageId,
            database.branches().findSelection(database.branches().findNode("root:${first.conversationId}")!!.id)?.selectedNodeId,
        )
    }

    @Test
    fun recoveryRetryReplacesOnlyTheSelectedFailedNodeAndKeepsItsContinuation() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL)
        repository.failZdrTurn(first.assistantMessageId)
        val later = repository.beginTurn(first.conversationId, "later", ChatModel.SOL)
        repository.completeTurn(later.assistantMessageId, "later answer", null)

        val retry = repository.retryFailedTurn(first.userMessageId, ChatModel.LUNA)

        assertEquals(
            listOf(first.userMessageId, retry.assistantMessageId, later.userMessageId, later.assistantMessageId),
            repository.messagesSnapshot(first.conversationId).filter {
                it.role == "USER" || it.role == "ASSISTANT"
            }.map { it.id },
        )
        assertEquals(retry.assistantMessageId, database.branches().findNode(later.userMessageId)!!.parentId)
        assertEquals(null, database.messages().find(first.assistantMessageId))
        assertEquals(3, database.usageEvents().observeForConversation(first.conversationId).first().size)
    }

    @Test
    fun newConversationRootsReserveCollidingMessageAndNodeIds() = runTest {
        val (repository, database) = testRepository()
        database.conversations().insert(ConversationEntity("collision", "Collision", ChatModel.SOL.apiId, 1, 1))
        database.messages().insert(
            MessageEntity("root:collision", "collision", "USER", "legacy", null, 2, "COMPLETE"),
        )

        repository.beginTurn("collision", "next", ChatModel.SOL)

        val roots = database.branches().listNodes("collision").filter { it.parentId == null }
        assertEquals(1, roots.size)
        assertEquals("root:collision:1", roots.single().id)
    }

    @Test
    fun sourceRejectsLocalNonselectedPendingAndCrossConversationTargets() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "alternative"),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )

        assertTrue(runCatching {
            repository.alternativeSource(first.conversationId, first.userMessageId)
        }.isFailure)
        assertTrue(runCatching {
            repository.alternativeSource(first.conversationId, database.messages().listForConversation(first.conversationId)
                .first { it.role == MessageState.INITIAL_MODEL }.id)
        }.isFailure)
        val pendingAssistant = repository.messagesSnapshot(first.conversationId).first { it.status == "PENDING" }
        assertTrue(runCatching {
            repository.alternativeSource(first.conversationId, pendingAssistant.id)
        }.isFailure)
        val other = repository.beginTurn(null, "other", ChatModel.SOL)
        assertTrue(runCatching {
            repository.alternativeSource(first.conversationId, other.userMessageId)
        }.isFailure)
    }

    @Test
    fun projectedCompleteUserCanBeEditedWhenAFailedResponseIsNotSelected() = runTest {
        val (repository, _) = testRepository()
        val first = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "successful", null)
        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val failed = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.failZdrTurn(failed.assistantMessageId)
        repository.selectAlternative(first.conversationId, first.assistantMessageId)

        val user = repository.messagesSnapshot(first.conversationId).single { it.id == first.userMessageId }
        assertEquals("COMPLETE", user.status)
        val editSource = repository.alternativeSource(first.conversationId, first.userMessageId)
        assertEquals("COMPLETE", editSource.target.status)

        val edited = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.SOL,
            "",
            editSource.expectedPathIds,
        )
        assertTrue(edited.userMessageId != first.userMessageId)
    }

    @Test
    fun recoveryRetryUsesTheFailedAssistantAncestorSettingsNotLaterContinuationSettings() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL, systemPrompt = "A")
        repository.failZdrTurn(first.assistantMessageId)
        val later = repository.beginTurn(first.conversationId, "later", ChatModel.LUNA, systemPrompt = "B")
        repository.completeTurn(later.assistantMessageId, "later answer", null)

        repository.retryFailedTurn(first.userMessageId, ChatModel.SOL, systemPrompt = "A")

        assertEquals(
            0,
            database.messages().listForConversation(first.conversationId)
                .count { it.role == MessageState.MODEL_CHANGE },
        )
        assertEquals(
            2,
            database.messages().listForConversation(first.conversationId)
                .count { it.role == MessageState.SYSTEM_PROMPT_CHANGE },
        )
    }

    @Test
    fun recoveryRetryPlacesNewSettingsNoticesBeforeItsReplacementAnchor() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL, systemPrompt = "A")
        repository.failZdrTurn(first.assistantMessageId)

        val retry = repository.retryFailedTurn(first.userMessageId, ChatModel.LUNA, systemPrompt = "B")
        val placements = database.branches().listPlacementsForNode(retry.assistantMessageId)
        val roleByMessageId = database.messages().listForConversation(first.conversationId).associate { it.id to it.role }

        assertEquals(
            listOf(MessageState.MODEL_CHANGE, MessageState.SYSTEM_PROMPT_CHANGE, "ASSISTANT"),
            placements.map { requireNotNull(roleByMessageId[it.messageId]) },
        )
        assertTrue(placements[0].position < placements[1].position)
        assertEquals(0, placements.last().position)
    }

    @Test
    fun editingSharedAttachmentsValidatesTheRequestedModelBeforeInsertingTheAlternative() = runTest {
        val image = ImageAttachment("shared-model-check", "Image.png", "image/png", 100, 133, 2, 2)
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "original", ChatModel.SOL, listOf(image))
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val unknownModel = ChatModel("example/unknown", "Unknown", ChatModel.SOL.apiFamily)

        val error = runCatching {
            repository.createAlternative(
                first.conversationId,
                AlternativeRequest.Edit(first.userMessageId, "edited"),
                unknownModel,
                "",
                source.expectedPathIds,
            )
        }.exceptionOrNull()

        assertTrue(error is AttachmentException && error.kind == AttachmentFailure.UNVERIFIED_MODEL)
        assertEquals(3, database.messages().listForConversation(first.conversationId).size)
        assertEquals(3, database.branches().listNodes(first.conversationId).size)
        assertEquals(1, database.usageEvents().observeForConversation(first.conversationId).first().size)
    }

    @Test
    fun fourResponseAndUserSiblingsRetainNestedSelectionAfterReopen() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "question", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "answer 0", null)
        val responseIds = mutableListOf(first.assistantMessageId)
        repeat(3) { index ->
            repository.selectAlternative(first.conversationId, first.assistantMessageId)
            val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
            val retry = repository.createAlternative(
                first.conversationId,
                AlternativeRequest.Retry(first.assistantMessageId),
                ChatModel.SOL,
                "",
                source.expectedPathIds,
            )
            repository.completeTurn(retry.assistantMessageId, "answer ${index + 1}", null)
            responseIds += retry.assistantMessageId
        }

        val userIds = mutableListOf(first.userMessageId)
        repeat(3) { index ->
            repository.selectAlternative(first.conversationId, first.userMessageId)
            val source = repository.alternativeSource(first.conversationId, first.userMessageId)
            val edit = repository.createAlternative(
                first.conversationId,
                AlternativeRequest.Edit(first.userMessageId, "question ${index + 1}"),
                ChatModel.SOL,
                "",
                source.expectedPathIds,
            )
            repository.completeTurn(edit.assistantMessageId, "edited answer ${index + 1}", null)
            userIds += edit.userMessageId
        }

        repository.selectAlternative(first.conversationId, first.userMessageId)
        repository.selectAlternative(first.conversationId, responseIds[2])
        val nested = repository.beginTurn(first.conversationId, "nested", ChatModel.SOL)
        repository.completeTurn(nested.assistantMessageId, "nested answer", null)
        val nestedSource = repository.alternativeSource(first.conversationId, nested.assistantMessageId)
        val nestedRetry = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(nested.assistantMessageId),
            ChatModel.SOL,
            "",
            nestedSource.expectedPathIds,
        )
        repository.completeTurn(nestedRetry.assistantMessageId, "nested retry", null)

        val reopened = ChatRepository(database)
        val reopenedRows = reopened.messagesSnapshot(first.conversationId)
        assertEquals(4, reopenedRows.single { it.id == first.userMessageId }.branchChoice!!.messageIds.size)
        assertEquals(4, reopenedRows.single { it.id == responseIds[2] }.branchChoice!!.messageIds.size)
        assertTrue(reopenedRows.any { it.id == nestedRetry.assistantMessageId })

        reopened.selectAlternative(first.conversationId, userIds[1])
        assertTrue(reopened.messagesSnapshot(first.conversationId).none { it.id == nestedRetry.assistantMessageId })
        reopened.selectAlternative(first.conversationId, first.userMessageId)
        assertTrue(reopened.messagesSnapshot(first.conversationId).any { it.id == nestedRetry.assistantMessageId })
    }

    @Test
    fun deletedFailedResponseWithContinuationCanBeReselectedAfterReopen() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "first", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "successful", null)
        val source = repository.alternativeSource(first.conversationId, first.assistantMessageId)
        val failed = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Retry(first.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.failZdrTurn(failed.assistantMessageId)
        val later = repository.beginTurn(first.conversationId, "later", ChatModel.SOL)
        repository.completeTurn(later.assistantMessageId, "later answer", null)

        repository.deleteFailedTurn(failed.userMessageId)
        val reopened = ChatRepository(database)
        assertEquals(
            listOf(first.userMessageId, later.userMessageId, later.assistantMessageId),
            reopened.messagesSnapshot(first.conversationId).filter { it.role == "USER" || it.role == "ASSISTANT" }.map { it.id },
        )

        reopened.selectAlternative(first.conversationId, first.assistantMessageId)
        assertEquals(
            listOf(first.userMessageId, first.assistantMessageId),
            reopened.messagesSnapshot(first.conversationId).filter { it.role == "USER" || it.role == "ASSISTANT" }.map { it.id },
        )
        reopened.selectAlternative(first.conversationId, failed.assistantMessageId)
        assertEquals(
            listOf(first.userMessageId, later.userMessageId, later.assistantMessageId),
            reopened.messagesSnapshot(first.conversationId).filter { it.role == "USER" || it.role == "ASSISTANT" }.map { it.id },
        )
        assertEquals(0, database.branches().listPlacementsForNode(failed.assistantMessageId).count { it.position == 0 })
    }

    @Test
    fun deletingAnEditedFailedUserKeepsTheOriginalUserSiblingAndSplicesItsParent() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "original", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val edited = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.failZdrTurn(edited.assistantMessageId)

        repository.deleteFailedTurn(edited.userMessageId)

        assertEquals(
            listOf(first.userMessageId, first.assistantMessageId),
            repository.messagesSnapshot(first.conversationId).filter {
                it.role == "USER" || it.role == "ASSISTANT"
            }.map { it.id },
        )
        assertEquals(null, database.messages().find(edited.userMessageId))
        assertEquals(null, database.branches().findNode(edited.userMessageId))
        assertEquals(null, database.branches().findNode(edited.assistantMessageId))
    }

    @Test
    fun deletingFailedEditedAlternativeDiscardsExclusiveNoticesAndRestoresOriginalTimeline() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "original", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val originalRows = repository.messagesSnapshot(first.conversationId)
        val originalIds = originalRows.map { it.id }.toSet()
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val edited = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.LUNA,
            "changed prompt",
            source.expectedPathIds,
        )
        val exclusiveNotices = database.messages().listForConversation(first.conversationId)
            .filter { it.id !in originalIds }
            .filter { it.role == MessageState.MODEL_CHANGE || it.role == MessageState.SYSTEM_PROMPT_CHANGE }
            .map { it.role to it.content }
        assertEquals(2, exclusiveNotices.size)
        repository.failZdrTurn(edited.assistantMessageId)

        repository.deleteFailedTurn(edited.userMessageId)

        assertEquals(originalRows, repository.messagesSnapshot(first.conversationId))
        assertEquals(originalIds, database.messages().listForConversation(first.conversationId).map { it.id }.toSet())
        assertTrue(database.messages().listForConversation(first.conversationId).none { message ->
            (message.role to message.content) in exclusiveNotices
        })
    }

    @Test
    fun deletingFailedEditedAlternativeKeepsItsNoticesOnlyOnASurvivingContinuationBranch() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "original", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val originalRows = repository.messagesSnapshot(first.conversationId)
        val originalIds = originalRows.map { it.id }.toSet()
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val edited = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.LUNA,
            "changed prompt",
            source.expectedPathIds,
        )
        repository.failZdrTurn(edited.assistantMessageId)
        val later = repository.beginTurn(
            first.conversationId,
            "later",
            ChatModel.LUNA,
            systemPrompt = "changed prompt",
        )
        repository.completeTurn(later.assistantMessageId, "later answer", null)
        val exclusiveNotices = database.messages().listForConversation(first.conversationId)
            .filter { it.id !in originalIds }
            .filter { it.role == MessageState.MODEL_CHANGE || it.role == MessageState.SYSTEM_PROMPT_CHANGE }
            .map { it.role to it.content }
        assertEquals(2, exclusiveNotices.size)

        repository.deleteFailedTurn(edited.userMessageId)

        repository.selectAlternative(first.conversationId, first.userMessageId)
        val originalBranch = repository.messagesSnapshot(first.conversationId)
        assertEquals(originalRows.map { it.id to it.content }, originalBranch.map { it.id to it.content })
        assertTrue(originalBranch.none { it.role == MessageState.MODEL_CHANGE || it.role == MessageState.SYSTEM_PROMPT_CHANGE })
        repository.selectAlternative(first.conversationId, later.userMessageId)
        val continuationBranch = repository.messagesSnapshot(first.conversationId)
        assertEquals(
            exclusiveNotices,
            continuationBranch
                .filter { it.role == MessageState.MODEL_CHANGE || it.role == MessageState.SYSTEM_PROMPT_CHANGE }
                .map { it.role to it.content },
        )
        assertTrue(database.branches().findNode(later.userMessageId)!!.parentId != edited.userMessageId)
    }

    @Test
    fun deletingFailedEditedAlternativePreservesNoticesForEveryContinuationAfterReopen() = runTest {
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "original", ChatModel.SOL)
        repository.completeTurn(first.assistantMessageId, "answer", null)
        val originalRows = repository.messagesSnapshot(first.conversationId)
        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        val failed = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "failed edited"),
            ChatModel.LUNA,
            "changed prompt",
            source.expectedPathIds,
        )
        repository.failZdrTurn(failed.assistantMessageId)

        val continuationOne = repository.beginTurn(
            first.conversationId,
            "continuation one",
            ChatModel.LUNA,
            systemPrompt = "changed prompt",
        )
        repository.completeTurn(continuationOne.assistantMessageId, "answer one", null)
        val continuationSource = repository.alternativeSource(
            first.conversationId,
            continuationOne.userMessageId,
        )
        val continuationTwo = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(continuationOne.userMessageId, "continuation two"),
            ChatModel.LUNA,
            "changed prompt",
            continuationSource.expectedPathIds,
        )
        repository.completeTurn(continuationTwo.assistantMessageId, "answer two", null)
        val usageBeforeDelete = database.usageEvents().listForConversation(first.conversationId)

        repository.deleteFailedTurn(failed.userMessageId)

        val reopened = ChatRepository(database)
        val noticeRows = { rows: List<Message> ->
            rows.filter { it.role == MessageState.MODEL_CHANGE || it.role == MessageState.SYSTEM_PROMPT_CHANGE }
        }
        reopened.selectAlternative(first.conversationId, continuationOne.userMessageId)
        val continuationOneRows = reopened.messagesSnapshot(first.conversationId)
        assertEquals(2, noticeRows(continuationOneRows).size)
        assertEquals("GPT-5.6 Luna", noticeRows(continuationOneRows).first().content)

        reopened.selectAlternative(first.conversationId, continuationTwo.userMessageId)
        val continuationTwoRows = reopened.messagesSnapshot(first.conversationId)
        assertEquals(2, noticeRows(continuationTwoRows).size)
        assertEquals("GPT-5.6 Luna", noticeRows(continuationTwoRows).first().content)
        assertTrue(
            noticeRows(continuationOneRows).map { it.id }.toSet()
                .intersect(noticeRows(continuationTwoRows).map { it.id }.toSet()).isEmpty(),
        )

        reopened.selectAlternative(first.conversationId, continuationOne.userMessageId)
        val noticeIdsBeforeFuture = database.messages().listForConversation(first.conversationId)
            .filter { it.role == MessageState.MODEL_CHANGE || it.role == MessageState.SYSTEM_PROMPT_CHANGE }
            .map { it.id }
            .toSet()
        val future = reopened.beginTurn(
            first.conversationId,
            "future on continuation one",
            ChatModel.LUNA,
            systemPrompt = "changed prompt",
        )
        val noticeIdsAfterFuture = database.messages().listForConversation(first.conversationId)
            .filter { it.role == MessageState.MODEL_CHANGE || it.role == MessageState.SYSTEM_PROMPT_CHANGE }
            .map { it.id }
            .toSet()
        assertEquals(noticeIdsBeforeFuture, noticeIdsAfterFuture)
        repository.completeTurn(future.assistantMessageId, "future answer", null)
        assertEquals(usageBeforeDelete.size + 1, database.usageEvents().listForConversation(first.conversationId).size)

        val reopenedAgain = ChatRepository(database)
        reopenedAgain.selectAlternative(first.conversationId, first.userMessageId)
        val originalBranch = reopenedAgain.messagesSnapshot(first.conversationId)
        assertEquals(originalRows.map { it.id to it.content }, originalBranch.map { it.id to it.content })
        assertTrue(noticeRows(originalBranch).isEmpty())
        reopenedAgain.selectAlternative(first.conversationId, continuationTwo.userMessageId)
        assertEquals(2, noticeRows(reopenedAgain.messagesSnapshot(first.conversationId)).size)
        assertEquals(
            database.messages().listForConversation(first.conversationId).size,
            database.messages().listForConversation(first.conversationId).map { it.id }.toSet().size,
        )
        assertEquals(
            database.branches().listPlacements(first.conversationId).size,
            database.branches().listPlacements(first.conversationId).map { it.messageId }.toSet().size,
        )
    }

    @Test
    fun failedProjectedUserCanBeEditedWithoutRemovingItsRecoveryAttachments() = runTest {
        val image = ImageAttachment("failed-edit-image", "Image.png", "image/png", 100, 133, 2, 2)
        val (repository, database) = testRepository()
        val first = repository.beginTurn(null, "failed", ChatModel.SOL, listOf(image))
        repository.failZdrTurn(first.assistantMessageId)

        val source = repository.alternativeSource(first.conversationId, first.userMessageId)
        assertEquals(MessageState.FAILED_ZDR, source.target.status)
        val edited = repository.createAlternative(
            first.conversationId,
            AlternativeRequest.Edit(first.userMessageId, "edited"),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )

        assertEquals(
            listOf(image.id),
            database.messageAttachments().listForMessage(first.userMessageId).map { it.attachmentId },
        )
        assertEquals(
            listOf(image.id),
            database.messageAttachments().listForMessage(edited.userMessageId).map { it.attachmentId },
        )
        repository.selectAlternative(first.conversationId, first.userMessageId)
        assertEquals(
            MessageState.FAILED_ZDR,
            repository.messagesSnapshot(first.conversationId).single { it.id == first.userMessageId }.status,
        )
        assertEquals("failed", database.messages().find(first.userMessageId)!!.content)
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
