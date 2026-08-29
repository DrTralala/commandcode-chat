package com.commandcode.chat

import android.content.Context
import com.commandcode.chat.data.budget.BudgetCalculator
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.CommandCodeClient
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.ChatDatabase
import com.commandcode.chat.data.database.ChatRepository
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.security.DatabaseKeyManager
import com.commandcode.chat.data.security.EncryptedBlobStore
import com.commandcode.chat.data.security.SecretRepository
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import kotlinx.coroutines.flow.Flow

interface SettingsStore {
    var zdr: Boolean
    var billingDay: Int
}

interface ApiKeyStore {
    fun saveApiKey(value: CharArray)
    fun readApiKey(): CharArray?
    fun clearApiKey()
}

interface ChatStore {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun messagesSnapshot(conversationId: String): List<Message>
    fun observeUsageEvents(): Flow<List<UsageEvent>>
    suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel): PendingTurn
    suspend fun checkpointAssistant(messageId: String, text: String)
    suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean
    suspend fun interruptTurn(messageId: String, partialText: String, reason: String)
    suspend fun deleteConversation(id: String)
}

class AppSettings(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    override var zdr: Boolean
        get() = preferences.getBoolean(KEY_ZDR, true)
        set(value) { preferences.edit().putBoolean(KEY_ZDR, value).apply() }

    override var billingDay: Int
        get() = preferences.getInt(KEY_BILLING_DAY, 1).coerceIn(1, 31)
        set(value) {
            require(value in 1..31) { "billingDay must be in 1..31" }
            preferences.edit().putInt(KEY_BILLING_DAY, value).apply()
        }

    internal fun resetForTests() { preferences.edit().clear().commit() }

    private companion object {
        const val KEY_ZDR = "zdr"
        const val KEY_BILLING_DAY = "billingDay"
    }
}

fun interface StreamSource {
    fun stream(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean): Flow<StreamEvent>
}

/** Application-scoped dependencies. Optional factories are narrow offline-test seams. */
class AppContainer(
    context: Context,
    databaseFactory: (Context, DatabaseKeyManager) -> ChatDatabase = ChatDatabase::open,
    clientFactory: () -> CommandCodeClient = ::CommandCodeClient,
    streamSourceFactory: ((CommandCodeClient) -> StreamSource)? = null,
) {
    private val applicationContext = context.applicationContext
    private val encryptedStore = EncryptedBlobStore(applicationContext)
    val settings = AppSettings(applicationContext)
    val secretRepository = SecretRepository(encryptedStore)
    val chatDatabase = databaseFactory(applicationContext, DatabaseKeyManager(encryptedStore))
    val chatRepository = ChatRepository(chatDatabase)
    val commandCodeClient = clientFactory()
    val budgetCalculator = BudgetCalculator
    val apiKeyStore: ApiKeyStore = object : ApiKeyStore {
        override fun saveApiKey(value: CharArray) = secretRepository.saveApiKey(value)
        override fun readApiKey(): CharArray? = secretRepository.readApiKey()
        override fun clearApiKey() = secretRepository.clearApiKey()
    }
    val chatStore: ChatStore = object : ChatStore {
        override fun observeConversations() = chatRepository.observeConversations()
        override fun observeMessages(conversationId: String) = chatRepository.observeMessages(conversationId)
        override suspend fun messagesSnapshot(conversationId: String) = chatRepository.messagesSnapshot(conversationId)
        override fun observeUsageEvents() = chatRepository.observeUsageEvents()
        override suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel) =
            chatRepository.beginTurn(conversationId, text, model)
        override suspend fun checkpointAssistant(messageId: String, text: String) =
            chatRepository.checkpointAssistant(messageId, text)
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean {
            chatRepository.completeTurn(messageId, text, usage)
            return chatRepository.isTurnComplete(messageId)
        }
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) =
            chatRepository.interruptTurn(messageId, partialText, reason)
        override suspend fun deleteConversation(id: String) = chatRepository.deleteConversation(id)
    }
    val streamSource: StreamSource = streamSourceFactory?.invoke(commandCodeClient)
        ?: StreamSource(commandCodeClient::stream)
}
