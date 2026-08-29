package com.commandcode.chat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.ui.AppUiState

@Composable
fun ChatScreen(
    state: AppUiState,
    onSelectModel: (ChatModel) -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Secure chat", style = MaterialTheme.typography.headlineSmall)
                Text("LOCAL TRANSCRIPT / ZDR ${if (state.zdr) "ON" else "OFF"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
            Column {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("model_selector").semantics { contentDescription = "Select chat model" },
                ) { Text(state.selectedModel.displayName) }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.testTag("model_menu"),
                ) {
                    ChatModel.entries.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName) },
                            onClick = { onSelectModel(model); menuOpen = false },
                        )
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.messages, key = Message::id) { message -> MessageRow(message.role, message.content, message.status) }
            if (state.streamingText.isNotEmpty()) item { MessageRow("ASSISTANT", state.streamingText, "STREAMING") }
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
            ) { Text("Send securely") }
        }
    }
}

@Composable
private fun MessageRow(role: String, content: String, status: String) {
    Surface(tonalElevation = if (role == "USER") 1.dp else 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("$role · $status", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Text(content)
        }
    }
}
