package com.commandcode.chat.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao interface AttachmentDao {
    @Query("SELECT id FROM attachments") fun listIds(): List<String>

    @Query(
        """
        SELECT a.* FROM attachments a
        JOIN messages m ON m.id = a.messageId
        WHERE m.conversationId = :conversationId
        ORDER BY m.createdAt ASC, m.id ASC, a.position ASC, a.id ASC
        """,
    )
    fun listForConversation(conversationId: String): List<AttachmentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM attachments a
        JOIN messages m ON m.id = a.messageId
        WHERE m.conversationId = :conversationId
        """,
    )
    fun countForConversation(conversationId: String): Int

    @Insert fun insert(values: List<AttachmentEntity>)
}
