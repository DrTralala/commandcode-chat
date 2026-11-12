package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesRequestTest {
    private val model = ChatModel("vendor/messages", "Messages", ApiFamily.ANTHROPIC_MESSAGES)

    @Test
    fun `body uses native Messages fields and keeps only conversation roles in messages`() {
        val body = MessagesRequestFactory.create(
            model,
            listOf(
                ApiMessage("system", "Instructions"),
                ApiMessage("user", "Hello"),
                ApiMessage("assistant", "Hi"),
            ),
        )

        val buffer = Buffer()
        body.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        val messages = json.getJSONArray("messages")

        assertEquals("application/json", body.contentType().toString())
        assertEquals("vendor/messages", json.getString("model"))
        assertEquals("Instructions", json.getString("system"))
        assertEquals(4096, json.getInt("max_tokens"))
        assertTrue(json.getBoolean("stream"))
        assertFalse(json.has("stream_options"))
        assertEquals(2, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("Hello", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("Hi", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `multiple system messages join in source order without changing conversation order`() {
        val body = MessagesRequestFactory.create(
            model,
            listOf(
                ApiMessage("system", "First"),
                ApiMessage("user", "Question"),
                ApiMessage("system", "Second"),
                ApiMessage("assistant", "Answer"),
                ApiMessage("system", "Third"),
            ),
        )

        val json = JSONObject(Buffer().also { body.writeTo(it) }.readUtf8())
        val messages = json.getJSONArray("messages")

        assertEquals("First\n\nSecond\n\nThird", json.getString("system"))
        assertEquals(2, messages.length())
        assertEquals("Question", messages.getJSONObject(0).getString("content"))
        assertEquals("Answer", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `rejects an empty conversation and unsupported roles`() {
        assertThrows(IllegalArgumentException::class.java) {
            MessagesRequestFactory.create(model, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessagesRequestFactory.create(model, listOf(ApiMessage("system", "Only instructions")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessagesRequestFactory.create(model, listOf(ApiMessage("developer", "Do not drop me"), ApiMessage("user", "Hi")))
        }
    }

    @Test
    fun `rejects image content locally`() {
        val image = ApiImage("image/png", 1) { error("Messages encoder must not write image bytes") }

        assertThrows(IllegalArgumentException::class.java) {
            MessagesRequestFactory.create(model, listOf(ApiMessage("user", "Look", listOf(image))))
        }
    }

    @Test
    fun `rejects models from another API family`() {
        assertThrows(IllegalArgumentException::class.java) {
            MessagesRequestFactory.create(ChatModel.SOL, listOf(ApiMessage("user", "Hello")))
        }
    }

    @Test
    fun `exposes the pinned upstream version for Messages transport`() {
        assertEquals("2026-10-21", MessagesRequestFactory.ANTHROPIC_VERSION)
    }
}
