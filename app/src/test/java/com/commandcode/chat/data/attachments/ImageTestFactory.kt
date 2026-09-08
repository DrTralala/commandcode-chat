package com.commandcode.chat.data.attachments

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Small still-image factories for tests. Generated in memory; no private fixtures. */
object ImageTestFactory {

    fun jpeg(width: Int = 2, height: Int = 2): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "JPEG compress failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun png(width: Int = 2, height: Int = 2): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 0, out)) { "PNG compress failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun webpLossless(width: Int = 2, height: Int = 2): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) { "WEBP compress failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun gif(): ByteArray = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // "GIF89a"
        1, 0, 1, 0, 0x00, 0x00, 0x00, 0x3B,
    )

    fun heifHeader(): ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x18, // box size
        0x66, 0x74, 0x79, 0x70, // "ftyp"
        0x6D, 0x69, 0x66, 0x31, // "mif1"
        0x00, 0x00, 0x00, 0x00, // minor version
        0x6D, 0x69, 0x66, 0x31, // "mif1"
    )

    /** byte `n` as a big-endian 32-bit integer. */
    fun be32(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(),
        (n ushr 16).toByte(),
        (n ushr 8).toByte(),
        n.toByte(),
    )

    /** byte `n` as a little-endian 32-bit integer (RIFF sizes). */
    fun le32(n: Int): ByteArray = byteArrayOf(
        n.toByte(),
        (n ushr 8).toByte(),
        (n ushr 16).toByte(),
        (n ushr 24).toByte(),
    )

    private fun crc32(vararg parts: ByteArray): ByteArray {
        val crc = CRC32()
        for (part in parts) crc.update(part)
        return be32(crc.value.toInt())
    }

    /** PNG with an ancillary [chunkType] chunk inserted directly before IEND. */
    fun pngWithChunks(width: Int, height: Int, chunkType: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val base = png(width, height)
        var offset = 8
        var iendOffset = -1
        while (offset + 12 <= base.size) {
            val length = be32ToInt(base, offset)
            val type = String(base, offset + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") {
                iendOffset = offset
                break
            }
            offset += 12 + length
        }
        check(iendOffset > 0) { "factory PNG has no IEND" }
        val typeBytes = chunkType.toByteArray(Charsets.US_ASCII)
        val newChunk = be32(payload.size) + typeBytes + payload + crc32(typeBytes, payload)
        return base.copyOfRange(0, iendOffset) + newChunk + base.copyOfRange(iendOffset, base.size)
    }

    private fun be32ToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun le32ToInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** Padding chunk with the declared little-endian size one more than the actual payload. */
    fun webpWithOddPadding(): ByteArray {
        val base = webpLossless(2, 2)
        require(base.size >= 12 && base[0] == 'R'.code.toByte() && base[1] == 'I'.code.toByte() &&
            base[2] == 'F'.code.toByte() && base[3] == 'F'.code.toByte())
        // Declare a single trailing truncated chunk inside an enlarged RIFF frame.
        // RIFF/WebP chunk layout is [fourcc:4][size:4]; declare 1 payload byte with none present.
        val chunk = "VP8X".toByteArray(Charsets.US_ASCII) + le32(1)
        val extra = chunk
        val riffSize = le32ToInt(base, 4) + extra.size
        val out = ByteArrayOutputStream()
        out.write(base, 0, 4)
        out.write(le32(riffSize))
        out.write(base, 8, base.size - 8)
        out.write(extra)
        return out.toByteArray()
    }

    /**
     * WebP with a real extended VP8X chunk inserted immediately before the image chunk.
     * The RIFF size is recomputed; the original image chunks follow verbatim.
     */
    fun webpWithVp8x(flags: Byte): ByteArray {
        val base = webpLossless(2, 2)
        val vp8xPayload = ByteArray(10)
        vp8xPayload[0] = flags
        val vp8x = "VP8X".toByteArray(Charsets.US_ASCII) + le32(10) + vp8xPayload
        val riffSize = le32ToInt(base, 4) + vp8x.size
        val out = ByteArrayOutputStream()
        out.write(base, 0, 4)
        out.write(le32(riffSize))
        out.write(base, 8, 4) // "WEBP"
        out.write(vp8x)
        out.write(base, 12, base.size - 12)
        return out.toByteArray()
    }
}
