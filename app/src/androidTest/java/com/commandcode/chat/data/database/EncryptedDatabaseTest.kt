package com.commandcode.chat.data.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.security.DatabaseKeyManager
import com.commandcode.chat.data.security.EncryptedBlobStore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    @Test fun databaseCanOpenWithManagedKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = ChatDatabase.open(context, DatabaseKeyManager(EncryptedBlobStore(context, "test-db-secrets")))
        database.close()
    }
}
