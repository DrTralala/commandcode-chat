package com.commandcode.chat.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.ui.AppUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun configuredKeyHidesEntryControlsAndClearRevealsThem() {
        var state by mutableStateOf(AppUiState(loading = false, keyConfigured = true))
        var clearCalls = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = state,
                    onSaveApiKey = {},
                    onClearApiKey = {
                        clearCalls += 1
                        state = state.copy(keyConfigured = false)
                    },
                    onSetZdr = {},
                    onSetAmoled = {},
                )
            }
        }

        compose.onNodeWithText("API key: configured").assertIsDisplayed()
        compose.onAllNodesWithTag("api_key").assertCountEquals(0)
        compose.onAllNodesWithTag("save_api_key").assertCountEquals(0)
        compose.onNodeWithTag("clear_api_key").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(1, clearCalls) }
        compose.onNodeWithText("Add your API key").assertIsDisplayed()
        compose.onNodeWithTag("api_key").assertIsDisplayed()
        compose.onNodeWithTag("save_api_key").assertIsDisplayed()
        compose.onAllNodesWithText("Clear API key").assertCountEquals(0)
    }

    @Test
    fun zdrLabelIncludesInitialismInTextAndAccessibilityDescription() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = AppUiState(loading = false),
                    onSaveApiKey = {},
                    onClearApiKey = {},
                    onSetZdr = {},
                    onSetAmoled = {},
                )
            }
        }

        compose.onNodeWithText("Zero data retention (ZDR)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zero data retention (ZDR)").assertIsDisplayed()
        compose.onAllNodesWithText("Zero data retention").assertCountEquals(0)
    }
}
