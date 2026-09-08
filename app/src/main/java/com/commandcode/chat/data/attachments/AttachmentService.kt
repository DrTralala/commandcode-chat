package com.commandcode.chat.data.attachments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.commandcode.chat.ChatStore
import com.commandcode.chat.data.commandcode.ApiImage
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.data.database.AttachmentDao
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.MessageState
import com.commandcode.chat.data.security.effectiveSystemPrompt
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class PreparedAttachmentTurn(
    val pending: PendingTurn,
    val messages: List<ApiMessage>,
)

interface AttachmentOperations {
    suspend fun initialise()
    suspend fun importImage(uri: Uri, modelId: String): ImageAttachment
    suspend fun discard(images: List<ImageAttachment>)
    suspend fun prepareTurn(
        conversationId: String?,
        text: String,
        model: ChatModel,
        images: List<ImageAttachment>,
        systemPrompt: String = "",
    ): PreparedAttachmentTurn
    suspend fun deleteConversation(id: String)
    suspend fun preview(id: String, maxEdge: Int): Bitmap
    suspend fun prepareRetry(
        conversationId: String,
        userMessageId: String,
        model: ChatModel,
        systemPrompt: String = "",
    ): PreparedAttachmentTurn =
        error("Message recovery is not supported by this attachment service")
    suspend fun deleteFailedMessage(conversationId: String, userMessageId: String): Unit =
        error("Message recovery is not supported by this attachment service")
}

/**
 * Coordinates encrypted attachment ownership with chat persistence. [prepareTurn] only
 * prepares and commits the local turn; its caller must encompass it and ActiveTurn
 * registration in one NonCancellable section before starting network work.
 */
class AttachmentService(
    private val chatStore: ChatStore,
    private val attachmentDao: AttachmentDao,
    private val imageImporter: ImageImporter,
    private val files: AttachmentFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val importImageBlocking: ((Uri, String) -> ImageAttachment)? = null,
) : AttachmentOperations {
    private val lifecycleMutex = Mutex()
    private val drafts = linkedMapOf<String, ImageAttachment>()
    private val importClaims = linkedMapOf<String, ImportClaim>()
    private val cleanupByConversation = linkedMapOf<String, MutableSet<String>>()
    private var committedIds: Set<String> = emptySet()
    private var reconciled = false

    override suspend fun initialise() {
        withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                ensureInitialisedLocked()
                val failures = withContext(NonCancellable) {
                    retryImportCleanupLocked()
                }
                if (failures.isNotEmpty()) throw cleanupFailure(failures.first())
            }
        }
    }

    override suspend fun importImage(uri: Uri, modelId: String): ImageAttachment {
        var claim: ImportClaim? = null
        try {
            val imported = withContext(ioDispatcher) {
                lifecycleMutex.withLock {
                    ensureInitialisedLocked()
                    // The blocking importer must finish and register its result even if
                    // the caller is cancelled while the dispatcher handoff is in flight.
                    withContext(NonCancellable) {
                        val result = importImageBlocking?.invoke(uri, modelId)
                            ?: imageImporter.importImage(uri, modelId) { id ->
                                claim = claimImportLocked(id)
                            }
                        val ownedClaim = claim ?: claimImportLocked(result.id).also { claim = it }
                        if (ownedClaim.id != result.id) {
                            throw failure(AttachmentFailure.STALE_SELECTION)
                        }
                        ownedClaim.attachment = result
                        drafts[result.id] = result
                        result
                    }
                }
            }
            claim = null
            return imported
        } catch (cancelled: CancellationException) {
            claim?.let { cleanupCancelledImport(it) }
            throw cancelled
        } catch (error: Exception) {
            claim?.let { cleanupCancelledImport(it) }
            throw error
        }
    }

    override suspend fun discard(images: List<ImageAttachment>) {
        withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                ensureInitialisedLocked()
                committedIds = attachmentDao.listIds().toSet()
                val failures = withContext(NonCancellable) {
                    discardLocked(images)
                }
                if (failures.isNotEmpty()) throw cleanupFailure(failures.first())
            }
        }
    }

    override suspend fun prepareTurn(
        conversationId: String?,
        text: String,
        model: ChatModel,
        images: List<ImageAttachment>,
        systemPrompt: String,
    ): PreparedAttachmentTurn {
        return withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                ensureInitialisedLocked()
                require(text.isNotBlank() || images.isNotEmpty()) {
                    "A turn needs text or at least one image"
                }

                if (conversationId != null &&
                    chatStore.observeConversations().first().none { it.id == conversationId }
                ) {
                    throw failure(AttachmentFailure.STALE_SELECTION)
                }

                // Refresh the committed-ID guard before accepting any caller-owned
                // selection. The transaction in ChatStore remains the final recheck.
                val databaseIds = attachmentDao.listIds().toSet()
                committedIds = databaseIds
                val selected = validateSelected(images, databaseIds, model)
                val history = conversationId?.let { chatStore.messagesSnapshot(it) }.orEmpty().requestHistory()
                val savedAttachmentIds = conversationId?.let {
                    attachmentDao.listForConversation(it).map { attachment -> attachment.id }.toSet()
                }.orEmpty()
                AttachmentPolicy.requireCount(
                    saved = conversationId?.let(attachmentDao::countForConversation) ?: 0,
                    pending = 0,
                    incoming = selected.size,
                )

                val historicalImages = validateHistory(history, databaseIds, savedAttachmentIds, model)
                historicalImages.forEach { authenticate(it, AttachmentFailure.INCOMPATIBLE_HISTORY) }
                selected.forEach { authenticate(it, AttachmentFailure.STALE_SELECTION) }

                val effective = effectiveSystemPrompt(systemPrompt)
                val context = prependSystemMessage(buildContext(history, text, selected), effective)
                val pending = chatStore.beginTurn(conversationId, text, model, selected, effective)

                // Nothing fallible happens after beginTurn succeeds. These are only
                // process-local ownership transfers for the IDs just committed.
                selected.forEach {
                    drafts.remove(it.id)
                    importClaims[it.id]?.let(::removeImportClaimLocked)
                }
                committedIds = committedIds + selected.map { it.id }
                PreparedAttachmentTurn(pending, context)
            }
        }
    }

    override suspend fun deleteConversation(id: String) {
        withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                ensureInitialisedLocked()
                val capturedIds = linkedSetOf<String>()
                capturedIds += attachmentDao.listForConversation(id).map { it.id }
                capturedIds += cleanupByConversation[id].orEmpty()

                // The database is authoritative. Do not touch any file unless this
                // operation has returned successfully.
                chatStore.deleteConversation(id)
                committedIds = committedIds - capturedIds

                val cleanup = withContext(NonCancellable) {
                    removeCapturedFiles(capturedIds)
                }
                if (cleanup.failures.isEmpty()) {
                    cleanupByConversation.remove(id)
                } else {
                    cleanupByConversation[id] = cleanup.failures.keys.toMutableSet()
                }
                // The cleanup block is NonCancellable, so a CancellationException
                // thrown by the file operation is an operation failure, not the
                // caller cancelling this delete. Preserve a real parent
                // cancellation before reporting a post-database cleanup failure.
                currentCoroutineContext().ensureActive()
                if (cleanup.failures.isNotEmpty()) {
                    throw cleanupFailure(cleanup.failures.values.first(), databaseDeleted = true)
                }
            }
        }
    }

    override suspend fun prepareRetry(
        conversationId: String,
        userMessageId: String,
        model: ChatModel,
        systemPrompt: String,
    ): PreparedAttachmentTurn =
        withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                ensureInitialisedLocked()
                val history = chatStore.messagesSnapshot(conversationId)
                val index = history.indexOfFirst { it.id == userMessageId }
                check(index >= 0) { "Message not found" }
                val user = history[index]
                check(user.role == USER_ROLE && user.status == MessageState.FAILED_ZDR)
                val prefix = history.take(index).requestHistory()
                val databaseIds = attachmentDao.listIds().toSet()
                committedIds = databaseIds
                val owned = attachmentDao.listForConversation(conversationId).map { it.id }.toSet()
                val images = validateHistory(prefix + user, databaseIds, owned, model)
                images.forEach { authenticate(it, AttachmentFailure.INCOMPATIBLE_HISTORY) }
                val effective = effectiveSystemPrompt(systemPrompt)
                val context = prependSystemMessage(buildContext(prefix, user.content, user.attachments), effective)
                val pending = chatStore.retryFailedTurn(userMessageId, model, effective)
                PreparedAttachmentTurn(pending, context)
            }
        }

    override suspend fun deleteFailedMessage(conversationId: String, userMessageId: String) {
        withContext(ioDispatcher) {
            lifecycleMutex.withLock {
                ensureInitialisedLocked()
                val user = chatStore.messagesSnapshot(conversationId).single { it.id == userMessageId }
                check(user.role == USER_ROLE && user.status == MessageState.FAILED_ZDR)
                val ids = user.attachments.map { it.id }.toSet()
                chatStore.deleteFailedTurn(userMessageId)
                committedIds = committedIds - ids
                val cleanup = withContext(NonCancellable) { removeCapturedFiles(ids) }
                if (cleanup.failures.isNotEmpty()) {
                    cleanupByConversation.getOrPut(conversationId) { mutableSetOf() }.addAll(cleanup.failures.keys)
                }
                currentCoroutineContext().ensureActive()
                if (cleanup.failures.isNotEmpty()) throw cleanupFailure(cleanup.failures.values.first(), databaseDeleted = true)
            }
        }
    }

    private fun List<Message>.requestHistory(): List<Message> = filter {
        // Explicit conversational allow-list; local timeline notices never leave
        // the device as provider messages. Pending/streaming assistants are
        // excluded separately by buildContext's status rule.
        (it.role == USER_ROLE || it.role == ASSISTANT_ROLE) && it.status != MessageState.FAILED_ZDR
    }

    private fun prependSystemMessage(
        context: List<ApiMessage>,
        effective: String,
    ): List<ApiMessage> =
        if (effective.isEmpty()) context else listOf(ApiMessage(SYSTEM_ROLE, effective)) + context

    override suspend fun preview(id: String, maxEdge: Int): Bitmap {
        require(maxEdge > 0) { "maxEdge must be positive" }
        return previewWithCancellableHandoff(id, maxEdge.coerceAtMost(MAX_PREVIEW_EDGE))
    }

    private suspend fun previewWithCancellableHandoff(id: String, maxEdge: Int): Bitmap =
        suspendCancellableCoroutine { continuation ->
            val worker = CoroutineScope(continuation.context).launch(ioDispatcher) {
                var decoded: Bitmap? = null
                try {
                    decoded = lifecycleMutex.withLock {
                        ensureInitialisedLocked()
                        if (id !in drafts && id !in committedIds) throw failure(AttachmentFailure.STALE_SELECTION)
                        try {
                            files.withPlaintext(id) { plaintext ->
                                decodePreview(plaintext, maxEdge)
                            }
                        } catch (error: AttachmentException) {
                            throw error
                        } catch (_: ImageDecoder.DecodeException) {
                            throw failure(AttachmentFailure.CORRUPT_IMAGE)
                        } catch (_: IOException) {
                            throw failure(AttachmentFailure.CORRUPT_IMAGE)
                        }
                    }

                    val value = requireNotNull(decoded)
                    continuation.resume(value) { _, bitmap, _ ->
                        recyclePreviewBitmap(bitmap)
                    }
                    decoded = null
                } catch (cancelled: CancellationException) {
                    decoded?.let(::recyclePreviewBitmap)
                    if (continuation.isActive) continuation.cancel(cancelled)
                } catch (error: AttachmentException) {
                    decoded?.let(::recyclePreviewBitmap)
                    resumePreviewFailure(continuation, error)
                } catch (_: ImageDecoder.DecodeException) {
                    decoded?.let(::recyclePreviewBitmap)
                    resumePreviewFailure(continuation, failure(AttachmentFailure.CORRUPT_IMAGE))
                } catch (_: IOException) {
                    decoded?.let(::recyclePreviewBitmap)
                    resumePreviewFailure(continuation, failure(AttachmentFailure.CORRUPT_IMAGE))
                } catch (error: Exception) {
                    decoded?.let(::recyclePreviewBitmap)
                    resumePreviewFailure(continuation, error)
                }
            }
            continuation.invokeOnCancellation { worker.cancel() }
        }

    private fun resumePreviewFailure(
        continuation: CancellableContinuation<Bitmap>,
        error: Throwable,
    ) {
        continuation.resumeWith(Result.failure(error))
    }

    private fun ensureInitialisedLocked() {
        if (reconciled) return
        // The DB read deliberately precedes files.initialise: an unreadable DB must
        // not trigger orphan cleanup, and both are blocking operations on IO.
        val ids = attachmentDao.listIds().toSet()
        files.initialise(ids)
        committedIds = ids
        reconciled = true
    }

    private fun claimImportLocked(id: String): ImportClaim {
        if (id in drafts || id in committedIds || id in importClaims) {
            throw failure(AttachmentFailure.STALE_SELECTION)
        }
        return ImportClaim(id).also { importClaims[id] = it }
    }

    private fun removeImportClaimLocked(claim: ImportClaim) {
        if (importClaims[claim.id] === claim) importClaims.remove(claim.id)
    }

    private fun retryImportCleanupLocked(): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        importClaims.values.filter { it.cleanupRequested }.toList().forEach { claim ->
            tryCleanupImportLocked(claim)?.let { failures += it }
        }
        return failures
    }

    private fun tryCleanupImportLocked(claim: ImportClaim): Throwable? {
        val ownsClaim = importClaims[claim.id] === claim
        val ownsDraft = claim.attachment?.let { drafts[claim.id] === it } == true
        if (!ownsClaim && !ownsDraft) return null
        if (claim.id in committedIds) {
            if (ownsDraft) drafts.remove(claim.id)
            removeImportClaimLocked(claim)
            return null
        }
        return try {
            files.remove(claim.id)
            if (ownsDraft) drafts.remove(claim.id)
            removeImportClaimLocked(claim)
            null
        } catch (error: Exception) {
            // Keep the claim/draft in its ownership map so a later explicit retry
            // cannot mistake the ciphertext for an unrelated orphan.
            error
        }
    }

    private fun validateSelected(
        images: List<ImageAttachment>,
        databaseIds: Set<String>,
        model: ChatModel,
    ): List<ImageAttachment> {
        val ids = images.map { it.id }
        if (ids.size != ids.toSet().size) throw failure(AttachmentFailure.STALE_SELECTION)
        val selected = images.map { image ->
            val owned = drafts[image.id]
            if (owned == null || image.id in databaseIds || owned != image) {
                throw failure(AttachmentFailure.STALE_SELECTION)
            }
            owned
        }
        selected.forEach { validateImage(it, model, AttachmentFailure.STALE_SELECTION) }
        return selected
    }

    private fun validateHistory(
        history: List<Message>,
        databaseIds: Set<String>,
        conversationAttachmentIds: Set<String>,
        model: ChatModel,
    ): List<ImageAttachment> {
        val historicalImages = history.flatMap { message ->
            if (message.role != USER_ROLE && message.attachments.isNotEmpty()) {
                throw failure(AttachmentFailure.INCOMPATIBLE_HISTORY)
            }
            if (message.role == USER_ROLE) message.attachments else emptyList()
        }
        historicalImages.forEach { image ->
            if (image.id !in databaseIds || image.id !in conversationAttachmentIds || image.id in drafts) {
                throw failure(AttachmentFailure.INCOMPATIBLE_HISTORY)
            }
            validateImage(image, model, AttachmentFailure.INCOMPATIBLE_HISTORY)
        }
        return historicalImages
    }

    private fun validateImage(
        image: ImageAttachment,
        model: ChatModel,
        invalidKind: AttachmentFailure,
    ) {
        try {
            AttachmentPolicy.requireAllowed(model.apiId, image.mimeType)
            if (image.originalBytes <= 0L || image.originalBytes > AttachmentPolicy.MAX_IMAGE_BYTES) {
                throw failure(AttachmentFailure.TOO_LARGE)
            }
            if (image.width < 1 || image.height < 1 ||
                image.width > AttachmentPolicy.MAX_DIMENSION || image.height > AttachmentPolicy.MAX_DIMENSION
            ) {
                throw failure(AttachmentFailure.EXCESSIVE_DIMENSIONS)
            }
        } catch (error: AttachmentException) {
            if (invalidKind == AttachmentFailure.INCOMPATIBLE_HISTORY &&
                error.kind != AttachmentFailure.UNVERIFIED_MODEL &&
                error.kind != AttachmentFailure.INVALID_FORMAT
            ) {
                throw failure(AttachmentFailure.INCOMPATIBLE_HISTORY, error)
            }
            throw error
        }
    }

    private fun authenticate(image: ImageAttachment, invalidKind: AttachmentFailure) {
        try {
            files.withPlaintext(image.id) { plaintext ->
                if (plaintext.size.toLong() != image.originalBytes) {
                    throw failure(invalidKind)
                }
                Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AttachmentException) {
            if (error.kind == invalidKind) throw error
            throw failure(invalidKind, error)
        } catch (error: Exception) {
            throw failure(invalidKind, error)
        }
    }

    private fun buildContext(
        history: List<Message>,
        text: String,
        selected: List<ImageAttachment>,
    ): List<ApiMessage> {
        val context = mutableListOf<ApiMessage>()
        history.forEach { message ->
            if (message.role == ASSISTANT_ROLE && message.status in NONTERMINAL_ASSISTANT_STATUSES) return@forEach
            if (message.role != USER_ROLE && message.role != ASSISTANT_ROLE) return@forEach
            if (message.content.isEmpty() && message.attachments.isEmpty()) return@forEach
            context += ApiMessage(
                role = if (message.role == USER_ROLE) "user" else "assistant",
                content = message.content,
                images = if (message.role == USER_ROLE) message.attachments.map(::deferredImage) else emptyList(),
            )
        }
        appendProposedUser(context, text, selected)
        return context
    }

    private fun appendProposedUser(context: MutableList<ApiMessage>, text: String, selected: List<ImageAttachment>) {
        if (text.isNotEmpty() || selected.isNotEmpty()) {
            context += ApiMessage("user", text, selected.map(::deferredImage))
        }
    }

    private fun deferredImage(image: ImageAttachment): ApiImage = ApiImage(
        mimeType = image.mimeType,
        byteCount = image.originalBytes,
        writeBase64 = { output ->
            files.withPlaintext(image.id) { plaintext ->
                val encoder = Base64.getEncoder().wrap(NonClosingOutputStream(output))
                try {
                    encoder.write(plaintext)
                } finally {
                    encoder.close()
                }
            }
        },
    )

    private fun decodePreview(plaintext: ByteArray, requestedEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(plaintext))
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (info.isAnimated) throw failure(AttachmentFailure.ANIMATED_IMAGE)
                if (info.size.width < 1 || info.size.height < 1 ||
                    info.size.width > AttachmentPolicy.MAX_DIMENSION || info.size.height > AttachmentPolicy.MAX_DIMENSION
                ) {
                    throw failure(AttachmentFailure.EXCESSIVE_DIMENSIONS)
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setOnPartialImageListener { false }
                val (width, height) = targetSize(info.size.width, info.size.height, requestedEdge)
                decoder.setTargetSize(width, height)
            }
        } catch (error: AttachmentException) {
            throw error
        } catch (error: ImageDecoder.DecodeException) {
            throw failure(AttachmentFailure.CORRUPT_IMAGE, error)
        } catch (error: IOException) {
            throw failure(AttachmentFailure.CORRUPT_IMAGE, error)
        }
    }

    private fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val largest = maxOf(width, height)
        if (largest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / largest.toDouble()
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }

    private fun discardLocked(images: List<ImageAttachment>): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        images.map { it.id }.distinct().forEach { id ->
            val owned = drafts[id] ?: return@forEach
            if (id in committedIds) {
                drafts.remove(id)
                importClaims[id]?.let(::removeImportClaimLocked)
                return@forEach
            }
            try {
                files.remove(id)
                if (drafts[id] === owned) {
                    drafts.remove(id)
                    importClaims[id]?.let(::removeImportClaimLocked)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += error
            }
        }
        return failures
    }

    private fun removeCapturedFiles(ids: Set<String>): CleanupAttempt {
        val failures = linkedMapOf<String, Throwable>()
        ids.forEach { id ->
            try {
                files.remove(id)
            } catch (error: CancellationException) {
                failures[id] = error
            } catch (error: Exception) {
                failures[id] = error
            }
        }
        return CleanupAttempt(failures)
    }

    private data class CleanupAttempt(
        val failures: Map<String, Throwable>,
    )

    private suspend fun cleanupCancelledImport(claim: ImportClaim) {
        withContext(ioDispatcher + NonCancellable) {
            lifecycleMutex.withLock {
                if (importClaims[claim.id] === claim) claim.cleanupRequested = true
                tryCleanupImportLocked(claim)
            }
        }
    }

    private class ImportClaim(
        val id: String,
        var attachment: ImageAttachment? = null,
        var cleanupRequested: Boolean = false,
    )

    private fun cleanupFailure(cause: Throwable, databaseDeleted: Boolean? = null): AttachmentException =
        AttachmentException(AttachmentFailure.CLEANUP, databaseDeleted).also { it.initCause(cause) }

    private fun failure(kind: AttachmentFailure, cause: Throwable? = null): AttachmentException =
        AttachmentException(kind).also { if (cause != null) it.initCause(cause) }

    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)
        override fun write(bytes: ByteArray) = delegate.write(bytes)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = delegate.write(bytes, offset, length)
        override fun flush() = delegate.flush()
        override fun close() = flush()
    }

    private fun recyclePreviewBitmap(bitmap: Bitmap) {
        runCatching {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        private const val MAX_PREVIEW_EDGE = 2_048
        private const val USER_ROLE = "USER"
        private const val ASSISTANT_ROLE = "ASSISTANT"
        private const val SYSTEM_ROLE = "system"
        private val NONTERMINAL_ASSISTANT_STATUSES = setOf("PENDING", "STREAMING")
    }
}
