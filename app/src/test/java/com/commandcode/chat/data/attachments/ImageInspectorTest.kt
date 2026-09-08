package com.commandcode.chat.data.attachments

import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageInspectorTest {

    private val inspector = ImageInspector()

    private fun failure(kind: AttachmentFailure, block: () -> Unit): AttachmentFailure {
        val error = assertThrows(AttachmentException::class.java, block)
        return error.kind
    }

    private fun assertKind(kind: AttachmentFailure, block: () -> Unit) {
        assertEquals(kind, failure(kind, block))
    }

    @Test
    fun inspectsRobolectricJpegAsImageJpegWithDimensions() {
        val info = inspector.inspect(ImageTestFactory.jpeg(), null, null)
        assertEquals("image/jpeg", info.mimeType)
        assertEquals(2, info.width)
        assertEquals(2, info.height)
    }

    @Test
    fun inspectsRobolectricPngAsImagePngWithDimensions() {
        val info = inspector.inspect(ImageTestFactory.png(), null, null)
        assertEquals("image/png", info.mimeType)
        assertEquals(2, info.width)
        assertEquals(2, info.height)
    }

    @Test
    fun inspectsRobolectricWebpAsImageWebpWithDimensions() {
        val info = inspector.inspect(ImageTestFactory.webpLossless(), null, null)
        assertEquals("image/webp", info.mimeType)
        assertEquals(2, info.width)
        assertEquals(2, info.height)
    }

    @Test
    fun rejectsGifContainerAsCorruptImage() {
        assertKind(AttachmentFailure.CORRUPT_IMAGE) {
            inspector.inspect(ImageTestFactory.gif(), "image/gif", "sticker.gif")
        }
    }

    @Test
    fun rejectsHeifContainerAsCorruptImage() {
        assertKind(AttachmentFailure.CORRUPT_IMAGE) {
            inspector.inspect(ImageTestFactory.heifHeader(), "image/heic", "photo.heic")
        }
    }

    @Test
    fun rejectsZeroLengthInput() {
        assertKind(AttachmentFailure.CORRUPT_IMAGE) { inspector.inspect(ByteArray(0), null, null) }
    }

    @Test
    fun rejectsTruncatedPng() {
        val png = ImageTestFactory.png()
        val truncated = png.copyOfRange(0, png.size - 10)
        assertKind(AttachmentFailure.CORRUPT_IMAGE) { inspector.inspect(truncated, null, null) }
    }

    @Test
    fun rejectsTrailingStructuralJunkAfterValidPng() {
        val png = ImageTestFactory.png()
        val junk = png + ImageTestFactory.be32(0x7FFFFFFF) + "JUNK".toByteArray(Charsets.US_ASCII)
        assertKind(AttachmentFailure.CORRUPT_IMAGE) { inspector.inspect(junk, null, null) }
    }

    @Test
    fun rejectsCorruptJpegBody() {
        val jpeg = ImageTestFactory.jpeg()
        // Entropy-coded JPEG often decodes regardless of interior corruption; a truncated
        // frame deterministically fails decoder validation.
        val truncated = jpeg.copyOfRange(0, jpeg.size - 12)
        assertKind(AttachmentFailure.CORRUPT_IMAGE) { inspector.inspect(truncated, "image/jpeg", "shot.jpg") }
    }

    @Test
    fun rejectsAnimatedPngViaActlChunk() {
        val bytes = ImageTestFactory.pngWithChunks(2, 2, "acTL", ImageTestFactory.be32(2) + ImageTestFactory.be32(0))
        assertKind(AttachmentFailure.ANIMATED_IMAGE) { inspector.inspect(bytes, "image/png", "animated.png") }
    }

    @Test
    fun rejectsAnimatedPngViaFctlChunk() {
        val payload = ImageTestFactory.be32(0) + ImageTestFactory.be32(0) + ImageTestFactory.be32(2) +
            ImageTestFactory.be32(2) + ImageTestFactory.be32(0) + ImageTestFactory.be32(0) +
            byteArrayOf(0x00, 0x00) + byteArrayOf(0x00)
        val bytes = ImageTestFactory.pngWithChunks(2, 2, "fcTL", payload)
        assertKind(AttachmentFailure.ANIMATED_IMAGE) { inspector.inspect(bytes, "image/png", "animated.png") }
    }

    @Test
    fun acceptsValidPngWithAncillaryTextChunk() {
        val payload = "Comment".toByteArray(Charsets.US_ASCII) + 0x00.toByte() +
            "not a search string".toByteArray(Charsets.US_ASCII)
        val bytes = ImageTestFactory.pngWithChunks(2, 2, "tEXt", payload)
        val info = inspector.inspect(bytes, "image/png", "photo.png")
        assertEquals("image/png", info.mimeType)
        assertEquals(2, info.width)
        assertEquals(2, info.height)
    }

    @Test
    fun dimensionCeilingIsInclusiveAtEightThousand() {
        // A valid one-scanline PNG whose exact decoded size is the inclusive ceiling.
        val bytes = ImageTestFactory.png(8000, 1)
        val info = inspector.inspect(bytes, "image/png", "wide.png")
        assertEquals(8000, info.width)
        assertEquals(1, info.height)
    }

    @Test
    fun dimensionAboveEightThousandIsExcessive() {
        val bytes = ImageTestFactory.png(8001, 1)
        assertKind(AttachmentFailure.EXCESSIVE_DIMENSIONS) { inspector.inspect(bytes, "image/png", "wide.png") }
    }

    @Test
    fun sniffedMimeIsAuthoritativeOverContradictoryDeclaredMime() {
        assertKind(AttachmentFailure.CORRUPT_IMAGE) {
            inspector.inspect(ImageTestFactory.png(), "image/jpeg", "photo.jpg")
        }
    }

    @Test
    fun sniffedMimeMatchesDeclaredMime() {
        val info = inspector.inspect(ImageTestFactory.png(), "image/png", "photo.png")
        assertEquals("image/png", info.mimeType)
    }

    @Test
    fun contradictoryDisplayNameSuffixRejects() {
        assertKind(AttachmentFailure.CORRUPT_IMAGE) {
            inspector.inspect(ImageTestFactory.png(), "image/png", "not-a-real.jpg")
        }
    }

    @Test
    fun jpegSuffixIsAcceptedForJpegContent() {
        val info = inspector.inspect(ImageTestFactory.jpeg(), "image/jpeg", "photo.jpeg")
        assertEquals("image/jpeg", info.mimeType)
    }

    @Test
    fun rejectsWebpDeclaredSizeMismatch() {
        val base = ImageTestFactory.webpLossless()
        val bad = ByteArray(base.size + 1)
        base.copyInto(bad)
        assertKind(AttachmentFailure.CORRUPT_IMAGE) { inspector.inspect(bad, null, null) }
    }

    @Test
    fun rejectsWebpWithOddPaddingTruncatedChunk() {
        val odd = ImageTestFactory.webpWithOddPadding()
        assertKind(AttachmentFailure.CORRUPT_IMAGE) { inspector.inspect(odd, null, null) }
    }

    @Test
    fun rejectsAnimatedWebpViaVp8xAnimationFlag() {
        val bytes = ImageTestFactory.webpWithVp8x(flags = 0x02)
        assertKind(AttachmentFailure.ANIMATED_IMAGE) { inspector.inspect(bytes, null, null) }
    }

    @Test
    fun rejectsGifSignatureInsideUnrelatedBytes() {
        val fake = byteArrayOf(0x00, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61) + ByteArray(4)
        assertKind(AttachmentFailure.CORRUPT_IMAGE) { inspector.inspect(fake, null, null) }
    }

    @Test
    fun metadataBearingPngSurvivesInspection() {
        val info = inspector.inspect(ImageTestFactory.png(), null, null)
        assertTrue(info.width in 1..8000)
        assertTrue(info.height in 1..8000)
    }
}
