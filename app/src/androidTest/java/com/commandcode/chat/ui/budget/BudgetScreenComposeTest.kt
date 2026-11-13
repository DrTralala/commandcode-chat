package com.commandcode.chat.ui.budget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.service.QuotaSnapshotCodec
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.RemainingQuota
import com.commandcode.chat.data.service.UsedQuota
import com.commandcode.chat.ui.BudgetFreshness
import com.commandcode.chat.ui.BudgetUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun liveBudgetRendersTelemetryAndEntryAndRefreshInvokeCallback() {
        var refreshCalls = 0
        val snapshot = testSnapshot()

        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(snapshot = snapshot, freshness = BudgetFreshness.LIVE),
                    onRefresh = { refreshCalls += 1 },
                )
            }
        }
        compose.waitForIdle()

        assertEquals(1, refreshCalls)
        assertTelemetry(snapshot, "Live")
        val titleBounds = compose.onNodeWithText("GOAT usage").fetchSemanticsNode().boundsInRoot
        val refreshBounds = compose.onNodeWithTag("budget_refresh").fetchSemanticsNode().boundsInRoot
        assertTrue("title=$titleBounds refresh=$refreshBounds", refreshBounds.left > titleBounds.left)
        assertTrue(
            "title=$titleBounds refresh=$refreshBounds",
            kotlin.math.abs(refreshBounds.center.y - titleBounds.center.y) < refreshBounds.height,
        )
        compose.onNodeWithTag("budget_refresh").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(2, refreshCalls)
    }

    @Test
    fun quotaWithoutPlanCapOrWindowsUsesGenericConditionalPresentation() {
        val snapshot = testSnapshot(
            planId = null,
            limited = null,
            monthly = RemainingQuota(remaining = 42.0, cap = null),
            fiveHour = null,
            weekly = null,
        )
        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(
                        snapshot = snapshot,
                        freshness = BudgetFreshness.LIVE,
                    ),
                    onRefresh = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Command Code usage").assertIsDisplayed()
        compose.onNodeWithText("Monthly").assertIsDisplayed()
        compose.onNodeWithText("Monthly usage unavailable").assertIsDisplayed()
        assertNoBalanceLabels()
        compose.onAllNodesWithTag("budget_monthly_progress").assertCountEquals(0)
        compose.onAllNodesWithTag("budget_five_hour_progress").assertCountEquals(0)
        compose.onAllNodesWithTag("budget_weekly_progress").assertCountEquals(0)
    }

    @Test
    fun unavailableRollingResetsKeepQuotaFiguresAndOmitResetLabels() {
        val snapshot = QuotaSnapshotCodec.decode(UNAVAILABLE_RESET_SNAPSHOT_JSON)

        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(snapshot = snapshot, freshness = BudgetFreshness.LIVE),
                    onRefresh = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Five-hour: 4.5 / 14 credits used").assertIsDisplayed()
        compose.onNodeWithText("Weekly: 22.75 / 35 credits used").assertIsDisplayed()
        compose.onNodeWithText("28 / 70 credits used").assertIsDisplayed()
        compose.onNodeWithTag("budget_five_hour_progress").assertIsDisplayed()
        compose.onNodeWithTag("budget_weekly_progress").assertIsDisplayed()
        compose.onNodeWithTag("budget_monthly_progress").assertIsDisplayed()
        compose.onAllNodesWithText("Resets", substring = true).assertCountEquals(0)
    }

    @Test
    fun expiredMonthlyRenewalIsNotPresented() {
        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(
                        snapshot = testSnapshot(
                            monthlyRenewsAt = Instant.parse("2000-09-03T21:50:00Z"),
                        ),
                        freshness = BudgetFreshness.LIVE,
                    ),
                    onRefresh = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("Renews", substring = true).assertCountEquals(0)
    }

    @Test
    fun staleBudgetRendersCachedTelemetryAndRefreshInvokesCallback() {
        var refreshCalls = 0
        val snapshot = testSnapshot()

        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(
                        snapshot = snapshot,
                        freshness = BudgetFreshness.STALE,
                        errorMessage = "Quota request failed. Showing cached data.",
                    ),
                    onRefresh = { refreshCalls += 1 },
                )
            }
        }
        compose.waitForIdle()

        assertEquals(1, refreshCalls)
        compose.onNodeWithText("Stale · last updated ${formatQuotaTimestamp(snapshot.fetchedAt)}")
            .fetchSemanticsNode()
        compose.onNodeWithTag("budget_error").fetchSemanticsNode()
        assertTelemetry(snapshot, "Stale · last updated ${formatQuotaTimestamp(snapshot.fetchedAt)}")
        compose.onNodeWithTag("budget_refresh").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(2, refreshCalls)
    }

    @Test
    fun unavailableBudgetRendersRetryAndRetryInvokesCallback() {
        var refreshCalls = 0

        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(),
                    onRefresh = { refreshCalls += 1 },
                )
            }
        }
        compose.waitForIdle()

        assertEquals(1, refreshCalls)
        compose.onNodeWithText("Command Code usage").assertIsDisplayed()
        val titleBounds = compose.onNodeWithText("Command Code usage").fetchSemanticsNode().boundsInRoot
        val retryBounds = compose.onNodeWithTag("budget_retry").fetchSemanticsNode().boundsInRoot
        assertTrue("title=$titleBounds retry=$retryBounds", retryBounds.left > titleBounds.left)
        assertTrue(
            "title=$titleBounds retry=$retryBounds",
            kotlin.math.abs(retryBounds.center.y - titleBounds.center.y) < retryBounds.height,
        )
        compose.onNodeWithTag("budget_freshness").assertIsDisplayed()
        compose.onNodeWithText("Budget unavailable").assertIsDisplayed()
        compose.onNodeWithTag("budget_retry").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(2, refreshCalls)
        compose.onAllNodesWithTag("budget_monthly_progress").assertCountEquals(0)
        compose.onAllNodesWithTag("budget_five_hour_progress").assertCountEquals(0)
        compose.onAllNodesWithTag("budget_weekly_progress").assertCountEquals(0)
    }

    @Test
    fun staleWithoutSnapshotRendersUnavailableRetryState() {
        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(freshness = BudgetFreshness.STALE),
                    onRefresh = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Budget unavailable").assertIsDisplayed()
        compose.onNodeWithTag("budget_retry").assertIsDisplayed()
        compose.onAllNodesWithTag("budget_refresh").assertCountEquals(0)
    }

    private fun assertTelemetry(snapshot: QuotaSnapshot, freshness: String) {
        val fiveHour = requireNotNull(snapshot.fiveHour)
        val weekly = requireNotNull(snapshot.weekly)
        compose.onNodeWithText("GOAT usage").fetchSemanticsNode()
        compose.onNodeWithText(freshness).fetchSemanticsNode()
        compose.onAllNodesWithText("Limited:", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Monthly").fetchSemanticsNode()
        compose.onNodeWithText("28 / 70 credits used").fetchSemanticsNode()
        compose.onNodeWithText("Plan-based allowance").fetchSemanticsNode()
        val renewal = requireNotNull(snapshot.monthlyRenewsAt)
        compose.onNodeWithText("Renews ${formatQuotaResetTimestamp(renewal)}").fetchSemanticsNode()
        compose.onAllNodesWithText(
            "Resets: ${formatQuotaResetTimestamp(renewal)}",
        ).assertCountEquals(0)
        compose.onNodeWithTag("budget_monthly_progress").fetchSemanticsNode()
        assertProgress("budget_monthly_progress", 28f / 70f)
        assertNoBalanceLabels()
        compose.onNodeWithText("Five-hour: 3.2 / 14 credits used").fetchSemanticsNode()
        compose.onNodeWithTag("budget_five_hour_progress").fetchSemanticsNode()
        assertProgress("budget_five_hour_progress", 3.2f / 14f)
        compose.onNodeWithText("Resets: ${formatQuotaResetTimestamp(requireNotNull(fiveHour.resetAt))}")
            .fetchSemanticsNode()
        compose.onNodeWithText("Weekly: 8 / 100 credits used").fetchSemanticsNode()
        compose.onNodeWithTag("budget_weekly_progress").fetchSemanticsNode()
        assertProgress("budget_weekly_progress", 0.08f)
        compose.onNodeWithText("Resets: ${formatQuotaResetTimestamp(requireNotNull(weekly.resetAt))}")
            .fetchSemanticsNode()
        val fiveHourBounds = compose.onNodeWithTag("budget_five_hour_progress").fetchSemanticsNode().boundsInRoot
        val weeklyBounds = compose.onNodeWithTag("budget_weekly_progress").fetchSemanticsNode().boundsInRoot
        val monthlyBounds = compose.onNodeWithTag("budget_monthly_progress").fetchSemanticsNode().boundsInRoot
        assertTrue("fiveHour=$fiveHourBounds weekly=$weeklyBounds", fiveHourBounds.bottom < weeklyBounds.top)
        assertTrue("weekly=$weeklyBounds monthly=$monthlyBounds", weeklyBounds.bottom < monthlyBounds.top)
    }

    private fun assertNoBalanceLabels() {
        compose.onAllNodesWithText("remaining", substring = true, ignoreCase = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Purchased credits", substring = true, ignoreCase = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Free credits", substring = true, ignoreCase = true)
            .assertCountEquals(0)
    }

    private fun assertProgress(tag: String, expected: Float) {
        val actual = compose.onNodeWithTag(tag).fetchSemanticsNode().config[
            SemanticsProperties.ProgressBarRangeInfo
        ].current
        assertEquals(expected, actual, 0.001f)
    }

    private fun testSnapshot(
        planId: String? = "individual-goat",
        limited: Boolean? = true,
        monthly: RemainingQuota = RemainingQuota(remaining = 42.0, cap = 70.0),
        fiveHour: UsedQuota? = UsedQuota(
            used = 3.2,
            cap = 14.0,
            resetAt = Instant.parse("2026-08-30T17:00:00Z"),
        ),
        weekly: UsedQuota? = UsedQuota(
            used = 8.0,
            cap = 100.0,
            resetAt = Instant.parse("2026-09-06T12:00:00Z"),
        ),
        monthlyRenewsAt: Instant? = Instant.parse("2099-09-03T21:50:00Z"),
    ) = QuotaSnapshot(
        fetchedAt = Instant.parse("2026-08-30T12:00:00Z"),
        planId = planId,
        limited = limited,
        monthly = monthly,
        fiveHour = fiveHour,
        weekly = weekly,
        purchasedCredits = 12.5,
        freeCredits = 4.0,
        monthlyRenewsAt = monthlyRenewsAt,
    )

    private companion object {
        private const val UNAVAILABLE_RESET_SNAPSHOT_JSON =
            "{\"schemaVersion\":4,\"fetchedAt\":1800000000000,\"planId\":\"individual-goat\"," +
                "\"monthlyRenewsAt\":1801000000000,\"monthly\":{\"remaining\":42.0,\"cap\":70.0}," +
                "\"windowLimits\":{\"limited\":true,\"fiveHour\":{\"used\":4.5,\"cap\":14.0,\"resetAt\":null}," +
                "\"weekly\":{\"used\":22.75,\"cap\":35.0,\"resetAt\":null}}," +
                "\"purchasedCredits\":9.5,\"freeCredits\":0.0}"
    }
}
