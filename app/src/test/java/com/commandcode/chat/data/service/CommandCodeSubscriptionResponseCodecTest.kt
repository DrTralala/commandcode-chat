package com.commandcode.chat.data.service

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandCodeSubscriptionResponseCodecTest {
    @Test
    fun activeFuturePeriodIsARenewal() {
        val result = CommandCodeSubscriptionResponseCodec.decode(
            """{"success":true,"data":{"planId":"individual-goat","status":"active","cancelAtPeriodEnd":false,"currentPeriodEnd":"2026-12-01T00:00:00Z"}}""",
            NOW,
        )

        assertEquals("individual-goat", result.planId)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), result.renewsAt)
    }

    @Test
    fun cancellationInactiveOrMissingStatusDoesNotReportRenewal() {
        val subscriptionResponses = listOf(
            """{"data":{"planId":"individual-goat","status":"cancelled","cancelAtPeriodEnd":false,"currentPeriodEnd":"2026-12-01T00:00:00Z"}}""",
            """{"data":{"planId":"individual-goat","status":"inactive","cancelAtPeriodEnd":false,"currentPeriodEnd":"2026-12-01T00:00:00Z"}}""",
            """{"data":{"planId":"individual-goat","status":"active","cancelAtPeriodEnd":true,"currentPeriodEnd":"2026-12-01T00:00:00Z"}}""",
            """{"data":{"planId":"individual-goat","cancelAtPeriodEnd":false,"currentPeriodEnd":"2026-12-01T00:00:00Z"}}""",
            """{"data":{"planId":"individual-goat","status":"active","currentPeriodEnd":"2026-12-01T00:00:00Z"}}""",
        )

        subscriptionResponses.forEach { text ->
            val result = CommandCodeSubscriptionResponseCodec.decode(text, NOW)
            assertEquals("individual-goat", result.planId)
            assertNull(result.renewsAt)
        }
    }

    @Test
    fun malformedRenewalDateDoesNotDiscardValidPlan() {
        val result = CommandCodeSubscriptionResponseCodec.decode(
            """{"data":{"planId":"individual-goat","status":"active","cancelAtPeriodEnd":false,"currentPeriodEnd":"not-a-date"}}""",
            NOW,
        )

        assertEquals("individual-goat", result.planId)
        assertNull(result.renewsAt)
    }

    @Test
    fun unrepresentableRenewalDoesNotDiscardValidPlan() {
        val result = CommandCodeSubscriptionResponseCodec.decode(
            """{"data":{"planId":"individual-goat","status":"active","cancelAtPeriodEnd":false,"currentPeriodEnd":"+1000000000-12-31T23:59:59.999999999Z"}}""",
            NOW,
        )

        assertEquals("individual-goat", result.planId)
        assertNull(result.renewsAt)
    }

    @Test
    fun equalOrPastPeriodEndIsNotARenewal() {
        val periodEnds = listOf("2026-11-04T00:00:00Z", "2026-11-03T23:59:59Z")

        periodEnds.forEach { periodEnd ->
            val result = CommandCodeSubscriptionResponseCodec.decode(
                """{"data":{"planId":"individual-goat","status":"active","cancelAtPeriodEnd":false,"currentPeriodEnd":"$periodEnd"}}""",
                NOW,
            )
            assertNull(result.renewsAt)
        }
    }

    @Test
    fun nullDataHasNoSubscriptionMetadata() {
        assertEquals(
            SubscriptionMetadata(planId = null, renewsAt = null),
            CommandCodeSubscriptionResponseCodec.decode("""{"success":true,"data":null}""", NOW),
        )
    }

    @Test
    fun retainsStrictStructuralAndDuplicateKeyValidation() {
        listOf(
            """{"data":"not-an-object"}""",
            """{"data":{"planId":42}}""",
            """{"success":"true","data":null}""",
            """{"success":true,"success":true,"data":null}""",
            """{"data":{"planId":"individual-goat","planId":"other"}}""",
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                CommandCodeSubscriptionResponseCodec.decode(text, NOW)
            }
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-04T00:00:00Z")
    }
}
