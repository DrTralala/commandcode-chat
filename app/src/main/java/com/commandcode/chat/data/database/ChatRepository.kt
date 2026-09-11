package com.commandcode.chat.data.database
import com.commandcode.chat.domain.MessageState

import androidx.room.withTransaction
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.ConversationTokenUsage
import com.commandcode.chat.domain.ReportedUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class Conversation(
    val id: String,
    val title: String,
    val defaultModelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val textBytes: Long = 0,
    val attachmentBytes: Long = 0,
    val attachmentCount: Int = 0,
) {
    val totalBytes: Long get() = textBytes + attachmentBytes
}

data class Message(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val modelId: String?,
    val createdAt: Long,
    val status: String,
    val attachments: List<ImageAttachment> = emptyList(),
    val replyToMessageId: String? = null,
    val branchChoice: BranchChoice? = null,
    val responseBranchChoice: BranchChoice? = null,
)

data class PendingTurn(
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String,
)

class ChatRepository(
    private val database: ChatDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private object Status {
        const val USER_COMPLETE = "COMPLETE"
        const val PENDING = "PENDING"
        const val STREAMING = "STREAMING"
        const val COMPLETE = "COMPLETE"
        const val INTERRUPTED = "INTERRUPTED"
        const val EMPTY_PROMPT_FINGERPRINT = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
    private val IMAGE_CHAT_TITLE: String = "Image chat"

    fun observeConversations(): Flow<List<Conversation>> = database.conversations().observeAll()
        .map { rows -> rows.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<Message>> = flow {
        val exists = database.withTransaction {
            if (database.conversations().find(conversationId) == null) {
                false
            } else {
                ensureTimeline(conversationId)
                true
            }
        }
        if (!exists) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            database.branches().observeGraph(conversationId)
                .map { rows ->
                    database.withTransaction {
                        if (database.conversations().find(conversationId) == null) {
                            emptyList()
                        } else {
                            rows.toBranchTimeline().selected(conversationId)
                        }
                    }
                },
        )
    }

    fun observeTokenUsage(conversationId: String): Flow<ConversationTokenUsage> = flow {
        val exists = database.withTransaction {
            if (database.conversations().find(conversationId) == null) {
                false
            } else {
                ensureTimeline(conversationId)
                true
            }
        }
        if (!exists) {
            emit(ConversationTokenUsage())
            return@flow
        }
        emitAll(
            database.branches().observeGraph(conversationId)
                .combine(database.usageEvents().observeForConversation(conversationId)) { _, _ ->
                    readTokenUsage(conversationId)
                },
        )
    }

    private suspend fun readTokenUsage(conversationId: String): ConversationTokenUsage =
        database.withTransaction {
            if (database.conversations().find(conversationId) == null) {
                return@withTransaction ConversationTokenUsage()
            }
            val timeline = loadTimeline(conversationId)
            val rootId = database.branches().findRoot(conversationId)?.id
                ?: error("Conversation has no timeline root")
            val messagesById = timeline.messages.associateBy { it.id }
            val contextRequestIds = timeline.selectedPath(conversationId)
                .filter { it.id != rootId }
                .filter { node -> messagesById[node.id]?.role == Role.ASSISTANT || node.anchorDeleted }
                .map { it.id }
                .toSet()
            ConversationTokenUsage.aggregate(
                database.usageEvents().listForConversation(conversationId).map {
                    ReportedUsage(it.id, it.requestId, it.modelId, it.timestamp,
                        it.inputTokens, it.cachedInputTokens, it.outputTokens, it.usageComplete)
                },
                contextRequestIds,
            )
        }

    suspend fun messagesSnapshot(conversationId: String): List<Message> = database.withTransaction {
        ensureTimeline(conversationId)
        loadTimeline(conversationId).selected(conversationId)
    }

    suspend fun deleteConversation(id: String) {
        database.withTransaction {
            if (database.conversations().find(id) == null) return@withTransaction
            deleteGraph(id)
            database.conversations().find(id)?.let(database.conversations()::delete)
        }
    }

    suspend fun beginTurn(
        conversationId: String?,
        text: String,
        model: ChatModel,
        attachments: List<ImageAttachment> = emptyList(),
        systemPrompt: String = "",
    ): PendingTurn {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "A turn needs text or at least one image" }
        val ids = attachments.map { it.id }
        require(ids.size == ids.toSet().size) { "Attachment ids must be unique within a turn" }
        val title = if (text.isBlank()) IMAGE_CHAT_TITLE else titleFor(text)
        val turnStartedAt = nowMillis()
        return database.withTransaction {
            val id = conversationId ?: UUID.randomUUID().toString()
            val previousLatest = database.messages().latestCreatedAt(id) ?: Long.MIN_VALUE
            var allocated = previousLatest
            fun allocateAt(): Long {
                allocated = nextTimestamp(turnStartedAt, allocated)
                return allocated
            }

            val isNewConversation = conversationId == null
            val initialEventAt = if (isNewConversation) allocateAt() else null
            val effective = effectivePrompt(systemPrompt)
            val fingerprint = promptFingerprint(effective)
            val history = if (isNewConversation) {
                emptyList()
            } else {
                database.conversations().find(id) ?: error("Conversation not found")
                ensureTimeline(id)
                loadTimeline(id).selected(id)
            }
            val latestPromptFingerprint = history.lastOrNull {
                it.role == MessageState.SYSTEM_PROMPT_CHANGE
            }?.content
            val promptEventAt = if (promptFingerprintChanged(latestPromptFingerprint, fingerprint)) allocateAt() else null

            val userAt = nextTimestamp(turnStartedAt, allocated)
            val assistantAt = nextTimestamp(userAt, userAt)
            if (isNewConversation) {
                database.conversations().insert(
                    ConversationEntity(id, title, model.apiId, userAt, assistantAt),
                )
            } else {
                val conversation = database.conversations().find(id) ?: error("Conversation not found")
                database.conversations().touch(id, maxOf(assistantAt, nextTimestamp(userAt, conversation.updatedAt)))
            }
            ensureTimeline(id)
            val currentTimeline = loadTimeline(id)
            val parentNodeId = currentTimeline.selectedPath(id).last().id
            attachments.forEach { requireOwnershipReuseAbsent(it.id) }
            attachments.forEach { AttachmentPolicy.requireAllowed(model.apiId, it.mimeType) }
            AttachmentPolicy.requireCount(database.attachments().countForConversation(id), 0, attachments.size)
            val userId = UUID.randomUUID().toString()
            val assistantId = UUID.randomUUID().toString()
            val initialId = initialEventAt?.let { UUID.randomUUID().toString() }
            val promptId = promptEventAt?.let { UUID.randomUUID().toString() }
            initialEventAt?.let { eventAt ->
                database.messages().insert(
                    MessageEntity(
                        requireNotNull(initialId), id, MessageState.INITIAL_MODEL,
                        model.displayName, model.apiId, eventAt, Status.COMPLETE,
                    ),
                )
            }
            promptEventAt?.let { eventAt ->
                database.messages().insert(
                    MessageEntity(
                        requireNotNull(promptId), id, MessageState.SYSTEM_PROMPT_CHANGE,
                        fingerprint, null, eventAt, Status.COMPLETE,
                    ),
                )
            }
            database.messages().insert(MessageEntity(userId, id, Role.USER, text, null, userAt, Status.USER_COMPLETE))
            database.messages().insert(MessageEntity(assistantId, id, Role.ASSISTANT, "", model.apiId, assistantAt, Status.PENDING, userId))
            val userNode = TimelineNodeEntity(
                id = userId,
                conversationId = id,
                parentId = parentNodeId,
                ordinal = allocateOrdinal(id, parentNodeId),
            )
            val assistantNode = TimelineNodeEntity(
                id = assistantId,
                conversationId = id,
                parentId = userId,
                ordinal = allocateOrdinal(id, userId),
            )
            database.branches().insertNode(userNode)
            database.branches().insertNode(assistantNode)
            database.branches().insertPlacement(MessagePlacementEntity(userId, userId, 0))
            database.branches().insertPlacement(MessagePlacementEntity(assistantId, assistantId, 0))
            database.branches().select(BranchSelectionEntity(parentNodeId, userId))
            database.branches().select(BranchSelectionEntity(userId, assistantId))
            initialId?.let { eventId ->
                database.branches().insertPlacement(
                    MessagePlacementEntity(eventId, currentTimeline.selectedPath(id).first().id, nextTrailingPosition(currentTimeline.selectedPath(id).first().id)),
                )
            }
            promptId?.let { eventId ->
                database.branches().insertPlacement(MessagePlacementEntity(eventId, userId, -1))
            }
            if (attachments.isNotEmpty()) {
                database.attachments().insert(
                    attachments.map { image ->
                        AttachmentEntity(
                            id = image.id,
                            conversationId = id,
                            displayName = image.displayName,
                            mimeType = image.mimeType,
                            originalBytes = image.originalBytes,
                            encryptedBytes = image.encryptedBytes,
                            width = image.width,
                            height = image.height,
                        )
                    },
                )
                database.messageAttachments().insert(
                    attachments.mapIndexed { position, image ->
                        MessageAttachmentEntity(userId, image.id, position)
                    },
                )
            }
            database.usageEvents().insert(
                UsageEventEntity(
                    id = UUID.randomUUID().toString(),
                    requestId = assistantId,
                    conversationId = id,
                    modelId = model.apiId,
                    timestamp = nextUsageTimestamp(id, turnStartedAt),
                    inputTokens = null,
                    cachedInputTokens = null,
                    outputTokens = null,
                    estimatedModelCost = null,
                    estimatedGoatCredits = null,
                    usageComplete = false,
                ),
            )
            PendingTurn(id, userId, assistantId)
        }
    }

    suspend fun alternativeSource(conversationId: String, messageId: String): AlternativeSource =
        database.withTransaction {
            ensureTimeline(conversationId)
            loadTimeline(conversationId).source(conversationId, messageId)
        }

    suspend fun createAlternative(
        conversationId: String,
        request: AlternativeRequest,
        model: ChatModel,
        systemPrompt: String,
        expectedPathIds: List<String>,
    ): PendingTurn = database.withTransaction {
        val conversation = database.conversations().find(conversationId) ?: error("Conversation not found")
        ensureTimeline(conversationId)
        val timeline = loadTimeline(conversationId)
        val source = timeline.source(conversationId, request.messageId)
        check(source.expectedPathIds == expectedPathIds) { "Alternative preparation is stale" }
        check(database.messages().listForConversation(conversationId).none {
            it.role == Role.ASSISTANT && it.status in setOf(Status.PENDING, Status.STREAMING)
        }) { "Another assistant turn is still in progress" }

        val targetEntity = database.messages().find(request.messageId)
            ?: error("Message not found")
        val targetNode = database.branches().findNode(request.messageId)
            ?: error("Message has no timeline node")
        check(targetEntity.conversationId == conversationId) { "Message belongs to another conversation" }
        val prefix = source.prefix
        val notices = settingNotices(
            prefix = prefix,
            conversationId = conversationId,
            requestedModel = model,
            systemPrompt = systemPrompt,
        )
        val startedAt = nowMillis()
        var allocated = database.messages().latestCreatedAt(conversationId) ?: Long.MIN_VALUE
        fun allocateAt(): Long {
            allocated = nextTimestamp(startedAt, allocated)
            return allocated
        }
        val noticeRows = notices.map { notice ->
            val id = UUID.randomUUID().toString()
            val createdAt = allocateAt()
            MessageEntity(id, conversationId, notice.role, notice.content, notice.modelId, createdAt, Status.COMPLETE) to id
        }

        val userId: String
        val assistantId = UUID.randomUUID().toString()
        val anchorNodeId: String
        val parentNodeId: String
        val userEntity: MessageEntity?
        val sharedLinks: List<MessageAttachmentEntity>
        val retainedAttachments: List<ImageAttachment>
        when (request) {
            is AlternativeRequest.Edit -> {
                check(targetEntity.role == Role.USER) { "Edit targets must be user messages" }
                val attachmentLinks = database.messageAttachments().listForMessage(targetEntity.id)
                require(request.text.isNotBlank() || attachmentLinks.isNotEmpty()) {
                    "A turn needs text or at least one image"
                }
                parentNodeId = targetNode.parentId ?: error("The root cannot be edited")
                userId = UUID.randomUUID().toString()
                userEntity = MessageEntity(
                    userId,
                    conversationId,
                    Role.USER,
                    request.text,
                    null,
                    allocateAt(),
                    Status.USER_COMPLETE,
                )
                sharedLinks = attachmentLinks.map { it.copy(messageId = userId) }
                retainedAttachments = source.target.attachments
                anchorNodeId = userId
            }
            is AlternativeRequest.Retry -> {
                check(source.target.status != MessageState.FAILED_ZDR) {
                    "Failed ZDR messages are recovery targets only"
                }
                check(targetEntity.role == Role.ASSISTANT || targetEntity.role == Role.USER) {
                    "Retry targets must be conversational messages"
                }
                val user = if (targetEntity.role == Role.ASSISTANT) {
                    val replyTo = targetEntity.replyToMessageId
                        ?: error("Assistant target has no unambiguous user ancestor")
                    database.messages().find(replyTo)?.also {
                        check(it.role == Role.USER && it.conversationId == conversationId)
                    } ?: error("Assistant target has no unambiguous user ancestor")
                } else {
                    targetEntity
                }
                userId = user.id
                parentNodeId = if (targetEntity.role == Role.ASSISTANT) {
                    targetNode.parentId ?: error("Assistant target has no user parent")
                } else {
                    targetNode.id
                }
                userEntity = null
                sharedLinks = emptyList()
                retainedAttachments = timeline.messages.single { it.id == user.id }.attachments
                anchorNodeId = assistantId
            }
        }

        retainedAttachments.forEach { attachment ->
            AttachmentPolicy.requireAllowed(model.apiId, attachment.mimeType)
        }

        noticeRows.forEach { (row, _) -> database.messages().insert(row) }
        val userNode = if (userEntity != null) {
            TimelineNodeEntity(
                id = userId,
                conversationId = conversationId,
                parentId = parentNodeId,
                ordinal = allocateOrdinal(conversationId, parentNodeId),
            )
        } else {
            null
        }
        val assistantNode = TimelineNodeEntity(
            id = assistantId,
            conversationId = conversationId,
            parentId = if (userEntity != null) userId else parentNodeId,
            ordinal = allocateOrdinal(
                conversationId,
                if (userEntity != null) userId else parentNodeId,
            ),
        )
        userNode?.let(database.branches()::insertNode)
        database.branches().insertNode(assistantNode)
        userEntity?.let(database.messages()::insert)
        database.messages().insert(
            MessageEntity(
                assistantId,
                conversationId,
                Role.ASSISTANT,
                "",
                model.apiId,
                allocateAt(),
                Status.PENDING,
                userId,
            ),
        )
        userNode?.let {
            database.branches().insertPlacement(MessagePlacementEntity(userId, userId, 0))
            database.branches().select(BranchSelectionEntity(parentNodeId, userId))
        }
        database.branches().insertPlacement(MessagePlacementEntity(assistantId, assistantId, 0))
        database.branches().select(
            if (userNode == null) BranchSelectionEntity(parentNodeId, assistantId)
            else BranchSelectionEntity(userId, assistantId),
        )
        noticeRows.forEachIndexed { index, (_, id) ->
            val nodeId = anchorNodeId
            database.branches().insertPlacement(
                MessagePlacementEntity(id, nodeId, -noticeRows.size + index),
            )
        }
        if (sharedLinks.isNotEmpty()) database.messageAttachments().insert(sharedLinks)
        database.usageEvents().insert(
            UsageEventEntity(
                id = UUID.randomUUID().toString(),
                requestId = assistantId,
                conversationId = conversationId,
                modelId = model.apiId,
                timestamp = nextUsageTimestamp(conversationId, startedAt),
                inputTokens = null,
                cachedInputTokens = null,
                outputTokens = null,
                estimatedModelCost = null,
                estimatedGoatCredits = null,
                usageComplete = false,
            ),
        )
        database.conversations().touch(
            conversationId,
            nextTimestamp(startedAt, conversation.updatedAt),
        )
        PendingTurn(conversationId, userId, assistantId)
    }

    suspend fun selectAlternative(conversationId: String, messageId: String) = database.withTransaction {
        ensureTimeline(conversationId)
        val timeline = loadTimeline(conversationId)
        val path = timeline.selectedPath(conversationId)
        val node = database.branches().findNode(messageId)
            ?: error("Alternative message not found")
        check(node.conversationId == conversationId && node.parentId != null) {
            "Alternative does not belong to the conversation"
        }
        val siblings = database.branches().listChildren(conversationId, node.parentId)
        check(siblings.any { it.id == node.id }) { "Alternative is not a sibling choice" }
        if (path.lastOrNull()?.id != node.id) {
            database.branches().select(BranchSelectionEntity(requireNotNull(node.parentId), node.id))
            touchConversation(conversationId)
        }
    }

    suspend fun checkpointAssistant(messageId: String, text: String) = updateAssistant(messageId, text, Status.STREAMING)

    suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean =
        database.withTransaction {
            val assistant = assistant(messageId)
            if (assistant.status == Status.COMPLETE) return@withTransaction true
            if (assistant.status == Status.INTERRUPTED || assistant.status == MessageState.FAILED_ZDR) return@withTransaction false
            check(assistant.status == Status.PENDING || assistant.status == Status.STREAMING) { "Assistant turn is terminal" }
            if (database.messages().updateIfStatus(messageId, assistant.status, text, Status.COMPLETE) == 0) return@withTransaction false
            touchConversation(assistant.conversationId)
            check(
                database.usageEvents().complete(
                    requestId = messageId,
                    inputTokens = usage?.inputTokens,
                    cachedInputTokens = usage?.cachedInputTokens,
                    outputTokens = usage?.outputTokens,
                    estimatedModelCost = null,
                    estimatedGoatCredits = null,
                ) == 1,
            ) { "Usage anchor missing" }
            true
        }

    suspend fun isTurnComplete(messageId: String): Boolean = database.withTransaction {
        database.messages().find(messageId)?.status == Status.COMPLETE
    }

    suspend fun interruptTurn(messageId: String, partialText: String, reason: String, usage: TokenUsage? = null) {
        // The reason is intentionally not persisted: it may contain transport details or secrets.
        database.withTransaction {
            val message = assistant(messageId)
            if (message.status !in setOf(Status.PENDING, Status.STREAMING)) return@withTransaction
            if (database.messages().updateIfStatus(messageId, message.status, partialText, Status.INTERRUPTED) == 0) return@withTransaction
            check(database.usageEvents().recordInterrupted(messageId, usage?.inputTokens,
                usage?.cachedInputTokens, usage?.outputTokens) == 1) { "Usage anchor missing" }
            touchConversation(message.conversationId)
        }
    }

    suspend fun failZdrTurn(assistantMessageId: String, usage: TokenUsage? = null) = database.withTransaction {
        val reply = assistant(assistantMessageId)
        ensureTimeline(reply.conversationId)
        val timeline = loadTimeline(reply.conversationId)
        val selectedPath = timeline.selectedPath(reply.conversationId)
        val replyNode = database.branches().findNode(reply.id)
            ?: error("Assistant message has no timeline node")
        check(selectedPath.any { it.id == replyNode.id }) { "Assistant turn is not selected" }
        val userNodeId = replyNode.parentId ?: error("Assistant turn has no user parent")
        val user = database.messages().find(userNodeId) ?: error("Message not found")
        check(user.role == Role.USER && user.conversationId == reply.conversationId)
        check(database.branches().findSelection(userNodeId)?.selectedNodeId == replyNode.id) {
            "Assistant turn is not selected"
        }
        check(reply.content.isEmpty()) { "Cannot discard a partial response" }
        val failed = com.commandcode.chat.domain.MessageState.FAILED_ZDR
        if (reply.status == failed && user.status == failed) return@withTransaction
        check(reply.status in setOf(Status.PENDING, Status.STREAMING))
        check(database.messages().updateIfStatus(reply.id, reply.status, "", failed) == 1)
        check(database.messages().updateIfStatus(user.id, user.status, user.content, failed) == 1)
        check(database.usageEvents().recordInterrupted(reply.id, usage?.inputTokens,
            usage?.cachedInputTokens, usage?.outputTokens) == 1) { "Usage anchor missing" }
        touchConversation(user.conversationId)
    }

    suspend fun retryFailedTurn(userMessageId: String, model: ChatModel, systemPrompt: String = ""): PendingTurn = database.withTransaction {
        val failed = failedPair(userMessageId)
        val user = failed.user
        val reply = failed.reply
        val conversationId = user.conversationId
        val timeline = loadTimeline(conversationId)
        val oldNode = database.branches().findNode(reply.id) ?: error("Assistant has no timeline node")
        val parentNodeId = oldNode.parentId ?: error("Failed assistant has no user parent")
        val oldChildren = database.branches().listChildren(conversationId, oldNode.id)
        val oldChildSelection = database.branches().findSelection(oldNode.id)
        val parentSelection = database.branches().findSelection(parentNodeId)
        check(parentSelection?.selectedNodeId == oldNode.id) { "Failed assistant is not selected" }
        check(oldChildSelection == null || oldChildren.any { it.id == oldChildSelection.selectedNodeId }) {
            "Failed assistant continuation selection is invalid"
        }
        val oldPlacements = database.branches().listPlacementsForNode(oldNode.id)
        val localPlacements = oldPlacements.filter { it.messageId != reply.id }
        val leadingLocal = localPlacements.filter { it.position < 0 }
            .sortedWith(compareBy<MessagePlacementEntity> { it.position }.thenBy { it.messageId })
        val trailingLocal = localPlacements.filter { it.position > 0 }
            .sortedWith(compareBy<MessagePlacementEntity> { it.position }.thenBy { it.messageId })
        val localSettings = localPlacements
            .sortedWith(compareBy<MessagePlacementEntity> { it.position }.thenBy { it.messageId })
            .mapNotNull { placement -> timeline.messages.singleOrNull { it.id == placement.messageId } }
        val effective = effectivePrompt(systemPrompt)
        val notices = settingNotices(
            timeline.ancestorPrefix(conversationId, reply.id) + localSettings,
            conversationId,
            model,
            effective,
        )
        val startedAt = nowMillis()
        var allocated = database.messages().latestCreatedAt(conversationId) ?: Long.MIN_VALUE
        fun allocateAt(): Long {
            allocated = nextTimestamp(startedAt, allocated)
            return allocated
        }
        val noticeRows = notices.map { notice ->
            val id = UUID.randomUUID().toString()
            val createdAt = allocateAt()
            MessageEntity(id, conversationId, notice.role, notice.content, notice.modelId, createdAt, Status.COMPLETE) to id
        }
        val newId = UUID.randomUUID().toString()
        val oldOrdinal = oldNode.ordinal
        val temporaryOrdinal = allocateOrdinal(conversationId, parentNodeId)

        database.branches().updateOrdinal(oldNode.id, temporaryOrdinal)
        database.branches().insertNode(
            TimelineNodeEntity(newId, conversationId, parentNodeId, oldOrdinal),
        )
        database.messages().insert(
            MessageEntity(newId, conversationId, Role.ASSISTANT, "", model.apiId, reply.createdAt, Status.PENDING, user.id),
        )
        database.branches().deleteSelection(parentNodeId)
        database.branches().select(BranchSelectionEntity(parentNodeId, newId))
        database.branches().deleteSelection(oldNode.id)
        oldChildSelection?.let {
            database.branches().select(BranchSelectionEntity(newId, it.selectedNodeId))
        }
        oldChildren.forEach { child ->
            database.branches().moveNode(child.id, newId, child.ordinal)
        }
        noticeRows.forEach { (row, _) -> database.messages().insert(row) }
        database.branches().deletePlacement(reply.id)
        localPlacements.forEach { placement -> database.branches().deletePlacement(placement.messageId) }
        database.branches().insertPlacement(MessagePlacementEntity(newId, newId, 0))
        val leadingIds = leadingLocal.map { it.messageId }
        val leadingPlacementCount = leadingIds.size.toLong() + noticeRows.size.toLong()
        check(leadingPlacementCount <= Int.MAX_VALUE.toLong()) { "Timeline placement space exhausted" }
        leadingIds.forEachIndexed { index, messageId ->
            database.branches().insertPlacement(
                MessagePlacementEntity(messageId, newId, -leadingPlacementCount.toInt() + index),
            )
        }
        noticeRows.forEachIndexed { index, (_, noticeId) ->
            database.branches().insertPlacement(
                MessagePlacementEntity(noticeId, newId, -noticeRows.size + index),
            )
        }
        trailingLocal.forEachIndexed { index, placement ->
            database.branches().insertPlacement(
                MessagePlacementEntity(placement.messageId, newId, index + 1),
            )
        }
        database.messages().delete(reply)
        database.branches().deleteNode(oldNode.id)
        check(database.messages().updateIfStatus(user.id, user.status, user.content, Status.USER_COMPLETE) == 1)
        database.usageEvents().insert(UsageEventEntity(
            UUID.randomUUID().toString(), newId, conversationId, model.apiId, nextUsageTimestamp(conversationId, startedAt),
            null, null, null, null, null, false,
        ))
        touchConversation(conversationId)
        PendingTurn(conversationId, user.id, newId)
    }

    suspend fun deleteFailedTurn(userMessageId: String) = database.withTransaction {
        val failed = failedPair(userMessageId)
        val user = failed.user
        val reply = failed.reply
        val conversationId = user.conversationId
        val userNode = database.branches().findNode(user.id) ?: error("User has no timeline node")
        val replyNode = database.branches().findNode(reply.id) ?: error("Assistant has no timeline node")
        val responseSiblings = database.branches().listChildren(conversationId, userNode.id)
        val attachmentIds = database.messageAttachments().listForMessage(user.id).map { it.attachmentId }
        if (responseSiblings.size > 1) {
            database.messages().delete(reply)
            database.branches().markAnchorDeleted(replyNode.id, true)
            check(database.messages().updateIfStatus(user.id, user.status, user.content, Status.USER_COMPLETE) == 1)
        } else {
            spliceDeletedFailedTurn(
                user = user,
                reply = reply,
                userNode = userNode,
                replyNode = replyNode,
            )
        }
        if (attachmentIds.isNotEmpty()) database.attachments().deleteUnreferenced(attachmentIds)
        touchConversation(conversationId)
    }

    private fun failedPair(userMessageId: String): FailedPair {
        val user = database.messages().find(userMessageId) ?: error("Message not found")
        val failed = com.commandcode.chat.domain.MessageState.FAILED_ZDR
        check(user.role == Role.USER) { "Message is not a failed ZDR send" }
        ensureTimeline(user.conversationId)
        val timeline = loadTimeline(user.conversationId)
        val userNode = database.branches().findNode(user.id) ?: error("User has no timeline node")
        val selectedPath = timeline.selectedPath(user.conversationId)
        check(selectedPath.any { it.id == userNode.id }) { "Message is not on the selected path" }
        val selection = database.branches().findSelection(userNode.id)
            ?: error("Failed user has no selected response")
        val reply = database.messages().find(selection.selectedNodeId)
            ?: error("Selected response has no message")
        check(reply.role == Role.ASSISTANT && reply.conversationId == user.conversationId)
        check(reply.status == failed && reply.content.isEmpty()) { "Message is not a failed ZDR send" }
        check(user.status == failed || user.status == Status.USER_COMPLETE) {
            "Message is not a failed ZDR send"
        }
        return FailedPair(user, reply)
    }

    private fun spliceDeletedFailedTurn(
        user: MessageEntity,
        reply: MessageEntity,
        userNode: TimelineNodeEntity,
        replyNode: TimelineNodeEntity,
    ) {
        val parentId = userNode.parentId ?: error("Failed user has no parent")
        check(database.branches().findSelection(parentId)?.selectedNodeId == userNode.id) {
            "Failed user is not selected"
        }
        val continuations = database.branches().listChildren(user.conversationId, replyNode.id)
        val continuationSelection = database.branches().findSelection(replyNode.id)
        check(continuationSelection == null || continuations.any { it.id == continuationSelection.selectedNodeId }) {
            "Failed continuation selection is invalid"
        }
        val retainedNotices = listOf(userNode.id, replyNode.id)
            .flatMap { nodeId ->
                database.branches().listPlacementsForNode(nodeId)
                    .sortedWith(compareBy<MessagePlacementEntity> { it.position }.thenBy { it.messageId })
            }
            .filter { placement ->
                database.messages().find(placement.messageId)?.role !in setOf(Role.USER, Role.ASSISTANT)
            }
        val retainedNoticeEntities = retainedNotices.map { placement ->
            database.messages().find(placement.messageId)
                ?: error("Failed branch notice has no message")
        }
        val destinationId = continuationSelection?.selectedNodeId ?: parentId

        database.branches().deleteSelection(parentId)
        database.branches().deleteSelection(userNode.id)
        database.branches().deleteSelection(replyNode.id)
        continuations.forEach { continuation ->
            database.branches().moveNode(
                continuation.id,
                parentId,
                allocateOrdinal(user.conversationId, parentId),
            )
        }

        retainedNotices.forEach { placement -> database.branches().deletePlacement(placement.messageId) }
        if (continuations.isNotEmpty()) {
            continuations.forEach { continuation ->
                val noticeIds = retainedNoticeEntities.map { notice ->
                    val noticeId = UUID.randomUUID().toString()
                    database.messages().insert(notice.copy(id = noticeId))
                    noticeId
                }
                placeRetainedNotices(
                    nodeId = continuation.id,
                    messageIds = noticeIds,
                    beforeAnchor = true,
                )
            }
            retainedNoticeEntities.forEach(database.messages()::delete)
        } else {
            retainedNoticeEntities.forEach(database.messages()::delete)
        }
        database.messages().delete(reply)
        database.messages().delete(user)
        database.branches().deleteNode(replyNode.id)
        database.branches().deleteNode(userNode.id)

        val survivingChildren = database.branches().listChildren(user.conversationId, parentId)
        val selectedId = continuationSelection?.selectedNodeId
            ?.takeIf { id -> survivingChildren.any { it.id == id } }
            ?: survivingChildren.firstOrNull()?.id
        selectedId?.let { database.branches().select(BranchSelectionEntity(parentId, it)) }
    }

    private fun loadTimeline(conversationId: String): BranchTimeline = BranchTimeline(
        nodes = database.branches().listNodes(conversationId),
        placements = database.branches().listPlacements(conversationId),
        selections = database.branches().listSelections(conversationId),
        messages = database.messages().listWithAttachments(conversationId).map { it.toDomain() },
    )

    /** Creates the graph for a v4 conversation fixture that has not been read since migration. */
    private fun ensureTimeline(conversationId: String) {
        database.conversations().find(conversationId) ?: error("Conversation not found")
        val existingNodes = database.branches().listNodes(conversationId)
        val roots = existingNodes.filter { it.parentId == null }
        check(roots.size <= 1) { "Conversation has multiple timeline roots" }
        if (database.branches().findRoot(conversationId) != null) {
            check(roots.size == 1) { "Conversation has an invalid timeline root" }
            return
        }
        check(existingNodes.isEmpty()) { "Conversation has timeline nodes but no root" }

        val usedIds = linkedSetOf<String>().apply {
            addAll(database.messages().listIds())
            addAll(database.branches().listAllNodeIds())
        }
        val rootId = allocateRootId(conversationId, usedIds)
        database.branches().insertNode(TimelineNodeEntity(rootId, conversationId, null, 0))

        val pendingNotices = mutableListOf<String>()
        var parentId = rootId
        var lastAnchorId: String? = null
        database.messages().listForConversation(conversationId).forEach { message ->
            if (message.role != Role.USER && message.role != Role.ASSISTANT) {
                pendingNotices += message.id
                return@forEach
            }
            database.branches().insertNode(
                TimelineNodeEntity(message.id, conversationId, parentId, 0),
            )
            if (lastAnchorId == null) {
                pendingNotices.forEachIndexed { index, noticeId ->
                    database.branches().insertPlacement(MessagePlacementEntity(noticeId, rootId, index))
                }
            } else {
                pendingNotices.forEachIndexed { index, noticeId ->
                    database.branches().insertPlacement(
                        MessagePlacementEntity(noticeId, message.id, -pendingNotices.size + index),
                    )
                }
            }
            pendingNotices.clear()
            database.branches().insertPlacement(MessagePlacementEntity(message.id, message.id, 0))
            database.branches().select(BranchSelectionEntity(parentId, message.id))
            parentId = message.id
            lastAnchorId = message.id
        }
        val trailingNode = lastAnchorId ?: rootId
        pendingNotices.forEachIndexed { index, noticeId ->
            val position = if (lastAnchorId == null) index else index + 1
            database.branches().insertPlacement(MessagePlacementEntity(noticeId, trailingNode, position))
        }
    }

    private fun allocateRootId(conversationId: String, usedIds: MutableSet<String>): String {
        val preferred = "root:$conversationId"
        if (usedIds.add(preferred)) return preferred
        var suffix = 1
        while (suffix < Int.MAX_VALUE) {
            val candidate = "$preferred:$suffix"
            if (usedIds.add(candidate)) return candidate
            suffix++
        }
        error("No deterministic root ID is available for conversation $conversationId")
    }

    private fun allocateOrdinal(conversationId: String, parentId: String?): Int {
        val maximum = database.branches().maxOrdinal(conversationId, parentId) ?: -1
        check(maximum < Int.MAX_VALUE) { "Timeline ordinal space exhausted" }
        return maximum + 1
    }

    private fun nextLeadingPosition(nodeId: String): Int {
        val minimum = database.branches().listPlacementsForNode(nodeId).minOfOrNull { it.position }
        check(minimum == null || minimum > Int.MIN_VALUE) { "Timeline placement space exhausted" }
        return (minimum ?: 0) - 1
    }

    private fun nextTrailingPosition(nodeId: String): Int {
        val maximum = database.branches().listPlacementsForNode(nodeId).maxOfOrNull { it.position }
        check(maximum == null || maximum < Int.MAX_VALUE) { "Timeline placement space exhausted" }
        return (maximum ?: -1) + 1
    }

    private fun placeRetainedNotices(nodeId: String, messageIds: List<String>, beforeAnchor: Boolean) {
        if (messageIds.isEmpty()) return
        val existing = database.branches().listPlacementsForNode(nodeId)
        if (beforeAnchor) {
            val minimum = existing.minOfOrNull { it.position } ?: 0
            check(minimum.toLong() - messageIds.size >= Int.MIN_VALUE) { "Timeline placement space exhausted" }
            val first = minimum - messageIds.size
            messageIds.forEachIndexed { index, messageId ->
                database.branches().insertPlacement(MessagePlacementEntity(messageId, nodeId, first + index))
            }
        } else {
            var position = existing.maxOfOrNull { it.position } ?: -1
            messageIds.forEach { messageId ->
                check(position < Int.MAX_VALUE) { "Timeline placement space exhausted" }
                position++
                database.branches().insertPlacement(MessagePlacementEntity(messageId, nodeId, position))
            }
        }
    }

    private fun deleteGraph(conversationId: String) {
        val nodes = database.branches().listNodes(conversationId)
        fun depth(node: TimelineNodeEntity): Int {
            var current = node
            var result = 0
            val seen = mutableSetOf<String>()
            while (current.parentId != null) {
                check(seen.add(current.id)) { "Timeline contains a cycle" }
                current = nodes.singleOrNull { it.id == current.parentId }
                    ?: error("Timeline node has a missing parent")
                result++
            }
            return result
        }
        nodes.sortedByDescending(::depth).forEach { node ->
            database.branches().listSelectionsForNode(node.id)
                .map { it.parentNodeId }
                .distinct()
                .forEach(database.branches()::deleteSelection)
            database.branches().listPlacementsForNode(node.id)
                .forEach { placement -> database.branches().deletePlacement(placement.messageId) }
            database.branches().deleteNode(node.id)
        }
    }

    private fun settingNotices(
        prefix: List<Message>,
        conversationId: String,
        requestedModel: ChatModel,
        systemPrompt: String,
    ): List<NoticeSpec> {
        val modelEvidence = prefix.asReversed().firstNotNullOfOrNull { message ->
            when (message.role) {
                MessageState.INITIAL_MODEL, MessageState.MODEL_CHANGE, Role.ASSISTANT ->
                    message.modelId ?: message.content.takeIf {
                        message.role != Role.ASSISTANT && it.isNotBlank()
                    }
                else -> null
            }
        } ?: legacyModelEvidence(conversationId)
        val notices = mutableListOf<NoticeSpec>()
        if (modelEvidence != null && modelEvidence != requestedModel.apiId) {
            notices += NoticeSpec(MessageState.MODEL_CHANGE, requestedModel.displayName, requestedModel.apiId)
        }
        val fingerprint = promptFingerprint(effectivePrompt(systemPrompt))
        val promptEvidence = prefix.lastOrNull { it.role == MessageState.SYSTEM_PROMPT_CHANGE }?.content
        if (promptFingerprintChanged(promptEvidence, fingerprint)) {
            notices += NoticeSpec(MessageState.SYSTEM_PROMPT_CHANGE, fingerprint, null)
        }
        return notices
    }

    private fun legacyModelEvidence(conversationId: String): String? {
        val models = database.messages().listForConversation(conversationId)
            .filter { it.role == Role.ASSISTANT && it.modelId != null }
            .mapNotNull { it.modelId }
            .distinct()
        return models.singleOrNull()
    }

    suspend fun changeConversationModel(conversationId: String, model: ChatModel) = database.withTransaction {
        val conversation = database.conversations().find(conversationId) ?: error("Conversation not found")
        if (conversation.defaultModel == model.apiId) return@withTransaction
        ensureTimeline(conversationId)
        val timeline = loadTimeline(conversationId)
        val selectedPath = timeline.selectedPath(conversationId)
        val selectedLeaf = selectedPath.last()
        database.conversations().changeModel(conversationId, model.apiId)
        val history = timeline.selected(conversationId)
        if (history.any { it.role == Role.USER || it.role == Role.ASSISTANT }) {
            val eventId = UUID.randomUUID().toString()
            val at = nextTimestamp(nowMillis(), database.messages().latestCreatedAt(conversationId) ?: Long.MIN_VALUE)
            database.messages().insert(MessageEntity(
                eventId, conversationId, com.commandcode.chat.domain.MessageState.MODEL_CHANGE,
                model.displayName, model.apiId, at, Status.COMPLETE,
            ))
            database.branches().insertPlacement(
                MessagePlacementEntity(eventId, selectedLeaf.id, nextTrailingPosition(selectedLeaf.id)),
            )
        }
        touchConversation(conversationId)
    }

    /** Backfills an evidence-based starting-model notice for chats that predate it. */
    suspend fun backfillInitialModel(conversationId: String) {
        database.withTransaction {
            val conversation = database.conversations().find(conversationId) ?: error("Conversation not found")
            ensureTimeline(conversationId)
            val history = database.messages().listForConversation(conversationId)
            if (history.any { it.role == MessageState.INITIAL_MODEL }) return@withTransaction
            val evidence = history.firstOrNull { it.role == Role.ASSISTANT && it.modelId != null }
                ?: return@withTransaction
            val evidenceModelId = requireNotNull(evidence.modelId)
            // Place the notice at the very start of the timeline so it reads as the
            // chat's starting model, before the first conversational message.
            val earliest = history.minOf { it.createdAt }
            val eventAt = if (earliest > Long.MIN_VALUE) earliest - 1 else return@withTransaction
            val eventId = UUID.randomUUID().toString()
            database.messages().insert(
                MessageEntity(
                    id = eventId,
                    conversationId = conversation.id,
                    role = MessageState.INITIAL_MODEL,
                    content = evidenceModelId,
                    modelId = evidenceModelId,
                    createdAt = eventAt,
                    status = Status.COMPLETE,
                ),
            )
            val rootId = database.branches().listNodes(conversationId).single { it.parentId == null }.id
            database.branches().insertPlacement(
                MessagePlacementEntity(eventId, rootId, nextLeadingPosition(rootId)),
            )
        }
    }

    private suspend fun updateAssistant(messageId: String, text: String, status: String) {
        database.withTransaction {
            val message = assistant(messageId)
            if (message.status == Status.COMPLETE || message.status == Status.INTERRUPTED || message.status == MessageState.FAILED_ZDR) return@withTransaction
            if (database.messages().updateIfStatus(messageId, message.status, text, status) == 0) return@withTransaction
            touchConversation(message.conversationId)
        }
    }

    private fun touchConversation(id: String) {
        val conversation = database.conversations().find(id) ?: error("Conversation not found")
        database.conversations().touch(id, nextTimestamp(System.currentTimeMillis(), conversation.updatedAt))
    }

    private fun nextTimestamp(now: Long, previous: Long): Long {
        check(previous < Long.MAX_VALUE) { "Timestamp space exhausted" }
        return maxOf(now, previous + 1)
    }

    private fun nextUsageTimestamp(conversationId: String, now: Long): Long =
        nextTimestamp(now, database.usageEvents().latestTimestamp(conversationId) ?: Long.MIN_VALUE)

    private fun effectivePrompt(value: String): String = if (value.isBlank()) "" else value

    private fun promptFingerprint(effective: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(effective.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Absence of any recorded prompt event represents a previously empty prompt. */
    private fun promptFingerprintChanged(latest: String?, fingerprint: String): Boolean {
        val lastUsed = latest ?: Status.EMPTY_PROMPT_FINGERPRINT
        return lastUsed != fingerprint
    }

    private fun assistant(id: String): MessageEntity =
        database.messages().find(id)?.also { check(it.role == Role.ASSISTANT) { "Message is not an assistant turn" } }
            ?: error("Message not found")

    private fun requireOwnershipReuseAbsent(id: String) {
        require(!database.attachments().listIds().contains(id)) { "Attachment id is already owned by another message" }
    }

    private fun ConversationRow.toDomain() =
        Conversation(id, title, defaultModel, createdAt, updatedAt, textBytes, attachmentBytes, attachmentCount)
    private fun MessageWithAttachments.toDomain() = Message(id, conversationId, role, content, modelId, createdAt, status, attachments.map { it.toDomain() }, replyToMessageId)

    private fun titleFor(text: String): String {
        val normalised = buildString {
            var pendingSpace = false
            text.codePoints().forEach { codePoint ->
                val character = codePoint.toChar()
                val whitespace = Character.isWhitespace(character) || Character.isSpaceChar(character)
                if (whitespace) pendingSpace = isNotEmpty()
                else {
                    if (pendingSpace) append(' ')
                    appendCodePoint(codePoint)
                    pendingSpace = false
                }
            }
        }
        return normalised.codePoints().limit(48).toArray().let { String(it, 0, it.size) }
    }

    private data class FailedPair(
        val user: MessageEntity,
        val reply: MessageEntity,
    )

    private data class NoticeSpec(
        val role: String,
        val content: String,
        val modelId: String?,
    )

    private object Role {
        const val USER = "USER"
        const val ASSISTANT = "ASSISTANT"
    }
}
