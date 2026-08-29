package com.commandcode.chat.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface ConversationDao {
    @Insert fun insert(value: ConversationEntity)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id ASC") fun observeAll(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") fun find(id: String): ConversationEntity?
    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id") fun touch(id: String, updatedAt: Long)
    @Delete fun delete(value: ConversationEntity)
}

@Dao interface MessageDao {
    @Insert fun insert(value: MessageEntity)
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC") fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC") fun listForConversation(conversationId: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id = :id") fun find(id: String): MessageEntity?
    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :conversationId") fun latestCreatedAt(conversationId: String): Long?
    @Query("UPDATE messages SET content = :content, status = :newStatus WHERE id = :id AND status = :expectedStatus")
    fun updateIfStatus(id: String, expectedStatus: String, content: String, newStatus: String): Int
}

@Dao interface UsageEventDao {
    @Insert fun insert(value: UsageEventEntity)
    @Query("SELECT * FROM usage_events ORDER BY timestamp ASC, id ASC") fun observeAll(): Flow<List<UsageEventEntity>>
    @Query("SELECT * FROM usage_events WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC") fun observeForConversation(conversationId: String): Flow<List<UsageEventEntity>>
}
