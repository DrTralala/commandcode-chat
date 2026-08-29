package com.commandcode.chat.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.commandcode.chat.data.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [ConversationEntity::class, MessageEntity::class, UsageEventEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun usageEvents(): UsageEventDao

    companion object {
        fun open(context: Context, keyManager: DatabaseKeyManager): ChatDatabase {
            System.loadLibrary("sqlcipher")
            return keyManager.withPassphrase { passphrase ->
                val database = Room.databaseBuilder(context, ChatDatabase::class.java, "chat.db")
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .fallbackToDestructiveMigration(false)
                    .build()
                database.openHelper.writableDatabase
                database
            }
        }
    }
}
