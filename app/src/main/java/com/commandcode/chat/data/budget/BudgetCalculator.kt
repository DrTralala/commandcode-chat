package com.commandcode.chat.data.budget

import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

data class CostEstimate(
    val modelCost: BigDecimal,
    val goatCredits: BigDecimal,
)

data class BudgetWindow(
    val usedCredits: BigDecimal,
    val capCredits: BigDecimal,
    val startedAt: Instant?,
    val resetAt: Instant?,
)

object BudgetCalculator {

    private val PER_MILLION = BigDecimal("1000000")

    /** GOAT credits = model-dollar usage * (70 / model monthly allowance). */
    fun estimate(model: ChatModel, usage: TokenUsage): CostEstimate {
        require(usage.inputTokens >= 0) { "inputTokens must not be negative" }
        require(usage.outputTokens >= 0) { "outputTokens must not be negative" }
        val cachedInput = requireNotNull(usage.cachedInputTokens) {
            "cachedInputTokens are required for a detailed estimate"
        }
        require(cachedInput >= 0) { "cachedInputTokens must not be negative" }
        require(cachedInput <= usage.inputTokens) { "cachedInputTokens must not exceed inputTokens" }

        val freshInput = usage.inputTokens - cachedInput
        val freshInputCost = freshInput.toBigDecimal()
            .multiply(model.rates.inputPerMillion, MathContext.DECIMAL128)
            .divide(PER_MILLION, MathContext.DECIMAL128)
        val cachedInputCost = cachedInput.toBigDecimal()
            .multiply(model.rates.cachedInputPerMillion, MathContext.DECIMAL128)
            .divide(PER_MILLION, MathContext.DECIMAL128)
        val outputCost = usage.outputTokens.toBigDecimal()
            .multiply(model.rates.outputPerMillion, MathContext.DECIMAL128)
            .divide(PER_MILLION, MathContext.DECIMAL128)

        val modelCost = freshInputCost
            .add(cachedInputCost, MathContext.DECIMAL128)
            .add(outputCost, MathContext.DECIMAL128)
            .stripTrailingZeros()
        val goatCredits = modelCost
            .multiply(model.goatMultiplier, MathContext.DECIMAL128)
            .stripTrailingZeros()
        return CostEstimate(modelCost, goatCredits)
    }

    fun estimateIfDetailed(model: ChatModel, usage: TokenUsage): CostEstimate? =
        if (usage.cachedInputTokens == null) null else estimate(model, usage)

    fun currentWindow(
        events: List<UsageEvent>,
        now: Instant,
        duration: Duration,
        cap: BigDecimal,
    ): BudgetWindow {
        val byTime = events.sortedBy { it.timestamp }
        var start: Instant? = null
        var usedCredits = BigDecimal.ZERO
        for (event in byTime) {
            if (event.timestamp > now) continue
            if (start == null) {
                start = event.timestamp
                usedCredits = BigDecimal.ZERO
            } else if (event.timestamp >= start.plus(duration)) {
                start = event.timestamp
                usedCredits = BigDecimal.ZERO
            }
            usedCredits = usedCredits.add(
                event.estimatedGoatCredits ?: BigDecimal.ZERO,
                MathContext.DECIMAL128,
            )
        }
        return when {
            start == null -> BudgetWindow(BigDecimal.ZERO, cap, null, null)
            start.plus(duration) <= now -> BudgetWindow(BigDecimal.ZERO, cap, null, null)
            else -> BudgetWindow(usedCredits.stripTrailingZeros(), cap, start, start.plus(duration))
        }
    }

    fun monthlyWindow(
        events: List<UsageEvent>,
        now: ZonedDateTime,
        billingDay: Int,
    ): BudgetWindow {
        require(billingDay in 1..31) { "billingDay must be in 1..31" }
        val zone = now.zone
        val cycleStart = currentCycleStart(now.toLocalDate(), billingDay)
        val cycleReset = nextCycleStart(cycleStart, billingDay)
        var usedCredits = BigDecimal.ZERO
        val cycleStartInstant = cycleStart.atStartOfDay(zone).toInstant()
        val cycleResetInstant = cycleReset.atStartOfDay(zone).toInstant()
        for (event in events) {
            val instant = event.timestamp
            if (instant >= cycleStartInstant && instant < cycleResetInstant) {
                usedCredits = usedCredits.add(
                    event.estimatedGoatCredits ?: BigDecimal.ZERO,
                    MathContext.DECIMAL128,
                )
            }
        }
        return BudgetWindow(
            usedCredits = usedCredits.stripTrailingZeros(),
            capCredits = BigDecimal.ZERO,
            startedAt = cycleStartInstant,
            resetAt = cycleResetInstant,
        )
    }

    private fun currentCycleStart(today: LocalDate, billingDay: Int): LocalDate {
        val candidateThisMonth = today.withDayOfMonth(billingDay.coerceAtMost(today.lengthOfMonth()))
        val candidateLastMonth = today.minusMonths(1)
            .withDayOfMonth(billingDay.coerceAtMost(today.minusMonths(1).lengthOfMonth()))
        return if (candidateThisMonth <= today) candidateThisMonth else candidateLastMonth
    }

    private fun nextCycleStart(cycleStart: LocalDate, billingDay: Int): LocalDate =
        cycleStart.plusMonths(1)
            .withDayOfMonth(billingDay.coerceAtMost(cycleStart.plusMonths(1).lengthOfMonth()))
}
