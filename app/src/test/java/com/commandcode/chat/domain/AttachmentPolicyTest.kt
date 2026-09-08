package com.commandcode.chat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPolicyTest {

    private val exactModelIds = listOf("gpt-5.6-sol", "gpt-5.6-luna", "google/gemini-3.7-flash")
    private val allowedMimeTypes = setOf("image/jpeg", "image/png", "image/webp")

    @Test fun exactModelIdsAreDocumentedWithJpegPngAndWebpOnly() {
        for (id in exactModelIds) {
            val capability = AttachmentPolicy.capability(id)
            assertEquals(AttachmentSupport.DOCUMENTED, capability.support)
            assertEquals(allowedMimeTypes, capability.mimeTypes)
        }
    }

    @Test fun unknownNearMatchAndMixedCaseModelIdsAreUnverifiedWithEmptyMimeTypes() {
        val unverifiedIds = listOf(
            "gpt-4o",
            "unknown-model",
            "gpt-5.6-sol-extra",
            "google/gemini-3.7-flash-lite",
            "GPT-5.6-SOL",
            "Google/Gemini-3.7-Flash",
            "",
            " ",
        )
        for (id in unverifiedIds) {
            val capability = AttachmentPolicy.capability(id)
            assertEquals(
                "capability for <$id> should be UNVERIFIED",
                AttachmentSupport.UNVERIFIED,
                capability.support,
            )
            assertTrue("capability for <$id> should expose no mime types", capability.mimeTypes.isEmpty())
        }
    }

    @Test fun requireAllowedAcceptsAllThreeMimeTypesForEveryExactModel() {
        for (id in exactModelIds) {
            for (mimeType in allowedMimeTypes) {
                AttachmentPolicy.requireAllowed(id, mimeType)
            }
        }
    }

    @Test fun requireAllowedRejectsGifHeicAndPdfOnDocumentedModel() {
        for (mimeType in listOf("image/gif", "image/heic", "application/pdf")) {
            val failure = assertThrows(AttachmentException::class.java) {
                AttachmentPolicy.requireAllowed("gpt-5.6-sol", mimeType)
            }
            assertEquals(AttachmentFailure.INVALID_FORMAT, failure.kind)
        }
    }

    @Test fun requireAllowedDoesNotNormaliseMimeCaseToWidenAllowlist() {
        val failure = assertThrows(AttachmentException::class.java) {
            AttachmentPolicy.requireAllowed("gpt-5.6-sol", "IMAGE/JPEG")
        }
        assertEquals(AttachmentFailure.INVALID_FORMAT, failure.kind)
    }

    @Test fun requireAllowedOnUnknownModelIsUnverifiedEvenWithSupportedMimeType() {
        val failure = assertThrows(AttachmentException::class.java) {
            AttachmentPolicy.requireAllowed("gpt-5.6-sol-extra", "image/jpeg")
        }
        assertEquals(AttachmentFailure.UNVERIFIED_MODEL, failure.kind)
    }

    @Test fun requireCountAcceptsNineteenSavedWithOnePending() {
        AttachmentPolicy.requireCount(saved = 19, pending = 1, incoming = 0)
    }

    @Test fun requireCountAcceptsTwentyIncomingWhenNothingElseStored() {
        AttachmentPolicy.requireCount(saved = 0, pending = 0, incoming = 20)
    }

    @Test fun requireCountRejectsTwentyOneTotalImages() {
        val failure = assertThrows(AttachmentException::class.java) {
            AttachmentPolicy.requireCount(saved = 19, pending = 1, incoming = 1)
        }
        assertEquals(AttachmentFailure.TOO_MANY, failure.kind)
    }

    @Test fun requireCountRejectsMaxValueCountsAsTooManyNotOverflow() {
        val failure = assertThrows(AttachmentException::class.java) {
            AttachmentPolicy.requireCount(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        }
        assertEquals(AttachmentFailure.TOO_MANY, failure.kind)
    }

    @Test fun requireCountRejectsSingleMaxValueCountAsTooMany() {
        val failure = assertThrows(AttachmentException::class.java) {
            AttachmentPolicy.requireCount(Int.MAX_VALUE, 0, 0)
        }
        assertEquals(AttachmentFailure.TOO_MANY, failure.kind)
    }

    @Test fun requireCountRejectsNegativeSavedCount() {
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPolicy.requireCount(saved = -1, pending = 0, incoming = 0)
        }
    }

    @Test fun requireCountRejectsNegativePendingCount() {
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPolicy.requireCount(saved = 0, pending = -1, incoming = 0)
        }
    }

    @Test fun requireCountRejectsNegativeIncomingCount() {
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPolicy.requireCount(saved = 0, pending = 0, incoming = -1)
        }
    }
}
