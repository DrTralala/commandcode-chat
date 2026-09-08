package com.commandcode.chat.data.attachments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import java.nio.ByteBuffer

/** Verified content metadata for a still image that passed container/decoder checks. */
data class InspectedImage(val mimeType: String, val width: Int, val height: Int)

/**
 * Bounded still-image inspection. Container structure is checked before any sampled
 * decode; the decoded dimensions and MIME must agree with the sniffed container.
 */
class ImageInspector {

    // Providers and downloaded filenames can mislabel valid images. Only validated
    // container content and Android's decoder determine the MIME sent to the API.
    @Suppress("UNUSED_PARAMETER")
    fun inspect(bytes: ByteArray, declaredMime: String?, displayName: String?): InspectedImage = inspect(bytes)

    private fun sniffMime(bytes: ByteArray): String = when {
        startsWith(bytes, PNG_SIGNATURE) -> "image/png"
        startsWith(bytes, JPEG_SOI) -> "image/jpeg"
        startsWith(bytes, RIFF_SIGNATURE) && startsWithAt(bytes, 8, WEBP_SIGNATURE) -> "image/webp"
        else -> throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
    }

    private fun inspect(bytes: ByteArray): InspectedImage {
        val mime = sniffMime(bytes)
        when (mime) {
            "image/png" -> inspectPng(bytes)
            "image/jpeg" -> inspectJpeg(bytes)
            "image/webp" -> inspectWebp(bytes)
            else -> throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        }
        val info = decodeSampled(bytes, mime)
        val width = info.width
        val height = info.height
        if (width < 1 || height < 1 || width > AttachmentPolicy.MAX_DIMENSION || height > AttachmentPolicy.MAX_DIMENSION) {
            throw AttachmentException(AttachmentFailure.EXCESSIVE_DIMENSIONS)
        }
        return InspectedImage(mime, width, height)
    }

    // --- containers ---

    private fun inspectJpeg(bytes: ByteArray) {
        if (!startsWith(bytes, JPEG_SOI)) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
    }

    private fun inspectPng(bytes: ByteArray) {
        if (!startsWith(bytes, PNG_SIGNATURE)) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        var offset = 8
        while (true) {
            if (offset + 12 > bytes.size) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
            val length = be32(bytes, offset)
            if (length < 0) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            when (type) {
                "IEND" -> {
                    if (offset + 12 != bytes.size) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
                    return
                }
                "acTL", "fcTL", "fdAT" -> throw AttachmentException(AttachmentFailure.ANIMATED_IMAGE)
            }
            val end = offset.toLong() + 12L + length.toLong()
            if (end > bytes.size.toLong()) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
            if (!validCrc(bytes, offset, length)) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
            offset = end.toInt()
        }
    }

    private fun inspectWebp(bytes: ByteArray) {
        if (!startsWith(bytes, RIFF_SIGNATURE) || bytes.size < 12) {
            throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        }
        val declared = le32(bytes, 4).toLong() + 8L
        if (declared != bytes.size.toLong()) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        if (!startsWithAt(bytes, 8, WEBP_SIGNATURE)) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        var offset = 12
        var sawImageChunk = false
        while (offset + 8 <= bytes.size) {
            // RIFF/WebP chunk layout is [fourcc:4][size:4 little-endian][payload],
            // payload padded to an even byte boundary.
            if (offset + 8 > bytes.size) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
            val chunkType = String(bytes, offset, 4, Charsets.US_ASCII)
            val payload = le32(bytes, offset + 4)
            if (payload < 0) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
            val padded = payload.toLong() + (payload.toLong() and 1L)
            val next = offset.toLong() + 8L + padded
            if (next > bytes.size.toLong()) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
            when (chunkType) {
                "VP8X" -> {
                    if (payload < 10) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
                    val flags = bytes[offset + 8]
                    if ((flags.toInt() and 0x02) != 0) throw AttachmentException(AttachmentFailure.ANIMATED_IMAGE)
                }
                "ANIM", "ANMF" -> throw AttachmentException(AttachmentFailure.ANIMATED_IMAGE)
                "VP8 ", "VP8L", "VP8X" -> sawImageChunk = true
            }
            offset = next.toInt()
        }
        if (offset != bytes.size || !sawImageChunk) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
    }

    // --- sampled decode ---

    private fun decodeSampled(bytes: ByteArray, sniffed: String): DecodedHeader {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val holder = HeaderHolder()
        val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            holder.size = info.size
            holder.mimeType = info.mimeType
            if (info.isAnimated) throw AttachmentException(AttachmentFailure.ANIMATED_IMAGE)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setOnPartialImageListener { false }
            val edge = targetEdge(info.size.width, info.size.height)
            decoder.setTargetSize(edge, edge)
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = ImageDecoder.decodeBitmap(source, listener)
        } catch (e: AttachmentException) {
            throw e
        } catch (e: ImageDecoder.DecodeException) {
            throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        } catch (e: java.io.IOException) {
            throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        } finally {
            bitmap?.recycle()
        }
        val size = holder.size ?: throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        if (holder.mimeType != sniffed) throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
        return DecodedHeader(size.width, size.height)
    }

    private fun targetEdge(width: Int, height: Int): Int {
        if (width <= 256 && height <= 256) return width.coerceAtLeast(height)
        val largest = width.coerceAtLeast(height)
        val scale = 256.0 / largest.toDouble()
        return (largest * scale).toInt().coerceAtLeast(1)
    }

    private data class DecodedHeader(val width: Int, val height: Int)

    private class HeaderHolder {
        var size: android.util.Size? = null
        var mimeType: String? = null
    }

    // --- byte helpers ---

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && bytes.copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun startsWithAt(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean =
        bytes.size >= offset + prefix.size && bytes.copyOfRange(offset, offset + prefix.size).contentEquals(prefix)

    private fun be32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun validCrc(bytes: ByteArray, chunkOffset: Int, length: Int): Boolean {
        val crc = java.util.zip.CRC32()
        crc.update(bytes, chunkOffset + 4, length + 4)
        return crc.value.toInt() == be32(bytes, chunkOffset + 8 + length)
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val RIFF_SIGNATURE = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
        val WEBP_SIGNATURE = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    }
}
