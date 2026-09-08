package com.commandcode.chat.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshRateInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun resumedWindowRequestsHighestCurrentResolutionAndReleasesItWhenPaused() {
        compose.waitForIdle()
        compose.runOnIdle {
            val display = requireNotNull(compose.activity.display)
            val mode = display.mode
            val expected = display.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }.maxOf { it.refreshRate }
            assertEquals(expected, compose.activity.window.attributes.preferredRefreshRate, 0.01f)
            assertEquals(0, compose.activity.window.attributes.preferredDisplayModeId)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { assertEquals(0f, it.window.attributes.preferredRefreshRate, 0f) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { org.junit.Assert.assertTrue(compose.activity.window.attributes.preferredRefreshRate > 0f) }
    }
}
