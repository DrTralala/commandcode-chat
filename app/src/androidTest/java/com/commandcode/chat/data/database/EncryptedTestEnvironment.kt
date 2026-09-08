package com.commandcode.chat.data.database

import android.content.Context
import com.commandcode.chat.ChatStore
import com.commandcode.chat.data.attachments.AndroidAttachmentKeyProvider
import com.commandcode.chat.data.attachments.EncryptedAttachmentStore
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.data.security.DatabaseKeyManager
import com.commandcode.chat.data.security.EncryptedBlobStore
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Test-owned database, wrapper, attachment directory and Keystore aliases. */
internal class EncryptedTestEnvironment(private val context: Context) {
    val databaseName = "chat-task11-${UUID.randomUUID()}.db"
    private val storageName = "task11-db-secrets-${UUID.randomUUID()}"
    val databaseAlias = "commandcode-task11-db-${UUID.randomUUID()}"
    val databaseBlobKey = "databaseKey-${UUID.randomUUID()}"
    val attachmentAlias = "commandcode-task11-attachment-${UUID.randomUUID()}"
    val attachmentRoot = File(context.noBackupFilesDir.canonicalFile, "task11-attachments-${UUID.randomUUID()}")
    val blobStore = EncryptedBlobStore(context, storageName)
    val keyManager = DatabaseKeyManager(
        blobStore,
        alias = databaseAlias,
        blobKey = databaseBlobKey,
    )

    fun openDatabase(): ChatDatabase = ChatDatabase.open(context, keyManager, databaseName)

    fun newAttachmentStore(): EncryptedAttachmentStore = EncryptedAttachmentStore(
        attachmentRoot,
        AndroidAttachmentKeyProvider(attachmentAlias),
    )

    fun hasDatabaseAlias(): Boolean = hasAlias(databaseAlias)

    fun hasAttachmentAlias(): Boolean = hasAlias(attachmentAlias)

    fun deleteAttachmentAlias() = deleteAlias(attachmentAlias)

    fun cleanUp() {
        context.deleteDatabase(databaseName)
        attachmentRoot.deleteRecursively()
        deleteAlias(databaseAlias)
        deleteAlias(attachmentAlias)
        context.deleteSharedPreferences(storageName)
    }

    private fun hasAlias(alias: String): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(alias)

    private fun deleteAlias(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** Adapter allowing the production repository to be used by [AttachmentService]. */
internal class DatabaseChatStore(private val repository: ChatRepository) : ChatStore {
    override fun observeConversations(): Flow<List<Conversation>> = repository.observeConversations()

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        repository.observeMessages(conversationId)

    override suspend fun messagesSnapshot(conversationId: String): List<Message> =
        repository.messagesSnapshot(conversationId)

    override suspend fun beginTurn(
        conversationId: String?,
        text: String,
        model: ChatModel,
        attachments: List<ImageAttachment>,
    ): PendingTurn = repository.beginTurn(conversationId, text, model, attachments)

    override suspend fun checkpointAssistant(messageId: String, text: String) =
        repository.checkpointAssistant(messageId, text)

    override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean =
        repository.completeTurn(messageId, text, usage)

    override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) =
        repository.interruptTurn(messageId, partialText, reason)

    override suspend fun deleteConversation(id: String) = repository.deleteConversation(id)
}
