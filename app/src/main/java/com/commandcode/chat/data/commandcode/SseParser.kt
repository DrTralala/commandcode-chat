package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.TokenUsage
import org.json.JSONException
import org.json.JSONObject

internal interface StreamParser {
    fun acceptLine(line: String): List<StreamEvent>
    fun finish(): List<StreamEvent>
}

enum class CompletionNotice { OUTPUT_LIMIT, CONTEXT_LIMIT, REFUSAL }

sealed interface StreamEvent {
    data class Delta(val content: String) : StreamEvent
    data class Usage(val usage: TokenUsage) : StreamEvent
    data class Notice(val reason: CompletionNotice) : StreamEvent
    data object Done : StreamEvent
    data class Error(val code: String, val message: String) : StreamEvent
}

class SseParser : StreamParser {
    override fun acceptLine(line: String): List<StreamEvent> {
        val value = line.trimEnd('\r')
        if (value.isBlank() || value.startsWith(":")) return emptyList()
        if (!value.startsWith("data:")) return emptyList()
        val data = value.removePrefix("data:").trimStart()
        if (data.isBlank()) return emptyList()
        if (data == "[DONE]") return listOf(StreamEvent.Done)
        return try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices")
            val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
            val content = delta?.takeIf { it.has("content") && !it.isNull("content") }?.getString("content")
            buildList {
                if (!content.isNullOrEmpty()) add(StreamEvent.Delta(content))
                json.optJSONObject("usage")?.let { usage ->
                    add(StreamEvent.Usage(TokenUsage(
                    usage.getLong("prompt_tokens"),
                    usage.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens")?.takeIf { usage.optJSONObject("prompt_tokens_details")?.has("cached_tokens") == true },
                    usage.getLong("completion_tokens"),
                    )))
                }
            }
        } catch (_: JSONException) {
            listOf(StreamEvent.Error("MALFORMED_FRAME", "Malformed SSE frame"))
        } catch (_: RuntimeException) {
            listOf(StreamEvent.Error("MALFORMED_FRAME", "Malformed SSE frame"))
        }
    }

    override fun finish(): List<StreamEvent> = emptyList()
}
