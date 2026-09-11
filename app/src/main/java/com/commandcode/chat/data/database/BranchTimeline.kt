package com.commandcode.chat.data.database

import com.commandcode.chat.domain.MessageState
import com.commandcode.chat.domain.ImageAttachment

/**
 * Pure projection of the graph tables into the one selected conversation path.
 * The class deliberately has no Room or coroutine dependency so malformed graph
 * fixtures can be rejected without involving persistence.
 */
class BranchTimeline(
    val nodes: List<TimelineNodeEntity>,
    val placements: List<MessagePlacementEntity>,
    val selections: List<BranchSelectionEntity>,
    val messages: List<Message>,
) {
    internal fun selectedPath(conversationId: String): List<TimelineNodeEntity> =
        graphFor(conversationId).path

    internal fun ancestorPrefix(conversationId: String, messageId: String): List<Message> {
        val graph = graphFor(conversationId)
        val nodeId = graph.placementByMessage[messageId]?.nodeId
            ?: error("Message is not on the selected path")
        val index = graph.path.indexOfFirst { it.id == nodeId }
        require(index >= 0) { "Message is not on the selected path" }
        return materialise(graph.path.take(index), graph)
    }

    fun selected(conversationId: String): List<Message> {
        val graph = graphFor(conversationId)
        return materialise(graph.path, graph)
    }

    fun source(conversationId: String, messageId: String): AlternativeSource {
        val graph = graphFor(conversationId)
        val pathIndex = graph.path.indexOfFirst { it.id == graph.placementByMessage[messageId]?.nodeId }
        require(pathIndex >= 0) { "Message is not on the selected path" }

        val rawTarget = graph.messageById[messageId]
            ?: error("Message is not part of conversation $conversationId")
        require(rawTarget.conversationId == conversationId) { "Message belongs to another conversation" }
        require(rawTarget.role == USER_ROLE || rawTarget.role == ASSISTANT_ROLE) {
            "Timeline notices cannot be alternative targets"
        }
        val visible = materialise(graph.path, graph)
        val target = visible.single { it.id == messageId }
        require(target.status != MessageState.FAILED_ZDR || rawTarget.role == USER_ROLE) {
            "Failed ZDR messages are recovery targets only"
        }
        require(target.status != PENDING && target.status != STREAMING) {
            "Pending messages cannot be alternative targets"
        }

        if (rawTarget.role == ASSISTANT_ROLE) {
            val replyTo = rawTarget.replyToMessageId
                ?: error("Assistant target has no unambiguous user ancestor")
            val user = graph.messageById[replyTo]
                ?: error("Assistant target has no unambiguous user ancestor")
            require(user.conversationId == conversationId && user.role == USER_ROLE) {
                "Assistant target has no unambiguous user ancestor"
            }
            val userNode = graph.placementByMessage[replyTo]?.nodeId
            require(userNode == graph.path[pathIndex].parentId) {
                "Assistant target has no unambiguous user ancestor"
            }
        }

        return AlternativeSource(
            target = target,
            prefix = materialise(graph.path.take(pathIndex), graph),
            expectedPathIds = graph.path.map { it.id },
        )
    }

    private fun graphFor(conversationId: String): Graph {
        val scopedNodes = nodes.filter { it.conversationId == conversationId }
        require(scopedNodes.isNotEmpty()) { "Conversation has no timeline root" }
        check(scopedNodes.map { it.id }.toSet().size == scopedNodes.size) {
            "Timeline contains duplicate node IDs"
        }
        val nodeById = scopedNodes.associateBy { it.id }
        val roots = scopedNodes.filter { it.parentId == null }
        check(roots.size == 1) { "Conversation must have exactly one timeline root" }

        scopedNodes.forEach { node ->
            node.parentId?.let { parentId ->
                val parent = nodeById[parentId]
                check(parent != null && parent.conversationId == conversationId) {
                    "Timeline node ${node.id} has a missing parent"
                }
            }
        }
        scopedNodes.forEach { node ->
            val seen = mutableSetOf<String>()
            var current: TimelineNodeEntity? = node
            while (current != null) {
                check(seen.add(current.id)) { "Timeline contains a cycle" }
                current = current.parentId?.let(nodeById::get)
            }
        }

        val childrenByParent = scopedNodes.groupBy { it.parentId }
            .mapValues { (_, children) -> children.sortedWith(nodeOrder) }
        val selectionByParent = selections
            .filter { it.parentNodeId in nodeById }
            .associateBy { it.parentNodeId }
        check(selectionByParent.size == selections.count { it.parentNodeId in nodeById }) {
            "Timeline contains duplicate selections"
        }
        childrenByParent.forEach { (parentId, children) ->
            if (parentId != null && children.isNotEmpty()) {
                val selection = selectionByParent[parentId]
                check(selection != null) {
                    "Timeline has children but no selected child for $parentId"
                }
                check(selection.selectedNodeId in children.map { it.id }) {
                    "Timeline selection points outside its child group"
                }
            }
        }
        selectionByParent.values.forEach { selection ->
            val parent = nodeById[selection.parentNodeId]
            val child = nodeById[selection.selectedNodeId]
            check(parent != null && child != null && child.parentId == parent.id) {
                "Timeline selection does not select a direct child"
            }
        }

        val scopedMessages = messages.filter { it.conversationId == conversationId }
        check(scopedMessages.map { it.id }.toSet().size == scopedMessages.size) {
            "Conversation contains duplicate message IDs"
        }
        val messageById = scopedMessages.associateBy { it.id }
        val placementByMessage = placements.associateBy { it.messageId }
        check(placementByMessage.size == placements.count { messageById.containsKey(it.messageId) }) {
            "Message has more than one placement"
        }
        val placementsByNode = placements
            .filter { placement -> nodeById[placement.nodeId] != null }
            .groupBy { it.nodeId }
            .mapValues { (_, values) ->
                check(values.map { it.position }.toSet().size == values.size) {
                    "Timeline node contains duplicate placement positions"
                }
                values.sortedWith(compareBy<MessagePlacementEntity> { it.position }.thenBy { it.messageId })
            }
        placements.forEach { placement ->
            val node = nodeById[placement.nodeId]
            val message = messageById[placement.messageId]
            check(node != null && message != null) {
                "Placement references a missing graph row"
            }
            check(message.conversationId == node.conversationId) {
                "Placement crosses conversation boundaries"
            }
        }
        scopedMessages.forEach { message ->
            check(placementByMessage.containsKey(message.id)) {
                "Message ${message.id} has no placement"
            }
        }
        scopedNodes.filter { it.parentId != null && !it.anchorDeleted }.forEach { node ->
            check(placementsByNode[node.id].orEmpty().any { it.position == 0 }) {
                "Timeline node ${node.id} has no anchor placement"
            }
        }

        val path = buildList {
            val visited = mutableSetOf<String>()
            var current = roots.single()
            while (true) {
                check(visited.add(current.id)) { "Selected timeline contains a cycle" }
                add(current)
                val children = childrenByParent[current.id].orEmpty()
                if (children.isEmpty()) break
                val selection = selectionByParent[current.id]
                    ?: error("Timeline has children but no selected child for ${current.id}")
                current = nodeById[selection.selectedNodeId]
                    ?: error("Timeline selection points to a missing child")
            }
        }

        return Graph(
            nodeById = nodeById,
            childrenByParent = childrenByParent,
            path = path,
            placementsByNode = placementsByNode,
            messageById = messageById,
            placementByMessage = placementByMessage,
        )
    }

    private fun materialise(path: List<TimelineNodeEntity>, graph: Graph): List<Message> {
        val pathIndexByNode = path.withIndex().associate { it.value.id to it.index }
        return path.flatMap { node ->
            val nodeChoice = node.parentId?.let { parentId ->
                val siblings = graph.childrenByParent[parentId].orEmpty()
                siblings.takeIf { it.size > 1 }?.let { choices ->
                    BranchChoice(choices.map { it.id }, choices.indexOfFirst { it.id == node.id })
                }
            }
            val selectedChild = pathIndexByNode[node.id]
                ?.let { index -> path.getOrNull(index + 1) }
            val responseChoice = selectedChild?.let { child ->
                val siblings = graph.childrenByParent[node.id].orEmpty()
                val selectedMessage = anchorMessage(child, graph)
                if (nodeAnchorMessage(node, graph)?.role == USER_ROLE &&
                    siblings.size > 1 &&
                    childAnchorIsInvisible(child, selectedMessage)
                ) {
                    BranchChoice(siblings.map { it.id }, siblings.indexOfFirst { it.id == child.id })
                } else {
                    null
                }
            }

            graph.placementsByNode[node.id].orEmpty().map { placement ->
                val message = graph.messageById[placement.messageId]
                    ?: error("Placement references a missing message")
                val selectedResponse = selectedChild?.takeIf { it.parentId == node.id }
                val projectedStatus = if (message.role == USER_ROLE && selectedResponse != null) {
                    val responseMessage = anchorMessage(selectedResponse, graph)
                    when {
                        selectedResponse.anchorDeleted -> COMPLETE
                        responseMessage?.status == MessageState.FAILED_ZDR -> MessageState.FAILED_ZDR
                        message.status == MessageState.FAILED_ZDR -> COMPLETE
                        else -> message.status
                    }
                } else {
                    message.status
                }
                message.copy(
                    status = projectedStatus,
                    branchChoice = nodeChoice.takeIf { placement.position == 0 },
                    responseBranchChoice = responseChoice.takeIf { placement.position == 0 },
                )
            }
        }
    }

    private fun anchorMessage(node: TimelineNodeEntity, graph: Graph): Message? =
        graph.placementsByNode[node.id].orEmpty().firstOrNull { it.position == 0 }
            ?.let { graph.messageById[it.messageId] }

    private fun nodeAnchorMessage(node: TimelineNodeEntity, graph: Graph): Message? =
        anchorMessage(node, graph)

    private fun childAnchorIsInvisible(node: TimelineNodeEntity, message: Message?): Boolean =
        node.anchorDeleted || message?.status == MessageState.FAILED_ZDR

    private data class Graph(
        val nodeById: Map<String, TimelineNodeEntity>,
        val childrenByParent: Map<String?, List<TimelineNodeEntity>>,
        val path: List<TimelineNodeEntity>,
        val placementsByNode: Map<String, List<MessagePlacementEntity>>,
        val messageById: Map<String, Message>,
        val placementByMessage: Map<String, MessagePlacementEntity>,
    )

    private companion object {
        const val USER_ROLE = "USER"
        const val ASSISTANT_ROLE = "ASSISTANT"
        const val PENDING = "PENDING"
        const val STREAMING = "STREAMING"
        const val COMPLETE = "COMPLETE"
        val nodeOrder = compareBy<TimelineNodeEntity>({ it.ordinal }, { it.id })
    }
}

internal fun List<BranchTimelineRow>.toBranchTimeline(): BranchTimeline {
    val nodes = map { row ->
        TimelineNodeEntity(
            id = row.nodeId,
            conversationId = row.nodeConversationId,
            parentId = row.nodeParentId,
            ordinal = row.nodeOrdinal,
            anchorDeleted = row.nodeAnchorDeleted,
        )
    }.distinctBy { it.id }
    val placements = mapNotNull { row ->
        row.placementMessageId?.let { messageId ->
            MessagePlacementEntity(
                messageId = messageId,
                nodeId = requireNotNull(row.placementNodeId),
                position = requireNotNull(row.placementPosition),
            )
        }
    }.distinctBy { it.messageId }
    val selections = mapNotNull { row ->
        row.selectionParentNodeId?.let { parentNodeId ->
            BranchSelectionEntity(parentNodeId, requireNotNull(row.selectionSelectedNodeId))
        }
    }.distinctBy { it.parentNodeId }
    val messages = filter { it.messageId != null }
        .groupBy { requireNotNull(it.messageId) }
        .values
        .map { messageRows ->
            val first = messageRows.first()
            Message(
                id = requireNotNull(first.messageId),
                conversationId = requireNotNull(first.messageConversationId),
                role = requireNotNull(first.messageRole),
                content = requireNotNull(first.messageContent),
                modelId = first.messageModelId,
                createdAt = requireNotNull(first.messageCreatedAt),
                status = requireNotNull(first.messageStatus),
                attachments = messageRows.mapNotNull { row ->
                    row.attachmentId?.let { attachmentId ->
                        ImageAttachment(
                            id = attachmentId,
                            displayName = requireNotNull(row.attachmentDisplayName),
                            mimeType = requireNotNull(row.attachmentMimeType),
                            originalBytes = requireNotNull(row.attachmentOriginalBytes),
                            encryptedBytes = requireNotNull(row.attachmentEncryptedBytes),
                            width = requireNotNull(row.attachmentWidth),
                            height = requireNotNull(row.attachmentHeight),
                        )
                    }
                }.distinctBy { it.id },
                replyToMessageId = first.messageReplyToMessageId,
            )
        }
    return BranchTimeline(nodes, placements, selections, messages)
}
