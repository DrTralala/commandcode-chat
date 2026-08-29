package com.commandcode.chat.domain

import java.math.BigDecimal

data class TokenRates(
    val inputPerMillion: BigDecimal,
    val cachedInputPerMillion: BigDecimal,
    val outputPerMillion: BigDecimal,
)

enum class ChatModel(
    val apiId: String,
    val displayName: String,
    val monthlyAllowance: BigDecimal,
    val rates: TokenRates,
) {
    SOL("gpt-5.6-sol", "GPT-5.6 Sol", bd("70"), TokenRates(bd("5"), bd("0.5"), bd("30"))),
    LUNA("gpt-5.6-luna", "GPT-5.6 Luna", bd("20"), TokenRates(bd("0.2"), bd("0.02"), bd("1.2")));

    companion object {
        const val RATES_EFFECTIVE_AS_OF = "2026-08-29"

        fun fromApiId(apiId: String): ChatModel? = entries.singleOrNull { it.apiId == apiId }
    }
}

private fun bd(value: String) = BigDecimal(value)
