package com.commandcode.chat.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.commandcode.chat.data.service.commandCodePlan
import com.commandcode.chat.ui.BudgetFreshness
import com.commandcode.chat.ui.BudgetUiState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

internal fun consumedQuotaFraction(remaining: Double, cap: Double): Float =
    ((cap - remaining) / cap).toFloat().coerceIn(0f, 1f)

internal fun usedQuotaFraction(used: Double, cap: Double): Float =
    (used / cap).toFloat().coerceIn(0f, 1f)

internal fun formatQuotaTimestamp(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.getDefault()).withZone(zone).format(instant)

private val QuotaResetFormatter = DateTimeFormatterBuilder()
    .appendPattern("d MMM h:mm")
    .appendText(ChronoField.AMPM_OF_DAY, mapOf(0L to "am", 1L to "pm"))
    .toFormatter(Locale.ENGLISH)

internal fun formatQuotaResetTimestamp(
    instant: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = QuotaResetFormatter.withZone(zone).format(instant)

internal fun formatCredits(value: Double): String =
    BigDecimal.valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

internal fun budgetTitle(planId: String?): String =
    commandCodePlan(planId)?.let { "${it.displayName} usage" } ?: "Command Code usage"

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = budgetTitle(budget.snapshot?.planId),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
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
        }
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
        budget.snapshot?.let { snapshot ->
            snapshot.fiveHour?.let { fiveHour ->
                QuotaWindow(
                    label = "Five-hour",
                    used = fiveHour.used,
                    cap = fiveHour.cap,
                    resetAt = fiveHour.resetAt,
                    testTag = "budget_five_hour_progress",
                )
            }
            snapshot.weekly?.let { weekly ->
                QuotaWindow(
                    label = "Weekly",
                    used = weekly.used,
                    cap = weekly.cap,
                    resetAt = weekly.resetAt,
                    testTag = "budget_weekly_progress",
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val monthlyCap = snapshot.monthly.cap
                if (monthlyCap == null) {
                    Text("Monthly credits remaining: ${formatCredits(snapshot.monthly.remaining)}")
                } else {
                    Text(
                        "${formatCredits(snapshot.monthly.remaining)} / " +
                            "${formatCredits(monthlyCap)} credits remaining",
                    )
                    LinearProgressIndicator(
                        progress = { consumedQuotaFraction(snapshot.monthly.remaining, monthlyCap) },
                        modifier = Modifier.fillMaxWidth().testTag("budget_monthly_progress"),
                    )
                }
            }
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
        Text("Resets: ${formatQuotaResetTimestamp(resetAt)}")
    }
}
