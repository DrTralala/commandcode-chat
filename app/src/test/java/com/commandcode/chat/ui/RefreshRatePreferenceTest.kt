package com.commandcode.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRatePreferenceTest {
    @Test fun highestCurrentResolutionWinsWithoutSelectingADifferentResolution() {
        val modes = listOf(RefreshMode(1080, 2400, 60f), RefreshMode(1080, 2400, 90f),
            RefreshMode(1080, 2400, 120f), RefreshMode(720, 1600, 144f))
        assertEquals(120f, highestRefreshRate(1080, 2400, modes), 0f)
        assertEquals(144f, highestRefreshRate(720, 1600, modes), 0f)
    }

    @Test fun missingAndInvalidModesDoNotMakeAnInvalidWindowRequest() {
        assertEquals(0f, highestRefreshRate(1080, 2400, emptyList()), 0f)
        assertEquals(0f, highestRefreshRate(1080, 2400, listOf(RefreshMode(1080, 2400, Float.NaN),
            RefreshMode(1080, 2400, Float.POSITIVE_INFINITY), RefreshMode(1080, 2400, -1f))), 0f)
    }
}
