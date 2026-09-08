package com.commandcode.chat.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Non-destructive v1 -> v2 migration: adds the attachment metadata table, its
 * unique per-message/per-position index and its FK to messages with ON DELETE
 * CASCADE. Existing conversations/messages/usage and SQLCipher key material
 * are untouched.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS attachments (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "messageId TEXT NOT NULL, " +
                "displayName TEXT NOT NULL, " +
                "mimeType TEXT NOT NULL, " +
                "originalBytes INTEGER NOT NULL, " +
                "encryptedBytes INTEGER NOT NULL, " +
                "width INTEGER NOT NULL, " +
                "height INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_attachments_messageId_position " +
                "ON attachments(messageId, position)",
        )
    }
}
