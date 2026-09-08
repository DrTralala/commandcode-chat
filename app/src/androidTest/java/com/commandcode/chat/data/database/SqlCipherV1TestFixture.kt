package com.commandcode.chat.data.database

import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Creates a real SQLCipher v1 database from the exported Room v1 schema. This is
 * deliberately separate from Room so the production v1 -> v2 migration is exercised.
 */
internal object SqlCipherV1TestFixture {
    const val CONVERSATION_ID = "task11-v1-conversation"
    const val USER_MESSAGE_ID = "task11-v1-user"
    const val ASSISTANT_MESSAGE_ID = "task11-v1-assistant"
    const val USAGE_ID = "task11-v1-usage"

    fun create(file: File, passphrase: ByteArray) {
        System.loadLibrary("sqlcipher")
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openDatabase(
            file.path,
            passphrase,
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
        )
        try {
            // SQL copied from app/schemas/.../ChatDatabase/1.json, with Room's
            // ${TABLE_NAME} placeholder replaced by each exported table name.
            EXPORTED_V1_SCHEMA_SQL.forEach(database::execSQL)
            database.execSQL(
                "INSERT INTO conversations (id, title, defaultModel, createdAt, updatedAt) " +
                    "VALUES ('$CONVERSATION_ID', 'Migrated image history', 'gpt-5.6-sol', 1000, 2000)",
            )
            database.execSQL(
                "INSERT INTO messages (id, conversationId, role, content, modelId, createdAt, status) " +
                    "VALUES " +
                    "('$USER_MESSAGE_ID', '$CONVERSATION_ID', 'USER', 'v1 prompt', NULL, 3000, 'COMPLETE'), " +
                    "('$ASSISTANT_MESSAGE_ID', '$CONVERSATION_ID', 'ASSISTANT', 'v1 answer', 'gpt-5.6-sol', 4000, 'COMPLETE')",
            )
            database.execSQL(
                "INSERT INTO usage_events (id, requestId, conversationId, modelId, timestamp, inputTokens, " +
                    "cachedInputTokens, outputTokens, estimatedModelCost, estimatedGoatCredits, usageComplete) " +
                    "VALUES ('$USAGE_ID', '$ASSISTANT_MESSAGE_ID', '$CONVERSATION_ID', 'gpt-5.6-luna', " +
                    "5000, 10, 2, 7, '0.0001', '0.0002', 1)",
            )
            database.version = 1
        } finally {
            database.close()
        }
    }

    private val EXPORTED_V1_SCHEMA_SQL = listOf(
        "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `defaultModel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_conversations_updatedAt` ON `conversations` (`updatedAt`)",
        "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `modelId` TEXT, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `messages` (`conversationId`, `createdAt`)",
        "CREATE TABLE IF NOT EXISTS `usage_events` (`id` TEXT NOT NULL, `requestId` TEXT, `conversationId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `inputTokens` INTEGER, `cachedInputTokens` INTEGER, `outputTokens` INTEGER, `estimatedModelCost` TEXT, `estimatedGoatCredits` TEXT, `usageComplete` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_usage_events_conversationId_timestamp` ON `usage_events` (`conversationId`, `timestamp`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e031ddea5e8fc2979c4c58f8e05cdc8a')",
    )
}
