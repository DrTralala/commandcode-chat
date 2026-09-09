package com.commandcode.chat.ui.chat

import com.commandcode.chat.ui.AppUiState
import java.math.BigDecimal
import java.math.RoundingMode

/** Percentage-only copy: never expose raw counts, including in accessibility text. */
data class ContextUsagePresentation(val label: String, val details: String)

fun contextUsagePresentation(state: AppUiState): ContextUsagePresentation {
    val measurement = state.tokenUsage.measurement
    val capacity = measurement?.let { state.contextWindows.limitFor(it.modelId) }
    val label = when {
        measurement == null -> "Context —"
        capacity == null -> "Context unavailable"
        else -> {
            val scaled = BigDecimal.valueOf(measurement.currentContextTokens).multiply(BigDecimal.valueOf(100))
            val limit = BigDecimal.valueOf(capacity)
            if (scaled.signum() > 0 && scaled < limit) "Context <1%"
            else "Context ≈${scaled.divide(limit, 0, RoundingMode.HALF_UP).toPlainString()}%"
        }
    }
    val details = buildList {
        add(label)
        if (measurement == null) {
            add("No completed response has reported usable context usage yet.")
        } else {
            val modelName = state.models.firstOrNull { it.apiId == measurement.modelId }?.displayName ?: measurement.modelId
            add("Last measured model: $modelName.")
            add("Estimated context after the last measured response, based on provider-reported input and output. It is not cumulative usage or an exact prediction of the next request.")
            if (capacity == null) add("A verified context-window limit is unavailable for this model.")
            if (state.selectedModel.apiId != measurement.modelId) {
                add("The selected model has changed. This percentage stays unchanged until a response from that model reports usage.")
            }
            if (state.sending && state.currentConversationId == state.activeConversationId) {
                add("A response is streaming. The previous measurement is shown until completion.")
            }
            if (state.tokenUsage.hasUnmeasuredAttempt) {
                add("There is a newer unmeasured or interrupted attempt; this is the last available measurement.")
            }
            val measuredIndex = state.messages.indexOfFirst { it.id == measurement.requestId }
            if (measuredIndex >= 0 && state.messages.drop(measuredIndex + 1).any {
                it.role in setOf("USER", "ASSISTANT") && it.status != "FAILED_ZDR"
            }) {
                add("This measurement covers an earlier part of the conversation, not the later messages.")
            }
        }
        add("Drafts, unsent attachments and subsequent system-prompt changes are not included. The next request may be counted differently by the provider.")
        add("Limits are bundled from ${state.contextWindows.source}" +
            state.contextWindows.verifiedAt.takeIf { it.isNotEmpty() }?.let { " (verified $it)." }.orEmpty())
    }.joinToString("\n\n")
    return ContextUsagePresentation(label, details)
}
