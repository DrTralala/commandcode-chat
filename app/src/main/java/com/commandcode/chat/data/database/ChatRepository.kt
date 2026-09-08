package com.commandcode.chat.data.database

import androidx.room.withTransaction
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import kotlinx.coroutines.flow.Flow
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
    }
    private val IMAGE_CHAT_TITLE: String = "Image chat"

    fun observeConversations(): Flow<List<Conversation>> = database.conversations().observeAll()
        .map { rows -> rows.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        database.messages().observeWithAttachments(conversationId)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun messagesSnapshot(conversationId: String): List<Message> = database.withTransaction {
        database.messages().listWithAttachments(conversationId).map { it.toDomain() }
    }

    suspend fun deleteConversation(id: String) {
        database.withTransaction {
            database.conversations().find(id)?.let(database.conversations()::delete)
        }
    }

    suspend fun beginTurn(
        conversationId: String?,
        text: String,
        model: ChatModel,
        attachments: List<ImageAttachment> = emptyList(),
    ): PendingTurn {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "A turn needs text or at least one image" }
        val ids = attachments.map { it.id }
        require(ids.size == ids.toSet().size) { "Attachment ids must be unique within a turn" }
        val title = if (text.isBlank()) IMAGE_CHAT_TITLE else titleFor(text)
        val turnStartedAt = nowMillis()
        return database.withTransaction {
            val id = conversationId ?: UUID.randomUUID().toString()
            val userAt = nextTimestamp(turnStartedAt, database.messages().latestCreatedAt(id) ?: Long.MIN_VALUE)
            val assistantAt = nextTimestamp(userAt, userAt)
            if (conversationId == null) {
                database.conversations().insert(
                    ConversationEntity(id, title, model.apiId, userAt, assistantAt),
                )
            } else {
                val conversation = database.conversations().find(id) ?: error("Conversation not found")
                database.conversations().touch(id, maxOf(assistantAt, nextTimestamp(userAt, conversation.updatedAt)))
            }
            attachments.forEach { requireOwnershipReuseAbsent(it.id) }
            attachments.forEach { AttachmentPolicy.requireAllowed(model.apiId, it.mimeType) }
            AttachmentPolicy.requireCount(database.attachments().countForConversation(id), 0, attachments.size)
            val userId = UUID.randomUUID().toString()
            val assistantId = UUID.randomUUID().toString()
            database.messages().insert(MessageEntity(userId, id, Role.USER, text, null, userAt, Status.USER_COMPLETE))
            database.messages().insert(MessageEntity(assistantId, id, Role.ASSISTANT, "", model.apiId, assistantAt, Status.PENDING))
            if (attachments.isNotEmpty()) {
                database.attachments().insert(
                    attachments.mapIndexed { position, image ->
                        AttachmentEntity(
                            id = image.id,
                            messageId = userId,
                            displayName = image.displayName,
                            mimeType = image.mimeType,
                            originalBytes = image.originalBytes,
                            encryptedBytes = image.encryptedBytes,
                            width = image.width,
                            height = image.height,
                            position = position,
                        )
                    },
                )
            }
            database.usageEvents().insert(
                UsageEventEntity(
                    id = UUID.randomUUID().toString(),
                    requestId = assistantId,
                    conversationId = id,
                    modelId = model.apiId,
                    timestamp = turnStartedAt,
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

    suspend fun checkpointAssistant(messageId: String, text: String) = updateAssistant(messageId, text, Status.STREAMING)

    suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean =
        database.withTransaction {
            val assistant = assistant(messageId)
            if (assistant.status == Status.COMPLETE) return@withTransaction true
            if (assistant.status == Status.INTERRUPTED) return@withTransaction false
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

    suspend fun interruptTurn(messageId: String, partialText: String, reason: String) {
        // The reason is intentionally not persisted: it may contain transport details or secrets.
        updateAssistant(messageId, partialText, Status.INTERRUPTED)
    }

    private suspend fun updateAssistant(messageId: String, text: String, status: String) {
        database.withTransaction {
            val message = assistant(messageId)
            if (message.status == Status.COMPLETE || message.status == Status.INTERRUPTED) return@withTransaction
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

    private fun assistant(id: String): MessageEntity =
        database.messages().find(id)?.also { check(it.role == Role.ASSISTANT) { "Message is not an assistant turn" } }
            ?: error("Message not found")

    private fun requireOwnershipReuseAbsent(id: String) {
        require(!database.attachments().listIds().contains(id)) { "Attachment id is already owned by another message" }
    }

    private fun ConversationRow.toDomain() =
        Conversation(id, title, defaultModel, createdAt, updatedAt, textBytes, attachmentBytes, attachmentCount)
    private fun MessageWithAttachments.toDomain() = Message(id, conversationId, role, content, modelId, createdAt, status, attachments.map { it.toDomain() })

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

    private object Role {
        const val USER = "USER"
        const val ASSISTANT = "ASSISTANT"
    }
}
