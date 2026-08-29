package com.commandcode.chat.data.database

import androidx.room.withTransaction
import com.commandcode.chat.data.budget.BudgetCalculator
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    fun observeConversations(): Flow<List<Conversation>> = database.conversations().observeAll()
        .map { rows -> rows.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        database.messages().observeForConversation(conversationId)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel): PendingTurn =
        database.withTransaction {
            val now = System.currentTimeMillis()
            val id = conversationId ?: UUID.randomUUID().toString()
            if (conversationId == null) {
                database.conversations().insert(
                    ConversationEntity(id, titleFor(text), model.apiId, now, now + 1),
                )
            } else {
                val conversation = database.conversations().find(id) ?: error("Conversation not found")
                database.conversations().touch(id, nextTimestamp(now, conversation.updatedAt))
            }
            val userId = UUID.randomUUID().toString()
            val assistantId = UUID.randomUUID().toString()
            database.messages().insert(MessageEntity(userId, id, "USER", text, null, now, "COMPLETE"))
            database.messages().insert(MessageEntity(assistantId, id, "ASSISTANT", "", model.apiId, now + 1, "PENDING"))
            PendingTurn(id, userId, assistantId)
        }

    suspend fun checkpointAssistant(messageId: String, text: String) = updateAssistant(messageId, text, "STREAMING")

    suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?) {
        database.withTransaction {
            val assistant = assistant(messageId)
            database.messages().update(assistant.copy(content = text, status = "COMPLETE"))
            touchConversation(assistant.conversationId)
            val tokenUsage = usage
            val estimate = tokenUsage?.let { BudgetCalculator.estimate(requireModel(assistant), it) }
            if (tokenUsage != null) {
                database.usageEvents().insert(
                    UsageEventEntity(
                        id = UUID.randomUUID().toString(), requestId = null,
                        conversationId = assistant.conversationId, modelId = assistant.modelId!!,
                        timestamp = System.currentTimeMillis(), inputTokens = tokenUsage.inputTokens,
                        cachedInputTokens = tokenUsage.cachedInputTokens, outputTokens = tokenUsage.outputTokens,
                        estimatedModelCost = estimate!!.modelCost.toPlainString(),
                        estimatedGoatCredits = estimate.goatCredits.toPlainString(), usageComplete = true,
                    ),
                )
            }
        }
    }

    suspend fun interruptTurn(messageId: String, partialText: String, reason: String) {
        // The reason is intentionally not persisted: it may contain transport details or secrets.
        updateAssistant(messageId, partialText, "INTERRUPTED")
    }

    private suspend fun updateAssistant(messageId: String, text: String, status: String) {
        database.withTransaction {
            val message = assistant(messageId)
            database.messages().update(message.copy(content = text, status = status))
            touchConversation(message.conversationId)
        }
    }

    private fun touchConversation(id: String) {
        val conversation = database.conversations().find(id) ?: error("Conversation not found")
        database.conversations().touch(id, nextTimestamp(System.currentTimeMillis(), conversation.updatedAt))
    }

    private fun nextTimestamp(now: Long, previous: Long): Long = maxOf(now, previous + 1)

    private fun assistant(id: String): MessageEntity =
        database.messages().find(id)?.also { check(it.role == "ASSISTANT") { "Message is not an assistant turn" } }
            ?: error("Message not found")

    private fun requireModel(message: MessageEntity): ChatModel =
        message.modelId?.let(ChatModel::fromApiId) ?: error("Assistant model missing")

    private fun ConversationEntity.toDomain() = Conversation(id, title, ChatModel.fromApiId(defaultModel)!!, createdAt, updatedAt)
    private fun MessageEntity.toDomain() = Message(id, conversationId, role, content, modelId?.let(ChatModel::fromApiId), createdAt, status)

    private fun titleFor(text: String): String {
        val normalised = text.trim().replace(Regex("\\s+"), " ")
        return normalised.codePoints().limit(48).toArray().let { String(it, 0, it.size) }
    }
}
