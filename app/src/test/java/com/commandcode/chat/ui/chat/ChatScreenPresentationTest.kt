package com.commandcode.chat.ui.chat

import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.ui.AmoledAppColours
import com.commandcode.chat.ui.AppUiState
import com.commandcode.chat.ui.StandardAppColours
import com.commandcode.chat.ui.attachmentPickerMimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenPresentationTest {
    @Test
    fun userAndAssistantMessagesUseOppositeSides() {
        assertEquals(MessageSide.END, messageSide("USER"))
        assertEquals(MessageSide.START, messageSide("ASSISTANT"))
    }

    @Test
    fun userUsesGreenContainerAndAssistantUsesBlueContainerInBothThemes() {
        listOf(StandardAppColours, AmoledAppColours).forEach { colours ->
            assertEquals(colours.secondaryContainer, messageContainerColour("USER", colours))
            assertEquals(colours.onSecondaryContainer, messageContentColour("USER", colours))
            assertEquals(colours.primaryContainer, messageContainerColour("ASSISTANT", colours))
            assertEquals(colours.onPrimaryContainer, messageContentColour("ASSISTANT", colours))
        }
    }

    @Test
    fun modelOptionTagSanitisesEveryNonLetterOrDigitAndPreservesCase() {
        assertEquals(
            "model_option_Qwen_Qwen3_8_Flash",
            modelOptionTestTag("Qwen/Qwen3.8-Flash"),
        )
    }

    @Test
    fun headingUsesNewChatUntilTheSelectedConversationIsAvailable() {
        assertEquals("New Chat", chatHeading(AppUiState(currentConversationId = null)))
        assertEquals("New Chat", chatHeading(AppUiState(currentConversationId = "pending")))
        assertEquals(
            "Saved conversation",
            chatHeading(
                AppUiState(
                    currentConversationId = "conversation",
                    conversations = listOf(
                        Conversation(
                            id = "conversation",
                            title = "Saved conversation",
                            defaultModelId = "gpt-5.6-sol",
                            createdAt = 1L,
                            updatedAt = 2L,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun imageOnlyDraftEnablesSendWithoutChangingTextOnlyBehaviour() {
        val image = ImageAttachment(
            id = "image",
            displayName = "photo.png",
            mimeType = "image/png",
            originalBytes = 4,
            encryptedBytes = 37,
            width = 2,
            height = 2,
        )

        assertTrue(sendButtonEnabled(AppUiState(pendingAttachments = listOf(image))))
        assertTrue(sendButtonEnabled(AppUiState(draftText = "hello")))
        assertFalse(sendButtonEnabled(AppUiState()))
    }

    @Test
    fun disabledModelsShowOnlyTheSimpleAttachmentError() {
        val unsupported = attachmentErrorCopy(AttachmentFailure.UNSUPPORTED_MODEL)
        assertEquals("This model does not support attachments", unsupported.primary)
        assertNull(unsupported.secondary)

        val unverified = attachmentErrorCopy(AttachmentFailure.UNVERIFIED_MODEL)
        assertEquals("This model does not support attachments", unverified.primary)
        assertNull(unverified.secondary)
    }

    @Test
    fun unreadableImageUsesRequestedUploadFailureCopy() {
        val copy = attachmentErrorCopy(AttachmentFailure.CORRUPT_IMAGE)
        assertEquals("Failed to upload image. Please try again.", copy.primary)
        assertNull(copy.secondary)
    }

    @Test
    fun attachmentNamesRemoveControlsAndPickerUsesPolicyOrder() {
        assertEquals("photo.png", safeAttachmentDisplayName(" photo\n.png "))
        assertEquals("Image", safeAttachmentDisplayName("\u0000\n"))
        assertArrayEquals(
            arrayOf("image/jpeg", "image/png", "image/webp"),
            attachmentPickerMimeTypes("gpt-5.6-sol"),
        )
    }
}
