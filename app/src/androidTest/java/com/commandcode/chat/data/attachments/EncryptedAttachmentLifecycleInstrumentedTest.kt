package com.commandcode.chat.data.attachments

import android.content.Context
import com.commandcode.chat.StreamSource
import com.commandcode.chat.data.commandcode.StreamEvent
import com.commandcode.chat.data.database.AttachmentDao
import com.commandcode.chat.data.database.ChatDatabase
import com.commandcode.chat.data.database.ChatRepository
import com.commandcode.chat.data.database.DatabaseChatStore
import com.commandcode.chat.data.database.EncryptedTestEnvironment
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.commandcode.ApiMessage
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.TokenUsage
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedAttachmentLifecycleInstrumentedTest {
    @After
    fun clearOwnedSources() {
        OwnedImageSources.clear()
    }

    @Test
    fun generatedFormatsImportSendContextAndPreviewAfterEncryptedDatabaseReopen() = runBlocking {
        val environment = EncryptedTestEnvironment(targetContext())
        var database: ChatDatabase? = null
        var reopened: ChatDatabase? = null
        try {
            val original = environment.openDatabase()
            database = original
            val files = environment.newAttachmentStore()
            val service = service(original, files)
            service.initialise()
            val modelIds = listOf(
                "gpt-5.6-sol",
                "gpt-5.6-luna",
                "google/gemini-3.7-flash",
            )
            val imported = AndroidImageTestFixtures.all().mapIndexed { index, format ->
                val source = OwnedImageSources.publish(format)
                source to service.importImage(source.uri, modelIds[index])
            }

            val prepared = service.prepareTurn(
                conversationId = null,
                text = "send these formats",
                model = ChatModel.SOL,
                images = imported.map { it.second },
            )
            assertEquals(listOf("user"), prepared.messages.map { it.role })
            assertEquals(3, prepared.messages.single().images.size)
            val streamedBytes = mutableListOf<ByteArray>()
            val fakeStream = StreamSource { _, _, messages, _ ->
                messages.flatMap(ApiMessage::images).forEach { image ->
                    val encoded = ByteArrayOutputStream()
                    image.writeBase64(encoded)
                    streamedBytes += Base64.getDecoder().decode(encoded.toByteArray())
                }
                kotlinx.coroutines.flow.flowOf(StreamEvent.Done)
            }
            fakeStream.stream("offline-test-key".toCharArray(), ChatModel.SOL, prepared.messages, true).first()
            assertEquals(imported.size, streamedBytes.size)
            imported.map { it.first.bytes }.zip(streamedBytes).forEach { (expectedBytes, actualBytes) ->
                assertArrayEquals(expectedBytes, actualBytes)
            }
            imported.forEach { (source, image) ->
                assertEquals(source.bytes.size.toLong(), image.originalBytes)
                assertEquals(source.bytes.size.toLong() + 33L, image.encryptedBytes)
                assertEquals(image.encryptedBytes, File(environment.attachmentRoot, image.id).length())
                OwnedImageSources.revoke(source)
                assertFalse(OwnedImageSources.isAvailable(source.uri))
            }

            original.close()
            database = null
            val reopenedDatabase = environment.openDatabase()
            reopened = reopenedDatabase
            val repository = ChatRepository(reopenedDatabase)
            val saved = repository.messagesSnapshot(prepared.pending.conversationId)
            assertEquals(3, saved.flatMap(Message::attachments).size)

            val reopenedFiles = environment.newAttachmentStore()
            val reopenedService = service(reopenedDatabase, reopenedFiles)
            reopenedService.initialise()
            saved.flatMap(Message::attachments).forEach { image ->
                val preview = reopenedService.preview(image.id, maxEdge = 256)
                try {
                    assertEquals(3, preview.width)
                    assertEquals(2, preview.height)
                } finally {
                    preview.recycle()
                }
            }
        } finally {
            reopened?.close()
            database?.close()
            environment.cleanUp()
        }
    }

    @Test
    fun imageOnlyFollowUpRetainsEncryptedHistoricalImageContextAfterSourceRevocation() = runBlocking {
        val environment = EncryptedTestEnvironment(targetContext())
        var database: ChatDatabase? = null
        try {
            val opened = environment.openDatabase()
            database = opened
            val repository = ChatRepository(opened)
            val files = environment.newAttachmentStore()
            val service = service(opened, files)
            service.initialise()

            val firstSource = OwnedImageSources.publish(AndroidImageTestFixtures.all()[0])
            val firstImage = service.importImage(firstSource.uri, ChatModel.SOL.apiId)
            val first = service.prepareTurn(null, "first image", ChatModel.SOL, listOf(firstImage))
            OwnedImageSources.revoke(firstSource)

            val secondSource = OwnedImageSources.publish(AndroidImageTestFixtures.all()[1])
            val secondImage = service.importImage(secondSource.uri, ChatModel.SOL.apiId)
            val followUp = service.prepareTurn(
                conversationId = first.pending.conversationId,
                text = "",
                model = ChatModel.SOL,
                images = listOf(secondImage),
            )
            OwnedImageSources.revoke(secondSource)

            assertEquals(listOf("user", "user"), followUp.messages.map(ApiMessage::role))
            assertEquals(listOf("first image", ""), followUp.messages.map(ApiMessage::content))
            assertEquals(listOf(listOf(firstImage.mimeType), listOf(secondImage.mimeType)), followUp.messages.map { message ->
                message.images.map { image -> image.mimeType }
            })
            assertTrue(followUp.messages.none { it.role == "assistant" })

            val encodedFollowUp = ByteArrayOutputStream()
            followUp.messages.last().images.single().writeBase64(encodedFollowUp)
            assertArrayEquals(secondSource.bytes, Base64.getDecoder().decode(encodedFollowUp.toByteArray()))

            service.deleteConversation(first.pending.conversationId)
            assertTrue(repository.observeConversations().first().isEmpty())
            assertTrue(environment.attachmentRoot.listFiles().orEmpty().isEmpty())
        } finally {
            database?.close()
            environment.cleanUp()
        }
    }

    @Test
    fun deletingOneEncryptedChatRemovesOnlyItsCopyAndLeavesOtherChatAndSourcesUntouched() = runBlocking {
        val environment = EncryptedTestEnvironment(targetContext())
        var database: ChatDatabase? = null
        try {
            val opened = environment.openDatabase()
            database = opened
            val repository = ChatRepository(opened)
            val files = environment.newAttachmentStore()
            val service = service(opened, files)
            service.initialise()

            val firstSource = OwnedImageSources.publish(AndroidImageTestFixtures.all()[0])
            val firstBytes = firstSource.bytes.copyOf()
            val firstImage = service.importImage(firstSource.uri, ChatModel.SOL.apiId)
            val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(firstImage))
            val secondSource = OwnedImageSources.publish(AndroidImageTestFixtures.all()[1])
            val secondBytes = secondSource.bytes.copyOf()
            val secondImage = service.importImage(secondSource.uri, ChatModel.SOL.apiId)
            val second = service.prepareTurn(null, "second", ChatModel.SOL, listOf(secondImage))

            assertArrayEquals(firstBytes, OwnedImageSources.readThroughTargetResolver(firstSource))
            assertArrayEquals(secondBytes, OwnedImageSources.readThroughTargetResolver(secondSource))

            service.deleteConversation(first.pending.conversationId)

            assertFalse(File(environment.attachmentRoot, firstImage.id).exists())
            assertTrue(File(environment.attachmentRoot, secondImage.id).isFile)
            assertTrue(repository.observeConversations().first().any { it.id == second.pending.conversationId })
            assertEquals(listOf(secondImage.id), opened.attachments().listIds())
            assertArrayEquals(firstBytes, OwnedImageSources.readThroughTargetResolver(firstSource))
            assertArrayEquals(secondBytes, OwnedImageSources.readThroughTargetResolver(secondSource))
            assertTrue(OwnedImageSources.isAvailable(firstSource.uri))
            assertTrue(OwnedImageSources.isAvailable(secondSource.uri))

            service.deleteConversation(second.pending.conversationId)
            assertTrue(environment.attachmentRoot.listFiles().orEmpty().isEmpty())
            assertArrayEquals(firstBytes, OwnedImageSources.readThroughTargetResolver(firstSource))
            assertArrayEquals(secondBytes, OwnedImageSources.readThroughTargetResolver(secondSource))
            OwnedImageSources.revoke(firstSource)
            OwnedImageSources.revoke(secondSource)
            assertFalse(OwnedImageSources.isAvailable(firstSource.uri))
            assertFalse(OwnedImageSources.isAvailable(secondSource.uri))
        } finally {
            database?.close()
            environment.cleanUp()
        }
    }

    @Test
    fun injectedImportCommitAndCleanupFailuresPreserveOwnershipUntilReadableRestart() = runBlocking {
        val environment = EncryptedTestEnvironment(targetContext())
        var database: ChatDatabase? = null
        try {
            val opened = environment.openDatabase()
            database = opened
            val repository = ChatRepository(opened)
            val delegateFiles = environment.newAttachmentStore()
            val files = FaultInjectingAttachmentFiles(delegateFiles)
            val chats = FailingBeginChatStore(DatabaseChatStore(repository))
            val service = service(opened, files, chats)
            service.initialise()

            val failedImportSource = OwnedImageSources.publish(AndroidImageTestFixtures.all()[0])
            files.failWrites = 1
            val importFailure = captureFailure {
                service.importImage(failedImportSource.uri, ChatModel.SOL.apiId)
            }
            assertTrue(importFailure is IOException)
            assertTrue(environment.attachmentRoot.listFiles().orEmpty().isEmpty())
            OwnedImageSources.revoke(failedImportSource)

            val committedSource = OwnedImageSources.publish(AndroidImageTestFixtures.all()[0])
            val committedImage = service.importImage(committedSource.uri, ChatModel.SOL.apiId)
            chats.failBegins = 1
            val commitFailure = captureFailure {
                service.prepareTurn(null, "commit retry", ChatModel.SOL, listOf(committedImage))
            }
            assertTrue(commitFailure is IOException)
            assertTrue(File(environment.attachmentRoot, committedImage.id).isFile)
            val committedTurn = service.prepareTurn(null, "commit retry", ChatModel.SOL, listOf(committedImage))
            OwnedImageSources.revoke(committedSource)

            val draftSource = OwnedImageSources.publish(AndroidImageTestFixtures.all()[1])
            val draftImage = service.importImage(draftSource.uri, ChatModel.SOL.apiId)
            OwnedImageSources.revoke(draftSource)
            files.failRemoves += draftImage.id
            val cleanupFailure = captureFailure { service.discard(listOf(draftImage)) }
            assertAttachmentFailure(AttachmentFailure.CLEANUP, cleanupFailure)
            assertTrue(File(environment.attachmentRoot, draftImage.id).isFile)
            files.failRemoves.clear()

            // A restart must read the encrypted DB before it is allowed to clean the
            // orphaned draft ciphertext.
            val gatedDao = ReadGateAttachmentDao(opened.attachments())
            val blockedRestart = service(opened, files, DatabaseChatStore(repository), gatedDao)
            gatedDao.failure = IOException("injected unreadable database")
            val blockedFailure = captureFailure { blockedRestart.initialise() }
            assertTrue(blockedFailure is IOException)
            assertTrue(File(environment.attachmentRoot, draftImage.id).isFile)
            gatedDao.failure = null
            blockedRestart.initialise()
            assertFalse(File(environment.attachmentRoot, draftImage.id).exists())
            assertTrue(File(environment.attachmentRoot, committedImage.id).isFile)

            files.failRemoves += committedImage.id
            val deleteFailure = captureFailure {
                blockedRestart.deleteConversation(committedTurn.pending.conversationId)
            }
            assertAttachmentFailure(AttachmentFailure.CLEANUP, deleteFailure)
            assertTrue((deleteFailure as AttachmentException).databaseDeleted == true)
            assertTrue(File(environment.attachmentRoot, committedImage.id).isFile)
            files.failRemoves.clear()

            val cleanupOnReadableRestart = service(opened, files)
            cleanupOnReadableRestart.initialise()
            assertFalse(File(environment.attachmentRoot, committedImage.id).exists())
            assertTrue(repository.observeConversations().first().none { it.id == committedTurn.pending.conversationId })
        } finally {
            database?.close()
            environment.cleanUp()
        }
    }

    @Test
    fun tamperedCiphertextMakesHistoryIncompatibleWithoutDeletingItOrRegeneratingKey() = runBlocking {
        val environment = EncryptedTestEnvironment(targetContext())
        var database: ChatDatabase? = null
        var reopened: ChatDatabase? = null
        try {
            val opened = environment.openDatabase()
            database = opened
            val repository = ChatRepository(opened)
            val files = environment.newAttachmentStore()
            val service = service(opened, files)
            service.initialise()
            val source = OwnedImageSources.publish(AndroidImageTestFixtures.all()[0])
            val image = service.importImage(source.uri, ChatModel.SOL.apiId)
            val turn = service.prepareTurn(null, "keep history", ChatModel.SOL, listOf(image))
            OwnedImageSources.revoke(source)
            File(environment.attachmentRoot, image.id).appendBytes(byteArrayOf(1))
            database.close()
            database = null

            val reopenedDatabase = environment.openDatabase()
            reopened = reopenedDatabase
            val reopenedRepository = ChatRepository(reopenedDatabase)
            val restarted = service(reopenedDatabase, environment.newAttachmentStore())
            restarted.initialise()
            val before = reopenedRepository.messagesSnapshot(turn.pending.conversationId)
            val failure = captureFailure {
                restarted.prepareTurn(turn.pending.conversationId, "retry", ChatModel.SOL, emptyList())
            }
            assertAttachmentFailure(AttachmentFailure.INCOMPATIBLE_HISTORY, failure)
            assertEquals(before, reopenedRepository.messagesSnapshot(turn.pending.conversationId))
            assertTrue(environment.hasAttachmentAlias())
            assertTrue(File(environment.attachmentRoot, image.id).isFile)
        } finally {
            reopened?.close()
            database?.close()
            environment.cleanUp()
        }
    }

    @Test
    fun missingAttachmentKeyDoesNotRegenerateKeyDeleteCiphertextOrDeleteHistory() = runBlocking {
        val environment = EncryptedTestEnvironment(targetContext())
        var database: ChatDatabase? = null
        var reopened: ChatDatabase? = null
        try {
            val opened = environment.openDatabase()
            database = opened
            val repository = ChatRepository(opened)
            val service = service(opened, environment.newAttachmentStore())
            service.initialise()
            val source = OwnedImageSources.publish(AndroidImageTestFixtures.all()[0])
            val image = service.importImage(source.uri, ChatModel.SOL.apiId)
            val turn = service.prepareTurn(null, "keep key history", ChatModel.SOL, listOf(image))
            OwnedImageSources.revoke(source)
            database.close()
            database = null
            environment.deleteAttachmentAlias()
            assertFalse(environment.hasAttachmentAlias())

            val reopenedDatabase = environment.openDatabase()
            reopened = reopenedDatabase
            val reopenedRepository = ChatRepository(reopenedDatabase)
            val restarted = service(reopenedDatabase, environment.newAttachmentStore())
            val failure = captureFailure { restarted.initialise() }
            assertAttachmentFailure(AttachmentFailure.KEY_UNAVAILABLE, failure)
            assertFalse(environment.hasAttachmentAlias())
            assertTrue(File(environment.attachmentRoot, image.id).isFile)
            assertTrue(reopenedRepository.messagesSnapshot(turn.pending.conversationId).any {
                image.id in it.attachments.map(ImageAttachment::id)
            })
        } finally {
            reopened?.close()
            database?.close()
            environment.cleanUp()
        }
    }

    private fun service(
        database: ChatDatabase,
        files: AttachmentFiles,
        chats: com.commandcode.chat.ChatStore = DatabaseChatStore(ChatRepository(database)),
        attachmentDao: AttachmentDao = database.attachments(),
    ): AttachmentService = AttachmentService(
        chatStore = chats,
        attachmentDao = attachmentDao,
        imageImporter = ImageImporter(
            targetContext().contentResolver,
            ImageInspector(),
            files,
        ),
        files = files,
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (error: Throwable) {
            return error
        }
        fail("expected operation to fail")
        throw AssertionError("expected operation to fail")
    }

    private fun assertAttachmentFailure(expected: AttachmentFailure, failure: Throwable) {
        assertTrue("expected AttachmentException, got $failure", failure is AttachmentException)
        assertEquals(expected, (failure as AttachmentException).kind)
    }

    private fun targetContext(): Context = ApplicationProvider.getApplicationContext()

    private class FaultInjectingAttachmentFiles(
        private val delegate: AttachmentFiles,
    ) : AttachmentFiles {
        var failWrites = 0
        val failRemoves = mutableSetOf<String>()

        override fun initialise(referencedIds: Set<String>) = delegate.initialise(referencedIds)

        override fun write(id: String, plaintext: ByteArray): Long {
            if (failWrites > 0) {
                failWrites--
                throw IOException("injected import write failure")
            }
            return delegate.write(id, plaintext)
        }

        override fun <T> withPlaintext(id: String, block: (ByteArray) -> T): T =
            delegate.withPlaintext(id, block)

        override fun remove(id: String) {
            if (id in failRemoves) throw IOException("injected cleanup failure")
            delegate.remove(id)
        }
    }

    private class FailingBeginChatStore(
        private val delegate: com.commandcode.chat.ChatStore,
    ) : com.commandcode.chat.ChatStore {
        var failBegins = 0

        override fun observeConversations(): Flow<List<Conversation>> = delegate.observeConversations()

        override fun observeMessages(conversationId: String): Flow<List<Message>> =
            delegate.observeMessages(conversationId)

        override suspend fun messagesSnapshot(conversationId: String): List<Message> =
            delegate.messagesSnapshot(conversationId)

        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
            systemPrompt: String,
        ): PendingTurn {
            if (failBegins > 0) {
                failBegins--
                throw IOException("injected commit failure")
            }
            return delegate.beginTurn(conversationId, text, model, attachments, systemPrompt)
        }

        override suspend fun checkpointAssistant(messageId: String, text: String) =
            delegate.checkpointAssistant(messageId, text)

        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean =
            delegate.completeTurn(messageId, text, usage)

        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String) =
            delegate.interruptTurn(messageId, partialText, reason)

        override suspend fun deleteConversation(id: String) = delegate.deleteConversation(id)
    }

    private class ReadGateAttachmentDao(
        private val delegate: AttachmentDao,
    ) : AttachmentDao {
        var failure: Throwable? = null

        override fun listIds(): List<String> {
            failure?.let { throw it }
            return delegate.listIds()
        }

        override fun listForConversation(conversationId: String): List<com.commandcode.chat.data.database.AttachmentEntity> {
            failure?.let { throw it }
            return delegate.listForConversation(conversationId)
        }

        override fun countForConversation(conversationId: String): Int {
            failure?.let { throw it }
            return delegate.countForConversation(conversationId)
        }

        override fun insert(values: List<com.commandcode.chat.data.database.AttachmentEntity>) {
            failure?.let { throw it }
            delegate.insert(values)
        }
    }
}
