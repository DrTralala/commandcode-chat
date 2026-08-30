package com.commandcode.chat.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.commandcode.chat.ui.BudgetFreshness
import com.commandcode.chat.ui.BudgetUiState
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun consumedQuotaFraction(remaining: Double, cap: Double): Float =
    ((cap - remaining) / cap).toFloat().coerceIn(0f, 1f)

internal fun usedQuotaFraction(used: Double, cap: Double): Float =
    (used / cap).toFloat().coerceIn(0f, 1f)

internal fun formatQuotaTimestamp(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.getDefault()).withZone(zone).format(instant)

private fun formatCredits(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

internal fun presentedBudgetFreshness(budget: BudgetUiState): BudgetFreshness =
    if (budget.freshness == BudgetFreshness.STALE && budget.snapshot == null) {
        BudgetFreshness.UNAVAILABLE
    } else {
        budget.freshness
    }

@Composable
fun BudgetScreen(budget: BudgetUiState, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val freshness = presentedBudgetFreshness(budget)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("budget_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GOAT budget telemetry", style = MaterialTheme.typography.headlineSmall)
        Text(
            when (freshness) {
                BudgetFreshness.LIVE -> "Live"
                BudgetFreshness.STALE -> budget.snapshot?.let {
                    "Stale · last updated ${formatQuotaTimestamp(it.fetchedAt)}"
                } ?: "Budget unavailable"
                BudgetFreshness.UNAVAILABLE -> "Budget unavailable"
            },
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag("budget_freshness"),
        )
        when (freshness) {
            BudgetFreshness.UNAVAILABLE -> Button(
                onClick = onRefresh,
                enabled = !budget.refreshing,
                modifier = Modifier.testTag("budget_retry"),
            ) { Text("Retry") }
            else -> OutlinedButton(
                onClick = onRefresh,
                enabled = !budget.refreshing,
                modifier = Modifier.testTag("budget_refresh"),
            ) { Text("Refresh") }
        }
        budget.snapshot?.let { snapshot ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Plan ID: ${snapshot.planId}")
                Text("Limited: ${if (snapshot.limited) "Yes" else "No"}")
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${formatCredits(snapshot.monthly.remaining)} / " +
                        "${formatCredits(snapshot.monthly.cap)} credits remaining",
                )
                LinearProgressIndicator(
                    progress = { consumedQuotaFraction(snapshot.monthly.remaining, snapshot.monthly.cap) },
                    modifier = Modifier.fillMaxWidth().testTag("budget_monthly_progress"),
                )
            }
            QuotaWindow(
                label = "Five-hour",
                used = snapshot.fiveHour.used,
                cap = snapshot.fiveHour.cap,
                resetAt = snapshot.fiveHour.resetAt,
                testTag = "budget_five_hour_progress",
            )
            QuotaWindow(
                label = "Weekly",
                used = snapshot.weekly.used,
                cap = snapshot.weekly.cap,
                resetAt = snapshot.weekly.resetAt,
                testTag = "budget_weekly_progress",
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Purchased credits: ${formatCredits(snapshot.purchasedCredits)}")
                Text("Free credits: ${formatCredits(snapshot.freeCredits)}")
            }
        }
        budget.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("budget_error"))
        }
    }
}

@Composable
private fun QuotaWindow(label: String, used: Double, cap: Double, resetAt: Instant, testTag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: ${formatCredits(used)} / ${formatCredits(cap)} credits used")
        LinearProgressIndicator(
            progress = { usedQuotaFraction(used, cap) },
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
        Text("Resets: ${formatQuotaTimestamp(resetAt)}")
    }
}
