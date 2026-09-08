package com.commandcode.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.commandcode.chat.ui.AppRoot
import com.commandcode.chat.ui.AppViewModel
import com.commandcode.chat.ui.RefreshRatePreference

class MainActivity : ComponentActivity() {
    private val refreshRatePreference by lazy { RefreshRatePreference(this) }
    private val commandCodeApplication: CommandCodeApplication
        get() = application as CommandCodeApplication

    val appContainer: AppContainer
        get() = checkNotNull(commandCodeApplication.appContainer) { "Application is in recovery mode" }

    val viewModelForTests: AppViewModel by viewModels { AppViewModel.factory(appContainer) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        recreateUiForTests()
    }

    fun recreateUiForTests() {
        val container = commandCodeApplication.appContainer
        setContent {
            AppRoot(
                viewModel = if (container == null) null else viewModelForTests,
                startupRecovery = commandCodeApplication.recoveryCause != null,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRatePreference.start()
    }

    override fun onPause() {
        refreshRatePreference.stop()
        super.onPause()
    }
}
