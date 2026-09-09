package com.commandcode.chat.ui.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun ContextUsageIndicator(usage: ContextUsagePresentation, conversationId: String?) {
    var expanded by remember(conversationId) { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = true },
        modifier = Modifier.heightIn(min = 48.dp).testTag("context_usage"),
    ) {
        Text(usage.label, style = MaterialTheme.typography.labelMedium)
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("context_usage_dialog"),
            title = { Text("Context usage") },
            text = {
                Text(usage.details, modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 4.dp))
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } },
        )
    }
}
