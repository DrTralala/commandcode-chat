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
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.RemainingQuota
import com.commandcode.chat.data.service.UNREPORTED_PLAN_ID
import com.commandcode.chat.data.service.UsedQuota
import com.commandcode.chat.ui.BudgetFreshness
import com.commandcode.chat.ui.BudgetUiState
import java.time.Instant
import org.junit.Assert.assertEquals
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
        compose.onNodeWithTag("budget_refresh").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(2, refreshCalls)
    }

    @Test
    fun currentQuotaSchemaDoesNotExposeTheInternalPlanSentinel() {
        compose.setContent {
            MaterialTheme {
                BudgetScreen(
                    budget = BudgetUiState(
                        snapshot = testSnapshot(planId = UNREPORTED_PLAN_ID),
                        freshness = BudgetFreshness.LIVE,
                    ),
                    onRefresh = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Plan unavailable").assertIsDisplayed()
        compose.onAllNodesWithText(UNREPORTED_PLAN_ID).assertCountEquals(0)
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
        compose.onNodeWithText("GOAT budget telemetry").assertIsDisplayed()
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
        compose.onNodeWithText("GOAT budget telemetry").fetchSemanticsNode()
        compose.onNodeWithText(freshness).fetchSemanticsNode()
        compose.onNodeWithText("Plan ID: ${snapshot.planId}").fetchSemanticsNode()
        compose.onNodeWithText("Limited: Yes").fetchSemanticsNode()
        compose.onNodeWithText("42 / 70 credits remaining").fetchSemanticsNode()
        compose.onNodeWithTag("budget_monthly_progress").fetchSemanticsNode()
        assertProgress("budget_monthly_progress", 0.4f)
        compose.onNodeWithText("Five-hour: 3.2 / 14 credits used").fetchSemanticsNode()
        compose.onNodeWithTag("budget_five_hour_progress").fetchSemanticsNode()
        assertProgress("budget_five_hour_progress", 3.2f / 14f)
        compose.onNodeWithText("Resets: ${formatQuotaTimestamp(snapshot.fiveHour.resetAt)}").fetchSemanticsNode()
        compose.onNodeWithText("Weekly: 8 / 100 credits used").fetchSemanticsNode()
        compose.onNodeWithTag("budget_weekly_progress").fetchSemanticsNode()
        assertProgress("budget_weekly_progress", 0.08f)
        compose.onNodeWithText("Resets: ${formatQuotaTimestamp(snapshot.weekly.resetAt)}").fetchSemanticsNode()
        compose.onNodeWithText("Purchased credits: 12.5").fetchSemanticsNode()
        compose.onNodeWithText("Free credits: 4").fetchSemanticsNode()
    }

    private fun assertProgress(tag: String, expected: Float) {
        val actual = compose.onNodeWithTag(tag).fetchSemanticsNode().config[
            SemanticsProperties.ProgressBarRangeInfo
        ].current
        assertEquals(expected, actual, 0.001f)
    }

    private fun testSnapshot(planId: String = "goat-pro") = QuotaSnapshot(
        fetchedAt = Instant.parse("2026-08-30T12:00:00Z"),
        planId = planId,
        limited = true,
        monthly = RemainingQuota(remaining = 42.0, cap = 70.0),
        fiveHour = UsedQuota(
            used = 3.2,
            cap = 14.0,
            resetAt = Instant.parse("2026-08-30T17:00:00Z"),
        ),
        weekly = UsedQuota(
            used = 8.0,
            cap = 100.0,
            resetAt = Instant.parse("2026-09-06T12:00:00Z"),
        ),
        purchasedCredits = 12.5,
        freeCredits = 4.0,
    )
}
