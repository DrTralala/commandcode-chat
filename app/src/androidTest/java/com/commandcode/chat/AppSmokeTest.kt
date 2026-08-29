package com.commandcode.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
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

        compose.onNodeWithTag("billing_day").performTextClearance()
        compose.onNodeWithTag("billing_day").performTextInput("0")
        compose.onNodeWithTag("save_billing_day").performClick()
        compose.onNodeWithText("Billing day must be from 1 to 31").assertIsDisplayed()

        compose.onNodeWithTag("api_key").performTextInput("fake-offline-key")
        compose.onNodeWithTag("save_api_key").performClick()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()

        compose.onNodeWithTag("nav_chat").performClick()
        compose.onNodeWithText("Secure chat").assertIsDisplayed()
        compose.onNodeWithTag("model_selector").performClick()
        compose.onNodeWithTag("model_menu").onChildren().assertCountEquals(2)
        assertTrue(compose.onAllNodesWithText("GPT-5.6 Sol").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("GPT-5.6 Luna").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("GPT-5.6 Luna").performClick()

        compose.onNodeWithTag("nav_history").performClick()
        compose.onNodeWithTag("history_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_budget").performClick()
        compose.onNodeWithText("Estimated from this app").assertIsDisplayed()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText("API key configured").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithText("API key configured").assertIsDisplayed()
        compose.onAllNodesWithText("Add your API key").assertCountEquals(0)
    }
}
