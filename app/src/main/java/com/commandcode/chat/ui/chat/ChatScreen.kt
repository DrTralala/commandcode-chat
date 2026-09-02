package com.commandcode.chat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.commandcode.chat.R
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.ui.AppUiState

internal fun modelOptionTestTag(apiId: String): String = buildString {
    append("model_option_")
    apiId.forEach { character ->
        append(if (character.isLetterOrDigit()) character else '_')
    }
}

internal enum class MessageSide { START, END }

internal fun messageSide(role: String): MessageSide =
    if (role == "USER") MessageSide.END else MessageSide.START

internal fun chatHeading(state: AppUiState): String {
    val conversationId = state.currentConversationId ?: return "New Chat"
    return state.conversations.firstOrNull { it.id == conversationId }?.title ?: "New Chat"
}

@Composable
fun ChatScreen(
    state: AppUiState,
    onSelectModel: (ChatModel) -> Unit,
    onNewChat: () -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = chatHeading(state),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedIconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("model_selector")
                            .semantics {
                                contentDescription =
                                    "Select chat model. Current: ${state.selectedModel.displayName}"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_brain),
                            contentDescription = null,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.testTag("model_menu"),
                    ) {
                        state.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = { onSelectModel(model); menuOpen = false },
                                modifier = Modifier
                                    .testTag(modelOptionTestTag(model.apiId))
                                    .semantics { contentDescription = "Use ${model.displayName}" },
                            )
                        }
                    }
                }
                OutlinedIconButton(
                    onClick = {
                        draft = ""
                        onNewChat()
                    },
                    enabled = !state.sending,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("new_chat")
                        .semantics { contentDescription = "New chat" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_square),
                        contentDescription = null,
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.visibleMessages, key = Message::id) { message ->
                MessageRow(message.role, message.content, message.status, Modifier.testTag("message_${message.id}"))
            }
            state.visibleStreamingText?.let { liveText ->
                item {
                    MessageRow(
                        "ASSISTANT",
                        liveText,
                        "STREAMING",
                        Modifier.testTag("active_assistant_response"),
                    )
                }
            }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().testTag("message_composer"),
            label = { Text("Message") },
            enabled = !state.sending,
        )
        if (state.sending) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel_send")) { Text("Cancel") }
        } else {
            Button(
                onClick = { val outgoing = draft; draft = ""; onSend(outgoing) },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("send_message"),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageRow(role: String, content: String, status: String, modifier: Modifier = Modifier) {
    val isUser = messageSide(role) == MessageSide.END
    val container = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColour = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = container,
            contentColor = contentColour,
            tonalElevation = if (isUser) 1.dp else 3.dp,
            modifier = modifier
                .fillMaxWidth(0.86f)
                .semantics { contentDescription = if (isUser) "User message" else "Assistant message" },
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("$role · $status", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Text(content)
            }
        }
    }
}
