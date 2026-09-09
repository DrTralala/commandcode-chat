package com.commandcode.chat.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.ChatStore
import com.commandcode.chat.MainActivity
import com.commandcode.chat.SettingsStore
import com.commandcode.chat.StreamSource
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
import com.commandcode.chat.ui.attachmentPickerMimeTypes
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentPickerInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionPickerMimeTypesCreateWildcardMultipleDocumentIntentWithoutPersistableGrant() {
        val mimeTypes = attachmentPickerMimeTypes(ChatModel.SOL.apiId)
        assertArrayEquals(
            arrayOf("image/jpeg", "image/png", "image/webp"),
            mimeTypes,
        )

        val intent = ActivityResultContracts.OpenMultipleDocuments().createIntent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            mimeTypes,
        )

        assertEquals("*/*", intent.type)
        assertArrayEquals(mimeTypes, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    @Test
    fun unverifiedPolicyProducesNoPickerMimeTypesForTheRootGuard() {
        assertEquals(0, attachmentPickerMimeTypes("unverified/model").size)
        val ticket = PickerTicket(generation = 4L, conversationId = "conversation", modelId = "unverified/model")
        assertEquals(4L, ticket.generation)
        assertEquals("conversation", ticket.conversationId)
    }

    @Test
    fun actualRootShowsUnverifiedCopyAndDoesNotLaunchPicker() {
        val harness = setRoot()

        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag(modelOptionTestTag(harness.unverified.apiId)).performClick()
        compose.onNodeWithTag("attach_image").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("attachment_error_box").assertIsDisplayed()
        compose.onNodeWithText("This model does not support attachments").assertIsDisplayed()
        compose.onNodeWithText("Attachment support for this model has not been verified.").assertDoesNotExist()
        assertEquals(0, harness.registry.launches.size)
    }

    @Test
    fun actualRootLaunchesSupportedPickerWithExactMimeContract() {
        val harness = setRoot()

        compose.onNodeWithTag("attach_image").performClick()
        compose.waitForIdle()

        val launch = harness.registry.launches.single()
        assertEquals("*/*", launch.intent.type)
        assertArrayEquals(
            arrayOf("image/jpeg", "image/png", "image/webp"),
            launch.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertTrue(launch.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(0, launch.intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    @Test
    fun actualRootRejectsResultForStalePickerTicketBeforeImport() {
        val harness = setRoot()

        compose.onNodeWithTag("attach_image").performClick()
        compose.waitForIdle()
        val launch = harness.registry.launches.single()

        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag(modelOptionTestTag(harness.unverified.apiId)).performClick()
        compose.runOnIdle {
            assertTrue(harness.registry.dispatchResult(launch.requestCode, listOf(Uri.parse("content://stale"))))
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("attachment_error_box").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("This attachment selection is no longer current. Try again.").assertIsDisplayed()
        assertEquals(0, harness.operations.importCalls)
    }

    @Test
    fun actualRootClearsCancelledPickerTicketRetainsDraftAndAllowsSecondLaunch() {
        val harness = setRoot()

        compose.onNodeWithTag("message_composer").performTextInput("retain this draft")
        compose.onNodeWithTag("attach_image").performClick()
        compose.waitForIdle()
        val launch = harness.registry.launches.single()
        compose.runOnIdle {
            assertTrue(harness.registry.dispatchResult(launch.requestCode, emptyList<Uri>()))
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag("attachment_error_box").assertCountEquals(0)
        compose.onNodeWithTag("message_composer").assert(
            SemanticsMatcher("cancelled picker retains editable text") { node ->
                node.config[SemanticsProperties.EditableText].text == "retain this draft"
            },
        )
        compose.onNodeWithTag("attach_image").performClick()
        compose.waitForIdle()
        assertEquals(2, harness.registry.launches.size)
        assertEquals(0, harness.operations.importCalls)
    }

    @Test
    fun manyAttachmentsStayAboveTheComposerWithTheRealKeyboardOpen() {
        val harness = setRoot(allowImports = true)
        compose.onNodeWithTag("attach_image").performClick()
        val launch = harness.registry.launches.single()
        compose.runOnIdle {
            harness.registry.dispatchResult(launch.requestCode, (1..20).map { Uri.parse("content://fixture/$it") })
        }
        compose.waitUntil(5_000) {
            harness.operations.importCalls == 20 &&
                !compose.onNodeWithTag("message_composer").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("message_composer").performClick().performTextInput("Line one\nLine two\nLine three\nLine four")
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("message_composer").assertIsDisplayed()
        compose.onNodeWithTag("send_message").assertIsDisplayed()
        compose.onNodeWithTag("pending_attachments").performScrollToIndex(19)
        compose.onNodeWithTag("remove_attachment_20").assertIsDisplayed()
        val composerContainer = compose.onNodeWithTag("composer_container").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("message_composer").fetchSemanticsNode().boundsInRoot
        val attachments = compose.onNodeWithTag("pending_attachments").fetchSemanticsNode().boundsInRoot
        assertTrue("attachments must remain above input", attachments.bottom <= input.top)
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val visibleFrame = android.graphics.Rect()
            decor.getWindowVisibleDisplayFrame(visibleFrame)
            val composeHost = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val location = IntArray(2)
            composeHost.getLocationOnScreen(location)
            val gap = visibleFrame.bottom - (location[1] + composerContainer.bottom)
            val maxGap = 24f * compose.activity.resources.displayMetrics.density
            assertTrue("actual composer-to-keyboard gap must be 0..${maxGap}px, was $gap", gap in 0f..maxGap)
        }
        if (InstrumentationRegistry.getArguments().getString("capturePolishEvidence") == "true") {
            val landscape = compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                java.io.File(compose.activity.cacheDir, "chat-polish-${if (landscape) "landscape" else "portrait"}.png")
                    .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                screenshot.recycle()
            }
        }
    }

    @Test
    fun keyboardLeavesComposerDirectlyAboveImeAndHidesBottomNavigation() {
        setRoot()
        compose.onNodeWithTag("message_composer").performClick().performTextInput("Line one\nLine two")
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("security_zdr_status").assertDoesNotExist()
        val input = compose.onNodeWithTag("message_composer").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("send_message").fetchSemanticsNode().boundsInRoot
        val container = compose.onNodeWithTag("composer_container").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("app_inset_content").fetchSemanticsNode().boundsInRoot
        assertTrue("send must sit below the editable text", send.center.y >= input.bottom)
        assertTrue("send must stay inside the composer", send.bottom <= container.bottom + 1f)
        assertTrue("composer must be at the bottom of inset content: $input $root", root.bottom - container.bottom < 80f)
        compose.runOnIdle {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
        }
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithTag("security_zdr_status").assertIsDisplayed()
    }

    private data class RootHarness(
        val registry: RecordingActivityResultRegistry,
        val operations: RecordingAttachmentOperations,
        val unverified: ChatModel,
    )

    private fun setRoot(allowImports: Boolean = false): RootHarness {
        val registry = RecordingActivityResultRegistry(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val operations = RecordingAttachmentOperations(allowImports)
        val unverified = ChatModel("unverified/model", "Unverified model", ChatModel.SOL.apiFamily)
        val catalogue = TestCatalogue(unverified)
        val viewModel = AppViewModel(
            secrets = TestKeys(),
            settings = TestSettings(),
            chats = EmptyChats(),
            streamSource = StreamSource { _, _, _, _ -> emptyFlow<StreamEvent>() },
            modelCatalogue = catalogue,
            quota = TestQuota(),
            attachmentOperations = operations,
            systemPromptStore = TestSystemPromptStore(),
        )
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        compose.activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AppRoot(viewModel = viewModel)
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("attach_image").fetchSemanticsNodes().isNotEmpty()
        }
        return RootHarness(registry, operations, unverified)
    }

    private data class Launch(val requestCode: Int, val intent: Intent)

    private class RecordingActivityResultRegistry(private val context: Context) : ActivityResultRegistry() {
        val launches = mutableListOf<Launch>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += Launch(requestCode, contract.createIntent(context, input))
        }
    }

    private class TestKeys : ApiKeyStore {
        override fun saveApiKey(value: CharArray) = Unit
        override fun readApiKey(): CharArray = "configured".toCharArray()
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

    private class EmptyChats : ChatStore {
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = emptyList()
        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
            systemPrompt: String,
        ): PendingTurn = error("not expected")
        override suspend fun checkpointAssistant(messageId: String, text: String) = Unit
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean = false
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String, usage: TokenUsage?) = Unit
        override suspend fun deleteConversation(id: String) = Unit
        override suspend fun backfillInitialModel(conversationId: String) = Unit
    }

    private class TestCatalogue(private val unverified: ChatModel) : ModelCatalogueSource {
        private val snapshot = ModelCatalogueSnapshot(
            schemaVersion = 1,
            catalogueVersion = "test",
            generatedAt = 1L,
            models = listOf(ChatModel.SOL, unverified),
        )

        override suspend fun loadLocal(): ModelCatalogueSnapshot = snapshot
        override suspend fun refresh(): ModelCatalogueSnapshot = snapshot
    }

    private class TestQuota : QuotaSource {
        override suspend fun loadCached(): QuotaSnapshot? = null
        override suspend fun refresh(apiKey: CharArray): QuotaSnapshot = QuotaSnapshot(
            fetchedAt = Instant.EPOCH,
            planId = null,
            limited = false,
            monthly = com.commandcode.chat.data.service.RemainingQuota(0.0, null),
            fiveHour = null,
            weekly = null,
            purchasedCredits = 0.0,
            freeCredits = 0.0,
        )
        override suspend fun clear() = Unit
    }

    private class RecordingAttachmentOperations(private val allowImports: Boolean) : AttachmentOperations {
        var importCalls = 0

        override suspend fun initialise() = Unit
        override suspend fun importImage(uri: Uri, modelId: String): ImageAttachment {
            // Match the real importer's asynchronous IO before publishing its result.
            kotlinx.coroutines.yield()
            importCalls += 1
            check(allowImports) { "stale result must not import" }
            return ImageAttachment(importCalls.toString(), "photo$importCalls.png", "image/png", 4, 37, 2, 2)
        }
        override suspend fun discard(images: List<ImageAttachment>) = Unit
        override suspend fun prepareTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            images: List<ImageAttachment>,
            systemPrompt: String,
        ): PreparedAttachmentTurn = error("not expected")
        override suspend fun deleteConversation(id: String) = Unit
        override suspend fun preview(id: String, maxEdge: Int): android.graphics.Bitmap {
            check(allowImports)
            return android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }
}
