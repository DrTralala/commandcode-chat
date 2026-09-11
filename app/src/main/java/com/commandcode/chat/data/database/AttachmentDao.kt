package com.commandcode.chat.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao interface AttachmentDao {
    @Query("SELECT id FROM attachments") fun listIds(): List<String>

    @Query(
        """
        SELECT * FROM attachments
        WHERE conversationId = :conversationId
        ORDER BY rowid ASC
        """,
    )
    fun listForConversation(conversationId: String): List<AttachmentEntity>

    @Query(
        """
        SELECT COUNT(*) FROM attachments
        WHERE conversationId = :conversationId
        """,
    )
    fun countForConversation(conversationId: String): Int

    @Query(
        "DELETE FROM attachments " +
            "WHERE id IN (:ids) AND id NOT IN (SELECT attachmentId FROM message_attachments)",
    )
    fun deleteUnreferenced(ids: List<String>): Int

    @Insert fun insert(values: List<AttachmentEntity>)
}
