package com.commandcode.chat.data.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ImageAttachment
import java.io.InputStream
import java.util.UUID

object ImageImporterDefaults {
    fun fallbackName(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "Image.jpg"
        "image/png" -> "Image.png"
        "image/webp" -> "Image.webp"
        else -> "Image.bin"
    }
}

/**
 * Bounded original-byte image import from a content:// document URI. Reads at most
 * 10,000,001 bytes into a fixed scratch buffer (never sized by provider metadata),
 * inspects the container/decodes bounds, verifies model/type policy and stores the
 * original bytes via [AttachmentFiles]. Calls are blocking and must run off main.
 */
class ImageImporter(
    private val resolver: ContentResolver,
    private val inspector: ImageInspector,
    private val files: AttachmentFiles,
) {
    fun importImage(uri: Uri, modelId: String): ImageAttachment =
        importImage(uri, modelId, onClaimed = {})

    /**
     * The optional claim callback is used by the coordinating service to identify the
     * freshly allocated file even if cancellation interrupts the blocking write before
     * this method can return its metadata. It is invoked only after inspection/policy
     * checks and before the exclusive file write.
     */
    internal fun importImage(
        uri: Uri,
        modelId: String,
        onClaimed: (String) -> Unit,
    ): ImageAttachment {
        if (uri.scheme != "content") throw AttachmentException(AttachmentFailure.STALE_SELECTION)
        val (declaredMime, displayName) = queryMetadata(uri)
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            throw AttachmentException(AttachmentFailure.STALE_SELECTION)
        }
        if (input == null) throw AttachmentException(AttachmentFailure.STALE_SELECTION)
        var bytes: ByteArray? = null
        try {
            bytes = readBoundedImage(input)
            val info = inspector.inspect(bytes, declaredMime, displayName)
            AttachmentPolicy.requireAllowed(modelId, info.mimeType)
            val safeDisplayName = safeName(displayName, info.mimeType)
            val id = UUID.randomUUID().toString()
            onClaimed(id)
            val encryptedBytes = files.write(id, bytes)
            return ImageAttachment(
                id = id,
                displayName = safeDisplayName,
                mimeType = info.mimeType,
                originalBytes = bytes.size.toLong(),
                encryptedBytes = encryptedBytes,
                width = info.width,
                height = info.height,
            )
        } finally {
            // The importer owns the resolver stream once openInputStream succeeds; close it
            // on every path. readBoundedImage never closes the caller's stream. The plaintext
            // buffer is cleared before close runs, so a close failure cannot leave it
            // uncleared, and a close exception is swallowed so it cannot mask the primary
            // typed result (or the returned attachment on success).
            val owned = bytes
            if (owned != null) {
                owned.fill(0)
            }
            try {
                input.close()
            } catch (_: Exception) {
                // Swallow: the import result (success or typed failure) is authoritative.
            }
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String?, String?> {
        var declaredMime: String? = null
        var displayName: String? = null
        try {
            declaredMime = resolver.getType(uri)
        } catch (_: Exception) {
            // Missing MIME metadata is not fatal; the container decides.
        }
        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {
            // Missing/broken name metadata falls back to the verified-MIME default.
        }
        return declaredMime to displayName
    }

    private fun safeName(displayName: String?, mimeType: String): String {
        if (displayName == null) return ImageImporterDefaults.fallbackName(mimeType)
        val cleaned = StringBuilder(displayName.length)
        for (ch in displayName) {
            if (Character.getType(ch) == Character.CONTROL.toInt()) continue
            cleaned.append(ch)
        }
        val trimmed = cleaned.toString().trim()
        if (trimmed.isEmpty()) return ImageImporterDefaults.fallbackName(mimeType)

        // Cap at 255 Unicode code points without splitting a surrogate pair.
        val capped = StringBuilder(trimmed.length)
        var offset = 0
        var codePoints = 0
        while (offset < trimmed.length && codePoints < 255) {
            val cp = trimmed.codePointAt(offset)
            capped.appendCodePoint(cp)
            offset += Character.charCount(cp)
            codePoints++
        }
        return capped.toString()
    }

    companion object {
        private const val SCRATCH_SIZE = 8192

        /**
         * Reads at most 10,000,001 bytes with a fixed 8192-byte scratch and a single
         * bounded byte array. A stream longer than the inclusive ceiling fails
         * immediately with TOO_LARGE; an input at the exact ceiling is returned intact.
         * Owned buffers are cleared before returning or throwing.
         */
        fun readBoundedImage(input: InputStream): ByteArray {
            val limit = AttachmentPolicy.MAX_IMAGE_BYTES
            val scratch = ByteArray(SCRATCH_SIZE)
            val buffer = ByteArray(limit.toInt() + 1)
            var filled = 0
            try {
                while (true) {
                    if (filled > limit) throw AttachmentException(AttachmentFailure.TOO_LARGE)
                    val want = minOf(limit + 1L - filled, scratch.size.toLong()).toInt()
                    val read = try {
                        input.read(scratch, 0, want)
                    } catch (e: java.io.IOException) {
                        throw AttachmentException(AttachmentFailure.CORRUPT_IMAGE)
                    }
                    if (read < 0) break
                    if (read > 0) {
                        scratch.copyInto(buffer, destinationOffset = filled, endIndex = read)
                        filled += read
                        if (filled > limit) throw AttachmentException(AttachmentFailure.TOO_LARGE)
                    }
                }
                if (filled > limit) throw AttachmentException(AttachmentFailure.TOO_LARGE)
                return buffer.copyOf(filled)
            } finally {
                scratch.fill(0)
                buffer.fill(0)
            }
        }
    }
}
