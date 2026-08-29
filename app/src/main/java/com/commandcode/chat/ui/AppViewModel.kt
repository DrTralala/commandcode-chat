package com.commandcode.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.commandcode.chat.AppContainer
import com.commandcode.chat.ApiKeyStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BudgetUiState(
    val fiveHours: BudgetWindow = BudgetWindow(BigDecimal.ZERO, BigDecimal("14"), null, null),
    val weekly: BudgetWindow = BudgetWindow(BigDecimal.ZERO, BigDecimal("35"), null, null),
    val monthly: BudgetWindow = BudgetWindow(BigDecimal.ZERO, BigDecimal("70"), null, null),
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
    val streamingText: String = "",
    val sending: Boolean = false,
    val errorMessage: String? = null,
    val budget: BudgetUiState = BudgetUiState(),
)

class AppViewModel(
    private val secrets: ApiKeyStore,
    private val settings: SettingsStore,
    private val chats: ChatStore,
    private val streamSource: StreamSource,
    private val now: () -> Instant = Instant::now,
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState(zdr = settings.zdr, billingDay = settings.billingDay))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var messageCollection: Job? = null
    private var sendJob: Job? = null
    private var usageEvents: List<UsageEvent> = emptyList()

    init {
        viewModelScope.launch {
            val configured = try {
                secrets.readApiKey()?.let { key ->
                    try { key.isNotEmpty() } finally { key.fill('\u0000') }
                } ?: false
            } catch (_: KeyRecoveryRequired) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    recoveryMessage = "The encrypted API key cannot be opened. Recovery is required.",
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
            mutableState.value = mutableState.value.copy(billingDayError = "Billing day must be from 1 to 31")
            return
        }
        settings.billingDay = day
        mutableState.value = mutableState.value.copy(billingDay = day, billingDayError = null)
        updateBudget()
    }

    fun selectModel(model: ChatModel) {
        require(model == ChatModel.SOL || model == ChatModel.LUNA)
        mutableState.value = mutableState.value.copy(selectedModel = model)
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
            chats.deleteConversation(id)
            if (mutableState.value.currentConversationId == id) {
                messageCollection?.cancel()
                mutableState.value = mutableState.value.copy(currentConversationId = null, messages = emptyList())
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || sendJob?.isActive == true || !mutableState.value.keyConfigured) return
        val startingState = mutableState.value
        sendJob = viewModelScope.launch {
            var pending: PendingTurn? = null
            var apiKey: CharArray? = null
            val partial = StringBuilder()
            var usage: TokenUsage? = null
            var done = false
            try {
                mutableState.value = mutableState.value.copy(sending = true, streamingText = "", errorMessage = null)
                pending = chats.beginTurn(startingState.currentConversationId, prompt, startingState.selectedModel)
                if (startingState.currentConversationId != pending.conversationId) openConversation(pending.conversationId)
                val context = startingState.messages.map {
                    ApiMessage(if (it.role == "USER") "user" else "assistant", it.content)
                } + ApiMessage("user", prompt)
                apiKey = secrets.readApiKey() ?: error("API key is not configured")
                var checkpointAt = elapsedMillis()
                var checkpointLength = 0
                streamSource.stream(apiKey, startingState.selectedModel, context, startingState.zdr).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            partial.append(event.content)
                            mutableState.value = mutableState.value.copy(streamingText = partial.toString())
                            val currentTime = elapsedMillis()
                            if (currentTime - checkpointAt >= CHECKPOINT_MILLIS || partial.length - checkpointLength >= CHECKPOINT_CHARS) {
                                chats.checkpointAssistant(pending.assistantMessageId, partial.toString())
                                checkpointAt = currentTime
                                checkpointLength = partial.length
                            }
                        }
                        is StreamEvent.Usage -> usage = event.usage
                        StreamEvent.Done -> done = true
                        is StreamEvent.Error -> throw StreamFailure(event.code)
                    }
                }
                if (!done) throw IOException("Stream ended before completion")
                chats.completeTurn(pending.assistantMessageId, partial.toString(), usage)
                mutableState.value = mutableState.value.copy(sending = false, streamingText = "")
            } catch (cancelled: CancellationException) {
                pending?.let {
                    withContext(NonCancellable) { chats.interruptTurn(it.assistantMessageId, partial.toString(), "cancelled") }
                }
                mutableState.value = mutableState.value.copy(
                    sending = false,
                    streamingText = "",
                    errorMessage = if (partial.isNotEmpty()) "Response interrupted" else null,
                )
                throw cancelled
            } catch (error: Exception) {
                pending?.let {
                    withContext(NonCancellable) { chats.interruptTurn(it.assistantMessageId, partial.toString(), "failed") }
                }
                mutableState.value = if (error is KeyRecoveryRequired) {
                    mutableState.value.copy(
                        sending = false,
                        streamingText = "",
                        recoveryMessage = "The encrypted API key cannot be opened. Recovery is required.",
                    )
                } else {
                    mutableState.value.copy(
                        sending = false,
                        streamingText = "",
                        errorMessage = safeMessage(error),
                    )
                }
            } finally {
                apiKey?.fill('\u0000')
            }
        }
    }

    fun cancel() {
        sendJob?.cancel()
    }

    private fun updateBudget() {
        val instant = now()
        val billingDay = mutableState.value.billingDay
        val fiveHours = BudgetCalculator.currentWindow(usageEvents, instant, Duration.ofHours(5), BigDecimal("14"))
        val weekly = BudgetCalculator.currentWindow(usageEvents, instant, Duration.ofDays(7), BigDecimal("35"))
        val calculatedMonth = BudgetCalculator.monthlyWindow(
            usageEvents,
            ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()),
            billingDay,
        ).copy(capCredits = BigDecimal("70"))
        mutableState.value = mutableState.value.copy(budget = BudgetUiState(fiveHours, weekly, calculatedMonth))
    }

    private fun safeMessage(error: Exception): String = when (error) {
        is CommandCodeException.Unauthorized -> "Invalid or expired API key"
        is CommandCodeException.Forbidden -> "API access is forbidden"
        is CommandCodeException.ZdrUnavailable -> "No ZDR-capable provider is available"
        is CommandCodeException.RateLimited -> "Request rate or plan limit reached"
        is CommandCodeException.ServerFailure -> "Command Code service failure"
        is StreamFailure -> "Stream error: ${error.code}"
        is IOException -> "Network stream interrupted"
        else -> "The request could not be completed"
    }

    private class StreamFailure(val code: String) : IOException()

    companion object {
        private const val CHECKPOINT_MILLIS = 1_000L
        private const val CHECKPOINT_CHARS = 1_024

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
