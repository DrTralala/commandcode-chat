package com.commandcode.chat.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real non-destructive v1 -> v2 migration coverage. A v1 database is built by
 * executing the exact v1 schema SQL through the framework SQLite helper, seeded
 * with conversation/message/usage fixture values and closed. Opening it through
 * Room with [MIGRATION_1_2] must preserve every v1 value and add the attachments
 * table, its unique index and its foreign key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AttachmentMigrationTest {
    private val databases = CopyOnWriteArrayList<ChatDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
    }

    @Test
    fun migratingRealV1DatabasePreservesAllValuesAndAddsAttachmentSchema() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "attachment-migration-${System.nanoTime()}.db"
        try {
            val v1 = buildV1Database(context, name)

            val migrated = Room.databaseBuilder(context, ChatDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
            databases += migrated

            // Old values survive byte-for-byte.
            val conversation = migrated.conversations().find("conversation-a")!!
            assertEquals("Migrated", conversation.title)
            assertEquals("gpt-5.6-sol", conversation.defaultModel)
            assertEquals(1000L, conversation.createdAt)
            assertEquals(2000L, conversation.updatedAt)

            val user = migrated.messages().find("message-user")!!
            assertEquals("conversation-a", user.conversationId)
            assertEquals("USER", user.role)
            assertEquals("hello", user.content)
            assertEquals(null, user.modelId)
            assertEquals(3000L, user.createdAt)
            assertEquals("COMPLETE", user.status)

            val assistant = migrated.messages().find("message-assistant")!!
            assertEquals("ASSISTANT", assistant.role)
            assertEquals("", assistant.content)
            assertEquals("gpt-5.6-sol", assistant.modelId)
            assertEquals("PENDING", assistant.status)

            val usage = migrated.usageEvents().observeForConversation("conversation-a").first().single()
            assertEquals("message-assistant", usage.requestId)
            assertEquals("gpt-5.6-luna", usage.modelId)
            assertEquals(5000L, usage.timestamp)
            assertEquals(10L, usage.inputTokens)
            assertEquals(2L, usage.cachedInputTokens)
            assertEquals(7L, usage.outputTokens)
            assertEquals("0.0001", usage.estimatedModelCost)
            assertEquals("0.0002", usage.estimatedGoatCredits)
            assertTrue(!usage.usageComplete)

            // The new table, index and FK are present and functional.
            val tables = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type IN ('table', 'index') AND name = ?",
                arrayOf<Any>("attachments"),
            ).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                names
            }
            // sqlite_master index lookup is case-sensitive on the raw name; query index separately.
            assertEquals(listOf("attachments"), tables)

            val indexSql = migrated.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'index_attachments_messageId_position'",
                emptyArray(),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            assertNotNull(indexSql)
            assertTrue(indexSql!!.contains("UNIQUE"))
            assertTrue(indexSql.contains("messageId"))
            assertTrue(indexSql.contains("position"))

            // FK enforcement works: an attachment must reference an existing message.
            migrated.attachments().insert(
                listOf(
                    AttachmentEntity(
                        id = "attachment-1",
                        messageId = "message-user",
                        displayName = "Image.png",
                        mimeType = "image/png",
                        originalBytes = 100L,
                        encryptedBytes = 133L,
                        width = 2,
                        height = 2,
                        position = 0,
                    ),
                ),
            )
            assertEquals(1, migrated.attachments().countForConversation("conversation-a"))
            // Invalid parent is rejected by the FK.
            val invalidRejected = runCatching {
                migrated.attachments().insert(
                    listOf(
                        AttachmentEntity(
                            id = "attachment-2",
                            messageId = "missing-message",
                            displayName = "Image.png",
                            mimeType = "image/png",
                            originalBytes = 1L,
                            encryptedBytes = 1L,
                            width = 1,
                            height = 1,
                            position = 1,
                        ),
                    ),
                )
            }
            assertTrue(invalidRejected.isFailure)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun buildV1Database(context: Context, name: String) {
        // Mirrors the generated v1 createAllTables SQL plus the room_master_table
        // identity row so the v1 database is a genuine Room v1 database.
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS conversations (" +
                    "id TEXT NOT NULL, title TEXT NOT NULL, defaultModel TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_updatedAt ON conversations (updatedAt)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS messages (" +
                    "id TEXT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                    "modelId TEXT, createdAt INTEGER NOT NULL, status TEXT NOT NULL, PRIMARY KEY(id), " +
                    "FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messages_conversationId_createdAt ON messages (conversationId, createdAt)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS usage_events (" +
                    "id TEXT NOT NULL, requestId TEXT, conversationId TEXT NOT NULL, modelId TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, inputTokens INTEGER, cachedInputTokens INTEGER, outputTokens INTEGER, " +
                    "estimatedModelCost TEXT, estimatedGoatCredits TEXT, usageComplete INTEGER NOT NULL, PRIMARY KEY(id), " +
                    "FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_usage_events_conversationId_timestamp ON usage_events (conversationId, timestamp)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'e031ddea5e8fc2979c4c58f8e05cdc8a')")
            db.execSQL(
                "INSERT INTO conversations (id, title, defaultModel, createdAt, updatedAt) " +
                    "VALUES ('conversation-a', 'Migrated', 'gpt-5.6-sol', 1000, 2000)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, role, content, modelId, createdAt, status) " +
                    "VALUES ('message-user', 'conversation-a', 'USER', 'hello', NULL, 3000, 'COMPLETE'), " +
                    "('message-assistant', 'conversation-a', 'ASSISTANT', '', 'gpt-5.6-sol', 4000, 'PENDING')",
            )
            db.execSQL(
                "INSERT INTO usage_events (id, requestId, conversationId, modelId, timestamp, inputTokens, " +
                    "cachedInputTokens, outputTokens, estimatedModelCost, estimatedGoatCredits, usageComplete) " +
                    "VALUES ('usage-1', 'message-assistant', 'conversation-a', 'gpt-5.6-luna', 5000, 10, 2, 7, " +
                    "'0.0001', '0.0002', 0)",
            )
            db.version = 1
        } finally {
            db.close()
        }
    }
}
