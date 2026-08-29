package com.commandcode.chat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.ui.AppRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSettings() {
        compose.activity.viewModelForTests.clearApiKey()
        compose.activity.appContainer.settings.resetForTests()
        compose.activity.viewModelForTests.setZdr(true)
        compose.activity.viewModelForTests.setBillingDay(1)
        compose.activity.recreateUiForTests()
        compose.waitForIdle()
    }

    @After
    fun clearKey() {
        compose.activity.appContainer.secretRepository.clearApiKey()
    }

    @Test
    fun firstLaunchSettingsNavigationModelsValidationAndRecreation() {
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithTag("zdr_toggle").assertIsOn()
        compose.onNodeWithContentDescription("Zero data retention").assertIsDisplayed()

        compose.onNodeWithTag("billing_day").performTextClearance()
        compose.onNodeWithTag("billing_day").performTextInput("0")
        compose.onNodeWithTag("save_billing_day").performClick()
        compose.onNodeWithText("Billing day must be from 1 to 31").assertIsDisplayed()
        compose.onNodeWithTag("billing_day").performTextClearance()
        compose.onNodeWithTag("billing_day").performTextInput("32")
        compose.onNodeWithTag("save_billing_day").performClick()
        compose.onNodeWithText("Billing day must be from 1 to 31").assertIsDisplayed()

        compose.onNodeWithTag("api_key").performTextInput("fake-offline-key")
        compose.onNodeWithTag("save_api_key").performClick()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithText("Secure chat").assertIsDisplayed()
        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag("model_option_sol").assertIsDisplayed()
        compose.onNodeWithTag("model_option_luna").assertIsDisplayed().performClick()

        val seeded = runBlocking {
            compose.activity.appContainer.chatRepository.beginTurn(null, "delete me offline", ChatModel.SOL).also {
                compose.activity.appContainer.chatRepository.interruptTurn(it.assistantMessageId, "partial", "test setup")
            }
        }

        compose.onNodeWithTag("nav_history").performClick()
        compose.onNodeWithTag("history_screen").assertIsDisplayed()
        compose.onNodeWithTag("delete_conversation_${seeded.conversationId}").performClick()
        compose.onNodeWithText("Delete conversation?").assertIsDisplayed()
        compose.onNodeWithTag("confirm_delete_conversation").performClick()
        compose.waitUntil {
            compose.onAllNodesWithText("delete me offline").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("nav_budget").performClick()
        compose.onNodeWithText("Estimated from this app").assertIsDisplayed()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText("API key configured").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zero data retention").performClick().assertIsOff()
        compose.onNodeWithTag("security_zdr_status").assertTextEquals("ZDR OFF")

        compose.onNodeWithTag("nav_chat").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithText("Secure chat").assertIsDisplayed()
        compose.onAllNodesWithText("Add your API key").assertCountEquals(0)
    }

    @Test
    fun startupRecoveryIsBlockingAndNonDestructive() {
        compose.activity.setContent { AppRoot(viewModel = null, startupRecovery = true) }

        compose.onNodeWithTag("recovery_screen").assertIsDisplayed()
        compose.onNodeWithText("Recovery required").assertIsDisplayed()
        compose.onNodeWithText("No encrypted data has been deleted.").assertIsDisplayed()
        compose.onNodeWithText("Unlock unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
    }
}
