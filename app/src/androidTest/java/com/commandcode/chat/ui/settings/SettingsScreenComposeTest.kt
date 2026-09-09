package com.commandcode.chat.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.data.security.effectiveSystemPrompt
import com.commandcode.chat.ui.AppUiState
import com.commandcode.chat.ui.SYSTEM_PROMPT_UNAVAILABLE_MESSAGE
import com.commandcode.chat.ui.SystemPromptFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun promptHarness(
        initial: AppUiState,
        onSave: (state: AppUiState, value: String, commit: Int) -> AppUiState = { current, value, _ ->
            val effective = effectiveSystemPrompt(value)
            current.copy(
                systemPrompt = effective,
                systemPromptLoaded = true,
                systemPromptSaving = false,
                systemPromptFeedback = SystemPromptFeedback.SAVED,
                systemPromptCommit = current.systemPromptCommit + 1,
            )
        },
        onClear: (state: AppUiState, commit: Int) -> AppUiState = { current, _ ->
            current.copy(
                systemPrompt = "",
                systemPromptLoaded = true,
                systemPromptSaving = false,
                systemPromptFeedback = SystemPromptFeedback.CLEARED,
                systemPromptCommit = current.systemPromptCommit + 1,
            )
        },
    ) {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = state,
                    onSaveApiKey = {},
                    onClearApiKey = {},
                    onSetZdr = {},
                    onSaveSystemPrompt = { value -> state = onSave(state, value, state.systemPromptCommit) },
                    onClearSystemPrompt = { state = onClear(state, state.systemPromptCommit) },
                )
            }
        }
    }

    @Test
    fun systemPromptOmitsEncryptionHelperCopyButKeepsEditingControls() {
        promptHarness(AppUiState(loading = false, systemPromptLoaded = true, keyConfigured = true))
        compose.onAllNodesWithText("Encrypted locally.", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("The actual instructions", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("system_prompt_field").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save_system_prompt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("clear_system_prompt").assertIsDisplayed()
    }

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
                    onSaveSystemPrompt = {},
                    onClearSystemPrompt = {},
                )
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onAllNodesWithText("SECURITY CONTROL").assertCountEquals(0)
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
                    onSaveSystemPrompt = {},
                    onClearSystemPrompt = {},
                )
            }
        }

        compose.onNodeWithText("Zero data retention (ZDR)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zero data retention (ZDR)").assertIsDisplayed()
        compose.onAllNodesWithText("Zero data retention").assertCountEquals(0)
        compose.onAllNodesWithTag("amoled_toggle").assertCountEquals(0)
        compose.onAllNodesWithText("AMOLED dark mode").assertCountEquals(0)
    }

    @Test
    fun successfulClearOverAnEmptyBaselineClearsTheTypedDraft() {
        promptHarness(
            AppUiState(loading = false, keyConfigured = true, systemPrompt = "", systemPromptLoaded = true),
        )
        compose.onNodeWithTag("system_prompt_field").performScrollTo().performTextInput("unsaved draft")
        compose.onNodeWithTag("clear_system_prompt").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("", editableText("system_prompt_field"))
        compose.onNodeWithText(systemPromptFeedbackText(SystemPromptFeedback.CLEARED)).assertIsDisplayed()
    }

    @Test
    fun whitespaceOnlySaveOverAnEmptyBaselineClearsTheTypedWhitespace() {
        promptHarness(
            AppUiState(loading = false, keyConfigured = true, systemPrompt = "", systemPromptLoaded = true),
        )
        compose.onNodeWithTag("system_prompt_field").performScrollTo().performTextInput("      ")
        compose.onNodeWithTag("save_system_prompt").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("", editableText("system_prompt_field"))
        compose.onNodeWithText(systemPromptFeedbackText(SystemPromptFeedback.SAVED)).assertIsDisplayed()
    }

    @Test
    fun failedSaveRetainsTheTypedDraftAndLastSuccessfulValue() {
        var capturedSystemPrompt = ""
        promptHarness(
            AppUiState(loading = false, keyConfigured = true, systemPrompt = "", systemPromptLoaded = true),
            onSave = { current, _, _ ->
                capturedSystemPrompt = current.systemPrompt
                current.copy(systemPromptFeedback = SystemPromptFeedback.SAVE_FAILED)
            },
        )
        compose.onNodeWithTag("system_prompt_field").performScrollTo().performTextInput("keep me")
        compose.onNodeWithTag("save_system_prompt").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("keep me", editableText("system_prompt_field"))
        compose.onNodeWithText(systemPromptFeedbackText(SystemPromptFeedback.SAVE_FAILED)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("", capturedSystemPrompt) }
    }

    @Test
    fun unloadedPromptShowsTheLoadFailureCopyOnceAcrossFeedbackAndGlobalBanner() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = AppUiState(
                        loading = false,
                        keyConfigured = true,
                        systemPrompt = "",
                        systemPromptLoaded = false,
                        systemPromptFeedback = SystemPromptFeedback.LOAD_FAILED,
                        errorMessage = SYSTEM_PROMPT_UNAVAILABLE_MESSAGE,
                    ),
                    onSaveApiKey = {},
                    onClearApiKey = {},
                    onSetZdr = {},
                    onSaveSystemPrompt = {},
                    onClearSystemPrompt = {},
                )
            }
        }

        compose.onAllNodesWithText(SYSTEM_PROMPT_UNAVAILABLE_MESSAGE).assertCountEquals(1)
    }

    @Test
    fun systemPromptActionsUseSaveLabelAndHaveAComfortableGapAfterTheField() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = AppUiState(
                        loading = false,
                        keyConfigured = true,
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

        compose.onNodeWithTag("save_system_prompt").performScrollTo()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onAllNodesWithText("Save system prompt").assertCountEquals(0)

        val field = compose.onNodeWithTag("system_prompt_field").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("save_system_prompt").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "save action must be separated from the prompt field: field=$field save=$save",
            save.top - field.bottom >= with(compose.density) { 12.dp.toPx() } - 1f,
        )
    }
}
