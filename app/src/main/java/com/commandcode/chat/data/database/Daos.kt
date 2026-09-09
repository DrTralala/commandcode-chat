package com.commandcode.chat.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface ConversationDao {
    @Insert fun insert(value: ConversationEntity)
    @Query(
        """
        SELECT c.*,
         COALESCE((SELECT SUM(length(CAST(m.content AS BLOB))) FROM messages m WHERE m.conversationId=c.id),0) AS textBytes,
         COALESCE((SELECT SUM(a.encryptedBytes) FROM attachments a JOIN messages m ON m.id=a.messageId WHERE m.conversationId=c.id),0) AS attachmentBytes,
         (SELECT COUNT(*) FROM attachments a JOIN messages m ON m.id=a.messageId WHERE m.conversationId=c.id) AS attachmentCount
        FROM conversations c ORDER BY c.updatedAt DESC, c.id ASC
        """,
    )
    fun observeAll(): Flow<List<ConversationRow>>
    @Query("SELECT * FROM conversations WHERE id = :id") fun find(id: String): ConversationEntity?
    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id") fun touch(id: String, updatedAt: Long)
    @Delete fun delete(value: ConversationEntity)
    @Query("UPDATE conversations SET defaultModel = :modelId WHERE id = :id")
    fun changeModel(id: String, modelId: String)
}

/** Projection row backing [Conversation] with reactive size fields. */
data class ConversationRow(
    val id: String,
    val title: String,
    val defaultModel: String,
    val createdAt: Long,
    val updatedAt: Long,
    val textBytes: Long,
    val attachmentBytes: Long,
    val attachmentCount: Int,
) {
    fun toDomain(): Conversation =
        Conversation(id, title, defaultModel, createdAt, updatedAt, textBytes, attachmentBytes, attachmentCount)
}

@Dao interface MessageDao {
    @Insert fun insert(value: MessageEntity)
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>
    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeWithAttachments(conversationId: String): Flow<List<MessageWithAttachments>>
    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun listWithAttachments(conversationId: String): List<MessageWithAttachments>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun listForConversation(conversationId: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id = :id") fun find(id: String): MessageEntity?
    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :conversationId") fun latestCreatedAt(conversationId: String): Long?
    @Query("UPDATE messages SET content = :content, status = :newStatus WHERE id = :id AND status = :expectedStatus")
    fun updateIfStatus(id: String, expectedStatus: String, content: String, newStatus: String): Int
    @Delete fun delete(value: MessageEntity)
    @Query("SELECT * FROM messages WHERE replyToMessageId = :userMessageId AND role = 'ASSISTANT'")
    fun replies(userMessageId: String): List<MessageEntity>
}

/** A message with its ordered attachment metadata, loaded in one Room transaction. */
data class MessageWithAttachments(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val modelId: String?,
    val createdAt: Long,
    val status: String,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AttachmentEntity>,
    val replyToMessageId: String? = null,
) {
    fun toDomain(): Message =
        Message(
            id,
            conversationId,
            role,
            content,
            modelId,
            createdAt,
            status,
            attachments.sortedWith(compareBy({ it.position }, { it.id })).map { it.toDomain() },
            replyToMessageId,
        )
}

@Dao interface UsageEventDao {
    @Insert fun insert(value: UsageEventEntity)
    @Query("SELECT MAX(timestamp) FROM usage_events WHERE conversationId = :conversationId")
    fun latestTimestamp(conversationId: String): Long?
    @Query("""
        UPDATE usage_events SET inputTokens = :inputTokens,
            cachedInputTokens = :cachedInputTokens, outputTokens = :outputTokens
        WHERE requestId = :requestId AND usageComplete = 0
    """)
    fun recordInterrupted(requestId: String, inputTokens: Long?, cachedInputTokens: Long?, outputTokens: Long?): Int
    @Query("""
        UPDATE usage_events SET
            inputTokens = :inputTokens,
            cachedInputTokens = :cachedInputTokens,
            outputTokens = :outputTokens,
            estimatedModelCost = :estimatedModelCost,
            estimatedGoatCredits = :estimatedGoatCredits,
            usageComplete = 1
        WHERE requestId = :requestId AND usageComplete = 0
    """)
    fun complete(
        requestId: String,
        inputTokens: Long?,
        cachedInputTokens: Long?,
        outputTokens: Long?,
        estimatedModelCost: String?,
        estimatedGoatCredits: String?,
    ): Int
    @Query("SELECT * FROM usage_events ORDER BY timestamp ASC, id ASC") fun observeAll(): Flow<List<UsageEventEntity>>
    @Query("SELECT * FROM usage_events WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC") fun observeForConversation(conversationId: String): Flow<List<UsageEventEntity>>
}
