package com.commandcode.chat.ui

import android.graphics.Bitmap
import android.net.Uri
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
import com.commandcode.chat.data.commandcode.CompletionNotice
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.attachments.AttachmentOperations
import com.commandcode.chat.data.database.AlternativeRequest
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.security.KeyRecoveryRequired
import com.commandcode.chat.data.security.SecureStoragePersistenceFailure
import com.commandcode.chat.data.security.SystemPromptStore
import com.commandcode.chat.data.security.effectiveSystemPrompt
import com.commandcode.chat.data.service.CatalogueOrigin
import com.commandcode.chat.data.service.ModelCatalogueCodec
import com.commandcode.chat.data.service.ModelCatalogueSnapshot
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.ContextWindowCatalogue
import com.commandcode.chat.domain.ConversationTokenUsage
import com.commandcode.chat.ui.chat.ContextUsagePresentation
import com.commandcode.chat.ui.chat.contextUsagePresentation
import com.commandcode.chat.data.service.QuotaSnapshot
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.data.service.ServiceException
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.AttachmentSupport
import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.MessageState
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.ui.chat.PickerTicket
import com.commandcode.chat.ui.history.HistorySort
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BudgetFreshness { LIVE, STALE, UNAVAILABLE }

enum class SystemPromptFeedback { SAVED, CLEARED, SAVE_FAILED, CLEAR_FAILED, LOAD_FAILED }

/** Single canonical copy for an unreadable saved prompt, shared by the blocking banner and Settings feedback. */
internal const val SYSTEM_PROMPT_UNAVAILABLE_MESSAGE =
    "Saved system prompt could not be opened. Sending is disabled until a new prompt is saved or cleared."

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
    val modelCatalogueOrigin: CatalogueOrigin = CatalogueOrigin.BUNDLED,
    val modelsRefreshing: Boolean = false,
    val modelsError: String? = null,
    val selectedModel: ChatModel = ChatModel.SOL,
    val conversations: List<Conversation> = emptyList(),
    val currentConversationId: String? = null,
    /** Immutable snapshot of conversations whose deletion jobs have not settled. */
    val deletingConversationIds: Set<String> = emptySet(),
    val messages: List<Message> = emptyList(),
    val tokenUsage: ConversationTokenUsage = ConversationTokenUsage(),
    val contextWindows: ContextWindowCatalogue = ContextWindowCatalogue(),
    val activeConversationId: String? = null,
    val activeAssistantMessageId: String? = null,
    val streamingText: String = "",
    val sending: Boolean = false,
    /** True while a non-streaming message mutation owns the message selection. */
    val messageMutationBusy: Boolean = false,
    val errorMessage: String? = null,
    val budget: BudgetUiState = BudgetUiState(),
    val draftText: String = "",
    val pendingAttachments: List<ImageAttachment> = emptyList(),
    val importingAttachments: Boolean = false,
    val attachmentError: AttachmentFailure? = null,
    val historySort: HistorySort = HistorySort.MOST_RECENT,
    val systemPrompt: String = "",
    val systemPromptLoaded: Boolean = false,
    val systemPromptSaving: Boolean = false,
    val systemPromptFeedback: SystemPromptFeedback? = null,
    val systemPromptCommit: Int = 0,
    val messageEdit: MessageEdit? = null,
    val pendingEditNavigation: EditNavigation? = null,
) {
    /** True when the currently shown conversation is in the deletion snapshot. */
    val deletingCurrentConversation: Boolean
        get() = currentConversationId?.let { it in deletingConversationIds } == true

    val contextUsage: ContextUsagePresentation get() = contextUsagePresentation(this)

    val selectedModelAvailable: Boolean
        get() = models.any { it.apiId == selectedModel.apiId }

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

    val visibleThinking: Boolean
        get() = sending && streamingText.isEmpty() && currentConversationId == activeConversationId

    /** Observable busy state shared by message actions and the ordinary composer. */
    val messageActionsBusy: Boolean
        get() = sending || messageMutationBusy || importingAttachments || deletingCurrentConversation
}

class AppViewModel(
    private val secrets: ApiKeyStore,
    private val settings: SettingsStore,
    private val chats: ChatStore,
    private val streamSource: StreamSource,
    private val modelCatalogue: ModelCatalogueSource,
    private val quota: QuotaSource,
    private val attachmentOperations: AttachmentOperations,
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val apiKeyCopier: (CharArray) -> CharArray = { it.copyOf() },
    private val systemPromptStore: SystemPromptStore,
    private val contextWindowLoader: suspend () -> ContextWindowCatalogue = { ContextWindowCatalogue() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState(zdr = settings.zdr))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var messageCollection: Job? = null
    private var sendJob: Job? = null
    private var sendHandoff: CompletableDeferred<String?>? = null
    private var attachmentImportJob: Job? = null
    private var attachmentImportConversationId: String? = null
    private var quotaJob: Job? = null
    private var modelRefreshJob: Job? = null
    private val keyMutationMutex = Mutex()
    private var activeTurn: ActiveTurn? = null
    private var sendTargetConversationId: String? = null
    private var messageMutationJob: Job? = null
    private var draftGeneration = 0L
    private var attachmentActionsReady = false
    private var pendingModels: ModelCatalogueSnapshot? = null
    private var bundledContextWindows = ContextWindowCatalogue()
    private var knownContextWindowMetadata: Map<String, Long> = emptyMap()
    private val deletingConversationIds = mutableSetOf<String>()

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
            val localCatalogue = try {
                ModelCatalogueCodec.validate(modelCatalogue.loadLocal())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ModelCatalogueCodec.validate(
                    ModelCatalogueSnapshot(
                        schemaVersion = ModelCatalogueCodec.SUPPORTED_SCHEMA_VERSION,
                        catalogueVersion = "bundled-fallback",
                        generatedAt = 1L,
                        models = listOf(ChatModel.SOL),
                        origin = CatalogueOrigin.BUNDLED,
                    ),
                )
            }
            val persistedContextWindows = try {
                modelCatalogue.loadKnownContextWindowMetadata()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
            val cachedQuota = try {
                quota.loadCached()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val contextWindows = try {
                contextWindowLoader()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ContextWindowCatalogue()
            }
            bundledContextWindows = contextWindows
            knownContextWindowMetadata = persistedContextWindows + contextWindowMetadata(localCatalogue.models)
            var attachmentFailure: AttachmentFailure? = null
            try {
                attachmentOperations.initialise()
                attachmentActionsReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attachmentFailure = attachmentFailureOf(error, AttachmentFailure.STORAGE)
            }
            var systemPrompt: String? = null
            try {
                systemPrompt = systemPromptStore.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                systemPrompt = null
            }
            mutableState.value = mutableState.value.copy(
                loading = false,
                recoveryMessage = recoveryMessage,
                keyConfigured = configured,
                models = localCatalogue.models,
                modelCatalogueOrigin = localCatalogue.origin,
                contextWindows = contextWindowsWithModelMetadata(contextWindows, localCatalogue.models),
                selectedModel = localCatalogue.models.firstOrNull {
                    it.apiId == ChatModel.SOL.apiId
                } ?: localCatalogue.models.first(),
                budget = cachedQuota?.takeIf { configured }?.let {
                    BudgetUiState(snapshot = it, freshness = BudgetFreshness.STALE)
                } ?: BudgetUiState(),
                attachmentError = attachmentFailure,
                errorMessage = if (systemPrompt == null) SYSTEM_PROMPT_UNAVAILABLE_MESSAGE
                    else attachmentFailure?.let(::safeAttachmentMessage),
                systemPrompt = systemPrompt ?: "",
                systemPromptLoaded = systemPrompt != null,
                systemPromptFeedback = if (systemPrompt == null) SystemPromptFeedback.LOAD_FAILED else null,
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
                        ).withBlockingPromptBanner()
                        refreshQuota()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        mutableState.value = mutableState.value.copy(
                            errorMessage = safeMessage(error),
                        ).withBlockingPromptBanner()
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
                        mutableState.value = mutableState.value.copy(errorMessage = null).withBlockingPromptBanner()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableState.value = mutableState.value.copy(
                            budget = BudgetUiState(errorMessage = QUOTA_CACHE_CLEAR_FAILURE_MESSAGE),
                        )
                    }
                } catch (_: SecureStoragePersistenceFailure) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = API_KEY_CLEAR_FAILURE_MESSAGE,
                    ).withBlockingPromptBanner()
                }
            }
        }
    }

    fun setZdr(enabled: Boolean) {
        settings.zdr = enabled
        mutableState.value = mutableState.value.copy(zdr = enabled)
    }

    fun saveSystemPrompt(value: String) {
        val current = mutableState.value
        if (current.systemPromptSaving) return
        mutableState.value = current.copy(
            systemPromptSaving = true,
            systemPromptFeedback = null,
        )
        viewModelScope.launch {
            try {
                val effective = effectiveSystemPrompt(value)
                systemPromptStore.save(effective)
                val snapshot = mutableState.value
                mutableState.value = snapshot.copy(
                    systemPrompt = effective,
                    systemPromptLoaded = true,
                    systemPromptSaving = false,
                    systemPromptFeedback = SystemPromptFeedback.SAVED,
                    systemPromptCommit = snapshot.systemPromptCommit + 1,
                    errorMessage = if (snapshot.errorMessage == SYSTEM_PROMPT_UNAVAILABLE_MESSAGE) null else snapshot.errorMessage,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    systemPromptSaving = false,
                    systemPromptFeedback = SystemPromptFeedback.SAVE_FAILED,
                )
            }
        }
    }

    fun clearSystemPrompt() {
        val current = mutableState.value
        if (current.systemPromptSaving) return
        mutableState.value = current.copy(
            systemPromptSaving = true,
            systemPromptFeedback = null,
        )
        viewModelScope.launch {
            try {
                systemPromptStore.clear()
                val snapshot = mutableState.value
                mutableState.value = snapshot.copy(
                    systemPrompt = "",
                    systemPromptLoaded = true,
                    systemPromptSaving = false,
                    systemPromptFeedback = SystemPromptFeedback.CLEARED,
                    systemPromptCommit = snapshot.systemPromptCommit + 1,
                    errorMessage = if (snapshot.errorMessage == SYSTEM_PROMPT_UNAVAILABLE_MESSAGE) null else snapshot.errorMessage,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    systemPromptSaving = false,
                    systemPromptFeedback = SystemPromptFeedback.CLEAR_FAILED,
                )
            }
        }
    }

    fun updateDraft(text: String) {
        val current = mutableState.value
        if (selectionIsFrozen()) return
        mutableState.value = current.copy(draftText = text)
    }

    fun startMessageEdit(messageId: String) {
        val current = mutableState.value
        if (current.loading || selectionIsFrozen(includeEdit = false)) return
        val existing = current.messageEdit
        if (existing?.messageId == messageId) return
        if (existing != null) {
            if (current.editableMessage(messageId) != null) {
                requestEditNavigation(EditNavigation.EditAnother(messageId))
            }
            return
        }
        val conversationId = current.currentConversationId ?: return
        val target = current.editableMessage(messageId) ?: return
        mutableState.value = current.copy(
            messageEdit = MessageEdit(conversationId, target.id, target.content),
            pendingEditNavigation = null,
        )
    }

    fun updateMessageEdit(text: String) {
        val current = mutableState.value
        val edit = current.messageEdit ?: return
        if (current.loading || selectionIsFrozen(includeEdit = false)) return
        mutableState.value = current.copy(messageEdit = edit.copy(text = text))
    }

    fun cancelMessageEdit() {
        val current = mutableState.value
        if (current.messageEdit == null || selectionIsFrozen(includeEdit = false)) return
        mutableState.value = current.copy(messageEdit = null, pendingEditNavigation = null)
        applyPendingModelsIfIdle()
    }

    /**
     * Drops only in-memory edit navigation state when recovery has taken over.
     * Unlike the explicit editor cancel path, this must not wait for an in-flight
     * submission or mutation to settle before clearing stale UI state.
     */
    fun clearEphemeralEditForRecovery() {
        val current = mutableState.value
        if (current.messageEdit == null && current.pendingEditNavigation == null) return
        mutableState.value = current.copy(messageEdit = null, pendingEditNavigation = null)
        applyPendingModelsIfIdle()
    }

    fun submitMessageEdit() {
        val current = mutableState.value
        val edit = current.messageEdit ?: return
        if (current.loading || selectionIsFrozen(includeEdit = false)) return
        val target = current.editableMessage(edit.messageId)?.takeIf {
            it.conversationId == edit.conversationId && it.conversationId == current.currentConversationId
        } ?: return
        sendOrRetry(
            SendAction.Alternative(AlternativeRequest.Edit(target.id, edit.text)),
        )
    }

    fun requestEditNavigation(target: EditNavigation): Boolean {
        val current = mutableState.value
        if (isNoOpNavigation(target)) {
            if (current.pendingEditNavigation != null) {
                mutableState.value = current.copy(pendingEditNavigation = null)
            }
            return true
        }
        if (current.messageEdit == null) {
            if (current.pendingEditNavigation != null) {
                mutableState.value = current.copy(pendingEditNavigation = null)
            }
            return true
        }
        mutableState.value = current.copy(pendingEditNavigation = target)
        return false
    }

    fun confirmEditNavigation(): EditNavigation? {
        val current = mutableState.value
        val target = current.pendingEditNavigation ?: return null
        mutableState.value = current.copy(
            messageEdit = null,
            pendingEditNavigation = null,
        )
        applyPendingModelsIfIdle()
        return target
    }

    fun cancelEditNavigation() {
        val current = mutableState.value
        if (current.pendingEditNavigation != null) {
            mutableState.value = current.copy(pendingEditNavigation = null)
        }
    }

    private fun isNoOpNavigation(target: EditNavigation): Boolean {
        val current = mutableState.value
        return when (target) {
            is EditNavigation.Tab -> false
            is EditNavigation.Conversation -> current.currentConversationId == target.id
            is EditNavigation.Branch -> isSelectedAlternative(target.messageId)
            is EditNavigation.EditAnother -> current.messageEdit?.messageId == target.messageId
            EditNavigation.NewChat -> current.currentConversationId == null && current.messageEdit == null
            EditNavigation.Back -> false
        }
    }

    private fun AppUiState.editableMessage(messageId: String): Message? {
        val conversationId = currentConversationId ?: return null
        return messages.firstOrNull {
                it.id == messageId &&
                it.conversationId == conversationId &&
                it.role == USER_ROLE &&
                it.status !in NONTERMINAL_MESSAGE_STATUSES
        }
    }

    private fun isSelectedAlternative(messageId: String): Boolean =
        mutableState.value.messages.asSequence()
            .flatMap { message -> sequenceOf(message.branchChoice, message.responseBranchChoice) }
            .filterNotNull()
            .any { choice -> choice.messageIds.getOrNull(choice.selectedIndex) == messageId }

    private fun AppUiState.hasAlternative(messageId: String, expectedIds: List<String>): Boolean =
        messages.asSequence()
            .flatMap { message -> sequenceOf(message.branchChoice, message.responseBranchChoice) }
            .filterNotNull()
            .any { choice -> choice.messageIds == expectedIds && messageId in choice.messageIds }

    private fun clearCommittedMessageEdit(request: AlternativeRequest.Edit, conversationId: String) {
        val current = mutableState.value
        val edit = current.messageEdit
        if (edit?.conversationId == conversationId && edit.messageId == request.messageId) {
            mutableState.value = current.copy(
                messageEdit = null,
                pendingEditNavigation = null,
            )
        }
    }

    fun requestAttachmentPicker(): PickerTicket? {
        val current = mutableState.value
        if (!attachmentActionsReady || current.loading || selectionIsFrozen()) {
            return null
        }
        return when (AttachmentPolicy.capability(current.selectedModel.apiId).support) {
            AttachmentSupport.DOCUMENTED -> PickerTicket(
                generation = draftGeneration,
                conversationId = current.currentConversationId,
                modelId = current.selectedModel.apiId,
            )
            AttachmentSupport.UNSUPPORTED -> null.also {
                publishAttachmentFailure(AttachmentFailure.UNSUPPORTED_MODEL)
            }
            AttachmentSupport.UNVERIFIED -> null.also {
                publishAttachmentFailure(AttachmentFailure.UNVERIFIED_MODEL)
            }
        }
    }

    fun importAttachments(ticket: PickerTicket, uris: List<Uri>) {
        if (!validatePickerTicket(ticket) || uris.isEmpty()) return
        mutableState.value = mutableState.value.copy(importingAttachments = true)
        attachmentImportConversationId = ticket.conversationId
        attachmentImportJob = viewModelScope.launch {
            try {
                uris.forEach { uri ->
                    ensurePickerStillCurrent(ticket)
                    val current = mutableState.value
                    val savedCount = current.conversations.firstOrNull {
                        it.id == ticket.conversationId
                    }?.attachmentCount ?: 0
                    AttachmentPolicy.requireCount(
                        saved = savedCount,
                        pending = current.pendingAttachments.size,
                        incoming = 1,
                    )

                    var imported: ImageAttachment? = null
                    try {
                        imported = attachmentOperations.importImage(uri, ticket.modelId)
                        if (!isPickerCurrent(ticket)) {
                            val stale = requireNotNull(imported)
                            imported = null
                            discardImportedSafely(stale)
                            throw attachmentFailure(AttachmentFailure.STALE_SELECTION)
                        }
                        val accepted = requireNotNull(imported)
                        mutableState.value = mutableState.value.copy(
                            pendingAttachments = mutableState.value.pendingAttachments + accepted,
                        )
                    } catch (cancelled: CancellationException) {
                        imported?.let { discardImportedSafely(it) }
                        throw cancelled
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isPickerCurrent(ticket)) {
                    publishAttachmentFailure(attachmentFailureOf(error, AttachmentFailure.STORAGE))
                }
            } finally {
                if (attachmentImportJob === currentCoroutineContext()[Job]) {
                    attachmentImportJob = null
                    attachmentImportConversationId = null
                    mutableState.value = mutableState.value.copy(importingAttachments = false)
                    applyPendingModelsIfIdle()
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        val current = mutableState.value
        if (selectionIsFrozen()) return
        val image = current.pendingAttachments.firstOrNull { it.id == id } ?: return
        val removalGeneration = nextDraftGeneration()
        mutableState.value = current.copy(
            pendingAttachments = current.pendingAttachments.filterNot { it.id == id },
        )
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { attachmentOperations.discard(listOf(image)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (draftGeneration == removalGeneration &&
                    mutableState.value.pendingAttachments.none { it.id == id }
                ) {
                    mutableState.value = mutableState.value.copy(
                        pendingAttachments = mutableState.value.pendingAttachments + image,
                    )
                    publishAttachmentFailure(attachmentFailureOf(error, AttachmentFailure.CLEANUP))
                }
            }
        }
    }

    fun dismissAttachmentError() {
        val current = mutableState.value
        val wasAttachmentMessage = current.attachmentError?.let(::safeAttachmentMessage) == current.errorMessage
        mutableState.value = current.copy(
            attachmentError = null,
            errorMessage = if (wasAttachmentMessage) null else current.errorMessage,
        ).withBlockingPromptBanner()
    }

    suspend fun loadAttachmentPreview(id: String, maxEdge: Int): Bitmap {
        return try {
            attachmentOperations.preview(id, maxEdge)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publishAttachmentFailure(attachmentFailureOf(error, AttachmentFailure.STORAGE))
            throw error
        }
    }

    fun setHistorySort(sort: HistorySort) {
        mutableState.value = mutableState.value.copy(historySort = sort)
    }

    fun selectModel(model: ChatModel) {
        if (selectionIsFrozen()) return
        val currentModel = mutableState.value.models.firstOrNull { it.apiId == model.apiId } ?: return
        if (mutableState.value.selectedModel.apiId == currentModel.apiId) {
            dismissResolvedUnavailableModelError()
            return
        }
        val conversationId = mutableState.value.currentConversationId
        if (conversationId == null) {
            nextDraftGeneration()
            mutableState.value = mutableState.value.copy(selectedModel = currentModel)
            dismissResolvedModelAttachmentError()
            dismissResolvedUnavailableModelError()
            return
        }
        if (messageMutationJob != null || mutableState.value.messageMutationBusy) return
        mutableState.value = mutableState.value.copy(messageMutationBusy = true)
        messageMutationJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                chats.changeConversationModel(conversationId, currentModel)
                nextDraftGeneration()
                mutableState.value = mutableState.value.copy(selectedModel = currentModel)
                dismissResolvedModelAttachmentError()
                dismissResolvedUnavailableModelError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Model could not be changed. Try again.",
                ).withBlockingPromptBanner()
            } finally {
                completeMessageMutation(currentCoroutineContext()[Job])
            }
        }
        messageMutationJob?.start()
    }

    private fun dismissResolvedModelAttachmentError() {
        val current = mutableState.value
        if ((current.attachmentError == AttachmentFailure.UNSUPPORTED_MODEL ||
                current.attachmentError == AttachmentFailure.UNVERIFIED_MODEL) &&
            AttachmentPolicy.capability(current.selectedModel.apiId).support == AttachmentSupport.DOCUMENTED
        ) {
            dismissAttachmentError()
        }
    }

    private fun dismissResolvedUnavailableModelError() {
        val current = mutableState.value
        if (current.errorMessage == SELECTED_MODEL_UNAVAILABLE_MESSAGE) {
            mutableState.value = current.copy(errorMessage = null).withBlockingPromptBanner()
        }
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
        if (!requestEditNavigation(EditNavigation.Conversation(id))) return
        if (messageMutationJob != null) return
        if (mutableState.value.currentConversationId == id) return
        if (isCurrentConversationDeleting() || id in deletingConversationIds) return
        showConversation(id, invalidateDraft = true)
    }

    fun newChat() {
        if (!requestEditNavigation(EditNavigation.NewChat)) return
        if (messageMutationJob != null) return
        if (isSendBusy() || isCurrentConversationDeleting()) return
        invalidateAttachmentImport()
        nextDraftGeneration()
        discardCurrentDraft()
        messageCollection?.cancel()
        messageCollection = null
        mutableState.value = mutableState.value.copy(
            currentConversationId = null,
            tokenUsage = ConversationTokenUsage(),
            messages = emptyList(),
            draftText = "",
            pendingAttachments = emptyList(),
            errorMessage = navigationErrorMessage(mutableState.value),
            attachmentError = null,
            importingAttachments = attachmentImportJob != null,
        )
    }

    private fun showConversation(id: String, invalidateDraft: Boolean) {
        if (id in deletingConversationIds) return
        if (invalidateDraft) {
            invalidateAttachmentImport()
            nextDraftGeneration()
            discardCurrentDraft()
        } else if (mutableState.value.currentConversationId != id) {
            nextDraftGeneration()
        }
        messageCollection?.cancel()
        mutableState.value = mutableState.value.copy(
            currentConversationId = id,
            tokenUsage = ConversationTokenUsage(),
            selectedModel = if (invalidateDraft) {
                val modelId = mutableState.value.conversations.firstOrNull { it.id == id }?.defaultModelId
                modelId?.let { defaultModelId ->
                    mutableState.value.models.firstOrNull { it.apiId == defaultModelId }
                        ?: mutableState.value.selectedModel.takeIf { it.apiId == defaultModelId }
                        ?: ChatModel(defaultModelId, defaultModelId, ApiFamily.OPENAI_CHAT)
                } ?: mutableState.value.selectedModel
            } else mutableState.value.selectedModel,
            messages = emptyList(),
            draftText = "",
            pendingAttachments = emptyList(),
            errorMessage = navigationErrorMessage(mutableState.value),
            attachmentError = null,
            importingAttachments = attachmentImportJob != null,
        )
        messageCollection = viewModelScope.launch {
            try {
                chats.backfillInitialModel(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best-effort backfill: the conversation still opens without it.
            }
            combine(
                chats.observeMessages(id),
                chats.observeTokenUsage(id).catch { error ->
                    if (error is CancellationException) throw error
                    emit(ConversationTokenUsage())
                },
            ) { messages, usage -> messages to usage }.collectLatest { (messages, usage) ->
                if (mutableState.value.currentConversationId == id) {
                    mutableState.value = mutableState.value.copy(messages = messages, tokenUsage = usage)
                }
            }
        }
    }

    /** While the saved prompt is unavailable the blocking reason must survive navigation. */
    private fun navigationErrorMessage(current: AppUiState): String? =
        if (!current.systemPromptLoaded && !current.loading) SYSTEM_PROMPT_UNAVAILABLE_MESSAGE else null

    /**
     * Enforces the blocking-banner invariant: while the saved prompt is unavailable
     * (startup load has completed and failed) the shared banner always reports that
     * reason. An unrelated error or a success-clear must never make disabled sending
     * look silent, and navigation must not swap the reason for other text. Heal
     * transitions (save/clear success) set `systemPromptLoaded` in the same update
     * and therefore bypass this helper; failing heal operations keep the banner.
     */
    private fun AppUiState.withBlockingPromptBanner(): AppUiState =
        if (systemPromptLoaded || loading) this
        else copy(errorMessage = SYSTEM_PROMPT_UNAVAILABLE_MESSAGE)

    private fun discardCurrentDraft() {
        val images = mutableState.value.pendingAttachments
        if (images.isEmpty()) return
        val generation = draftGeneration
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { attachmentOperations.discard(images) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (draftGeneration == generation) {
                    publishAttachmentFailure(AttachmentFailure.CLEANUP)
                }
            }
        }
    }

    private fun invalidateAttachmentImport() {
        val job = attachmentImportJob
        if (job != null) {
            job.cancel()
            if (job.isCompleted) {
                attachmentImportJob = null
                mutableState.value = mutableState.value.copy(importingAttachments = false)
            } else {
                // Keep the handle as a completion barrier until its NonCancellable
                // discard path has finished.
                mutableState.value = mutableState.value.copy(importingAttachments = true)
            }
        }
    }

    private fun validatePickerTicket(ticket: PickerTicket): Boolean {
        val current = mutableState.value
        if (!attachmentActionsReady ||
            current.loading ||
            selectionIsFrozen() ||
            current.importingAttachments ||
            ticket.conversationId in deletingConversationIds
        ) {
            if (current.importingAttachments) publishAttachmentFailure(AttachmentFailure.STALE_SELECTION)
            return false
        }
        if (ticket.generation != draftGeneration ||
            ticket.conversationId != current.currentConversationId ||
            ticket.modelId != current.selectedModel.apiId
        ) {
            publishAttachmentFailure(AttachmentFailure.STALE_SELECTION)
            return false
        }
        return when (AttachmentPolicy.capability(ticket.modelId).support) {
            AttachmentSupport.DOCUMENTED -> true
            AttachmentSupport.UNSUPPORTED -> false.also {
                publishAttachmentFailure(AttachmentFailure.UNSUPPORTED_MODEL)
            }
            AttachmentSupport.UNVERIFIED -> false.also {
                publishAttachmentFailure(AttachmentFailure.UNVERIFIED_MODEL)
            }
        }
    }

    private fun ensurePickerStillCurrent(ticket: PickerTicket) {
        if (!isPickerCurrent(ticket)) throw attachmentFailure(AttachmentFailure.STALE_SELECTION)
        if (AttachmentPolicy.capability(ticket.modelId).support != AttachmentSupport.DOCUMENTED) {
            throw attachmentFailure(AttachmentFailure.UNVERIFIED_MODEL)
        }
    }

    private fun isPickerCurrent(ticket: PickerTicket): Boolean {
        val current = mutableState.value
        return attachmentActionsReady &&
            !current.loading &&
            !isSendBusy() &&
            !isConversationDeleting(ticket.conversationId) &&
            ticket.generation == draftGeneration &&
            ticket.conversationId == current.currentConversationId &&
            ticket.modelId == current.selectedModel.apiId
    }

    private suspend fun discardImportedSafely(image: ImageAttachment) {
        try {
            withContext(NonCancellable) { attachmentOperations.discard(listOf(image)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The service retains failed cleanup ownership for reconciliation/retry.
        }
    }

    private fun nextDraftGeneration(): Long {
        draftGeneration += 1L
        return draftGeneration
    }

    private fun isSendBusy(): Boolean = mutableState.value.sending || sendJob != null

    private fun isConversationDeleting(id: String?): Boolean = id != null && id in deletingConversationIds

    private fun isCurrentConversationDeleting(): Boolean =
        isConversationDeleting(mutableState.value.currentConversationId)

    private fun publishDeletingConversationIds() {
        mutableState.value = mutableState.value.copy(
            deletingConversationIds = deletingConversationIds.toSet(),
        )
    }

    private fun selectionIsFrozen(includeEdit: Boolean = true): Boolean =
        mutableState.value.messageMutationBusy || messageMutationJob != null || isSendBusy() ||
            mutableState.value.importingAttachments ||
            attachmentImportJob != null ||
            isCurrentConversationDeleting() ||
            (includeEdit && mutableState.value.messageEdit != null)

    private fun publishAttachmentFailure(failure: AttachmentFailure) {
        mutableState.value = mutableState.value.copy(
            attachmentError = failure,
            errorMessage = safeAttachmentMessage(failure),
        ).withBlockingPromptBanner()
    }

    fun deleteConversation(id: String) {
        if (messageMutationJob != null || mutableState.value.messageEdit != null) return
        if (id in deletingConversationIds) return
        val snapshot = mutableState.value
        val ownsCurrentDraft = snapshot.currentConversationId == id
        val draft = if (ownsCurrentDraft) snapshot.pendingAttachments.toList() else emptyList()
        val targetsActiveSend = activeTurn?.pending?.conversationId == id || sendTargetConversationId == id
        val unresolvedSendHandoff = if (sendTargetConversationId == null && sendJob?.isActive == true) {
            sendHandoff
        } else {
            null
        }
        val importJobToJoin = if (attachmentImportConversationId == id) attachmentImportJob else null
        deletingConversationIds += id
        publishDeletingConversationIds()
        if (ownsCurrentDraft) {
            // Reserve the draft and invalidate every picker ticket before the
            // first suspension. The deletion job owns this snapshot until the
            // database operation and its cleanup have settled.
            nextDraftGeneration()
            invalidateAttachmentImport()
        } else {
            importJobToJoin?.cancel()
        }
        viewModelScope.launch {
            try {
                val handedOffSendTarget = unresolvedSendHandoff?.await()
                if (targetsActiveSend || handedOffSendTarget == id) {
                    sendJob?.cancelAndJoin()
                }
                if (importJobToJoin != null) {
                    importJobToJoin.cancelAndJoin()
                    if (attachmentImportJob === importJobToJoin) attachmentImportJob = null
                }
                var cleanupWarning: Throwable? = null
                try {
                    attachmentOperations.deleteConversation(id)
                } catch (error: AttachmentException) {
                    if (error.kind != AttachmentFailure.CLEANUP || error.databaseDeleted != true) throw error
                    cleanupWarning = error
                }

                if (ownsCurrentDraft && mutableState.value.currentConversationId == id) {
                    messageCollection?.cancelAndJoin()
                    messageCollection = null
                    mutableState.value = mutableState.value.copy(
                        currentConversationId = null,
                        tokenUsage = ConversationTokenUsage(),
                        messages = emptyList(),
                        draftText = "",
                        pendingAttachments = emptyList(),
                        importingAttachments = false,
                    )
                }
                if (draft.isNotEmpty()) {
                    try {
                        withContext(NonCancellable) { attachmentOperations.discard(draft) }
                    } catch (error: Exception) {
                        cleanupWarning = cleanupWarning ?: error
                    }
                }
                if (cleanupWarning != null) {
                    publishAttachmentFailure(AttachmentFailure.CLEANUP)
                    mutableState.value = mutableState.value.copy(
                        errorMessage = ATTACHMENT_CLEANUP_WARNING,
                    ).withBlockingPromptBanner()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Conversation could not be deleted. Try again.",
                ).withBlockingPromptBanner()
            } finally {
                deletingConversationIds -= id
                publishDeletingConversationIds()
                applyPendingModelsIfIdle()
            }
        }
    }

    fun send(text: String) = sendOrRetry(SendAction.Normal(text))

    private fun sendOrRetry(action: SendAction) {
        val snapshot = mutableState.value
        if (snapshot.messageEdit != null && action !is SendAction.Alternative) return
        if (!snapshot.systemPromptLoaded) {
            // Never drop an enabled send/retry silently: the only on-screen reason
            // is the unavailable-prompt banner, so make sure it is visible.
            if (snapshot.errorMessage != SYSTEM_PROMPT_UNAVAILABLE_MESSAGE) {
                mutableState.value = snapshot.copy(errorMessage = SYSTEM_PROMPT_UNAVAILABLE_MESSAGE)
            }
            return
        }
        if (!snapshot.selectedModelAvailable) {
            mutableState.value = snapshot.copy(errorMessage = SELECTED_MODEL_UNAVAILABLE_MESSAGE)
            return
        }
        val normalText = (action as? SendAction.Normal)?.text
        if ((normalText != null && normalText.trim().isEmpty() && snapshot.pendingAttachments.isEmpty()) ||
            selectionIsFrozen(includeEdit = action !is SendAction.Alternative) ||
            !snapshot.keyConfigured
        ) return
        if (normalText != null && snapshot.draftText != normalText) {
            mutableState.value = snapshot.copy(draftText = normalText)
        }
        val selectedModel = snapshot.selectedModel
        val zdr = snapshot.zdr
        // Capture the system prompt alongside model/ZDR so a concurrent Settings
        // change cannot alter this in-flight request.
        val systemPrompt = snapshot.systemPrompt
        val conversationId = snapshot.currentConversationId
        if (action !is SendAction.Normal && conversationId == null) return
        val snapshotImages = if (action is SendAction.Normal) snapshot.pendingAttachments.toList() else emptyList()
        val snapshotGeneration = draftGeneration
        val capturedAction = action
        sendTargetConversationId = conversationId
        val handoff = CompletableDeferred<String?>()
        sendHandoff = handoff
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var turn: ActiveTurn? = null
            var apiKey: CharArray? = null
            try {
                mutableState.value = mutableState.value.copy(sending = true, streamingText = "", errorMessage = null)
                val registered = withContext(NonCancellable) {
                    val prepared = when (val action = capturedAction) {
                        is SendAction.Normal -> attachmentOperations.prepareTurn(
                            conversationId,
                            action.text.trim(),
                            selectedModel,
                            snapshotImages,
                            systemPrompt,
                        )
                        is SendAction.FailedRetry -> attachmentOperations.prepareRetry(
                            requireNotNull(conversationId), action.userMessageId, selectedModel, systemPrompt,
                        )
                        is SendAction.Alternative -> attachmentOperations.prepareAlternative(
                            requireNotNull(conversationId), action.request, selectedModel, systemPrompt,
                        )
                    }
                    val active = ActiveTurn(
                        pending = prepared.pending,
                        draftGeneration = snapshotGeneration,
                        draftImages = snapshotImages,
                    ).also { active ->
                        turn = active
                        activeTurn = active
                        sendTargetConversationId = prepared.pending.conversationId
                        mutableState.value = mutableState.value.copy(
                            activeConversationId = prepared.pending.conversationId,
                            activeAssistantMessageId = prepared.pending.assistantMessageId,
                        )
                        handoff.complete(prepared.pending.conversationId)
                    }
                    if (capturedAction is SendAction.Normal && draftGeneration == snapshotGeneration) {
                        mutableState.value = mutableState.value.copy(
                            draftText = "",
                            pendingAttachments = emptyList(),
                        )
                    }
                    if (capturedAction is SendAction.Alternative &&
                        capturedAction.request is AlternativeRequest.Edit
                    ) {
                        clearCommittedMessageEdit(capturedAction.request, requireNotNull(conversationId))
                    }
                    RegisteredTurn(active, prepared.messages)
                }
                currentCoroutineContext().ensureActive()
                val registeredTurn = registered.turn
                val pending = registeredTurn.pending
                if (conversationId != pending.conversationId &&
                    mutableState.value.currentConversationId == conversationId
                ) {
                    showConversation(pending.conversationId, invalidateDraft = false)
                }
                apiKey = secrets.readApiKey() ?: error("API key is not configured")
                collectStream(registeredTurn, apiKey, selectedModel, registered.messages, zdr)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    registeredTurn.completionCommitted = chats.completeTurn(
                        pending.assistantMessageId,
                        registeredTurn.partial.toString(),
                        registeredTurn.usage,
                    )
                    if (!registeredTurn.completionCommitted) throw TurnFailure(TurnFailureKind.COMMIT_REJECTED)
                    finishTurn(
                        registeredTurn,
                        errorMessage = completionFeedback(registeredTurn.completionNotice),
                    )
                    refreshQuota()
                }
            } catch (cancelled: CancellationException) {
                if (turn?.completionCommitted != true) {
                    turn?.let { interruptSafely(it, "cancelled") }
                    finishTurn(turn, if (turn?.partial?.isNotEmpty() == true) "Response interrupted" else null)
                } else {
                    finishTurn(turn, completionFeedback(turn.completionNotice))
                }
                throw cancelled
            } catch (error: Exception) {
                if (turn?.completionCommitted != true) turn?.let { active ->
                    if (zdr && error is CommandCodeException.ZdrUnavailable && active.partial.isEmpty()) {
                        try {
                            withContext(NonCancellable) { chats.failZdrTurn(active.pending.assistantMessageId, active.usage) }
                        } catch (_: Exception) {
                            interruptSafely(active, "failed")
                        }
                    } else interruptSafely(active, "failed")
                }
                if (error is KeyRecoveryRequired) {
                    finishTurn(turn, null, recoveryMessage = API_KEY_RECOVERY_MESSAGE)
                } else if (error is AttachmentException) {
                    val failure = error.kind
                    finishTurn(turn, safeAttachmentMessage(failure), attachmentFailure = failure)
                } else {
                    finishTurn(
                        turn,
                        if (turn?.completionCommitted == true) {
                            completionFeedback(turn.completionNotice)
                        } else if (turn == null) {
                            PREPARE_FAILURE_MESSAGE
                        } else {
                            safeMessage(error)
                        },
                    )
                }
            } finally {
                apiKey?.fill('\u0000')
                clearSendHandle(currentCoroutineContext()[Job], handoff)
            }
        }
        sendJob = job
        job.invokeOnCompletion { clearSendHandle(job, handoff) }
        job.start()
    }

    private fun clearSendHandle(job: Job?, handoff: CompletableDeferred<String?>) {
        if (job == null || sendJob !== job) return
        sendJob = null
        handoff.complete(null)
        if (sendHandoff === handoff) {
            sendHandoff = null
            sendTargetConversationId = null
        }
        applyPendingModelsIfIdle()
    }

    fun cancel() {
        sendJob?.cancel()
    }

    fun retryMessage(userMessageId: String) {
        val user = mutableState.value.messages.firstOrNull {
            it.id == userMessageId && it.role == USER_ROLE && it.status == MessageState.FAILED_ZDR
        } ?: return
        sendOrRetry(SendAction.FailedRetry(user.id))
    }

    fun retryAssistant(messageId: String) {
        val snapshot = mutableState.value
        if (selectionIsFrozen()) return
        val conversationId = snapshot.currentConversationId ?: return
        val target = snapshot.messages.firstOrNull {
            it.id == messageId &&
                it.conversationId == conversationId &&
                it.role == ASSISTANT_ROLE &&
                it.status != MessageState.FAILED_ZDR &&
                it.status !in NONTERMINAL_MESSAGE_STATUSES
        } ?: return
        if (target.replyToMessageId == null) return
        sendOrRetry(SendAction.Alternative(AlternativeRequest.Retry(target.id)))
    }

    fun selectMessageAlternative(messageId: String) {
        val snapshot = mutableState.value
        val choice = snapshot.messages.asSequence()
            .flatMap { message -> sequenceOf(message.branchChoice, message.responseBranchChoice) }
            .filterNotNull()
            .firstOrNull { messageId in it.messageIds }
            ?: return
        if (choice.messageIds.getOrNull(choice.selectedIndex) == messageId) {
            requestEditNavigation(EditNavigation.Branch(messageId))
            return
        }
        if (!requestEditNavigation(EditNavigation.Branch(messageId))) return
        if (selectionIsFrozen()) return
        val conversationId = snapshot.currentConversationId ?: return
        val expectedChoiceIds = choice.messageIds.toList()
        if (messageMutationJob != null || mutableState.value.messageMutationBusy) return
        mutableState.value = mutableState.value.copy(messageMutationBusy = true)
        messageMutationJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val current = mutableState.value
                if (current.currentConversationId != conversationId ||
                    !current.hasAlternative(messageId, expectedChoiceIds)
                ) return@launch
                chats.selectAlternative(conversationId, messageId)
                if (mutableState.value.currentConversationId != conversationId) return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mutableState.value.currentConversationId == conversationId) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = "Alternative could not be selected. Try again.",
                    ).withBlockingPromptBanner()
                }
            } finally {
                completeMessageMutation(currentCoroutineContext()[Job])
            }
        }
        messageMutationJob?.start()
    }

    fun deleteFailedMessage(userMessageId: String) {
        if (selectionIsFrozen()) return
        val snapshot = mutableState.value
        val conversationId = snapshot.currentConversationId ?: return
        if (snapshot.messages.none { it.id == userMessageId && it.role == "USER" && it.status == com.commandcode.chat.domain.MessageState.FAILED_ZDR }) return
        if (messageMutationJob != null || mutableState.value.messageMutationBusy) return
        mutableState.value = mutableState.value.copy(messageMutationBusy = true)
        messageMutationJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                attachmentOperations.deleteFailedMessage(conversationId, userMessageId)
                mutableState.value = mutableState.value.copy(errorMessage = null).withBlockingPromptBanner()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AttachmentException) {
                publishAttachmentFailure(error.kind)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Message could not be deleted. Try again.",
                ).withBlockingPromptBanner()
            } finally {
                completeMessageMutation(currentCoroutineContext()[Job])
            }
        }
        messageMutationJob?.start()
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
                is StreamEvent.Notice -> turn.completionNotice = event.reason
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
                chats.interruptTurn(turn.pending.assistantMessageId, turn.partial.toString(), reason, turn.usage)
            }
        } catch (_: Exception) {
            // UI still settles; repository terminal methods are idempotent and deletion waits for this path.
        }
    }

    private fun finishTurn(
        turn: ActiveTurn?,
        errorMessage: String?,
        recoveryMessage: String? = null,
        attachmentFailure: AttachmentFailure? = null,
    ) {
        if (turn == null || activeTurn?.pending?.assistantMessageId == turn.pending.assistantMessageId) {
            activeTurn = null
            mutableState.value = mutableState.value.copy(
                activeConversationId = null,
                activeAssistantMessageId = null,
                streamingText = "",
                sending = false,
                errorMessage = errorMessage,
                recoveryMessage = recoveryMessage ?: mutableState.value.recoveryMessage,
                attachmentError = attachmentFailure ?:
                    if (errorMessage == null) null else mutableState.value.attachmentError,
            )
        }
    }

    fun refreshModels() {
        if (modelRefreshJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(modelsRefreshing = true, modelsError = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = ModelCatalogueCodec.validate(modelCatalogue.refresh())
                if (selectionIsFrozen()) {
                    pendingModels = snapshot
                } else {
                    publishModels(snapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(modelsError = safeModelsMessage(error))
            } finally {
                if (modelRefreshJob === currentCoroutineContext()[Job]) {
                    modelRefreshJob = null
                    mutableState.value = mutableState.value.copy(modelsRefreshing = false)
                    applyPendingModelsIfIdle()
                }
            }
        }
        modelRefreshJob = job
        job.start()
    }

    private fun applyPendingModelsIfIdle() {
        if (selectionIsFrozen()) return
        pendingModels?.let { snapshot ->
            pendingModels = null
            publishModels(snapshot)
        }
    }

    private fun completeMessageMutation(job: Job?) {
        if (job == null || messageMutationJob !== job) return
        messageMutationJob = null
        mutableState.value = mutableState.value.copy(messageMutationBusy = false)
        applyPendingModelsIfIdle()
    }

    private fun publishModels(snapshot: ModelCatalogueSnapshot) {
        val current = mutableState.value
        val selected = snapshot.models.firstOrNull {
            it.apiId == current.selectedModel.apiId
        } ?: current.selectedModel
        knownContextWindowMetadata = knownContextWindowMetadata + contextWindowMetadata(snapshot.models)
        mutableState.value = current.copy(
            models = snapshot.models,
            selectedModel = selected,
            modelCatalogueOrigin = snapshot.origin,
            contextWindows = contextWindowsWithModelMetadata(
                bundled = bundledContextWindows,
                models = snapshot.models,
                measuredModelId = current.tokenUsage.measurement?.modelId,
                previous = current.contextWindows,
            ),
        )
    }

    private fun contextWindowsWithModelMetadata(
        bundled: ContextWindowCatalogue,
        models: List<ChatModel>,
        measuredModelId: String? = null,
        previous: ContextWindowCatalogue = ContextWindowCatalogue(),
    ): ContextWindowCatalogue {
        val availableModelIds = models.mapTo(hashSetOf()) { it.apiId }
        val retiredCapacities = knownContextWindowMetadata.filter { (id, tokens) ->
            id !in availableModelIds && tokens > 0
        }
        val apiCapacities = contextWindowMetadata(models)
        // Retain only the last measured model's known capacity; retired models never re-enter choices.
        val retainedMeasurementCapacity = measuredModelId
            ?.takeIf { id -> models.none { it.apiId == id } }
            ?.let { id -> previous.limitFor(id)?.let { id to it } }
            ?.let { mapOf(it) }
            .orEmpty()
        return bundled.copy(limits = bundled.limits + retiredCapacities + apiCapacities + retainedMeasurementCapacity)
    }

    private fun contextWindowMetadata(models: List<ChatModel>): Map<String, Long> = models.mapNotNull { model ->
        model.contextWindowTokens?.takeIf { it > 0 }?.let { model.apiId to it }
    }.toMap()

    private fun safeModelsMessage(error: Exception): String = when ((error as? ServiceException)?.kind) {
        ServiceException.Kind.UNAUTHORIZED -> "Model catalogue request was not authorised."
        ServiceException.Kind.FORBIDDEN -> "Model catalogue access is forbidden."
        ServiceException.Kind.RATE_LIMITED -> "Model catalogue is temporarily rate limited."
        ServiceException.Kind.TIMEOUT -> "Model catalogue request timed out. Try again."
        ServiceException.Kind.BAD_RESPONSE -> "Model catalogue returned an invalid response."
        ServiceException.Kind.UNAVAILABLE -> "Model catalogue service is unavailable. Try again."
        null -> "Models could not be refreshed. Try again."
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

    private fun safeMessage(error: Exception): String = when (error) {
        is CommandCodeException.Unauthorized -> "Invalid or expired API key"
        is CommandCodeException.Forbidden -> "API access is forbidden"
        is CommandCodeException.ZdrUnavailable -> "No ZDR-capable provider is available"
        is CommandCodeException.RateLimited -> "Request rate or plan limit reached"
        is CommandCodeException.InsufficientCredits ->
            "Command Code reports insufficient credits for this request. Check your plan usage and available credits in Command Code before retrying."
        is CommandCodeException.ServerFailure -> "Command Code service failure"
        is AttachmentException -> safeAttachmentMessage(error.kind)
        is TurnFailure -> when (error.kind) {
            TurnFailureKind.EARLY_EOF -> "Response stream ended early. Partial response saved. Try again."
            TurnFailureKind.FRAME_ERROR -> "Response stream could not be read. Partial response saved. Try again."
            TurnFailureKind.COMMIT_REJECTED -> "Response could not be finalised. Partial response saved. Try again."
        }
        is IOException -> "Network stream interrupted. Partial response saved. Try again."
        else -> "The request could not be completed. Try again."
    }

    private fun completionFeedback(notice: CompletionNotice?): String? = when (notice) {
        CompletionNotice.OUTPUT_LIMIT -> "Response reached the output limit."
        CompletionNotice.CONTEXT_LIMIT -> "Response reached the model context limit."
        CompletionNotice.REFUSAL -> "The model declined this request."
        null -> null
    }

    private fun attachmentFailureOf(error: Throwable, fallback: AttachmentFailure): AttachmentFailure = when (error) {
        is AttachmentException -> error.kind
        is KeyRecoveryRequired -> AttachmentFailure.KEY_UNAVAILABLE
        else -> fallback
    }

    private fun attachmentFailure(kind: AttachmentFailure, cause: Throwable? = null): AttachmentException =
        AttachmentException(kind).also { if (cause != null) it.initCause(cause) }

    private fun safeAttachmentMessage(failure: AttachmentFailure): String = when (failure) {
        AttachmentFailure.UNSUPPORTED_MODEL,
        AttachmentFailure.UNVERIFIED_MODEL,
        -> AttachmentPolicy.UNSUPPORTED_MESSAGE
        AttachmentFailure.INVALID_FORMAT -> "This image format is not supported. Choose JPEG, PNG, or WebP."
        AttachmentFailure.CORRUPT_IMAGE -> "Failed to upload image. Please try again."
        AttachmentFailure.TOO_LARGE -> "This image is too large. The maximum is 10 MB."
        AttachmentFailure.TOO_MANY -> "This chat already has the maximum number of images."
        AttachmentFailure.EXCESSIVE_DIMENSIONS -> "This image exceeds the 8000-pixel dimension limit."
        AttachmentFailure.ANIMATED_IMAGE -> "Animated images are not supported."
        AttachmentFailure.STORAGE -> "Attachment storage is unavailable. Try again."
        AttachmentFailure.KEY_UNAVAILABLE -> "Encrypted attachment storage requires recovery."
        AttachmentFailure.STALE_SELECTION -> "This attachment selection is no longer current. Try again."
        AttachmentFailure.INCOMPATIBLE_HISTORY -> "This chat contains incompatible attachment history."
        AttachmentFailure.CLEANUP -> "Attachment cleanup could not be completed. Try again."
    }

    private data class ActiveTurn(
        val pending: PendingTurn,
        val draftGeneration: Long,
        val draftImages: List<ImageAttachment>,
        val partial: StringBuilder = StringBuilder(),
        var usage: TokenUsage? = null,
        var completionNotice: CompletionNotice? = null,
        var completionCommitted: Boolean = false,
    )

    private data class RegisteredTurn(
        val turn: ActiveTurn,
        val messages: List<ApiMessage>,
    )

    private sealed interface SendAction {
        data class Normal(val text: String) : SendAction
        data class FailedRetry(val userMessageId: String) : SendAction
        data class Alternative(val request: AlternativeRequest) : SendAction
    }

    private enum class StreamPhase { COLLECTING, DONE }
    private enum class TurnFailureKind { EARLY_EOF, FRAME_ERROR, COMMIT_REJECTED }
    private class TurnFailure(val kind: TurnFailureKind) : IOException()

    companion object {
        private const val CHECKPOINT_MILLIS = 1_000L
        private const val CHECKPOINT_CHARS = 1_024
        private const val API_KEY_RECOVERY_MESSAGE = "The encrypted API key cannot be opened. Recovery is required."
        private const val API_KEY_CLEAR_FAILURE_MESSAGE = "API key could not be cleared. Try again."
        private const val QUOTA_CACHE_CLEAR_FAILURE_MESSAGE = "Saved quota could not be cleared. Try again."
        private const val PREPARE_FAILURE_MESSAGE = "The request could not be completed. Try again."
        private const val SELECTED_MODEL_UNAVAILABLE_MESSAGE =
            "Selected model is no longer available. Choose another model."
        private const val ATTACHMENT_CLEANUP_WARNING =
            "Conversation deleted, but encrypted attachment cleanup could not be completed."
        private const val USER_ROLE = "USER"
        private const val ASSISTANT_ROLE = "ASSISTANT"
        private val NONTERMINAL_MESSAGE_STATUSES = setOf("PENDING", "STREAMING")

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(
                container.apiKeyStore,
                container.settings,
                container.chatStore,
                container.streamSource,
                container.modelCatalogue,
                container.quota,
                container.attachmentOperations,
                systemPromptStore = container.systemPromptStore,
                contextWindowLoader = container::loadContextWindows,
            ) as T
        }
    }
}
