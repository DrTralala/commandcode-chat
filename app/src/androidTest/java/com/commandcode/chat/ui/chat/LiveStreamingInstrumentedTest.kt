package com.commandcode.chat.ui.chat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.MainActivity
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.commandcode.CommandCodeException
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.ui.AppRoot
import com.commandcode.chat.ui.AppViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in device check: supply a one-use cache/chat-polish-live-key file via run-as.
 * The key is never saved to settings, printed or included in test arguments/results.
 */
class LiveStreamingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realGatewayStreamsIntoTheComposerScreenBeforeCompletion() {
        val keyFile = File(compose.activity.cacheDir, "chat-polish-live-key")
        assumeTrue("Live credential was not supplied", keyFile.isFile)
        val key = keyFile.readText().trim().toCharArray()
        check(keyFile.delete())
        val container = compose.activity.appContainer
        val deltas = AtomicInteger()
        val attempts = AtomicInteger()
        val exerciseRecovery = InstrumentationRegistry.getArguments().getString("exerciseRecovery") == "true"
        val vm = AppViewModel(
            secrets = object : ApiKeyStore {
                override fun readApiKey() = key.copyOf()
                override fun saveApiKey(value: CharArray) = error("not used")
                override fun clearApiKey() = Unit
            },
            settings = container.settings,
            chats = container.chatStore,
            streamSource = StreamSource { credential, model, messages, zdr ->
                if (exerciseRecovery && attempts.getAndIncrement() == 0) flow { throw CommandCodeException.ZdrUnavailable() }
                else container.commandCodeClient.stream(credential, model, messages, zdr).onEach {
                    if (it is StreamEvent.Delta) deltas.incrementAndGet()
                }
            },
            modelCatalogue = container.modelCatalogue,
            quota = container.quota,
            attachmentOperations = container.attachmentOperations,
        )
        var createdConversation: String? = null
        try {
            compose.activity.setContent { AppRoot(viewModel = vm) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("message_composer").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("message_composer").performTextInput(
                "For a streaming UI test, list the integers 1 to 120, one per line. No introduction.",
            )
            compose.onNodeWithTag("send_message").performClick()
            if (exerciseRecovery) {
                compose.waitUntil(10_000) { vm.state.value.messages.any { it.role == "USER" && it.status == "FAILED_ZDR" } }
                createdConversation = vm.state.value.currentConversationId
                val failedUser = vm.state.value.messages.single { it.role == "USER" }.id
                compose.onNodeWithTag("failed_message_$failedUser").assertIsDisplayed()
                val currentModel = vm.state.value.models.single { it.apiId == ChatModel.LUNA.apiId }
                compose.runOnIdle { vm.selectModel(currentModel) }
                compose.waitUntil(10_000) {
                    vm.state.value.selectedModel.apiId == currentModel.apiId &&
                        vm.state.value.messages.any { it.role == "MODEL_CHANGE" && it.modelId == currentModel.apiId }
                }
                compose.onNodeWithText("Model changed to ${currentModel.displayName}").assertIsDisplayed()
                compose.onNodeWithTag("message_$failedUser").performTouchInput { longClick() }
                compose.onNodeWithText("Retry").performClick()
            }
            compose.waitUntil(10_000) {
                createdConversation = vm.state.value.currentConversationId
                compose.onAllNodesWithTag("thinking_indicator").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("thinking_indicator").assertIsDisplayed()
            compose.waitUntil(90_000) { deltas.get() >= 2 || !vm.state.value.sending }
            if (exerciseRecovery && deltas.get() == 0 && vm.state.value.errorMessage == "No ZDR-capable provider is available") {
                compose.waitUntil(10_000) { vm.state.value.messages.any { it.role == "USER" && it.status == "FAILED_ZDR" } }
                val user = vm.state.value.messages.single { it.role == "USER" }
                assertTrue("Real routing failure must persist on the original user", user.status == "FAILED_ZDR")
                compose.onNodeWithTag("failed_message_${user.id}").assertIsDisplayed()
                compose.onNodeWithTag("message_${user.id}").performTouchInput { longClick() }
                if (InstrumentationRegistry.getArguments().getString("captureRecoveryEvidence") == "true") {
                    val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    try {
                        File(compose.activity.cacheDir, "recovery-zdr-failure.png").outputStream().use {
                            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { screenshot.recycle() }
                }
                // Dismiss the menu, explicitly select another model, and retry without relaxing ZDR.
                compose.onNodeWithText("Retry").assertIsDisplayed()
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                )
                val sol = vm.state.value.models.single { it.apiId == ChatModel.SOL.apiId }
                compose.runOnIdle { vm.selectModel(sol) }
                compose.waitUntil(10_000) {
                    vm.state.value.selectedModel.apiId == sol.apiId &&
                        vm.state.value.messages.lastOrNull()?.modelId == sol.apiId
                }
                compose.onNodeWithTag("message_${user.id}").performTouchInput { longClick() }
                compose.onNodeWithText("Retry").performClick()
                compose.waitUntil(10_000) { vm.state.value.sending }
                compose.waitUntil(90_000) { deltas.get() >= 2 || !vm.state.value.sending }
                assertTrue("Recovery must not disable ZDR", vm.state.value.zdr)
            }
            assertTrue("Gateway must deliver multiple text deltas; safe UI error: ${vm.state.value.errorMessage}", deltas.get() >= 2)
            assertTrue("Partial answer must be visible before completion", vm.state.value.sending)
            compose.onNodeWithTag("active_assistant_response").assertIsDisplayed()
            compose.onNodeWithTag("thinking_indicator").assertDoesNotExist()
            compose.waitUntil(90_000) { !vm.state.value.sending }
            assertTrue("Live send must finish without an error", vm.state.value.errorMessage == null)
            compose.waitUntil(5_000) {
                vm.state.value.visibleMessages.any { it.role == "ASSISTANT" && it.content.contains("120") }
            }
            assertTrue("Retry must not duplicate the outgoing message", vm.state.value.messages.count { it.role == "USER" } == 1)
        } finally {
            key.fill('\u0000')
            compose.runOnIdle { vm.cancel() }
            createdConversation?.let { id ->
                compose.runOnIdle { vm.deleteConversation(id) }
                compose.waitUntil(10_000) { vm.state.value.conversations.none { it.id == id } }
            }
        }
    }
}
