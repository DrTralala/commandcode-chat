package com.commandcode.chat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.TokenUsage
import com.commandcode.chat.domain.UsageEvent
import com.commandcode.chat.ui.AppRoot
import com.commandcode.chat.ui.AppViewModel
import com.commandcode.chat.data.security.KeyRecoveryRequired
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
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
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CommandCodeApplication
        application.appContainer?.secretRepository?.clearApiKey()
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
        compose.onNodeWithTag("nav_budget").performClick()
        compose.onNodeWithText("Estimated app-local GPT-5.6 Sol equivalent remaining: 14 credits").assertIsDisplayed()
        compose.onNodeWithTag("nav_chat").performClick()
        compose.onNodeWithTag("model_selector").performClick()
        compose.onAllNodes(SemanticsMatcher("actual model options") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("model_option_")
        }).assertCountEquals(2)
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
        compose.onNodeWithText("Estimated app-local GPT-5.6 Luna equivalent remaining: 4 credits").assertIsDisplayed()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText("API key configured").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zero data retention").performClick().assertIsOff()
        compose.onNodeWithTag("security_zdr_status").assertTextEquals("ZDR OFF")

        compose.onNodeWithTag("nav_budget").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithText("Estimated from this app").assertIsDisplayed()
        compose.onAllNodesWithText("Add your API key").assertCountEquals(0)

        compose.activityRule.scenario.close()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil { compose.onAllNodesWithText("Secure chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Secure chat").assertIsDisplayed()
            compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        }
    }

    @Test
    fun startupRecoveryFromFailingSecretStoreIsBlockingAndNonDestructive() {
        val secrets = FailingStartupSecrets()
        val chats = MutationRecordingChats()
        val viewModel = AppViewModel(
            secrets = secrets,
            settings = TestSettings(),
            chats = chats,
            streamSource = StreamSource { _: CharArray, _: ChatModel, _: List<ApiMessage>, _: Boolean -> emptyFlow() },
            budgetTicks = emptyFlow(),
        )
        compose.activity.setContent { AppRoot(viewModel = viewModel) }

        compose.onNodeWithTag("recovery_screen").assertIsDisplayed()
        compose.onNodeWithText("Recovery required").assertIsDisplayed()
        compose.onNodeWithText("No encrypted data has been deleted.").assertIsDisplayed()
        compose.onNodeWithText("Unlock unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
        assertEquals(1, secrets.readCalls)
        assertEquals(0, secrets.mutationCalls)
        assertEquals(0, chats.mutationCalls)
    }

    private class FailingStartupSecrets : ApiKeyStore {
        var readCalls = 0
        var mutationCalls = 0
        override fun readApiKey(): CharArray? {
            readCalls += 1
            throw KeyRecoveryRequired()
        }
        override fun saveApiKey(value: CharArray) { mutationCalls += 1 }
        override fun clearApiKey() { mutationCalls += 1 }
    }

    private class TestSettings : SettingsStore {
        override var zdr = true
        override var billingDay = 1
    }

    private class MutationRecordingChats : ChatStore {
        var mutationCalls = 0
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun messagesSnapshot(conversationId: String): List<Message> = emptyList()
        override fun observeUsageEvents(): Flow<List<UsageEvent>> = flowOf(emptyList())
        override suspend fun beginTurn(conversationId: String?, text: String, model: ChatModel): PendingTurn {
            mutationCalls += 1
            error("not expected")
        }
        override suspend fun checkpointAssistant(messageId: String, text: String) { mutationCalls += 1 }
        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean {
            mutationCalls += 1
            return false
        }
        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) { mutationCalls += 1 }
        override suspend fun deleteConversation(id: String) { mutationCalls += 1 }
    }
}
