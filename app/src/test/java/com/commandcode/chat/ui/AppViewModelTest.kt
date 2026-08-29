package com.commandcode.chat.ui

import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.ChatStore
import com.commandcode.chat.SettingsStore
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun orderedDeltasCheckpointAtCharacterThresholdAndCompleteWithUsage() = runTest(dispatcher) {
        val chats = FakeChats()
        val usage = TokenUsage(20, 5, 8)
        val source = StreamSource {
                _, _, _, _ -> flowOf(
                    StreamEvent.Delta("a".repeat(600)),
                    StreamEvent.Delta("b".repeat(424)),
                    StreamEvent.Usage(usage),
                    StreamEvent.Done,
                )
        }
        val viewModel = viewModel(chats, source)
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals(listOf("a".repeat(600) + "b".repeat(424)), chats.checkpoints)
        assertEquals("a".repeat(600) + "b".repeat(424), chats.completedText)
        assertEquals(usage, chats.completedUsage)
        assertFalse(viewModel.state.value.sending)
    }

    @Test
    fun cancellationRetainsPartialContentAndInterruptsTurn() = runTest(dispatcher) {
        val chats = FakeChats()
        val source = StreamSource { _, _, _, _ ->
            flow {
                emit(StreamEvent.Delta("partial"))
                awaitCancellation()
            }
        }
        val viewModel = viewModel(chats, source)
        configureKey(viewModel)

        viewModel.send("hello")
        runCurrent()
        viewModel.cancel()
        advanceUntilIdle()

        assertEquals("partial", chats.interruptedText)
        assertEquals("Response interrupted", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.sending)
    }

    private fun TestScope.configureKey(viewModel: AppViewModel) {
        val key = "offline-test-key".toCharArray()
        viewModel.saveApiKey(key)
        runCurrent()
        assertTrue(key.all { it == '\u0000' })
        assertTrue(viewModel.state.value.keyConfigured)
    }

    private fun viewModel(chats: FakeChats, source: StreamSource) = AppViewModel(
        secrets = FakeKeys(),
        settings = FakeSettings(),
        chats = chats,
        streamSource = source,
        now = { Instant.parse("2026-08-29T12:00:00Z") },
        elapsedMillis = { 0L },
    )

    private class FakeKeys : ApiKeyStore {
        private var key: CharArray? = null
        override fun saveApiKey(value: CharArray) { key = value.copyOf() }
        override fun readApiKey(): CharArray? = key?.copyOf()
        override fun clearApiKey() { key?.fill('\u0000'); key = null }
    }

    private class FakeSettings : SettingsStore {
        override var zdr = true
        override var billingDay = 1
    }

    private class FakeChats : ChatStore {
        private val conversations = MutableStateFlow<List<Conversation>>(emptyList())
        private val messages = MutableStateFlow<List<Message>>(emptyList())
        private val usage = MutableStateFlow<List<UsageEvent>>(emptyList())
        val checkpoints = mutableListOf<String>()
        var completedText: String? = null
        var completedUsage: TokenUsage? = null
        var interruptedText: String? = null

        override fun observeConversations(): Flow<List<Conversation>> = conversations
        override fun observeMessages(conversationId: String): Flow<List<Message>> = messages
        override fun observeUsageEvents(): Flow<List<UsageEvent>> = usage
        override suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel) =
            PendingTurn("conversation", "user", "assistant")
        override suspend fun checkpointAssistant(messageId: String, text: String) { checkpoints += text }
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?) {
            completedText = text
            completedUsage = usage
        }
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) {
            interruptedText = partialText
        }
        override suspend fun deleteConversation(id: String) = Unit
    }
}
