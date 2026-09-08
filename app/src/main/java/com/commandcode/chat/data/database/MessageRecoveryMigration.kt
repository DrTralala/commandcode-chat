package com.commandcode.chat.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT")
        // Old versions always inserted a user and assistant together. Preserve their
        // association without guessing failure causes for historical interruptions.
        db.execSQL("""
            UPDATE messages SET replyToMessageId = (
                SELECT u.id FROM messages AS u
                WHERE u.conversationId = messages.conversationId AND u.role = 'USER'
                  AND (u.createdAt < messages.createdAt OR (u.createdAt = messages.createdAt AND u.id < messages.id))
                ORDER BY u.createdAt DESC, u.id DESC LIMIT 1
            ) WHERE role = 'ASSISTANT'
        """.trimIndent())
    }
}
