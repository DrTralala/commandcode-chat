package com.commandcode.chat.data.database

/** A creation-ordered selector for sibling timeline nodes. */
data class BranchChoice(val messageIds: List<String>, val selectedIndex: Int) {
    init {
        require(messageIds.isNotEmpty()) { "A branch choice needs at least one sibling" }
        require(selectedIndex in messageIds.indices) { "Selected branch index is out of range" }
    }

    val previousId: String? get() = messageIds.getOrNull(selectedIndex - 1)
    val nextId: String? get() = messageIds.getOrNull(selectedIndex + 1)
}

sealed interface AlternativeRequest {
    val messageId: String

    data class Edit(override val messageId: String, val text: String) : AlternativeRequest
    data class Retry(override val messageId: String) : AlternativeRequest
}

data class AlternativeSource(
    val target: Message,
    val prefix: List<Message>,
    val expectedPathIds: List<String>,
)
