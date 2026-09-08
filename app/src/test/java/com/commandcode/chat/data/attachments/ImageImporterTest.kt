package com.commandcode.chat.data.attachments

import android.net.Uri
import com.commandcode.chat.data.attachments.FakeAttachmentFiles
import com.commandcode.chat.data.attachments.ImageImporter
import com.commandcode.chat.data.attachments.ImageInspector
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ImageAttachment
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageImporterTest {

    private lateinit var files: FakeAttachmentFiles

    @Before
    fun setUp() {
        files = FakeAttachmentFiles()
    }

    @After
    fun tearDown() {
        ShadowContentResolver.reset()
    }

    private fun importer(): ImageImporter =
        ImageImporter(
            resolver = RuntimeEnvironment.getApplication().contentResolver,
            inspector = ImageInspector(),
            files = files,
        )

    private fun registerProvider(
        bytes: ByteArray,
        size: Long? = null,
        mime: String? = null,
        name: String? = null,
        streamSupplier: (() -> java.io.InputStream)? = null,
    ) {
        val authority = "fixture.attachments.test"
        val context = RuntimeEnvironment.getApplication()
        val provider = AttachmentContentProvider(
            bytes = bytes,
            size = size,
            declaredMime = mime,
            displayName = name,
            streamSupplier = streamSupplier,
        )
        provider.registerWith(context, authority)
    }

    private fun uri(authority: String = "fixture.attachments.test"): Uri =
        Uri.parse("content://$authority/doc/1")

    @Test
    fun importsValidPngAndReturnsOriginalBytesAndDimensions() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = bytes.size.toLong(), mime = "image/png", name = "photo.png")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("image/png", attachment.mimeType)
        assertEquals("photo.png", attachment.displayName)
        assertEquals(bytes.size.toLong(), attachment.originalBytes)
        assertEquals(2, attachment.width)
        assertEquals(2, attachment.height)
        assertNotEquals("", attachment.id)
        assertTrue(files.stored.containsKey(attachment.id))
        files.assertStored(attachment.id, bytes)
        files.assertNoPlaintextOutstanding()
    }

    @Test
    fun providerSizeIsNotAuthoritativeWhenItIsTooSmall() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = 1L, mime = "image/png", name = "photo.png")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals(bytes.size.toLong(), attachment.originalBytes)
        files.assertStored(attachment.id, bytes)
    }

    @Test
    fun providerSizeIsNotAuthoritativeWhenItIsTooLarge() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = Long.MAX_VALUE, mime = "image/png", name = "photo.png")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals(bytes.size.toLong(), attachment.originalBytes)
        files.assertStored(attachment.id, bytes)
    }

    @Test
    fun missingProviderSizeStillImports() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = "photo.png")
        val attachment = importer().importImage(uri(), "gpt-5.6-luna")
        assertEquals(bytes.size.toLong(), attachment.originalBytes)
    }

    @Test
    fun rejectsEmptyProviderStream() {
        registerProvider(ByteArray(0), size = 0L, mime = "image/png", name = "empty.png")
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.CORRUPT_IMAGE, error.kind)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun rejectsNonContentUriScheme() {
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(Uri.parse("https://example.com/a.png"), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.STALE_SELECTION, error.kind)
    }

    @Test
    fun unverifiableModelRejectsBeforeStorage() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = "photo.png")
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "unknown-model")
        }
        assertEquals(AttachmentFailure.UNVERIFIED_MODEL, error.kind)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun unsupportedMimeForDocumentedModelRejects() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = "photo.png")
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-4o")
        }
        assertEquals(AttachmentFailure.UNVERIFIED_MODEL, error.kind)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun missingNameFallsBackByVerifiedMime() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = null)
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("Image.png", attachment.displayName)
    }

    @Test
    fun jpegNameFallsBackByVerifiedMime() {
        val bytes = ImageTestFactory.jpeg()
        registerProvider(bytes, size = null, mime = "image/jpeg", name = null)
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("Image.jpg", attachment.displayName)
    }

    @Test
    fun webpNameFallsBackByVerifiedMime() {
        val bytes = ImageTestFactory.webpLossless()
        registerProvider(bytes, size = null, mime = "image/webp", name = null)
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("Image.webp", attachment.displayName)
    }

    @Test
    fun genericProviderMimeResolvesFromActualBytes() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "application/octet-stream", name = "renamed.bin")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("image/png", attachment.mimeType)
    }

    @Test
    fun wildcardImageProviderMimeResolvesFromActualBytes() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/*", name = "photo")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("image/png", attachment.mimeType)
    }

    @Test
    fun specificContradictoryDeclaredMimeUsesVerifiedContent() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/jpeg", name = "photo.png")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("image/png", attachment.mimeType)
        assertEquals(1, files.stored.size)
    }

    @Test
    fun controlCharactersAreRemovedFromDisplayName() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = "bad\u0000\u0007name.png")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("badname.png", attachment.displayName)
    }

    @Test
    fun longDisplayNameIsCappedTo255CodePoints() {
        val bytes = ImageTestFactory.png()
        val longName = "a".repeat(300) + ".png"
        registerProvider(bytes, size = null, mime = "image/png", name = longName)
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals(255, attachment.displayName.codePointCount(0, attachment.displayName.length))
    }

    @Test
    fun blankDisplayNameFallsBackByVerifiedMime() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = "   ")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals("Image.png", attachment.displayName)
    }

    @Test
    fun importDoesNotTakePersistableUriPermission() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = "photo.png")
        importer().importImage(uri(), "gpt-5.6-sol")
        assertTrue(
            "no persisted URI permissions may be taken",
            RuntimeEnvironment.getApplication().contentResolver.persistedUriPermissions.isEmpty(),
        )
    }

    @Test
    fun streamLengthIsAuthoritative() {
        assertEquals(
            10_000_000,
            ImageImporter.readBoundedImage(ByteArray(10_000_000).inputStream()).size,
        )
        val error = assertThrows(AttachmentException::class.java) {
            ImageImporter.readBoundedImage(ByteArray(10_000_001).inputStream())
        }
        assertEquals(AttachmentFailure.TOO_LARGE, error.kind)
    }

    @Test
    fun readerRejectsWhenStreamEndsAfterExactCeiling() {
        // A stream of exactly 10,000,000 bytes is allowed; 10,000,001 is not.
        val allowed = ByteArray(10_000_000)
        assertEquals(10_000_000, ImageImporter.readBoundedImage(ByteArrayInputStream(allowed)).size)
    }

    @Test
    fun importsPngExactlyTenMillionBytesPreservingOriginalBytes() {
        val base = ImageTestFactory.png()
        val payload = ancillaryPayloadForTargetSize(base, 10_000_000)
        val exact = appendAncillary(base, "tEXt", payload)
        assertEquals(10_000_000, exact.size)
        registerProvider(exact, size = null, mime = "image/png", name = "huge.png")
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals(10_000_000L, attachment.originalBytes)
        files.assertStored(attachment.id, exact)
    }

    @Test
    fun rejectsPngExactlyTenMillionOneBytes() {
        val over = ByteArray(10_000_001)
        val error = assertThrows(AttachmentException::class.java) {
            ImageImporter.readBoundedImage(java.io.ByteArrayInputStream(over))
        }
        assertEquals(AttachmentFailure.TOO_LARGE, error.kind)
    }

    @Test
    fun rejectsPngExactlyTenMillionOneBytesViaProvider() {
        val base = ImageTestFactory.png()
        val payload = ancillaryPayloadForTargetSize(base, 10_000_001)
        val over = appendAncillary(base, "tEXt", payload)
        assertEquals(10_000_001, over.size)
        registerProvider(over, size = null, mime = "image/png", name = "huge.png")
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.TOO_LARGE, error.kind)
        assertTrue(files.stored.isEmpty())
    }

    // Builds a valid tEXt payload sized so base+tEXt overhead lands exactly on target.
    private fun ancillaryPayloadForTargetSize(base: ByteArray, target: Int): ByteArray {
        val keyword = "Comment"
        val overhead = 12 + keyword.length + 1 // chunk header + keyword + null
        val desiredPayload = target - base.size - overhead
        check(desiredPayload >= 0) { "target too small for base+overhead" }
        // Deterministic, safely repeated ASCII filler (not a control character).
        val fill = CharArray(desiredPayload) { 'a' }
        return (keyword + "\u0000" + String(fill)).toByteArray(Charsets.UTF_8)
    }

    private fun appendAncillary(base: ByteArray, type: String, payload: ByteArray): ByteArray {
        // Only supports the factory layout: 8-byte signature then IEND at the end.
        check(base.size > 8)
        val iendOffset = base.size - 12
        check(
            base[iendOffset + 4].toInt() == 'I'.code &&
                base[iendOffset + 5].toInt() == 'E'.code &&
                base[iendOffset + 6].toInt() == 'N'.code &&
                base[iendOffset + 7].toInt() == 'D'.code,
        ) { "factory PNG must end in a 12-byte IEND chunk" }
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = java.util.zip.CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        val chunk = ImageTestFactory.be32(payload.size) + typeBytes + payload + ImageTestFactory.be32(crc.value.toInt())
        return base.copyOfRange(0, iendOffset) + chunk + base.copyOfRange(iendOffset, base.size)
    }

    @Test
    fun animatedPngFailsImportWithoutStoring() {
        val bytes = ImageTestFactory.pngWithChunks(2, 2, "acTL", ImageTestFactory.be32(2) + ImageTestFactory.be32(0))
        registerProvider(bytes, size = null, mime = "image/png", name = "anim.png")
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.ANIMATED_IMAGE, error.kind)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun webpImportWorksEndToEnd() {
        val bytes = ImageTestFactory.webpLossless()
        registerProvider(bytes, size = null, mime = "image/webp", name = null)
        val attachment = importer().importImage(uri(), "google/gemini-3.7-flash")
        assertEquals("image/webp", attachment.mimeType)
        assertEquals("Image.webp", attachment.displayName)
        files.assertStored(attachment.id, bytes)
    }

    @Test
    fun jpegImportWorksEndToEnd() {
        val bytes = ImageTestFactory.jpeg()
        registerProvider(bytes, size = null, mime = "image/jpeg", name = "shot.jpg")
        val attachment = importer().importImage(uri(), "gpt-5.6-luna")
        assertEquals("image/jpeg", attachment.mimeType)
        files.assertStored(attachment.id, bytes)
    }

    @Test
    fun importClearsTemporaryReadBufferOnSuccess() {
        val bytes = ImageTestFactory.png()
        registerProvider(bytes, size = null, mime = "image/png", name = "photo.png")
        importer().importImage(uri(), "gpt-5.6-sol")
        files.assertNoPlaintextOutstanding()
    }

    // --- importer-owned resolver stream closure ---

    @Test
    fun importClosesResolverInputStreamOnSuccess() {
        val bytes = ImageTestFactory.png()
        val tracking = CloseTrackingInputStream(ByteArrayInputStream(bytes))
        registerProvider(
            bytes = bytes,
            size = null,
            mime = "image/png",
            name = "photo.png",
            streamSupplier = { tracking },
        )
        importer().importImage(uri(), "gpt-5.6-sol")
        assertTrue("resolver stream must be closed after a successful import", tracking.closed)
    }

    @Test
    fun importClosesResolverInputStreamOnReadOverflow() {
        val base = ImageTestFactory.png()
        val payload = ancillaryPayloadForTargetSize(base, 10_000_001)
        val over = appendAncillary(base, "tEXt", payload)
        assertEquals(10_000_001, over.size)
        val tracking = CloseTrackingInputStream(ByteArrayInputStream(over))
        registerProvider(
            bytes = over,
            size = null,
            mime = "image/png",
            name = "huge.png",
            streamSupplier = { tracking },
        )
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.TOO_LARGE, error.kind)
        assertTrue("resolver stream must be closed when the ceiling is exceeded", tracking.closed)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun importClosesResolverInputStreamOnReadFailure() {
        val tracking = CloseTrackingInputStream(FailingReadInputStream())
        registerProvider(
            bytes = ByteArray(0),
            size = null,
            mime = "image/png",
            name = "photo.png",
            streamSupplier = { tracking },
        )
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.CORRUPT_IMAGE, error.kind)
        assertTrue("resolver stream must be closed when reading fails", tracking.closed)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun importClosesResolverInputStreamOnInspectionFailure() {
        val bytes = ImageTestFactory.png().dropLast(10).toByteArray()
        val tracking = CloseTrackingInputStream(ByteArrayInputStream(bytes))
        registerProvider(
            bytes = bytes,
            size = null,
            mime = "image/png",
            name = "photo.png",
            streamSupplier = { tracking },
        )
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.CORRUPT_IMAGE, error.kind)
        assertTrue("resolver stream must be closed when inspection fails", tracking.closed)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun closeFailureDoesNotMaskTypedInspectionFailure() {
        val bytes = ImageTestFactory.png().dropLast(10).toByteArray()
        val closeThrowing = CloseThrowingInputStream(ByteArrayInputStream(bytes))
        registerProvider(
            bytes = bytes,
            size = null,
            mime = "image/png",
            name = "photo.png",
            streamSupplier = { closeThrowing },
        )
        val error = assertThrows(AttachmentException::class.java) {
            importer().importImage(uri(), "gpt-5.6-sol")
        }
        assertEquals(AttachmentFailure.CORRUPT_IMAGE, error.kind)
        assertEquals("close must still be attempted once", 1, closeThrowing.closeAttempts)
        assertTrue(files.stored.isEmpty())
    }

    @Test
    fun closeFailureOnSuccessfulImportStillReturnsAttachmentAndStoresOriginalBytes() {
        val bytes = ImageTestFactory.png()
        val closeThrowing = CloseThrowingInputStream(ByteArrayInputStream(bytes))
        registerProvider(
            bytes = bytes,
            size = null,
            mime = "image/png",
            name = "photo.png",
            streamSupplier = { closeThrowing },
        )
        val attachment = importer().importImage(uri(), "gpt-5.6-sol")
        assertEquals(bytes.size.toLong(), attachment.originalBytes)
        files.assertStored(attachment.id, bytes)
        assertEquals("close must still be attempted once", 1, closeThrowing.closeAttempts)
    }
}
