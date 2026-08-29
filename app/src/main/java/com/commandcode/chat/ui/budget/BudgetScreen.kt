package com.commandcode.chat.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.commandcode.chat.data.budget.BudgetWindow
import com.commandcode.chat.ui.BudgetUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BudgetScreen(budget: BudgetUiState) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GOAT budget telemetry", style = MaterialTheme.typography.headlineSmall)
        Text("Estimated from this app", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
        BudgetCard("5 hours", budget.fiveHours)
        BudgetCard("Weekly", budget.weekly)
        BudgetCard("Monthly", budget.monthly)
        Text("Other clients using this API key are not included.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BudgetCard(label: String, window: BudgetWindow) {
    val cap = window.capCredits.toFloat().takeIf { it > 0f } ?: 1f
    val progress = (window.usedCredits.toFloat() / cap).coerceIn(0f, 1f)
    val reset = window.resetAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("d MMM, HH:mm")) ?: "after first usage"
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text("${window.usedCredits.toPlainString()} / ${window.capCredits.toPlainString()} credits", fontFamily = FontFamily.Monospace)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("Reset: $reset", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        }
    }
}
