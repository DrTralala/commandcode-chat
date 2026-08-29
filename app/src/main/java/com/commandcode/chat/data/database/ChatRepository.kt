package com.commandcode.chat.data.database

import androidx.room.withTransaction
import com.commandcode.chat.data.budget.BudgetCalculator
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Conversation(
    val id: String,
    val title: String,
    val defaultModel: ChatModel,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Message(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val model: ChatModel?,
    val createdAt: Long,
    val status: String,
)

data class PendingTurn(
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String,
)

class ChatRepository(private val database: ChatDatabase) {
    private object Status {
        const val USER_COMPLETE = "COMPLETE"
        const val PENDING = "PENDING"
        const val STREAMING = "STREAMING"
        const val COMPLETE = "COMPLETE"
        const val INTERRUPTED = "INTERRUPTED"
    }
    fun observeConversations(): Flow<List<Conversation>> = database.conversations().observeAll()
        .map { rows -> rows.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        database.messages().observeForConversation(conversationId)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun messagesSnapshot(conversationId: String): List<Message> = database.withTransaction {
        database.messages().listForConversation(conversationId).map { it.toDomain() }
    }

    fun observeUsageEvents(): Flow<List<UsageEvent>> = database.usageEvents().observeAll()
        .map { rows -> rows.map { it.toDomain() } }

    suspend fun deleteConversation(id: String) {
        database.withTransaction {
            database.conversations().find(id)?.let(database.conversations()::delete)
        }
    }

    suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel): PendingTurn =
        database.withTransaction {
            val id = conversationId ?: UUID.randomUUID().toString()
            val userAt = nextTimestamp(System.currentTimeMillis(), database.messages().latestCreatedAt(id) ?: Long.MIN_VALUE)
            val assistantAt = nextTimestamp(userAt, userAt)
            if (conversationId == null) {
                database.conversations().insert(
                    ConversationEntity(id, titleFor(text), model.apiId, userAt, assistantAt),
                )
            } else {
                val conversation = database.conversations().find(id) ?: error("Conversation not found")
                database.conversations().touch(id, maxOf(assistantAt, nextTimestamp(userAt, conversation.updatedAt)))
            }
            val userId = UUID.randomUUID().toString()
            val assistantId = UUID.randomUUID().toString()
            database.messages().insert(MessageEntity(userId, id, Role.USER, text, null, userAt, Status.USER_COMPLETE))
            database.messages().insert(MessageEntity(assistantId, id, Role.ASSISTANT, "", model.apiId, assistantAt, Status.PENDING))
            PendingTurn(id, userId, assistantId)
        }

    suspend fun checkpointAssistant(messageId: String, text: String) = updateAssistant(messageId, text, Status.STREAMING)

    suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?) {
        database.withTransaction {
            val assistant = assistant(messageId)
            if (assistant.status == Status.COMPLETE || assistant.status == Status.INTERRUPTED) return@withTransaction
            check(assistant.status == Status.PENDING || assistant.status == Status.STREAMING) { "Assistant turn is terminal" }
            if (usage == null) {
                if (database.messages().updateIfStatus(messageId, assistant.status, text, Status.INTERRUPTED) != 0) {
                    touchConversation(assistant.conversationId)
                }
                return@withTransaction
            }
            if (database.messages().updateIfStatus(messageId, assistant.status, text, Status.COMPLETE) == 0) return@withTransaction
            touchConversation(assistant.conversationId)
            val estimate = BudgetCalculator.estimate(requireModel(assistant), usage)
            database.usageEvents().insert(
                UsageEventEntity(
                    id = UUID.randomUUID().toString(), requestId = messageId,
                    conversationId = assistant.conversationId, modelId = assistant.modelId!!,
                    timestamp = System.currentTimeMillis(), inputTokens = usage.inputTokens,
                    cachedInputTokens = usage.cachedInputTokens, outputTokens = usage.outputTokens,
                    estimatedModelCost = estimate.modelCost.toPlainString(),
                    estimatedGoatCredits = estimate.goatCredits.toPlainString(), usageComplete = true,
                ),
            )
        }
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

    private fun requireModel(message: MessageEntity): ChatModel =
        message.modelId?.let(ChatModel::fromApiId) ?: error("Assistant model missing")

    private fun ConversationEntity.toDomain() = Conversation(id, title, ChatModel.fromApiId(defaultModel)!!, createdAt, updatedAt)
    private fun MessageEntity.toDomain() = Message(id, conversationId, role, content, modelId?.let(ChatModel::fromApiId), createdAt, status)
    private fun UsageEventEntity.toDomain() = UsageEvent(
        id = id,
        model = ChatModel.fromApiId(modelId) ?: error("Unknown stored model"),
        timestamp = Instant.ofEpochMilli(timestamp),
        usage = if (usageComplete && inputTokens != null && outputTokens != null) {
            TokenUsage(inputTokens, cachedInputTokens, outputTokens)
        } else null,
        estimatedModelCost = estimatedModelCost?.let(::BigDecimal),
        estimatedGoatCredits = estimatedGoatCredits?.let(::BigDecimal),
    )

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
