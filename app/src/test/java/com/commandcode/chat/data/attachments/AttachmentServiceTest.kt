package com.commandcode.chat.data.attachments

import android.app.Application
import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.ChatStore
import com.commandcode.chat.data.commandcode.CommandCodeRequestFactory
import com.commandcode.chat.data.database.AttachmentDao
import com.commandcode.chat.data.database.AttachmentEntity
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.Key
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val files = ServiceAttachmentFiles()
    private val dao = ServiceAttachmentDao()
    private val chats = ServiceChatStore(dao)
    private var providerNumber = 0

    @After
    fun tearDown() {
        ShadowContentResolver.reset()
    }

    @Test
    fun failedPrepareLeavesDraftForRetryAndCommittedDraftCannotBeDiscarded() = runTest {
        val service = service()
        val bytes = ImageTestFactory.png()
        val uri = registerProvider(bytes, "photo.png", "image/png")
        val image = service.importImage(uri, ChatModel.SOL.apiId)

        chats.beginFailure = IOException("injected begin failure")
        val failure = captureFailure {
            service.prepareTurn(null, "hello", ChatModel.SOL, listOf(image))
        }

        assertTrue(failure is IOException)
        assertEquals(1, chats.beginCalls)
        assertEquals(0, chats.persistedTurns)
        assertTrue(files.stored.containsKey(image.id))

        chats.beginFailure = null
        val prepared = service.prepareTurn(null, "hello", ChatModel.SOL, listOf(image))

        assertEquals(2, chats.beginCalls)
        assertEquals(1, chats.persistedTurns)
        assertEquals(listOf("user"), prepared.messages.map { it.role })
        assertEquals("hello", prepared.messages.single().content)
        assertEquals(listOf("image/png"), prepared.messages.single().images.map { it.mimeType })

        val output = CloseTrackingOutputStream()
        prepared.messages.single().images.single().writeBase64(output)
        assertEquals(Base64.getEncoder().encodeToString(bytes), output.toByteArray().toString(Charsets.UTF_8))
        assertEquals(0, output.closeCalls)

        service.discard(listOf(image))
        assertTrue("a committed attachment is not a discardable draft", files.stored.containsKey(image.id))
        assertFalse(files.removed.contains(image.id))
    }

    @Test
    fun missingHistoricalImageBlocksBeforeBeginTurn() = runTest {
        val service = service()
        val image = importPng(service)
        val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(image))
        val beginCallsBeforeRetry = chats.beginCalls
        files.missing += image.id

        val failure = captureFailure {
            service.prepareTurn(first.pending.conversationId, "second", ChatModel.SOL, emptyList())
        }

        assertAttachmentFailure(AttachmentFailure.INCOMPATIBLE_HISTORY, failure)
        assertEquals(beginCallsBeforeRetry, chats.beginCalls)
        assertEquals(1, chats.persistedTurns)
    }

    @Test
    fun tamperedHistoricalImageBlocksBeforeBeginTurn() = runTest {
        val service = service()
        val image = importPng(service)
        val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(image))
        val beginCallsBeforeRetry = chats.beginCalls
        files.readFailures[image.id] = AttachmentException(AttachmentFailure.STORAGE)

        val failure = captureFailure {
            service.prepareTurn(first.pending.conversationId, "second", ChatModel.SOL, emptyList())
        }

        assertAttachmentFailure(AttachmentFailure.INCOMPATIBLE_HISTORY, failure)
        assertEquals(beginCallsBeforeRetry, chats.beginCalls)
        assertEquals(1, chats.persistedTurns)
    }

    @Test
    fun incompatibleHistoricalModelBlocksBeforeAuthenticationAndBeginTurn() = runTest {
        val service = service()
        val image = importPng(service)
        val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(image))
        val beginCallsBeforeRetry = chats.beginCalls
        files.readCount = 0
        val retired = ChatModel("example/retired", "Retired", ChatModel.SOL.apiFamily)

        val failure = captureFailure {
            service.prepareTurn(first.pending.conversationId, "second", retired, emptyList())
        }

        assertAttachmentFailure(AttachmentFailure.UNVERIFIED_MODEL, failure)
        assertEquals(0, files.readCount)
        assertEquals(beginCallsBeforeRetry, chats.beginCalls)
    }

    @Test
    fun contextPreservesHistoryOrderExcludesPendingAssistantAndKeepsImageOnlyUserRows() = runTest {
        val service = service()
        val historyImage = importPng(service)
        val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(historyImage))
        val pendingAssistant = first.pending.assistantMessageId
        val proposedImage = importPng(service)

        val prepared = service.prepareTurn(
            first.pending.conversationId,
            "",
            ChatModel.SOL,
            listOf(proposedImage),
        )

        assertEquals(listOf("user", "user"), prepared.messages.map { it.role })
        assertEquals(listOf("first", ""), prepared.messages.map { it.content })
        assertEquals(listOf(1, 1), prepared.messages.map { it.images.size })
        assertTrue(prepared.messages.none { it.content == "" && it.images.isEmpty() })
        assertTrue(chats.messagesSnapshot(first.pending.conversationId).any { it.id == pendingAssistant })
    }

    @Test
    fun unknownAndCommittedOrOtherChatIdsAreRejectedWithoutCleanup() = runTest {
        val service = service()
        val unknown = image("unknown")
        service.discard(listOf(unknown))
        assertTrue(files.removed.isEmpty())

        val image = importPng(service)
        val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(image))
        val before = chats.beginCalls

        service.discard(listOf(image))
        assertTrue(files.stored.containsKey(image.id))
        assertFalse(files.removed.contains(image.id))

        val failure = captureFailure {
            service.prepareTurn(null, "other chat", ChatModel.SOL, listOf(image))
        }
        assertAttachmentFailure(AttachmentFailure.STALE_SELECTION, failure)
        assertEquals(before, chats.beginCalls)
        assertTrue(first.pending.conversationId.isNotEmpty())
    }

    @Test
    fun duplicateImportIdFailureDoesNotCleanEarlierDraftOrFile() = runTest {
        val actualImporter = importer()
        var importFunction: (Uri, String) -> ImageAttachment = actualImporter::importImage
        val service = service(importImageBlocking = { uri, model -> importFunction(uri, model) })
        val first = importPng(service)
        importFunction = { _, _ -> first }

        val failure = captureFailure {
            service.importImage(Uri.parse("content://fixture/duplicate"), ChatModel.SOL.apiId)
        }

        assertAttachmentFailure(AttachmentFailure.STALE_SELECTION, failure)
        assertTrue(files.stored.containsKey(first.id))
        assertFalse(files.removed.contains(first.id))
        service.prepareTurn(null, "retained", ChatModel.SOL, listOf(first))
        assertEquals(1, chats.persistedTurns)
    }

    @Test
    fun failedDiscardAndDeleteReportCleanupAndRetainRetryOwnership() = runTest {
        val service = service()
        val draft = importPng(service)
        files.removeFailures += draft.id

        val discardFailure = captureFailure { service.discard(listOf(draft)) }
        assertAttachmentFailure(AttachmentFailure.CLEANUP, discardFailure)
        assertTrue(files.stored.containsKey(draft.id))

        files.removeFailures.clear()
        service.discard(listOf(draft))
        assertFalse(files.stored.containsKey(draft.id))

        val committed = importPng(service)
        val turn = service.prepareTurn(null, "delete", ChatModel.SOL, listOf(committed))
        files.removeFailures += committed.id

        val deleteFailure = captureFailure { service.deleteConversation(turn.pending.conversationId) }
        assertAttachmentFailure(AttachmentFailure.CLEANUP, deleteFailure)
        assertTrue(chats.conversations.value.none { it.id == turn.pending.conversationId })
        assertTrue(files.stored.containsKey(committed.id))

        files.removeFailures.clear()
        service.deleteConversation(turn.pending.conversationId)
        assertFalse(files.stored.containsKey(committed.id))
    }

    @Test
    fun fileCancellationAfterDatabaseDeleteIsReportedAsPostDatabaseCleanup() = runTest {
        val service = service()
        val image = importPng(service)
        val turn = service.prepareTurn(null, "delete", ChatModel.SOL, listOf(image))
        files.removeCancellationFailures += image.id

        val failure = captureFailure { service.deleteConversation(turn.pending.conversationId) }

        assertAttachmentFailure(AttachmentFailure.CLEANUP, failure)
        assertTrue((failure as AttachmentException).databaseDeleted == true)
        assertTrue(chats.conversations.value.none { it.id == turn.pending.conversationId })
        assertTrue(files.stored.containsKey(image.id))
    }

    @Test
    fun parentCancellationDuringPostDatabaseCleanupRemainsCancellation() = runTest {
        val service = service()
        val image = importPng(service)
        val turn = service.prepareTurn(null, "delete", ChatModel.SOL, listOf(image))
        files.removeEntered = CompletableDeferred()
        files.removeRelease = CompletableDeferred()

        val job = launch { service.deleteConversation(turn.pending.conversationId) }
        files.removeEntered!!.await()
        job.cancel()
        files.removeRelease!!.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(chats.conversations.value.none { it.id == turn.pending.conversationId })
    }

    @Test
    fun databaseDeleteFailureLeavesConversationAndCiphertextUntouched() = runTest {
        val service = service()
        val image = importPng(service)
        val turn = service.prepareTurn(null, "keep", ChatModel.SOL, listOf(image))
        chats.deleteFailure = IOException("injected database delete failure")

        val failure = captureFailure { service.deleteConversation(turn.pending.conversationId) }

        assertTrue(failure is IOException)
        assertTrue(chats.conversations.value.any { it.id == turn.pending.conversationId })
        assertTrue(files.stored.containsKey(image.id))
        assertFalse(files.removed.contains(image.id))
    }

    @Test
    fun deletingOneConversationLeavesReferencedOtherConversationFileUntouched() = runTest {
        val service = service()
        val firstImage = importPng(service)
        val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(firstImage))
        val secondImage = importPng(service)
        val second = service.prepareTurn(null, "second", ChatModel.SOL, listOf(secondImage))

        service.deleteConversation(first.pending.conversationId)

        assertFalse(files.stored.containsKey(firstImage.id))
        assertTrue(files.stored.containsKey(secondImage.id))
        assertTrue(chats.conversations.value.any { it.id == second.pending.conversationId })
    }

    @Test
    fun cancellationAfterEncryptionCleansOnlyThatImportAndPreservesPreviousDraft() = runTest {
        val actualImporter = importer()
        var importFunction: (Uri, String) -> ImageAttachment = actualImporter::importImage
        val service = service(importImageBlocking = { uri, model -> importFunction(uri, model) })
        val first = importPng(service)
        val secondId = UUID.randomUUID().toString()
        val secondBytes = byteArrayOf(8, 7, 6, 5)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        importFunction = { _, _ ->
            files.write(secondId, secondBytes)
            entered.complete(Unit)
            runBlocking { release.await() }
            ImageAttachment(secondId, "Image.png", "image/png", secondBytes.size.toLong(), secondBytes.size + 33L, 2, 2)
        }

        val job = launch(Dispatchers.Default) { service.importImage(Uri.parse("content://fixture/cancel"), ChatModel.SOL.apiId) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(files.stored.containsKey(first.id))
        assertFalse(files.stored.containsKey(secondId))
    }

    @Test
    fun cancellationThrownAfterEncryptionClaimsOnlyThatImportForCleanup() = runTest {
        val service = service()
        val first = importPng(service)
        files.cancelAfterWrite = true

        val failure = captureFailure {
            service.importImage(
                registerProvider(ImageTestFactory.png(), "cancelled.png", "image/png"),
                ChatModel.SOL.apiId,
            )
        }

        assertTrue(failure is CancellationException)
        assertTrue(files.stored.containsKey(first.id))
        assertEquals(1, files.stored.size)
    }

    @Test
    fun failedCancelledImportCleanupRetainsOwnershipForInitialiseRetry() = runTest {
        val service = service()
        val first = importPng(service)
        files.cancelAfterWrite = true
        files.failRemovalAfterWrite = true

        val failure = captureFailure {
            service.importImage(
                registerProvider(ImageTestFactory.png(), "failed-cleanup.png", "image/png"),
                ChatModel.SOL.apiId,
            )
        }

        assertTrue(failure is CancellationException)
        val cancelledId = files.stored.keys.single { it != first.id }
        assertTrue(files.stored.containsKey(first.id))
        assertTrue(files.stored.containsKey(cancelledId))

        files.removeFailures.clear()
        service.initialise()

        assertEquals(1, files.initialiseCalls)
        assertTrue(files.stored.containsKey(first.id))
        assertFalse(files.stored.containsKey(cancelledId))
    }

    @Test
    fun failedCancelledCleanupAfterDraftDeliveryRemainsDiscardable() = runTest {
        val actualImporter = importer()
        var importFunction: (Uri, String) -> ImageAttachment = actualImporter::importImage
        val service = service(importImageBlocking = { uri, model -> importFunction(uri, model) })
        val first = importPng(service)
        val secondId = UUID.randomUUID().toString()
        val secondBytes = byteArrayOf(3, 2, 1, 0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var second: ImageAttachment
        importFunction = { _, _ ->
            files.failRemovalAfterWrite = true
            files.write(secondId, secondBytes)
            entered.complete(Unit)
            runBlocking { release.await() }
            ImageAttachment(secondId, "Image.png", "image/png", secondBytes.size.toLong(), secondBytes.size + 33L, 2, 2)
                .also { second = it }
        }

        val job = launch(Dispatchers.Default) { service.importImage(Uri.parse("content://fixture/cancel-delivered"), ChatModel.SOL.apiId) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(files.stored.containsKey(first.id))
        assertTrue(files.stored.containsKey(secondId))

        files.removeFailures.clear()
        service.discard(listOf(second))

        assertFalse(files.stored.containsKey(secondId))
        assertTrue(files.stored.containsKey(first.id))
    }

    @Test
    fun laterFailedImportDoesNotEraseAnEarlierValidDraft() = runTest {
        val service = service()
        val first = importPng(service)
        val invalid = registerProvider(byteArrayOf(1, 2, 3), "bad.png", "image/png")

        val failure = captureFailure { service.importImage(invalid, ChatModel.SOL.apiId) }

        assertAttachmentFailure(AttachmentFailure.CORRUPT_IMAGE, failure)
        assertTrue(files.stored.containsKey(first.id))
    }

    @Test
    fun initialiseReadsDatabaseBeforeFilesAndRunsReconciliationOnlyOnce() = runTest {
        val service = service()
        val orphan = UUID.randomUUID().toString()
        files.stored[orphan] = byteArrayOf(4, 5)
        dao.failListIds = IOException("database unreadable")

        val failure = captureFailure { service.initialise() }

        assertTrue(failure is IOException)
        assertEquals(0, files.initialiseCalls)
        assertTrue(files.stored.containsKey(orphan))

        dao.failListIds = null
        service.initialise()
        assertEquals(1, files.initialiseCalls)
        assertFalse(files.stored.containsKey(orphan))

        val draft = importPng(service)
        service.initialise()
        assertEquals(1, files.initialiseCalls)
        assertTrue(files.stored.containsKey(draft.id))
    }

    @Test
    fun deletedConversationIsNotRecreatedByPrepare() = runTest {
        val service = service()
        val first = service.prepareTurn(null, "first", ChatModel.SOL, emptyList())
        service.deleteConversation(first.pending.conversationId)
        val before = chats.beginCalls

        val failure = captureFailure {
            service.prepareTurn(first.pending.conversationId, "again", ChatModel.SOL, emptyList())
        }

        assertAttachmentFailure(AttachmentFailure.STALE_SELECTION, failure)
        assertEquals(before, chats.beginCalls)
        assertTrue(chats.conversations.value.none { it.id == first.pending.conversationId })
    }

    @Test
    fun deferredWritersAndPreviewAuthenticateThroughSerialStoreCallbacks() = runTest {
        val service = service()
        val image = importPng(service)
        val prepared = service.prepareTurn(null, "preview", ChatModel.SOL, listOf(image))
        val apiImage = prepared.messages.single().images.single()
        val release = CompletableDeferred<Unit>()
        files.readBarrier = release
        val firstWriter = async(Dispatchers.Default) {
            apiImage.writeBase64(ByteArrayOutputStream())
        }
        files.firstReadEntered.await()
        val secondAttempted = CompletableDeferred<Unit>()
        files.secondReadAttempted = secondAttempted
        val secondWriter = async(Dispatchers.Default) {
            apiImage.writeBase64(ByteArrayOutputStream())
        }
        secondAttempted.await()
        assertEquals(1, files.maxActiveReads)
        release.complete(Unit)
        firstWriter.await()
        secondWriter.await()

        val bitmap = service.preview(image.id, maxEdge = 4096)
        try {
            assertEquals(2, bitmap.width)
            assertEquals(2, bitmap.height)
            assertTrue(files.maxActiveReads == 1)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun previewCancellationAfterDecodeRecyclesBitmapBeforeCancellationEscapes() = runTest {
        val service = service()
        val image = importPng(service)
        var cancellationObserved = false
        lateinit var previewJob: Job
        files.cancelAfterPreview = { previewJob.cancel() }

        previewJob = launch {
            try {
                service.preview(image.id, maxEdge = 256)
            } catch (cancelled: CancellationException) {
                cancellationObserved = true
                throw cancelled
            }
        }
        previewJob.join()

        assertTrue(previewJob.isCancelled)
        assertTrue(cancellationObserved)
        assertTrue(files.previewBitmap != null)
        assertTrue(files.previewBitmap?.isRecycled == true)
    }

    @Test
    fun previewUnexpectedServiceFailureReachesCaller() = runTest {
        val service = service()
        val image = importPng(service)
        files.readFailures[image.id] = IllegalStateException("private failure")

        val failure = captureFailure { service.preview(image.id, maxEdge = 256) }

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun actualDeferredWriterCleansEncryptedPlaintextWhenRequestSinkFailsAndStopsLaterImages() = runTest {
        val realFiles = ObservingAttachmentFiles(
            EncryptedAttachmentStore(temporaryFolder.newFolder("request-attachments"), ServiceKeyProvider()),
        )
        val importer = ImageImporter(
            resolver = RuntimeEnvironment.getApplication().contentResolver,
            inspector = ImageInspector(),
            files = realFiles,
        )
        val candidates = listOf(
            UUID.randomUUID().toString() to byteArrayOf(1, 2, 3, 4),
            UUID.randomUUID().toString() to byteArrayOf(5, 6, 7, 8),
        )
        var nextCandidate = 0
        val service = AttachmentService(
            chatStore = chats,
            attachmentDao = dao,
            imageImporter = importer,
            files = realFiles,
            importImageBlocking = { _, _ ->
                val (id, bytes) = candidates[nextCandidate++]
                ImageAttachment(
                    id = id,
                    displayName = "Image.png",
                    mimeType = "image/png",
                    originalBytes = bytes.size.toLong(),
                    encryptedBytes = realFiles.write(id, bytes),
                    width = 2,
                    height = 2,
                )
            },
        )
        val first = service.importImage(Uri.parse("content://fixture/first"), ChatModel.SOL.apiId)
        val second = service.importImage(Uri.parse("content://fixture/second"), ChatModel.SOL.apiId)
        val prepared = service.prepareTurn(null, "send", ChatModel.SOL, listOf(first, second))
        val cleanupBeforeRequest = realFiles.cleanedIds.size

        val request = CommandCodeRequestFactory.create(ChatModel.SOL, prepared.messages)
        val failure = runCatching {
            request.writeTo(FailingDiscardingSink().buffer())
        }.exceptionOrNull()

        assertTrue("expected downstream IOException, got $failure", failure is IOException)
        assertEquals(listOf(first.id), realFiles.cleanedIds.drop(cleanupBeforeRequest))
        assertEquals(listOf(true), realFiles.clearedBuffers.drop(cleanupBeforeRequest))
        assertEquals(0, realFiles.plaintextOutstanding)
    }

    private fun service(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        importImageBlocking: ((Uri, String) -> ImageAttachment)? = null,
    ): AttachmentService {
        val imageImporter = importer()
        return if (importImageBlocking == null) {
            AttachmentService(chats, dao, imageImporter, files, dispatcher)
        } else {
            AttachmentService(chats, dao, imageImporter, files, dispatcher, importImageBlocking)
        }
    }

    private fun importer(): ImageImporter = ImageImporter(
        resolver = RuntimeEnvironment.getApplication().contentResolver,
        inspector = ImageInspector(),
        files = files,
    )

    private suspend fun importPng(service: AttachmentService): ImageAttachment {
        val bytes = ImageTestFactory.png()
        return service.importImage(registerProvider(bytes, "photo.png", "image/png"), ChatModel.SOL.apiId)
    }

    private fun registerProvider(bytes: ByteArray, name: String, mime: String): Uri {
        val authority = "fixture.attachments.service.${providerNumber++}"
        val context = ApplicationProvider.getApplicationContext<Context>()
        AttachmentContentProvider(
            bytes = bytes,
            size = null,
            declaredMime = mime,
            displayName = name,
        ).registerWith(context, authority)
        return Uri.parse("content://$authority/doc/1")
    }

    private fun image(id: String): ImageAttachment = ImageAttachment(
        id = id,
        displayName = "Image.png",
        mimeType = "image/png",
        originalBytes = 4,
        encryptedBytes = 37,
        width = 2,
        height = 2,
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        fail("expected operation to fail")
        throw AssertionError("expected operation to fail")
    }

    private fun assertAttachmentFailure(expected: AttachmentFailure, failure: Throwable) {
        assertTrue("expected AttachmentException, got $failure", failure is AttachmentException)
        assertEquals(expected, (failure as AttachmentException).kind)
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closeCalls = 0
            private set

        override fun close() {
            closeCalls++
            super.close()
        }
    }

    private class FailingDiscardingSink : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            source.skip(byteCount)
            throw IOException("injected request sink failure")
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private class ObservingAttachmentFiles(
        private val delegate: AttachmentFiles,
    ) : AttachmentFiles {
        val cleanedIds = mutableListOf<String>()
        val clearedBuffers = mutableListOf<Boolean>()
        var plaintextOutstanding = 0
            private set

        override fun initialise(referencedIds: Set<String>) = delegate.initialise(referencedIds)

        override fun write(id: String, plaintext: ByteArray): Long = delegate.write(id, plaintext)

        override fun <T> withPlaintext(id: String, block: (ByteArray) -> T): T {
            var observed: ByteArray? = null
            return try {
                delegate.withPlaintext(id) { plaintext ->
                    observed = plaintext
                    plaintextOutstanding++
                    block(plaintext)
                }
            } finally {
                observed?.let { plaintext ->
                    clearedBuffers += plaintext.all { it == 0.toByte() }
                    cleanedIds += id
                    plaintextOutstanding--
                }
            }
        }

        override fun remove(id: String) = delegate.remove(id)
    }

    private class ServiceKeyProvider : AttachmentKeyProvider {
        private val key: Key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun initialise(allowCreate: Boolean) = Unit

        override fun existingKey(): Key = key
    }

    private class ServiceAttachmentFiles : AttachmentFiles {
        val stored = linkedMapOf<String, ByteArray>()
        val removed = mutableListOf<String>()
        val missing = mutableSetOf<String>()
        val readFailures = mutableMapOf<String, Throwable>()
        val removeFailures = mutableSetOf<String>()
        var initialiseCalls = 0
        var failInitialise: Throwable? = null
        var cancelAfterWrite = false
        var failRemovalAfterWrite = false
        val removeCancellationFailures = mutableSetOf<String>()
        var removeEntered: CompletableDeferred<Unit>? = null
        var removeRelease: CompletableDeferred<Unit>? = null
        var readCount = 0
        var maxActiveReads = 0
        var readBarrier: CompletableDeferred<Unit>? = null
        var firstReadEntered = CompletableDeferred<Unit>()
        var secondReadAttempted: CompletableDeferred<Unit>? = null
        var previewBitmap: Bitmap? = null
        var cancelAfterPreview: (() -> Unit)? = null
        private val activeReads = AtomicInteger(0)
        private val readLock = Any()

        override fun initialise(referencedIds: Set<String>) {
            initialiseCalls++
            failInitialise?.let { throw it }
            stored.keys.retainAll(referencedIds)
        }

        override fun write(id: String, plaintext: ByteArray): Long {
            stored[id] = plaintext.copyOf()
            if (failRemovalAfterWrite) removeFailures += id
            if (cancelAfterWrite) throw CancellationException("cancelled after encryption")
            return plaintext.size.toLong() + 33L
        }

        override fun <T> withPlaintext(id: String, block: (ByteArray) -> T): T {
            secondReadAttempted?.complete(Unit)
            synchronized(readLock) {
                val active = activeReads.incrementAndGet()
                maxActiveReads = maxOf(maxActiveReads, active)
                readCount++
                firstReadEntered.complete(Unit)
                try {
                    readBarrier?.let { runBlocking { it.await() } }
                    readFailures[id]?.let { throw it }
                    if (id in missing || id !in stored) throw AttachmentException(AttachmentFailure.STORAGE)
                    val result = block(stored.getValue(id).copyOf())
                    if (result is Bitmap) {
                        previewBitmap = result
                        cancelAfterPreview?.invoke()
                    }
                    return result
                } finally {
                    activeReads.decrementAndGet()
                }
            }
        }

        override fun remove(id: String) {
            removeEntered?.complete(Unit)
            removeRelease?.let { runBlocking { it.await() } }
            if (id in removeCancellationFailures) throw CancellationException("injected cleanup cancellation")
            removeFailures.firstOrNull { it == id }?.let { throw IOException("injected remove failure") }
            stored.remove(id)
            removed += id
        }
    }

    private class ServiceAttachmentDao : AttachmentDao {
        val rows = linkedMapOf<String, AttachmentEntity>()
        val messageConversations = mutableMapOf<String, String>()
        var failListIds: Throwable? = null

        override fun listIds(): List<String> {
            failListIds?.let { throw it }
            return rows.keys.toList()
        }

        override fun listForConversation(conversationId: String): List<AttachmentEntity> =
            rows.values.filter { messageConversations[it.messageId] == conversationId }

        override fun countForConversation(conversationId: String): Int = listForConversation(conversationId).size

        override fun insert(values: List<AttachmentEntity>) {
            values.forEach { rows[it.id] = it }
        }
    }

    private class ServiceChatStore(private val dao: ServiceAttachmentDao) : ChatStore {
        val conversations = MutableStateFlow<List<Conversation>>(emptyList())
        private val messages = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        var beginFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var beginCalls = 0
        var persistedTurns = 0
        var deleteCalls = 0

        override fun observeConversations(): Flow<List<Conversation>> = conversations

        override fun observeMessages(conversationId: String): Flow<List<Message>> =
            messages[conversationId] ?: flowOf(emptyList())

        override suspend fun messagesSnapshot(conversationId: String): List<Message> = messages[conversationId]?.value ?: emptyList()

        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
        ): PendingTurn {
            beginCalls++
            beginFailure?.let { throw it }
            val id = conversationId ?: "conversation-${persistedTurns + 1}"
            if (conversationId != null && conversations.value.none { it.id == id }) {
                error("Conversation not found")
            }
            val userId = "user-${persistedTurns + 1}"
            val assistantId = "assistant-${persistedTurns + 1}"
            if (conversationId == null) {
                conversations.value += Conversation(id, if (text.isBlank()) "Image chat" else text, model.apiId, 1L, 2L)
            }
            val existing = messages.getOrPut(id) { MutableStateFlow(emptyList()) }
            existing.value += Message(userId, id, "USER", text, null, existing.value.size.toLong() + 1L, "COMPLETE", attachments)
            existing.value += Message(assistantId, id, "ASSISTANT", "", model.apiId, existing.value.size.toLong() + 1L, "PENDING")
            dao.messageConversations[userId] = id
            dao.messageConversations[assistantId] = id
            dao.insert(attachments.mapIndexed { position, image ->
                AttachmentEntity(
                    id = image.id,
                    messageId = userId,
                    displayName = image.displayName,
                    mimeType = image.mimeType,
                    originalBytes = image.originalBytes,
                    encryptedBytes = image.encryptedBytes,
                    width = image.width,
                    height = image.height,
                    position = position,
                )
            })
            persistedTurns++
            return PendingTurn(id, userId, assistantId)
        }

        override suspend fun checkpointAssistant(messageId: String, text: String) = Unit

        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean = true

        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) = Unit

        override suspend fun deleteConversation(id: String) {
            deleteCalls++
            deleteFailure?.let { throw it }
            conversations.value = conversations.value.filterNot { it.id == id }
            messages.remove(id)
            val removedMessageIds = dao.messageConversations.filterValues { it == id }.keys
            removedMessageIds.forEach(dao.messageConversations::remove)
            dao.rows.values.removeIf { it.messageId in removedMessageIds }
        }
    }
}
