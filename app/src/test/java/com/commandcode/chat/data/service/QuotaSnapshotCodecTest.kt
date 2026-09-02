package com.commandcode.chat.data.service

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QuotaSnapshotCodecTest {
    @Test
    fun versionTwoRoundTripPreservesQuotaWithoutPlanCapOrWindows() {
        val snapshot = QuotaSnapshot(
            fetchedAt = Instant.parse("2026-09-01T12:00:00Z"),
            planId = null,
            limited = null,
            monthly = RemainingQuota(12.5, null),
            fiveHour = null,
            weekly = null,
            purchasedCredits = 3.0,
            freeCredits = 0.0,
        )

        assertEquals(snapshot, QuotaSnapshotCodec.decode(QuotaSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun versionOneCacheMigratesSentinelPlanToNullableDomain() {
        val legacy = """{"schemaVersion":1,"fetchedAt":1800000000000,"planId":"unreported","limited":true,"monthly":{"remaining":42.0,"cap":70.0},"fiveHour":{"used":2.0,"cap":14.0,"resetAt":1800000001234},"weekly":{"used":5.0,"cap":35.0,"resetAt":1800000005678},"purchasedCredits":1.0,"freeCredits":2.0}"""

        val decoded = QuotaSnapshotCodec.decode(legacy)

        assertNull(decoded.planId)
        assertEquals(70.0, decoded.monthly.cap)
        assertEquals(true, decoded.limited)
        assertEquals(14.0, decoded.fiveHour?.cap)
    }

    @Test
    fun versionOneCachePreservesReportedPlan() {
        val legacy = """{"schemaVersion":1,"fetchedAt":1800000000000,"planId":"individual-pro-v1","limited":true,"monthly":{"remaining":42.0,"cap":70.0},"fiveHour":{"used":2.0,"cap":14.0,"resetAt":1800000001234},"weekly":{"used":5.0,"cap":35.0,"resetAt":1800000005678},"purchasedCredits":1.0,"freeCredits":2.0}"""

        assertEquals("individual-pro-v1", QuotaSnapshotCodec.decode(legacy).planId)
    }

    @Test
    fun validationRejectsPartiallyPresentRollingWindows() {
        val snapshot = QuotaSnapshot(
            fetchedAt = Instant.parse("2026-09-01T12:00:00Z"),
            planId = null,
            limited = true,
            monthly = RemainingQuota(12.5, null),
            fiveHour = UsedQuota(1.0, 5.0, Instant.parse("2026-09-01T13:00:00Z")),
            weekly = null,
            purchasedCredits = 3.0,
            freeCredits = 0.0,
        )

        assertThrows(IllegalArgumentException::class.java) {
            QuotaSnapshotCodec.validate(snapshot)
        }
    }
}
