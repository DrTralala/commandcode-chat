package com.commandcode.chat.data.service

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QuotaSnapshotCodecTest {
    @Test
    fun versionThreeRoundTripPreservesQuotaWithoutPlanCapOrWindows() {
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
        assertNull(decoded.monthlyRenewsAt)
    }

    @Test
    fun versionOneCachePreservesReportedPlan() {
        val legacy = """{"schemaVersion":1,"fetchedAt":1800000000000,"planId":"individual-pro-v1","limited":true,"monthly":{"remaining":42.0,"cap":70.0},"fiveHour":{"used":2.0,"cap":14.0,"resetAt":1800000001234},"weekly":{"used":5.0,"cap":35.0,"resetAt":1800000005678},"purchasedCredits":1.0,"freeCredits":2.0}"""

        assertEquals("individual-pro-v1", QuotaSnapshotCodec.decode(legacy).planId)
    }

    @Test
    fun versionTwoCacheLoadsWithoutRenewalMetadata() {
        val previous = """{"schemaVersion":2,"fetchedAt":1800000000000,"planId":"individual-goat","monthly":{"remaining":42.0,"cap":70.0},"windowLimits":null,"purchasedCredits":1.0,"freeCredits":2.0}"""

        val decoded = QuotaSnapshotCodec.decode(previous)

        assertEquals("individual-goat", decoded.planId)
        assertEquals(RemainingQuota(42.0, 70.0), decoded.monthly)
        assertNull(decoded.monthlyRenewsAt)
    }

    @Test
    fun versionThreeRoundTripEncodesNullableRenewalAsEpochMilliseconds() {
        val renewal = Instant.parse("2026-12-01T00:00:00Z")
        val snapshot = QuotaSnapshot(
            fetchedAt = Instant.parse("2026-11-04T00:00:00Z"),
            planId = "individual-goat",
            limited = null,
            monthly = RemainingQuota(12.5, null),
            fiveHour = null,
            weekly = null,
            purchasedCredits = 3.0,
            freeCredits = 0.0,
            monthlyRenewsAt = renewal,
        )

        val encoded = QuotaSnapshotCodec.encode(snapshot)

        assertEquals(3, JSONObject(encoded).getInt("schemaVersion"))
        assertEquals(1_796_083_200_000L, JSONObject(encoded).getLong("monthlyRenewsAt"))
        assertEquals(snapshot, QuotaSnapshotCodec.decode(encoded))
    }

    @Test
    fun retainsPastRenewalOnAnOlderCachedSnapshot() {
        val snapshot = QuotaSnapshot(
            fetchedAt = Instant.parse("2026-11-04T00:00:00Z"),
            planId = "individual-goat",
            limited = null,
            monthly = RemainingQuota(12.5, null),
            fiveHour = null,
            weekly = null,
            purchasedCredits = 3.0,
            freeCredits = 0.0,
            monthlyRenewsAt = Instant.parse("2026-11-01T00:00:00Z"),
        )

        assertEquals(snapshot, QuotaSnapshotCodec.decode(QuotaSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun versionThreeAllowsNullRenewalAndRejectsInvalidRenewalTimestamps() {
        val snapshot = QuotaSnapshot(
            fetchedAt = Instant.parse("2026-11-04T00:00:00Z"),
            planId = null,
            limited = null,
            monthly = RemainingQuota(12.5, null),
            fiveHour = null,
            weekly = null,
            purchasedCredits = 3.0,
            freeCredits = 0.0,
        )
        val encoded = QuotaSnapshotCodec.encode(snapshot)

        assertNull(QuotaSnapshotCodec.decode(encoded).monthlyRenewsAt)
        assertThrows(IllegalArgumentException::class.java) {
            QuotaSnapshotCodec.validate(snapshot.copy(monthlyRenewsAt = Instant.EPOCH))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuotaSnapshotCodec.validate(snapshot.copy(monthlyRenewsAt = Instant.MAX))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuotaSnapshotCodec.decode(encoded.replace("\"monthlyRenewsAt\":null", "\"monthlyRenewsAt\":0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuotaSnapshotCodec.decode(
                encoded.replace(
                    "\"monthlyRenewsAt\":null",
                    "\"monthlyRenewsAt\":9223372036854775808",
                ),
            )
        }
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
