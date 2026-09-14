package com.commandcode.chat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.ui.AppUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionDismissalTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun consumedChildTapDoesNotInvokeTapAwayDismissal() {
        var childClicks = 0
        var dismissals = 0
        compose.setContent {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .testTag("dismissal_host")
                    .dismissSelectionOnTapAway(true) { dismissals += 1 },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(80.dp)
                        .testTag("consuming_child")
                        .clickable { childClicks += 1 },
                )
            }
        }

        compose.onNodeWithTag("consuming_child").performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, childClicks)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun unconsumedBackgroundTapInvokesTapAwayDismissal() {
        var dismissals = 0
        compose.setContent {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .testTag("dismissal_host")
                    .dismissSelectionOnTapAway(true) { dismissals += 1 },
            )
        }

        compose.onNodeWithTag("dismissal_host").performTouchInput {
            click(Offset(16f, 16f))
        }

        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun blankBackgroundTapClearsSelectedMessageText() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = selectionState(),
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

        compose.onNodeWithTag("message_text_selected").performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_select_text_selected").performClick()
        compose.onNodeWithTag("message_selectable_text_selected").assertIsDisplayed()

        compose.onNodeWithTag("chat_messages").performTouchInput {
            val size = visibleSize
            click(Offset(size.width / 2f, 8f))
        }
        compose.waitForIdle()

        compose.onNodeWithTag("message_selectable_text_selected").assertDoesNotExist()
        compose.onNodeWithTag("message_text_selected").assertIsDisplayed()
    }

    @Test
    fun anotherMessageClickClearsSelectedMessageText() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = selectionState(),
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

        compose.onNodeWithTag("message_text_selected").performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_select_text_selected").performClick()
        compose.onNodeWithTag("message_selectable_text_selected").assertIsDisplayed()

        compose.onNodeWithTag("message_text_other").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("message_selectable_text_selected").assertDoesNotExist()
    }

    private fun selectionState() = AppUiState(
        loading = false,
        keyConfigured = true,
        systemPromptLoaded = true,
        currentConversationId = "conversation",
        messages = listOf(
            Message("selected", "conversation", "USER", "Selected text", null, 1L, "COMPLETE"),
            Message("other", "conversation", "ASSISTANT", "Other text", null, 2L, "COMPLETE"),
        ),
    )
}
