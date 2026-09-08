package com.commandcode.chat.data.commandcode

import com.commandcode.chat.domain.ChatModel
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRequestStreamingTest {
    @Test fun `large image writers are deferred sequential and bounded`() {
        val rawBytes = 10_000_000
        val imageCount = 20
        val calls = IntArray(imageCount)
        val encodedBytes = LongArray(imageCount)
        val order = mutableListOf<Int>()
        val active = AtomicInteger(0)
        var maxActive = 0

        val images = (0 until imageCount).map { index ->
            ApiImage("image/png", rawBytes.toLong()) { output ->
                calls[index]++
                order += index
                val nowActive = active.incrementAndGet()
                maxActive = maxOf(maxActive, nowActive)
                val countedOutput = CountingOutputStream(output) { encodedBytes[index] += it }
                val encoder = Base64.getEncoder().wrap(countedOutput)
                val block = ByteArray(8_191) { offset -> (index + offset).toByte() }
                var remaining = rawBytes
                try {
                    while (remaining > 0) {
                        val count = min(remaining, block.size)
                        encoder.write(block, 0, count)
                        remaining -= count
                    }
                } finally {
                    encoder.close()
                    active.decrementAndGet()
                }
            }
        }

        val request = CommandCodeRequestFactory.create(
            ChatModel.SOL,
            listOf(ApiMessage("user", "bulk", images)),
        )
        assertEquals(-1L, request.contentLength())
        assertEquals(0, calls.sum())

        val downstream = CountingDiscardingSink()
        val sink = downstream.buffer()
        request.writeTo(sink)
        sink.flush()

        assertEquals((0 until imageCount).toList(), order)
        assertTrue(calls.all { it == 1 })
        assertEquals(1, maxActive)
        val expectedBase64Bytes = 4L * ((rawBytes.toLong() + 2L) / 3L)
        assertTrue(encodedBytes.all { it == expectedBase64Bytes })
        assertTrue("downstream chunk was ${downstream.maxWrite}", downstream.maxWrite <= 32_768L)
        assertFalse(downstream.closed)
    }

    @Test fun `downstream IOException stops later writers and still runs writer cleanup`() {
        val order = mutableListOf<Int>()
        var cleanupRan = false
        val images = (0 until 20).map { index ->
            ApiImage("image/png", 100_000) { output ->
                order += index
                try {
                    output.write(ByteArray(100_000) { 'A'.code.toByte() })
                } finally {
                    if (index == 0) cleanupRan = true
                }
            }
        }
        val request = CommandCodeRequestFactory.create(
            ChatModel.SOL,
            listOf(ApiMessage("user", "fails", images)),
        )
        val downstream = CountingDiscardingSink(failAfter = 1_024)
        val sink = downstream.buffer()

        val failure = runCatching { request.writeTo(sink) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(listOf(0), order)
        assertTrue(cleanupRan)
    }

    @Test fun `leading system message precedes multimodal user without carrying images`() {
        var writes = 0
        val image = ApiImage("image/png", 3) {
            writes++
            it.write("AQID".toByteArray())
        }
        val request = CommandCodeRequestFactory.create(
            ChatModel.SOL,
            listOf(
                ApiMessage("system", "be kind"),
                ApiMessage("user", "look", listOf(image)),
            ),
        )
        val json = JSONObject(Buffer().also { request.writeTo(it) }.readUtf8())
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("be kind", messages.getJSONObject(0).getString("content"))
        val second = messages.getJSONObject(1)
        assertEquals("user", second.getString("role"))
        assertEquals(
            "data:image/png;base64,AQID",
            second.getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
        assertTrue(writes > 0)
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
        private val onBytes: (Long) -> Unit,
    ) : OutputStream() {
        override fun write(value: Int) {
            onBytes(1)
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            onBytes(length.toLong())
            delegate.write(bytes, offset, length)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private class CountingDiscardingSink(
        private val failAfter: Long? = null,
    ) : Sink {
        var totalBytes = 0L
            private set
        var maxWrite = 0L
            private set
        var closed = false
            private set

        override fun write(source: Buffer, byteCount: Long) {
            val remaining = failAfter?.let { it - totalBytes }
            val accepted = if (remaining == null) byteCount else min(byteCount, remaining.coerceAtLeast(0L))
            source.skip(accepted)
            totalBytes += accepted
            maxWrite = maxOf(maxWrite, accepted)
            if (accepted != byteCount) throw IOException("injected downstream failure")
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }
}
