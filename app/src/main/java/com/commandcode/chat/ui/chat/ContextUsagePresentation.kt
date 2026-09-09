package com.commandcode.chat.ui.chat

import com.commandcode.chat.ui.AppUiState
import java.math.BigDecimal
import java.math.RoundingMode

/** Percentage-only copy: never expose raw counts, including in accessibility text. */
data class ContextUsagePresentation(val label: String, val level: ContextUsageLevel)

enum class ContextUsageLevel { UNKNOWN, LOW, MEDIUM, HIGH }

fun contextUsagePresentation(state: AppUiState): ContextUsagePresentation {
    val measurement = state.tokenUsage.measurement
    val capacity = measurement?.let { state.contextWindows.limitFor(it.modelId) }
    if (measurement == null || capacity == null) {
        return ContextUsagePresentation("—", ContextUsageLevel.UNKNOWN)
    }
    val percentage = BigDecimal.valueOf(measurement.currentContextTokens)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(capacity), 0, RoundingMode.HALF_UP)
    // Compare the displayed integer, without narrowing large provider values to Int/Long.
    val level = when {
        percentage >= BigDecimal.valueOf(80) -> ContextUsageLevel.HIGH
        percentage >= BigDecimal.valueOf(50) -> ContextUsageLevel.MEDIUM
        else -> ContextUsageLevel.LOW
    }
    return ContextUsagePresentation("${percentage.toPlainString()}%", level)
}
