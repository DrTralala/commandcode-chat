package com.commandcode.chat.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.commandcode.chat.ui.budget.BudgetScreen
import com.commandcode.chat.ui.chat.ChatScreen
import com.commandcode.chat.ui.history.HistoryScreen
import com.commandcode.chat.ui.settings.SettingsScreen
import com.commandcode.chat.R
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.ui.chat.PickerTicket

private val ElectricBlue = Color(0xFF2F8CFF)
private val Amber = Color(0xFFFFB84D)
private val MutedStatus = Color(0xFF9AA9BC)
internal val ZdrOnColour = Color(0xFF71D6AE)
internal val ZdrOffColour = Color(0xFFFF6B6B)

internal fun zdrStatusColour(zdr: Boolean): Color = if (zdr) ZdrOnColour else ZdrOffColour

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
    val colours = AmoledAppColours
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
    MaterialTheme(colorScheme = colours, typography = AppTypography) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = NunitoFontFamily),
        ) {
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
}

@Composable
private fun AppContent(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingPickerTicket by remember { mutableStateOf<PickerTicket?>(null) }
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val ticket = pendingPickerTicket
        pendingPickerTicket = null
        ticket?.let { viewModel.importAttachments(it, uris) }
    }

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
    val currentEntry by navController.currentBackStackEntryAsState()
    val chatKeyboardVisible = currentEntry?.destination?.route == Route.CHAT &&
        WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var previouslyConfigured by rememberSaveable { mutableStateOf(state.keyConfigured) }
    LaunchedEffect(state.keyConfigured) {
        navigationTarget(previouslyConfigured, state.keyConfigured)?.let { destination ->
            if (previouslyConfigured && !state.keyConfigured) {
                viewModel.clearEphemeralEditForRecovery()
            }
            navController.navigate(destination) {
                if (!state.keyConfigured) popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
        previouslyConfigured = state.keyConfigured
    }
    val currentRoute = currentEntry?.destination?.route
    fun dispatchNavigation(target: EditNavigation) {
        when (target) {
            is EditNavigation.Tab -> navController.navigate(target.route) { launchSingleTop = true }
            is EditNavigation.Conversation -> {
                viewModel.openConversation(target.id)
                navController.navigate(Route.CHAT) { launchSingleTop = true }
            }
            is EditNavigation.Branch -> viewModel.selectMessageAlternative(target.messageId)
            is EditNavigation.EditAnother -> viewModel.startMessageEdit(target.messageId)
            EditNavigation.NewChat -> viewModel.newChat()
            EditNavigation.Back -> viewModel.cancelMessageEdit()
        }
    }
    fun requestNavigation(target: EditNavigation) {
        if (target is EditNavigation.Tab && target.route == currentRoute) return
        if (viewModel.requestEditNavigation(target)) dispatchNavigation(target)
    }
    BackHandler(enabled = state.messageEdit != null) {
        requestNavigation(EditNavigation.Back)
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
                        onNewChat = { requestNavigation(EditNavigation.NewChat) },
                        onSend = viewModel::send,
                        onCancel = viewModel::cancel,
                        onRetryMessage = viewModel::retryMessage,
                        onDeleteFailedMessage = viewModel::deleteFailedMessage,
                        onRetryAssistant = viewModel::retryAssistant,
                        onStartMessageEdit = { requestNavigation(EditNavigation.EditAnother(it)) },
                        onMessageEditChange = viewModel::updateMessageEdit,
                        onSubmitMessageEdit = viewModel::submitMessageEdit,
                        onCancelMessageEdit = viewModel::cancelMessageEdit,
                        onSelectAlternative = { requestNavigation(EditNavigation.Branch(it)) },
                        onDraftChange = viewModel::updateDraft,
                        onAttach = {
                            if (pendingPickerTicket == null) {
                                viewModel.requestAttachmentPicker()?.let { ticket ->
                                    pendingPickerTicket = ticket
                                    pickerLauncher.launch(attachmentPickerMimeTypes(ticket.modelId))
                                }
                            }
                        },
                        onRemoveAttachment = viewModel::removeAttachment,
                        onDismissAttachmentError = viewModel::dismissAttachmentError,
                        loadAttachmentPreview = viewModel::loadAttachmentPreview,
                    )
                }
                composable(Route.HISTORY) {
                    HistoryScreen(
                        conversations = state.conversations,
                        sort = state.historySort,
                        onSortChange = viewModel::setHistorySort,
                        modelDisplayName = { modelId -> modelDisplayName(state.models, modelId) },
                        onOpen = { requestNavigation(EditNavigation.Conversation(it)) },
                        onDelete = viewModel::deleteConversation,
                        errorMessage = state.errorMessage,
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
                        onSaveSystemPrompt = viewModel::saveSystemPrompt,
                        onClearSystemPrompt = viewModel::clearSystemPrompt,
                    )
                }
            }
        }
        if (!chatKeyboardVisible) {
            if (state.keyConfigured) {
                BottomPanel(
                    currentRoute = currentRoute,
                    onNavigate = { route -> requestNavigation(EditNavigation.Tab(route)) },
                )
            }
            SecurityRail(state.zdr)
        }
    }
    state.pendingEditNavigation?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelEditNavigation,
            text = { Text("Unsaved changes will be lost. Proceed?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmEditNavigation()?.let(::dispatchNavigation)
                    },
                    modifier = Modifier.testTag("discard_edit_yes"),
                ) { Text("Yes") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelEditNavigation,
                    modifier = Modifier.testTag("discard_edit_cancel"),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BottomPanel(currentRoute: String?, onNavigate: (String) -> Unit) {
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
                selected = currentRoute == route,
                onClick = { onNavigate(route) },
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

internal fun attachmentPickerMimeTypes(modelId: String): Array<String> =
    AttachmentPolicy.capability(modelId).mimeTypes.toTypedArray()
