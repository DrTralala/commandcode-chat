package com.commandcode.chat

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.attachments.AttachmentOperations
import com.commandcode.chat.data.attachments.PreparedAttachmentTurn
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.security.SystemPromptStore
import com.commandcode.chat.data.service.ModelCatalogueSnapshot
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.ui.AppRoot
import com.commandcode.chat.ui.AppViewModel
import com.commandcode.chat.data.security.KeyRecoveryRequired
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSettings() {
        compose.activity.viewModelForTests.clearApiKey()
        compose.activity.appContainer.settings.resetForTests()
        compose.activity.viewModelForTests.setZdr(true)
        compose.activity.viewModelForTests.setAmoled(false)
        compose.activity.recreateUiForTests()
        compose.waitForIdle()
    }

    @After
    fun clearKey() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CommandCodeApplication
        application.appContainer?.secretRepository?.clearApiKey()
    }

    @Test
    fun firstLaunchSettingsNavigationModelsValidationAndRecreation() {
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithTag("zdr_toggle").assertIsOn()
        compose.onNodeWithContentDescription("Zero data retention (ZDR)").assertIsDisplayed()
        compose.onAllNodesWithTag("billing_day").assertCountEquals(0)

        compose.onNodeWithTag("api_key").performTextInput("fake-offline-key")
        compose.onNodeWithTag("save_api_key").performClick()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithText("New Chat").assertIsDisplayed()
        compose.onNodeWithTag("nav_budget").performClick()
        compose.onNodeWithText("Budget unavailable").assertIsDisplayed()
        compose.onNodeWithTag("budget_retry").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithTag("nav_chat").performClick()
        compose.onNodeWithTag("model_selector").performClick()
        compose.onAllNodes(SemanticsMatcher("actual model options") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("model_option_")
        }).assertCountEquals(44)
        compose.onNodeWithTag("model_option_Qwen_Qwen3_8_Flash").assertIsDisplayed().performClick()
        compose.onNodeWithTag("model_selector")
            .assertContentDescriptionEquals("Select chat model. Current: Qwen 3.8 Flash")
        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag("model_option_gpt_5_6_sol").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("model_selector")
            .assertContentDescriptionEquals("Select chat model. Current: GPT-5.6 Sol")

        val seeded = runBlocking {
            compose.activity.appContainer.chatRepository.beginTurn(null, "delete me offline", ChatModel.SOL).also {
                compose.activity.appContainer.chatRepository.interruptTurn(it.assistantMessageId, "partial", "test setup")
            }
        }
        val retiredModelId = "retired/provider-model"
        val retired = runBlocking {
            val retiredModel = ChatModel(retiredModelId, "No longer catalogued", ChatModel.SOL.apiFamily)
            compose.activity.appContainer.chatRepository.beginTurn(null, "historical model", retiredModel).also {
                compose.activity.appContainer.chatRepository.interruptTurn(it.assistantMessageId, "partial", "test setup")
            }
        }

        compose.onNodeWithTag("nav_history").performClick()
        compose.onNodeWithTag("history_screen").assertIsDisplayed()
        compose.onNodeWithText("GPT-5.6 Sol").assertIsDisplayed()
        compose.onNodeWithText(retiredModelId).assertIsDisplayed()
        compose.onNodeWithTag("delete_conversation_${retired.conversationId}").performClick()
        compose.onNodeWithTag("confirm_delete_conversation").performClick()
        compose.onNodeWithTag("delete_conversation_${seeded.conversationId}").performClick()
        compose.onNodeWithText("Delete conversation?").assertIsDisplayed()
        compose.onNodeWithTag("confirm_delete_conversation").performClick()
        compose.waitUntil {
            compose.onAllNodesWithText("delete me offline").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("nav_budget").performClick()
        compose.onNodeWithText("Budget unavailable").assertIsDisplayed()
        compose.onNodeWithTag("budget_retry").assertIsDisplayed()
        compose.onAllNodesWithText("Estimated from this app").assertCountEquals(0)
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText("API key: configured").assertIsDisplayed()
        compose.onAllNodesWithTag("api_key").assertCountEquals(0)
        compose.onAllNodesWithTag("save_api_key").assertCountEquals(0)
        compose.onAllNodesWithTag("billing_day").assertCountEquals(0)
        compose.onNodeWithContentDescription("Zero data retention (ZDR)").performClick().assertIsOff()
        compose.onNodeWithTag("security_zdr_status").assertTextEquals("ZDR OFF")
        compose.onNodeWithContentDescription("AMOLED dark mode").performClick().assertIsOn()

        compose.onNodeWithTag("nav_budget").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithText("Budget unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Estimated from this app").assertCountEquals(0)
        compose.onAllNodesWithText("Add your API key").assertCountEquals(0)
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("amoled_toggle").assertIsOn()

        compose.activityRule.scenario.close()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil { compose.onAllNodesWithText("New Chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("New Chat").assertIsDisplayed()
            compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        }
    }

    @Test
    fun appContainerInitialisesAttachmentStorageAtStartup() {
        compose.waitUntil(timeoutMillis = 5_000) {
            !compose.activity.viewModelForTests.state.value.loading
        }

        assertTrue(!compose.activity.viewModelForTests.state.value.loading)
        assertTrue(compose.activity.viewModelForTests.state.value.attachmentError == null)
        assertTrue(
            File(compose.activity.noBackupFilesDir.canonicalFile, "attachments").isDirectory,
        )
    }

    @Test
    fun rootInsetsKeepHeadingBelowStatusBarInset() {
        val expectedBoundary = expectedInsetBoundary(includeIme = false)
        val insetContent = appInsetContentBounds()

        assertRectMatches("app_inset_content", insetContent, expectedBoundary)
        assertNodeWithinBoundary("Settings", insetContent)
    }

    @Test
    fun composerAndBottomNavigationRemainVisibleWithImeOpen() {
        compose.onNodeWithTag("api_key").performTextInput("fake-offline-key")
        compose.onNodeWithTag("save_api_key").performClick()
        compose.onNodeWithTag("message_composer").performClick()
        compose.activityRule.scenario.onActivity { activity ->
            activity.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(activity.currentFocus, InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }

        val expectedBoundary = expectedInsetBoundary(includeIme = true)
        val insetContent = appInsetContentBounds()
        assertRectMatches("app_inset_content", insetContent, expectedBoundary)
        assertNodeWithinBoundary("message_composer", insetContent)
        assertNodeWithinBoundary("bottom_navigation", insetContent)
    }

    private fun appInsetContentBounds(): RectF {
        val bounds = compose.onNodeWithTag("app_inset_content").fetchSemanticsNode().boundsInRoot
        return RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun expectedInsetBoundary(includeIme: Boolean): RectF {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val rootWindowInsets = checkNotNull(compose.activity.window.decorView.rootWindowInsets) {
            "rootWindowInsets unavailable after Compose has settled"
        }
        val safeInsets = rootWindowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val imeBottom = if (includeIme) {
            rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            0
        }
        return RectF(
            root.left + safeInsets.left,
            root.top + safeInsets.top,
            root.right - safeInsets.right,
            root.bottom - maxOf(safeInsets.bottom, imeBottom),
        )
    }

    private fun assertRectMatches(label: String, actual: RectF, expected: RectF) {
        val tolerance = 1f
        assertTrue(
            "$label bounds=$actual expected=$expected",
            kotlin.math.abs(actual.left - expected.left) <= tolerance &&
                kotlin.math.abs(actual.top - expected.top) <= tolerance &&
                kotlin.math.abs(actual.right - expected.right) <= tolerance &&
                kotlin.math.abs(actual.bottom - expected.bottom) <= tolerance,
        )
    }

    private fun assertNodeWithinBoundary(label: String, boundary: RectF) {
        val node = if (label == "Settings") {
            compose.onNodeWithText(label).fetchSemanticsNode()
        } else {
            compose.onNodeWithTag(label).fetchSemanticsNode()
        }
        val bounds = node.boundsInRoot
        assertTrue(
            "$label bounds=$bounds boundary=$boundary",
            bounds.left >= boundary.left &&
                bounds.top >= boundary.top &&
                bounds.right <= boundary.right &&
                bounds.bottom <= boundary.bottom,
        )
    }

    @Test
    fun startupRecoveryFromFailingSecretStoreIsBlockingAndNonDestructive() {
        val secrets = FailingStartupSecrets()
        val chats = MutationRecordingChats()
        val viewModel = AppViewModel(
            secrets = secrets,
            settings = TestSettings(),
            chats = chats,
            streamSource = StreamSource { _: CharArray, _: ChatModel, _: List<ApiMessage>, _: Boolean -> emptyFlow() },
            modelCatalogue = EmptyCatalogue(),
            quota = EmptyQuota(),
            attachmentOperations = NoopAttachmentOperations(),
            systemPromptStore = TestSystemPromptStore(),
        )
        compose.activity.setContent { AppRoot(viewModel = viewModel) }

        compose.onNodeWithTag("recovery_screen").assertIsDisplayed()
        compose.onNodeWithText("Recovery required").assertIsDisplayed()
        compose.onNodeWithText("No encrypted data has been deleted.").assertIsDisplayed()
        compose.onNodeWithText("Unlock unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
        assertEquals(1, secrets.readCalls)
        assertEquals(0, secrets.mutationCalls)
        assertEquals(0, chats.mutationCalls)
    }

    @Test
    fun injectedRootSendsThroughOfflineStreamWithoutCallingAProvider() {
        val chats = OfflineChatStore()
        val streamedContexts = mutableListOf<List<ApiMessage>>()
        val viewModel = AppViewModel(
            secrets = ConfiguredKeys(),
            settings = TestSettings(),
            chats = chats,
            streamSource = StreamSource { _, _, messages, _ ->
                streamedContexts += messages
                flowOf(StreamEvent.Delta("offline response"), StreamEvent.Done)
            },
            modelCatalogue = EmptyCatalogue(),
            quota = EmptyQuota(),
            attachmentOperations = OfflineAttachmentOperations(chats),
            systemPromptStore = TestSystemPromptStore(),
        )
        compose.activity.setContent { AppRoot(viewModel = viewModel) }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("New Chat").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("message_composer").performTextInput("offline prompt")
        compose.onNodeWithTag("send_message").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("offline response").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(1, streamedContexts.size)
        assertEquals("offline prompt", streamedContexts.single().single().content)
        assertEquals("offline response", chats.completedText)
        assertTrue(viewModel.state.value.errorMessage == null)
    }

    private class FailingStartupSecrets : ApiKeyStore {
        var readCalls = 0
        var mutationCalls = 0
        override fun readApiKey(): CharArray? {
            readCalls += 1
            throw KeyRecoveryRequired()
        }
        override fun saveApiKey(value: CharArray) { mutationCalls += 1 }
        override fun clearApiKey() { mutationCalls += 1 }
    }

    private class TestSettings : SettingsStore {
        override var zdr = true
        override var amoled = false
    }

    private class ConfiguredKeys : ApiKeyStore {
        override fun saveApiKey(value: CharArray) = Unit
        override fun readApiKey(): CharArray = "offline-test-key".toCharArray()
        override fun clearApiKey() = Unit
    }

    private class TestSystemPromptStore : SystemPromptStore {
        override fun read(): String = ""
        override fun save(value: String) = Unit
        override fun clear() = Unit
    }

    private class NoopAttachmentOperations : AttachmentOperations {
        override suspend fun initialise() = Unit
        override suspend fun importImage(uri: Uri, modelId: String): ImageAttachment =
            error("not expected")
        override suspend fun discard(images: List<ImageAttachment>) = Unit
        override suspend fun prepareTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            images: List<ImageAttachment>,
            systemPrompt: String,
        ): PreparedAttachmentTurn = error("not expected")
        override suspend fun deleteConversation(id: String) = Unit
        override suspend fun preview(id: String, maxEdge: Int): Bitmap = error("not expected")
    }

    private class OfflineAttachmentOperations(private val chats: ChatStore) : AttachmentOperations {
        override suspend fun initialise() = Unit
        override suspend fun importImage(uri: Uri, modelId: String): ImageAttachment = error("not expected")
        override suspend fun discard(images: List<ImageAttachment>) = Unit
        override suspend fun prepareTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            images: List<ImageAttachment>,
            systemPrompt: String,
        ): PreparedAttachmentTurn = PreparedAttachmentTurn(
            pending = chats.beginTurn(conversationId, text, model, images, systemPrompt),
            messages = listOf(ApiMessage("user", text)),
        )
        override suspend fun deleteConversation(id: String) = Unit
        override suspend fun preview(id: String, maxEdge: Int): Bitmap = error("not expected")
    }

    private class MutationRecordingChats : ChatStore {
        var mutationCalls = 0
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = emptyList()
        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
            systemPrompt: String,
        ): PendingTurn {
            mutationCalls += 1
            error("not expected")
        }
        override suspend fun checkpointAssistant(messageId: String, text: String) { mutationCalls += 1 }
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean {
            mutationCalls += 1
            return false
        }
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) { mutationCalls += 1 }
        override suspend fun deleteConversation(id: String) { mutationCalls += 1 }
        override suspend fun backfillInitialModel(conversationId: String) = Unit
    }

    private class OfflineChatStore : ChatStore {
        private val conversations = MutableStateFlow<List<Conversation>>(emptyList())
        private val messages = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        var completedText: String? = null

        override fun observeConversations(): Flow<List<Conversation>> = conversations

        override fun observeMessages(conversationId: String): Flow<List<Message>> =
            messages[conversationId] ?: MutableStateFlow(emptyList())

        override suspend fun messagesSnapshot(conversationId: String): List<Message> =
            messages[conversationId]?.value.orEmpty()

        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
            systemPrompt: String,
        ): PendingTurn {
            val id = conversationId ?: "offline-conversation"
            val flow = messages.getOrPut(id) { MutableStateFlow(emptyList()) }
            if (conversationId == null) {
                conversations.value = listOf(Conversation(id, text, model.apiId, 1L, 2L))
            }
            val user = Message("offline-user-${flow.value.size}", id, "USER", text, null, flow.value.size.toLong() + 1, "COMPLETE", attachments)
            val assistant = Message("offline-assistant-${flow.value.size}", id, "ASSISTANT", "", model.apiId, flow.value.size.toLong() + 2, "PENDING")
            flow.value += user
            flow.value += assistant
            return PendingTurn(id, user.id, assistant.id)
        }

        override suspend fun checkpointAssistant(messageId: String, text: String) = Unit

        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean {
            completedText = text
            messages.values.forEach { flow ->
                flow.value = flow.value.map { message ->
                    if (message.id == messageId) message.copy(content = text, status = "COMPLETE") else message
                }
            }
            return true
        }

        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) = Unit

        override suspend fun deleteConversation(id: String) = Unit

        override suspend fun backfillInitialModel(conversationId: String) = Unit
    }

    private class EmptyCatalogue : ModelCatalogueSource {
        private val snapshot = ModelCatalogueSnapshot(1, "test", 1L, listOf(ChatModel.SOL))
        override suspend fun loadLocal(): ModelCatalogueSnapshot = snapshot
        override suspend fun refresh(): ModelCatalogueSnapshot = snapshot
    }

    private class EmptyQuota : QuotaSource {
        override suspend fun loadCached(): QuotaSnapshot? = null
        override suspend fun refresh(apiKey: CharArray): QuotaSnapshot = error("not expected")
        override suspend fun clear() = Unit
    }
}
