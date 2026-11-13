package com.commandcode.chat.data.service

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaSnapshotCodecTest {
    @Test
    fun versionFourRoundTripPreservesQuotaWithoutPlanCapOrWindows() {
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
    fun versionThreeCacheMigratesWithPositiveResetsAndRenewalMetadata() {
        val legacy = """{"schemaVersion":3,"fetchedAt":1800000000000,"planId":"individual-goat","monthlyRenewsAt":1801000000000,"monthly":{"remaining":42.5,"cap":70.0},"windowLimits":{"limited":true,"fiveHour":{"used":4.5,"cap":14.0,"resetAt":1800000001234},"weekly":{"used":22.75,"cap":35.0,"resetAt":1800000005678}},"purchasedCredits":9.5,"freeCredits":0.0}"""

        val migrated = QuotaSnapshotCodec.decode(legacy)
        val encoded = QuotaSnapshotCodec.encode(migrated)

        assertEquals(Instant.ofEpochMilli(1_801_000_000_000), migrated.monthlyRenewsAt)
        assertEquals(Instant.ofEpochMilli(1_800_000_001_234), migrated.fiveHour?.resetAt)
        assertEquals(4, JSONObject(encoded).getInt("schemaVersion"))
        assertEquals(migrated, QuotaSnapshotCodec.decode(encoded))
    }

    @Test
    fun versionsOneTwoAndThreeKeepStrictPositiveResetTimestampContract() {
        val legacySnapshots = listOf(
            """{"schemaVersion":1,"fetchedAt":1800000000000,"planId":"individual-goat","limited":true,"monthly":{"remaining":42.5,"cap":70.0},"fiveHour":{"used":4.5,"cap":14.0,"resetAt":1800000001234},"weekly":{"used":22.75,"cap":35.0,"resetAt":1800000005678},"purchasedCredits":9.5,"freeCredits":0.0}""",
            """{"schemaVersion":2,"fetchedAt":1800000000000,"planId":"individual-goat","monthly":{"remaining":42.5,"cap":70.0},"windowLimits":{"limited":true,"fiveHour":{"used":4.5,"cap":14.0,"resetAt":1800000001234},"weekly":{"used":22.75,"cap":35.0,"resetAt":1800000005678}},"purchasedCredits":9.5,"freeCredits":0.0}""",
            """{"schemaVersion":3,"fetchedAt":1800000000000,"planId":"individual-goat","monthlyRenewsAt":1801000000000,"monthly":{"remaining":42.5,"cap":70.0},"windowLimits":{"limited":true,"fiveHour":{"used":4.5,"cap":14.0,"resetAt":1800000001234},"weekly":{"used":22.75,"cap":35.0,"resetAt":1800000005678}},"purchasedCredits":9.5,"freeCredits":0.0}""",
        )

        legacySnapshots.forEach { legacy ->
            assertTrue(QuotaSnapshotCodec.decode(legacy).fiveHour?.resetAt?.isAfter(Instant.EPOCH) == true)
            listOf("0", "-1").forEach { unavailableTimestamp ->
                val invalidLegacy = legacy.replace("1800000001234", unavailableTimestamp)
                assertThrows(IllegalArgumentException::class.java) {
                    QuotaSnapshotCodec.decode(invalidLegacy)
                }
            }
        }
    }

    @Test
    fun versionFourRoundTripEncodesNullableRenewalAsEpochMilliseconds() {
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

        assertEquals(4, JSONObject(encoded).getInt("schemaVersion"))
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
    fun versionFourAllowsNullRenewalAndRejectsInvalidRenewalTimestamps() {
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

    @Test
    fun versionFourRoundTripPreservesQuotaWithUnavailableRollingResets() {
        val encodedV4 = """{"schemaVersion":4,"fetchedAt":1800000000000,"planId":"individual-goat","monthlyRenewsAt":1801000000000,"monthly":{"remaining":42.5,"cap":70.0},"windowLimits":{"limited":true,"fiveHour":{"used":4.5,"cap":14.0,"resetAt":null},"weekly":{"used":22.75,"cap":35.0,"resetAt":null}},"purchasedCredits":9.5,"freeCredits":0.0}"""

        val decoded = QuotaSnapshotCodec.decode(encodedV4)
        val reencoded = QuotaSnapshotCodec.encode(decoded)
        val windowLimits = JSONObject(reencoded).getJSONObject("windowLimits")

        assertEquals(4, JSONObject(reencoded).getInt("schemaVersion"))
        assertEquals(RemainingQuota(42.5, 70.0), decoded.monthly)
        assertEquals(4.5, decoded.fiveHour?.used ?: -1.0, 0.0)
        assertEquals(14.0, decoded.fiveHour?.cap ?: -1.0, 0.0)
        assertNull(decoded.fiveHour?.resetAt)
        assertEquals(22.75, decoded.weekly?.used ?: -1.0, 0.0)
        assertEquals(35.0, decoded.weekly?.cap ?: -1.0, 0.0)
        assertNull(decoded.weekly?.resetAt)
        assertEquals(Instant.ofEpochMilli(1_801_000_000_000), decoded.monthlyRenewsAt)
        assertTrue(windowLimits.getJSONObject("fiveHour").has("resetAt"))
        assertEquals(JSONObject.NULL, windowLimits.getJSONObject("fiveHour").get("resetAt"))
        assertTrue(windowLimits.getJSONObject("weekly").has("resetAt"))
        assertEquals(JSONObject.NULL, windowLimits.getJSONObject("weekly").get("resetAt"))
        assertEquals(decoded, QuotaSnapshotCodec.decode(reencoded))
    }

    @Test
    fun versionFourRejectsMalformedResetTimestampTypesFractionsAndOverflow() {
        val validV4 = """{"schemaVersion":4,"fetchedAt":1800000000000,"planId":"individual-goat","monthlyRenewsAt":null,"monthly":{"remaining":42.5,"cap":70.0},"windowLimits":{"limited":true,"fiveHour":{"used":4.5,"cap":14.0,"resetAt":null},"weekly":{"used":22.75,"cap":35.0,"resetAt":null}},"purchasedCredits":9.5,"freeCredits":0.0}"""
        val invalidValues = listOf("\"1800000001234\"", "1.5", "9223372036854775808")

        invalidValues.forEach { value ->
            val invalid = validV4.replace("\"resetAt\":null", "\"resetAt\":$value")
            assertThrows(IllegalArgumentException::class.java) { QuotaSnapshotCodec.decode(invalid) }
        }
    }
}
