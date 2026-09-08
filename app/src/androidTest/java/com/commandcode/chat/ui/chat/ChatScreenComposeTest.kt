package com.commandcode.chat.ui.chat

import android.graphics.Bitmap
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
