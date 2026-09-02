package com.commandcode.chat.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
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
import com.commandcode.chat.domain.ChatModel

private val Ink = Color(0xFF0B111B)
private val Slate = Color(0xFF131E2C)
private val ElectricBlue = Color(0xFF2F8CFF)
private val Amber = Color(0xFFFFB84D)
private val MutedStatus = Color(0xFF9AA9BC)
internal val ZdrOnColour = Color(0xFF71D6AE)
internal val ZdrOffColour = Color(0xFFFF6B6B)

internal fun zdrStatusColour(zdr: Boolean): Color = if (zdr) ZdrOnColour else ZdrOffColour

internal val StandardAppColours = darkColorScheme(
    primary = ElectricBlue,
    secondary = Amber,
    background = Ink,
    surface = Slate,
    surfaceContainer = Slate,
    primaryContainer = Color(0xFF123D6B),
    secondaryContainer = Color(0xFF164744),
    onPrimary = Color.White,
    onBackground = Color(0xFFE8EEF7),
    onSurface = Color(0xFFE8EEF7),
    onPrimaryContainer = Color(0xFFE8EEF7),
    onSecondaryContainer = Color(0xFFE8EEF7),
)

internal val AmoledAppColours = darkColorScheme(
    primary = ElectricBlue,
    secondary = Amber,
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceVariant = Color.Black,
    surfaceTint = Color.Black,
    primaryContainer = Color(0xFF082B51),
    secondaryContainer = Color(0xFF063D3B),
    onPrimary = Color.White,
    onBackground = Color(0xFFF1F5FA),
    onSurface = Color(0xFFF1F5FA),
    onPrimaryContainer = Color(0xFFF1F5FA),
    onSecondaryContainer = Color(0xFFF1F5FA),
)

@Composable
fun AppRoot(viewModel: AppViewModel?, startupRecovery: Boolean = false) {
    val colours = if (viewModel != null) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        if (state.amoled) AmoledAppColours else StandardAppColours
    } else {
        StandardAppColours
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colours.background.toArgb()
        window.navigationBarColor = colours.background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(colorScheme = colours) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .testTag("app_inset_content"),
            ) {
                when {
                    startupRecovery -> RecoveryScreen("Encrypted local data cannot be opened on this device.")
                    viewModel == null -> RecoveryScreen("Application security could not be initialised.")
                    else -> AppContent(viewModel)
                }
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
    var previouslyConfigured by rememberSaveable { mutableStateOf(state.keyConfigured) }
    LaunchedEffect(state.keyConfigured) {
        navigationTarget(previouslyConfigured, state.keyConfigured)?.let { destination ->
            navController.navigate(destination) {
                if (!state.keyConfigured) popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
        previouslyConfigured = state.keyConfigured
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = initialRoute(state.keyConfigured),
            ) {
                composable(Route.CHAT) {
                    ChatScreen(
                        state = state,
                        onSelectModel = viewModel::selectModel,
                        onNewChat = viewModel::newChat,
                        onSend = viewModel::send,
                        onCancel = viewModel::cancel,
                    )
                }
                composable(Route.HISTORY) {
                    HistoryScreen(
                        conversations = state.conversations,
                        modelDisplayName = { modelId -> modelDisplayName(state.models, modelId) },
                        onOpen = { viewModel.openConversation(it); navController.navigate(Route.CHAT) },
                        onDelete = viewModel::deleteConversation,
                    )
                }
                composable(Route.BUDGET) {
                    BudgetScreen(budget = state.budget, onRefresh = viewModel::refreshQuota)
                }
                composable(Route.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        onSaveApiKey = viewModel::saveApiKey,
                        onClearApiKey = viewModel::clearApiKey,
                        onSetZdr = viewModel::setZdr,
                        onSetAmoled = viewModel::setAmoled,
                    )
                }
            }
        }
        if (state.keyConfigured) BottomPanel(navController)
        SecurityRail(state.zdr)
    }
}

@Composable
private fun BottomPanel(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
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
private fun SecurityRail(zdr: Boolean) {
    val statusDescription = stringResource(R.string.encrypted_local_status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .semantics { contentDescription = statusDescription }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("● ENCRYPTED LOCAL", color = Color(0xFF71D6AE), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        Text(
            text = buildAnnotatedString {
                append("ZDR ")
                withStyle(SpanStyle(color = zdrStatusColour(zdr))) {
                    append(if (zdr) "ON" else "OFF")
                }
            },
            color = MutedStatus,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("security_zdr_status"),
        )
    }
}

@Composable
private fun RecoveryScreen(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp).testTag("recovery_screen"), contentAlignment = Alignment.Center) {
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

internal fun initialRoute(keyConfigured: Boolean): String = if (keyConfigured) Route.CHAT else Route.SETTINGS

internal fun navigationTarget(previouslyConfigured: Boolean, keyConfigured: Boolean): String? = when {
    !previouslyConfigured && keyConfigured -> Route.CHAT
    previouslyConfigured && !keyConfigured -> Route.SETTINGS
    else -> null
}

internal fun modelDisplayName(models: List<ChatModel>, modelId: String): String =
    models.firstOrNull { it.apiId == modelId }?.displayName ?: modelId
