package com.commandcode.chat.ui

import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.ChatStore
import com.commandcode.chat.SettingsStore
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.CommandCodeException
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setMain() = Dispatchers.setMain(dispatcher)
    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun usageBeforeDoneCompletesOrderedDeltasAndClearsStreamingKeyCopy() = runTest(dispatcher) {
        val chats = FakeChats()
        val keys = FakeKeys()
        val usage = TokenUsage(20, 5, 8)
        val source = StreamSource { _, _, _, _ -> flowOf(
            StreamEvent.Delta("a"), StreamEvent.Delta("b"), StreamEvent.Usage(usage), StreamEvent.Done,
        ) }
        val viewModel = viewModel(chats, source, keys = keys)
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals("ab", chats.completedText)
        assertEquals(usage, chats.completedUsage)
        assertTrue(keys.returnedKeys.last().all { it == '\u0000' })
        assertFalse(viewModel.state.value.sending)
    }

    @Test
    fun doneWithoutUsageInterruptsPartialWithActionableSafeError() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ ->
            flowOf(StreamEvent.Delta("partial"), StreamEvent.Done)
        })
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals("partial", chats.interruptedText)
        assertNull(chats.completedText)
        assertEquals("Usage data was not received. Partial response saved. Try again.", viewModel.state.value.errorMessage)
    }

    @Test
    fun eofWithoutDoneInterruptsPartial() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flowOf(StreamEvent.Delta("partial")) })
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals("partial", chats.interruptedText)
        assertEquals("Response stream ended early. Partial response saved. Try again.", viewModel.state.value.errorMessage)
    }

    @Test
    fun eventsAfterDoneAreIgnoredIncludingLateUsageAndDelta() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flowOf(
            StreamEvent.Delta("kept"), StreamEvent.Done,
            StreamEvent.Error("ignored-code", "ignored detail"),
            StreamEvent.Usage(TokenUsage(1, 0, 1)), StreamEvent.Delta("ignored"),
        ) })
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals("kept", chats.interruptedText)
        assertNull(chats.completedText)
    }

    @Test
    fun cancellationBeforeCommitInterrupts() = runTest(dispatcher) {
        val chats = FakeChats()
        val source = StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("partial"))
            emit(StreamEvent.Usage(TokenUsage(1, 0, 1)))
            emit(StreamEvent.Done)
            awaitCancellation()
        } }
        val viewModel = viewModel(chats, source)
        configureKey(viewModel)
        viewModel.send("hello")
        runCurrent()

        viewModel.cancel()
        advanceUntilIdle()

        assertEquals("partial", chats.interruptedText)
        assertNull(chats.completedText)
        assertEquals("Response interrupted", viewModel.state.value.errorMessage)
    }

    @Test
    fun cancellationAfterCommitDoesNotInterruptOrReportFailure() = runTest(dispatcher) {
        val chats = FakeChats().apply {
            completionEntered = CompletableDeferred()
            completionRelease = CompletableDeferred()
        }
        val viewModel = viewModel(chats, successfulSource("answer"))
        configureKey(viewModel)
        viewModel.send("hello")
        runCurrent()
        chats.completionEntered!!.await()

        viewModel.cancel()
        chats.completionRelease!!.complete(Unit)
        advanceUntilIdle()

        assertEquals("answer", chats.completedText)
        assertNull(chats.interruptedText)
        assertNull(viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.sending)
    }

    @Test
    fun deletingActiveConversationSettlesInterruptBeforeDelete() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("partial"))
            awaitCancellation()
        } })
        configureKey(viewModel)
        viewModel.send("hello")
        runCurrent()

        viewModel.deleteConversation("conversation")
        advanceUntilIdle()

        assertEquals(listOf("interrupt:assistant-1", "delete:conversation"), chats.lifecycleEvents)
        assertFalse(viewModel.state.value.sending)
        assertNull(viewModel.state.value.currentConversationId)
    }

    @Test
    fun switchingConversationSuppressesOtherConversationLiveResponse() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("other") }
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("conversation A partial"))
            awaitCancellation()
        } })
        configureKey(viewModel)
        viewModel.send("hello")
        runCurrent()

        viewModel.openConversation("other")
        runCurrent()

        assertEquals("other", viewModel.state.value.currentConversationId)
        assertNull(viewModel.state.value.visibleStreamingText)
        assertTrue(viewModel.state.value.visibleMessages.none { it.content.contains("conversation A") })
        viewModel.cancel()
        advanceUntilIdle()
    }

    @Test
    fun immediateSecondSendUsesCommittedRepositorySnapshot() = runTest(dispatcher) {
        val chats = FakeChats()
        val requests = mutableListOf<List<ApiMessage>>()
        val source = StreamSource { _, _, messages, _ ->
            requests += messages
            successfulEvents(if (requests.size == 1) "first answer" else "second answer")
        }
        val viewModel = viewModel(chats, source)
        configureKey(viewModel)

        viewModel.send("first question")
        advanceUntilIdle()
        viewModel.send("second question")
        advanceUntilIdle()

        assertEquals(
            listOf("first question", "first answer", "second question"),
            requests[1].map(ApiMessage::content),
        )
    }

    @Test
    fun activeAssistantPlaceholderIsSuppressedFromVisibleMessages() {
        val assistant = Message("assistant", "conversation", "ASSISTANT", "checkpoint", ChatModel.SOL, 2, "STREAMING")
        val state = AppUiState(
            currentConversationId = "conversation",
            messages = listOf(assistant),
            activeConversationId = "conversation",
            activeAssistantMessageId = "assistant",
            streamingText = "checkpoint plus live",
        )

        assertTrue(state.visibleMessages.isEmpty())
        assertEquals("checkpoint plus live", state.visibleStreamingText)
    }

    @Test
    fun timeThresholdCheckpointsWithoutCharacterThreshold() = runTest(dispatcher) {
        val chats = FakeChats()
        var elapsed = 0L
        val source = StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("a"))
            elapsed = 1_000L
            emit(StreamEvent.Delta("b"))
            emit(StreamEvent.Usage(TokenUsage(1, 0, 1)))
            emit(StreamEvent.Done)
        } }
        val viewModel = viewModel(chats, source, elapsedMillis = { elapsed })
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals(listOf("ab"), chats.checkpoints)
    }

    @Test
    fun typedTransportFailureUsesFixedMessageAndInterrupts() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow { throw CommandCodeException.RateLimited() } })
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals("Request rate or plan limit reached", viewModel.state.value.errorMessage)
        assertEquals("", chats.interruptedText)
    }

    @Test
    fun providerFrameCodeIsNeverInterpolatedIntoErrorText() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flowOf(
            StreamEvent.Error("secret-provider-detail", "raw detail"),
        ) })
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals("Response stream could not be read. Partial response saved. Try again.", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.errorMessage!!.contains("secret-provider-detail"))
    }

    @Test
    fun unreadableKeyAtStartupBlocksInRecovery() = runTest(dispatcher) {
        val keys = FakeKeys(readFailure = true)
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), keys = keys)
        runCurrent()

        assertEquals("The encrypted API key cannot be opened. Recovery is required.", viewModel.state.value.recoveryMessage)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun billingBoundsRejectZeroAndThirtyTwo() = runTest(dispatcher) {
        val viewModel = viewModel(FakeChats(), successfulSource("unused"))
        runCurrent()

        viewModel.setBillingDay(0)
        assertEquals("Billing day must be from 1 to 31", viewModel.state.value.billingDayError)
        viewModel.setBillingDay(32)
        assertEquals("Billing day must be from 1 to 31", viewModel.state.value.billingDayError)
        assertEquals(1, viewModel.state.value.billingDay)
    }

    @Test
    fun budgetRefreshTickExpiresWindowWithoutDatabaseEmission() = runTest(dispatcher) {
        val eventTime = Instant.parse("2026-08-29T10:00:00Z")
        var now = Instant.parse("2026-08-29T12:00:00Z")
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val chats = FakeChats().apply {
            usage.value = listOf(UsageEvent("u", ChatModel.SOL, eventTime, null, null, BigDecimal.ONE))
        }
        val viewModel = viewModel(chats, successfulSource("unused"), now = { now }, budgetTicks = ticks)
        runCurrent()
        assertEquals(BigDecimal.ONE, viewModel.state.value.budget.fiveHours.usedCredits)

        now = Instant.parse("2026-08-29T15:00:00Z")
        ticks.emit(Unit)
        runCurrent()

        assertEquals(BigDecimal.ZERO, viewModel.state.value.budget.fiveHours.usedCredits)
    }

    private fun successfulSource(answer: String) = StreamSource { _, _, _, _ -> successfulEvents(answer) }
    private fun successfulEvents(answer: String) = flowOf(
        StreamEvent.Delta(answer), StreamEvent.Usage(TokenUsage(1, 0, 1)), StreamEvent.Done,
    )

    private fun TestScope.configureKey(viewModel: AppViewModel) {
        val key = "offline-test-key".toCharArray()
        viewModel.saveApiKey(key)
        runCurrent()
        assertTrue(key.all { it == '\u0000' })
        assertTrue(viewModel.state.value.keyConfigured)
    }

    private fun viewModel(
        chats: FakeChats,
        source: StreamSource,
        keys: FakeKeys = FakeKeys(),
        now: () -> Instant = { Instant.parse("2026-08-29T12:00:00Z") },
        elapsedMillis: () -> Long = { 0L },
        budgetTicks: Flow<Unit>? = flowOf(),
    ) = AppViewModel(
        secrets = keys,
        settings = FakeSettings(),
        chats = chats,
        streamSource = source,
        now = now,
        elapsedMillis = elapsedMillis,
        budgetTicks = budgetTicks,
    )

    private class FakeKeys(private val readFailure: Boolean = false) : ApiKeyStore {
        private var key: CharArray? = null
        val returnedKeys = mutableListOf<CharArray>()
        override fun saveApiKey(value: CharArray) { key = value.copyOf() }
        override fun readApiKey(): CharArray? {
            if (readFailure) throw com.commandcode.chat.data.security.KeyRecoveryRequired()
            return key?.copyOf()?.also(returnedKeys::add)
        }
        override fun clearApiKey() { key?.fill('\u0000'); key = null }
    }

    private class FakeSettings : SettingsStore {
        override var zdr = true
        override var billingDay = 1
    }

    private class FakeChats : ChatStore {
        val conversations = MutableStateFlow<List<Conversation>>(emptyList())
        private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        val usage = MutableStateFlow<List<UsageEvent>>(emptyList())
        val checkpoints = mutableListOf<String>()
        val lifecycleEvents = mutableListOf<String>()
        var completedText: String? = null
        var completedUsage: TokenUsage? = null
        var interruptedText: String? = null
        var completionEntered: CompletableDeferred<Unit>? = null
        var completionRelease: CompletableDeferred<Unit>? = null
        private var turnCount = 0
        private var deleted = false

        override fun observeConversations(): Flow<List<Conversation>> = conversations
        override fun observeMessages(conversationId: String): Flow<List<Message>> = messages(conversationId)
        override fun observeUsageEvents(): Flow<List<UsageEvent>> = usage
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = messages(conversationId).value

        override suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel): PendingTurn {
            val id = conversationId ?: "conversation"
            turnCount += 1
            val userId = "user-$turnCount"
            val assistantId = "assistant-$turnCount"
            if (conversations.value.none { it.id == id }) addConversation(id)
            val existing = messages(id).value
            messages(id).value = existing +
                Message(userId, id, "USER", text, null, turnCount * 2L, "COMPLETE") +
                Message(assistantId, id, "ASSISTANT", "", model, turnCount * 2L + 1, "PENDING")
            return PendingTurn(id, userId, assistantId)
        }

        override suspend fun checkpointAssistant(messageId: String, text: String) {
            check(!deleted) { "checkpoint after delete" }
            checkpoints += text
            updateAssistant(messageId, text, "STREAMING")
        }

        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean {
            completedText = text
            completedUsage = usage
            updateAssistant(messageId, text, "COMPLETE")
            completionEntered?.complete(Unit)
            completionRelease?.await()
            return true
        }

        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) {
            check(!deleted) { "interrupt after delete" }
            lifecycleEvents += "interrupt:$messageId"
            interruptedText = partialText
            updateAssistant(messageId, partialText, "INTERRUPTED")
        }

        override suspend fun deleteConversation(id: String) {
            lifecycleEvents += "delete:$id"
            deleted = true
            conversations.value = conversations.value.filterNot { it.id == id }
            messageFlows.remove(id)
        }

        fun addConversation(id: String) {
            conversations.value = conversations.value + Conversation(id, id, ChatModel.SOL, 1, 1)
            messages(id)
        }

        private fun messages(id: String) = messageFlows.getOrPut(id) { MutableStateFlow(emptyList()) }
        private fun updateAssistant(messageId: String, text: String, status: String) {
            messageFlows.values.forEach { messageFlow ->
                messageFlow.value = messageFlow.value.map {
                    if (it.id == messageId) it.copy(content = text, status = status) else it
                }
            }
        }
    }
}
