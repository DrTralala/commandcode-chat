package com.commandcode.chat.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageSizeTest {
    @Test fun zeroBytesFormatAsZeroMb() {
        assertEquals("0 MB", formatStorageBytes(0))
    }

    @Test fun positiveBytesBelowTenThousandFormatAsSubHundredthMb() {
        assertEquals("<0.01 MB", formatStorageBytes(1))
        assertEquals("<0.01 MB", formatStorageBytes(9_999))
    }

    @Test fun tenThousandBytesFormatAsZeroPointZeroOneMb() {
        assertEquals("0.01 MB", formatStorageBytes(10_000))
    }

    @Test fun exactDecimalMegabytesUseTwoPlaces() {
        assertEquals("10.00 MB", formatStorageBytes(10_000_000))
    }

    @Test fun roundedMbStopsJustBelowOneThousand() {
        assertEquals("999.99 MB", formatStorageBytes(999_994_999))
    }

    @Test fun roundedMbOfOneThousandPromotesToOneGb() {
        assertEquals("1.00 GB", formatStorageBytes(999_995_000))
        assertEquals("1.00 GB", formatStorageBytes(1_000_000_000))
    }

    @Test fun wholeGigabytesFormatAsGb() {
        assertEquals("1.50 GB", formatStorageBytes(1_500_000_000))
    }

    @Test fun hundredthsRoundHalfUpNotFloatBiased() {
        assertEquals("1.01 MB", formatStorageBytes(1_005_000))
        assertEquals("1.00 MB", formatStorageBytes(1_004_999))
    }

    @Test fun gbRoundsFromOriginalBytesNotRoundedMbPromotion() {
        assertEquals("1.99 GB", formatStorageBytes(1_994_997_000))
        assertEquals("2.00 GB", formatStorageBytes(1_995_000_000))
        assertEquals("999.99 GB", formatStorageBytes(999_994_999_999))
        assertEquals("1000.00 GB", formatStorageBytes(999_995_000_000))
    }

    @Test fun formattedSizeUsesDecimalPointIndependentOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.50 GB", formatStorageBytes(1_500_000_000))
            assertEquals("10.00 MB", formatStorageBytes(10_000_000))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun negativeBytesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { formatStorageBytes(-1) }
        assertThrows(IllegalArgumentException::class.java) { formatStorageBytes(Long.MIN_VALUE) }
    }
}
