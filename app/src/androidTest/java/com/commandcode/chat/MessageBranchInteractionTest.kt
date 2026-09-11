package com.commandcode.chat

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commandcode.chat.data.attachments.AttachmentOperations
import com.commandcode.chat.data.attachments.PreparedAttachmentTurn
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.AlternativeRequest
import com.commandcode.chat.data.database.ChatDatabase
import com.commandcode.chat.data.database.ChatRepository
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.security.SystemPromptStore
import com.commandcode.chat.data.service.ContextWindowCatalogue
import com.commandcode.chat.data.service.ModelCatalogueSnapshot
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.ui.AppRoot
import com.commandcode.chat.ui.AppViewModel
import com.commandcode.chat.ui.chat.timelineMessagesForDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageBranchInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private var mountedDatabase: ChatDatabase? = null
    private val testViewModelStore = ViewModelStore()

    @After
    fun closeTestDatabase() {
        testViewModelStore.clear()
        mountedDatabase?.close()
        mountedDatabase = null
    }

    @Test
    fun persistedMessagesExposeOrderedMenusAndCopyExactText() {
        val scenario = mount(seedScenario())
        val userId = scenario.original.userMessageId
        val assistantId = scenario.original.assistantMessageId

        compose.onNodeWithTag("message_text_$userId").performTouchInput { longClick() }
        val edit = compose.onNodeWithTag("message_menu_edit_$userId").assertIsDisplayed()
        val userCopy = compose.onNodeWithTag("message_menu_copy_$userId").assertIsDisplayed()
        assertTrue(edit.fetchSemanticsNode().boundsInRoot.top < userCopy.fetchSemanticsNode().boundsInRoot.top)
        userCopy.performClick()
        assertEquals("line one\nline <two>", clipboardText())

        compose.onNodeWithTag("message_text_$assistantId").performTouchInput { longClick() }
        val assistantCopy = compose.onNodeWithTag("message_menu_copy_$assistantId").assertIsDisplayed()
        val retry = compose.onNodeWithTag("message_menu_retry_$assistantId").assertIsDisplayed()
        assertTrue(assistantCopy.fetchSemanticsNode().boundsInRoot.top < retry.fetchSemanticsNode().boundsInRoot.top)

        val notice = scenario.viewModel.state.value.messages.first { it.role == "INITIAL_MODEL" }
        compose.onNodeWithTag("local_notice_${notice.id}").assertIsDisplayed()
        compose.onNodeWithTag("local_notice_${notice.id}").performTouchInput { longClick() }
        compose.onAllNodesWithTag("message_menu_copy_${notice.id}").assertCountEquals(0)
        compose.onAllNodesWithTag("message_menu_edit_${notice.id}").assertCountEquals(0)
    }

    @Test
    fun selectTextMenuEntersSelectionModeForBothMessageRoles() {
        val scenario = mount(seedScenario(assistantText = "answer first\nanswer <second>"))
        val userId = scenario.original.userMessageId
        val assistantId = scenario.original.assistantMessageId

        openMessageMenu(userId)
        assertTrue(
            compose.onNodeWithTag("message_menu_copy_$userId").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("message_menu_select_text_$userId").fetchSemanticsNode().boundsInRoot.top,
        )
        compose.onNodeWithTag("message_menu_select_text_$userId").performClick()
        compose.onNodeWithTag("message_selectable_text_$userId").assertIsDisplayed()
        pressBack()

        openMessageMenu(assistantId)
        assertTrue(
            compose.onNodeWithTag("message_menu_retry_$assistantId").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("message_menu_select_text_$assistantId").fetchSemanticsNode().boundsInRoot.top,
        )
        compose.onNodeWithTag("message_menu_select_text_$assistantId").performClick()
        compose.onNodeWithTag("message_selectable_text_$assistantId").assertIsDisplayed()
        pressBack()
    }

    @Test
    fun selectTextCreatesNativeSelectionHandlesInsideTheBubble() {
        val scenario = mount(seedScenario(assistantText = "answer first\nanswer <second>"))
        val userId = scenario.original.userMessageId

        openTextSelection(userId)
        val text = compose.onNodeWithTag("message_selectable_text_$userId")
        val bounds = text.fetchSemanticsNode().boundsInRoot
        text.performTouchInput {
            longClick(Offset(bounds.width * 0.7f, bounds.height * 0.25f))
        }

        val handles = compose.onAllNodes(selectionHandleMatcher, useUnmergedTree = true)
        handles.assertCountEquals(2)
    }

    @Test
    fun selectTextCopiesProperSubstringFromOriginalUserAndAssistantBubbles() {
        val scenario = mount(seedScenario(assistantText = "answer first\nanswer <second>"))

        val userSelection = selectTextToEndAndCopy(scenario.original.userMessageId)
        assertEquals("one\nline <two", userSelection)
        assertTrue(userSelection != "line one\nline <two>")
        pressBack()
        compose.onNodeWithTag("message_selectable_text_${scenario.original.userMessageId}").assertDoesNotExist()

        val assistantSelection = selectTextToEndAndCopy(scenario.original.assistantMessageId)
        assertEquals(
            "first\nanswer <second",
            assistantSelection,
        )
        assertTrue(assistantSelection != "answer first\nanswer <second>")
    }

    @Test
    fun selectTextCopiesProperSubstringFromNativeFloatingToolbarForBothRoles() {
        val scenario = mount(seedScenario(assistantText = "answer first\nanswer <second>"))

        val userSelection = selectTextToEndAndCopyFromNativeToolbar(
            scenario.original.userMessageId,
            "one\nline <two",
        )
        assertEquals("one\nline <two", userSelection)
        pressBack()

        val assistantSelection = selectTextToEndAndCopyFromNativeToolbar(
            scenario.original.assistantMessageId,
            "first\nanswer <second",
        )
        assertEquals("first\nanswer <second", assistantSelection)
    }

    @Test
    fun selectTextExitsWhenAnotherMessageEntersTheEditor() {
        val scenario = mount(seedScenario(withAnotherMessage = true))
        val firstId = scenario.original.userMessageId
        val anotherId = requireNotNull(scenario.another).userMessageId

        startTextSelection(firstId)
        openEditor(anotherId)
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.viewModel.state.value.messageEdit?.messageId == anotherId
        }
        compose.onNodeWithTag("message_selectable_text_$firstId").assertDoesNotExist()
    }

    @Test
    fun selectTextClearsWhenAnotherMessageMenuOpens() {
        val scenario = mount(seedScenario(withAnotherMessage = true))
        val firstId = scenario.original.userMessageId
        val anotherId = requireNotNull(scenario.another).userMessageId

        startTextSelection(firstId)
        openMessageMenu(anotherId)
        compose.onAllNodes(selectionHandleMatcher, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("message_selectable_text_$firstId").assertDoesNotExist()
    }

    @Test
    fun selectTextClearsWhenGlobalModelActionOpens() {
        val scenario = mount(seedScenario())
        val userId = scenario.original.userMessageId

        startTextSelection(userId)
        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag("model_menu").assertIsDisplayed()
        compose.onAllNodes(selectionHandleMatcher, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("message_selectable_text_$userId").assertDoesNotExist()
    }

    @Test
    fun selectTextDoesNotOpenImeWhileTheMessageListScrolls() {
        val scenario = mount(seedScenario(extraTurns = 24))
        val userId = scenario.original.userMessageId

        scrollToMessage(scenario, userId)
        startTextSelection(userId)
        compose.waitUntil(timeoutMillis = 5_000) { !imeVisible() }
        compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(!imeVisible())
    }

    @Test
    fun selectTextExitsOnBranchSwitchAndEditorCanBeReentered() {
        val scenario = mount(seedBranchScenario())
        val originalId = scenario.original.userMessageId
        val alternativeId = scenario.userAlternatives.first().userMessageId

        startTextSelection(originalId)
        compose.onNodeWithTag("branch_next_$originalId")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.viewModel.state.value.messages.any { it.id == alternativeId }
        }
        compose.onNodeWithTag("message_selectable_text_$originalId").assertDoesNotExist()

        openEditor(alternativeId)
        compose.onNodeWithTag("message_editor_$alternativeId").assertIsDisplayed()
        hideIme()
        compose.onNodeWithTag("message_edit_cancel_$alternativeId").performClick()
        compose.waitUntil { scenario.viewModel.state.value.messageEdit == null }

        openEditor(alternativeId)
        compose.onNodeWithTag("message_editor_$alternativeId").assertIsDisplayed()
    }

    @Test
    fun pendingPersistedAssistantDisablesCopyAndRetry() {
        val scenario = mount(seedScenario(pendingAssistant = true))
        val assistantId = scenario.original.assistantMessageId

        compose.onNodeWithTag("message_$assistantId").performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_copy_$assistantId").assertIsNotEnabled()
        compose.onNodeWithTag("message_menu_retry_$assistantId").assertIsNotEnabled()
    }

    @Test
    fun emptyMessageDisablesSelectTextAlongsideCopy() {
        val scenario = mount(seedScenario(pendingAssistant = true))
        val assistantId = scenario.original.assistantMessageId

        compose.onNodeWithTag("message_$assistantId").performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_copy_$assistantId").assertIsNotEnabled()
        compose.onNodeWithTag("message_menu_select_text_$assistantId").assertIsNotEnabled()
    }

    @Test
    fun editorPreservesComposerAndGuardsTabNavigationUntilConfirmation() {
        val scenario = mount(seedScenario())
        val userId = scenario.original.userMessageId

        compose.onNodeWithTag("message_composer").performTextInput("ordinary draft")
        openEditor(userId)
        compose.onNodeWithTag("message_editor_$userId").performTextReplacement("edited")
        compose.onNodeWithTag("send_message").assertIsNotEnabled()
        hideIme()

        compose.onNodeWithTag("nav_chat", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("Unsaved changes will be lost. Proceed?").assertCountEquals(0)
        compose.onNodeWithTag("message_editor_$userId").assertTextEquals("edited")

        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText("Unsaved changes will be lost. Proceed?").assertIsDisplayed()
        compose.onNodeWithTag("discard_edit_cancel").performClick()
        compose.onNodeWithTag("message_editor_$userId").assertTextEquals("edited")

        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("discard_edit_yes").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { scenario.viewModel.state.value.messageEdit == null }
        compose.onNodeWithTag("message_editor_$userId").assertDoesNotExist()
        compose.onNodeWithTag("system_prompt_field").assertIsDisplayed()
    }

    @Test
    fun editingAnotherMessageUsesTheSameDiscardGuard() {
        val scenario = mount(seedScenario(withAnotherMessage = true))
        val firstId = scenario.original.userMessageId
        val anotherId = scenario.another!!.userMessageId

        openEditor(firstId)
        compose.onNodeWithTag("message_editor_$firstId").performTextReplacement("edited first")
        hideIme()
        compose.onNodeWithTag("message_text_$anotherId")
            .performScrollTo()
            .performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_edit_$anotherId").performClick()
        compose.onNodeWithText("Unsaved changes will be lost. Proceed?").assertIsDisplayed()
        compose.onNodeWithTag("discard_edit_cancel").performClick()
        compose.onNodeWithTag("message_editor_$firstId").assertTextEquals("edited first")

        compose.onNodeWithTag("message_text_$anotherId")
            .performScrollTo()
            .performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_edit_$anotherId").performClick()
        compose.onNodeWithTag("discard_edit_yes").performClick()
        compose.waitUntil { scenario.viewModel.state.value.messageEdit?.messageId == anotherId }
        compose.onNodeWithTag("message_editor_$anotherId").assertIsDisplayed()
    }

    @Test
    fun branchSelectionWhileEditingUsesTheDiscardGuard() {
        val scenario = mount(seedBranchScenario())
        val userId = scenario.original.userMessageId
        val alternativeId = scenario.userAlternatives.first().userMessageId

        openEditor(userId)
        hideIme()
        compose.onNodeWithTag("branch_next_$userId")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText("Unsaved changes will be lost. Proceed?").assertIsDisplayed()
        compose.onNodeWithTag("discard_edit_cancel").performClick()
        compose.onNodeWithTag("message_editor_$userId").assertIsDisplayed()

        compose.onNodeWithTag("branch_next_$userId").performScrollTo().performClick()
        compose.onNodeWithTag("discard_edit_yes").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.viewModel.state.value.messageEdit == null &&
                scenario.viewModel.state.value.messages.any { it.id == alternativeId }
        }
        compose.onNodeWithTag("message_editor_$userId").assertDoesNotExist()
    }

    @Test
    fun keyLossClearsSuspendedEditorBeforeRoutingToSettings() {
        val scenario = mount(seedScenario())
        val userId = scenario.original.userMessageId
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario.operations.prepareEntered = entered
        scenario.operations.prepareRelease = release

        openEditor(userId)
        compose.onNodeWithTag("message_editor_$userId").performTextReplacement("in flight")
        hideIme()
        compose.onNodeWithTag("message_edit_save_$userId").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { entered.isCompleted }

        compose.runOnIdle { scenario.viewModel.clearApiKey() }
        compose.waitUntil(timeoutMillis = 5_000) {
            !scenario.viewModel.state.value.keyConfigured &&
                scenario.viewModel.state.value.messageEdit == null
        }
        compose.onNodeWithTag("system_prompt_field").assertIsDisplayed()

        release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { !scenario.viewModel.state.value.sending }
        compose.waitForIdle()
    }

    @Test
    fun newChatNavigationIsGuardedWhileEditing() {
        val scenario = mount(seedScenario())
        val userId = scenario.original.userMessageId

        openEditor(userId)
        hideIme()
        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithText("Unsaved changes will be lost. Proceed?").assertIsDisplayed()
        compose.onNodeWithTag("discard_edit_cancel").performClick()
        compose.onNodeWithTag("message_editor_$userId").assertIsDisplayed()

        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithTag("discard_edit_yes").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.viewModel.state.value.messageEdit == null &&
                scenario.viewModel.state.value.currentConversationId == null
        }
        compose.onNodeWithTag("message_editor_$userId").assertDoesNotExist()
        compose.onNodeWithText("New Chat").assertIsDisplayed()
    }

    @Test
    fun backNavigationIsGuardedAndThenClosesOnlyTheEditor() {
        val scenario = mount(seedScenario())
        val userId = scenario.original.userMessageId

        openEditor(userId)
        hideIme()
        pressBack()
        compose.onNodeWithText("Unsaved changes will be lost. Proceed?").assertIsDisplayed()
        compose.onNodeWithTag("discard_edit_cancel").performClick()
        compose.onNodeWithTag("message_editor_$userId").assertIsDisplayed()

        pressBack()
        compose.onNodeWithTag("discard_edit_yes").performClick()
        compose.waitUntil { scenario.viewModel.state.value.messageEdit == null }
        compose.onNodeWithTag("message_editor_$userId").assertDoesNotExist()
        assertEquals(scenario.conversationId, scenario.viewModel.state.value.currentConversationId)
    }

    @Test
    fun historicLargeMessagesRemainReachableForTheirNativeMenu() {
        val scenario = mount(
            seedScenario(
                originalText = "large message ".repeat(300),
                extraTurns = 24,
            ),
        )
        val userId = scenario.original.userMessageId

        val state = scenario.viewModel.state.value
        val conversationCreatedAt = state.conversations
            .first { it.id == scenario.conversationId }
            .createdAt
        val targetIndex = timelineMessagesForDisplay(state.messages, conversationCreatedAt)
            .asReversed()
            .indexOfFirst { it.id == userId }
        require(targetIndex >= 0)
        compose.onNodeWithTag("chat_messages").performScrollToIndex(targetIndex)
        compose.onNodeWithTag("message_text_$userId").assertIsDisplayed()
        compose.onNodeWithTag("message_text_$userId").performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_edit_$userId").assertIsDisplayed()
    }

    @Test
    fun editorCancelLeavesHistoryAndSaveCreatesASeparateAlternative() {
        val scenario = mount(seedScenario())
        val userId = scenario.original.userMessageId

        openEditor(userId)
        compose.onNodeWithTag("message_editor_$userId").performTextReplacement("discarded")
        hideIme()
        compose.onNodeWithTag("message_edit_cancel_$userId").performClick()
        compose.waitUntil { scenario.viewModel.state.value.messageEdit == null }
        assertEquals(
            "line one\nline <two>",
            scenario.viewModel.state.value.messages.single { it.id == userId }.content,
        )

        openEditor(userId)
        compose.onNodeWithTag("message_editor_$userId").performTextReplacement("saved alternative")
        hideIme()
        compose.onNodeWithTag("message_edit_save_$userId").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            val savedState = scenario.viewModel.state.value
            !savedState.sending && savedState.messageEdit == null &&
                savedState.messages.any { it.content == "saved alternative" }
        }
        assertEquals(
            "line one\nline <two>",
            runBlocking { scenario.database.messages().find(userId)?.content },
        )
    }

    @Test
    fun threeSiblingSelectorsUseCreationOrderAndRestoreNestedSelection() {
        val scenario = mount(seedBranchScenario())
        val originalUser = scenario.original.userMessageId
        val secondUser = scenario.userAlternatives[0].userMessageId
        val thirdUser = scenario.userAlternatives[1].userMessageId
        val originalAssistant = scenario.original.assistantMessageId
        val nestedAssistant = scenario.nestedAlternative.assistantMessageId

        compose.onNodeWithTag("branch_count_$originalUser").assertTextEquals("1/3")
        compose.onNodeWithTag("branch_previous_$originalUser").assertIsNotEnabled()
        compose.onNodeWithTag("branch_next_$originalUser").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == secondUser } }
        compose.onNodeWithTag("branch_count_$secondUser").assertTextEquals("2/3")
        compose.onNodeWithTag("branch_previous_$secondUser").assertIsEnabled()
        compose.onNodeWithTag("branch_next_$secondUser").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == thirdUser } }
        compose.onNodeWithTag("branch_count_$thirdUser").assertTextEquals("3/3")
        compose.onNodeWithTag("branch_next_$thirdUser").assertIsNotEnabled()

        compose.onNodeWithTag("branch_previous_$thirdUser").performScrollTo().performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == secondUser } }
        compose.onNodeWithTag("branch_next_$secondUser").performScrollTo().performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == thirdUser } }
        compose.onNodeWithTag("branch_previous_$thirdUser").performScrollTo().performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == secondUser } }

        compose.onNodeWithTag("branch_next_${scenario.userAlternatives[0].assistantMessageId}")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == nestedAssistant } }
        compose.onNodeWithText("nested response").assertIsDisplayed()

        compose.onNodeWithTag("branch_previous_$secondUser").performScrollTo().performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == originalUser } }
        compose.onNodeWithTag("branch_next_$originalUser").performScrollTo().performClick()
        compose.waitUntil { scenario.viewModel.state.value.messages.any { it.id == secondUser } }
        compose.onNodeWithText("nested response").assertIsDisplayed()
        compose.onNodeWithTag("branch_count_$originalAssistant").assertDoesNotExist()
    }

    @Test
    fun failedResponseSelectorSurvivesRedIndicatorDeletionWithoutFakeAssistantText() {
        val scenario = mount(seedFailedResponseScenario())
        val userId = scenario.original.userMessageId
        val failedAssistantId = scenario.failedResponse!!.assistantMessageId

        compose.onNodeWithTag("failed_message_$userId").assertIsDisplayed()
        compose.onNodeWithTag("branch_count_$failedAssistantId").assertTextEquals("4/4")
        compose.onNodeWithTag("branch_previous_$failedAssistantId").assertIsEnabled()
        compose.onNodeWithTag("branch_next_$failedAssistantId").assertIsNotEnabled()
        compose.onAllNodesWithTag("message_text_$failedAssistantId").assertCountEquals(0)

        compose.onNodeWithTag("failed_message_$userId").performClick()
        compose.onNodeWithTag("failed_message_delete_$userId").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.viewModel.state.value.messages.none { it.id == failedAssistantId }
        }
        compose.onNodeWithTag("failed_message_$userId").assertDoesNotExist()
        compose.onNodeWithTag("branch_count_$failedAssistantId").assertIsDisplayed()
        compose.onAllNodesWithTag("message_text_$failedAssistantId").assertCountEquals(0)
    }

    @Test
    fun failedResponseCanBeRetriedFromTheRedIndicator() {
        val scenario = mount(seedFailedResponseScenario())
        val userId = scenario.original.userMessageId

        compose.onNodeWithTag("failed_message_$userId").performClick()
        compose.onNodeWithTag("failed_message_retry_$userId")
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            val state = scenario.viewModel.state.value
            !state.sending && state.messages.any { it.id == userId && it.status == "COMPLETE" }
        }
        compose.onNodeWithTag("failed_message_$userId").assertDoesNotExist()
    }

    @Test
    fun failedProjectedUserCanOpenNormalMenuAndCreateAnEditedBranch() {
        val scenario = mount(seedFailedResponseScenario())
        val userId = scenario.original.userMessageId

        openMessageMenu(userId)
        compose.onNodeWithTag("message_menu_edit_$userId").assertIsDisplayed().performClick()
        compose.onNodeWithTag("message_editor_$userId").performTextReplacement("edited failed user")
        hideIme()
        compose.onNodeWithTag("message_edit_save_$userId").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            val state = scenario.viewModel.state.value
            !state.sending && state.messageEdit == null &&
                state.messages.any { it.role == "USER" && it.content == "edited failed user" }
        }
        assertEquals("line one\nline <two>", runBlocking {
            scenario.database.messages().find(userId)?.content
        })
    }

    @Test
    fun activeMessageCollectorSettlesBeforeServiceCleanupReturnsAfterDatabaseDeletion() {
        val scenario = mount(seedScenario())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario.operations.deleteEntered = entered
        scenario.operations.deleteRelease = release

        compose.runOnIdle { scenario.viewModel.deleteConversation(scenario.conversationId) }
        compose.waitUntil(timeoutMillis = 5_000) { entered.isCompleted }
        compose.waitUntil(timeoutMillis = 5_000) { scenario.viewModel.state.value.messages.isEmpty() }
        assertEquals(scenario.conversationId, scenario.viewModel.state.value.currentConversationId)
        assertTrue(scenario.viewModel.state.value.deletingCurrentConversation)

        release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.viewModel.state.value.currentConversationId == null
        }
    }

    @Test
    fun retainedAttachmentEditAllowsEmptyTextAndDisablesPendingRemoval() {
        val image = testImage("retained")
        val scenario = mount(seedScenario(originalAttachments = listOf(image)))
        val userId = scenario.original.userMessageId

        val ticket = compose.runOnIdle { requireNotNull(scenario.viewModel.requestAttachmentPicker()) }
        scenario.viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        compose.waitUntil(timeoutMillis = 5_000) {
            scenario.viewModel.state.value.pendingAttachments.isNotEmpty()
        }
        val pendingImage = scenario.viewModel.state.value.pendingAttachments.single()

        openEditor(userId)
        compose.onNodeWithTag("remove_attachment_${pendingImage.id}").assertIsNotEnabled()
        compose.onNodeWithTag("message_editor_$userId").performTextReplacement("")
        compose.onNodeWithTag("message_edit_save_$userId").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            val state = scenario.viewModel.state.value
            !state.sending && state.messageEdit == null &&
                state.messages.any { it.role == "USER" && it.content.isEmpty() }
        }

        val saved = runBlocking {
            scenario.repository.messagesSnapshot(scenario.conversationId).single {
                it.role == "USER" && it.content.isEmpty()
            }
        }
        assertEquals(listOf(image), saved.attachments)
    }

    private fun mount(scenario: Scenario): Scenario {
        mountedDatabase = scenario.database
        testViewModelStore.put("scenario", scenario.viewModel)
        mountUi(scenario.viewModel, scenario.conversationId, scenario.original.userMessageId)
        return scenario
    }

    private fun mount(scenario: BranchScenario): BranchScenario {
        mountedDatabase = scenario.database
        testViewModelStore.put("scenario", scenario.viewModel)
        mountUi(scenario.viewModel, scenario.conversationId, scenario.original.userMessageId)
        return scenario
    }

    private fun mountUi(viewModel: AppViewModel, conversationId: String, originalUserId: String) {
        hideIme()
        compose.activity.setContent { AppRoot(viewModel = viewModel) }
        compose.waitUntil(timeoutMillis = 5_000) { !viewModel.state.value.loading }
        compose.runOnIdle { viewModel.openConversation(conversationId) }
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.messages.any { it.id == originalUserId }
        }
    }

    private fun openEditor(messageId: String) {
        compose.onNodeWithTag("message_text_$messageId")
            .performScrollTo()
            .performTouchInput { longClick() }
        compose.onNodeWithTag("message_menu_edit_$messageId").performClick()
        compose.onNodeWithTag("message_editor_$messageId").assertIsDisplayed()
    }

    private fun openTextSelection(messageId: String) {
        openMessageMenu(messageId)
        compose.onNodeWithTag("message_menu_select_text_$messageId").performClick()
    }

    private fun openMessageMenu(messageId: String) {
        compose.onNodeWithTag("message_text_$messageId")
            .performScrollTo()
            .performTouchInput { longClick() }
    }

    private fun scrollToMessage(scenario: Scenario, messageId: String) {
        val state = scenario.viewModel.state.value
        val conversationCreatedAt = state.conversations
            .first { it.id == scenario.conversationId }
            .createdAt
        val targetIndex = timelineMessagesForDisplay(state.messages, conversationCreatedAt)
            .asReversed()
            .indexOfFirst { it.id == messageId }
        require(targetIndex >= 0)
        compose.onNodeWithTag("chat_messages").performScrollToIndex(targetIndex)
        compose.onNodeWithTag("message_text_$messageId").assertIsDisplayed()
    }

    private val selectionHandleMatcher = SemanticsMatcher("SelectionHandleInfo") { node: SemanticsNode ->
        node.config.any { it.key.name == "SelectionHandleInfo" }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun selectTextToEndAndCopy(messageId: String): String? {
        startTextSelection(messageId)
        val text = compose.onNodeWithTag("message_selectable_text_$messageId")
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val endHandle = compose.onNode(selectionHandleMatcherFor("SelectionEnd"), useUnmergedTree = true)
        val endPosition = selectionHandlePosition(endHandle.fetchSemanticsNode())
        val target = Offset(bounds.width - 1f, bounds.height - 1f)
        endHandle.performTouchInput {
            val origin = Offset(50f, 50f)
            swipe(origin, origin + target - endPosition, durationMillis = 500)
        }
        text.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
        }
        return clipboardText()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun selectTextToEndAndCopyFromNativeToolbar(messageId: String, expectedText: String): String? {
        compose.runOnIdle {
            compose.activity.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
        }
        startTextSelection(messageId)
        val text = compose.onNodeWithTag("message_selectable_text_$messageId")
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val endHandle = compose.onNode(selectionHandleMatcherFor("SelectionEnd"), useUnmergedTree = true)
        val endPosition = selectionHandlePosition(endHandle.fetchSemanticsNode())
        val target = Offset(bounds.width - 1f, bounds.height - 1f)
        endHandle.performTouchInput {
            val origin = Offset(50f, 50f)
            swipe(origin, origin + target - endPosition, durationMillis = 500)
        }
        compose.waitUntil(timeoutMillis = 5_000) { nativeFloatingToolbarBounds() != null }
        tapNativeToolbarCopy(requireNotNull(nativeFloatingToolbarBounds()))
        compose.waitUntil(timeoutMillis = 5_000) { clipboardText() == expectedText }
        return clipboardText()
    }

    private fun startTextSelection(messageId: String) {
        openTextSelection(messageId)
        val text = compose.onNodeWithTag("message_selectable_text_$messageId")
        val bounds = text.fetchSemanticsNode().boundsInRoot
        text.performTouchInput {
            longClick(Offset(bounds.width * 0.7f, bounds.height * 0.25f))
        }
        val handles = compose.onAllNodes(selectionHandleMatcher, useUnmergedTree = true)
        handles.assertCountEquals(2)
    }

    private fun selectionHandleMatcherFor(handle: String) =
        SemanticsMatcher("SelectionHandleInfo $handle") { node: SemanticsNode ->
            node.config.any { entry ->
                entry.key.name == "SelectionHandleInfo" && entry.value.toString().contains("handle=$handle")
            }
        }

    private fun selectionHandlePosition(node: SemanticsNode): Offset {
        val description = node.config
            .first { it.key.name == "SelectionHandleInfo" }
            .value
            .toString()
        val match = Regex("position=Offset\\((-?[0-9.]+), (-?[0-9.]+)\\)").find(description)
            ?: error("Selection handle position missing: $description")
        return Offset(match.groupValues[1].toFloat(), match.groupValues[2].toFloat())
    }

    private fun hideIme() {
        compose.runOnIdle {
            compose.activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
    }

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun clipboardText(): String? =
        compose.activity.getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(compose.activity)
            ?.toString()

    private data class NativeToolbarBounds(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun nativeFloatingToolbarBounds(): NativeToolbarBounds? {
        val dump = runDeviceShell("dumpsys window windows")
        if (!dump.contains("PopupWindow:")) return null
        val match = Regex(
            "mAttrs=\\{\\((-?\\d+),(-?\\d+)\\)\\((\\d+)x(\\d+)\\).*ty=APPLICATION_ABOVE_SUB_PANEL",
        ).find(dump) ?: return null
        return NativeToolbarBounds(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            width = match.groupValues[3].toInt(),
            height = match.groupValues[4].toInt(),
        )
    }

    private fun tapNativeToolbarCopy(bounds: NativeToolbarBounds) {
        val copyX = bounds.left + bounds.width / 6
        val copyY = bounds.top + bounds.height / 2
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input tap $copyX $copyY")
        descriptor.close()
    }

    private fun runDeviceShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun seedScenario(
        pendingAssistant: Boolean = false,
        withAnotherMessage: Boolean = false,
        originalText: String = "line one\nline <two>",
        assistantText: String = "assistant answer",
        extraTurns: Int = 0,
        originalAttachments: List<ImageAttachment> = emptyList(),
    ): Scenario = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        val repository = ChatRepository(database)
        val operations = RepositoryAttachmentOperations(repository)
        val original = repository.beginTurn(null, originalText, ChatModel.SOL, originalAttachments)
        if (!pendingAssistant) repository.completeTurn(original.assistantMessageId, assistantText, null)
        val another = if (withAnotherMessage) {
            repository.beginTurn(original.conversationId, "another question", ChatModel.SOL).also {
                repository.completeTurn(it.assistantMessageId, "another answer", null)
            }
        } else {
            null
        }
        repeat(extraTurns) { index ->
            repository.beginTurn(original.conversationId, "historic question $index", ChatModel.SOL).also {
                repository.completeTurn(it.assistantMessageId, "historic answer $index", null)
            }
        }
        Scenario(
            database = database,
            repository = repository,
            viewModel = testViewModel(repository, operations),
            conversationId = original.conversationId,
            original = original,
            another = another,
            operations = operations,
        )
    }

    private fun seedBranchScenario(): BranchScenario = runBlocking {
        val base = seedScenario()
        val repository = base.repository
        val userAlternatives = buildList {
            repeat(2) { index ->
                repository.selectAlternative(base.conversationId, base.original.userMessageId)
                val source = repository.alternativeSource(base.conversationId, base.original.userMessageId)
                val alternative = repository.createAlternative(
                    base.conversationId,
                    AlternativeRequest.Edit(base.original.userMessageId, "alternative ${index + 2}"),
                    ChatModel.SOL,
                    "",
                    source.expectedPathIds,
                )
                repository.completeTurn(alternative.assistantMessageId, "answer ${index + 2}", null)
                add(alternative)
            }
            repository.selectAlternative(base.conversationId, base.original.userMessageId)
        }
        val responseAlternatives = buildList {
            repeat(2) { index ->
                repository.selectAlternative(base.conversationId, base.original.assistantMessageId)
                val source = repository.alternativeSource(base.conversationId, base.original.assistantMessageId)
                val alternative = repository.createAlternative(
                    base.conversationId,
                    AlternativeRequest.Retry(base.original.assistantMessageId),
                    ChatModel.SOL,
                    "",
                    source.expectedPathIds,
                )
                repository.completeTurn(alternative.assistantMessageId, "response ${index + 2}", null)
                add(alternative)
            }
            repository.selectAlternative(base.conversationId, base.original.assistantMessageId)
        }
        repository.selectAlternative(base.conversationId, userAlternatives[0].userMessageId)
        val nestedSource = repository.alternativeSource(
            base.conversationId,
            userAlternatives[0].assistantMessageId,
        )
        val nested = repository.createAlternative(
            base.conversationId,
            AlternativeRequest.Retry(userAlternatives[0].assistantMessageId),
            ChatModel.SOL,
            "",
            nestedSource.expectedPathIds,
        )
        repository.completeTurn(nested.assistantMessageId, "nested response", null)
        repository.selectAlternative(base.conversationId, userAlternatives[0].assistantMessageId)
        repository.selectAlternative(base.conversationId, base.original.userMessageId)
        BranchScenario(
            database = base.database,
            repository = repository,
            viewModel = base.viewModel,
            conversationId = base.conversationId,
            original = base.original,
            userAlternatives = userAlternatives,
            responseAlternatives = responseAlternatives,
            nestedAlternative = nested,
        )
    }

    private fun seedFailedResponseScenario(): BranchScenario = runBlocking {
        val base = seedBranchScenario()
        val repository = base.repository
        repository.selectAlternative(base.conversationId, base.original.assistantMessageId)
        val source = repository.alternativeSource(base.conversationId, base.original.assistantMessageId)
        val failed = repository.createAlternative(
            base.conversationId,
            AlternativeRequest.Retry(base.original.assistantMessageId),
            ChatModel.SOL,
            "",
            source.expectedPathIds,
        )
        repository.failZdrTurn(failed.assistantMessageId)
        base.copy(failedResponse = failed)
    }

    private fun testViewModel(
        repository: ChatRepository,
        operations: RepositoryAttachmentOperations = RepositoryAttachmentOperations(repository),
    ): AppViewModel = AppViewModel(
        secrets = ConfiguredKeys(),
        settings = TestSettings(),
        chats = RepositoryChatStore(repository),
        streamSource = StreamSource { _, _, _, _ -> flowOf(StreamEvent.Done) },
        modelCatalogue = TestCatalogue(),
        quota = UnavailableQuota(),
        attachmentOperations = operations,
        systemPromptStore = TestSystemPromptStore(),
        contextWindowLoader = { ContextWindowCatalogue() },
    )

    private fun testImage(id: String) = ImageAttachment(
        id = id,
        displayName = "$id.png",
        mimeType = "image/png",
        originalBytes = 4,
        encryptedBytes = 37,
        width = 2,
        height = 2,
    )

    private data class Scenario(
        val database: ChatDatabase,
        val repository: ChatRepository,
        val viewModel: AppViewModel,
        val conversationId: String,
        val original: PendingTurn,
        val operations: RepositoryAttachmentOperations,
        val another: PendingTurn? = null,
    )

    private data class BranchScenario(
        val database: ChatDatabase,
        val repository: ChatRepository,
        val viewModel: AppViewModel,
        val conversationId: String,
        val original: PendingTurn,
        val userAlternatives: List<PendingTurn>,
        val responseAlternatives: List<PendingTurn>,
        val nestedAlternative: PendingTurn,
        val failedResponse: PendingTurn? = null,
    )

    private class ConfiguredKeys : ApiKeyStore {
        override fun saveApiKey(value: CharArray) = Unit
        override fun readApiKey(): CharArray = "offline-test-key".toCharArray()
        override fun clearApiKey() = Unit
    }

    private class TestSettings : SettingsStore {
        override var zdr = true
    }

    private class TestSystemPromptStore : SystemPromptStore {
        override fun read(): String = ""
        override fun save(value: String) = Unit
        override fun clear() = Unit
    }

    private class TestCatalogue : ModelCatalogueSource {
        private val snapshot = ModelCatalogueSnapshot(1, "test", 1L, listOf(ChatModel.SOL))
        override suspend fun loadLocal(): ModelCatalogueSnapshot = snapshot
        override suspend fun refresh(): ModelCatalogueSnapshot = snapshot
    }

    private class UnavailableQuota : QuotaSource {
        override suspend fun loadCached(): QuotaSnapshot? = null
        override suspend fun refresh(apiKey: CharArray): QuotaSnapshot = error("offline test")
        override suspend fun clear() = Unit
    }

    private class RepositoryChatStore(private val repository: ChatRepository) : ChatStore {
        override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()
        override fun observeMessages(conversationId: String): Flow<List<Message>> = repository.observeMessages(conversationId)
        override fun observeTokenUsage(conversationId: String) = repository.observeTokenUsage(conversationId)
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = repository.messagesSnapshot(conversationId)
        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
            systemPrompt: String,
        ): PendingTurn = repository.beginTurn(conversationId, text, model, attachments, systemPrompt)
        override suspend fun checkpointAssistant(messageId: String, text: String) = repository.checkpointAssistant(messageId, text)
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean =
            repository.completeTurn(messageId, text, usage)
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String, usage: TokenUsage?) =
            repository.interruptTurn(messageId, partialText, reason, usage)
        override suspend fun deleteConversation(id: String) = repository.deleteConversation(id)
        override suspend fun alternativeSource(conversationId: String, messageId: String) =
            repository.alternativeSource(conversationId, messageId)
        override suspend fun createAlternative(
            conversationId: String,
            request: AlternativeRequest,
            model: ChatModel,
            systemPrompt: String,
            expectedPathIds: List<String>,
        ) = repository.createAlternative(conversationId, request, model, systemPrompt, expectedPathIds)
        override suspend fun selectAlternative(conversationId: String, messageId: String) =
            repository.selectAlternative(conversationId, messageId)
        override suspend fun failZdrTurn(assistantMessageId: String, usage: TokenUsage?) =
            repository.failZdrTurn(assistantMessageId, usage)
        override suspend fun retryFailedTurn(userMessageId: String, model: ChatModel, systemPrompt: String) =
            repository.retryFailedTurn(userMessageId, model, systemPrompt)
        override suspend fun deleteFailedTurn(userMessageId: String) = repository.deleteFailedTurn(userMessageId)
        override suspend fun changeConversationModel(conversationId: String, model: ChatModel) =
            repository.changeConversationModel(conversationId, model)
        override suspend fun backfillInitialModel(conversationId: String) = repository.backfillInitialModel(conversationId)
    }

    private class RepositoryAttachmentOperations(private val repository: ChatRepository) : AttachmentOperations {
        var prepareEntered: CompletableDeferred<Unit>? = null
        var prepareRelease: CompletableDeferred<Unit>? = null
        var deleteEntered: CompletableDeferred<Unit>? = null
        var deleteRelease: CompletableDeferred<Unit>? = null

        override suspend fun initialise() = Unit
        override suspend fun importImage(uri: Uri, modelId: String): ImageAttachment = ImageAttachment(
            id = "pending",
            displayName = "pending.png",
            mimeType = "image/png",
            originalBytes = 4,
            encryptedBytes = 37,
            width = 2,
            height = 2,
        )
        override suspend fun discard(images: List<ImageAttachment>) = Unit
        override suspend fun prepareTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            images: List<ImageAttachment>,
            systemPrompt: String,
        ): PreparedAttachmentTurn {
            val pending = repository.beginTurn(conversationId, text, model, images, systemPrompt)
            return PreparedAttachmentTurn(pending, listOf(ApiMessage("user", text)))
        }
        override suspend fun prepareAlternative(
            conversationId: String,
            request: AlternativeRequest,
            model: ChatModel,
            systemPrompt: String,
        ): PreparedAttachmentTurn {
            val source = repository.alternativeSource(conversationId, request.messageId)
            val pending = repository.createAlternative(
                conversationId,
                request,
                model,
                systemPrompt,
                source.expectedPathIds,
            )
            prepareEntered?.complete(Unit)
            prepareRelease?.await()
            val userText = when (request) {
                is AlternativeRequest.Edit -> request.text
                is AlternativeRequest.Retry -> source.prefix.last { it.role == "USER" }.content
            }
            return PreparedAttachmentTurn(pending, listOf(ApiMessage("user", userText)))
        }
        override suspend fun deleteConversation(id: String) {
            repository.deleteConversation(id)
            deleteEntered?.complete(Unit)
            deleteRelease?.await()
        }
        override suspend fun preview(id: String, maxEdge: Int): Bitmap = error("not expected")
        override suspend fun prepareRetry(
            conversationId: String,
            userMessageId: String,
            model: ChatModel,
            systemPrompt: String,
        ): PreparedAttachmentTurn {
            val pending = repository.retryFailedTurn(userMessageId, model, systemPrompt)
            return PreparedAttachmentTurn(pending, listOf(ApiMessage("user", "retry")))
        }
        override suspend fun deleteFailedMessage(conversationId: String, userMessageId: String) =
            repository.deleteFailedTurn(userMessageId)
    }
}
