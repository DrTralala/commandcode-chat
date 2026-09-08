package com.commandcode.chat.domain

enum class AttachmentSupport { DOCUMENTED, UNSUPPORTED, UNVERIFIED }

data class AttachmentCapability(val support: AttachmentSupport, val mimeTypes: Set<String>)

object AttachmentPolicy {
    const val MAX_IMAGE_BYTES: Long = 10_000_000L
    const val MAX_IMAGES_PER_CHAT: Int = 20
    const val MAX_DIMENSION: Int = 8000
    const val UNSUPPORTED_MESSAGE: String = "This model does not support attachments"

    private val IMAGE_ONLY_MODEL_IDS: Set<String> = setOf(
        "gpt-5.6-sol",
        "gpt-5.6-luna",
        "google/gemini-3.7-flash",
    )
    private val IMAGE_MIME_TYPES: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
    )

    fun capability(modelId: String): AttachmentCapability =
        if (modelId in IMAGE_ONLY_MODEL_IDS) {
            AttachmentCapability(AttachmentSupport.DOCUMENTED, IMAGE_MIME_TYPES)
        } else {
            AttachmentCapability(AttachmentSupport.UNVERIFIED, emptySet())
        }

    fun requireAllowed(modelId: String, mimeType: String) {
        if (modelId !in IMAGE_ONLY_MODEL_IDS) throw AttachmentException(AttachmentFailure.UNVERIFIED_MODEL)
        if (mimeType !in IMAGE_MIME_TYPES) throw AttachmentException(AttachmentFailure.INVALID_FORMAT)
    }

    fun requireCount(saved: Int, pending: Int, incoming: Int) {
        require(saved >= 0) { "saved count must be non-negative" }
        require(pending >= 0) { "pending count must be non-negative" }
        require(incoming >= 0) { "incoming count must be non-negative" }
        val total: Long = saved.toLong() + pending.toLong() + incoming.toLong()
        if (total > MAX_IMAGES_PER_CHAT.toLong()) throw AttachmentException(AttachmentFailure.TOO_MANY)
    }
}
