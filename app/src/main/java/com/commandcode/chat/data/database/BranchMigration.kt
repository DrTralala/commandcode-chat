package com.commandcode.chat.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        migrateAttachments(db)
        createBranchTables(db)
        backfillTimeline(db)
    }

    private fun createBranchTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE timeline_nodes (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                parentId TEXT,
                ordinal INTEGER NOT NULL,
                anchorDeleted INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(parentId) REFERENCES timeline_nodes(id) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX index_timeline_nodes_conversationId ON timeline_nodes(conversationId)")
        database.execSQL(
            "CREATE UNIQUE INDEX index_timeline_nodes_parentId_ordinal " +
                "ON timeline_nodes(parentId,ordinal)",
        )
        database.execSQL(
            """
            CREATE TABLE message_placements (
                messageId TEXT NOT NULL PRIMARY KEY,
                nodeId TEXT NOT NULL,
                position INTEGER NOT NULL,
                FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(nodeId) REFERENCES timeline_nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE UNIQUE INDEX index_message_placements_nodeId_position " +
                "ON message_placements(nodeId,position)",
        )
        database.execSQL(
            """
            CREATE TABLE branch_selections (
                parentNodeId TEXT NOT NULL PRIMARY KEY,
                selectedNodeId TEXT NOT NULL,
                FOREIGN KEY(parentNodeId) REFERENCES timeline_nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(selectedNodeId) REFERENCES timeline_nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX index_branch_selections_selectedNodeId ON branch_selections(selectedNodeId)")
    }

    private fun migrateAttachments(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE attachments_v4 (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                displayName TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                originalBytes INTEGER NOT NULL,
                encryptedBytes INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        val oldAttachmentCount = scalarLong(database, "SELECT COUNT(*) FROM attachments")
        database.execSQL(
            """
            INSERT INTO attachments_v4 (
                id, conversationId, displayName, mimeType, originalBytes,
                encryptedBytes, width, height
            )
            SELECT a.id, m.conversationId, a.displayName, a.mimeType, a.originalBytes,
                a.encryptedBytes, a.width, a.height
            FROM attachments AS a
            JOIN messages AS m ON m.id = a.messageId
            """.trimIndent(),
        )
        check(scalarLong(database, "SELECT COUNT(*) FROM attachments_v4") == oldAttachmentCount) {
            "Attachment metadata copy was incomplete"
        }

        database.execSQL(
            "CREATE TEMP TABLE legacy_message_attachments (" +
                "messageId TEXT NOT NULL, attachmentId TEXT NOT NULL, position INTEGER NOT NULL)",
        )
        database.execSQL(
            "INSERT INTO legacy_message_attachments (messageId, attachmentId, position) " +
                "SELECT messageId, id, position FROM attachments",
        )
        val oldLinkCount = scalarLong(database, "SELECT COUNT(*) FROM legacy_message_attachments")

        database.execSQL("DROP TABLE attachments")
        database.execSQL("ALTER TABLE attachments_v4 RENAME TO attachments")
        database.execSQL("CREATE INDEX index_attachments_conversationId ON attachments(conversationId)")
        database.execSQL(
            """
            CREATE TABLE message_attachments (
                messageId TEXT NOT NULL,
                attachmentId TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(messageId, attachmentId),
                FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(attachmentId) REFERENCES attachments(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE UNIQUE INDEX index_message_attachments_messageId_position " +
                "ON message_attachments(messageId,position)",
        )
        database.execSQL(
            "CREATE INDEX index_message_attachments_attachmentId ON message_attachments(attachmentId)",
        )
        database.execSQL(
            "INSERT INTO message_attachments (messageId, attachmentId, position) " +
                "SELECT messageId, attachmentId, position FROM legacy_message_attachments",
        )
        check(scalarLong(database, "SELECT COUNT(*) FROM message_attachments") == oldLinkCount) {
            "Attachment links copy was incomplete"
        }
        database.execSQL("DROP TABLE legacy_message_attachments")
    }

    private fun backfillTimeline(database: SupportSQLiteDatabase) {
        val usedIds = allocatedIds(database)
        database.query("SELECT id FROM conversations ORDER BY createdAt ASC, id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val conversationId = cursor.getString(0)
                val rootId = allocateRootId(conversationId, usedIds)
                backfillConversation(database, conversationId, rootId)
            }
        }
    }

    private fun allocatedIds(database: SupportSQLiteDatabase): MutableSet<String> {
        val usedIds = linkedSetOf<String>()
        database.query("SELECT id FROM messages").use { cursor ->
            while (cursor.moveToNext()) usedIds += cursor.getString(0)
        }
        database.query("SELECT id FROM timeline_nodes").use { cursor ->
            while (cursor.moveToNext()) usedIds += cursor.getString(0)
        }
        return usedIds
    }

    private fun allocateRootId(conversationId: String, usedIds: MutableSet<String>): String {
        val preferred = "root:$conversationId"
        if (usedIds.add(preferred)) return preferred
        var suffix = 1
        while (suffix < Int.MAX_VALUE) {
            val candidate = "$preferred:$suffix"
            if (usedIds.add(candidate)) return candidate
            suffix++
        }
        error("No deterministic root ID is available for conversation $conversationId")
    }

    private fun backfillConversation(database: SupportSQLiteDatabase, conversationId: String, rootId: String) {
        database.execSQL(
            "INSERT INTO timeline_nodes (id, conversationId, parentId, ordinal, anchorDeleted) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(rootId, conversationId, null, 0, 0),
        )

        val notices = mutableListOf<String>()
        var parentId = rootId
        var lastAnchorId: String? = null
        database.query(
            "SELECT id, role FROM messages WHERE conversationId = ? ORDER BY createdAt ASC, id ASC",
            arrayOf(conversationId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val messageId = cursor.getString(0)
                val role = cursor.getString(1)
                if (role != "USER" && role != "ASSISTANT") {
                    notices += messageId
                    continue
                }

                database.execSQL(
                    "INSERT INTO timeline_nodes (id, conversationId, parentId, ordinal, anchorDeleted) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(messageId, conversationId, parentId, 0, 0),
                )
                if (lastAnchorId == null) {
                    insertNoticePlacements(database, rootId, notices, rootPositions = true)
                } else {
                    insertNoticePlacements(database, messageId, notices, rootPositions = false)
                }
                notices.clear()

                database.execSQL(
                    "INSERT INTO message_placements (messageId, nodeId, position) VALUES (?, ?, ?)",
                    arrayOf<Any?>(messageId, messageId, 0),
                )
                database.execSQL(
                    "INSERT INTO branch_selections (parentNodeId, selectedNodeId) VALUES (?, ?)",
                    arrayOf<Any?>(parentId, messageId),
                )
                parentId = messageId
                lastAnchorId = messageId
            }
        }

        if (notices.isNotEmpty()) {
            insertNoticePlacements(
                database,
                lastAnchorId ?: rootId,
                notices,
                rootPositions = lastAnchorId == null,
                trailing = lastAnchorId != null,
            )
        }
    }

    private fun insertNoticePlacements(
        database: SupportSQLiteDatabase,
        nodeId: String,
        messageIds: List<String>,
        rootPositions: Boolean,
        trailing: Boolean = false,
    ) {
        messageIds.forEachIndexed { index, messageId ->
            val position = when {
                rootPositions -> index
                trailing -> index + 1
                else -> -messageIds.size + index
            }
            database.execSQL(
                "INSERT INTO message_placements (messageId, nodeId, position) VALUES (?, ?, ?)",
                arrayOf<Any?>(messageId, nodeId, position),
            )
        }
    }

    private fun scalarLong(database: SupportSQLiteDatabase, sql: String): Long =
        database.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Expected scalar result for $sql" }
            cursor.getLong(0)
        }
}
