package com.commandcode.chat.ui.history

import com.commandcode.chat.data.database.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPresentationTest {
    private fun conversation(
        id: String,
        updatedAt: Long = 2,
        textBytes: Long = 0,
        attachmentBytes: Long = 0,
        attachmentCount: Int = 0,
    ) = Conversation(
        id = id,
        title = id,
        defaultModelId = "gpt-5.6-sol",
        createdAt = 0,
        updatedAt = updatedAt,
        textBytes = textBytes,
        attachmentBytes = attachmentBytes,
        attachmentCount = attachmentCount,
    )

    @Test fun mostRecentSortsUpdatedAtDescendingThenIdAscending() {
        val a = conversation("a", updatedAt = 2)
        val b = conversation("b", updatedAt = 2)
        val c = conversation("c", updatedAt = 1)

        assertEquals(
            listOf("a", "b", "c"),
            sortConversations(listOf(c, b, a), HistorySort.MOST_RECENT).map { it.id },
        )
    }

    @Test fun largestFirstSortsTotalBytesDescending() {
        val a = conversation("a", textBytes = 10)
        val b = conversation("b", textBytes = 10)
        val c = conversation("c", updatedAt = 1, textBytes = 11)

        assertEquals(
            listOf("c", "a", "b"),
            sortConversations(listOf(b, c, a), HistorySort.LARGEST_FIRST).map { it.id },
        )
    }

    @Test fun smallestFirstSortsTotalBytesAscending() {
        val a = conversation("a", textBytes = 10)
        val b = conversation("b", textBytes = 10)
        val c = conversation("c", updatedAt = 1, textBytes = 11)

        assertEquals(
            listOf("a", "b", "c"),
            sortConversations(listOf(b, c, a), HistorySort.SMALLEST_FIRST).map { it.id },
        )
    }

    @Test fun sizeSortsUseRawTotalBytesNotRoundedLabel() {
        val a = conversation("a", updatedAt = 2, textBytes = 10)
        val b = conversation("b", updatedAt = 2, textBytes = 11)
        val c = conversation("c", updatedAt = 2, textBytes = 12)

        assertEquals(
            listOf("c", "b", "a"),
            sortConversations(listOf(a, b, c), HistorySort.LARGEST_FIRST).map { it.id },
        )
    }

    @Test fun sizeSortsAddTextAndAttachmentBytesForTotal() {
        val a = conversation("a", updatedAt = 2, attachmentBytes = 10)
        val b = conversation("b", updatedAt = 2, textBytes = 9, attachmentBytes = 2)
        val c = conversation("c", updatedAt = 2, textBytes = 10)

        assertEquals(
            listOf("b", "a", "c"),
            sortConversations(listOf(a, b, c), HistorySort.LARGEST_FIRST).map { it.id },
        )
    }

    @Test fun differentByteTotalsSharingOneRoundedLabelStillSortByRawBytes() {
        val a = conversation("a", updatedAt = 2, textBytes = 999_950_000, attachmentBytes = 0)
        val b = conversation("b", updatedAt = 2, textBytes = 999_949_999, attachmentBytes = 0)

        assertEquals(
            listOf("a", "b"),
            sortConversations(listOf(b, a), HistorySort.LARGEST_FIRST).map { it.id },
        )
        assertEquals(
            listOf("b", "a"),
            sortConversations(listOf(b, a), HistorySort.SMALLEST_FIRST).map { it.id },
        )
    }

    @Test fun sortDoesNotMutateInputList() {
        val input = listOf(
            conversation("b", updatedAt = 2, textBytes = 11),
            conversation("a", updatedAt = 2, textBytes = 10),
        )

        sortConversations(input, HistorySort.LARGEST_FIRST)

        assertEquals(listOf("b", "a"), input.map { it.id })
    }

    @Test fun sizeLabelShowsTotalAndAttachmentBreakdown() {
        assertEquals(
            "~8.40 MB total · 8.30 MB attachments",
            conversationSizeLabel(
                conversation("x", textBytes = 100_000, attachmentBytes = 8_300_000),
            ),
        )
    }

    @Test fun textOnlyConversationShowsZeroAttachments() {
        assertEquals(
            "~0.01 MB total · 0 MB attachments",
            conversationSizeLabel(conversation("x", textBytes = 10_000)),
        )
    }

    @Test fun historySortLabelsMatchTheHistoryControlCopy() {
        assertEquals("Most recent", historySortLabel(HistorySort.MOST_RECENT))
        assertEquals("Largest first", historySortLabel(HistorySort.LARGEST_FIRST))
        assertEquals("Smallest first", historySortLabel(HistorySort.SMALLEST_FIRST))
    }
}
