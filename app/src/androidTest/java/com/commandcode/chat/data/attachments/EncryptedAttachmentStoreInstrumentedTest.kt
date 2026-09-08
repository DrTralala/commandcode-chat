package com.commandcode.chat.data.attachments

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedAttachmentStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(context.noBackupFilesDir, "attachments-test-$suffix")
    private val alias = "commandcode-test-attachment-$suffix"

    @After
    fun cleanUp() {
        root.deleteRecursively()
        OwnedImageSources.clear()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    @Test
    fun actualKeystoreRoundTripRandomisesAndAuthenticatesOpaqueFiles() {
        val keys = AndroidAttachmentKeyProvider(alias)
        val store = EncryptedAttachmentStore(root, keys)
        store.initialise(emptySet())
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val firstPlaintext = "instrumented private image".toByteArray()

        store.write(first, firstPlaintext)
        store.write(second, firstPlaintext)
        val firstEncoded = File(root, first).readBytes()
        val secondEncoded = File(root, second).readBytes()

        assertFalse(firstEncoded.contentEquals(secondEncoded))
        assertFalse(firstEncoded.toString(Charsets.ISO_8859_1).contains("instrumented private image"))
        assertEquals(setOf(first, second), root.listFiles()!!.map { it.name }.toSet())
        assertArrayEquals(firstPlaintext, store.withPlaintext(first) { it.copyOf() })

        File(root, first).apply {
            val tampered = readBytes()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            writeBytes(tampered)
        }
        val error = assertThrows(AttachmentException::class.java) {
            store.withPlaintext(first) { error("authentication must fail before callback") }
        }
        assertEquals(AttachmentFailure.STORAGE, error.kind)
    }

    @Test
    fun generatedPngJpegAndStaticWebpImportIntoEncryptedFilesAfterSourceRevocation() {
        val store = EncryptedAttachmentStore(root, AndroidAttachmentKeyProvider(alias))
        store.initialise(emptySet())
        val importer = ImageImporter(context.contentResolver, ImageInspector(), store)
        val modelIds = listOf(
            "gpt-5.6-sol",
            "gpt-5.6-luna",
            "google/gemini-3.7-flash",
        )
        val sources = AndroidImageTestFixtures.all()
        val images = sources.mapIndexed { index, format ->
            val source = OwnedImageSources.publish(format)
            importer.importImage(source.uri, modelIds[index]).also { image ->
                assertEquals(format.mimeType, image.mimeType)
                assertEquals(format.bytes.size.toLong(), image.originalBytes)
                assertEquals(format.bytes.size.toLong() + AttachmentCipher.OVERHEAD_BYTES, image.encryptedBytes)
                assertEquals(image.encryptedBytes, File(root, image.id).length())
                assertArrayEquals(format.bytes, store.withPlaintext(image.id) { it.copyOf() })
                OwnedImageSources.revoke(source)
                assertFalse(OwnedImageSources.isAvailable(source.uri))
            }
        }

        // Reconciliation verifies that all three encrypted files remain readable without
        // consulting the now-revoked source provider.
        store.initialise(images.map { it.id }.toSet())
        images.forEachIndexed { index, image ->
            assertArrayEquals(sources[index].bytes, store.withPlaintext(image.id) { it.copyOf() })
        }
    }

    @Test
    fun missingActualKeyCannotBeRecreatedWhenOwnedHistoryExists() {
        val keys = AndroidAttachmentKeyProvider(alias)
        val store = EncryptedAttachmentStore(root, keys)
        store.initialise(emptySet())
        val id = UUID.randomUUID().toString()
        store.write(id, byteArrayOf(8, 9, 10))

        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }

        val error = assertThrows(AttachmentException::class.java) {
            EncryptedAttachmentStore(root, AndroidAttachmentKeyProvider(alias)).initialise(emptySet())
        }

        assertEquals(AttachmentFailure.KEY_UNAVAILABLE, error.kind)
        assertTrue(File(root, id).exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }
}
