package com.commandcode.chat.domain

import org.junit.Assert.*
import org.junit.Test

class ConversationTokenUsageTest {
    private fun event(id: String, at: Long, input: Long? = 500, output: Long? = 200,
                      cached: Long? = 100, complete: Boolean = true) =
        ReportedUsage(id, id, "model", at, input, cached, output, complete)

    @Test fun latestContextIsNotCumulativeAndCacheIsNotAdded() {
        val usage = ConversationTokenUsage.aggregate(listOf(
            event("a", 1), event("b", 2, 750, 300), event("c", 3, 1100, 250),
        ))
        assertEquals(2350L, usage.cumulativeInputTokens)
        assertEquals(750L, usage.cumulativeOutputTokens)
        assertEquals(300L, usage.cachedInputTokens)
        assertEquals(3100L, usage.totalProcessedTokens)
        assertEquals(1350L, usage.measurement?.currentContextTokens)
        assertFalse(usage.accountingIncomplete)
    }

    @Test fun interruptedReportsCountButDoNotReplaceCompletedMeasurement() {
        val usage = ConversationTokenUsage.aggregate(listOf(event("a", 1), event("b", 2, complete = false)))
        assertEquals(1400L, usage.totalProcessedTokens)
        assertEquals("a", usage.measurement?.requestId)
        assertTrue(usage.accountingIncomplete)
        assertTrue(usage.hasUnmeasuredAttempt)
    }

    @Test fun absentCountsAreUnknownNotZeroAndDoNotEraseEarlierMeasurement() {
        val usage = ConversationTokenUsage.aggregate(listOf(event("a", 1), event("b", 2, null, null, null)))
        assertNull(usage.totalProcessedTokens)
        assertNull(usage.cachedInputTokens)
        assertEquals(700L, usage.measurement?.currentContextTokens)
        assertTrue(usage.hasUnmeasuredAttempt)
        assertTrue(usage.accountingIncomplete)
        assertNull(ConversationTokenUsage.aggregate(emptyList()).measurement)
    }

    @Test fun invalidAndOverflowingCountsNeverWrapOrBecomeMeasurements() {
        for (bad in listOf(event("b", 2, -1), event("b", 2, Long.MAX_VALUE, 1))) {
            val usage = ConversationTokenUsage.aggregate(listOf(event("a", 1), bad))
            assertEquals("a", usage.measurement?.requestId)
            assertNull(usage.totalProcessedTokens)
            assertTrue(usage.accountingIncomplete)
        }
        val sumOverflow = ConversationTokenUsage.aggregate(listOf(
            event("a", 1, Long.MAX_VALUE, 0, 0), event("b", 2, 1, 0, 0),
        ))
        assertNull(sumOverflow.cumulativeInputTokens)
        assertEquals(1L, sumOverflow.measurement?.currentContextTokens)
        assertTrue(sumOverflow.accountingIncomplete)
    }

    @Test fun missingOrImpossibleCachedDetailDoesNotInvalidatePromptMeasurement() {
        for (cached in listOf(null, -1L, 501L)) {
            val usage = ConversationTokenUsage.aggregate(listOf(event("a", 1, cached = cached)))
            assertNull(usage.cachedInputTokens)
            assertEquals(700L, usage.measurement?.currentContextTokens)
        }
    }

    @Test fun orderingUsesAttemptTimestampNotInputOrder() {
        val usage = ConversationTokenUsage.aggregate(listOf(event("z", 2, 10, 5), event("a", 1)))
        assertEquals("z", usage.measurement?.requestId)
        assertEquals(15L, usage.measurement?.currentContextTokens)
    }
}
