package com.commandcode.chat.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao interface ConversationDao {
    @Insert fun insert(value: ConversationEntity)
    @Query(
        """
        SELECT c.*,
         COALESCE((SELECT SUM(length(CAST(m.content AS BLOB))) FROM messages m WHERE m.conversationId=c.id),0) AS textBytes,
         COALESCE((SELECT SUM(a.encryptedBytes) FROM attachments a WHERE a.conversationId=c.id),0) AS attachmentBytes,
         (SELECT COUNT(*) FROM attachments a WHERE a.conversationId=c.id) AS attachmentCount
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

@Dao
abstract class MessageDao {
    @Insert
    abstract fun insert(value: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    abstract fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.id, m.conversationId, m.role, m.content, m.modelId, m.createdAt, m.status,
            m.replyToMessageId,
            a.id AS attachmentId,
            a.conversationId AS attachmentConversationId,
            a.displayName AS attachmentDisplayName,
            a.mimeType AS attachmentMimeType,
            a.originalBytes AS attachmentOriginalBytes,
            a.encryptedBytes AS attachmentEncryptedBytes,
            a.width AS attachmentWidth,
            a.height AS attachmentHeight,
            ma.position AS attachmentPosition
        FROM messages AS m
        LEFT JOIN message_attachments AS ma ON ma.messageId = m.id
        LEFT JOIN attachments AS a ON a.id = ma.attachmentId
        WHERE m.conversationId = :conversationId
        ORDER BY m.createdAt ASC, m.id ASC, ma.position ASC, a.id ASC
        """,
    )
    abstract fun observeWithAttachmentRows(conversationId: String): Flow<List<MessageWithAttachmentRow>>

    @Query(
        """
        SELECT m.id, m.conversationId, m.role, m.content, m.modelId, m.createdAt, m.status,
            m.replyToMessageId,
            a.id AS attachmentId,
            a.conversationId AS attachmentConversationId,
            a.displayName AS attachmentDisplayName,
            a.mimeType AS attachmentMimeType,
            a.originalBytes AS attachmentOriginalBytes,
            a.encryptedBytes AS attachmentEncryptedBytes,
            a.width AS attachmentWidth,
            a.height AS attachmentHeight,
            ma.position AS attachmentPosition
        FROM messages AS m
        LEFT JOIN message_attachments AS ma ON ma.messageId = m.id
        LEFT JOIN attachments AS a ON a.id = ma.attachmentId
        WHERE m.conversationId = :conversationId
        ORDER BY m.createdAt ASC, m.id ASC, ma.position ASC, a.id ASC
        """,
    )
    abstract fun listWithAttachmentRows(conversationId: String): List<MessageWithAttachmentRow>

    fun observeWithAttachments(conversationId: String): Flow<List<MessageWithAttachments>> =
        observeWithAttachmentRows(conversationId).map(::messageGroups)

    fun listWithAttachments(conversationId: String): List<MessageWithAttachments> =
        messageGroups(listWithAttachmentRows(conversationId))

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    abstract fun listForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract fun find(id: String): MessageEntity?

    @Query("SELECT id FROM messages")
    abstract fun listIds(): List<String>

    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :conversationId")
    abstract fun latestCreatedAt(conversationId: String): Long?

    @Query("UPDATE messages SET content = :content, status = :newStatus WHERE id = :id AND status = :expectedStatus")
    abstract fun updateIfStatus(id: String, expectedStatus: String, content: String, newStatus: String): Int

    @Query("UPDATE messages SET status = :newStatus WHERE id = :id AND status = :expectedStatus")
    abstract fun updateStatusIf(id: String, expectedStatus: String, newStatus: String): Int

    @Delete
    abstract fun delete(value: MessageEntity)

    @Query("SELECT * FROM messages WHERE replyToMessageId = :userMessageId AND role = 'ASSISTANT'")
    abstract fun replies(userMessageId: String): List<MessageEntity>
}

data class MessageWithAttachmentRow(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val modelId: String?,
    val createdAt: Long,
    val status: String,
    val replyToMessageId: String?,
    val attachmentId: String?,
    val attachmentConversationId: String?,
    val attachmentDisplayName: String?,
    val attachmentMimeType: String?,
    val attachmentOriginalBytes: Long?,
    val attachmentEncryptedBytes: Long?,
    val attachmentWidth: Int?,
    val attachmentHeight: Int?,
    val attachmentPosition: Int?,
)

/** A message with its ordered attachment metadata, loaded from link rows. */
data class MessageWithAttachments(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val modelId: String?,
    val createdAt: Long,
    val status: String,
    val attachments: List<MessageAttachmentProjection>,
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

data class MessageAttachmentProjection(
    val id: String,
    val conversationId: String,
    val displayName: String,
    val mimeType: String,
    val originalBytes: Long,
    val encryptedBytes: Long,
    val width: Int,
    val height: Int,
    val position: Int,
) {
    fun toDomain() = com.commandcode.chat.domain.ImageAttachment(
        id,
        displayName,
        mimeType,
        originalBytes,
        encryptedBytes,
        width,
        height,
    )
}

private fun messageGroups(rows: List<MessageWithAttachmentRow>): List<MessageWithAttachments> {
    val grouped = linkedMapOf<String, MutableList<MessageWithAttachmentRow>>()
    rows.forEach { row -> grouped.getOrPut(row.id) { mutableListOf() } += row }
    return grouped.values.map { messageRows ->
        val first = messageRows.first()
        MessageWithAttachments(
            id = first.id,
            conversationId = first.conversationId,
            role = first.role,
            content = first.content,
            modelId = first.modelId,
            createdAt = first.createdAt,
            status = first.status,
            attachments = messageRows.mapNotNull { row ->
                row.attachmentId?.let {
                    MessageAttachmentProjection(
                        id = it,
                        conversationId = requireNotNull(row.attachmentConversationId),
                        displayName = requireNotNull(row.attachmentDisplayName),
                        mimeType = requireNotNull(row.attachmentMimeType),
                        originalBytes = requireNotNull(row.attachmentOriginalBytes),
                        encryptedBytes = requireNotNull(row.attachmentEncryptedBytes),
                        width = requireNotNull(row.attachmentWidth),
                        height = requireNotNull(row.attachmentHeight),
                        position = requireNotNull(row.attachmentPosition),
                    )
                }
            },
            replyToMessageId = first.replyToMessageId,
        )
    }
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
    @Query("SELECT * FROM usage_events WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun listForConversation(conversationId: String): List<UsageEventEntity>
}
