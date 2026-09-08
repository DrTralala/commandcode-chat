package com.commandcode.chat.data.attachments

import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal interface AttachmentFileIo {
    fun openExclusive(path: Path): AttachmentWriteHandle
    fun moveAtomically(source: Path, target: Path)
}

internal interface AttachmentWriteHandle : Closeable {
    fun write(buffer: ByteBuffer): Int
    fun force()
}

private object DefaultAttachmentFileIo : AttachmentFileIo {
    override fun openExclusive(path: Path): AttachmentWriteHandle =
        FileChannelAttachmentWriteHandle(
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )

    override fun moveAtomically(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }
}

private class FileChannelAttachmentWriteHandle(
    private val channel: FileChannel,
) : AttachmentWriteHandle {
    override fun write(buffer: ByteBuffer): Int = channel.write(buffer)

    override fun force() {
        channel.force(true)
    }

    override fun close() {
        channel.close()
    }
}

/**
 * Blocking encrypted per-attachment file store. Callers must invoke it off the
 * main thread. Plaintext is available only during [withPlaintext]'s callback.
 */
interface AttachmentFiles {
    fun initialise(referencedIds: Set<String>)
    fun write(id: String, plaintext: ByteArray): Long
    fun <T> withPlaintext(id: String, block: (ByteArray) -> T): T
    fun remove(id: String)
}

class EncryptedAttachmentStore private constructor(
    private val root: File,
    private val keyProvider: AttachmentKeyProvider,
    private val fileIo: AttachmentFileIo,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : AttachmentFiles {
    constructor(root: File, keyProvider: AttachmentKeyProvider) :
        this(root, keyProvider, DefaultAttachmentFileIo, Unit)

    internal constructor(root: File, keyProvider: AttachmentKeyProvider, fileIo: AttachmentFileIo) :
        this(root, keyProvider, fileIo, Unit)

    private val cipher = AttachmentCipher(keyProvider)
    private val operationLock = Any()

    override fun initialise(referencedIds: Set<String>) = synchronized(operationLock) {
        if (referencedIds.any { !isUuid(it) }) throw failure(AttachmentFailure.INCOMPATIBLE_HISTORY)
        val rootPath = safeRoot()
        val entries = ownedEntries(rootPath)
        val hasOwnedHistory = entries.isNotEmpty()
        initialiseKeys(allowCreate = referencedIds.isEmpty() && !hasOwnedHistory)
        verifyReferencedFiles(rootPath, referencedIds)
        cleanOrphans(entries, referencedIds)
    }

    override fun write(id: String, plaintext: ByteArray): Long = synchronized(operationLock) {
        requireUuid(id)
        if (plaintext.size.toLong() > AttachmentCipher.MAX_IMAGE_BYTES) {
            throw failure(AttachmentFailure.TOO_LARGE)
        }
        val rootPath = safeRoot()
        val target = child(rootPath, id)
        val part = child(rootPath, "$id.part")
        if (entryExists(target) || entryExists(part)) throw failure(AttachmentFailure.STORAGE)

        var encoded: ByteArray? = null
        var partCreated = false
        var moved = false
        try {
            val encrypted = cipher.encrypt(id, plaintext)
            encoded = encrypted
            if (encrypted.size > AttachmentCipher.MAX_ENCODED_BYTES) {
                throw failure(AttachmentFailure.TOO_LARGE)
            }
            writeExclusive(part, encrypted)
            partCreated = true
            if (regularSize(part) != encrypted.size.toLong()) throw failure(AttachmentFailure.STORAGE)
            try {
                fileIo.moveAtomically(part, target)
            } catch (error: AtomicMoveNotSupportedException) {
                throw failure(AttachmentFailure.STORAGE, error)
            }
            moved = true
            val actualSize = regularSize(target)
            if (actualSize != encrypted.size.toLong()) throw failure(AttachmentFailure.STORAGE)
            return actualSize
        } catch (error: AttachmentException) {
            cleanupOwnedCiphertext(part, target, partCreated, moved)
            throw error
        } catch (error: Exception) {
            cleanupOwnedCiphertext(part, target, partCreated, moved)
            throw failure(AttachmentFailure.STORAGE, error)
        } finally {
            encoded?.fill(0)
        }
    }

    override fun <T> withPlaintext(id: String, block: (ByteArray) -> T): T = synchronized(operationLock) {
        requireUuid(id)
        val rootPath = safeRoot()
        val target = child(rootPath, id)
        var encoded: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            val stored = readEncoded(target)
            encoded = stored
            val decrypted = cipher.decrypt(id, stored)
            plaintext = decrypted
            return block(decrypted)
        } finally {
            plaintext?.fill(0)
            encoded?.fill(0)
        }
    }

    override fun remove(id: String) = synchronized(operationLock) {
        requireUuid(id)
        val target = child(safeRoot(), id)
        if (!entryExists(target)) return@synchronized
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(AttachmentFailure.STORAGE)
        }
        try {
            Files.delete(target)
            if (entryExists(target)) throw failure(AttachmentFailure.STORAGE)
        } catch (error: AttachmentException) {
            throw error
        } catch (error: Exception) {
            throw failure(AttachmentFailure.STORAGE, error)
        }
    }

    private fun initialiseKeys(allowCreate: Boolean) {
        try {
            keyProvider.initialise(allowCreate)
        } catch (error: AttachmentException) {
            throw error
        } catch (error: Exception) {
            throw failure(AttachmentFailure.KEY_UNAVAILABLE, error)
        }
    }

    private fun safeRoot(): Path {
        val path = root.toPath().toAbsolutePath().normalize()
        rejectSymlinkComponents(path)
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path)
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(AttachmentFailure.STORAGE)
            }
            rejectSymlinkComponents(path)
            return path
        } catch (error: AttachmentException) {
            throw error
        } catch (error: Exception) {
            throw failure(AttachmentFailure.STORAGE, error)
        }
    }

    private fun rejectSymlinkComponents(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw failure(AttachmentFailure.STORAGE)
            }
            current = current.parent
        }
    }

    private fun ownedEntries(rootPath: Path): List<OwnedEntry> {
        val entries = mutableListOf<OwnedEntry>()
        try {
            directoryStream(rootPath).use { stream ->
                for (path in stream) {
                    val name = path.fileName?.toString() ?: continue
                    when {
                        isUuid(name) -> entries += OwnedEntry(path, name, false)
                        name.endsWith(".part") && isUuid(name.removeSuffix(".part")) ->
                            entries += OwnedEntry(path, name.removeSuffix(".part"), true)
                    }
                }
            }
            return entries
        } catch (error: AttachmentException) {
            throw error
        } catch (error: Exception) {
            throw failure(AttachmentFailure.CLEANUP, error)
        }
    }

    private fun directoryStream(path: Path): DirectoryStream<Path> = Files.newDirectoryStream(path)

    private fun verifyReferencedFiles(rootPath: Path, referencedIds: Set<String>) {
        for (id in referencedIds) {
            val path = child(rootPath, id)
            if (!entryExists(path)) throw failure(AttachmentFailure.INCOMPATIBLE_HISTORY)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(AttachmentFailure.STORAGE)
            }
        }
    }

    private fun cleanOrphans(entries: List<OwnedEntry>, referencedIds: Set<String>) {
        for (entry in entries) {
            if (!entry.part && entry.id in referencedIds) continue
            if (Files.isSymbolicLink(entry.path)) continue
            if (!Files.isRegularFile(entry.path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(AttachmentFailure.CLEANUP)
            }
            try {
                Files.delete(entry.path)
                if (entryExists(entry.path)) throw failure(AttachmentFailure.CLEANUP)
            } catch (error: AttachmentException) {
                throw error
            } catch (error: Exception) {
                throw failure(AttachmentFailure.CLEANUP, error)
            }
        }
    }

    private fun writeExclusive(path: Path, bytes: ByteArray) {
        var created = false
        var completed = false
        try {
            fileIo.openExclusive(path).use { channel ->
                created = true
                val buffer = ByteBuffer.wrap(bytes)
                var emptyWrites = 0
                while (buffer.hasRemaining()) {
                    val written = channel.write(buffer)
                    if (written < 0) throw IOException("attachment write ended unexpectedly")
                    if (written == 0) {
                        emptyWrites++
                        if (emptyWrites > 3) throw IOException("attachment write made no progress")
                    } else {
                        emptyWrites = 0
                    }
                }
                channel.force()
            }
            completed = true
        } catch (error: AttachmentException) {
            if (created && !completed) cleanupOwnedCiphertext(path, path, true, false)
            throw error
        } catch (error: Exception) {
            if (created && !completed) cleanupOwnedCiphertext(path, path, true, false)
            throw failure(AttachmentFailure.STORAGE, error)
        }
    }

    private fun readEncoded(path: Path): ByteArray {
        if (!entryExists(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(AttachmentFailure.STORAGE)
        }
        val scratch = ByteArray(READ_BUFFER_BYTES)
        val bounded = ByteArray(AttachmentCipher.MAX_ENCODED_BYTES + 1)
        var count = 0
        var emptyReads = 0
        try {
            FileInputStream(path.toFile()).use { input ->
                while (true) {
                    val read = try {
                        input.read(scratch)
                    } catch (error: IOException) {
                        throw failure(AttachmentFailure.STORAGE, error)
                    }
                    if (read < 0) break
                    if (read == 0) {
                        emptyReads++
                        if (emptyReads > 3) throw failure(AttachmentFailure.STORAGE)
                        continue
                    }
                    emptyReads = 0
                    if (count > AttachmentCipher.MAX_ENCODED_BYTES - read) {
                        throw failure(AttachmentFailure.STORAGE)
                    }
                    scratch.copyInto(bounded, destinationOffset = count, endIndex = read)
                    count += read
                    if (count > AttachmentCipher.MAX_ENCODED_BYTES) throw failure(AttachmentFailure.STORAGE)
                }
            }
            return bounded.copyOf(count)
        } catch (error: AttachmentException) {
            throw error
        } catch (error: Exception) {
            throw failure(AttachmentFailure.STORAGE, error)
        } finally {
            scratch.fill(0)
            bounded.fill(0)
        }
    }

    private fun regularSize(path: Path): Long {
        if (Files.isSymbolicLink(path)) throw failure(AttachmentFailure.STORAGE)
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: Exception) {
            throw failure(AttachmentFailure.STORAGE, error)
        }
        if (!attributes.isRegularFile) throw failure(AttachmentFailure.STORAGE)
        return attributes.size()
    }

    private fun cleanupOwnedCiphertext(part: Path, target: Path, partCreated: Boolean, moved: Boolean) {
        val path = if (moved) target else if (partCreated) part else null
        if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
        runCatching { Files.deleteIfExists(path) }
    }

    private fun child(rootPath: Path, name: String): Path {
        val path = rootPath.resolve(name).normalize()
        if (path.parent != rootPath || Files.isSymbolicLink(path)) throw failure(AttachmentFailure.STORAGE)
        return path
    }

    private fun entryExists(path: Path): Boolean =
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)

    private fun requireUuid(id: String) {
        if (!isUuid(id)) throw failure(AttachmentFailure.STORAGE)
    }

    private fun isUuid(id: String): Boolean {
        if (id.length != UUID_TEXT_LENGTH || id[8] != '-' || id[13] != '-' || id[18] != '-' || id[23] != '-') {
            return false
        }
        return runCatching { UUID.fromString(id) }.isSuccess
    }

    private data class OwnedEntry(val path: Path, val id: String, val part: Boolean)

    companion object {
        private const val READ_BUFFER_BYTES = 8192
        private const val UUID_TEXT_LENGTH = 36

        private fun failure(kind: AttachmentFailure, cause: Throwable? = null): AttachmentException =
            AttachmentException(kind).also {
                if (cause != null) it.initCause(cause)
            }
    }
}
