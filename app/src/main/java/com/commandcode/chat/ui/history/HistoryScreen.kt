package com.commandcode.chat.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.commandcode.chat.data.database.Conversation

@Composable
fun HistoryScreen(
    conversations: List<Conversation>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("history_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Text("ENCRYPTED ON DEVICE", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        if (conversations.isEmpty()) Text("No local conversations yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = Conversation::id) { conversation ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onOpen(conversation.id) }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(conversation.title)
                            Text(conversation.defaultModel.displayName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedButton(
                        onClick = { pendingDelete = conversation },
                        modifier = Modifier
                            .testTag("delete_conversation_${conversation.id}")
                            .semantics { contentDescription = "Delete ${conversation.title}" },
                    ) { Text("Delete") }
                }
            }
        }
    }
    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("This permanently removes the encrypted local conversation.") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(conversation.id); pendingDelete = null },
                    modifier = Modifier.testTag("confirm_delete_conversation"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}
