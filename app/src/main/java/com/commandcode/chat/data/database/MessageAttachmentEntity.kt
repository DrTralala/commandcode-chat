package com.commandcode.chat.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query

@Entity(
    tableName = "message_attachments",
    primaryKeys = ["messageId", "attachmentId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageId", "position"], unique = true),
        Index(value = ["attachmentId"]),
    ],
)
data class MessageAttachmentEntity(
    val messageId: String,
    val attachmentId: String,
    val position: Int,
)

@Dao
interface MessageAttachmentDao {
    @Insert
    fun insert(values: List<MessageAttachmentEntity>)

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY position ASC, attachmentId ASC")
    fun listForMessage(messageId: String): List<MessageAttachmentEntity>
}
