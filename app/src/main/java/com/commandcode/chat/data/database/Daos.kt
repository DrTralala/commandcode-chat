package com.commandcode.chat.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface ConversationDao {
    @Insert fun insert(value: ConversationEntity)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") fun find(id: String): ConversationEntity?
    @Delete fun delete(value: ConversationEntity)
}

@Dao interface MessageDao {
    @Insert fun insert(value: MessageEntity)
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC") fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE id = :id") fun find(id: String): MessageEntity?
    @Update fun update(value: MessageEntity)
}

@Dao interface UsageEventDao {
    @Insert fun insert(value: UsageEventEntity)
    @Query("SELECT * FROM usage_events WHERE conversationId = :conversationId ORDER BY timestamp ASC") fun observeForConversation(conversationId: String): Flow<List<UsageEventEntity>>
}
