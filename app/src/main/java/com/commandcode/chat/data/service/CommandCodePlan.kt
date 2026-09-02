package com.commandcode.chat.data.service

internal data class CommandCodePlan(
    val displayName: String,
    val monthlyCap: Double?,
)

internal fun commandCodePlan(planId: String?): CommandCodePlan? = when (planId) {
    "individual-go" -> CommandCodePlan("Go", 10.0)
    "individual-goat" -> CommandCodePlan("GOAT", 70.0)
    "individual-pro" -> CommandCodePlan("Pro", 30.0)
    "individual-pro-v1" -> CommandCodePlan("Pro", 80.0)
    "individual-provider" -> CommandCodePlan("Provider", 15.0)
    "individual-max" -> CommandCodePlan("Max 10×", 150.0)
    "individual-ultra" -> CommandCodePlan("Max 20×", 300.0)
    "teams-pro" -> CommandCodePlan("Team Pro", 40.0)
    else -> null
}
