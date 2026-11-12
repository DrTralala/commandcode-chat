package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.TokenUsage
import java.math.BigDecimal
import org.json.JSONException
import org.json.JSONObject

internal class MessagesSseParser : StreamParser {
    private val dataLines = mutableListOf<String>()
    private var frameBytes = 0L
    private var started = false
    private var finished = false
    private var terminal = false

    private var inputTokens: Long? = null
    private var cacheCreationInputTokens: Long? = null
    private var cacheReadInputTokens: Long? = null
    private var outputTokens: Long? = null
    private var stopReason: String? = null

    fun rejectOversizedLine(): List<StreamEvent> =
        if (finished || terminal) emptyList() else malformed()

    override fun acceptLine(line: String): List<StreamEvent> {
        if (finished || terminal) return emptyList()

        val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
        if (lineBytes > MAX_FRAME_BYTES - frameBytes) return malformed()
        frameBytes += lineBytes

        val value = line.trimEnd('\r')
        if (value.isEmpty()) return dispatchFrame()
        if (value.startsWith(":")) return emptyList()

        val colon = value.indexOf(':')
        val field = if (colon < 0) value else value.substring(0, colon)
        val fieldValue = if (colon < 0) "" else value.substring(colon + 1).removePrefix(" ")
        if (field == "data") dataLines += fieldValue
        // SSE event names and other fields are intentionally not authoritative; dispatch uses JSON type.
        return emptyList()
    }

    override fun finish(): List<StreamEvent> {
        if (!finished) {
            finished = true
            clearFrame()
        }
        return emptyList()
    }

    private fun dispatchFrame(): List<StreamEvent> {
        val data = dataLines.joinToString("\n")
        val hasData = dataLines.isNotEmpty()
        clearFrame()
        if (!hasData || data == "[DONE]") return emptyList()

        return try {
            dispatchJson(JSONObject(data))
        } catch (_: JSONException) {
            malformed()
        } catch (_: InvalidMessagesFrame) {
            malformed()
        } catch (_: RuntimeException) {
            malformed()
        }
    }

    private fun dispatchJson(json: JSONObject): List<StreamEvent> {
        val type = json.opt("type") as? String ?: throw InvalidMessagesFrame()
        return when (type) {
            "message_start" -> messageStart(json)
            "content_block_delta" -> contentBlockDelta(json)
            "message_delta" -> messageDelta(json)
            "message_stop" -> messageStop()
            "error" -> providerError()
            else -> emptyList()
        }
    }

    private fun messageStart(json: JSONObject): List<StreamEvent> {
        if (started) throw InvalidMessagesFrame()
        val message = json.requiredObject("message")
        message.optionalObject("usage")?.let(::mergeUsage)
        started = true
        val usage = completeUsage() ?: return emptyList()
        return listOf(StreamEvent.Usage(usage))
    }

    private fun contentBlockDelta(json: JSONObject): List<StreamEvent> {
        if (!started) throw InvalidMessagesFrame()
        val delta = json.requiredObject("delta")
        val type = delta.opt("type") as? String ?: throw InvalidMessagesFrame()
        if (type != "text_delta") return emptyList()

        val text = delta.opt("text") as? String ?: throw InvalidMessagesFrame()
        return if (text.isEmpty()) emptyList() else listOf(StreamEvent.Delta(text))
    }

    private fun messageDelta(json: JSONObject): List<StreamEvent> {
        if (!started) throw InvalidMessagesFrame()
        val delta = json.requiredObject("delta")
        if (delta.has("stop_reason") && !delta.isNull("stop_reason")) {
            stopReason = delta.opt("stop_reason") as? String ?: throw InvalidMessagesFrame()
        }
        json.optionalObject("usage")?.let(::mergeUsage)
        val usage = completeUsage() ?: return emptyList()
        return listOf(StreamEvent.Usage(usage))
    }

    private fun messageStop(): List<StreamEvent> {
        if (!started) throw InvalidMessagesFrame()
        val completion = when (stopReason) {
            "end_turn", "stop_sequence" -> null
            "max_tokens" -> CompletionNotice.OUTPUT_LIMIT
            "model_context_window_exceeded" -> CompletionNotice.CONTEXT_LIMIT
            "refusal" -> CompletionNotice.REFUSAL
            else -> throw InvalidMessagesFrame()
        }
        terminal = true
        return buildList {
            completion?.let { add(StreamEvent.Notice(it)) }
            add(StreamEvent.Done)
        }
    }

    private fun providerError(): List<StreamEvent> {
        terminal = true
        clearFrame()
        return listOf(StreamEvent.Error("PROVIDER_ERROR", "Provider returned an error"))
    }

    private fun mergeUsage(usage: JSONObject) {
        readCounter(usage, "input_tokens")?.let { inputTokens = it }
        readCounter(usage, "cache_creation_input_tokens")?.let { cacheCreationInputTokens = it }
        readCounter(usage, "cache_read_input_tokens")?.let { cacheReadInputTokens = it }
        readCounter(usage, "output_tokens")?.let { outputTokens = it }
        totalInputTokens()
    }

    private fun readCounter(usage: JSONObject, name: String): Long? {
        if (!usage.has(name) || usage.isNull(name)) return null
        val value = usage.opt(name) as? Number ?: throw InvalidMessagesFrame()
        val counter = try {
            BigDecimal(value.toString()).longValueExact()
        } catch (_: ArithmeticException) {
            throw InvalidMessagesFrame()
        } catch (_: NumberFormatException) {
            throw InvalidMessagesFrame()
        }
        if (counter < 0) throw InvalidMessagesFrame()
        return counter
    }

    private fun completeUsage(): TokenUsage? {
        val input = totalInputTokens() ?: return null
        val output = outputTokens ?: return null
        return TokenUsage(input, cacheReadInputTokens, output)
    }

    private fun totalInputTokens(): Long? {
        val input = inputTokens ?: return null
        val cacheCreation = cacheCreationInputTokens ?: return null
        val cacheRead = cacheReadInputTokens ?: return null
        return try {
            Math.addExact(Math.addExact(input, cacheCreation), cacheRead)
        } catch (_: ArithmeticException) {
            throw InvalidMessagesFrame()
        }
    }

    private fun malformed(): List<StreamEvent> {
        terminal = true
        clearFrame()
        return listOf(StreamEvent.Error("MALFORMED_FRAME", "Malformed Messages stream"))
    }

    private fun clearFrame() {
        dataLines.clear()
        frameBytes = 0L
    }

    private fun JSONObject.requiredObject(name: String): JSONObject =
        optionalObject(name) ?: throw InvalidMessagesFrame()

    private fun JSONObject.optionalObject(name: String): JSONObject? {
        if (!has(name) || isNull(name)) return null
        return optJSONObject(name) ?: throw InvalidMessagesFrame()
    }

    private class InvalidMessagesFrame : RuntimeException()

    internal companion object {
        const val MAX_FRAME_BYTES = 262_144L
    }
}
