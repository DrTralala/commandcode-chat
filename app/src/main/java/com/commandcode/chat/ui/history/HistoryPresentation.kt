package com.commandcode.chat.ui.history

import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.domain.formatStorageBytes

enum class HistorySort { MOST_RECENT, LARGEST_FIRST, SMALLEST_FIRST }

private val byRecencyThenId =
    compareByDescending<Conversation> { it.updatedAt }.thenBy { it.id }

private fun comparatorFor(sort: HistorySort): Comparator<Conversation> = when (sort) {
    HistorySort.MOST_RECENT -> byRecencyThenId
    HistorySort.LARGEST_FIRST -> compareByDescending<Conversation> { it.totalBytes }.then(byRecencyThenId)
    HistorySort.SMALLEST_FIRST -> compareBy<Conversation> { it.totalBytes }.then(byRecencyThenId)
}

fun sortConversations(values: List<Conversation>, sort: HistorySort): List<Conversation> =
    values.sortedWith(comparatorFor(sort))

fun conversationSizeLabel(value: Conversation): String =
    "~${formatStorageBytes(value.totalBytes)} total · ${formatStorageBytes(value.attachmentBytes)} attachments"
