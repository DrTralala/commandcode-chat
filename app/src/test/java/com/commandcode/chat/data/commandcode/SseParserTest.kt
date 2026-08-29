package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.TokenUsage
import org.junit.Assert.*
import org.junit.Test

class SseParserTest {
    @Test fun `parses delta and usage`() {
        val parser = SseParser()
        assertEquals(listOf(StreamEvent.Delta("Hi")), parser.acceptLine("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"))
        assertEquals(listOf(StreamEvent.Usage(TokenUsage(12, 3, 4))), parser.acceptLine("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":3}}}"))
        assertEquals(listOf(StreamEvent.Done), parser.acceptLine("data: [DONE]"))
    }

    @Test fun `ignores blank and empty keepalive frames`() {
        val parser = SseParser()
        assertTrue(parser.acceptLine("").isEmpty())
        assertTrue(parser.acceptLine("data: {}").isEmpty())
        assertTrue(parser.finish().isEmpty())
    }

    @Test fun `malformed json produces safe typed error`() {
        val event = SseParser().acceptLine("data: {not-json").single()
        assertTrue(event is StreamEvent.Error)
        assertEquals("MALFORMED_FRAME", (event as StreamEvent.Error).code)
        assertFalse(event.message.contains("not-json"))
    }
}
