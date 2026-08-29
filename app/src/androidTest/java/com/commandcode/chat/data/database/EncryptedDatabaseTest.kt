package com.commandcode.chat.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.security.DatabaseKeyManager
import com.commandcode.chat.data.security.EncryptedBlobStore
import com.commandcode.chat.data.security.DatabaseRecoveryRequired
import com.commandcode.chat.data.security.KeystoreCipher
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
        val store = object : EncryptedBlobStore(context, storageName) {
            override fun put(key: String, blob: com.commandcode.chat.data.security.EncryptedBlob) {
                throw IllegalStateException("scripted checked persistence failure")
            }
        }
        val keyManager = DatabaseKeyManager(store, alias = alias, blobKey = blobKey)
        try {
            assertThrows(DatabaseRecoveryRequired::class.java) {
                ChatDatabase.open(context, keyManager, databaseName)
            }
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
        val store = object : EncryptedBlobStore(context, storageName) {
            override fun put(key: String, blob: com.commandcode.chat.data.security.EncryptedBlob) {
                throw IllegalStateException("scripted checked persistence failure")
            }
        }
        val keyManager = DatabaseKeyManager(store, alias = alias, blobKey = blobKey)
        try {
            KeystoreCipher().encrypt(alias, ByteArray(32))
            assertTrue(hasAlias(alias))

            assertThrows(DatabaseRecoveryRequired::class.java) {
                ChatDatabase.open(context, keyManager, databaseName)
            }

            assertFalse(context.getDatabasePath(databaseName).exists())
            assertNull(store.rawValue(blobKey))
            assertTrue(hasAlias(alias))
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
