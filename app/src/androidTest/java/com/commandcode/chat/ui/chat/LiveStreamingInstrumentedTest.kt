package com.commandcode.chat.ui.chat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.MainActivity
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.ui.AppRoot
import com.commandcode.chat.ui.AppViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.onEach
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
        val vm = AppViewModel(
            secrets = object : ApiKeyStore {
                override fun readApiKey() = key.copyOf()
                override fun saveApiKey(value: CharArray) = error("not used")
                override fun clearApiKey() = Unit
            },
            settings = container.settings,
            chats = container.chatStore,
            streamSource = StreamSource { credential, model, messages, zdr ->
                container.commandCodeClient.stream(credential, model, messages, zdr).onEach {
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
            compose.waitUntil(10_000) {
                createdConversation = vm.state.value.currentConversationId
                compose.onAllNodesWithTag("thinking_indicator").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("thinking_indicator").assertIsDisplayed()
            compose.waitUntil(90_000) { deltas.get() >= 2 || !vm.state.value.sending }
            assertTrue("Gateway must deliver multiple text deltas", deltas.get() >= 2)
            assertTrue("Partial answer must be visible before completion", vm.state.value.sending)
            compose.onNodeWithTag("active_assistant_response").assertIsDisplayed()
            compose.onNodeWithTag("thinking_indicator").assertDoesNotExist()
            compose.waitUntil(90_000) { !vm.state.value.sending }
            assertTrue("Live send must finish without an error", vm.state.value.errorMessage == null)
            compose.waitUntil(5_000) {
                vm.state.value.visibleMessages.any { it.role == "ASSISTANT" && it.content.contains("120") }
            }
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
