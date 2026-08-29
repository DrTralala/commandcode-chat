package com.commandcode.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.commandcode.chat.ApiKeyStore
import com.commandcode.chat.AppContainer
import com.commandcode.chat.ChatStore
import com.commandcode.chat.SettingsStore
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.budget.BudgetCalculator
import com.commandcode.chat.data.budget.BudgetWindow
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.CommandCodeException
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.security.KeyRecoveryRequired
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import java.io.IOException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BudgetUiState(
    val fiveHours: BudgetWindow = BudgetWindow(BigDecimal.ZERO, BigDecimal("14"), null, null),
    val weekly: BudgetWindow = BudgetWindow(BigDecimal.ZERO, BigDecimal("35"), null, null),
    val monthly: BudgetWindow = BudgetWindow(BigDecimal.ZERO, BigDecimal("70"), null, null),
    val selectedModel: ChatModel = ChatModel.SOL,
)

data class AppUiState(
    val loading: Boolean = true,
    val recoveryMessage: String? = null,
    val keyConfigured: Boolean = false,
    val zdr: Boolean = true,
    val billingDay: Int = 1,
    val billingDayError: String? = null,
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
    private val now: () -> Instant = Instant::now,
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val budgetTicks: Flow<Unit>? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState(zdr = settings.zdr, billingDay = settings.billingDay))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var messageCollection: Job? = null
    private var sendJob: Job? = null
    private var budgetTimer: Job? = null
    private var usageEvents: List<UsageEvent> = emptyList()
    private var activeTurn: ActiveTurn? = null
    private var sendTargetConversationId: String? = null

    init {
        viewModelScope.launch {
            val configured = try {
                secrets.readApiKey()?.let { key ->
                    try { key.isNotEmpty() } finally { key.fill('\u0000') }
                } ?: false
            } catch (_: KeyRecoveryRequired) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    recoveryMessage = API_KEY_RECOVERY_MESSAGE,
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(loading = false, keyConfigured = configured)
        }
        viewModelScope.launch {
            chats.observeConversations().collect { conversations ->
                mutableState.value = mutableState.value.copy(conversations = conversations)
            }
        }
        viewModelScope.launch {
            chats.observeUsageEvents().collect { events ->
                usageEvents = events
                updateBudget()
            }
        }
        budgetTicks?.let { ticks ->
            viewModelScope.launch { ticks.collect { updateBudget() } }
        }
    }

    fun saveApiKey(value: CharArray) {
        val protectedCopy = value.copyOf()
        value.fill('\u0000')
        viewModelScope.launch {
            try {
                require(protectedCopy.isNotEmpty()) { "API key cannot be empty" }
                secrets.saveApiKey(protectedCopy)
                mutableState.value = mutableState.value.copy(keyConfigured = true, errorMessage = null)
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(errorMessage = safeMessage(error))
            } finally {
                protectedCopy.fill('\u0000')
            }
        }
    }

    fun clearApiKey() {
        cancel()
        secrets.clearApiKey()
        mutableState.value = mutableState.value.copy(keyConfigured = false, errorMessage = null)
    }

    fun setZdr(enabled: Boolean) {
        settings.zdr = enabled
        mutableState.value = mutableState.value.copy(zdr = enabled)
    }

    fun setBillingDay(day: Int) {
        if (day !in 1..31) {
            mutableState.value = mutableState.value.copy(billingDayError = BILLING_DAY_ERROR)
            return
        }
        settings.billingDay = day
        mutableState.value = mutableState.value.copy(billingDay = day, billingDayError = null)
        updateBudget()
    }

    fun selectModel(model: ChatModel) {
        require(model == ChatModel.SOL || model == ChatModel.LUNA)
        mutableState.value = mutableState.value.copy(selectedModel = model)
        updateBudget()
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

    private fun updateBudget() {
        val instant = now()
        val billingDay = mutableState.value.billingDay
        val fiveHours = BudgetCalculator.currentWindow(usageEvents, instant, Duration.ofHours(5), BigDecimal("14"))
        val weekly = BudgetCalculator.currentWindow(usageEvents, instant, Duration.ofDays(7), BigDecimal("35"))
        val monthly = BudgetCalculator.monthlyWindow(
            usageEvents,
            ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()),
            billingDay,
        ).copy(capCredits = BigDecimal("70"))
        val budget = BudgetUiState(fiveHours, weekly, monthly, mutableState.value.selectedModel)
        mutableState.value = mutableState.value.copy(budget = budget)
        if (budgetTicks == null) scheduleBudgetRefresh(instant, budget)
    }

    private fun scheduleBudgetRefresh(instant: Instant, budget: BudgetUiState) {
        budgetTimer?.cancel()
        val nextReset = listOfNotNull(
            budget.fiveHours.resetAt,
            budget.weekly.resetAt,
            budget.monthly.resetAt,
        ).filter { it > instant }.minOrNull() ?: return
        val delayMillis = Duration.between(instant, nextReset).toMillis().coerceAtLeast(1L)
        budgetTimer = viewModelScope.launch {
            delay(delayMillis)
            budgetTimer = null
            updateBudget()
        }
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
        private const val BILLING_DAY_ERROR = "Billing day must be from 1 to 31"
        private const val API_KEY_RECOVERY_MESSAGE = "The encrypted API key cannot be opened. Recovery is required."

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(
                container.apiKeyStore,
                container.settings,
                container.chatStore,
                container.streamSource,
            ) as T
        }
    }
}
