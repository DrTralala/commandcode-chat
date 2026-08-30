package com.commandcode.chat.ui

import androidx.lifecycle.viewModelScope
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
import com.commandcode.chat.data.security.SecureStoragePersistenceFailure
import com.commandcode.chat.data.service.ModelCatalogueSnapshot
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.data.service.RemainingQuota
import com.commandcode.chat.data.service.ServiceException
import com.commandcode.chat.data.service.UsedQuota
import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
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
    fun doneWithoutUsageCompletesWithUnknownUsageAndNoError() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ ->
            flowOf(StreamEvent.Delta("partial"), StreamEvent.Done)
        })
        configureKey(viewModel)

        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals("partial", chats.completedText)
        assertNull(chats.completedUsage)
        assertNull(chats.interruptedText)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun eofWithoutDoneInterruptsPartial() = runTest(dispatcher) {
        val chats = FakeChats()
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flowOf(
            StreamEvent.Delta("partial"),
            StreamEvent.Usage(TokenUsage(1, 0, 1)),
        ) })
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

        assertEquals("kept", chats.completedText)
        assertNull(chats.completedUsage)
        assertNull(chats.interruptedText)
    }

    @Test
    fun usageAndDoneCompletesWithoutWaitingForOpenUpstreamAndCancelsIt() = runTest(dispatcher) {
        val chats = FakeChats()
        val upstreamCleaned = CompletableDeferred<Unit>()
        var codeAfterDoneRan = false
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow {
            try {
                emit(StreamEvent.Delta("answer"))
                emit(StreamEvent.Usage(TokenUsage(1, 0, 1)))
                emit(StreamEvent.Done)
                codeAfterDoneRan = true
                awaitCancellation()
            } finally {
                upstreamCleaned.complete(Unit)
            }
        } })
        configureKey(viewModel)

        viewModel.send("hello")
        runCurrent()
        withTimeout(1_000) { upstreamCleaned.await() }

        assertEquals("answer", chats.completedText)
        assertTrue(upstreamCleaned.isCompleted)
        assertFalse(codeAfterDoneRan)
        assertFalse(viewModel.state.value.sending)
    }

    @Test
    fun cancellationBeforeCommitInterrupts() = runTest(dispatcher) {
        val chats = FakeChats()
        val source = StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("partial"))
            emit(StreamEvent.Usage(TokenUsage(1, 0, 1)))
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
    fun cancellationImmediatelyAfterBeginTurnCommitRegistersThenInterruptsAnchor() = runTest(dispatcher) {
        val chats = FakeChats().apply {
            beginTurnCommitted = CompletableDeferred()
            beginTurnRelease = CompletableDeferred()
        }
        val viewModel = viewModel(chats, successfulSource("unused"))
        configureKey(viewModel)
        viewModel.send("hello")
        runCurrent()
        chats.beginTurnCommitted!!.await()

        viewModel.cancel()
        chats.beginTurnRelease!!.complete(Unit)
        advanceUntilIdle()

        assertEquals("INTERRUPTED", chats.assistantStatus("assistant-1"))
        assertEquals(false, chats.usageCompleteByRequest["assistant-1"])
        assertTrue(chats.lifecycleEvents.contains("interrupt:assistant-1"))
        assertFalse(viewModel.state.value.sending)
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
    fun deletingUnrelatedConversationKeepsActiveSendAndCancelControl() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("conversation"); addConversation("other") }
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("active A"))
            awaitCancellation()
        } })
        configureKey(viewModel)
        viewModel.openConversation("conversation")
        runCurrent()
        viewModel.send("hello")
        runCurrent()
        viewModel.openConversation("other")
        runCurrent()

        viewModel.deleteConversation("other")
        runCurrent()

        assertTrue(viewModel.state.value.sending)
        assertEquals("conversation", viewModel.state.value.activeConversationId)
        assertTrue(chats.lifecycleEvents.none { it.startsWith("interrupt:") })
        viewModel.cancel()
        advanceUntilIdle()
        assertEquals("active A", chats.interruptedText)
        assertFalse(viewModel.state.value.sending)
    }

    @Test
    fun failedUnrelatedDeletionKeepsActiveSendAndCancelControl() = runTest(dispatcher) {
        val chats = FakeChats().apply {
            addConversation("conversation")
            addConversation("other")
            deleteFailures += "other"
        }
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("active A"))
            awaitCancellation()
        } })
        configureKey(viewModel)
        viewModel.openConversation("conversation")
        runCurrent()
        viewModel.send("hello")
        runCurrent()

        viewModel.deleteConversation("other")
        runCurrent()

        assertTrue(viewModel.state.value.sending)
        assertEquals("conversation", viewModel.state.value.activeConversationId)
        assertEquals("Conversation could not be deleted. Try again.", viewModel.state.value.errorMessage)
        viewModel.cancel()
        advanceUntilIdle()
        assertEquals("active A", chats.interruptedText)
        assertFalse(viewModel.state.value.sending)
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
        val assistant = Message("assistant", "conversation", "ASSISTANT", "checkpoint", ChatModel.SOL.apiId, 2, "STREAMING")
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
    fun checkedStoreRemovalFailureRetainsConfiguredKeyAndUsesSafeError() = runTest(dispatcher) {
        val keys = FakeKeys(removeFailure = true)
        val quota = FakeQuota(cached = quotaSnapshot("cached"))
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), keys = keys, quota = quota)
        configureKey(viewModel)
        val retainedBudget = viewModel.state.value.budget
        val clearsBefore = quota.clearCalls

        viewModel.clearApiKey()
        runCurrent()

        assertTrue(viewModel.state.value.keyConfigured)
        assertEquals("API key could not be cleared. Try again.", viewModel.state.value.errorMessage)
        assertEquals(retainedBudget, viewModel.state.value.budget)
        assertEquals(clearsBefore, quota.clearCalls)
    }

    @Test
    fun localFortyFourModelSnapshotLoadsBeforeLoadingFinishes() = runTest(dispatcher) {
        val release = CompletableDeferred<ModelCatalogueSnapshot>()
        val catalogue = FakeCatalogue().apply { localBlock = { release.await() } }
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)

        runCurrent()
        assertTrue(viewModel.state.value.loading)

        release.complete(catalogueSnapshot(models = catalogueModels()))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertEquals(44, viewModel.state.value.models.size)
    }

    @Test
    fun successfulLocalCatalogueRefreshKeepsAllModelsWithoutAnError() = runTest(dispatcher) {
        val catalogue = FakeCatalogue(local = catalogueSnapshot(models = catalogueModels()))
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)

        advanceUntilIdle()

        assertEquals(44, viewModel.state.value.models.size)
        assertEquals(catalogue.local.models, viewModel.state.value.models)
        assertEquals(1, catalogue.refreshCalls)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun remoteCatalogueSuccessReplacesLocalModels() = runTest(dispatcher) {
        val remote = listOf(ChatModel.SOL, model("remote"))
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, model("local"))),
            remote = catalogueSnapshot(version = "remote", models = remote),
        )

        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)
        advanceUntilIdle()

        assertEquals(remote, viewModel.state.value.models)
    }

    @Test
    fun remoteCatalogueFailureRetainsLocalModels() = runTest(dispatcher) {
        val local = listOf(ChatModel.SOL, model("local"))
        val catalogue = FakeCatalogue(local = catalogueSnapshot(models = local)).apply {
            refreshFailure = IllegalStateException("secret catalogue detail")
        }

        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)
        advanceUntilIdle()

        assertEquals(local, viewModel.state.value.models)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun selectionAcceptsOnlyAnActiveCatalogueEntry() = runTest(dispatcher) {
        val active = model("active")
        val catalogue = FakeCatalogue(local = catalogueSnapshot(models = listOf(ChatModel.SOL, active)))
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)
        advanceUntilIdle()

        viewModel.selectModel(active)
        assertEquals(active, viewModel.state.value.selectedModel)

        viewModel.selectModel(model("inactive"))
        assertEquals(active, viewModel.state.value.selectedModel)
    }

    @Test
    fun selectionResolvesStaleMetadataThroughTheActiveProviderId() = runTest(dispatcher) {
        val current = ChatModel("active", "Current name", ApiFamily.OPENAI_CHAT)
        val stale = ChatModel("active", "Old name", ApiFamily.OPENAI_CHAT)
        val catalogue = FakeCatalogue(local = catalogueSnapshot(models = listOf(ChatModel.SOL, current)))
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)
        advanceUntilIdle()

        viewModel.selectModel(stale)

        assertEquals(current, viewModel.state.value.selectedModel)
    }

    @Test
    fun removedSelectedModelFallsBackToSol() = runTest(dispatcher) {
        val removed = model("removed")
        val remoteRelease = CompletableDeferred<ModelCatalogueSnapshot>()
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, removed)),
        ).apply { refreshBlock = { remoteRelease.await() } }
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)
        runCurrent()
        viewModel.selectModel(removed)

        remoteRelease.complete(catalogueSnapshot(version = "remote", models = listOf(ChatModel.SOL)))
        advanceUntilIdle()

        assertEquals(ChatModel.SOL, viewModel.state.value.selectedModel)
    }

    @Test
    fun refreshedCatalogueRetainsSelectionByModelIdAndUsesCurrentMetadata() = runTest(dispatcher) {
        val selected = ChatModel("selected", "Old name", ApiFamily.OPENAI_CHAT)
        val refreshed = ChatModel("selected", "New name", ApiFamily.OPENAI_CHAT)
        val remoteRelease = CompletableDeferred<ModelCatalogueSnapshot>()
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, selected)),
        ).apply { refreshBlock = { remoteRelease.await() } }
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)
        runCurrent()
        viewModel.selectModel(selected)

        remoteRelease.complete(catalogueSnapshot(version = "remote", models = listOf(ChatModel.SOL, refreshed)))
        advanceUntilIdle()

        assertEquals(refreshed, viewModel.state.value.selectedModel)
    }

    @Test
    fun solLessRemoteCatalogueCannotPublishASelectionOutsideTheActiveModels() = runTest(dispatcher) {
        val local = listOf(ChatModel.SOL, model("local"))
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = local),
            remote = catalogueSnapshot(version = "invalid", models = listOf(model("remote"))),
        )

        val viewModel = viewModel(FakeChats(), successfulSource("unused"), catalogue = catalogue)
        advanceUntilIdle()

        assertEquals(local, viewModel.state.value.models)
        assertTrue(viewModel.state.value.selectedModel in viewModel.state.value.models)
    }

    @Test
    fun cachedStartupQuotaIsStaleUntilLiveRefreshSucceeds() = runTest(dispatcher) {
        val cached = quotaSnapshot("cached")
        val live = quotaSnapshot("live")
        val release = CompletableDeferred<QuotaSnapshot>()
        val quota = FakeQuota(cached = cached).apply { refreshBlock = { release.await() } }
        val viewModel = viewModel(
            FakeChats(),
            successfulSource("unused"),
            keys = FakeKeys(initialKey = "configured".toCharArray()),
            quota = quota,
        )

        runCurrent()
        assertEquals(cached, viewModel.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.STALE, viewModel.state.value.budget.freshness)
        assertTrue(viewModel.state.value.budget.refreshing)

        release.complete(live)
        advanceUntilIdle()
        assertEquals(live, viewModel.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.LIVE, viewModel.state.value.budget.freshness)
        assertFalse(viewModel.state.value.budget.refreshing)
    }

    @Test
    fun startupQuotaFailureWithoutCacheIsUnavailable() = runTest(dispatcher) {
        val quota = FakeQuota().apply { refreshFailure = ServiceException(ServiceException.Kind.UNAVAILABLE) }
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"),
            keys = FakeKeys(initialKey = "configured".toCharArray()), quota = quota,
        )

        advanceUntilIdle()

        assertNull(viewModel.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.UNAVAILABLE, viewModel.state.value.budget.freshness)
        assertEquals("Quota service is unavailable. Try again.", viewModel.state.value.budget.errorMessage)
    }

    @Test
    fun startupQuotaFailureWithCacheRemainsStaleWithSafeError() = runTest(dispatcher) {
        val cached = quotaSnapshot("cached")
        val quota = FakeQuota(cached).apply { refreshFailure = ServiceException(ServiceException.Kind.BAD_RESPONSE) }
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"),
            keys = FakeKeys(initialKey = "configured".toCharArray()), quota = quota,
        )

        advanceUntilIdle()

        assertEquals(cached, viewModel.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.STALE, viewModel.state.value.budget.freshness)
        assertEquals("Quota service returned an invalid response.", viewModel.state.value.budget.errorMessage)
    }

    @Test
    fun startupBudgetOpenManualAndSuccessfulSendTriggerQuotaRefreshes() = runTest(dispatcher) {
        val quota = FakeQuota()
        val viewModel = viewModel(
            FakeChats(), successfulSource("answer"),
            keys = FakeKeys(initialKey = "configured".toCharArray()), quota = quota,
        )
        advanceUntilIdle()
        assertEquals(1, quota.refreshCalls)

        viewModel.refreshQuota() // Budget destination entry.
        advanceUntilIdle()
        viewModel.refreshQuota() // Manual refresh.
        advanceUntilIdle()
        viewModel.send("hello")
        advanceUntilIdle()

        assertEquals(4, quota.refreshCalls)
    }

    @Test
    fun overlappingQuotaTriggersShareOneRefresh() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val quota = FakeQuota().apply {
            refreshBlock = {
                entered.complete(Unit)
                release.await()
                quotaSnapshot("live")
            }
        }
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"),
            keys = FakeKeys(initialKey = "configured".toCharArray()), quota = quota,
        )
        runCurrent()
        entered.await()

        viewModel.refreshQuota()
        runCurrent()
        assertEquals(1, quota.refreshCalls)

        release.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun replacingKeyClearsOldQuotaBeforeFailedRefresh() = runTest(dispatcher) {
        val quota = FakeQuota(cached = quotaSnapshot("old"))
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"),
            keys = FakeKeys(initialKey = "old-key".toCharArray()), quota = quota,
        )
        advanceUntilIdle()
        quota.events.clear()
        quota.refreshFailure = ServiceException(ServiceException.Kind.UNAVAILABLE)

        viewModel.saveApiKey("new-key".toCharArray())
        advanceUntilIdle()

        assertEquals(listOf("clear", "refresh"), quota.events)
        assertNull(viewModel.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.UNAVAILABLE, viewModel.state.value.budget.freshness)
    }

    @Test
    fun successfulKeyRemovalClearsQuotaCacheAndState() = runTest(dispatcher) {
        val quota = FakeQuota(cached = quotaSnapshot("old"))
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"),
            keys = FakeKeys(initialKey = "old-key".toCharArray()), quota = quota,
        )
        advanceUntilIdle()
        val clearsBefore = quota.clearCalls

        viewModel.clearApiKey()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.keyConfigured)
        assertEquals(clearsBefore + 1, quota.clearCalls)
        assertEquals(BudgetUiState(), viewModel.state.value.budget)
    }

    @Test
    fun saveThenClearIsOrderedByInvocationAndLeavesCredentialCacheAndUiCleared() = runTest(dispatcher) {
        val firstClearEntered = CompletableDeferred<Unit>()
        val releaseFirstClear = CompletableDeferred<Unit>()
        val keys = FakeKeys(initialKey = "old-key".toCharArray())
        val quota = FakeQuota(cached = quotaSnapshot("old")).apply {
            clearBlock = { call ->
                if (call == 1) {
                    firstClearEntered.complete(Unit)
                    releaseFirstClear.await()
                }
            }
        }
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"), keys = keys, quota = quota,
        )
        advanceUntilIdle()

        viewModel.saveApiKey("new-key".toCharArray())
        runCurrent()
        firstClearEntered.await()
        viewModel.clearApiKey()
        runCurrent()
        assertEquals(1, quota.clearCalls)

        releaseFirstClear.complete(Unit)
        advanceUntilIdle()

        assertNull(keys.storedKey())
        assertNull(quota.cached)
        assertFalse(viewModel.state.value.keyConfigured)
        assertEquals(BudgetUiState(), viewModel.state.value.budget)
    }

    @Test
    fun clearThenSaveIsOrderedByInvocationAndLeavesCredentialCacheAndUiOnNewKey() = runTest(dispatcher) {
        val firstClearEntered = CompletableDeferred<Unit>()
        val releaseFirstClear = CompletableDeferred<Unit>()
        val keys = FakeKeys(initialKey = "old-key".toCharArray())
        val quota = FakeQuota(cached = quotaSnapshot("old")).apply {
            refreshed = quotaSnapshot("new")
            refreshBlock = { refreshed.also { cached = it } }
            clearBlock = { call ->
                if (call == 1) {
                    firstClearEntered.complete(Unit)
                    releaseFirstClear.await()
                }
            }
        }
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"), keys = keys, quota = quota,
        )
        advanceUntilIdle()

        viewModel.clearApiKey()
        runCurrent()
        firstClearEntered.await()
        viewModel.saveApiKey("new-key".toCharArray())
        runCurrent()
        assertEquals(1, quota.clearCalls)

        releaseFirstClear.complete(Unit)
        advanceUntilIdle()

        assertEquals("new-key", keys.storedKey())
        assertEquals("new", quota.cached?.planId)
        assertTrue(viewModel.state.value.keyConfigured)
        assertEquals("new", viewModel.state.value.budget.snapshot?.planId)
        assertEquals(BudgetFreshness.LIVE, viewModel.state.value.budget.freshness)
    }

    @Test
    fun queuedSaveCopyIsWipedWhenViewModelScopeIsCancelledBeforeLockAcquisition() = runTest(dispatcher) {
        val clearEntered = CompletableDeferred<Unit>()
        val clearRelease = CompletableDeferred<Unit>()
        val keys = FakeKeys(initialKey = "old-key".toCharArray())
        val quota = FakeQuota(cached = quotaSnapshot("old")).apply {
            clearBlock = {
                clearEntered.complete(Unit)
                clearRelease.await()
            }
        }
        var clientOwnedCopy: CharArray? = null
        val viewModel = viewModel(
            FakeChats(),
            successfulSource("unused"),
            keys = keys,
            quota = quota,
            apiKeyCopier = { value -> value.copyOf().also { clientOwnedCopy = it } },
        )
        advanceUntilIdle()

        viewModel.clearApiKey()
        clearEntered.await()
        val callerKey = "new-key".toCharArray()
        viewModel.saveApiKey(callerKey)
        runCurrent()

        assertTrue(callerKey.all { it == '\u0000' })
        assertEquals("new-key", requireNotNull(clientOwnedCopy).concatToString())
        assertEquals(1, quota.clearCalls)

        viewModel.viewModelScope.cancel()
        advanceUntilIdle()

        assertTrue(requireNotNull(clientOwnedCopy).all { it == '\u0000' })
        assertNull(keys.storedKey())
        assertEquals(1, quota.clearCalls)
    }

    @Test
    fun replacementCacheClearFailureKeepsOldKeyAndReportsOnlyBudgetError() = runTest(dispatcher) {
        val keys = FakeKeys(initialKey = "old-key".toCharArray())
        val quota = FakeQuota(cached = quotaSnapshot("old"))
        val viewModel = viewModel(
            FakeChats(),
            StreamSource { _, _, _, _ -> flow { throw CommandCodeException.RateLimited() } },
            keys = keys,
            quota = quota,
        )
        advanceUntilIdle()
        viewModel.send("set chat error")
        advanceUntilIdle()
        val retainedSnapshot = viewModel.state.value.budget.snapshot
        quota.events.clear()
        quota.clearFailure = IOException("secret cache failure")
        val replacement = "new-key".toCharArray()

        viewModel.saveApiKey(replacement)
        advanceUntilIdle()

        assertEquals(listOf("clear"), quota.events)
        assertEquals("old-key", keys.storedKey())
        assertTrue(replacement.all { it == '\u0000' })
        assertEquals("Request rate or plan limit reached", viewModel.state.value.errorMessage)
        assertEquals(retainedSnapshot, viewModel.state.value.budget.snapshot)
        assertEquals("Saved quota could not be cleared. Try again.", viewModel.state.value.budget.errorMessage)
    }

    @Test
    fun removalCacheClearFailureCannotRestoreOldQuotaAfterRestart() = runTest(dispatcher) {
        val keys = FakeKeys(initialKey = "old-key".toCharArray())
        val quota = FakeQuota(cached = quotaSnapshot("old"))
        val viewModel = viewModel(
            FakeChats(),
            StreamSource { _, _, _, _ -> flow { throw CommandCodeException.RateLimited() } },
            keys = keys,
            quota = quota,
        )
        advanceUntilIdle()
        viewModel.send("set chat error")
        advanceUntilIdle()
        quota.clearFailure = IOException("secret cache failure")

        viewModel.clearApiKey()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.keyConfigured)
        assertNull(keys.storedKey())
        assertEquals("Request rate or plan limit reached", viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.UNAVAILABLE, viewModel.state.value.budget.freshness)
        assertEquals("Saved quota could not be cleared. Try again.", viewModel.state.value.budget.errorMessage)
        assertEquals("old", quota.cached?.planId)

        quota.clearFailure = null
        val restarted = viewModel(FakeChats(), successfulSource("unused"), keys = keys, quota = quota)
        advanceUntilIdle()

        assertFalse(restarted.state.value.keyConfigured)
        assertNull(restarted.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.UNAVAILABLE, restarted.state.value.budget.freshness)
    }

    @Test
    fun everyKeyReturnedForQuotaUseIsZeroed() = runTest(dispatcher) {
        val keys = FakeKeys(initialKey = "configured".toCharArray())
        val quota = FakeQuota()
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), keys = keys, quota = quota)
        advanceUntilIdle()
        quota.refreshFailure = ServiceException(ServiceException.Kind.UNAVAILABLE)
        viewModel.refreshQuota()
        advanceUntilIdle()

        assertTrue(keys.returnedKeys.isNotEmpty())
        assertTrue(keys.returnedKeys.all { key -> key.all { it == '\u0000' } })
    }

    @Test
    fun quotaFailureDoesNotAlterChatErrorOrCreateEstimateState() = runTest(dispatcher) {
        val quota = FakeQuota().apply { refreshFailure = ServiceException(ServiceException.Kind.UNAVAILABLE) }
        val viewModel = viewModel(
            FakeChats(),
            StreamSource { _, _, _, _ -> flow { throw CommandCodeException.RateLimited() } },
            quota = quota,
        )
        configureKey(viewModel)
        viewModel.send("hello")
        advanceUntilIdle()
        assertEquals("Request rate or plan limit reached", viewModel.state.value.errorMessage)

        viewModel.refreshQuota()
        advanceUntilIdle()

        assertEquals("Request rate or plan limit reached", viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.budget.snapshot)
        assertEquals(BudgetFreshness.UNAVAILABLE, viewModel.state.value.budget.freshness)
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
        elapsedMillis: () -> Long = { 0L },
        catalogue: FakeCatalogue = FakeCatalogue(),
        quota: FakeQuota = FakeQuota(),
        apiKeyCopier: (CharArray) -> CharArray = { it.copyOf() },
    ) = AppViewModel(
        secrets = keys,
        settings = FakeSettings(),
        chats = chats,
        streamSource = source,
        modelCatalogue = catalogue,
        quota = quota,
        elapsedMillis = elapsedMillis,
        apiKeyCopier = apiKeyCopier,
    )

    private class FakeCatalogue(
        var local: ModelCatalogueSnapshot = catalogueSnapshot(models = catalogueModels()),
        var remote: ModelCatalogueSnapshot = local,
    ) : ModelCatalogueSource {
        var localBlock: (suspend () -> ModelCatalogueSnapshot)? = null
        var refreshBlock: (suspend () -> ModelCatalogueSnapshot)? = null
        var refreshFailure: Exception? = null
        var loadCalls = 0
        var refreshCalls = 0

        override suspend fun loadLocal(): ModelCatalogueSnapshot {
            loadCalls += 1
            return localBlock?.invoke() ?: local
        }

        override suspend fun refresh(): ModelCatalogueSnapshot {
            refreshCalls += 1
            refreshFailure?.let { throw it }
            return refreshBlock?.invoke() ?: remote
        }
    }

    private class FakeQuota(
        var cached: QuotaSnapshot? = null,
    ) : QuotaSource {
        var refreshed = quotaSnapshot("live")
        var refreshBlock: (suspend (CharArray) -> QuotaSnapshot)? = null
        var refreshFailure: Exception? = null
        var clearFailure: Exception? = null
        var clearBlock: (suspend (Int) -> Unit)? = null
        var loadCalls = 0
        var refreshCalls = 0
        var clearCalls = 0
        val events = mutableListOf<String>()

        override suspend fun loadCached(): QuotaSnapshot? {
            loadCalls += 1
            return cached
        }

        override suspend fun refresh(apiKey: CharArray): QuotaSnapshot {
            refreshCalls += 1
            events += "refresh"
            refreshFailure?.let { throw it }
            return refreshBlock?.invoke(apiKey) ?: refreshed
        }

        override suspend fun clear() {
            clearCalls += 1
            events += "clear"
            clearBlock?.invoke(clearCalls)
            clearFailure?.let { throw it }
            cached = null
        }
    }

    private companion object {
        fun model(id: String) = ChatModel(id, id, ApiFamily.OPENAI_CHAT)

        fun catalogueModels(): List<ChatModel> = listOf(ChatModel.SOL) +
            (1 until 44).map { index -> model("model-$index") }

        fun catalogueSnapshot(
            version: String = "local",
            models: List<ChatModel>,
        ) = ModelCatalogueSnapshot(1, version, 1L, models)

        fun quotaSnapshot(plan: String) = QuotaSnapshot(
            fetchedAt = Instant.parse("2026-08-30T12:00:00Z"),
            planId = plan,
            limited = true,
            monthly = RemainingQuota(42.0, 70.0),
            fiveHour = UsedQuota(2.0, 14.0, Instant.parse("2026-08-30T15:00:00Z")),
            weekly = UsedQuota(5.0, 35.0, Instant.parse("2026-09-01T12:00:00Z")),
            purchasedCredits = 1.0,
            freeCredits = 2.0,
        )
    }

    private class FakeKeys(
        readFailure: Boolean = false,
        private val removeFailure: Boolean = false,
        initialKey: CharArray? = null,
    ) : ApiKeyStore {
        var readFailure = readFailure
        private var key: CharArray? = initialKey?.copyOf()
        val returnedKeys = mutableListOf<CharArray>()
        override fun saveApiKey(value: CharArray) { key = value.copyOf() }
        override fun readApiKey(): CharArray? {
            if (readFailure) throw com.commandcode.chat.data.security.KeyRecoveryRequired()
            return key?.copyOf()?.also(returnedKeys::add)
        }
        override fun clearApiKey() {
            if (removeFailure) throw SecureStoragePersistenceFailure(IllegalStateException("scripted sensitive detail"))
            key?.fill('\u0000')
            key = null
        }

        fun storedKey(): String? = key?.concatToString()
    }

    private class FakeSettings : SettingsStore {
        override var zdr = true
    }

    private class FakeChats : ChatStore {
        val conversations = MutableStateFlow<List<Conversation>>(emptyList())
        private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        val checkpoints = mutableListOf<String>()
        val lifecycleEvents = mutableListOf<String>()
        var completedText: String? = null
        var completedUsage: TokenUsage? = null
        var interruptedText: String? = null
        var completionEntered: CompletableDeferred<Unit>? = null
        var completionRelease: CompletableDeferred<Unit>? = null
        var beginTurnCommitted: CompletableDeferred<Unit>? = null
        var beginTurnRelease: CompletableDeferred<Unit>? = null
        val usageCompleteByRequest = mutableMapOf<String, Boolean>()
        val deleteFailures = mutableSetOf<String>()
        private var turnCount = 0
        private val deletedConversations = mutableSetOf<String>()
        private val messageOwners = mutableMapOf<String, String>()

        override fun observeConversations(): Flow<List<Conversation>> = conversations
        override fun observeMessages(conversationId: String): Flow<List<Message>> = messages(conversationId)
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = messages(conversationId).value

        override suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel): PendingTurn {
            val id = conversationId ?: "conversation"
            turnCount += 1
            val userId = "user-$turnCount"
            val assistantId = "assistant-$turnCount"
            messageOwners[userId] = id
            messageOwners[assistantId] = id
            if (conversations.value.none { it.id == id }) addConversation(id)
            val existing = messages(id).value
            messages(id).value = existing +
                Message(userId, id, "USER", text, null, turnCount * 2L, "COMPLETE") +
                Message(assistantId, id, "ASSISTANT", "", model.apiId, turnCount * 2L + 1, "PENDING")
            usageCompleteByRequest[assistantId] = false
            beginTurnCommitted?.complete(Unit)
            beginTurnRelease?.await()
            return PendingTurn(id, userId, assistantId)
        }

        override suspend fun checkpointAssistant(messageId: String, text: String) {
            check(messageConversation(messageId) !in deletedConversations) { "checkpoint after delete" }
            checkpoints += text
            updateAssistant(messageId, text, "STREAMING")
        }

        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean {
            completedText = text
            completedUsage = usage
            updateAssistant(messageId, text, "COMPLETE")
            usageCompleteByRequest[messageId] = true
            completionEntered?.complete(Unit)
            completionRelease?.await()
            return true
        }

        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) {
            check(messageConversation(messageId) !in deletedConversations) { "interrupt after delete" }
            lifecycleEvents += "interrupt:$messageId"
            interruptedText = partialText
            updateAssistant(messageId, partialText, "INTERRUPTED")
        }

        override suspend fun deleteConversation(id: String) {
            if (id in deleteFailures) throw IllegalStateException("fake delete failure")
            lifecycleEvents += "delete:$id"
            deletedConversations += id
            conversations.value = conversations.value.filterNot { it.id == id }
            messageFlows.remove(id)
        }

        fun addConversation(id: String) {
            conversations.value = conversations.value + Conversation(id, id, ChatModel.SOL.apiId, 1, 1)
            messages(id)
        }

        fun assistantStatus(messageId: String): String? =
            messageFlows.values.flatMap { it.value }.singleOrNull { it.id == messageId }?.status

        private fun messages(id: String) = messageFlows.getOrPut(id) { MutableStateFlow(emptyList()) }
        private fun messageConversation(messageId: String): String? = messageOwners[messageId]
        private fun updateAssistant(messageId: String, text: String, status: String) {
            messageFlows.values.forEach { messageFlow ->
                messageFlow.value = messageFlow.value.map {
                    if (it.id == messageId) it.copy(content = text, status = status) else it
                }
            }
        }
    }
}
