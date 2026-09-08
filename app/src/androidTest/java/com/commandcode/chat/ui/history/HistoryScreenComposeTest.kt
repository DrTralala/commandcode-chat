package com.commandcode.chat.ui.history

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.ChatStore
import com.commandcode.chat.SettingsStore
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.attachments.AttachmentOperations
import com.commandcode.chat.data.attachments.PreparedAttachmentTurn
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.service.ModelCatalogueSnapshot
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.ui.AppRoot
import com.commandcode.chat.ui.AppViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sortMenuUsesControllerSelectionAndDeleteShowsAttachmentCopy() {
        var selectedSort by mutableStateOf(HistorySort.MOST_RECENT)
        val conversations = listOf(
            conversation("a", updatedAt = 4, textBytes = 100),
            conversation("b", updatedAt = 3, textBytes = 200),
            conversation("c", updatedAt = 2, textBytes = 9_000_000, attachmentBytes = 1_000_000),
        )

        compose.setContent {
            MaterialTheme {
                HistoryScreen(
                    conversations = conversations,
                    sort = selectedSort,
                    onSortChange = { selectedSort = it },
                    modelDisplayName = { it },
                    onOpen = {},
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithTag("history_sort").performClick()
        compose.onNodeWithText("Largest first").performClick()
        compose.waitForIdle()

        assertEquals(HistorySort.LARGEST_FIRST, selectedSort)
        val largestTop = compose.onNodeWithTag("conversation_row_c").fetchSemanticsNode().boundsInRoot.top
        val smallerTop = compose.onNodeWithTag("conversation_row_a").fetchSemanticsNode().boundsInRoot.top
        assertTrue("largest conversation should be first", largestTop < smallerTop)

        compose.onNodeWithTag("delete_conversation_c").performClick()
        compose.onNodeWithText("This removes the conversation and its encrypted attachment copies from this device.")
            .assertIsDisplayed()
    }

    @Test
    fun rowSizeDescriptionUpdatesWhenReactiveConversationTotalsChange() {
        var conversations by mutableStateOf(
            listOf(conversation("c", textBytes = 100_000, attachmentBytes = 8_300_000)),
        )

        compose.setContent {
            MaterialTheme {
                HistoryScreen(
                    conversations = conversations,
                    sort = HistorySort.MOST_RECENT,
                    onSortChange = {},
                    modelDisplayName = { it },
                    onOpen = {},
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithText("~8.40 MB total · 8.30 MB attachments").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Estimated storage: ~8.40 MB total · 8.30 MB attachments",
        ).assertIsDisplayed()

        compose.runOnIdle {
            conversations = listOf(conversation("c", textBytes = 20_000_000, attachmentBytes = 1_000_000))
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("~8.40 MB total · 8.30 MB attachments").assertCountEquals(0)
        compose.onNodeWithText("~21.00 MB total · 1.00 MB attachments").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Estimated storage: ~21.00 MB total · 1.00 MB attachments",
        ).assertIsDisplayed()
    }

    @Test
    fun actualRootPreservesSelectedSortAcrossHistoryChatHistoryJourney() {
        val viewModel = AppViewModel(
            secrets = ConfiguredKeys(),
            settings = TestSettings(),
            chats = TestChats(
                listOf(
                    conversation("a", updatedAt = 2, textBytes = 10),
                    conversation("b", updatedAt = 1, textBytes = 20),
                ),
            ),
            streamSource = StreamSource { _, _, _, _ -> flowOf(StreamEvent.Done) },
            modelCatalogue = TestCatalogue(),
            quota = UnavailableQuota(),
            attachmentOperations = NoopAttachmentOperations(),
        )

        compose.setContent { AppRoot(viewModel = viewModel) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("New Chat").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("nav_history").performClick()
        compose.onNodeWithTag("history_sort").performClick()
        compose.onNodeWithText("Smallest first").performClick()
        compose.onNodeWithTag("nav_chat").performClick()
        compose.onNodeWithTag("nav_history").performClick()
        compose.waitForIdle()

        assertEquals(HistorySort.SMALLEST_FIRST, viewModel.state.value.historySort)
        compose.onNodeWithText("Smallest first").assertIsDisplayed()
    }

    private fun conversation(
        id: String,
        updatedAt: Long = 2,
        textBytes: Long = 0,
        attachmentBytes: Long = 0,
    ) = Conversation(
        id = id,
        title = id,
        defaultModelId = ChatModel.SOL.apiId,
        createdAt = 0,
        updatedAt = updatedAt,
        textBytes = textBytes,
        attachmentBytes = attachmentBytes,
    )

    private class ConfiguredKeys : ApiKeyStore {
        override fun saveApiKey(value: CharArray) = Unit
        override fun readApiKey(): CharArray = "configured".toCharArray()
        override fun clearApiKey() = Unit
    }

    private class TestSettings : SettingsStore {
        override var zdr = true
        override var amoled = false
    }

    private class TestChats(
        private val values: List<Conversation>,
    ) : ChatStore {
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(values)
        override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = emptyList()
        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
        ): PendingTurn = error("not expected")
        override suspend fun checkpointAssistant(messageId: String, text: String) = Unit
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean = false
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) = Unit
        override suspend fun deleteConversation(id: String) = Unit
    }

    private class TestCatalogue : ModelCatalogueSource {
        private val snapshot = ModelCatalogueSnapshot(1, "test", 1L, listOf(ChatModel.SOL))
        override suspend fun loadLocal(): ModelCatalogueSnapshot = snapshot
        override suspend fun refresh(): ModelCatalogueSnapshot = snapshot
    }

    private class UnavailableQuota : QuotaSource {
        override suspend fun loadCached() = null
        override suspend fun refresh(apiKey: CharArray) = error("offline test")
        override suspend fun clear() = Unit
    }

    private class NoopAttachmentOperations : AttachmentOperations {
        override suspend fun initialise() = Unit
        override suspend fun importImage(uri: Uri, modelId: String): ImageAttachment = error("not expected")
        override suspend fun discard(images: List<ImageAttachment>) = Unit
        override suspend fun prepareTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            images: List<ImageAttachment>,
        ): PreparedAttachmentTurn = error("not expected")
        override suspend fun deleteConversation(id: String) = Unit
        override suspend fun preview(id: String, maxEdge: Int): Bitmap = error("not expected")
    }
}
