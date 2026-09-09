package com.commandcode.chat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.commandcode.chat.ui.AppUiState
import com.commandcode.chat.ui.SYSTEM_PROMPT_UNAVAILABLE_MESSAGE
import com.commandcode.chat.ui.SystemPromptFeedback
import com.commandcode.chat.ui.ZdrOnColour

internal fun apiKeyConfiguredStatus() = buildAnnotatedString {
    append("API key: ")
    withStyle(SpanStyle(color = ZdrOnColour)) { append("configured") }
}

internal fun systemPromptFeedbackText(feedback: SystemPromptFeedback): String = when (feedback) {
    SystemPromptFeedback.SAVED ->
        "System prompt saved. It will apply to the next message sent in any chat."
    SystemPromptFeedback.CLEARED ->
        "System prompt cleared. Empty instructions will apply to the next message sent in any chat."
    SystemPromptFeedback.SAVE_FAILED -> "System prompt could not be saved. Try again."
    SystemPromptFeedback.CLEAR_FAILED -> "System prompt could not be cleared. Try again."
    SystemPromptFeedback.LOAD_FAILED -> SYSTEM_PROMPT_UNAVAILABLE_MESSAGE
}

private val SYSTEM_PROMPT_ACTION_ROW_TOP_PADDING = 12.dp

@Composable
fun SettingsScreen(
    state: AppUiState,
    onSaveApiKey: (CharArray) -> Unit,
    onClearApiKey: () -> Unit,
    onSetZdr: (Boolean) -> Unit,
    onSaveSystemPrompt: (String) -> Unit,
    onClearSystemPrompt: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    // Reset the editor after every successful save/clear (commit advances even when
    // the effective value is unchanged, e.g. clear or whitespace-only save over an
    // empty baseline); failed operations leave the draft untouched for retry.
    var promptDraft by remember(state.systemPrompt, state.systemPromptCommit) { mutableStateOf(state.systemPrompt) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        if (state.keyConfigured) {
            Text(apiKeyConfiguredStatus())
            OutlinedButton(
                onClick = onClearApiKey,
                modifier = Modifier.fillMaxWidth().testTag("clear_api_key"),
            ) { Text("Clear API key") }
        } else {
            Text("Add your API key")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth().testTag("api_key"),
                label = { Text("Command Code API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
            )
            Button(
                onClick = { val chars = apiKey.toCharArray(); apiKey = ""; onSaveApiKey(chars) },
                enabled = apiKey.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("save_api_key"),
            ) { Text("Encrypt and save key") }
        }
        Column {
            Text("Zero data retention (ZDR)")
            Text("Fail rather than route to a non-ZDR provider.", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = state.zdr,
                onCheckedChange = onSetZdr,
                modifier = Modifier
                    .testTag("zdr_toggle")
                    .semantics { contentDescription = "Zero data retention (ZDR)" },
            )
        }
        Column {
            Text("System prompt")
            Text(
                "Encrypted locally. The actual instructions are sent to the provider with " +
                    "every message you send, including images.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .testTag("system_prompt_field"),
                label = { Text("System instructions") },
                enabled = !state.systemPromptSaving,
                minLines = 5,
                maxLines = 12,
            )
            state.systemPromptFeedback?.let { feedback ->
                Text(
                    systemPromptFeedbackText(feedback),
                    color = if (feedback == SystemPromptFeedback.SAVED ||
                        feedback == SystemPromptFeedback.CLEARED
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Row(
                modifier = Modifier
                    .padding(top = SYSTEM_PROMPT_ACTION_ROW_TOP_PADDING),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onSaveSystemPrompt(promptDraft) },
                    enabled = !state.systemPromptSaving && !state.loading,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_system_prompt"),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = onClearSystemPrompt,
                    enabled = !state.systemPromptSaving && !state.loading,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clear_system_prompt"),
                ) { Text("Clear") }
            }
        }
        // The load-failure reason is already shown as feedback under the System
        // Prompt field; do not repeat the identical sentence in the general banner.
        val visibleFeedbackCopy = state.systemPromptFeedback?.let(::systemPromptFeedbackText)
        state.errorMessage
            ?.takeUnless { it == visibleFeedbackCopy }
            ?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
