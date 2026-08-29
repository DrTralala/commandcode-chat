package com.commandcode.chat.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.commandcode.chat.data.security.DatabaseKeyManager
import com.commandcode.chat.data.security.DatabaseRecoveryRequired
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [ConversationEntity::class, MessageEntity::class, UsageEventEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun usageEvents(): UsageEventDao

    companion object {
        fun open(context: Context, keyManager: DatabaseKeyManager): ChatDatabase =
            open(context, keyManager, "chat.db")

        fun open(context: Context, keyManager: DatabaseKeyManager, databaseName: String): ChatDatabase {
            System.loadLibrary("sqlcipher")
            if (!context.getDatabasePath(databaseName).exists()) keyManager.initialiseIfMissing()
            return keyManager.withPassphrase { passphrase ->
                try {
                    // SQLCipher 4.18.0's factory retains the supplied array and exposes no
                    // clearing/close hook for it. Give it its own lifetime-managed copy; the
                    // manager clears the temporary decrypted array after Room consumes it.
                    val factoryPassphrase = passphrase.copyOf()
                    val database = Room.databaseBuilder(context, ChatDatabase::class.java, databaseName)
                    .openHelperFactory(SupportOpenHelperFactory(factoryPassphrase))
                    .build()
                    database.openHelper.writableDatabase
                    database.reconcileUnfinishedTurnsForStartup()
                    database
                } catch (error: Exception) {
                    throw DatabaseRecoveryRequired("Encrypted database requires recovery", error)
                }
            }
        }
    }

    internal fun reconcileUnfinishedTurnsForStartup() {
        val writableDatabase = openHelper.writableDatabase
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL(
                "UPDATE messages SET status = 'INTERRUPTED' " +
                    "WHERE role = 'ASSISTANT' AND status IN ('PENDING', 'STREAMING')",
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}
