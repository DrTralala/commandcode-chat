package com.commandcode.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.AppContainer
import com.commandcode.chat.ChatStore
import com.commandcode.chat.SettingsStore
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.CommandCodeException
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.security.KeyRecoveryRequired
import com.commandcode.chat.data.security.SecureStoragePersistenceFailure
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.data.service.ServiceException
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BudgetFreshness { LIVE, STALE, UNAVAILABLE }

data class BudgetUiState(
    val snapshot: QuotaSnapshot? = null,
    val freshness: BudgetFreshness = BudgetFreshness.UNAVAILABLE,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
)

data class AppUiState(
    val loading: Boolean = true,
    val recoveryMessage: String? = null,
    val keyConfigured: Boolean = false,
    val zdr: Boolean = true,
    val models: List<ChatModel> = listOf(ChatModel.SOL),
    val selectedModel: ChatModel = ChatModel.SOL,
    val conversations: List<Conversation> = emptyList(),
    val currentConversationId: String? = null,
    val messages: List<Message> = emptyList(),
    val activeConversationId: String? = null,
    val activeAssistantMessageId: String? = null,
    val streamingText: String = "",
    val sending: Boolean = false,
    val errorMessage: String? = null,
    val budget: BudgetUiState = BudgetUiState(),
) {
    val visibleMessages: List<Message>
        get() = if (currentConversationId == activeConversationId && activeAssistantMessageId != null) {
            messages.filterNot { it.id == activeAssistantMessageId }
        } else {
            messages
        }

    val visibleStreamingText: String?
        get() = streamingText.takeIf {
            it.isNotEmpty() && currentConversationId == activeConversationId
        }
}

class AppViewModel(
    private val secrets: ApiKeyStore,
    private val settings: SettingsStore,
    private val chats: ChatStore,
    private val streamSource: StreamSource,
    private val modelCatalogue: ModelCatalogueSource,
    private val quota: QuotaSource,
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val apiKeyCopier: (CharArray) -> CharArray = { it.copyOf() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState(zdr = settings.zdr))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var messageCollection: Job? = null
    private var sendJob: Job? = null
    private var quotaJob: Job? = null
    private val keyMutationMutex = Mutex()
    private var activeTurn: ActiveTurn? = null
    private var sendTargetConversationId: String? = null

    init {
        viewModelScope.launch {
            var recoveryMessage: String? = null
            val configured = try {
                secrets.readApiKey()?.let { key ->
                    try { key.isNotEmpty() } finally { key.fill('\u0000') }
                } ?: false
            } catch (_: KeyRecoveryRequired) {
                recoveryMessage = API_KEY_RECOVERY_MESSAGE
                false
            }
            val localModels = try {
                modelCatalogue.loadLocal().models.requireSolModel()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                listOf(ChatModel.SOL)
            }
            val cachedQuota = try {
                quota.loadCached()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutableState.value = mutableState.value.copy(
                loading = false,
                recoveryMessage = recoveryMessage,
                keyConfigured = configured,
                models = localModels,
                selectedModel = localModels.firstOrNull {
                    it.apiId == mutableState.value.selectedModel.apiId
                } ?: ChatModel.SOL,
                budget = cachedQuota?.takeIf { configured }?.let {
                    BudgetUiState(snapshot = it, freshness = BudgetFreshness.STALE)
                } ?: BudgetUiState(),
            )
            refreshModels()
            if (configured && recoveryMessage == null) refreshQuota()
        }
        viewModelScope.launch {
            chats.observeConversations().collect { conversations ->
                mutableState.value = mutableState.value.copy(conversations = conversations)
            }
        }
    }

    fun saveApiKey(value: CharArray) {
        val protectedCopy = apiKeyCopier(value)
        value.fill('\u0000')
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                keyMutationMutex.withLock {
                    try {
                        require(protectedCopy.isNotEmpty()) { "API key cannot be empty" }
                        quotaJob?.cancelAndJoin()
                        try {
                            quota.clear()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.value = mutableState.value.copy(
                                budget = mutableState.value.budget.copy(
                                    refreshing = false,
                                    errorMessage = QUOTA_CACHE_CLEAR_FAILURE_MESSAGE,
                                ),
                            )
                            return@withLock
                        }
                        mutableState.value = mutableState.value.copy(
                            budget = BudgetUiState(),
                        )
                        secrets.saveApiKey(protectedCopy)
                        mutableState.value = mutableState.value.copy(
                            keyConfigured = true,
                            errorMessage = null,
                        )
                        refreshQuota()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        mutableState.value = mutableState.value.copy(errorMessage = safeMessage(error))
                    }
                }
            } finally {
                protectedCopy.fill('\u0000')
            }
        }
    }

    fun clearApiKey() {
        cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            keyMutationMutex.withLock {
                try {
                    secrets.clearApiKey()
                    quotaJob?.cancelAndJoin()
                    mutableState.value = mutableState.value.copy(
                        keyConfigured = false,
                        budget = BudgetUiState(),
                    )
                    try {
                        quota.clear()
                        mutableState.value = mutableState.value.copy(errorMessage = null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableState.value = mutableState.value.copy(
                            budget = BudgetUiState(errorMessage = QUOTA_CACHE_CLEAR_FAILURE_MESSAGE),
                        )
                    }
                } catch (_: SecureStoragePersistenceFailure) {
                    mutableState.value = mutableState.value.copy(errorMessage = API_KEY_CLEAR_FAILURE_MESSAGE)
                }
            }
        }
    }

    fun setZdr(enabled: Boolean) {
        settings.zdr = enabled
        mutableState.value = mutableState.value.copy(zdr = enabled)
    }

    fun selectModel(model: ChatModel) {
        val currentModel = mutableState.value.models.firstOrNull { it.apiId == model.apiId } ?: return
        mutableState.value = mutableState.value.copy(selectedModel = currentModel)
    }

    fun refreshQuota() {
        if (!mutableState.value.keyConfigured || quotaJob?.isActive == true) return
        quotaJob = viewModelScope.launch {
            var apiKey: CharArray? = null
            mutableState.value = mutableState.value.copy(
                budget = mutableState.value.budget.copy(refreshing = true, errorMessage = null),
            )
            try {
                apiKey = secrets.readApiKey() ?: throw IllegalStateException("API key unavailable")
                val snapshot = quota.refresh(apiKey)
                mutableState.value = mutableState.value.copy(
                    budget = BudgetUiState(
                        snapshot = snapshot,
                        freshness = BudgetFreshness.LIVE,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val snapshot = mutableState.value.budget.snapshot
                mutableState.value = mutableState.value.copy(
                    budget = BudgetUiState(
                        snapshot = snapshot,
                        freshness = if (snapshot == null) BudgetFreshness.UNAVAILABLE else BudgetFreshness.STALE,
                        errorMessage = safeQuotaMessage(error),
                    ),
                )
            } finally {
                apiKey?.fill('\u0000')
                if (mutableState.value.budget.refreshing) {
                    mutableState.value = mutableState.value.copy(
                        budget = mutableState.value.budget.copy(refreshing = false),
                    )
                }
            }
        }
    }

    fun openConversation(id: String) {
        if (mutableState.value.currentConversationId == id) return
        messageCollection?.cancel()
        mutableState.value = mutableState.value.copy(currentConversationId = id, messages = emptyList())
        messageCollection = viewModelScope.launch {
            chats.observeMessages(id).collectLatest { messages ->
                mutableState.value = mutableState.value.copy(messages = messages)
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            val targetsActiveSend = activeTurn?.pending?.conversationId == id || sendTargetConversationId == id
            try {
                if (targetsActiveSend) {
                    sendJob?.cancelAndJoin()
                }
                chats.deleteConversation(id)
                if (mutableState.value.currentConversationId == id) {
                    messageCollection?.cancelAndJoin()
                    mutableState.value = mutableState.value.copy(
                        currentConversationId = null,
                        messages = emptyList(),
                    )
                }
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Conversation could not be deleted. Try again.",
                )
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || sendJob?.isActive == true || !mutableState.value.keyConfigured) return
        val selectedModel = mutableState.value.selectedModel
        val zdr = mutableState.value.zdr
        val requestedConversationId = mutableState.value.currentConversationId
        sendTargetConversationId = requestedConversationId
        sendJob = viewModelScope.launch {
            var turn: ActiveTurn? = null
            var apiKey: CharArray? = null
            try {
                mutableState.value = mutableState.value.copy(sending = true, streamingText = "", errorMessage = null)
                val registeredTurn = withContext(NonCancellable) {
                    val pending = chats.beginTurn(requestedConversationId, prompt, selectedModel)
                    sendTargetConversationId = pending.conversationId
                    ActiveTurn(pending).also { active ->
                        turn = active
                        activeTurn = active
                        mutableState.value = mutableState.value.copy(
                            activeConversationId = pending.conversationId,
                            activeAssistantMessageId = pending.assistantMessageId,
                        )
                    }
                }
                currentCoroutineContext().ensureActive()
                val pending = registeredTurn.pending
                if (requestedConversationId != pending.conversationId) openConversation(pending.conversationId)

                val context = chats.messagesSnapshot(pending.conversationId)
                    .asSequence()
                    .filterNot { it.id == pending.assistantMessageId }
                    .filter { it.content.isNotEmpty() }
                    .mapNotNull { message ->
                        when (message.role) {
                            "USER" -> ApiMessage("user", message.content)
                            "ASSISTANT" -> ApiMessage("assistant", message.content)
                            else -> null
                        }
                    }
                    .toList()
                apiKey = secrets.readApiKey() ?: error("API key is not configured")
                collectStream(registeredTurn, apiKey, selectedModel, context, zdr)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    registeredTurn.completionCommitted = chats.completeTurn(
                        pending.assistantMessageId,
                        registeredTurn.partial.toString(),
                        registeredTurn.usage,
                    )
                    if (!registeredTurn.completionCommitted) throw TurnFailure(TurnFailureKind.COMMIT_REJECTED)
                    finishTurn(registeredTurn, errorMessage = null)
                    refreshQuota()
                }
            } catch (cancelled: CancellationException) {
                if (turn?.completionCommitted != true) {
                    turn?.let { interruptSafely(it, "cancelled") }
                    finishTurn(turn, if (turn?.partial?.isNotEmpty() == true) "Response interrupted" else null)
                } else {
                    finishTurn(turn, null)
                }
                throw cancelled
            } catch (error: Exception) {
                if (turn?.completionCommitted != true) turn?.let { interruptSafely(it, "failed") }
                if (error is KeyRecoveryRequired) {
                    finishTurn(turn, null, recoveryMessage = API_KEY_RECOVERY_MESSAGE)
                } else {
                    finishTurn(turn, safeMessage(error))
                }
            } finally {
                apiKey?.fill('\u0000')
                sendTargetConversationId = null
            }
        }
    }

    fun cancel() {
        sendJob?.cancel()
    }

    private suspend fun collectStream(
        turn: ActiveTurn,
        apiKey: CharArray,
        model: ChatModel,
        context: List<ApiMessage>,
        zdr: Boolean,
    ) {
        var phase = StreamPhase.COLLECTING
        var checkpointAt = elapsedMillis()
        var checkpointLength = 0
        streamSource.stream(apiKey, model, context, zdr).takeWhile { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    turn.partial.append(event.content)
                    mutableState.value = mutableState.value.copy(streamingText = turn.partial.toString())
                    val currentTime = elapsedMillis()
                    if (currentTime - checkpointAt >= CHECKPOINT_MILLIS ||
                        turn.partial.length - checkpointLength >= CHECKPOINT_CHARS
                    ) {
                        chats.checkpointAssistant(turn.pending.assistantMessageId, turn.partial.toString())
                        checkpointAt = currentTime
                        checkpointLength = turn.partial.length
                    }
                }
                is StreamEvent.Usage -> turn.usage = event.usage
                StreamEvent.Done -> phase = StreamPhase.DONE
                is StreamEvent.Error -> throw TurnFailure(TurnFailureKind.FRAME_ERROR)
            }
            phase != StreamPhase.DONE
        }.collect { }
        if (phase != StreamPhase.DONE) throw TurnFailure(TurnFailureKind.EARLY_EOF)
    }

    private suspend fun interruptSafely(turn: ActiveTurn, reason: String) {
        try {
            withContext(NonCancellable) {
                chats.interruptTurn(turn.pending.assistantMessageId, turn.partial.toString(), reason)
            }
        } catch (_: Exception) {
            // UI still settles; repository terminal methods are idempotent and deletion waits for this path.
        }
    }

    private fun finishTurn(turn: ActiveTurn?, errorMessage: String?, recoveryMessage: String? = null) {
        if (turn == null || activeTurn?.pending?.assistantMessageId == turn.pending.assistantMessageId) {
            activeTurn = null
            mutableState.value = mutableState.value.copy(
                activeConversationId = null,
                activeAssistantMessageId = null,
                streamingText = "",
                sending = false,
                errorMessage = errorMessage,
                recoveryMessage = recoveryMessage ?: mutableState.value.recoveryMessage,
            )
        }
    }

    private fun refreshModels() {
        viewModelScope.launch {
            try {
                val models = modelCatalogue.refresh().models.requireSolModel()
                val selected = models.firstOrNull {
                    it.apiId == mutableState.value.selectedModel.apiId
                } ?: ChatModel.SOL
                mutableState.value = mutableState.value.copy(models = models, selectedModel = selected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The validated local catalogue remains active when refresh fails.
            }
        }
    }

    private fun safeQuotaMessage(error: Exception): String = when ((error as? ServiceException)?.kind) {
        ServiceException.Kind.UNAUTHORIZED -> "Quota could not be loaded: invalid or expired API key."
        ServiceException.Kind.FORBIDDEN -> "Quota access is forbidden."
        ServiceException.Kind.RATE_LIMITED -> "Quota is temporarily rate limited."
        ServiceException.Kind.TIMEOUT -> "Quota request timed out. Try again."
        ServiceException.Kind.BAD_RESPONSE -> "Quota service returned an invalid response."
        ServiceException.Kind.UNAVAILABLE -> "Quota service is unavailable. Try again."
        null -> "Quota could not be refreshed. Try again."
    }

    private fun List<ChatModel>.requireSolModel(): List<ChatModel> = also { models ->
        require(models.any { it == ChatModel.SOL }) { "The active catalogue must contain GPT-5.6 Sol" }
    }

    private fun safeMessage(error: Exception): String = when (error) {
        is CommandCodeException.Unauthorized -> "Invalid or expired API key"
        is CommandCodeException.Forbidden -> "API access is forbidden"
        is CommandCodeException.ZdrUnavailable -> "No ZDR-capable provider is available"
        is CommandCodeException.RateLimited -> "Request rate or plan limit reached"
        is CommandCodeException.ServerFailure -> "Command Code service failure"
        is TurnFailure -> when (error.kind) {
            TurnFailureKind.EARLY_EOF -> "Response stream ended early. Partial response saved. Try again."
            TurnFailureKind.FRAME_ERROR -> "Response stream could not be read. Partial response saved. Try again."
            TurnFailureKind.COMMIT_REJECTED -> "Response could not be finalised. Partial response saved. Try again."
        }
        is IOException -> "Network stream interrupted. Partial response saved. Try again."
        else -> "The request could not be completed. Try again."
    }

    private data class ActiveTurn(
        val pending: PendingTurn,
        val partial: StringBuilder = StringBuilder(),
        var usage: TokenUsage? = null,
        var completionCommitted: Boolean = false,
    )

    private enum class StreamPhase { COLLECTING, DONE }
    private enum class TurnFailureKind { EARLY_EOF, FRAME_ERROR, COMMIT_REJECTED }
    private class TurnFailure(val kind: TurnFailureKind) : IOException()

    companion object {
        private const val CHECKPOINT_MILLIS = 1_000L
        private const val CHECKPOINT_CHARS = 1_024
        private const val API_KEY_RECOVERY_MESSAGE = "The encrypted API key cannot be opened. Recovery is required."
        private const val API_KEY_CLEAR_FAILURE_MESSAGE = "API key could not be cleared. Try again."
        private const val QUOTA_CACHE_CLEAR_FAILURE_MESSAGE = "Saved quota could not be cleared. Try again."

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(
                container.apiKeyStore,
                container.settings,
                container.chatStore,
                container.streamSource,
                container.modelCatalogue,
                container.quota,
            ) as T
        }
    }
}
