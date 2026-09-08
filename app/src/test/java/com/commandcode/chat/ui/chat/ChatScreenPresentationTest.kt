package com.commandcode.chat.ui.chat

import androidx.compose.ui.text.input.KeyboardCapitalization
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.MessageState
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

        assertTrue(sendButtonEnabled(AppUiState(systemPromptLoaded = true, pendingAttachments = listOf(image))))
        assertTrue(sendButtonEnabled(AppUiState(draftText = "hello", systemPromptLoaded = true)))
        assertFalse(sendButtonEnabled(AppUiState()))
    }

    @Test
    fun sendStaysDisabledForBlankWhitespaceImportsAndAnUnloadedPrompt() {
        val image = ImageAttachment(
            id = "image",
            displayName = "photo.png",
            mimeType = "image/png",
            originalBytes = 4,
            encryptedBytes = 37,
            width = 2,
            height = 2,
        )

        assertFalse(sendButtonEnabled(AppUiState(draftText = "   ", systemPromptLoaded = true)))
        assertFalse(sendButtonEnabled(AppUiState(draftText = "\n\t ", systemPromptLoaded = true)))
        assertFalse(sendButtonEnabled(AppUiState(draftText = "ready text")))
        assertFalse(sendButtonEnabled(AppUiState(draftText = "ready text", importingAttachments = true, systemPromptLoaded = true)))
        assertFalse(sendButtonEnabled(AppUiState(systemPromptLoaded = true, importingAttachments = true, pendingAttachments = listOf(image))))
        assertFalse(sendButtonEnabled(AppUiState(draftText = "ready text", sending = true, systemPromptLoaded = true)))
        assertTrue(sendButtonEnabled(AppUiState(draftText = "ready text", systemPromptLoaded = true)))
    }

    @Test
    fun composerRequestsSentenceCapitalisationWithoutTransformingText() {
        assertEquals(KeyboardCapitalization.Sentences, composerKeyboardOptions.capitalization)
    }

    @Test
    fun disabledComposerDimsPlaceholderToMatchTheDimmedDraftText() {
        listOf(StandardAppColours, AmoledAppColours).forEach { colours ->
            assertEquals(colours.onSurface, composerContentColour(colours, enabled = true))
            assertEquals(colours.onSurface.copy(alpha = 0.38f), composerContentColour(colours, enabled = false))
            assertEquals(colours.onSurfaceVariant, composerPlaceholderColour(colours, enabled = true))
            assertEquals(colours.onSurfaceVariant.copy(alpha = 0.38f), composerPlaceholderColour(colours, enabled = false))
            assertTrue(
                "disabled placeholder must not read brighter than disabled content",
                composerPlaceholderColour(colours, enabled = false).alpha <=
                    composerContentColour(colours, enabled = false).alpha,
            )
        }
    }

    @Test
    fun initialModelNoticesResolveCatalogueDisplayNamesAndFallBackToContent() {
        val sol = ChatModel.SOL
        fun initial(createdAt: Long, content: String, modelId: String?) =
            Message("id$createdAt", "conversation", MessageState.INITIAL_MODEL, content, modelId, createdAt, "COMPLETE")

        assertEquals(
            "Model: GPT-5.6 Sol",
            noticeText(initial(1, sol.displayName, sol.apiId), listOf(sol)),
        )
        assertEquals(
            "Model: GPT-5.6 Sol",
            noticeText(initial(2, sol.apiId, sol.apiId), listOf(sol)),
        )
        assertEquals(
            "Model: gpt-5.6-sol",
            noticeText(initial(3, sol.apiId, sol.apiId), emptyList()),
        )
        assertEquals(
            "Model: retired-label",
            noticeText(initial(4, "retired-label", "retired-model"), emptyList()),
        )
    }

    @Test
    fun localNoticeLabelsCoverChangesAndPromptWithoutRenderingPrivateFingerprint() {
        val luna = ChatModel.LUNA
        fun message(role: String, content: String, modelId: String?) =
            Message("m", "conversation", role, content, modelId, 1L, "COMPLETE")

        assertEquals(
            "Model changed to GPT-5.6 Luna",
            noticeText(message(MessageState.MODEL_CHANGE, luna.displayName, luna.apiId), listOf(luna)),
        )
        assertEquals(
            "System Prompt changed",
            noticeText(message(MessageState.SYSTEM_PROMPT_CHANGE, "private-fingerprint", null), listOf(luna)),
        )
        assertNull(noticeText(message("USER", "Hello", null), listOf(luna)))
        assertNull(noticeText(message("ASSISTANT", "Hello", luna.apiId), listOf(luna)))
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
