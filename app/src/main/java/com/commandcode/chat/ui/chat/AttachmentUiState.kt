package com.commandcode.chat.ui.chat

import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ImageAttachment

/** A picker result is valid only for the draft ownership it was created for. */
data class PickerTicket(
    val generation: Long,
    val conversationId: String?,
    val modelId: String,
)

/** Attachment-specific state kept flat in [com.commandcode.chat.ui.AppUiState]. */
data class AttachmentUiState(
    val pendingAttachments: List<ImageAttachment> = emptyList(),
    val importingAttachments: Boolean = false,
    val attachmentError: AttachmentFailure? = null,
)
