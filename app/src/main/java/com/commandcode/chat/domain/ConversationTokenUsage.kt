package com.commandcode.chat.domain

/** Provider reports for request attempts, including interrupted and unmeasured attempts. */
data class ReportedUsage(
    val id: String,
    val requestId: String?,
    val modelId: String,
    val timestamp: Long,
    val inputTokens: Long?,
    val cachedInputTokens: Long?,
    val outputTokens: Long?,
    val complete: Boolean,
)

data class ContextMeasurement(
    val eventId: String,
    val requestId: String?,
    val modelId: String,
    val timestamp: Long,
    val currentContextTokens: Long,
)

data class ConversationTokenUsage(
    val cumulativeInputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cumulativeOutputTokens: Long? = null,
    val totalProcessedTokens: Long? = null,
    val measurement: ContextMeasurement? = null,
    val accountingIncomplete: Boolean = true,
    val hasUnmeasuredAttempt: Boolean = false,
) {
    companion object {
        fun aggregate(events: List<ReportedUsage>): ConversationTokenUsage {
            if (events.isEmpty()) return ConversationTokenUsage()
            val ordered = events.sortedWith(compareBy({ it.timestamp }, { it.id }))
            val measured = ordered.lastOrNull {
                it.complete && addCounts(it.inputTokens, it.outputTokens) != null
            }
            val input = sumCounts(events.map { it.inputTokens })
            val output = sumCounts(events.map { it.outputTokens })
            val cached = sumCounts(events.map { event ->
                event.cachedInputTokens?.takeIf { it >= 0 && event.inputTokens != null && it <= event.inputTokens }
            })
            val processed = addCounts(input, output)
            return ConversationTokenUsage(
                cumulativeInputTokens = input,
                cachedInputTokens = cached,
                cumulativeOutputTokens = output,
                totalProcessedTokens = processed,
                measurement = measured?.let {
                    ContextMeasurement(it.id, it.requestId, it.modelId, it.timestamp,
                        requireNotNull(addCounts(it.inputTokens, it.outputTokens)))
                },
                accountingIncomplete = events.any { !it.complete } || processed == null || cached == null,
                hasUnmeasuredAttempt = measured?.id != ordered.last().id,
            )
        }

        private fun sumCounts(values: List<Long?>): Long? =
            values.fold(0L as Long?) { sum, value -> addCounts(sum, value) }

        private fun addCounts(a: Long?, b: Long?): Long? {
            if (a == null || b == null || a < 0 || b < 0 || a > Long.MAX_VALUE - b) return null
            return a + b
        }
    }
}
