package com.commandcode.chat.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.ui.AppUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun failedMessageLongPressOffersRetryAndDeleteAndSuccessfulMessagesDoNot() {
        var retried: String? = null
        var deleted: String? = null
        val rows = listOf(
            Message("failed", "conversation", "USER", "Try this", null, 1, "FAILED_ZDR"),
            Message("empty", "conversation", "ASSISTANT", "", null, 2, "FAILED_ZDR", replyToMessageId = "failed"),
            Message("notice", "conversation", "MODEL_CHANGE", "GPT-5.6 Luna", ChatModel.LUNA.apiId, 3, "COMPLETE"),
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(state().copy(messages = rows), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> error("unused") },
                    onRetryMessage = { retried = it }, onDeleteFailedMessage = { deleted = it })
            }
        }
        compose.onNodeWithTag("failed_message_failed").assertIsDisplayed()
            .assertContentDescriptionEquals("Message not sent. No ZDR-capable provider is available. Message actions")
        compose.onNodeWithTag("message_empty").assertDoesNotExist()
        compose.onNodeWithText("Model changed to GPT-5.6 Luna").assertIsDisplayed()
        compose.onNodeWithTag("message_failed").performTouchInput { longClick() }
        compose.onNodeWithText("Retry").performClick()
        assertEquals("failed", retried)
        compose.onNodeWithTag("failed_message_failed").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals("failed", deleted)
    }

    @Test
    fun retryThinkingAndStreamingRemainBetweenOriginalUserAndLaterMessages() {
        val live = mutableStateOf(state().copy(
            sending = true, activeConversationId = "conversation", activeAssistantMessageId = "retry",
            messages = listOf(
                Message("original", "conversation", "USER", "Original", null, 1, "COMPLETE"),
                Message("retry", "conversation", "ASSISTANT", "", null, 2, "PENDING", replyToMessageId = "original"),
                Message("later", "conversation", "USER", "Later", null, 3, "COMPLETE"),
            ),
        ))
        compose.setContent {
            MaterialTheme { ChatScreen(live.value, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> error("unused") }) }
        }
        val original = compose.onNodeWithTag("message_original").fetchSemanticsNode().boundsInRoot
        val thinking = compose.onNodeWithTag("thinking_indicator").fetchSemanticsNode().boundsInRoot
        val later = compose.onNodeWithTag("message_later").fetchSemanticsNode().boundsInRoot
        assertTrue("thinking belongs to original turn", original.bottom <= thinking.top && thinking.bottom <= later.top)
        compose.runOnIdle { live.value = live.value.copy(streamingText = "Retry answer") }
        val answer = compose.onNodeWithTag("active_assistant_response").fetchSemanticsNode().boundsInRoot
        assertTrue("stream stays at original turn", answer.bottom <= compose.onNodeWithTag("message_later").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    fun longStreamingResponseFollowsNewestTextUntilTheUserScrollsUp() {
        val live = mutableStateOf(state().copy(
            sending = true, activeConversationId = "conversation", activeAssistantMessageId = "pending",
            streamingText = (1..100).joinToString("\n") { "Line $it" },
        ))
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = live.value, onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                    onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                    onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("unused") },
                )
            }
        }
        fun scrollOffset() = compose.onNodeWithTag("chat_messages").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(0f, scrollOffset())
        compose.runOnIdle { live.value = live.value.copy(streamingText = live.value.streamingText + "\nLine 101") }
        assertEquals(0f, scrollOffset())
        compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val scrolledOffset = scrollOffset()
        assertTrue("user can scroll up in a long response", scrolledOffset > 0f)
        compose.runOnIdle { live.value = live.value.copy(streamingText = live.value.streamingText + "\nLine 102") }
        assertTrue("incoming text must not pull the user back to the bottom", scrollOffset() > 0f)
    }

    @Test
    fun manyAttachmentsRemainCompactStackedAndScrollableWithoutDisplacingComposer() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(400.dp)) {
                    ChatScreen(
                        state = state().copy(pendingAttachments = (1..20).map { image().copy(id = "image$it") }),
                        onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                        onDraftChange = {}, onAttach = {}, onRemoveAttachment = {}, onDismissAttachmentError = {},
                        loadAttachmentPreview = { _, _ -> Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) },
                    )
                }
            }
        }
        compose.onNodeWithTag("message_composer").assertIsDisplayed()
        compose.onNodeWithTag("send_message").assertIsDisplayed()
        val input = compose.onNodeWithTag("message_composer").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("pending_attachment_image1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("pending_attachment_image2").fetchSemanticsNode().boundsInRoot
        assertTrue("rows do not overlap", first.bottom <= second.top)
        assertTrue("rows are above composer", second.bottom <= input.top)
        val thumbnail = compose.onNodeWithTag("attachment_preview_image1").fetchSemanticsNode().boundsInRoot
        assertTrue("thumbnail is compact", thumbnail.height <= with(compose.density) { 56.dp.toPx() } + 1f)
        compose.onNodeWithTag("pending_attachments").performScrollToIndex(19)
        compose.onNodeWithTag("remove_attachment_image20").assertIsDisplayed()
        compose.onNodeWithTag("message_composer").assertIsDisplayed()
    }

    @Test
    fun thinkingIsReplacedByIncrementalAnswerAndStopsAfterCancellation() {
        val live = mutableStateOf(state().copy(
            sending = true, activeConversationId = "conversation", activeAssistantMessageId = "pending",
        ))
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = live.value, onSelectModel = {}, onNewChat = {}, onSend = {},
                    onCancel = { live.value = live.value.copy(sending = false, activeConversationId = null) },
                    onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                    onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("unused") },
                )
            }
        }
        compose.onNodeWithText("Thinking").assertIsDisplayed()
        compose.runOnIdle { live.value = live.value.copy(streamingText = "First") }
        compose.onNodeWithText("Thinking").assertDoesNotExist()
        compose.onNodeWithText("First").assertIsDisplayed()
        compose.runOnIdle { live.value = live.value.copy(streamingText = "First second") }
        compose.onNodeWithText("First second").assertIsDisplayed()
        compose.runOnIdle { live.value = live.value.copy(streamingText = "", currentConversationId = "other") }
        compose.onNodeWithText("Thinking").assertDoesNotExist()
        compose.runOnIdle { live.value = live.value.copy(currentConversationId = "conversation") }
        compose.onNodeWithText("Thinking").assertIsDisplayed()
        compose.onNodeWithTag("cancel_send").performClick()
        compose.onNodeWithText("Thinking").assertDoesNotExist()
    }

    @Test
    fun newConversationHeaderUsesModelFirstAndSimpleSendCopy() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(currentConversationId = null, messages = emptyList()),
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        compose.onNodeWithText("New Chat").assertIsDisplayed()
        compose.onAllNodesWithText("LOCAL TRANSCRIPT", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("ZDR", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Send").assertIsDisplayed()
        compose.onAllNodesWithText("Send securely").assertCountEquals(0)

        val modelBounds = compose.onNodeWithTag("model_selector").fetchSemanticsNode().boundsInRoot
        val newChatBounds = compose.onNodeWithTag("new_chat").fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText("New Chat").fetchSemanticsNode().boundsInRoot
        assertTrue("model=$modelBounds newChat=$newChatBounds", modelBounds.left < newChatBounds.left)
        assertTrue(
            "title=$titleBounds model=$modelBounds",
            kotlin.math.abs(titleBounds.center.y - modelBounds.center.y) <= 1f,
        )
        assertTrue(
            "title=$titleBounds newChat=$newChatBounds",
            kotlin.math.abs(titleBounds.center.y - newChatBounds.center.y) <= 1f,
        )
    }

    @Test
    fun newChatInvokesCallbackAndClearsTheDraft() {
        var newChatCalls = 0
        compose.setContent {
            var uiState by remember { mutableStateOf(state()) }
            MaterialTheme {
                ChatScreen(
                    state = uiState,
                    onSelectModel = {},
                    onNewChat = {
                        newChatCalls += 1
                        uiState = uiState.copy(draftText = "")
                    },
                    onSend = {},
                    onCancel = {},
                    onDraftChange = { uiState = uiState.copy(draftText = it) },
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }
        compose.onNodeWithTag("message_composer").performTextInput("draft message")

        compose.onNodeWithTag("new_chat")
            .assertIsEnabled()
            .assertContentDescriptionEquals("New chat")
            .performClick()

        assertEquals(1, newChatCalls)
        compose.onNodeWithTag("message_composer").assert(
            SemanticsMatcher("Editable text is empty") { node ->
                node.config[SemanticsProperties.EditableText].text.isEmpty()
            }
        )
        compose.onAllNodesWithText("New chat").assertCountEquals(0)
    }

    @Test
    fun brainControlOpensTheModelDropdownAndSelectsAModel() {
        var selected: ChatModel? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(models = listOf(ChatModel.SOL, ChatModel.LUNA)),
                    onSelectModel = { selected = it },
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        compose.onNodeWithTag("model_selector")
            .assertContentDescriptionEquals("Select chat model. Current: GPT-5.6 Sol")
            .performClick()
        compose.onNodeWithTag("model_menu").assertIsDisplayed()
        compose.onNodeWithTag(modelOptionTestTag(ChatModel.LUNA.apiId)).performClick()

        assertEquals(ChatModel.LUNA, selected)
    }

    @Test
    fun newChatIsDisabledWhileSending() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(sending = true),
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        compose.onNodeWithTag("new_chat").assertIsNotEnabled()
    }

    @Test
    fun messagesUseStableRoleIdentityAndOppositeBoundedAlignment() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(
                        messages = listOf(
                            Message("user", "conversation", "USER", "Question", null, 1L, "COMPLETE"),
                            Message("assistant", "conversation", "ASSISTANT", "Answer", null, 2L, "COMPLETE"),
                        ),
                        activeConversationId = "conversation",
                        activeAssistantMessageId = "pending",
                        streamingText = "Streaming answer",
                        sending = true,
                    ),
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        val user = compose.onNodeWithTag("message_user")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("User message")
            .fetchSemanticsNode().boundsInRoot
        val assistant = compose.onNodeWithTag("message_assistant")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Assistant message")
            .fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("active_assistant_response")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Assistant message")
        compose.onNodeWithText("Question").assertIsDisplayed()
        compose.onNodeWithText("Answer").assertIsDisplayed()
        compose.onNodeWithText("Streaming answer").assertIsDisplayed()
        compose.onAllNodesWithText("USER · COMPLETE").assertCountEquals(0)
        compose.onAllNodesWithText("ASSISTANT · COMPLETE").assertCountEquals(0)
        compose.onAllNodesWithText("ASSISTANT · STREAMING").assertCountEquals(0)

        assertTrue("user=$user assistant=$assistant", user.right > assistant.right)
        assertTrue("user=$user assistant=$assistant", assistant.left < user.left)
    }

    @Test
    fun attachControlRemainsVisibleAndAccessibleForAnUnverifiedModel() {
        var attachCalls = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(
                        selectedModel = ChatModel("unverified/model", "Unverified", ChatModel.SOL.apiFamily),
                    ),
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = { attachCalls += 1 },
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        compose.onNodeWithTag("attach_image")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Attach image")
            .performClick()
        assertEquals(1, attachCalls)
    }

    @Test
    fun typedUnsupportedErrorShowsOneSafeRedBoxAndNoGenericDuplicate() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(
                        attachmentError = AttachmentFailure.UNSUPPORTED_MODEL,
                        errorMessage = AttachmentPolicy.UNSUPPORTED_MESSAGE,
                    ),
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        compose.onNodeWithTag("attachment_error_box").assertIsDisplayed()
        compose.onNodeWithText(AttachmentPolicy.UNSUPPORTED_MESSAGE).assertIsDisplayed()
        compose.onAllNodesWithText(AttachmentPolicy.UNSUPPORTED_MESSAGE).assertCountEquals(1)
    }

    @Test
    fun failedSendKeepsControlledDraftUntilTheControllerChangesState() {
        var sentText: String? = null
        compose.setContent {
            var uiState by remember { mutableStateOf(state()) }
            MaterialTheme {
                ChatScreen(
                    state = uiState,
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = { sentText = it },
                    onCancel = {},
                    onDraftChange = { uiState = uiState.copy(draftText = it) },
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        compose.onNodeWithTag("message_composer").performTextInput("keep this text")
        compose.onNodeWithTag("send_message").performClick()

        assertEquals("keep this text", sentText)
        compose.onNodeWithTag("message_composer").assert(
            SemanticsMatcher("failed send retains editable text") { node ->
                node.config[SemanticsProperties.EditableText].text == "keep this text"
            },
        )
    }

    @Test
    fun imageOnlyDraftEnablesSendAndPendingRemovalUsesAttachmentId() {
        val image = image()
        var removedId: String? = null
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(draftText = "", pendingAttachments = listOf(image)),
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = {},
                    onRemoveAttachment = { removedId = it },
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }

        compose.onNodeWithTag("send_message").assertIsEnabled()
        compose.onNodeWithTag("pending_attachment_${image.id}").assertIsDisplayed()
        compose.onNodeWithText("photo.png").assertIsDisplayed()
        compose.onNodeWithText("<0.01 MB").assertIsDisplayed()
        compose.onNodeWithTag("remove_attachment_${image.id}").performClick()
        assertEquals(image.id, removedId)
    }

    @Test
    fun savedImageOnlyMessageShowsAttachmentMetadataAndSafePreviewFailure() {
        val image = image().copy(displayName = "photo\n.png")
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(
                        messages = listOf(
                            Message(
                                "image-message",
                                "conversation",
                                "USER",
                                "",
                                null,
                                1L,
                                "COMPLETE",
                                listOf(image),
                            ),
                        ),
                    ),
                    onSelectModel = {},
                    onNewChat = {},
                    onSend = {},
                    onCancel = {},
                    onDraftChange = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("private preview failure") },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("message_image-message").assertIsDisplayed()
        compose.onNodeWithTag("message_attachment_${image.id}").assertIsDisplayed()
        compose.onNodeWithText("photo.png").assertIsDisplayed()
        compose.onNodeWithText("<0.01 MB").assertIsDisplayed()
        compose.onNodeWithText("Preview unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("private preview failure").assertCountEquals(0)
    }

    @Test
    fun disposingPreviewCancelsLoaderAndReleasesBitmapReturnedAfterCancellation() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visible = mutableStateOf(true)
        var returned: Bitmap? = null

        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    AttachmentPreview(
                        image = image(),
                        maxEdge = 256,
                        load = { _, _ ->
                            entered.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { returned = it }
                        },
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) { entered.isCompleted }
        compose.runOnIdle { visible.value = false }
        release.complete(Unit)
        compose.waitForIdle()

        assertTrue(returned?.isRecycled == true)
    }

    private fun state() = AppUiState(
        loading = false,
        keyConfigured = true,
        currentConversationId = "conversation",
        messages = listOf(
            Message("message", "conversation", "USER", "Hello", null, 1L, "COMPLETE"),
        ),
    )

    private fun image() = ImageAttachment(
        id = "00000000-0000-0000-0000-000000000001",
        displayName = "photo.png",
        mimeType = "image/png",
        originalBytes = 4,
        encryptedBytes = 37,
        width = 2,
        height = 2,
    )
}
