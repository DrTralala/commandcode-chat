package com.commandcode.chat.domain

data class ImageAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val originalBytes: Long,
    val encryptedBytes: Long,
    val width: Int,
    val height: Int,
)

enum class AttachmentFailure {
    UNSUPPORTED_MODEL, UNVERIFIED_MODEL, INVALID_FORMAT, CORRUPT_IMAGE,
    TOO_LARGE, TOO_MANY, EXCESSIVE_DIMENSIONS, ANIMATED_IMAGE,
    STORAGE, KEY_UNAVAILABLE, STALE_SELECTION, INCOMPATIBLE_HISTORY, CLEANUP,
}

class AttachmentException(
    val kind: AttachmentFailure,
    val databaseDeleted: Boolean? = null,
) : java.io.IOException(kind.name)
