package com.commandcode.chat.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.R

internal fun historySortLabel(sort: HistorySort): String = when (sort) {
    HistorySort.MOST_RECENT -> "Most recent"
    HistorySort.LARGEST_FIRST -> "Largest first"
    HistorySort.SMALLEST_FIRST -> "Smallest first"
}

@Composable
fun HistoryScreen(
    conversations: List<Conversation>,
    sort: HistorySort,
    onSortChange: (HistorySort) -> Unit,
    modelDisplayName: (String) -> String,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    errorMessage: String? = null,
) {
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    val availableIds = conversations.map { it.id }.toSet()
    val selected = selectedIds.intersect(availableIds)
    fun toggle(id: String) {
        selectedIds = if (id in selected) selected - id else selected + id
    }
    fun leaveSelection() {
        selecting = false
        selectedIds = emptySet()
        pendingDelete = null
    }
    var sortMenuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("history_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (selecting) {
                TextButton(
                    onClick = { pendingDelete = selected },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.testTag("history_delete_selected"),
                ) { Text("Delete") }
            }
            IconButton(
                onClick = { if (selecting) leaveSelection() else selecting = true },
                modifier = Modifier.testTag("history_delete_mode"),
            ) {
                Icon(painterResource(R.drawable.ic_delete), if (selecting) "Cancel deletion selection" else "Select conversations to delete")
            }
        }
        Text("ENCRYPTED ON DEVICE", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        if (selecting) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", modifier = Modifier.weight(1f))
                TextButton(onClick = { selectedIds = availableIds }, modifier = Modifier.testTag("history_select_all")) { Text("Select all") }
                TextButton(onClick = { leaveSelection() }, modifier = Modifier.testTag("history_cancel_selection")) { Text("Cancel") }
            }
        }
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Box {
            OutlinedButton(
                onClick = { sortMenuOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_sort")
                    .semantics {
                        contentDescription = "Sort history. Current: ${historySortLabel(sort)}"
                    },
            ) { Text(historySortLabel(sort)) }
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
                modifier = Modifier.testTag("history_sort_menu"),
            ) {
                HistorySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(historySortLabel(option)) },
                        onClick = {
                            onSortChange(option)
                            sortMenuOpen = false
                        },
                        modifier = Modifier.testTag("history_sort_option_${option.name}"),
                    )
                }
            }
        }
        if (conversations.isEmpty()) Text("No local conversations yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortConversations(conversations, sort), key = Conversation::id) { conversation ->
                val sizeLabel = conversationSizeLabel(conversation)
                Row(
                    Modifier.fillMaxWidth().testTag("conversation_row_${conversation.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { if (selecting) toggle(conversation.id) else onOpen(conversation.id) }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(conversation.title)
                            Text(modelDisplayName(conversation.defaultModelId), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            Text(
                                sizeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.semantics {
                                    contentDescription = "Estimated storage: $sizeLabel"
                                },
                            )
                        }
                    }
                    if (selecting) {
                        Checkbox(
                            checked = conversation.id in selected,
                            onCheckedChange = { toggle(conversation.id) },
                            modifier = Modifier.testTag("select_conversation_${conversation.id}")
                                .semantics { contentDescription = "Select ${conversation.title}" },
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { ids ->
        val remainingIds = ids.intersect(availableIds)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${remainingIds.size} ${if (remainingIds.size == 1) "conversation" else "conversations"}?") },
            text = { Text("This removes the selected conversations and their encrypted attachment copies from this device.") },
            confirmButton = {
                TextButton(
                    onClick = { remainingIds.forEach(onDelete); leaveSelection() },
                    enabled = remainingIds.isNotEmpty(),
                    modifier = Modifier.testTag("confirm_delete_conversation"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}
