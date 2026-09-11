package com.commandcode.chat.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_nodes",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["parentId", "ordinal"], unique = true),
    ],
)
data class TimelineNodeEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String?,
    val ordinal: Int,
    @ColumnInfo(defaultValue = "0") val anchorDeleted: Boolean = false,
)

@Entity(
    tableName = "message_placements",
    primaryKeys = ["messageId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["nodeId", "position"], unique = true)],
)
data class MessagePlacementEntity(
    val messageId: String,
    val nodeId: String,
    val position: Int,
)

@Entity(
    tableName = "branch_selections",
    primaryKeys = ["parentNodeId"],
    foreignKeys = [
        ForeignKey(
            entity = TimelineNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentNodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["selectedNodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["selectedNodeId"])],
)
data class BranchSelectionEntity(
    val parentNodeId: String,
    val selectedNodeId: String,
)
