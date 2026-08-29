package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CommandCodeRequestTest {
    @Test fun `request contains exact streaming payload and zdr header`() {
        val request = CommandCodeClient.requestForTesting(
            apiKey = charArrayOf('s', 'e', 'c', 'r', 'e', 't'),
            model = ChatModel.SOL,
            messages = listOf(ApiMessage("user", "Hello")),
            zdr = true,
        )
        val body = Buffer().also { request.body!!.writeTo(it) }
        val json = JSONObject(body.readUtf8())
        assertEquals("https://api.commandcode.ai/provider/v1/chat/completions", request.url.toString())
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("application/json", request.body!!.contentType().toString())
        assertEquals("1", request.header("x-cmd-zdr"))
        assertEquals("gpt-5.6-sol", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        assertTrue(json.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals("user", json.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("Hello", json.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun `zdr header is absent when disabled and client takes model`() {
        val request: Request = CommandCodeClient.requestForTesting(charArrayOf('k'), ChatModel.LUNA, emptyList(), false)
        assertEquals(null, request.header("x-cmd-zdr"))
        assertEquals("gpt-5.6-luna", JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("model"))
    }
}
