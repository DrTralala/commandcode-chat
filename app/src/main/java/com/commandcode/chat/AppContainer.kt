package com.commandcode.chat

import android.content.Context
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
import com.commandcode.chat.data.service.CommandCodeQuotaClient
import com.commandcode.chat.data.service.ModelCatalogueRepository
import com.commandcode.chat.data.service.ModelCatalogueSource
import com.commandcode.chat.data.service.QuotaApi
import com.commandcode.chat.data.service.QuotaRepository
import com.commandcode.chat.data.service.QuotaSource
import com.commandcode.chat.data.service.ServiceSnapshotStore
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import okhttp3.Call
import okhttp3.OkHttpClient

interface SettingsStore {
    var zdr: Boolean
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

    internal fun resetForTests() { preferences.edit().clear().commit() }

    private companion object {
        const val KEY_ZDR = "zdr"
    }
}

fun interface StreamSource {
    fun stream(apiKey: CharArray, model: ChatModel, messages: List<ApiMessage>, zdr: Boolean): Flow<StreamEvent>
}

/** Application-scoped dependencies. The quota API factory is a narrow offline-test seam. */
class AppContainer(
    context: Context,
    databaseFactory: (Context, DatabaseKeyManager) -> ChatDatabase = ChatDatabase::open,
    clientFactory: () -> CommandCodeClient = ::CommandCodeClient,
    streamSourceFactory: ((CommandCodeClient) -> StreamSource)? = null,
    quotaApiFactory: (Call.Factory) -> QuotaApi = { CommandCodeQuotaClient(it) },
) {
    private val applicationContext = context.applicationContext
    private val encryptedStore = EncryptedBlobStore(applicationContext)
    val settings = AppSettings(applicationContext)
    val secretRepository = SecretRepository(encryptedStore)
    val chatDatabase = databaseFactory(applicationContext, DatabaseKeyManager(encryptedStore))
    val chatRepository = ChatRepository(chatDatabase)
    val commandCodeClient = clientFactory()
    private val serviceHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val serviceSnapshotStore = ServiceSnapshotStore(applicationContext)
    val modelCatalogue: ModelCatalogueSource = ModelCatalogueRepository(applicationContext)
    val quota: QuotaSource = QuotaRepository(
        applicationContext,
        quotaApiFactory(serviceHttpClient),
        serviceSnapshotStore,
    )
    val apiKeyStore: ApiKeyStore = object : ApiKeyStore {
        override fun saveApiKey(value: CharArray) = secretRepository.saveApiKey(value)
        override fun readApiKey(): CharArray? = secretRepository.readApiKey()
        override fun clearApiKey() = secretRepository.clearApiKey()
    }
    val chatStore: ChatStore = object : ChatStore {
        override fun observeConversations() = chatRepository.observeConversations()
        override fun observeMessages(conversationId: String) = chatRepository.observeMessages(conversationId)
        override suspend fun messagesSnapshot(conversationId: String) = chatRepository.messagesSnapshot(conversationId)
        override suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel) =
            chatRepository.beginTurn(conversationId, text, model)
        override suspend fun checkpointAssistant(messageId: String, text: String) =
            chatRepository.checkpointAssistant(messageId, text)
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean {
            return chatRepository.completeTurn(messageId, text, usage)
        }
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) =
            chatRepository.interruptTurn(messageId, partialText, reason)
        override suspend fun deleteConversation(id: String) = chatRepository.deleteConversation(id)
    }
    val streamSource: StreamSource = streamSourceFactory?.invoke(commandCodeClient)
        ?: StreamSource(commandCodeClient::stream)
}
