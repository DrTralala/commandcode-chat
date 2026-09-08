package com.commandcode.chat.data.attachments

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.data.database.ChatRepository
import com.commandcode.chat.data.database.DatabaseChatStore
import com.commandcode.chat.data.database.EncryptedTestEnvironment
import com.commandcode.chat.domain.ChatModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ImageRecoveryInstrumentedTest {
    @Test fun generatedWebpWithJpegMetadataImportsRetriesAndDeletesEncryptedCopy() {
        exercise(AndroidImageTestFixtures.all().last().copy(mimeType = "image/jpeg", displayName = "mislabelled.jpg"))
    }

    /** Opt-in local reproduction; never transmits the user's supplied image. */
    @Test fun suppliedMislabelledImageImportsAndPreviewsLocally() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "recovery-image.jpg")
        assumeTrue("Reproduction image not supplied", file.isFile)
        val bytes = file.readBytes()
        try {
            exercise(AndroidImageTestFixtures.Format("reproduction", "image/jpeg", "mislabelled.jpg", bytes))
        } finally {
            bytes.fill(0)
            check(file.delete())
        }
    }

    private fun exercise(format: AndroidImageTestFixtures.Format) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = EncryptedTestEnvironment(context)
        val database = environment.openDatabase()
        try {
            val repository = ChatRepository(database)
            val files = environment.newAttachmentStore()
            val service = AttachmentService(DatabaseChatStore(repository), database.attachments(),
                ImageImporter(context.contentResolver, ImageInspector(), files), files)
            service.initialise()
            val source = OwnedImageSources.publish(format)
            val image = service.importImage(source.uri, ChatModel.SOL.apiId)
            assertEquals("image/webp", image.mimeType)
            assertEquals(format.bytes.size.toLong(), image.originalBytes)
            val preview = service.preview(image.id, 128)
            assertTrue(preview.width in 1..128 && preview.height in 1..128)
            preview.recycle()
            val first = service.prepareTurn(null, "inspect image", ChatModel.SOL, listOf(image))
            repository.failZdrTurn(first.pending.assistantMessageId)
            val later = service.prepareTurn(first.pending.conversationId, "later text", ChatModel.SOL, emptyList())
            repository.completeTurn(later.pending.assistantMessageId, "later answer", null)
            val retry = service.prepareRetry(first.pending.conversationId, first.pending.userMessageId, ChatModel.LUNA)
            assertEquals(listOf("inspect image"), retry.messages.map { it.content })
            assertEquals(1, retry.messages.single().images.size)
            assertEquals(first.pending.userMessageId, retry.pending.userMessageId)
            repository.failZdrTurn(retry.pending.assistantMessageId)
            service.deleteFailedMessage(first.pending.conversationId, first.pending.userMessageId)
            assertFalse(File(environment.attachmentRoot, image.id).exists())
            assertEquals(listOf(later.pending.userMessageId, later.pending.assistantMessageId),
                repository.messagesSnapshot(first.pending.conversationId).map { it.id })
        } finally {
            database.close()
            OwnedImageSources.clear()
            environment.cleanUp()
        }
    }
}
