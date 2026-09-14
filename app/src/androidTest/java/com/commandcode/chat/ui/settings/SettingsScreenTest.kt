package com.commandcode.chat.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.ui.AppUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(keyConfigured: Boolean) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = AppUiState(
                        loading = false,
                        keyConfigured = keyConfigured,
                        systemPromptLoaded = true,
                    ),
                    onSaveApiKey = {},
                    onClearApiKey = {},
                    onSetZdr = {},
                    onSaveSystemPrompt = {},
                    onClearSystemPrompt = {},
                )
            }
        }
    }

    @Test
    fun systemPromptSectionIsHiddenWithoutAConfiguredApiKey() {
        render(keyConfigured = false)

        compose.onAllNodesWithTag("system_prompt_field").assertCountEquals(0)
        compose.onAllNodesWithTag("save_system_prompt").assertCountEquals(0)
        compose.onAllNodesWithTag("clear_system_prompt").assertCountEquals(0)
        compose.onAllNodesWithText("System prompt").assertCountEquals(0)
    }

    @Test
    fun systemPromptSectionIsVisibleWithAConfiguredApiKey() {
        render(keyConfigured = true)

        compose.onNodeWithTag("system_prompt_field").assertIsDisplayed()
        compose.onNodeWithTag("save_system_prompt").assertIsDisplayed()
        compose.onNodeWithTag("clear_system_prompt").assertIsDisplayed()
        compose.onNodeWithText("System prompt").assertIsDisplayed()
    }
}
