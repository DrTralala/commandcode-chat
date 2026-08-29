package com.commandcode.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.commandcode.chat.ui.budget.BudgetScreen
import com.commandcode.chat.ui.chat.ChatScreen
import com.commandcode.chat.ui.history.HistoryScreen
import com.commandcode.chat.ui.settings.SettingsScreen
import com.commandcode.chat.R

private val Ink = Color(0xFF0B111B)
private val Slate = Color(0xFF131E2C)
private val ElectricBlue = Color(0xFF2F8CFF)
private val Amber = Color(0xFFFFB84D)

private val AppColours = darkColorScheme(
    primary = ElectricBlue,
    secondary = Amber,
    background = Ink,
    surface = Slate,
    onPrimary = Color.White,
    onBackground = Color(0xFFE8EEF7),
    onSurface = Color(0xFFE8EEF7),
)

@Composable
fun AppRoot(viewModel: AppViewModel?, startupRecovery: Boolean = false) {
    MaterialTheme(colorScheme = AppColours) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                startupRecovery -> RecoveryScreen("Encrypted local data cannot be opened on this device.")
                viewModel == null -> RecoveryScreen("Application security could not be initialised.")
                else -> AppContent(viewModel)
            }
        }
    }
}

@Composable
private fun AppContent(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val recoveryMessage = state.recoveryMessage
    if (recoveryMessage != null) {
        RecoveryScreen(recoveryMessage)
        return
    }

    val navController = rememberNavController()
    LaunchedEffect(state.keyConfigured) {
        if (!state.keyConfigured) navController.navigate(Route.SETTINGS) {
            popUpTo(navController.graph.startDestinationId) { inclusive = true }
            launchSingleTop = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = if (state.keyConfigured) Route.CHAT else Route.SETTINGS,
            ) {
                composable(Route.CHAT) {
                    ChatScreen(
                        state = state,
                        onSelectModel = viewModel::selectModel,
                        onSend = viewModel::send,
                        onCancel = viewModel::cancel,
                    )
                }
                composable(Route.HISTORY) {
                    HistoryScreen(
                        conversations = state.conversations,
                        onOpen = { viewModel.openConversation(it); navController.navigate(Route.CHAT) },
                        onDelete = viewModel::deleteConversation,
                    )
                }
                composable(Route.BUDGET) { BudgetScreen(state.budget) }
                composable(Route.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        onSaveApiKey = viewModel::saveApiKey,
                        onClearApiKey = viewModel::clearApiKey,
                        onSetZdr = viewModel::setZdr,
                        onSetBillingDay = viewModel::setBillingDay,
                    )
                }
            }
        }
        if (state.keyConfigured) BottomPanel(navController)
        SecurityRail()
    }
}

@Composable
private fun BottomPanel(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    NavigationBar(
        containerColor = Slate,
        modifier = Modifier.testTag("bottom_navigation"),
    ) {
        listOf(
            Route.CHAT to "Chat",
            Route.HISTORY to "History",
            Route.BUDGET to "Budget",
            Route.SETTINGS to "Settings",
        ).forEach { (route, label) ->
            NavigationBarItem(
                selected = current == route,
                onClick = { navController.navigate(route) { launchSingleTop = true } },
                icon = { Text(label.take(1), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                label = { Text(label) },
                modifier = Modifier.testTag("nav_$route"),
                colors = NavigationBarItemDefaults.colors(indicatorColor = ElectricBlue.copy(alpha = .24f)),
            )
        }
    }
}

@Composable
private fun SecurityRail() {
    val statusDescription = stringResource(R.string.encrypted_local_status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF09101A))
            .semantics { contentDescription = statusDescription }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("● ENCRYPTED LOCAL", color = Color(0xFF71D6AE), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        Text("ZDR READY", color = Color(0xFF9AA9BC), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RecoveryScreen(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Recovery required", style = MaterialTheme.typography.headlineSmall, color = Amber)
            Text(message)
            Text("No encrypted data has been deleted.", fontFamily = FontFamily.Monospace)
            Button(onClick = {}, enabled = false) { Text("Unlock unavailable") }
        }
    }
}

private object Route {
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val BUDGET = "budget"
    const val SETTINGS = "settings"
}
