package com.commandcode.chat.data.attachments

import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import java.io.File
import java.nio.file.Files
import java.security.Key
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedAttachmentStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesEncryptedBytesAndRoundTripsOnlyInsideCallback() {
        val root = temporaryFolder.newFolder("attachments")
        val store = store(root)
        store.initialise(emptySet())
        val id = UUID.randomUUID().toString()
        val plaintext = "private image".toByteArray()

        assertEquals(plaintext.size.toLong() + 33L, store.write(id, plaintext))
        val encoded = File(root, id).readBytes()
        assertFalse(encoded.asList().windowed(plaintext.size).any { it.toByteArray().contentEquals(plaintext) })

        var callbackCalls = 0
        val roundTrip = store.withPlaintext(id) {
            callbackCalls++
            it.copyOf()
        }

        assertEquals(1, callbackCalls)
        assertArrayEquals(plaintext, roundTrip)
    }

    @Test
    fun initialisationCreatesKeyOnlyForAnEmptyOwnedHistoryAndCleansOrphans() {
        val root = temporaryFolder.newFolder("attachments")
        val keys = TestKeyProvider()
        val first = EncryptedAttachmentStore(root, keys)
        first.initialise(emptySet())
        val keep = UUID.randomUUID().toString()
        val orphan = UUID.randomUUID().toString()
        first.write(keep, byteArrayOf(1, 2, 3))
        File(root, "$orphan.part").writeBytes(byteArrayOf(4, 5))
        File(root, "user-file.txt").writeText("keep me")

        EncryptedAttachmentStore(root, keys).initialise(setOf(keep))

        assertTrue(File(root, keep).isFile)
        assertFalse(File(root, orphan).exists())
        assertFalse(File(root, "$orphan.part").exists())
        assertTrue(File(root, "user-file.txt").isFile)
        assertEquals(listOf(true, false), keys.allowCreateCalls)
    }

    @Test
    fun missingKeyWithOwnedFilesFailsBeforeOrphanCleanupAndNeverRegenerates() {
        val root = temporaryFolder.newFolder("attachments")
        val orphan = UUID.randomUUID().toString()
        File(root, orphan).writeBytes(byteArrayOf(1, 2, 3))
        File(root, "${UUID.randomUUID()}.part").writeBytes(byteArrayOf(4))
        val keys = TestKeyProvider(missing = true)

        val error = runCatching {
            EncryptedAttachmentStore(root, keys).initialise(emptySet())
        }.exceptionOrNull() as AttachmentException

        assertEquals(AttachmentFailure.KEY_UNAVAILABLE, error.kind)
        assertTrue(File(root, orphan).exists())
        assertEquals(listOf(false), keys.allowCreateCalls)
    }

    @Test
    fun missingKeyWithReferencedHistoryAlsoRefusesCreation() {
        val root = temporaryFolder.newFolder("attachments")
        val keys = TestKeyProvider(missing = true)
        val id = UUID.randomUUID().toString()

        val error = org.junit.Assert.assertThrows(AttachmentException::class.java) {
            EncryptedAttachmentStore(root, keys).initialise(setOf(id))
        }

        assertEquals(AttachmentFailure.KEY_UNAVAILABLE, error.kind)
        assertEquals(listOf(false), keys.allowCreateCalls)
        assertFalse(File(root, id).exists())
    }

    @Test
    fun referencedHistoryRequiresAnExistingFinalFile() {
        val root = temporaryFolder.newFolder("attachments")
        val keys = TestKeyProvider()
        val id = UUID.randomUUID().toString()

        val error = runCatching {
            EncryptedAttachmentStore(root, keys).initialise(setOf(id))
        }.exceptionOrNull() as AttachmentException

        assertEquals(AttachmentFailure.INCOMPATIBLE_HISTORY, error.kind)
        assertEquals(listOf(false), keys.allowCreateCalls)
    }

    @Test
    fun rejectsNonUuidIdsPathFragmentsAndExistingCollisionsWithoutOverwriting() {
        val root = temporaryFolder.newFolder("attachments")
        val store = store(root)
        store.initialise(emptySet())
        val id = UUID.randomUUID().toString()
        val existing = File(root, id).apply { writeBytes(byteArrayOf(9, 8, 7)) }

        assertFailure(AttachmentFailure.STORAGE) { store.write("not-an-id", byteArrayOf(1)) }
        assertFailure(AttachmentFailure.STORAGE) { store.write("../outside", byteArrayOf(1)) }
        assertFailure(AttachmentFailure.STORAGE) { store.write(id, byteArrayOf(1)) }
        assertArrayEquals(byteArrayOf(9, 8, 7), existing.readBytes())
    }

    @Test
    fun existingPartialCollisionIsNotOverwritten() {
        val root = temporaryFolder.newFolder("attachments")
        val store = store(root)
        store.initialise(emptySet())
        val id = UUID.randomUUID().toString()
        val partial = File(root, "$id.part").apply { writeBytes(byteArrayOf(6, 6)) }

        assertFailure(AttachmentFailure.STORAGE) { store.write(id, byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(6, 6), partial.readBytes())
    }

    @Test
    fun partialWriteFailureCleansOwnedPartAndLeavesExistingFilesUntouched() {
        val root = temporaryFolder.newFolder("attachments")
        val keys = TestKeyProvider()
        val initial = EncryptedAttachmentStore(root, keys)
        initial.initialise(emptySet())
        val existingId = UUID.randomUUID().toString()
        val existingBytes = byteArrayOf(7, 8, 9)
        initial.write(existingId, existingBytes)

        val failedId = UUID.randomUUID().toString()
        val failing = EncryptedAttachmentStore(root, keys, FailAfterPartialWriteIo())
        failing.initialise(setOf(existingId))

        val error = org.junit.Assert.assertThrows(AttachmentException::class.java) {
            failing.write(failedId, byteArrayOf(1, 2, 3, 4, 5))
        }

        assertEquals(AttachmentFailure.STORAGE, error.kind)
        assertFalse(File(root, failedId).exists())
        assertFalse(File(root, "$failedId.part").exists())
        assertArrayEquals(existingBytes, failing.withPlaintext(existingId) { it.copyOf() })
    }

    @Test
    fun symlinkRootAndAttachmentAreNeverFollowedOrRemoved() {
        val realRoot = temporaryFolder.newFolder("real-root")
        val linkedRoot = File(temporaryFolder.root, "linked-root")
        if (!tryCreateSymlink(linkedRoot, realRoot.toPath())) return

        assertFailure(AttachmentFailure.STORAGE) {
            EncryptedAttachmentStore(linkedRoot, TestKeyProvider()).initialise(emptySet())
        }
        assertTrue(realRoot.isDirectory)

        val root = temporaryFolder.newFolder("attachments")
        val id = UUID.randomUUID().toString()
        val outside = temporaryFolder.newFile("outside.bin").apply { writeBytes(byteArrayOf(7, 7, 7)) }
        val link = File(root, id)
        if (!tryCreateSymlink(link, outside.toPath())) return
        val store = store(root)
        store.initialise(emptySet())

        assertFailure(AttachmentFailure.STORAGE) { store.withPlaintext(id) { it.size } }
        assertFailure(AttachmentFailure.STORAGE) { store.remove(id) }
        assertTrue(link.isSymbolicLink())
        assertArrayEquals(byteArrayOf(7, 7, 7), outside.readBytes())
    }

    @Test
    fun tamperingOrSubstitutingCiphertextDoesNotInvokeReaderCallback() {
        val root = temporaryFolder.newFolder("attachments")
        val store = store(root)
        store.initialise(emptySet())
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        store.write(first, byteArrayOf(1, 2, 3))
        store.write(second, byteArrayOf(4, 5, 6))
        var calls = 0

        File(root, first).appendBytes(byteArrayOf(1))
        assertFailure(AttachmentFailure.STORAGE) {
            store.withPlaintext(first) {
                calls++
                Unit
            }
        }
        File(root, second).writeBytes(File(root, first).readBytes())
        assertFailure(AttachmentFailure.STORAGE) {
            store.withPlaintext(second) {
                calls++
                Unit
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun callbackFailureClearsTheOnlyPlaintextBufferBeforePropagating() {
        val root = temporaryFolder.newFolder("attachments")
        val store = store(root)
        store.initialise(emptySet())
        val id = UUID.randomUUID().toString()
        store.write(id, byteArrayOf(1, 2, 3, 4))
        var callbackBytes: ByteArray? = null

        assertTrue(
            runCatching {
                store.withPlaintext(id) {
                    callbackBytes = it
                    throw IllegalStateException("callback failure")
                }
            }.exceptionOrNull() is IllegalStateException,
        )
        assertArrayEquals(ByteArray(4), callbackBytes)
    }

    @Test
    fun readWriteAndRemoveAreSerialisedAroundTheReaderCallback() {
        val root = temporaryFolder.newFolder("attachments")
        val store = store(root)
        store.initialise(emptySet())
        val id = UUID.randomUUID().toString()
        store.write(id, byteArrayOf(1, 2, 3))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = Thread {
            store.withPlaintext(id) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        reader.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val removerDone = CountDownLatch(1)
        val remover = Thread {
            store.remove(id)
            removerDone.countDown()
        }
        remover.start()
        assertFalse(removerDone.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        reader.join(2_000)
        remover.join(2_000)
        assertTrue(removerDone.await(2, TimeUnit.SECONDS))
        assertFalse(File(root, id).exists())
    }

    @Test
    fun exactImageCeilingIsStoredButOneByteOverIsRejected() {
        val root = temporaryFolder.newFolder("attachments")
        val store = store(root)
        store.initialise(emptySet())
        val exact = ByteArray(10_000_000) { 3 }
        val id = UUID.randomUUID().toString()

        assertEquals(10_000_033L, store.write(id, exact))
        assertFailure(AttachmentFailure.TOO_LARGE) {
            store.write(UUID.randomUUID().toString(), ByteArray(10_000_001))
        }
    }

    private fun store(root: File, keys: TestKeyProvider = TestKeyProvider()): EncryptedAttachmentStore =
        EncryptedAttachmentStore(root, keys)

    private fun assertFailure(expected: AttachmentFailure, block: () -> Unit) {
        val error = org.junit.Assert.assertThrows(AttachmentException::class.java, block)
        assertEquals(expected, error.kind)
    }

    private fun tryCreateSymlink(link: File, target: java.nio.file.Path): Boolean =
        try {
            Files.createSymbolicLink(link.toPath(), target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: java.nio.file.FileSystemException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private class TestKeyProvider(private val missing: Boolean = false) : AttachmentKeyProvider {
        private val key: Key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val allowCreateCalls = mutableListOf<Boolean>()

        override fun initialise(allowCreate: Boolean) {
            allowCreateCalls += allowCreate
            if (missing) throw AttachmentException(AttachmentFailure.KEY_UNAVAILABLE)
        }

        override fun existingKey(): Key {
            if (missing) throw AttachmentException(AttachmentFailure.KEY_UNAVAILABLE)
            return key
        }
    }

    private class FailAfterPartialWriteIo : AttachmentFileIo {
        override fun openExclusive(path: java.nio.file.Path): AttachmentWriteHandle {
            val output = Files.newOutputStream(
                path,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE,
            )
            return object : AttachmentWriteHandle {
                override fun write(buffer: java.nio.ByteBuffer): Int {
                    val count = minOf(2, buffer.remaining())
                    val partial = ByteArray(count)
                    buffer.get(partial)
                    output.write(partial)
                    throw java.io.IOException("injected mid-write failure")
                }

                override fun force() = Unit

                override fun close() = output.close()
            }
        }

        override fun moveAtomically(source: java.nio.file.Path, target: java.nio.file.Path): Unit =
            error("move must not be reached after the partial write")
    }

    private fun File.isSymbolicLink(): Boolean = Files.isSymbolicLink(toPath())
}
