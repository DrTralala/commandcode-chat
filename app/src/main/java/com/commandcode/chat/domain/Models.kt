package com.commandcode.chat.domain

import java.math.BigDecimal
import java.time.Instant

data class TokenUsage(
    val inputTokens: Long,
    val cachedInputTokens: Long?,
    val outputTokens: Long,
)

data class UsageEvent(
    val id: String,
    val model: ChatModel,
    val timestamp: Instant,
    val usage: TokenUsage?,
    val estimatedModelCost: BigDecimal?,
    val estimatedGoatCredits: BigDecimal?,
    val usageComplete: Boolean = true,
)
