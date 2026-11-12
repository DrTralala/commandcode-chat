package com.commandcode.chat.domain

enum class ApiFamily(val wireValue: String) {
    OPENAI_CHAT("openai-chat"),
    ANTHROPIC_MESSAGES("anthropic-messages"),
}

data class ChatModel(
    val apiId: String,
    val displayName: String,
    val apiFamily: ApiFamily,
    val contextWindowTokens: Long? = null,
) {
    init {
        require(apiId.isNotBlank())
        require(displayName.isNotBlank())
        require(contextWindowTokens == null || contextWindowTokens > 0)
    }

    companion object {
        val SOL = ChatModel("gpt-5.6-sol", "GPT-5.6 Sol", ApiFamily.OPENAI_CHAT)
        val LUNA = ChatModel("gpt-5.6-luna", "GPT-5.6 Luna", ApiFamily.OPENAI_CHAT)
    }
}
