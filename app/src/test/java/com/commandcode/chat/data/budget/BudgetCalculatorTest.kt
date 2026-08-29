package com.commandcode.chat.data.budget

import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class BudgetCalculatorTest {

    private val sol = ChatModel.SOL
    private val luna = ChatModel.LUNA

    private fun bd(value: String) = BigDecimal(value)

    private fun event(id: String, timestamp: Instant, model: ChatModel, usage: TokenUsage): UsageEvent {
        val estimate = BudgetCalculator.estimate(model, usage)
        return UsageEvent(
            id = id,
            model = model,
            timestamp = timestamp,
            usage = usage,
            estimatedModelCost = estimate.modelCost,
            estimatedGoatCredits = estimate.goatCredits,
        )
    }

    private fun eventWithoutUsage(id: String, timestamp: Instant, model: ChatModel): UsageEvent =
        UsageEvent(id, model, timestamp, usage = null, estimatedModelCost = null, estimatedGoatCredits = null)

    // ---- Cost estimates ----

    @Test
    fun solEstimateMatchesBriefValues() {
        val estimate = BudgetCalculator.estimate(sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1))
        assertEquals(0, bd("0.000080").compareTo(estimate.modelCost))
        assertEquals(0, bd("0.000080").compareTo(estimate.goatCredits))
    }

    @Test
    fun lunaEstimateUsesTriplePointFiveMultiplier() {
        val estimate = BudgetCalculator.estimate(luna, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1))
        assertEquals(0, bd("0.0000032").compareTo(estimate.modelCost))
        assertEquals(0, bd("0.0000112").compareTo(estimate.goatCredits))
    }

    @Test
    fun solFreshCachedAndOutputComponentsAreDistinguished() {
        val fresh = BudgetCalculator.estimate(sol, TokenUsage(inputTokens = 10, cachedInputTokens = null, outputTokens = 0))
        val cached = BudgetCalculator.estimate(sol, TokenUsage(inputTokens = 10, cachedInputTokens = 10, outputTokens = 0))
        val output = BudgetCalculator.estimate(sol, TokenUsage(inputTokens = 0, cachedInputTokens = 0, outputTokens = 1))
        assertEquals(0, bd("0.000050").compareTo(fresh.modelCost))
        assertEquals(0, bd("0.000005").compareTo(cached.modelCost))
        assertEquals(0, bd("0.000030").compareTo(output.modelCost))
    }

    @Test
    fun negativeTokenCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetCalculator.estimate(sol, TokenUsage(inputTokens = -1, cachedInputTokens = 0, outputTokens = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetCalculator.estimate(sol, TokenUsage(inputTokens = 0, cachedInputTokens = 0, outputTokens = -1))
        }
    }

    @Test
    fun cachedTokensBeyondInputAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetCalculator.estimate(sol, TokenUsage(inputTokens = 5, cachedInputTokens = 10, outputTokens = 1))
        }
    }

    // ---- Anchored windows ----

    @Test
    fun noActiveWindowReturnsZeroUsageAndNoReset() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        val window = BudgetCalculator.currentWindow(emptyList(), now, Duration.ofHours(5), bd("1.00"))
        assertEquals(BigDecimal.ZERO, window.usedCredits)
        assertEquals(bd("1.00"), window.capCredits)
        assertEquals(null, window.startedAt)
        assertEquals(null, window.resetAt)
    }

    @Test
    fun firstRequestAnchorsWindowStart() {
        val start = Instant.parse("2026-08-29T12:00:00Z")
        val events = listOf(event("1", start, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)))
        val window = BudgetCalculator.currentWindow(events, start.plusSeconds(1), Duration.ofHours(5), bd("1.00"))
        assertEquals(start, window.startedAt)
    }

    @Test
    fun resetInstantIsExactlyStartPlusDuration() {
        val windowStart = Instant.parse("2026-08-29T12:00:00Z")
        val events = listOf(event("1", windowStart, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)))
        val window = BudgetCalculator.currentWindow(events, windowStart.plusSeconds(1), Duration.ofHours(5), bd("1.00"))
        assertEquals(windowStart.plus(Duration.ofHours(5)), window.resetAt)
    }

    @Test
    fun secondRequestWithinBucketAccumulatesUsage() {
        val start = Instant.parse("2026-08-29T12:00:00Z")
        val mid = start.plus(Duration.ofHours(2))
        val events = listOf(
            event("1", start, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
            event("2", mid, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
        )
        val window = BudgetCalculator.currentWindow(events, mid.plusSeconds(1), Duration.ofHours(5), bd("1.00"))
        assertEquals(0, bd("0.00016").compareTo(window.usedCredits))
        assertEquals(start, window.startedAt)
        assertEquals(start.plus(Duration.ofHours(5)), window.resetAt)
    }

    @Test
    fun bucketExpiredAtResetInstantHasZeroUsageAndNoReset() {
        val start = Instant.parse("2026-08-29T12:00:00Z")
        val atReset = start.plus(Duration.ofHours(5))
        val events = listOf(event("1", start, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)))
        val window = BudgetCalculator.currentWindow(events, atReset, Duration.ofHours(5), bd("1.00"))
        assertEquals(BigDecimal.ZERO, window.usedCredits)
        assertEquals(null, window.startedAt)
        assertEquals(null, window.resetAt)
    }

    @Test
    fun eventExactlyAtResetStartsNextBucket() {
        val start = Instant.parse("2026-08-29T12:00:00Z")
        val secondStart = start.plus(Duration.ofHours(5))
        val events = listOf(
            event("1", start, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
            event("2", secondStart, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
        )
        val window = BudgetCalculator.currentWindow(events, secondStart.plusSeconds(30), Duration.ofHours(5), bd("1.00"))
        assertEquals(0, bd("0.000080").compareTo(window.usedCredits))
        assertEquals(secondStart, window.startedAt)
        assertEquals(secondStart.plus(Duration.ofHours(5)), window.resetAt)
    }

    @Test
    fun windowAccumulatesLunaGoatCredits() {
        val start = Instant.parse("2026-08-29T12:00:00Z")
        val events = listOf(
            event("1", start, luna, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
            event("2", start.plusSeconds(1), luna, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
        )
        val window = BudgetCalculator.currentWindow(events, start.plusSeconds(2), Duration.ofHours(5), bd("1.00"))
        assertEquals(0, bd("0.0000224").compareTo(window.usedCredits))
    }

    @Test
    fun eventsAreSettledByTimestamp() {
        val earlier = Instant.parse("2026-08-29T12:00:00Z")
        val later = earlier.plus(Duration.ofHours(1))
        val events = listOf(
            event("1", later, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
            event("2", earlier, sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)),
        )
        val window = BudgetCalculator.currentWindow(events, later.plusSeconds(1), Duration.ofHours(5), bd("1.00"))
        assertEquals(0, bd("0.00016").compareTo(window.usedCredits))
        assertEquals(earlier, window.startedAt)
    }

    @Test
    fun unknownUsageContributesNothingButStillAnchors() {
        val start = Instant.parse("2026-08-29T12:00:00Z")
        val events = listOf(eventWithoutUsage("1", start, sol))
        val window = BudgetCalculator.currentWindow(events, start.plusSeconds(1), Duration.ofHours(5), bd("1.00"))
        assertEquals(BigDecimal.ZERO, window.usedCredits)
        assertEquals(start, window.startedAt)
        assertEquals(start.plus(Duration.ofHours(5)), window.resetAt)
    }

    // ---- Monthly windows and billing-day clamping ----

    @Test
    fun billingDayOneIsHonouredInShortMonths() {
        val now = ZonedDateTime.of(2026, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC)
        val events = listOf(event("1", now.toInstant(), sol, TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)))
        val window = BudgetCalculator.monthlyWindow(events, now, billingDay = 1)
        assertEquals(ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.startedAt)
        assertEquals(ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.resetAt)
    }

    @Test
    fun billingDay28IsClampedToFebruaryLength() {
        val now = ZonedDateTime.of(2026, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC)
        val window = BudgetCalculator.monthlyWindow(emptyList(), now, billingDay = 28)
        assertEquals(ZonedDateTime.of(2026, 1, 28, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.startedAt)
        assertEquals(ZonedDateTime.of(2026, 2, 28, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.resetAt)
    }

    @Test
    fun billingDay29IsClampedToFebruaryLength() {
        val now = ZonedDateTime.of(2026, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC)
        val window = BudgetCalculator.monthlyWindow(emptyList(), now, billingDay = 29)
        assertEquals(ZonedDateTime.of(2026, 1, 29, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.startedAt)
        assertEquals(ZonedDateTime.of(2026, 2, 28, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.resetAt)
    }

    @Test
    fun billingDay30IsClampedToFebruaryLength() {
        val now = ZonedDateTime.of(2026, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC)
        val window = BudgetCalculator.monthlyWindow(emptyList(), now, billingDay = 30)
        assertEquals(ZonedDateTime.of(2026, 1, 30, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.startedAt)
        assertEquals(ZonedDateTime.of(2026, 2, 28, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.resetAt)
    }

    @Test
    fun billingDay31IsClampedToFebruaryLength() {
        val now = ZonedDateTime.of(2026, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC)
        val window = BudgetCalculator.monthlyWindow(emptyList(), now, billingDay = 31)
        assertEquals(ZonedDateTime.of(2026, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.startedAt)
        assertEquals(ZonedDateTime.of(2026, 2, 28, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.resetAt)
    }

    @Test
    fun billingDay31ClampsToThirtyDayMonths() {
        val now = ZonedDateTime.of(2026, 4, 15, 12, 0, 0, 0, ZoneOffset.UTC)
        val window = BudgetCalculator.monthlyWindow(emptyList(), now, billingDay = 31)
        assertEquals(ZonedDateTime.of(2026, 3, 31, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.startedAt)
        assertEquals(ZonedDateTime.of(2026, 4, 30, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(), window.resetAt)
    }

    @Test
    fun monthlyWindowSumsEventsWithinCurrentCycle() {
        val now = ZonedDateTime.of(2026, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC)
        val cycleStart = ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val cycleReset = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val usage = TokenUsage(inputTokens = 10, cachedInputTokens = 0, outputTokens = 1)
        val events = listOf(
            event("1", cycleStart.plusSeconds(1), sol, usage),
            event("2", cycleStart.plusSeconds(2), luna, usage),
            event("3", cycleReset.minusSeconds(1), sol, usage),
            event("4", cycleReset.plusSeconds(1), sol, usage),
        )
        val window = BudgetCalculator.monthlyWindow(events, now, billingDay = 1)
        assertEquals(0, bd("0.0001712").compareTo(window.usedCredits))
    }

    @Test
    fun invalidBillingDayIsRejected() {
        val now = ZonedDateTime.of(2026, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC)
        assertThrows(IllegalArgumentException::class.java) {
            BudgetCalculator.monthlyWindow(emptyList(), now, billingDay = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetCalculator.monthlyWindow(emptyList(), now, billingDay = 32)
        }
    }
}
