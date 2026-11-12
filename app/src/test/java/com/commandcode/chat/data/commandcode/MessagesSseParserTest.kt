package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesSseParserTest {
    @Test
    fun `complete messages stream emits text cumulative usage and terminal success`() {
        val parser: StreamParser = MessagesSseParser()

        assertEquals(
            listOf(StreamEvent.Usage(TokenUsage(15, 3, 0))),
            frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":0}}}"""),
        )
        assertEquals(
            listOf(StreamEvent.Delta("Hello")),
            frame(parser, """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""),
        )
        assertEquals(
            listOf(StreamEvent.Usage(TokenUsage(15, 3, 4))),
            frame(parser, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}"""),
        )
        assertEquals(listOf(StreamEvent.Done), frame(parser, """{"type":"message_stop"}"""))
    }

    @Test
    fun `event names multiline data and crlf preserve json type dispatch`() {
        val parser = MessagesSseParser()
        assertTrue(frame(parser, """{"type":"message_start","message":{"usage":{}}}""").isEmpty())

        val events = parser.acceptLine("event: provider-event\r") +
            parser.acceptLine("data: {\"type\":\"content_block_delta\",\r") +
            parser.acceptLine("data: \"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"joined\"}}\r") +
            parser.acceptLine("\r")

        assertEquals(listOf(StreamEvent.Delta("joined")), events)
    }

    @Test
    fun `ping unknown and thinking events never become visible text`() {
        val parser = MessagesSseParser()
        assertTrue(frame(parser, """{"type":"message_start","message":{"usage":{}}}""").isEmpty())

        assertTrue(frame(parser, """{"type":"ping"}""").isEmpty())
        assertTrue(frame(parser, """{"type":"provider_extension","content":"hidden"}""").isEmpty())
        assertTrue(frame(parser, """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"private"}}""").isEmpty())
        assertTrue(frame(parser, """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private"}}""").isEmpty())
        assertTrue(frame(parser, """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"private"}}""").isEmpty())
    }

    @Test
    fun `provider error is sanitised and prevents subsequent completion`() {
        val parser = MessagesSseParser()
        val events = frame(parser, """{"type":"error","error":{"type":"authentication_error","message":"private provider detail"}}""")

        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Error)
        val error = events.single() as StreamEvent.Error
        assertEquals("PROVIDER_ERROR", error.code)
        assertEquals("Provider returned an error", error.message)
        assertFalse(error.message.contains("private provider detail"))
        assertTrue(frame(parser, """{"type":"message_start","message":{"usage":{}}}""").isEmpty())
        assertTrue(frame(parser, """{"type":"message_stop"}""").isEmpty())
    }

    @Test
    fun `malformed json and malformed event types are safe terminal errors`() {
        assertMalformed("{not-json")
        assertMalformed("""{"type":42}""")
        assertMalformed("""{"type":"message_start","message":{"usage":"wrong"}}""")
        assertMalformed("""{"type":"content_block_delta","delta":{"type":"text_delta","text":7}}""")
    }

    @Test
    fun `missing and null counters remain unknown until every component is known`() {
        val parser = MessagesSseParser()
        assertTrue(frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":null,"cache_read_input_tokens":3,"output_tokens":null}}}""").isEmpty())
        assertTrue(frame(parser, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4,"cache_creation_input_tokens":null}}""").isEmpty())
        assertEquals(
            listOf(StreamEvent.Usage(TokenUsage(15, 3, 7))),
            frame(parser, """{"type":"message_delta","delta":{"stop_reason":null},"usage":{"cache_creation_input_tokens":2,"output_tokens":7}}"""),
        )
        assertEquals(listOf(StreamEvent.Done), frame(parser, """{"type":"message_stop"}"""))
    }

    @Test
    fun `message start emits usage when all initial counters are known`() {
        val parser = MessagesSseParser()

        assertEquals(
            listOf(StreamEvent.Usage(TokenUsage(15, 3, 0))),
            frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":0}}}"""),
        )
    }

    @Test
    fun `message start does not infer usage when initial counters are missing`() {
        val parser = MessagesSseParser()

        assertTrue(frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":2}}}""").isEmpty())
    }

    @Test
    fun `missing and null output counts are not emitted as zero`() {
        val parser = MessagesSseParser()
        frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":null}}}""")

        assertTrue(frame(parser, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{}}""").isEmpty())
        assertTrue(frame(parser, """{"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":null}}""").isEmpty())
        assertEquals(
            listOf(StreamEvent.Usage(TokenUsage(15, 3, 7))),
            frame(parser, """{"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":7}}"""),
        )
    }

    @Test
    fun `new cumulative usage replaces rather than adds prior output counts`() {
        val parser = MessagesSseParser()
        val initial = frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":0}}}""")

        val first = frame(parser, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}""")
        val second = frame(parser, """{"type":"message_delta","delta":{"stop_reason":null},"usage":{"input_tokens":null,"cache_creation_input_tokens":null,"cache_read_input_tokens":null,"output_tokens":7}}""")

        assertEquals(listOf(StreamEvent.Usage(TokenUsage(15, 3, 0))), initial)
        assertEquals(listOf(StreamEvent.Usage(TokenUsage(15, 3, 4))), first)
        assertEquals(listOf(StreamEvent.Usage(TokenUsage(15, 3, 7))), second)
    }

    @Test
    fun `negative fractional and overflowing usage counters are rejected`() {
        assertMalformed("""{"type":"message_start","message":{"usage":{"input_tokens":-1}}}""")
        assertMalformed("""{"type":"message_start","message":{"usage":{"input_tokens":1.5}}}""")
        assertMalformed("""{"type":"message_start","message":{"usage":{"input_tokens":9223372036854775808}}}""")
    }

    @Test
    fun `input usage addition is checked for overflow`() {
        val parser = MessagesSseParser()
        val event = frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":9223372036854775807,"cache_creation_input_tokens":1,"cache_read_input_tokens":0,"output_tokens":0}}}""").single()

        assertMalformedEvent(event)
    }

    @Test
    fun `stop reasons map notices only for terminal provider outcomes`() {
        assertStopReason("max_tokens", listOf(StreamEvent.Notice(CompletionNotice.OUTPUT_LIMIT), StreamEvent.Done))
        assertStopReason("model_context_window_exceeded", listOf(StreamEvent.Notice(CompletionNotice.CONTEXT_LIMIT), StreamEvent.Done))
        assertStopReason("refusal", listOf(StreamEvent.Notice(CompletionNotice.REFUSAL), StreamEvent.Done))
        assertStopReason("end_turn", listOf(StreamEvent.Done))
        assertStopReason("stop_sequence", listOf(StreamEvent.Done))
    }

    @Test
    fun `unsupported absent and unknown stop reasons fail instead of succeeding`() {
        assertStopReason("tool_use", error = true)
        assertStopReason("pause_turn", error = true)
        assertStopReason("vendor_stop", error = true)

        val parser = MessagesSseParser()
        frame(parser, """{"type":"message_start","message":{"usage":{}}}""")
        frame(parser, """{"type":"message_delta","delta":{"stop_reason":null},"usage":{}}""")
        assertErrorWithoutDone(frame(parser, """{"type":"message_stop"}"""))
    }

    @Test
    fun `message stop before message start is malformed and cannot complete`() {
        assertErrorWithoutDone(frame(MessagesSseParser(), """{"type":"message_stop"}"""))
    }

    @Test
    fun `duplicate terminal event cannot emit completion twice`() {
        val parser = MessagesSseParser()
        frame(parser, """{"type":"message_start","message":{"usage":{}}}""")
        frame(parser, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{}}""")

        assertEquals(listOf(StreamEvent.Done), frame(parser, """{"type":"message_stop"}"""))
        assertTrue(frame(parser, """{"type":"message_stop"}""").isEmpty())
    }

    @Test
    fun `done sentinel and premature eof do not complete an unfinished attempt`() {
        val parser = MessagesSseParser()
        assertEquals(
            listOf(StreamEvent.Usage(TokenUsage(15, 3, 0))),
            frame(parser, """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":0}}}"""),
        )
        assertEquals(listOf(StreamEvent.Delta("partial")), frame(parser, """{"type":"content_block_delta","delta":{"type":"text_delta","text":"partial"}}"""))
        assertEquals(
            listOf(StreamEvent.Usage(TokenUsage(15, 3, 4))),
            frame(parser, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}"""),
        )
        assertTrue(frame(parser, "[DONE]").isEmpty())
        assertTrue(parser.finish().isEmpty())
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun `oversized utf8 frame errors once and cannot later complete`() {
        val parser = MessagesSseParser()
        val events = parser.acceptLine("data: " + "é".repeat(131_072))

        assertErrorWithoutDone(events)
        assertTrue(frame(parser, """{"type":"message_start","message":{"usage":{}}}""").isEmpty())
        assertTrue(frame(parser, """{"type":"message_stop"}""").isEmpty())
    }

    private fun frame(parser: StreamParser, json: String): List<StreamEvent> =
        parser.acceptLine("data: $json") + parser.acceptLine("")

    private fun assertMalformed(json: String) {
        assertErrorWithoutDone(frame(MessagesSseParser(), json))
    }

    private fun assertMalformedEvent(event: StreamEvent) {
        assertTrue(event is StreamEvent.Error)
        val error = event as StreamEvent.Error
        assertEquals("MALFORMED_FRAME", error.code)
        assertEquals("Malformed Messages stream", error.message)
    }

    private fun assertErrorWithoutDone(events: List<StreamEvent>) {
        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertEquals(1, errors.size)
        assertEquals("MALFORMED_FRAME", errors.single().code)
        assertEquals("Malformed Messages stream", errors.single().message)
        assertFalse(events.any { it === StreamEvent.Done })
    }

    private fun assertStopReason(reason: String, expected: List<StreamEvent>? = null, error: Boolean = false) {
        val parser = MessagesSseParser()
        frame(parser, """{"type":"message_start","message":{"usage":{}}}""")
        frame(parser, """{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{}}""")
        val terminal = frame(parser, """{"type":"message_stop"}""")
        if (error) assertErrorWithoutDone(terminal) else assertEquals(expected, terminal)
    }
}
