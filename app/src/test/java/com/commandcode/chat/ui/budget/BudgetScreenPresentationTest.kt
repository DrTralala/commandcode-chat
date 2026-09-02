package com.commandcode.chat.ui.budget

import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.RemainingQuota
import com.commandcode.chat.data.service.UsedQuota
import com.commandcode.chat.ui.BudgetFreshness
import com.commandcode.chat.ui.BudgetUiState
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetScreenPresentationTest {
    @Test
    fun creditValuesUseHalfUpRoundingWithAtMostTwoDecimalPlaces() {
        assertEquals("1.23", formatCredits(1.234))
        assertEquals("1.24", formatCredits(1.235))
        assertEquals("1.2", formatCredits(1.2))
        assertEquals("1", formatCredits(1.0))
        assertEquals("0.01", formatCredits(0.005))
    }

    @Test
    fun titleUsesKnownPlanAndSafeFallback() {
        assertEquals("GOAT usage", budgetTitle("individual-goat"))
        assertEquals("Pro usage", budgetTitle("individual-pro-v1"))
        assertEquals("Command Code usage", budgetTitle(null))
        assertEquals("Command Code usage", budgetTitle("future-plan"))
    }

    @Test
    fun progressUsesConsumedMonthlyFractionAndClampsUsedQuotas() {
        assertEquals(0.4f, consumedQuotaFraction(42.0, 70.0))
        assertEquals(0.0f, consumedQuotaFraction(80.0, 70.0))
        assertEquals(1.0f, usedQuotaFraction(20.0, 14.0))
        assertEquals(0.0f, usedQuotaFraction(-1.0, 14.0))
    }

    @Test
    fun staleTimestampUsesLocalDisplayFormat() {
        assertEquals(
            "2026-08-30 12:00 Z",
            formatQuotaTimestamp(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun resetTimestampUsesDayMonthAndLowercaseTwelveHourTime() {
        assertEquals(
            "3 Sep 9:50pm",
            formatQuotaResetTimestamp(
                Instant.parse("2026-09-03T21:50:00Z"),
                ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun staleWithoutSnapshotPresentsUnavailableRetryState() {
        assertEquals(
            BudgetFreshness.UNAVAILABLE,
            presentedBudgetFreshness(BudgetUiState(freshness = BudgetFreshness.STALE)),
        )
        assertEquals(
            BudgetFreshness.STALE,
            presentedBudgetFreshness(
                BudgetUiState(snapshot = snapshot(), freshness = BudgetFreshness.STALE)
            ),
        )
    }

    private fun snapshot() = QuotaSnapshot(
        fetchedAt = Instant.parse("2026-08-30T12:00:00Z"),
        planId = "individual-goat",
        limited = true,
        monthly = RemainingQuota(42.0, 70.0),
        fiveHour = UsedQuota(2.0, 14.0, Instant.parse("2026-08-30T17:00:00Z")),
        weekly = UsedQuota(5.0, 35.0, Instant.parse("2026-09-06T12:00:00Z")),
        purchasedCredits = 0.0,
        freeCredits = 0.0,
    )
}
