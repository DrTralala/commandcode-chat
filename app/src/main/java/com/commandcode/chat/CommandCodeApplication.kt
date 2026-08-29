package com.commandcode.chat

import android.app.Application

class CommandCodeApplication : Application() {
    var appContainer: AppContainer? = null
        private set
    var recoveryCause: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            appContainer = AppContainer(this)
        } catch (error: Exception) {
            recoveryCause = error
        }
    }
}
