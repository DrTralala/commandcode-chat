package com.commandcode.chat.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val defaultModel: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "messages", indices = [Index("conversationId", "createdAt")], foreignKeys = [androidx.room.ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = androidx.room.ForeignKey.CASCADE)])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val role: String, val content: String, val modelId: String?, val createdAt: Long, val status: String)

@Entity(tableName = "usage_events", indices = [Index("conversationId", "timestamp")], foreignKeys = [androidx.room.ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = androidx.room.ForeignKey.CASCADE)])
data class UsageEventEntity(@PrimaryKey val id: String, val requestId: String?, val conversationId: String, val modelId: String, val timestamp: Long, val inputTokens: Long?, val cachedInputTokens: Long?, val outputTokens: Long?, val estimatedModelCost: String?, val estimatedGoatCredits: String?, val usageComplete: Boolean)
