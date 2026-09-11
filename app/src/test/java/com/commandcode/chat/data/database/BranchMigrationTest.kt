package com.commandcode.chat.data.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real v3 -> v4 migration coverage. The v3 database is created from the
 * exported v3 entity SQL, then reopened through Room so migration validation
 * checks the complete new schema rather than only the migration statements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BranchMigrationTest {
    private val databases = CopyOnWriteArrayList<ChatDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
    }

    @Test
    fun migratingRealV3DatabasePreservesAllRowsAndBackfillsOneSelectedChain() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "branch-migration-${System.nanoTime()}.db"
        try {
            val fixture = buildV3Database(context, name)

            val migrated = Room.databaseBuilder(context, ChatDatabase::class.java, name)
                .addMigrations(MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
            databases += migrated

            assertEquals(4, migrated.openHelper.writableDatabase.version)
            assertEquals(
                fixture.conversations,
                fixture.conversations.map { requireNotNull(migrated.conversations().find(it.id)) },
            )
            fixture.messagesByConversation.forEach { (conversationId, expected) ->
                assertEquals(expected, migrated.messages().listForConversation(conversationId))
            }
            fixture.usageByConversation.forEach { (conversationId, expected) ->
                assertEquals(
                    expected,
                    migrated.usageEvents().observeForConversation(conversationId).first(),
                )
            }

            val legacyMessageIds = fixture.messagesByConversation.getValue("conversation-a").map { it.id }
            val placements = migrated.branches().listPlacements("conversation-a")
            assertEquals(legacyMessageIds.toSet(), placements.map { it.messageId }.toSet())
            assertEquals(legacyMessageIds.size, placements.size)
            assertEquals("message-user", migrated.messages().find("message-assistant")!!.replyToMessageId)

            val nodes = migrated.branches().listNodes("conversation-a").associateBy { it.id }
            assertEquals(
                setOf(
                    "root:conversation-a:3",
                    "message-user",
                    "message-assistant",
                    "later-user",
                    "later-assistant",
                    "root:conversation-a",
                    "root:conversation-b",
                ),
                nodes.keys,
            )
            assertEquals(null, nodes.getValue("root:conversation-a:3").parentId)
            assertEquals("root:conversation-a:3", nodes.getValue("message-user").parentId)
            assertEquals("message-user", nodes.getValue("message-assistant").parentId)
            assertEquals("message-assistant", nodes.getValue("later-user").parentId)
            assertEquals("later-user", nodes.getValue("later-assistant").parentId)
            assertEquals("later-assistant", nodes.getValue("root:conversation-a").parentId)
            assertEquals("root:conversation-a", nodes.getValue("root:conversation-b").parentId)
            assertTrue(nodes.values.all { it.ordinal == 0 })

            val selections = migrated.branches().listSelections("conversation-a")
                .associate { it.parentNodeId to it.selectedNodeId }
            assertEquals(
                mapOf(
                    "root:conversation-a:3" to "message-user",
                    "message-user" to "message-assistant",
                    "message-assistant" to "later-user",
                    "later-user" to "later-assistant",
                    "later-assistant" to "root:conversation-a",
                    "root:conversation-a" to "root:conversation-b",
                ),
                selections,
            )

            val placementByMessage = placements.associateBy { it.messageId }
            assertEquals(
                MessagePlacementEntity("root:conversation-a", "root:conversation-a", 0),
                placementByMessage.getValue("root:conversation-a"),
            )
            assertEquals(
                MessagePlacementEntity("initial-a", "root:conversation-a:3", 0),
                placementByMessage.getValue("initial-a"),
            )
            assertEquals(
                MessagePlacementEntity("prompt-a", "root:conversation-a:3", 1),
                placementByMessage.getValue("prompt-a"),
            )
            assertEquals(
                MessagePlacementEntity("trailing-model-a", "root:conversation-b", 1),
                placementByMessage.getValue("trailing-model-a"),
            )
            assertEquals(
                MessagePlacementEntity("trailing-prompt-a", "root:conversation-b", 2),
                placementByMessage.getValue("trailing-prompt-a"),
            )
            assertEquals(
                MessagePlacementEntity("middle-prompt-a", "later-user", -1),
                placementByMessage.getValue("middle-prompt-a"),
            )

            val otherNodes = migrated.branches().listNodes("conversation-b").associateBy { it.id }
            assertTrue(otherNodes.containsKey("root:conversation-b:2"))
            assertEquals(null, otherNodes.getValue("root:conversation-b:2").parentId)
            assertEquals(
                MessagePlacementEntity("initial-b", "root:conversation-b:2", 0),
                migrated.branches().listPlacements("conversation-b")
                    .associateBy { it.messageId }.getValue("initial-b"),
            )

            val attachment = migrated.attachments().listForConversation("conversation-a").single()
            assertEquals("image", attachment.id)
            assertEquals("conversation-a", attachment.conversationId)
            assertEquals("Image.webp", attachment.displayName)
            assertEquals("image/webp", attachment.mimeType)
            assertEquals(100L, attachment.originalBytes)
            assertEquals(133L, attachment.encryptedBytes)
            assertEquals(2, attachment.width)
            assertEquals(2, attachment.height)
            assertEquals(listOf("image"), migrated.attachments().listIds())
            assertEquals(1, migrated.attachments().countForConversation("conversation-a"))
            assertEquals(0, migrated.attachments().countForConversation("conversation-b"))
            assertEquals(
                listOf(MessageAttachmentEntity("message-user", "image", 0)),
                migrated.messageAttachments().listForMessage("message-user"),
            )

            assertTrue(
                runCatching {
                    migrated.branches().insertPlacement(
                        MessagePlacementEntity("missing-message", "root:conversation-a:3", 0),
                    )
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    migrated.messageAttachments().insert(
                        listOf(MessageAttachmentEntity("missing-message", "image", 0)),
                    )
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    migrated.branches().insertNode(
                        TimelineNodeEntity("invalid-node", "conversation-a", "missing-node", 0),
                    )
                }.isFailure,
            )

            migrated.close()
            val reopened = Room.databaseBuilder(context, ChatDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
            databases += reopened
            assertEquals(
                legacyMessageIds.toSet(),
                reopened.branches().listPlacements("conversation-a").map { it.messageId }.toSet(),
            )
            assertEquals(
                mapOf(
                    "root:conversation-a:3" to "message-user",
                    "message-user" to "message-assistant",
                    "message-assistant" to "later-user",
                    "later-user" to "later-assistant",
                    "later-assistant" to "root:conversation-a",
                    "root:conversation-a" to "root:conversation-b",
                ),
                reopened.branches().listSelections("conversation-a")
                    .associate { it.parentNodeId to it.selectedNodeId },
            )
            assertEquals("conversation-a", reopened.attachments().listForConversation("conversation-a").single().conversationId)
        } finally {
            databases.forEach { runCatching { it.close() } }
            context.deleteDatabase(name)
        }
    }

    private fun buildV3Database(context: Context, name: String): LegacyFixture {
        val conversations = listOf(
            ConversationEntity("conversation-a", "Branch A", "gpt-5.6-sol", 100L, 900L),
            ConversationEntity("conversation-b", "Branch B", "gpt-5.6-luna", 100L, 900L),
        )
        val messagesByConversation = mapOf(
            "conversation-a" to listOf(
                MessageEntity("initial-a", "conversation-a", "INITIAL_MODEL", "gpt-5.6-sol", "gpt-5.6-sol", 100L, "COMPLETE"),
                MessageEntity("prompt-a", "conversation-a", "SYSTEM_PROMPT_CHANGE", "prompt-a-fingerprint", null, 100L, "COMPLETE"),
                MessageEntity("message-user", "conversation-a", "USER", "original", null, 200L, "FAILED_ZDR"),
                MessageEntity("message-assistant", "conversation-a", "ASSISTANT", "", "gpt-5.6-sol", 300L, "FAILED_ZDR", "message-user"),
                MessageEntity("middle-prompt-a", "conversation-a", "SYSTEM_PROMPT_CHANGE", "middle-fingerprint", null, 350L, "COMPLETE"),
                MessageEntity("later-user", "conversation-a", "USER", "later", null, 400L, "COMPLETE"),
                MessageEntity("later-assistant", "conversation-a", "ASSISTANT", "", "gpt-5.6-luna", 500L, "PENDING", "later-user"),
                MessageEntity("root:conversation-a", "conversation-a", "ASSISTANT", "colliding-root-a", null, 650L, "COMPLETE"),
                MessageEntity("root:conversation-b", "conversation-a", "ASSISTANT", "colliding-root-b", null, 660L, "COMPLETE"),
                MessageEntity("trailing-model-a", "conversation-a", "MODEL_CHANGE", "gpt-5.6-luna", "gpt-5.6-luna", 700L, "COMPLETE"),
                MessageEntity("trailing-prompt-a", "conversation-a", "SYSTEM_PROMPT_CHANGE", "trailing-fingerprint", null, 710L, "COMPLETE"),
            ),
            "conversation-b" to listOf(
                MessageEntity("initial-b", "conversation-b", "INITIAL_MODEL", "gpt-5.6-luna", "gpt-5.6-luna", 100L, "COMPLETE"),
                MessageEntity("user-b", "conversation-b", "USER", "second conversation", null, 200L, "COMPLETE"),
                MessageEntity("assistant-b", "conversation-b", "ASSISTANT", "answer", "gpt-5.6-luna", 300L, "COMPLETE", "user-b"),
                MessageEntity("root:conversation-a:1", "conversation-b", "ASSISTANT", "colliding-root-a-suffix-1", null, 400L, "COMPLETE"),
                MessageEntity("root:conversation-a:2", "conversation-b", "ASSISTANT", "colliding-root-a-suffix-2", null, 410L, "COMPLETE"),
                MessageEntity("root:conversation-b:1", "conversation-b", "ASSISTANT", "colliding-root-b-suffix-1", null, 420L, "COMPLETE"),
                MessageEntity("trailing-b", "conversation-b", "MODEL_CHANGE", "gpt-5.6-sol", "gpt-5.6-sol", 500L, "COMPLETE"),
            ),
        )
        val usageByConversation = mapOf(
            "conversation-a" to listOf(
                UsageEventEntity("usage-complete-a", "message-assistant", "conversation-a", "gpt-5.6-sol", 350L, 10L, 2L, 7L, "0.0001", "0.0002", true),
                UsageEventEntity("usage-incomplete-a", "later-assistant", "conversation-a", "gpt-5.6-luna", 550L, null, null, null, null, null, false),
            ),
            "conversation-b" to listOf(
                UsageEventEntity("usage-complete-b", "assistant-b", "conversation-b", "gpt-5.6-luna", 350L, 20L, 3L, 9L, "0.0003", "0.0004", true),
            ),
        )
        val attachment = LegacyAttachment("image", "message-user", "Image.webp", "image/webp", 100L, 133L, 2, 2, 0)

        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            v3CreateSql().forEach(db::execSQL)
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'ce6d677e56f606b50eded4617234b24b')")
            conversations.forEach { value ->
                db.execSQL(
                    "INSERT INTO conversations (id, title, defaultModel, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(value.id, value.title, value.defaultModel, value.createdAt, value.updatedAt),
                )
            }
            messagesByConversation.values.flatten().forEach { value ->
                db.execSQL(
                    "INSERT INTO messages (id, conversationId, role, content, modelId, createdAt, status, replyToMessageId) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(value.id, value.conversationId, value.role, value.content, value.modelId, value.createdAt, value.status, value.replyToMessageId),
                )
            }
            usageByConversation.values.flatten().forEach { value ->
                db.execSQL(
                    "INSERT INTO usage_events (id, requestId, conversationId, modelId, timestamp, inputTokens, cachedInputTokens, outputTokens, estimatedModelCost, estimatedGoatCredits, usageComplete) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(value.id, value.requestId, value.conversationId, value.modelId, value.timestamp, value.inputTokens, value.cachedInputTokens, value.outputTokens, value.estimatedModelCost, value.estimatedGoatCredits, value.usageComplete),
                )
            }
            db.execSQL(
                "INSERT INTO attachments (id, messageId, displayName, mimeType, originalBytes, encryptedBytes, width, height, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(attachment.id, attachment.messageId, attachment.displayName, attachment.mimeType, attachment.originalBytes, attachment.encryptedBytes, attachment.width, attachment.height, attachment.position),
            )
            db.version = 3
        } finally {
            db.close()
        }
        return LegacyFixture(conversations, messagesByConversation, usageByConversation, attachment)
    }

    private fun v3CreateSql(): List<String> = listOf(
        "conversations" to "CREATE TABLE IF NOT EXISTS `$TABLE_NAME_PLACEHOLDER` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `defaultModel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "conversations" to "CREATE INDEX IF NOT EXISTS `index_conversations_updatedAt` ON `$TABLE_NAME_PLACEHOLDER` (`updatedAt`)",
        "messages" to "CREATE TABLE IF NOT EXISTS `$TABLE_NAME_PLACEHOLDER` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `modelId` TEXT, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `replyToMessageId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "messages" to "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `$TABLE_NAME_PLACEHOLDER` (`conversationId`, `createdAt`)",
        "usage_events" to "CREATE TABLE IF NOT EXISTS `$TABLE_NAME_PLACEHOLDER` (`id` TEXT NOT NULL, `requestId` TEXT, `conversationId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `inputTokens` INTEGER, `cachedInputTokens` INTEGER, `outputTokens` INTEGER, `estimatedModelCost` TEXT, `estimatedGoatCredits` TEXT, `usageComplete` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "usage_events" to "CREATE INDEX IF NOT EXISTS `index_usage_events_conversationId_timestamp` ON `$TABLE_NAME_PLACEHOLDER` (`conversationId`, `timestamp`)",
        "attachments" to "CREATE TABLE IF NOT EXISTS `$TABLE_NAME_PLACEHOLDER` (`id` TEXT NOT NULL, `messageId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `originalBytes` INTEGER NOT NULL, `encryptedBytes` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "attachments" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_attachments_messageId_position` ON `$TABLE_NAME_PLACEHOLDER` (`messageId`, `position`)",
    ).map { (tableName, sql) -> sql.replace(TABLE_NAME_PLACEHOLDER, tableName) }

    private companion object {
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }

    private data class LegacyFixture(
        val conversations: List<ConversationEntity>,
        val messagesByConversation: Map<String, List<MessageEntity>>,
        val usageByConversation: Map<String, List<UsageEventEntity>>,
        val attachment: LegacyAttachment,
    )

    private data class LegacyAttachment(
        val id: String,
        val messageId: String,
        val displayName: String,
        val mimeType: String,
        val originalBytes: Long,
        val encryptedBytes: Long,
        val width: Int,
        val height: Int,
        val position: Int,
    )

}
