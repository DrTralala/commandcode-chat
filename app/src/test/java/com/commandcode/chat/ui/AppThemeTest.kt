package com.commandcode.chat.ui

import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.commandcode.chat.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppThemeTest {
    @Test fun launchWindowAndSystemBarsAreTrueBlack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_CommandCodeChat, true) }
        val values = theme.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowBackground, android.R.attr.statusBarColor, android.R.attr.navigationBarColor,
        ))
        try {
            for (index in 0..2) assertEquals(android.graphics.Color.BLACK, values.getColor(index, -1))
        } finally { values.recycle() }
    }
    @Test
    fun amoledUsesTrueBlackBackgroundAndSurfaces() {
        assertEquals(Color.Black, AmoledAppColours.background)
        assertEquals(Color.Black, AmoledAppColours.surface)
        assertEquals(Color.Black, AmoledAppColours.surfaceContainer)
    }

    @Test
    fun roleContainersRemainDistinct() {
        assertNotEquals(AmoledAppColours.primaryContainer, AmoledAppColours.secondaryContainer)
    }

    @Test
    fun zdrStatusUsesGreenForOnAndRedForOff() {
        assertEquals(Color(0xFF71D6AE), zdrStatusColour(true))
        assertEquals(Color(0xFFFF6B6B), zdrStatusColour(false))
    }
}
