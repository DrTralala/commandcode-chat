package com.commandcode.chat.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.attachments.AndroidImageTestFixtures
import com.commandcode.chat.data.attachments.AttachmentService
import com.commandcode.chat.data.attachments.ImageImporter
import com.commandcode.chat.data.attachments.ImageInspector
import com.commandcode.chat.data.attachments.OwnedImageSources
import com.commandcode.chat.data.security.DatabaseKeyManager
import com.commandcode.chat.data.security.EncryptedBlob
import com.commandcode.chat.data.security.EncryptedBlobStore
import com.commandcode.chat.data.security.DatabaseRecoveryRequired
import com.commandcode.chat.data.security.KeystoreCipher
import com.commandcode.chat.data.security.SecureStoragePersistenceFailure
import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    @Test fun encryptedDatabasePersistsAndRejectsFrameworkSqlite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = TestDatabaseResources(context)
        try {
            val database = ChatDatabase.open(context, resources.keyManager, resources.databaseName)
            database.conversations().insert(ConversationEntity("conversation-1", "Test", "gpt-5.6-sol", 1L, 1L))
            database.close()

            val databaseFile = context.getDatabasePath(resources.databaseName)
            val header = databaseFile.inputStream().use { it.readNBytes(15) }
            assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))

            val reopened = ChatDatabase.open(context, resources.keyManager, resources.databaseName)
            assertEquals("Test", reopened.conversations().find("conversation-1")?.title)
            reopened.close()

            assertThrows(SQLiteDatabaseCorruptException::class.java) {
                FrameworkSQLiteDatabase.openDatabase(databaseFile.path, null, FrameworkSQLiteDatabase.OPEN_READONLY).use { frameworkDatabase ->
                    frameworkDatabase.rawQuery("SELECT name FROM sqlite_master", null).use { cursor ->
                        cursor.moveToFirst()
                    }
                }
            }
        } finally {
            resources.cleanUp()
        }
    }

    @Test fun deletingActualDatabaseAliasRequiresRecoveryWithoutMutatingWrapperOrDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = TestDatabaseResources(context)
        try {
            ChatDatabase.open(context, resources.keyManager, resources.databaseName).useDatabase { database ->
                database.conversations().insert(ConversationEntity("conversation-1", "Keep", "gpt-5.6-sol", 1L, 1L))
            }
            val databaseFile = context.getDatabasePath(resources.databaseName)
            val fileBefore = databaseFile.readBytes()
            val wrapperBefore = resources.store.rawValue(resources.blobKey)
            resources.deleteAlias()

            assertThrows(DatabaseRecoveryRequired::class.java) {
                ChatDatabase.open(context, resources.keyManager, resources.databaseName)
            }

            assertArrayEquals(fileBefore, databaseFile.readBytes())
            assertEquals(wrapperBefore, resources.store.rawValue(resources.blobKey))
            assertFalse(resources.hasAlias())
        } finally {
            resources.cleanUp()
        }
    }

    @Test fun existingDatabaseWithMissingWrapperRequiresRecoveryWithoutMutationOrReplacementAlias() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = TestDatabaseResources(context)
        try {
            ChatDatabase.open(context, resources.keyManager, resources.databaseName).useDatabase { database ->
                database.conversations().insert(ConversationEntity("conversation-1", "Keep", "gpt-5.6-sol", 1L, 1L))
            }
            val databaseFile = context.getDatabasePath(resources.databaseName)
            val fileBefore = databaseFile.readBytes()
            resources.store.remove(resources.blobKey)
            resources.deleteAlias()

            assertThrows(DatabaseRecoveryRequired::class.java) {
                ChatDatabase.open(context, resources.keyManager, resources.databaseName)
            }

            assertArrayEquals(fileBefore, databaseFile.readBytes())
            assertNull(resources.store.rawValue(resources.blobKey))
            assertFalse(resources.hasAlias())
        } finally {
            resources.cleanUp()
        }
    }

    @Test fun failedWrapperPersistenceAbortsBeforeDatabaseCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val databaseName = "chat-failed-wrapper-$suffix.db"
        val storageName = "test-failed-wrapper-$suffix"
        val alias = "commandcode-test-db-$suffix"
        val blobKey = "databaseKey-$suffix"
        val store = InstallingThenFailingStore(context, storageName)
        val keyManager = DatabaseKeyManager(store, alias = alias, blobKey = blobKey)
        try {
            assertThrows(DatabaseRecoveryRequired::class.java) {
                ChatDatabase.open(context, keyManager, databaseName)
            }
            assertTrue(store.wrapperWasObservableBeforeFailure)
            assertFalse(context.getDatabasePath(databaseName).exists())
            assertNull(store.rawValue(blobKey))
            assertFalse(hasAlias(alias))
        } finally {
            context.deleteDatabase(databaseName)
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    @Test fun failedWrapperPersistenceDoesNotRemovePreExistingAlias() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val databaseName = "chat-failed-wrapper-existing-alias-$suffix.db"
        val storageName = "test-failed-wrapper-existing-alias-$suffix"
        val alias = "commandcode-test-db-existing-$suffix"
        val blobKey = "databaseKey-$suffix"
        val store = InstallingThenFailingStore(context, storageName)
        val keyManager = DatabaseKeyManager(store, alias = alias, blobKey = blobKey)
        try {
            KeystoreCipher().encrypt(alias, ByteArray(32))
            assertTrue(hasAlias(alias))

            assertThrows(DatabaseRecoveryRequired::class.java) {
                ChatDatabase.open(context, keyManager, databaseName)
            }

            assertTrue(store.wrapperWasObservableBeforeFailure)
            assertFalse(context.getDatabasePath(databaseName).exists())
            assertNull(store.rawValue(blobKey))
            assertTrue(hasAlias(alias))
        } finally {
            context.deleteDatabase(databaseName)
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    @Test fun wrapperRollbackFailureIsSuppressedWithoutHidingPrimaryPersistenceFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val databaseName = "chat-failed-wrapper-rollback-$suffix.db"
        val storageName = "test-failed-wrapper-rollback-$suffix"
        val alias = "commandcode-test-db-rollback-$suffix"
        val blobKey = "databaseKey-$suffix"
        val store = InstallingThenFailingStore(context, storageName, failRemovalAfterApplying = true)
        val keyManager = DatabaseKeyManager(store, alias = alias, blobKey = blobKey)
        try {
            val failure = assertThrows(DatabaseRecoveryRequired::class.java) {
                ChatDatabase.open(context, keyManager, databaseName)
            }

            assertTrue(failure.cause is SecureStoragePersistenceFailure)
            assertEquals(1, failure.cause!!.suppressed.size)
            assertTrue(failure.cause!!.suppressed.single() is SecureStoragePersistenceFailure)
            assertFalse(context.getDatabasePath(databaseName).exists())
            assertNull(store.rawValue(blobKey))
            assertFalse(hasAlias(alias))
        } finally {
            context.deleteDatabase(databaseName)
            deleteAlias(alias)
            context.deleteSharedPreferences(storageName)
        }
    }

    @Test fun reopeningDatabaseReconcilesUnfinishedAssistantRowsAndPreservesCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = TestDatabaseResources(context)
        try {
            ChatDatabase.open(context, resources.keyManager, resources.databaseName).useDatabase { database ->
                database.conversations().insert(ConversationEntity("conversation", "Test", "gpt-5.6-sol", 1L, 1L))
                database.messages().insert(MessageEntity("pending", "conversation", "ASSISTANT", "", "gpt-5.6-sol", 2L, "PENDING"))
                database.messages().insert(MessageEntity("streaming", "conversation", "ASSISTANT", "checkpoint", "gpt-5.6-sol", 3L, "STREAMING"))
            }

            ChatDatabase.open(context, resources.keyManager, resources.databaseName).useDatabase { reopened ->
                assertEquals("INTERRUPTED", reopened.messages().find("pending")?.status)
                assertEquals("", reopened.messages().find("pending")?.content)
                assertEquals("INTERRUPTED", reopened.messages().find("streaming")?.status)
                assertEquals("checkpoint", reopened.messages().find("streaming")?.content)
            }
        } finally {
            resources.cleanUp()
        }
    }

    @Test
    fun encryptedExportedV1MigratesToProductionV2AndReopensOwnedImageAfterSourceRevocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = EncryptedTestEnvironment(context)
        var migrated: ChatDatabase? = null
        var reopened: ChatDatabase? = null
        try {
            // The fixture passphrase is generated and wrapped by the test-owned
            // Android Keystore alias, then consumed again by ChatDatabase.open.
            environment.keyManager.initialiseIfMissing()
            assertTrue(environment.hasDatabaseAlias())
            val wrapperBefore = requireNotNull(environment.blobStore.rawValue(environment.databaseBlobKey))
            val databaseFile = context.getDatabasePath(environment.databaseName)
            environment.keyManager.withPassphrase { passphrase ->
                SqlCipherV1TestFixture.create(databaseFile, passphrase)
            }

            val v1Bytes = databaseFile.readBytes()
            assertFalse(String(v1Bytes.copyOf(15), Charsets.US_ASCII).startsWith("SQLite format 3"))
            assertThrows(SQLiteException::class.java) {
                FrameworkSQLiteDatabase.openDatabase(
                    databaseFile.path,
                    null,
                    FrameworkSQLiteDatabase.OPEN_READONLY,
                ).use { plaintextDatabase ->
                    plaintextDatabase.rawQuery("SELECT name FROM sqlite_master", null).use { cursor ->
                        cursor.moveToFirst()
                    }
                }
            }

            val migratedDatabase = environment.openDatabase()
            migrated = migratedDatabase
            assertTrue(environment.hasDatabaseAlias())
            assertEquals(wrapperBefore, environment.blobStore.rawValue(environment.databaseBlobKey))
            assertEquals(
                ConversationEntity(
                    SqlCipherV1TestFixture.CONVERSATION_ID,
                    "Migrated image history",
                    "gpt-5.6-sol",
                    1000L,
                    2000L,
                ),
                migratedDatabase.conversations().find(SqlCipherV1TestFixture.CONVERSATION_ID),
            )
            assertEquals(
                MessageEntity(
                    SqlCipherV1TestFixture.USER_MESSAGE_ID,
                    SqlCipherV1TestFixture.CONVERSATION_ID,
                    "USER",
                    "v1 prompt",
                    null,
                    3000L,
                    "COMPLETE",
                ),
                migratedDatabase.messages().find(SqlCipherV1TestFixture.USER_MESSAGE_ID),
            )
            assertEquals(
                MessageEntity(
                    SqlCipherV1TestFixture.ASSISTANT_MESSAGE_ID,
                    SqlCipherV1TestFixture.CONVERSATION_ID,
                    "ASSISTANT",
                    "v1 answer",
                    "gpt-5.6-sol",
                    4000L,
                    "COMPLETE",
                ),
                migratedDatabase.messages().find(SqlCipherV1TestFixture.ASSISTANT_MESSAGE_ID),
            )
            assertEquals(
                UsageEventEntity(
                    SqlCipherV1TestFixture.USAGE_ID,
                    SqlCipherV1TestFixture.ASSISTANT_MESSAGE_ID,
                    SqlCipherV1TestFixture.CONVERSATION_ID,
                    "gpt-5.6-luna",
                    5000L,
                    10L,
                    2L,
                    7L,
                    "0.0001",
                    "0.0002",
                    true,
                ),
                migratedDatabase.usageEvents()
                    .observeForConversation(SqlCipherV1TestFixture.CONVERSATION_ID)
                    .first()
                    .single(),
            )

            val migratedRepository = ChatRepository(migratedDatabase)
            val attachmentFiles = environment.newAttachmentStore()
            val attachmentService = AttachmentService(
                chatStore = DatabaseChatStore(migratedRepository),
                attachmentDao = migratedDatabase.attachments(),
                imageImporter = ImageImporter(
                    context.contentResolver,
                    ImageInspector(),
                    attachmentFiles,
                ),
                files = attachmentFiles,
            )
            attachmentService.initialise()
            val source = OwnedImageSources.publish(AndroidImageTestFixtures.all().first())
            val image = attachmentService.importImage(source.uri, ChatModel.SOL.apiId)
            val turn = attachmentService.prepareTurn(
                conversationId = SqlCipherV1TestFixture.CONVERSATION_ID,
                text = "",
                model = ChatModel.SOL,
                images = listOf(image),
            )
            assertEquals(SqlCipherV1TestFixture.CONVERSATION_ID, turn.pending.conversationId)
            assertEquals(1, turn.messages.single().images.size)

            // The MediaStore source is no longer available; the app-owned ciphertext is
            // the only input used by the reopened service below.
            OwnedImageSources.revoke(source)
            assertFalse(OwnedImageSources.isAvailable(source.uri))
            migratedDatabase.close()
            migrated = null

            val reopenedDatabase = environment.openDatabase()
            reopened = reopenedDatabase
            assertTrue(environment.hasDatabaseAlias())
            assertEquals(wrapperBefore, environment.blobStore.rawValue(environment.databaseBlobKey))
            val reopenedRepository = ChatRepository(reopenedDatabase)
            val reloadedImage = reopenedRepository.messagesSnapshot(SqlCipherV1TestFixture.CONVERSATION_ID)
                .flatMap { it.attachments }
                .single { it.id == image.id }
            assertEquals(image, reloadedImage)

            val reopenedFiles = environment.newAttachmentStore()
            val reopenedService = AttachmentService(
                chatStore = DatabaseChatStore(reopenedRepository),
                attachmentDao = reopenedDatabase.attachments(),
                imageImporter = ImageImporter(
                    context.contentResolver,
                    ImageInspector(),
                    reopenedFiles,
                ),
                files = reopenedFiles,
            )
            reopenedService.initialise()
            val preview = reopenedService.preview(image.id, maxEdge = 256)
            try {
                assertEquals(image.width, preview.width)
                assertEquals(image.height, preview.height)
            } finally {
                preview.recycle()
            }
        } finally {
            reopened?.close()
            migrated?.close()
            OwnedImageSources.clear()
            environment.cleanUp()
        }
    }

    private class TestDatabaseResources(private val context: Context) {
        private val suffix = UUID.randomUUID().toString()
        val databaseName = "chat-test-$suffix.db"
        private val storageName = "test-db-secrets-$suffix"
        private val alias = "commandcode-test-db-$suffix"
        val blobKey = "databaseKey-$suffix"
        val store = EncryptedBlobStore(context, storageName)
        val keyManager = DatabaseKeyManager(store, alias = alias, blobKey = blobKey)

        fun hasAlias(): Boolean =
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

        fun deleteAlias() = Companion.deleteAlias(alias)

        fun cleanUp() {
            context.deleteDatabase(databaseName)
            deleteAlias()
            context.deleteSharedPreferences(storageName)
        }
    }

    private class InstallingThenFailingStore(
        context: Context,
        name: String,
        private val failRemovalAfterApplying: Boolean = false,
    ) : EncryptedBlobStore(context, name) {
        var wrapperWasObservableBeforeFailure = false

        override fun put(key: String, blob: EncryptedBlob) {
            super.put(key, blob)
            wrapperWasObservableBeforeFailure = rawValue(key) != null
            throw SecureStoragePersistenceFailure(IllegalStateException("scripted checked persistence failure"))
        }

        override fun remove(key: String) {
            super.remove(key)
            if (failRemovalAfterApplying) {
                throw SecureStoragePersistenceFailure(IllegalStateException("scripted rollback failure"))
            }
        }
    }

    private inline fun <T> ChatDatabase.useDatabase(block: (ChatDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private companion object {
        fun hasAlias(alias: String): Boolean =
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

        fun deleteAlias(alias: String) {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }
}
