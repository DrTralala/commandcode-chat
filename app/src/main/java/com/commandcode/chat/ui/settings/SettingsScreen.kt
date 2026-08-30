package com.commandcode.chat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.commandcode.chat.ui.AppUiState

@Composable
fun SettingsScreen(
    state: AppUiState,
    onSaveApiKey: (CharArray) -> Unit,
    onClearApiKey: () -> Unit,
    onSetZdr: (Boolean) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("SECURITY CONTROL", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        Text(if (state.keyConfigured) "API key configured" else "Add your API key")
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
        if (state.keyConfigured) {
            OutlinedButton(onClick = onClearApiKey, modifier = Modifier.fillMaxWidth()) { Text("Clear API key") }
        }
        Column {
            Text("Zero data retention")
            Text("Fail rather than route to a non-ZDR provider.", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = state.zdr,
                onCheckedChange = onSetZdr,
                modifier = Modifier
                    .testTag("zdr_toggle")
                    .semantics { contentDescription = "Zero data retention" },
            )
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
