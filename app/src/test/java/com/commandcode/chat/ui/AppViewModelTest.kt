package com.commandcode.chat.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.ChatStore
import com.commandcode.chat.SettingsStore
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.CommandCodeException
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.attachments.AttachmentOperations
import com.commandcode.chat.data.attachments.PreparedAttachmentTurn
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.security.SecureStoragePersistenceFailure
import com.commandcode.chat.data.security.SystemPromptStore
import com.commandcode.chat.data.security.effectiveSystemPrompt
import com.commandcode.chat.data.service.ModelCatalogueSnapshot
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.data.service.RemainingQuota
import com.commandcode.chat.data.service.ServiceException
import com.commandcode.chat.data.service.UsedQuota
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
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
    fun zdrOffAndPartialResponsesAreNeverMarkedAsRetryableRoutingFailures() = runTest(dispatcher) {
        for ((zdr, partial) in listOf(false to "", true to "partial")) {
            val chats = FakeChats()
            val vm = viewModel(chats, StreamSource { _, _, _, _ -> flow {
                if (partial.isNotEmpty()) emit(StreamEvent.Delta(partial))
                throw CommandCodeException.ZdrUnavailable()
            } }, settings = FakeSettings(zdr = zdr))
            configureKey(vm)
            vm.send("hello")
            advanceUntilIdle()
            assertEquals("COMPLETE", vm.state.value.messages.single { it.role == "USER" }.status)
            assertEquals("INTERRUPTED", vm.state.value.messages.single { it.role == "ASSISTANT" }.status)
            assertEquals(partial, chats.interruptedText)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun repeatedRoutingFailureCanBeDeletedWithoutClearingDraftOrConversation() = runTest(dispatcher) {
        val chats = FakeChats()
        val vm = viewModel(chats, StreamSource { _, _, _, _ -> flow { throw CommandCodeException.ZdrUnavailable() } })
        configureKey(vm)
        vm.send("failed")
        advanceUntilIdle()
        val user = vm.state.value.messages.single { it.role == "USER" }.id
        vm.retryMessage(user)
        advanceUntilIdle()
        assertEquals("FAILED_ZDR", vm.state.value.messages.single { it.id == user }.status)
        val id = vm.state.value.currentConversationId
        vm.updateDraft("unrelated draft")
        vm.deleteFailedMessage(user)
        vm.deleteFailedMessage(user)
        advanceUntilIdle()
        assertEquals(id, vm.state.value.currentConversationId)
        assertEquals("unrelated draft", vm.state.value.draftText)
        assertTrue(vm.state.value.messages.isEmpty())
        assertEquals(1, vm.state.value.conversations.size)
    }

    @Test
    fun zdrFailureRetryUsesCurrentSettingsWithoutDuplicatingUserOrClearingDraft() = runTest(dispatcher) {
        val chats = FakeChats()
        var attempts = 0
        val usedModels = mutableListOf<String>()
        val usedZdr = mutableListOf<Boolean>()
        val vm = viewModel(chats, StreamSource { _, model, _, zdr -> flow {
            usedModels += model.apiId
            usedZdr += zdr
            if (++attempts == 1) throw CommandCodeException.ZdrUnavailable()
            emit(StreamEvent.Delta("retry answer"))
            emit(StreamEvent.Done)
        } }, catalogue = FakeCatalogue(local = catalogueSnapshot(models = listOf(ChatModel.SOL, ChatModel.LUNA))))
        configureKey(vm)
        vm.send("original")
        advanceUntilIdle()
        val user = vm.state.value.messages.single { it.role == "USER" }
        assertEquals("FAILED_ZDR", user.status)
        vm.selectModel(ChatModel.LUNA)
        advanceUntilIdle()
        vm.setZdr(false)
        vm.updateDraft("keep this draft")
        vm.retryMessage(user.id)
        vm.retryMessage(user.id)
        advanceUntilIdle()
        assertEquals(listOf(ChatModel.SOL.apiId, ChatModel.LUNA.apiId), usedModels)
        assertEquals(listOf(true, false), usedZdr)
        assertEquals(1, vm.state.value.messages.count { it.role == "USER" })
        assertEquals("retry answer", chats.completedText)
        assertEquals("keep this draft", vm.state.value.draftText)
        assertEquals(1, vm.state.value.messages.count { it.role == "MODEL_CHANGE" })
    }

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
    fun newChatClearsSelectionMessagesAndErrorWithoutCreatingOrDeletingAConversation() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("conversation") }
        chats.beginTurn("conversation", "existing", ChatModel.SOL)
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow {
            throw IOException("scripted failure")
        } })
        configureKey(viewModel)
        viewModel.openConversation("conversation")
        runCurrent()
        viewModel.send("fails")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.messages.isNotEmpty())
        assertTrue(viewModel.state.value.errorMessage != null)
        val beginTurnsBefore = chats.beginTurnCalls

        viewModel.newChat()

        assertNull(viewModel.state.value.currentConversationId)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(chats.lifecycleEvents.none { it.startsWith("delete:") })
        assertEquals(beginTurnsBefore, chats.beginTurnCalls)
    }

    @Test
    fun newChatDoesNothingWhileAResponseIsStreaming() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("conversation") }
        val viewModel = viewModel(chats, StreamSource { _, _, _, _ -> flow {
            emit(StreamEvent.Delta("partial"))
            awaitCancellation()
        } })
        configureKey(viewModel)
        viewModel.openConversation("conversation")
        runCurrent()
        viewModel.send("hello")
        runCurrent()
        val before = viewModel.state.value

        viewModel.newChat()

        assertEquals(before.currentConversationId, viewModel.state.value.currentConversationId)
        assertEquals(before.messages, viewModel.state.value.messages)
        assertEquals(before.streamingText, viewModel.state.value.streamingText)
        assertTrue(viewModel.state.value.sending)
        viewModel.cancel()
        advanceUntilIdle()
    }

    @Test
    fun amoledLoadsFromSettingsAndPersistsChanges() = runTest(dispatcher) {
        val settings = FakeSettings(amoled = true)
        val viewModel = viewModel(FakeChats(), successfulSource("unused"), settings = settings)
        runCurrent()
        assertTrue(viewModel.state.value.amoled)

        viewModel.setAmoled(false)

        assertFalse(settings.amoled)
        assertFalse(viewModel.state.value.amoled)
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

    @Test
    fun unsupportedOrUnverifiedModelReturnsNoPickerTicketAndTypedError() = runTest(dispatcher) {
        val operations = FakeAttachmentOperations(FakeChats())
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, model("model-1"))),
        )
        val viewModel = viewModel(
            FakeChats(), successfulSource("unused"),
            operations = operations,
            catalogue = catalogue,
        )
        advanceUntilIdle()

        viewModel.selectModel(model("model-1"))

        assertNull(viewModel.requestAttachmentPicker())
        assertEquals(AttachmentFailure.UNVERIFIED_MODEL, viewModel.state.value.attachmentError)
        assertTrue(operations.imported.isEmpty())
    }

    @Test
    fun pickerTicketIsInvalidatedByModelConversationAndUnsavedNewChatChanges() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("other") }
        val operations = FakeAttachmentOperations(chats)
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, model("model-1"))),
        )
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations, catalogue = catalogue)
        advanceUntilIdle()

        val modelTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.selectModel(model("model-1"))
        viewModel.importAttachments(modelTicket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        assertEquals(0, operations.imported.size)
        assertEquals(AttachmentFailure.STALE_SELECTION, viewModel.state.value.attachmentError)

        viewModel.selectModel(ChatModel.SOL)
        val conversationTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.openConversation("other")
        viewModel.importAttachments(conversationTicket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        assertEquals(0, operations.imported.size)
        assertEquals(AttachmentFailure.STALE_SELECTION, viewModel.state.value.attachmentError)

        viewModel.newChat()
        val unsavedTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.newChat()
        viewModel.importAttachments(unsavedTicket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        assertEquals(0, operations.imported.size)
        assertEquals(AttachmentFailure.STALE_SELECTION, viewModel.state.value.attachmentError)
    }

    @Test
    fun changingModelWhileDraftIsPendingRetainsImagesForLaterPreflight() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats)
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, model("model-1"))),
        )
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations, catalogue = catalogue)
        advanceUntilIdle()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())

        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        val retained = viewModel.state.value.pendingAttachments
        assertEquals(1, retained.size)

        viewModel.selectModel(model("model-1"))

        assertEquals(retained, viewModel.state.value.pendingAttachments)
        assertNull(viewModel.requestAttachmentPicker())
        assertEquals(AttachmentFailure.UNVERIFIED_MODEL, viewModel.state.value.attachmentError)
    }

    @Test
    fun navigationInvalidatesAndDiscardsTheFormerDraftBeforeShowingAnotherConversation() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("other") }
        val operations = FakeAttachmentOperations(chats)
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.updateDraft("discard on navigation")
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        val former = viewModel.state.value.pendingAttachments.single()

        viewModel.openConversation("other")
        assertEquals("other", viewModel.state.value.currentConversationId)
        assertEquals("", viewModel.state.value.draftText)
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf(former), operations.discarded)
    }

    @Test
    fun navigationCancelsFormerImportBeforeAcceptingAnewPickerResult() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("other") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = FakeAttachmentOperations(chats).apply {
            importEntered = entered
            importRelease = release
        }
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()
        val formerTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(formerTicket, listOf(Uri.EMPTY))
        runCurrent()
        entered.await()

        viewModel.openConversation("other")
        assertNull(viewModel.requestAttachmentPicker())
        release.complete(Unit)
        advanceUntilIdle()
        val newTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(newTicket, listOf(Uri.EMPTY))
        advanceUntilIdle()

        assertEquals("other", viewModel.state.value.currentConversationId)
        assertEquals(1, viewModel.state.value.pendingAttachments.size)
        assertEquals(1, operations.imported.size)
        assertTrue(operations.discarded.isEmpty())
    }

    @Test
    fun imageOnlySendUsesAttachmentPreparationAndClearsDraftAfterRegistration() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats)
        val viewModel = viewModel(
            chats,
            StreamSource { _, _, messages, _ ->
                operations.streamCalls++
                operations.streamContextImages = messages.sumOf { it.images.size }
                successfulEvents("answer")
            },
            operations = operations,
        )
        configureKey(viewModel)
        advanceUntilIdle()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        viewModel.updateDraft("")

        viewModel.send("")
        advanceUntilIdle()

        assertEquals(
            "attachmentError=${viewModel.state.value.attachmentError}, importing=${viewModel.state.value.importingAttachments}, calls=${operations.importCalls}",
            1,
            operations.prepared.size,
        )
        assertEquals(1, operations.prepared.single().images.size)
        assertEquals(1, operations.preparedContexts.single().last().images.size)
        assertEquals(1, chats.beginTurnCalls)
        assertEquals(1, operations.streamContextImages)
        assertEquals(1, operations.streamCalls)
        assertEquals("", viewModel.state.value.draftText)
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
    }

    @Test
    fun preflightFailureRetainsTextAndImagesAndDoesNotStartStream() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats).apply {
            prepareFailure = AttachmentException(AttachmentFailure.INCOMPATIBLE_HISTORY)
        }
        val viewModel = viewModel(chats, successfulSource("must-not-run"), operations = operations)
        configureKey(viewModel)
        advanceUntilIdle()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        viewModel.updateDraft("preserve me")
        val before = viewModel.state.value.pendingAttachments

        viewModel.send("preserve me")
        advanceUntilIdle()

        assertEquals("preserve me", viewModel.state.value.draftText)
        assertEquals(before, viewModel.state.value.pendingAttachments)
        assertEquals(AttachmentFailure.INCOMPATIBLE_HISTORY, viewModel.state.value.attachmentError)
        assertEquals(0, operations.streamCalls)
        assertEquals(0, chats.beginTurnCalls)
    }

    @Test
    fun persistenceFailureDuringAttachmentPreparationRetainsDraftAndDoesNotStream() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats).apply {
            prepareFailure = IOException("database failure")
        }
        val viewModel = viewModel(chats, successfulSource("must-not-run"), operations = operations)
        configureKey(viewModel)
        advanceUntilIdle()
        viewModel.updateDraft("retain on persistence failure")

        viewModel.send("retain on persistence failure")
        advanceUntilIdle()

        assertEquals("retain on persistence failure", viewModel.state.value.draftText)
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
        assertEquals(0, operations.streamCalls)
        assertEquals(0, chats.beginTurnCalls)
    }

    @Test
    fun draftIsRetainedUntilCommittedTurnIsRegistered() = runTest(dispatcher) {
        val chats = FakeChats()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = FakeAttachmentOperations(chats).apply {
            prepareEntered = entered
            prepareRelease = release
        }
        val viewModel = viewModel(chats, successfulSource("answer"), operations = operations)
        configureKey(viewModel)
        viewModel.updateDraft("commit me")
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        viewModel.send("commit me")
        runCurrent()
        entered.await()

        assertEquals("commit me", viewModel.state.value.draftText)
        assertEquals(1, viewModel.state.value.pendingAttachments.size)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.draftText)
    }

    @Test
    fun sendReservesDraftAgainstImmediateMutationBeforeCoroutineStarts() = runTest(dispatcher) {
        val chats = FakeChats()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = FakeAttachmentOperations(chats).apply {
            prepareEntered = entered
            prepareRelease = release
        }
        val viewModel = viewModel(chats, successfulSource("answer"), operations = operations)
        configureKey(viewModel)
        viewModel.updateDraft("first")

        viewModel.send("first")
        viewModel.updateDraft("second")

        assertEquals("first", viewModel.state.value.draftText)

        release.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun cancellationAfterCommittedRegistrationInterruptsPendingTurn() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats)
        val viewModel = viewModel(
            chats,
            StreamSource { _, _, _, _ -> flow {
                emit(StreamEvent.Delta("partial"))
                awaitCancellation()
            } },
            operations = operations,
        )
        configureKey(viewModel)
        viewModel.updateDraft("cancel me")
        viewModel.send("cancel me")
        runCurrent()

        viewModel.cancel()
        advanceUntilIdle()

        assertEquals("partial", chats.interruptedText)
        assertFalse(viewModel.state.value.sending)
        assertEquals("", viewModel.state.value.draftText)
    }

    @Test
    fun modelSelectionIsFrozenUntilImportSettles() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats)
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, model("model-1"))),
        )
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations, catalogue = catalogue)
        advanceUntilIdle()
        val firstTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(firstTicket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        val previous = viewModel.state.value.pendingAttachments.single()

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        operations.importEntered = entered
        operations.importRelease = release
        val staleTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(staleTicket, listOf(Uri.EMPTY))
        runCurrent()
        entered.await()

        viewModel.selectModel(model("model-1"))
        assertEquals(ChatModel.SOL, viewModel.state.value.selectedModel)
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(previous, operations.imported.last()), viewModel.state.value.pendingAttachments)
        assertTrue(operations.discarded.isEmpty())
    }

    @Test
    fun importStopsAtTwentySavedPlusPendingImagesAndKeepsEarlierSelection() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("full") }
        chats.conversations.value = chats.conversations.value.map {
            if (it.id == "full") it.copy(attachmentCount = 19) else it
        }
        val operations = FakeAttachmentOperations(chats)
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()
        viewModel.openConversation("full")
        runCurrent()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())

        viewModel.importAttachments(
            ticket,
            listOf(Uri.EMPTY, Uri.EMPTY),
        )
        advanceUntilIdle()

        assertEquals(
            "attachmentError=${viewModel.state.value.attachmentError}, importing=${viewModel.state.value.importingAttachments}, calls=${operations.importCalls}",
            1,
            operations.imported.size,
        )
        assertEquals(1, viewModel.state.value.pendingAttachments.size)
        assertEquals(AttachmentFailure.TOO_MANY, viewModel.state.value.attachmentError)
    }

    @Test
    fun attachmentInitialisationFailureDisablesAttachmentActionsWithTypedSafeError() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats).apply {
            initialiseFailure = AttachmentException(AttachmentFailure.KEY_UNAVAILABLE)
        }
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertEquals(AttachmentFailure.KEY_UNAVAILABLE, viewModel.state.value.attachmentError)
        assertNull(viewModel.requestAttachmentPicker())
        assertTrue(operations.imported.isEmpty())
    }

    @Test
    fun attachmentActionsRemainDisabledUntilInitialisationCompletes() = runTest(dispatcher) {
        val chats = FakeChats()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = FakeAttachmentOperations(chats).apply {
            initialiseEntered = entered
            initialiseRelease = release
        }
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        runCurrent()
        entered.await()

        assertTrue(viewModel.state.value.loading)
        assertNull(viewModel.requestAttachmentPicker())

        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.requestAttachmentPicker() != null)
    }

    @Test
    fun cleanupWarningClearsDeletedConversationWithoutClaimingReclaimedBytes() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("conversation") }
        val operations = FakeAttachmentOperations(chats).apply {
            deleteFailure = AttachmentException(AttachmentFailure.CLEANUP, databaseDeleted = true)
        }
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()
        viewModel.openConversation("conversation")
        runCurrent()

        viewModel.deleteConversation("conversation")
        advanceUntilIdle()

        assertNull(viewModel.state.value.currentConversationId)
        assertEquals(AttachmentFailure.CLEANUP, viewModel.state.value.attachmentError)
        assertEquals(listOf("conversation"), operations.deleted)
        assertTrue(!viewModel.state.value.errorMessage.orEmpty().contains("reclaim", ignoreCase = true))
        assertTrue(!viewModel.state.value.errorMessage.orEmpty().contains("bytes", ignoreCase = true))
    }

    @Test
    fun catalogueRefreshDuringImportDefersSelectionAndSettlesImport() = runTest(dispatcher) {
        val chats = FakeChats()
        val refreshEntered = CompletableDeferred<Unit>()
        val refreshRelease = CompletableDeferred<Unit>()
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, ChatModel.LUNA)),
            remote = catalogueSnapshot(version = "remote", models = listOf(ChatModel.SOL)),
        ).apply {
            refreshBlock = {
                refreshEntered.complete(Unit)
                refreshRelease.await()
                remote
            }
        }
        val importEntered = CompletableDeferred<Unit>()
        val importRelease = CompletableDeferred<Unit>()
        val operations = FakeAttachmentOperations(chats).apply {
            this.importEntered = importEntered
            this.importRelease = importRelease
        }
        val viewModel = viewModel(chats, successfulSource("unused"), catalogue = catalogue, operations = operations)
        runCurrent()
        refreshEntered.await()

        viewModel.selectModel(ChatModel.LUNA)
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        runCurrent()
        importEntered.await()

        refreshRelease.complete(Unit)
        runCurrent()

        assertEquals(ChatModel.LUNA, viewModel.state.value.selectedModel)
        assertTrue(viewModel.state.value.importingAttachments)

        importRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChatModel.SOL, viewModel.state.value.selectedModel)
        assertFalse(viewModel.state.value.importingAttachments)
        assertEquals(1, viewModel.state.value.pendingAttachments.size)
    }

    @Test
    fun catalogueRefreshDuringPrepareDefersSelectionAndClearsCommittedDraft() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("conversation") }
        val refreshEntered = CompletableDeferred<Unit>()
        val refreshRelease = CompletableDeferred<Unit>()
        val catalogue = FakeCatalogue(
            local = catalogueSnapshot(models = listOf(ChatModel.SOL, ChatModel.LUNA)),
            remote = catalogueSnapshot(version = "remote", models = listOf(ChatModel.SOL)),
        ).apply {
            refreshBlock = {
                refreshEntered.complete(Unit)
                refreshRelease.await()
                remote
            }
        }
        val operations = FakeAttachmentOperations(chats)
        val viewModel = viewModel(
            chats,
            successfulSource("answer"),
            catalogue = catalogue,
            operations = operations,
        )
        runCurrent()
        refreshEntered.await()
        viewModel.openConversation("conversation")
        runCurrent()
        viewModel.selectModel(ChatModel.LUNA)
        runCurrent()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        viewModel.updateDraft("preserve until commit")

        val prepareEntered = CompletableDeferred<Unit>()
        val prepareRelease = CompletableDeferred<Unit>()
        operations.prepareEntered = prepareEntered
        operations.prepareRelease = prepareRelease
        configureKey(viewModel)
        viewModel.send("preserve until commit")
        runCurrent()
        prepareEntered.await()

        refreshRelease.complete(Unit)
        runCurrent()

        assertEquals(ChatModel.LUNA, viewModel.state.value.selectedModel)
        assertEquals("preserve until commit", viewModel.state.value.draftText)
        assertEquals(1, viewModel.state.value.pendingAttachments.size)

        prepareRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChatModel.SOL, viewModel.state.value.selectedModel)
        assertEquals("", viewModel.state.value.draftText)
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
    }

    @Test
    fun deletionReservesCurrentDraftAndBlocksConcurrentEntriesUntilComplete() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("conversation") }
        val operations = FakeAttachmentOperations(chats).apply {
            deleteEntered = CompletableDeferred()
            deleteRelease = CompletableDeferred()
        }
        val viewModel = viewModel(chats, successfulSource("answer"), operations = operations)
        configureKey(viewModel)
        advanceUntilIdle()
        viewModel.openConversation("conversation")
        runCurrent()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        advanceUntilIdle()
        viewModel.updateDraft("delete me")

        viewModel.deleteConversation("conversation")
        runCurrent()
        operations.deleteEntered!!.await()

        assertNull(viewModel.requestAttachmentPicker())
        viewModel.send("escape")
        assertEquals(0, operations.prepared.size)
        assertEquals(1, operations.imported.size)
        assertEquals("delete me", viewModel.state.value.draftText)

        operations.deleteRelease!!.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.currentConversationId)
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
        assertEquals(0, operations.prepared.size)
    }

    @Test
    fun deletingNewConversationWaitsForSendRegistrationBeforeServiceDelete() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats).apply {
            prepareEntered = CompletableDeferred()
            prepareRelease = CompletableDeferred()
            deleteEntered = CompletableDeferred()
        }
        val viewModel = viewModel(chats, successfulSource("answer"), operations = operations)
        configureKey(viewModel)
        advanceUntilIdle()

        viewModel.send("unsaved conversation")
        runCurrent()
        operations.prepareEntered!!.await()
        assertEquals("conversation", chats.conversations.value.single().id)

        viewModel.deleteConversation("conversation")
        runCurrent()

        assertFalse(operations.deleteEntered!!.isCompleted)

        operations.prepareRelease!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("prepare-return", "delete"), operations.events)
        assertNull(viewModel.state.value.currentConversationId)
    }

    @Test
    fun deletingFormerConversationWaitsForNavigationCancelledImportCleanup() = runTest(dispatcher) {
        val chats = FakeChats().apply {
            addConversation("conversation")
            addConversation("other")
        }
        val operations = FakeAttachmentOperations(chats).apply {
            importEntered = CompletableDeferred()
            importRelease = CompletableDeferred()
            importIgnoresCancellation = true
            deleteEntered = CompletableDeferred()
        }
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()
        viewModel.openConversation("conversation")
        runCurrent()
        val ticket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(ticket, listOf(Uri.EMPTY))
        runCurrent()
        operations.importEntered!!.await()

        viewModel.openConversation("other")
        viewModel.deleteConversation("conversation")
        runCurrent()

        assertFalse(operations.deleteEntered!!.isCompleted)

        operations.importRelease!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(operations.events.indexOf("discard") < operations.events.indexOf("delete"))
        assertTrue(chats.conversations.value.none { it.id == "conversation" })
        assertEquals("other", viewModel.state.value.currentConversationId)
    }

    @Test
    fun preDatabaseCleanupFailurePreservesCurrentConversationAndDraft() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("conversation") }
        val operations = FakeAttachmentOperations(chats).apply {
            deleteFailure = AttachmentException(AttachmentFailure.CLEANUP, databaseDeleted = false)
        }
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()
        viewModel.openConversation("conversation")
        runCurrent()
        viewModel.updateDraft("keep on failure")

        viewModel.deleteConversation("conversation")
        advanceUntilIdle()

        assertEquals("conversation", viewModel.state.value.currentConversationId)
        assertEquals("keep on failure", viewModel.state.value.draftText)
        assertEquals("Conversation could not be deleted. Try again.", viewModel.state.value.errorMessage)
    }

    @Test
    fun cancelledImportCleanupSettlesBeforeNewPickerIsAccepted() = runTest(dispatcher) {
        val chats = FakeChats().apply { addConversation("other") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = FakeAttachmentOperations(chats).apply {
            importEntered = entered
            importRelease = release
            importIgnoresCancellation = true
        }
        val viewModel = viewModel(chats, successfulSource("unused"), operations = operations)
        advanceUntilIdle()
        val formerTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(formerTicket, listOf(Uri.EMPTY))
        runCurrent()
        entered.await()

        viewModel.openConversation("other")

        assertTrue(viewModel.state.value.importingAttachments)
        assertNull(viewModel.requestAttachmentPicker())

        release.complete(Unit)
        advanceUntilIdle()

        val newTicket = requireNotNull(viewModel.requestAttachmentPicker())
        viewModel.importAttachments(newTicket, listOf(Uri.EMPTY))
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.pendingAttachments.size)
        assertEquals(2, operations.imported.size)
        assertEquals(1, operations.discarded.size)
        assertTrue(operations.discarded.single().id != viewModel.state.value.pendingAttachments.single().id)
    }

    @Test
    fun preflightIOExceptionUsesNonStreamingSafeMessage() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats).apply {
            prepareFailure = IOException("database failure")
        }
        val viewModel = viewModel(chats, successfulSource("must-not-run"), operations = operations)
        configureKey(viewModel)
        advanceUntilIdle()

        viewModel.send("before stream")
        advanceUntilIdle()

        assertEquals("The request could not be completed. Try again.", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.errorMessage.orEmpty().contains("Partial response"))
        assertFalse(viewModel.state.value.errorMessage.orEmpty().contains("Network stream"))
        assertEquals(0, operations.streamCalls)
    }

    @Test
    fun savedSystemPromptLoadsAtStartupAndLeadsTheFirstAndLatestRequests() = runTest(dispatcher) {
        val requests = mutableListOf<List<ApiMessage>>()
        val chats = FakeChats()
        val prompts = FakeSystemPromptStore(stored = "be kind")
        val vm = viewModel(chats, StreamSource { _, _, messages, _ ->
            requests += messages
            successfulEvents("answer")
        }, prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()

        assertEquals("be kind", vm.state.value.systemPrompt)
        assertTrue(vm.state.value.systemPromptLoaded)

        vm.send("first")
        advanceUntilIdle()
        vm.saveSystemPrompt("beta")
        advanceUntilIdle()
        vm.send("second")
        advanceUntilIdle()

        assertEquals("system", requests[0].first().role)
        assertEquals("be kind", requests[0].first().content)
        assertEquals("system", requests[1].first().role)
        assertEquals("beta", requests[1].first().content)
        assertEquals(listOf("beta"), prompts.savedValues)
        assertTrue(vm.state.value.messages.none { it.content.contains("be kind") })
    }

    @Test
    fun blankSystemPromptSaveNormalisesToEmptyAndOmitsSystemMessage() = runTest(dispatcher) {
        val requests = mutableListOf<List<ApiMessage>>()
        val prompts = FakeSystemPromptStore()
        val vm = viewModel(FakeChats(), StreamSource { _, _, messages, _ ->
            requests += messages
            successfulEvents("answer")
        }, prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()

        vm.saveSystemPrompt("   \n")
        advanceUntilIdle()

        assertTrue(vm.state.value.systemPromptLoaded)
        assertEquals("", vm.state.value.systemPrompt)
        assertEquals("", prompts.stored)
        vm.send("hello")
        advanceUntilIdle()
        assertTrue(requests.single().all { it.role != "system" })
    }

    @Test
    fun unreadableSavedPromptBlocksSendsUntilASuccessfulSaveHealsIt() = runTest(dispatcher) {
        val chats = FakeChats()
        val prompts = FakeSystemPromptStore(readFailure = true)
        val vm = viewModel(chats, successfulSource("must not run"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()

        assertFalse(vm.state.value.systemPromptLoaded)
        assertEquals(SystemPromptFeedback.LOAD_FAILED, vm.state.value.systemPromptFeedback)

        vm.send("blocked")
        advanceUntilIdle()

        assertEquals(0, chats.beginTurnCalls)
        assertFalse(vm.state.value.sending)
        assertTrue(vm.state.value.errorMessage!!.contains("system prompt", ignoreCase = true))

        vm.saveSystemPrompt("healed")
        advanceUntilIdle()

        assertTrue(vm.state.value.systemPromptLoaded)
        assertEquals(SystemPromptFeedback.SAVED, vm.state.value.systemPromptFeedback)
        assertNull(vm.state.value.errorMessage)
        prompts.readFailure = false
        val chatRequests = mutableListOf<List<ApiMessage>>()
        val sending = FakeChats()
        val secondVm = viewModel(sending, StreamSource { _, _, messages, _ ->
            chatRequests += messages
            successfulEvents("ok")
        }, prompts = prompts)
        configureKey(secondVm)
        advanceUntilIdle()
        assertTrue(secondVm.state.value.systemPromptLoaded)
        secondVm.send("now works")
        advanceUntilIdle()
        assertEquals("system", chatRequests.single().first().role)
        assertEquals("healed", chatRequests.single().first().content)
    }

    @Test
    fun promptLoadFailureBannerSurvivesOpeningConversationsAndNewChat() = runTest(dispatcher) {
        val chats = FakeChats().apply {
            addConversation("conversation")
            addConversation("other")
        }
        val prompts = FakeSystemPromptStore(readFailure = true)
        val vm = viewModel(chats, successfulSource("unused"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()
        assertFalse(vm.state.value.systemPromptLoaded)

        vm.openConversation("conversation")
        runCurrent()
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("system prompt", ignoreCase = true))

        vm.newChat()
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("system prompt", ignoreCase = true))

        vm.openConversation("other")
        runCurrent()
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("system prompt", ignoreCase = true))
        assertEquals(0, vm.state.value.messages.size)
    }

    @Test
    fun unrelatedFailureWhilePromptUnavailableCannotReplaceTheBlockingBanner() = runTest(dispatcher) {
        val chats = FakeChats().apply {
            addConversation("other")
            deleteFailures += "other"
        }
        val prompts = FakeSystemPromptStore(readFailure = true)
        val vm = viewModel(chats, successfulSource("unused"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()
        assertFalse(vm.state.value.systemPromptLoaded)
        assertEquals(SYSTEM_PROMPT_UNAVAILABLE_MESSAGE, vm.state.value.errorMessage)

        vm.deleteConversation("other")
        advanceUntilIdle()

        assertEquals(SYSTEM_PROMPT_UNAVAILABLE_MESSAGE, vm.state.value.errorMessage)
    }

    @Test
    fun unrelatedSuccessWhilePromptUnavailableCannotClearTheBlockingBanner() = runTest(dispatcher) {
        val prompts = FakeSystemPromptStore(readFailure = true)
        val vm = viewModel(FakeChats(), successfulSource("unused"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()
        assertFalse(vm.state.value.systemPromptLoaded)
        assertEquals(SYSTEM_PROMPT_UNAVAILABLE_MESSAGE, vm.state.value.errorMessage)

        vm.clearApiKey()
        advanceUntilIdle()

        assertFalse(vm.state.value.systemPromptLoaded)
        assertEquals(SYSTEM_PROMPT_UNAVAILABLE_MESSAGE, vm.state.value.errorMessage)
    }

    @Test
    fun healingClearsTheBlockingBannerWithoutLeavingStaleUnrelatedText() = runTest(dispatcher) {
        val chats = FakeChats().apply {
            addConversation("other")
            deleteFailures += "other"
        }
        val prompts = FakeSystemPromptStore(readFailure = true)
        val vm = viewModel(chats, successfulSource("unused"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()
        assertEquals(SYSTEM_PROMPT_UNAVAILABLE_MESSAGE, vm.state.value.errorMessage)

        // An unrelated failure during the blocked window must not stash stale text
        // that would outlive a later successful heal.
        vm.deleteConversation("other")
        advanceUntilIdle()
        assertEquals(SYSTEM_PROMPT_UNAVAILABLE_MESSAGE, vm.state.value.errorMessage)

        vm.saveSystemPrompt("healed")
        advanceUntilIdle()

        assertTrue(vm.state.value.systemPromptLoaded)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun sendAttemptWhilePromptUnavailableIsNeverSilentlyDropped() = runTest(dispatcher) {
        val chats = FakeChats()
        val prompts = FakeSystemPromptStore(readFailure = true)
        val vm = viewModel(chats, successfulSource("must not run"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()
        assertFalse(vm.state.value.systemPromptLoaded)

        vm.updateDraft("ready to send")
        vm.send("ready to send")
        advanceUntilIdle()

        assertEquals(0, chats.beginTurnCalls)
        assertFalse(vm.state.value.sending)
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("system prompt", ignoreCase = true))
        assertEquals("ready to send", vm.state.value.draftText)
    }

    @Test
    fun combinedAttachmentAndPromptStartupFailuresPrioritiseThePromptBanner() = runTest(dispatcher) {
        val chats = FakeChats()
        val operations = FakeAttachmentOperations(chats).apply {
            initialiseFailure = AttachmentException(AttachmentFailure.KEY_UNAVAILABLE)
        }
        val prompts = FakeSystemPromptStore(readFailure = true)
        val vm = viewModel(chats, successfulSource("unused"), operations = operations, prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()

        assertFalse(vm.state.value.systemPromptLoaded)
        assertEquals(AttachmentFailure.KEY_UNAVAILABLE, vm.state.value.attachmentError)
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("system prompt", ignoreCase = true))
    }

    @Test
    fun failedPromptSaveKeepsLastSuccessfulValueAndReportsGenericFeedback() = runTest(dispatcher) {
        val prompts = FakeSystemPromptStore(stored = "alpha")
        val vm = viewModel(FakeChats(), successfulSource("unused"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()
        prompts.saveFailure = IllegalStateException("secret storage detail")

        vm.saveSystemPrompt("beta")
        advanceUntilIdle()

        assertEquals("alpha", vm.state.value.systemPrompt)
        assertEquals("alpha", prompts.stored)
        assertEquals(SystemPromptFeedback.SAVE_FAILED, vm.state.value.systemPromptFeedback)
        assertFalse(vm.state.value.systemPromptFeedback.toString().contains("secret"))

        prompts.saveFailure = null
        vm.saveSystemPrompt("  beta  ")
        advanceUntilIdle()
        assertEquals("  beta  ", vm.state.value.systemPrompt)
        assertEquals("  beta  ", prompts.stored)
    }

    @Test
    fun failedPromptClearKeepsLastSuccessfulValueUntilClearSucceeds() = runTest(dispatcher) {
        val prompts = FakeSystemPromptStore(stored = "alpha")
        val vm = viewModel(FakeChats(), successfulSource("unused"), prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()
        prompts.clearFailure = IllegalStateException("secret clear detail")

        vm.clearSystemPrompt()
        advanceUntilIdle()

        assertEquals("alpha", vm.state.value.systemPrompt)
        assertEquals("alpha", prompts.stored)
        assertEquals(SystemPromptFeedback.CLEAR_FAILED, vm.state.value.systemPromptFeedback)

        prompts.clearFailure = null
        vm.clearSystemPrompt()
        advanceUntilIdle()
        assertEquals("", vm.state.value.systemPrompt)
        assertEquals("", prompts.stored)
        assertEquals(SystemPromptFeedback.CLEARED, vm.state.value.systemPromptFeedback)
    }

    @Test
    fun inFlightRequestKeepsItsStartTimePromptSnapshotWhenSettingsChangeDuringStreaming() = runTest(dispatcher) {
        val requests = mutableListOf<List<ApiMessage>>()
        val prompts = FakeSystemPromptStore(stored = "alpha")
        val vm = viewModel(FakeChats(), StreamSource { _, _, messages, _ -> flow {
            requests += messages
            emit(StreamEvent.Delta("partial"))
            awaitCancellation()
        } }, prompts = prompts)
        configureKey(vm)
        advanceUntilIdle()

        vm.send("in flight")
        runCurrent()
        assertTrue(vm.state.value.sending)

        vm.saveSystemPrompt("beta")
        advanceUntilIdle()

        assertEquals("beta", vm.state.value.systemPrompt)
        assertEquals(listOf("system", "user"), requests.single().map { it.role })
        assertEquals("alpha", requests.single().first().content)

        vm.cancel()
        advanceUntilIdle()
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
        settings: SettingsStore = FakeSettings(),
        apiKeyCopier: (CharArray) -> CharArray = { it.copyOf() },
        operations: FakeAttachmentOperations = FakeAttachmentOperations(chats),
        prompts: SystemPromptStore = FakeSystemPromptStore(),
    ) = AppViewModel(
        secrets = keys,
        settings = settings,
        chats = chats,
        streamSource = source,
        modelCatalogue = catalogue,
        quota = quota,
        attachmentOperations = operations,
        elapsedMillis = elapsedMillis,
        apiKeyCopier = apiKeyCopier,
        systemPromptStore = prompts,
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

    private class FakeSettings(
        override var zdr: Boolean = true,
        override var amoled: Boolean = false,
    ) : SettingsStore {
    }

    private class FakeSystemPromptStore(
        var stored: String = "",
        var readFailure: Boolean = false,
        var saveFailure: Exception? = null,
        var clearFailure: Exception? = null,
    ) : SystemPromptStore {
        val savedValues = mutableListOf<String>()
        var clearCalls = 0

        override fun read(): String {
            if (readFailure) throw com.commandcode.chat.data.security.PromptRecoveryRequired()
            return stored
        }

        override fun save(value: String) {
            saveFailure?.let { throw it }
            val effective = effectiveSystemPrompt(value)
            stored = effective
            savedValues += effective
        }

        override fun clear() {
            clearFailure?.let { throw it }
            clearCalls += 1
            stored = ""
        }
    }

    private class FakeAttachmentOperations(
        private val chats: FakeChats,
    ) : AttachmentOperations {
        var initialiseFailure: Exception? = null
        var initialiseEntered: CompletableDeferred<Unit>? = null
        var initialiseRelease: CompletableDeferred<Unit>? = null
        var prepareFailure: Exception? = null
        var importFailure: Exception? = null
        var discardFailure: Exception? = null
        var deleteFailure: Exception? = null
        var importEntered: CompletableDeferred<Unit>? = null
        var importRelease: CompletableDeferred<Unit>? = null
        var importIgnoresCancellation = false
        var deleteEntered: CompletableDeferred<Unit>? = null
        var deleteRelease: CompletableDeferred<Unit>? = null
        var prepareEntered: CompletableDeferred<Unit>? = null
        var prepareRelease: CompletableDeferred<Unit>? = null
        val imported = mutableListOf<ImageAttachment>()
        val discarded = mutableListOf<ImageAttachment>()
        val prepared = mutableListOf<PreparedRequest>()
        val preparedContexts = mutableListOf<List<ApiMessage>>()
        val deleted = mutableListOf<String>()
        val events = mutableListOf<String>()
        var importCalls = 0
        var streamCalls = 0
        var streamContextImages = 0
        private var id = 0

        override suspend fun initialise() {
            initialiseEntered?.complete(Unit)
            initialiseRelease?.await()
            initialiseFailure?.let { throw it }
        }

        override suspend fun importImage(uri: Uri, modelId: String): ImageAttachment {
            importCalls++
            importFailure?.let { throw it }
            importEntered?.complete(Unit)
            if (importIgnoresCancellation) {
                withContext(NonCancellable) { importRelease?.await() }
            } else {
                importRelease?.await()
            }
            val image = ImageAttachment(
                id = "00000000-0000-0000-0000-${(++id).toString().padStart(12, '0')}",
                displayName = "Image.png",
                mimeType = "image/png",
                originalBytes = 4,
                encryptedBytes = 37,
                width = 2,
                height = 2,
            )
            imported += image
            events += "import-return"
            return image
        }

        override suspend fun discard(images: List<ImageAttachment>) {
            discardFailure?.let { throw it }
            discarded += images
            events += "discard"
        }

        override suspend fun prepareTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            images: List<ImageAttachment>,
            systemPrompt: String,
        ): PreparedAttachmentTurn {
            prepareFailure?.let { throw it }
            val history = conversationId?.let { chats.messagesSnapshot(it) }.orEmpty()
            val context = history.mapNotNull { message ->
                when (message.role) {
                    "USER" -> ApiMessage(
                        "user",
                        message.content,
                        message.attachments.map { com.commandcode.chat.data.commandcode.ApiImage(it.mimeType, it.originalBytes) {} },
                    )
                    "ASSISTANT" -> ApiMessage("assistant", message.content)
                    else -> null
                }.takeIf { message.content.isNotEmpty() }
            }.toMutableList()
            context += ApiMessage(
                "user",
                text,
                images.map { com.commandcode.chat.data.commandcode.ApiImage(it.mimeType, it.originalBytes) {} },
            )
            val effective = effectiveSystemPrompt(systemPrompt)
            val messages = if (effective.isEmpty()) context
                else listOf(ApiMessage("system", effective)) + context
            val pending = chats.beginTurn(conversationId, text, model, images, effective)
            prepared += PreparedRequest(text, model, images)
            preparedContexts += messages
            prepareEntered?.complete(Unit)
            prepareRelease?.await()
            events += "prepare-return"
            return PreparedAttachmentTurn(pending, messages)
        }

        override suspend fun deleteConversation(id: String) {
            deleted += id
            events += "delete"
            deleteEntered?.complete(Unit)
            deleteRelease?.await()
            deleteFailure?.let { failure ->
                if (failure is AttachmentException &&
                    failure.kind == AttachmentFailure.CLEANUP &&
                    failure.databaseDeleted == true
                ) {
                    chats.deleteConversation(id)
                }
                throw failure
            }
            chats.deleteConversation(id)
        }

        override suspend fun preview(id: String, maxEdge: Int): Bitmap =
            throw UnsupportedOperationException("preview is not used by AppViewModel tests")

        override suspend fun prepareRetry(conversationId: String, userMessageId: String, model: ChatModel, systemPrompt: String): PreparedAttachmentTurn {
            val user = chats.messagesSnapshot(conversationId).single { it.id == userMessageId }
            val context = listOf(ApiMessage("user", user.content))
            val effective = effectiveSystemPrompt(systemPrompt)
            val messages = if (effective.isEmpty()) context
                else listOf(ApiMessage("system", effective)) + context
            return PreparedAttachmentTurn(chats.retryFailedTurn(userMessageId, model, effective), messages)
        }

        override suspend fun deleteFailedMessage(conversationId: String, userMessageId: String) = chats.deleteFailedTurn(userMessageId)

        data class PreparedRequest(
            val text: String,
            val model: ChatModel,
            val images: List<ImageAttachment>,
        )
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
        var beginTurnCalls = 0
        private var turnCount = 0
        private val deletedConversations = mutableSetOf<String>()
        private val messageOwners = mutableMapOf<String, String>()

        override fun observeConversations(): Flow<List<Conversation>> = conversations
        override fun observeMessages(conversationId: String): Flow<List<Message>> = messages(conversationId)
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = messages(conversationId).value

        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
            systemPrompt: String,
        ): PendingTurn {
            beginTurnCalls += 1
            val id = conversationId ?: "conversation"
            turnCount += 1
            val userId = "user-$turnCount"
            val assistantId = "assistant-$turnCount"
            messageOwners[userId] = id
            messageOwners[assistantId] = id
            if (conversations.value.none { it.id == id }) addConversation(id)
            val existing = messages(id).value
            messages(id).value = existing +
                Message(userId, id, "USER", text, null, turnCount * 2L, "COMPLETE", attachments) +
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

        override suspend fun failZdrTurn(assistantMessageId: String) {
            val rows = messageFlows.values.single { flow -> flow.value.any { it.id == assistantMessageId } }
            val index = rows.value.indexOfFirst { it.id == assistantMessageId }
            rows.value = rows.value.mapIndexed { i, m -> if (i == index || i == index - 1) m.copy(status = "FAILED_ZDR") else m }
        }

        override suspend fun retryFailedTurn(userMessageId: String, model: ChatModel, systemPrompt: String): PendingTurn {
            val rows = messageFlows.values.single { flow -> flow.value.any { it.id == userMessageId } }
            val index = rows.value.indexOfFirst { it.id == userMessageId }
            val user = rows.value[index]
            check(user.status == "FAILED_ZDR")
            val reply = rows.value[index + 1].copy(id = "retry-${++turnCount}", status = "PENDING", modelId = model.apiId, replyToMessageId = user.id)
            messageOwners[reply.id] = user.conversationId
            rows.value = rows.value.mapIndexed { i, m -> when(i) { index -> m.copy(status = "COMPLETE"); index + 1 -> reply; else -> m } }
            return PendingTurn(user.conversationId, user.id, reply.id)
        }

        override suspend fun backfillInitialModel(conversationId: String) = Unit

        override suspend fun deleteFailedTurn(userMessageId: String) {
            val rows = messageFlows.values.single { flow -> flow.value.any { it.id == userMessageId } }
            val index = rows.value.indexOfFirst { it.id == userMessageId }
            check(rows.value[index].status == "FAILED_ZDR")
            rows.value = rows.value.filterIndexed { i, _ -> i != index && i != index + 1 }
        }

        override suspend fun changeConversationModel(conversationId: String, model: ChatModel) {
            val conversation = conversations.value.single { it.id == conversationId }
            if (conversation.defaultModelId == model.apiId) return
            conversations.value = conversations.value.map { if (it.id == conversationId) it.copy(defaultModelId = model.apiId) else it }
            val rows = messages(conversationId)
            if (rows.value.isNotEmpty()) rows.value += Message("model-event-${rows.value.size}", conversationId, "MODEL_CHANGE", model.displayName, model.apiId, rows.value.size + 1L, "COMPLETE")
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
