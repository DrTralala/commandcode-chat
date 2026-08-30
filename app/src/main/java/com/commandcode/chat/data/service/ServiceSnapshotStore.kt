package com.commandcode.chat.data.service

import android.content.Context

class ServiceSnapshotStore(context: Context) : QuotaSnapshotStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun loadQuota(): QuotaSnapshot? {
        val encoded = preferences.getString(QUOTA_KEY, null) ?: return null
        return runCatching { QuotaSnapshotCodec.decode(encoded) }.getOrNull()
    }

    override fun saveQuota(snapshot: QuotaSnapshot) {
        val encoded = QuotaSnapshotCodec.encode(snapshot)
        check(preferences.edit().putString(QUOTA_KEY, encoded).commit()) {
            "Could not persist quota snapshot"
        }
    }

    override fun clearQuota() {
        check(preferences.edit().remove(QUOTA_KEY).commit()) {
            "Could not clear quota snapshot"
        }
    }

    companion object {
        const val PREFERENCES_NAME = "service_snapshots"
        const val QUOTA_KEY = "goat_quota"
    }
}
