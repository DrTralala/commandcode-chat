package com.commandcode.chat.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BranchDao {
    @Insert
    fun insertNode(value: TimelineNodeEntity)

    @Insert
    fun insertPlacement(value: MessagePlacementEntity)

    @Query("DELETE FROM message_placements WHERE messageId = :messageId")
    fun deletePlacement(messageId: String)

    @Query("UPDATE message_placements SET nodeId = :nodeId, position = :position WHERE messageId = :messageId")
    fun movePlacement(messageId: String, nodeId: String, position: Int)

    @Query("SELECT * FROM message_placements WHERE nodeId = :nodeId ORDER BY position ASC, messageId ASC")
    fun listPlacementsForNode(nodeId: String): List<MessagePlacementEntity>

    @Upsert
    fun select(value: BranchSelectionEntity)

    @Query("DELETE FROM branch_selections WHERE parentNodeId = :parentNodeId")
    fun deleteSelection(parentNodeId: String)

    @Query("SELECT * FROM branch_selections WHERE parentNodeId = :parentNodeId")
    fun findSelection(parentNodeId: String): BranchSelectionEntity?

    @Query("SELECT * FROM branch_selections WHERE selectedNodeId = :selectedNodeId")
    fun listSelectionsForSelectedNode(selectedNodeId: String): List<BranchSelectionEntity>

    @Query("SELECT * FROM branch_selections WHERE parentNodeId = :nodeId OR selectedNodeId = :nodeId")
    fun listSelectionsForNode(nodeId: String): List<BranchSelectionEntity>

    @Query("SELECT * FROM timeline_nodes WHERE conversationId = :conversationId AND parentId IS NULL")
    fun findRoot(conversationId: String): TimelineNodeEntity?

    @Query("SELECT * FROM timeline_nodes WHERE id = :id")
    fun findNode(id: String): TimelineNodeEntity?

    @Query("SELECT * FROM timeline_nodes WHERE conversationId = :conversationId AND parentId = :parentId ORDER BY ordinal ASC, id ASC")
    fun listChildren(conversationId: String, parentId: String): List<TimelineNodeEntity>

    @Query("SELECT id FROM timeline_nodes")
    fun listAllNodeIds(): List<String>

    @Query("SELECT MAX(ordinal) FROM timeline_nodes WHERE conversationId = :conversationId AND parentId IS :parentId")
    fun maxOrdinal(conversationId: String, parentId: String?): Int?

    @Query("UPDATE timeline_nodes SET ordinal = :ordinal WHERE id = :id")
    fun updateOrdinal(id: String, ordinal: Int)

    @Query("UPDATE timeline_nodes SET parentId = :newParentId WHERE conversationId = :conversationId AND parentId = :oldParentId")
    fun reparentChildren(conversationId: String, oldParentId: String, newParentId: String)

    @Query("UPDATE timeline_nodes SET parentId = :newParentId, ordinal = :ordinal WHERE id = :id")
    fun moveNode(id: String, newParentId: String, ordinal: Int)

    @Query("UPDATE timeline_nodes SET anchorDeleted = :deleted WHERE id = :id")
    fun markAnchorDeleted(id: String, deleted: Boolean)

    @Query("DELETE FROM timeline_nodes WHERE id = :id")
    fun deleteNode(id: String)

    @Query("SELECT * FROM timeline_nodes WHERE conversationId = :conversationId ORDER BY parentId ASC, ordinal ASC, id ASC")
    fun listNodes(conversationId: String): List<TimelineNodeEntity>

    @Query(
        "SELECT p.* FROM message_placements AS p " +
            "JOIN timeline_nodes AS n ON n.id = p.nodeId " +
            "WHERE n.conversationId = :conversationId " +
            "ORDER BY p.nodeId ASC, p.position ASC, p.messageId ASC",
    )
    fun listPlacements(conversationId: String): List<MessagePlacementEntity>

    @Query(
        "SELECT s.* FROM branch_selections AS s " +
            "JOIN timeline_nodes AS n ON n.id = s.parentNodeId " +
            "WHERE n.conversationId = :conversationId " +
            "ORDER BY s.parentNodeId ASC",
    )
    fun listSelections(conversationId: String): List<BranchSelectionEntity>

    @Query("SELECT * FROM timeline_nodes WHERE conversationId = :conversationId ORDER BY parentId ASC, ordinal ASC, id ASC")
    fun observeNodes(conversationId: String): Flow<List<TimelineNodeEntity>>

    @Query(
        "SELECT p.* FROM message_placements AS p " +
            "JOIN timeline_nodes AS n ON n.id = p.nodeId " +
            "WHERE n.conversationId = :conversationId " +
            "ORDER BY p.nodeId ASC, p.position ASC, p.messageId ASC",
    )
    fun observePlacements(conversationId: String): Flow<List<MessagePlacementEntity>>

    @Query(
        "SELECT s.* FROM branch_selections AS s " +
            "JOIN timeline_nodes AS n ON n.id = s.parentNodeId " +
            "WHERE n.conversationId = :conversationId " +
            "ORDER BY s.parentNodeId ASC",
    )
    fun observeSelections(conversationId: String): Flow<List<BranchSelectionEntity>>

    /**
     * One invalidation query covers every graph and message table. The
     * repository folds its rows into a [BranchTimeline] after the committed
     * transaction, so a selector change cannot be observed without its graph.
     */
    @Query(
        """
        SELECT
            n.id AS nodeId,
            n.conversationId AS nodeConversationId,
            n.parentId AS nodeParentId,
            n.ordinal AS nodeOrdinal,
            n.anchorDeleted AS nodeAnchorDeleted,
            p.messageId AS placementMessageId,
            p.nodeId AS placementNodeId,
            p.position AS placementPosition,
            s.parentNodeId AS selectionParentNodeId,
            s.selectedNodeId AS selectionSelectedNodeId,
            m.id AS messageId,
            m.conversationId AS messageConversationId,
            m.role AS messageRole,
            m.content AS messageContent,
            m.modelId AS messageModelId,
            m.createdAt AS messageCreatedAt,
            m.status AS messageStatus,
            m.replyToMessageId AS messageReplyToMessageId,
            a.id AS attachmentId,
            a.conversationId AS attachmentConversationId,
            a.displayName AS attachmentDisplayName,
            a.mimeType AS attachmentMimeType,
            a.originalBytes AS attachmentOriginalBytes,
            a.encryptedBytes AS attachmentEncryptedBytes,
            a.width AS attachmentWidth,
            a.height AS attachmentHeight,
            ma.position AS attachmentPosition
        FROM timeline_nodes AS n
        LEFT JOIN message_placements AS p ON p.nodeId = n.id
        LEFT JOIN branch_selections AS s ON s.parentNodeId = n.id
        LEFT JOIN messages AS m ON m.id = p.messageId
        LEFT JOIN message_attachments AS ma ON ma.messageId = m.id
            LEFT JOIN attachments AS a ON a.id = ma.attachmentId
            WHERE n.conversationId = :conversationId
            UNION ALL
            SELECT
                root.id AS nodeId,
                root.conversationId AS nodeConversationId,
                root.parentId AS nodeParentId,
                root.ordinal AS nodeOrdinal,
                root.anchorDeleted AS nodeAnchorDeleted,
                NULL AS placementMessageId,
                NULL AS placementNodeId,
                NULL AS placementPosition,
                NULL AS selectionParentNodeId,
                NULL AS selectionSelectedNodeId,
                m.id AS messageId,
                m.conversationId AS messageConversationId,
                m.role AS messageRole,
                m.content AS messageContent,
                m.modelId AS messageModelId,
                m.createdAt AS messageCreatedAt,
                m.status AS messageStatus,
                m.replyToMessageId AS messageReplyToMessageId,
                a.id AS attachmentId,
                a.conversationId AS attachmentConversationId,
                a.displayName AS attachmentDisplayName,
                a.mimeType AS attachmentMimeType,
                a.originalBytes AS attachmentOriginalBytes,
                a.encryptedBytes AS attachmentEncryptedBytes,
                a.width AS attachmentWidth,
                a.height AS attachmentHeight,
                ma.position AS attachmentPosition
            FROM timeline_nodes AS root
            JOIN messages AS m ON m.conversationId = root.conversationId
            LEFT JOIN message_attachments AS ma ON ma.messageId = m.id
            LEFT JOIN attachments AS a ON a.id = ma.attachmentId
            WHERE root.conversationId = :conversationId
              AND root.parentId IS NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM message_placements AS existingPlacement
                  JOIN timeline_nodes AS existingNode ON existingNode.id = existingPlacement.nodeId
                  WHERE existingPlacement.messageId = m.id
                    AND existingNode.conversationId = root.conversationId
              )
            ORDER BY nodeId ASC, placementPosition ASC, placementMessageId ASC, attachmentPosition ASC, attachmentId ASC
        """,
    )
    fun observeGraph(conversationId: String): Flow<List<BranchTimelineRow>>
}

data class BranchTimelineRow(
    val nodeId: String,
    val nodeConversationId: String,
    val nodeParentId: String?,
    val nodeOrdinal: Int,
    val nodeAnchorDeleted: Boolean,
    val placementMessageId: String?,
    val placementNodeId: String?,
    val placementPosition: Int?,
    val selectionParentNodeId: String?,
    val selectionSelectedNodeId: String?,
    val messageId: String?,
    val messageConversationId: String?,
    val messageRole: String?,
    val messageContent: String?,
    val messageModelId: String?,
    val messageCreatedAt: Long?,
    val messageStatus: String?,
    val messageReplyToMessageId: String?,
    val attachmentId: String?,
    val attachmentConversationId: String?,
    val attachmentDisplayName: String?,
    val attachmentMimeType: String?,
    val attachmentOriginalBytes: Long?,
    val attachmentEncryptedBytes: Long?,
    val attachmentWidth: Int?,
    val attachmentHeight: Int?,
    val attachmentPosition: Int?,
)
