package com.commandcode.chat.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppThemeTest {
    @Test
    fun amoledUsesTrueBlackBackgroundAndSurfaces() {
        assertEquals(Color.Black, AmoledAppColours.background)
        assertEquals(Color.Black, AmoledAppColours.surface)
        assertEquals(Color.Black, AmoledAppColours.surfaceContainer)
    }

    @Test
    fun roleContainersRemainDistinctInBothThemes() {
        assertNotEquals(StandardAppColours.primaryContainer, StandardAppColours.secondaryContainer)
        assertNotEquals(AmoledAppColours.primaryContainer, AmoledAppColours.secondaryContainer)
    }

    @Test
    fun zdrStatusUsesGreenForOnAndRedForOff() {
        assertEquals(Color(0xFF71D6AE), zdrStatusColour(true))
        assertEquals(Color(0xFFFF6B6B), zdrStatusColour(false))
    }
}
