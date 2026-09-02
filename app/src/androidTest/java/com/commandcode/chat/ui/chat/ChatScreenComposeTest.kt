package com.commandcode.chat.ui.chat

import androidx.compose.material3.MaterialTheme
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
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.ui.AppUiState
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
        assertTrue("model=$modelBounds newChat=$newChatBounds", modelBounds.left < newChatBounds.left)
    }

    @Test
    fun newChatInvokesCallbackAndClearsTheDraft() {
        var newChatCalls = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = state(),
                    onSelectModel = {},
                    onNewChat = { newChatCalls += 1 },
                    onSend = {},
                    onCancel = {},
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

        assertTrue("user=$user assistant=$assistant", user.right > assistant.right)
        assertTrue("user=$user assistant=$assistant", assistant.left < user.left)
    }

    private fun state() = AppUiState(
        loading = false,
        keyConfigured = true,
        currentConversationId = "conversation",
        messages = listOf(
            Message("message", "conversation", "USER", "Hello", null, 1L, "COMPLETE"),
        ),
    )
}
