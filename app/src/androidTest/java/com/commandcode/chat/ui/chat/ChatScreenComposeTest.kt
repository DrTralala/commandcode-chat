package com.commandcode.chat.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.commandcode.chat.domain.ConversationTokenUsage
import com.commandcode.chat.domain.ReportedUsage
import com.commandcode.chat.data.service.ContextWindowCatalogue
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
import com.commandcode.chat.domain.MessageState
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

    @Test fun contextIndicatorRemainsTappableWhileStreamingAndResetsDialogOnNavigation() {
        val live = mutableStateOf(state().copy(
            sending = true, activeConversationId = "conversation",
            tokenUsage = ConversationTokenUsage.aggregate(listOf(
                ReportedUsage("event", null, ChatModel.SOL.apiId, 1, 200, 0, 50, true),
            )),
            contextWindows = ContextWindowCatalogue(mapOf(ChatModel.SOL.apiId to 1000L)),
        ))
        compose.setContent {
            MaterialTheme {
                ChatScreen(live.value, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> error("unused") })
            }
        }
        compose.onNodeWithTag("context_usage").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("context_usage_dialog").assertIsDisplayed()
        compose.onNodeWithText("A response is streaming.", substring = true).assertExists()
        compose.onAllNodesWithText("250", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("1000", substring = true).assertCountEquals(0)
        compose.runOnIdle { live.value = live.value.copy(currentConversationId = "another", tokenUsage = ConversationTokenUsage()) }
        compose.onNodeWithTag("context_usage_dialog").assertDoesNotExist()
        compose.onNodeWithText("Context —").assertIsDisplayed()
    }

    @Test fun narrowLargeTextComposerKeepsContextAndActionButtonsVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(320.dp)) {
                        ChatScreen(state().copy(selectedModel = ChatModel.SOL.copy(displayName = "A very long model display name")),
                            {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> error("unused") })
                    }
                }
            }
        }
        compose.onNodeWithTag("context_usage").assertIsDisplayed().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("attach_image").assertIsDisplayed()
        compose.onNodeWithTag("send_message").assertIsDisplayed()
        val context = compose.onNodeWithTag("context_usage").fetchSemanticsNode().boundsInRoot
        val attach = compose.onNodeWithTag("attach_image").fetchSemanticsNode().boundsInRoot
        assertTrue("context must not overlap attachment target", context.right <= attach.left)
    }

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
        compose.onNodeWithTag("send_message")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Send message")
        compose.onAllNodesWithText("Send").assertCountEquals(0)
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

    @Test
    fun composerOutlineEnclosesTextAreaAndSendIconBelowTheEditableText() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(draftText = "Ready to send", messages = emptyList()),
                    onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                    onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                    onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }
        val container = compose.onNodeWithTag("composer_container").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("message_composer").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("send_message").fetchSemanticsNode().boundsInRoot
        fun inside(bounds: androidx.compose.ui.geometry.Rect) =
            bounds.left >= container.left - 1f && bounds.right <= container.right + 1f &&
                bounds.top >= container.top - 1f && bounds.bottom <= container.bottom + 1f
        assertTrue("message_composer $input must be inside composer_container $container", inside(input))
        assertTrue("send_message $send must be inside composer_container $container", inside(send))
        assertTrue("send $send must sit below the editable text $input", send.top >= input.bottom - 1f)
    }

    @Test
    fun longModelNameStaysCompactWithoutDisplacingTheActionIcons() {
        val longModel = ChatModel(
            "long/model",
            "A very long model display name that keeps going well beyond any reasonable width",
            ChatModel.SOL.apiFamily,
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(300.dp)) {
                    ChatScreen(
                        state = state().copy(models = listOf(longModel), selectedModel = longModel, messages = emptyList()),
                        onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                        onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                        onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("preview not used") },
                    )
                }
            }
        }
        val container = compose.onNodeWithTag("composer_container").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithTag("composer_model").fetchSemanticsNode().boundsInRoot
        val attach = compose.onNodeWithTag("attach_image").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("send_message").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("send_message").assertIsDisplayed()
        compose.onNodeWithTag("composer_model").assertIsDisplayed()
        assertTrue("model $model must stay left of attach $attach", model.right <= attach.left + 1f)
        assertTrue("attach $attach must stay left of send $send", attach.right <= send.left + 1f)
        assertTrue("send $send must remain inside composer $container", send.right <= container.right + 1f)
        assertTrue("model name must stay on one compact line", model.height <= attach.height + 1f)
    }

    @Test
    fun stopIconReplacesSendAndRemainsAvailableWhileStreaming() {
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(
                        sending = true,
                        activeConversationId = "conversation",
                        activeAssistantMessageId = "pending",
                        streamingText = "Partial answer",
                        draftText = "",
                    ),
                    onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = { cancelled = true },
                    onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                    onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }
        compose.onNodeWithTag("send_message").assertDoesNotExist()
        compose.onNodeWithTag("cancel_send").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("message_composer").assertIsNotEnabled()
        compose.onNodeWithTag("attach_image").assertIsNotEnabled()
        compose.onNodeWithTag("cancel_send").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun emptyUnsavedChatPreviewsSelectedModelAndSavedEmptyChatDoesNot() {
        val live = mutableStateOf(state().copy(currentConversationId = null, messages = emptyList()))
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = live.value,
                    onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                    onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                    onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }
        compose.onNodeWithTag("initial_model_preview").assertIsDisplayed()
        compose.onNodeWithText("Model: GPT-5.6 Sol").assertIsDisplayed()

        compose.runOnIdle { live.value = live.value.copy(selectedModel = ChatModel.LUNA) }
        compose.onNodeWithText("Model: GPT-5.6 Luna").assertIsDisplayed()
        compose.onAllNodesWithText("Model: GPT-5.6 Sol").assertCountEquals(0)

        compose.runOnIdle {
            live.value = live.value.copy(
                currentConversationId = "conversation",
                messages = emptyList(),
                selectedModel = ChatModel.SOL,
            )
        }
        compose.onNodeWithTag("initial_model_preview").assertDoesNotExist()
        compose.onAllNodesWithText("Model: GPT-5.6 Sol").assertCountEquals(0)
    }

    @Test
    fun localNoticesRenderStartingModelsChangesAndPromptSwitchCopyOnly() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(
                        models = listOf(ChatModel.SOL, ChatModel.LUNA),
                        messages = listOf(
                            Message("initial-new", "conversation", MessageState.INITIAL_MODEL, ChatModel.SOL.displayName, ChatModel.SOL.apiId, 1L, "COMPLETE"),
                            Message("initial-legacy", "conversation", MessageState.INITIAL_MODEL, ChatModel.LUNA.apiId, ChatModel.LUNA.apiId, 2L, "COMPLETE"),
                            Message("changed", "conversation", MessageState.MODEL_CHANGE, ChatModel.LUNA.displayName, ChatModel.LUNA.apiId, 3L, "COMPLETE"),
                            Message("prompt", "conversation", MessageState.SYSTEM_PROMPT_CHANGE, "fingerprint-not-rendered", null, 4L, "COMPLETE"),
                        ),
                    ),
                    onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                    onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                    onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }
        compose.onNodeWithTag("local_notice_initial-new").assertIsDisplayed()
        compose.onNodeWithTag("local_notice_initial-legacy").assertIsDisplayed()
        compose.onNodeWithTag("local_notice_changed").assertIsDisplayed()
        compose.onNodeWithTag("local_notice_prompt").assertIsDisplayed()
        compose.onNodeWithText("Model: GPT-5.6 Sol").assertIsDisplayed()
        compose.onNodeWithText("Model: GPT-5.6 Luna").assertIsDisplayed()
        compose.onNodeWithText("Model changed to GPT-5.6 Luna").assertIsDisplayed()
        compose.onNodeWithText("System Prompt changed").assertIsDisplayed()
        compose.onAllNodesWithText("fingerprint-not-rendered").assertCountEquals(0)
        compose.onAllNodesWithText("Model changed to GPT-5.6 Sol").assertCountEquals(0)
    }

    @Test
    fun composerKeepsIntentionalLowercaseAndPunctuationUntransformed() {
        compose.setContent {
            var uiState by remember { mutableStateOf(state()) }
            MaterialTheme {
                ChatScreen(
                    state = uiState,
                    onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                    onDraftChange = { uiState = uiState.copy(draftText = it) },
                    onAttach = {}, onRemoveAttachment = {}, onDismissAttachmentError = {},
                    loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }
        val typed = "intentional lowercase 'quoted' text stays as typed"
        compose.onNodeWithTag("message_composer").performTextInput(typed)
        compose.onNodeWithTag("message_composer").assert(
            SemanticsMatcher("lowercase draft is preserved verbatim") { node ->
                node.config[SemanticsProperties.EditableText].text == typed
            },
        )
    }

    @Test
    fun sendStaysDisabledWhileTheSavedSystemPromptIsUnavailable() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state().copy(draftText = "hello", systemPromptLoaded = false),
                    onSelectModel = {}, onNewChat = {}, onSend = {}, onCancel = {},
                    onDraftChange = {}, onAttach = {}, onRemoveAttachment = {},
                    onDismissAttachmentError = {}, loadAttachmentPreview = { _, _ -> error("preview not used") },
                )
            }
        }
        compose.onNodeWithTag("message_composer").assertIsEnabled()
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
    }

    private fun state() = AppUiState(
        loading = false,
        keyConfigured = true,
        systemPromptLoaded = true,
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
