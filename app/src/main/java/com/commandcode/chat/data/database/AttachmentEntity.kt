package com.commandcode.chat.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commandcode.chat.domain.ImageAttachment

/**
 * Physical attachment metadata owned by a conversation. Message ownership is
 * represented by [MessageAttachmentEntity] so multiple branch messages can
 * share one encrypted file.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId"])],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val displayName: String,
    val mimeType: String,
    val originalBytes: Long,
    val encryptedBytes: Long,
    val width: Int,
    val height: Int,
) {
    fun toDomain(): ImageAttachment =
        ImageAttachment(id, displayName, mimeType, originalBytes, encryptedBytes, width, height)
}
