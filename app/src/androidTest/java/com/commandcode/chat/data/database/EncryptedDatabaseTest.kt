package com.commandcode.chat.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.security.DatabaseKeyManager
import com.commandcode.chat.data.security.EncryptedBlobStore
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    @Test fun encryptedDatabasePersistsAndRejectsFrameworkSqlite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("chat.db")
        val keyManager = DatabaseKeyManager(EncryptedBlobStore(context, "test-db-secrets"))
        val database = ChatDatabase.open(context, keyManager)
        database.conversations().insert(ConversationEntity("conversation-1", "Test", "gpt-5.6-sol", 1L, 1L))
        database.close()

        val databaseFile = context.getDatabasePath("chat.db")
        val header = databaseFile.inputStream().use { it.readNBytes(15) }
        assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))

        val reopened = ChatDatabase.open(context, keyManager)
        assertEquals("Test", reopened.conversations().find("conversation-1")?.title)
        reopened.close()

        assertThrows(SQLiteDatabaseCorruptException::class.java) {
            FrameworkSQLiteDatabase.openDatabase(databaseFile.path, null, FrameworkSQLiteDatabase.OPEN_READONLY).use { frameworkDatabase ->
                frameworkDatabase.rawQuery("SELECT name FROM sqlite_master", null).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        }
    }
}
