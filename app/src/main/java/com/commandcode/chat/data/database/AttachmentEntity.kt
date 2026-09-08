package com.commandcode.chat.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commandcode.chat.domain.ImageAttachment

/**
 * Attachment metadata row owned by a message. The table SQL, unique index and
 * foreign key are defined exactly by the v1 -> v2 migration; this entity must
 * stay schema-identical to that migration SQL.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId", "position"], unique = true)],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val displayName: String,
    val mimeType: String,
    val originalBytes: Long,
    val encryptedBytes: Long,
    val width: Int,
    val height: Int,
    val position: Int,
) {
    fun toDomain(): ImageAttachment =
        ImageAttachment(id, displayName, mimeType, originalBytes, encryptedBytes, width, height)
}
