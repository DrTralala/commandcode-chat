package com.commandcode.chat.data.attachments

import android.app.Application
import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.ChatStore
import com.commandcode.chat.data.commandcode.CommandCodeRequestFactory
import com.commandcode.chat.data.database.AlternativeRequest
import com.commandcode.chat.data.database.AlternativeSource
import com.commandcode.chat.data.database.AttachmentDao
import com.commandcode.chat.data.database.AttachmentEntity
import com.commandcode.chat.data.database.ChatDatabase
import com.commandcode.chat.data.database.ChatRepository
import com.commandcode.chat.data.database.Conversation
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.data.database.PendingTurn
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.ApiFamily
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
import kotlinx.coroutines.flow.first
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
    private val databases = mutableListOf<ChatDatabase>()
    private var providerNumber = 0

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
        ShadowContentResolver.reset()
    }

    @Test
    fun retryUsesPrefixAndOriginalCommittedImageOnce() = runTest {
        val service = service()
        val image = importPng(service)
        val first = service.prepareTurn(null, "failed", ChatModel.SOL, listOf(image))
        chats.failZdrTurn(first.pending.assistantMessageId)
        service.prepareTurn(first.pending.conversationId, "later", ChatModel.SOL, emptyList())
        val retry = service.prepareRetry(first.pending.conversationId, first.pending.userMessageId, ChatModel.LUNA)
        assertEquals(listOf("failed"), retry.messages.map { it.content })
        assertEquals(1, retry.messages.single().images.size)
        assertEquals(first.pending.userMessageId, retry.pending.userMessageId)
        assertEquals(setOf(image.id), dao.rows.keys)
        val output = CloseTrackingOutputStream()
        retry.messages.single().images.single().writeBase64(output)
        assertTrue(output.size() > 0)
        service.discard(listOf(image))
        assertTrue(files.stored.containsKey(image.id))
    }

    @Test
    fun ordinarySendExcludesFailedImagesAndLocalEvents() = runTest {
        val service = service()
        val image = importPng(service)
        val first = service.prepareTurn(null, "failed", ChatModel.SOL, listOf(image))
        chats.failZdrTurn(first.pending.assistantMessageId)
        chats.changeConversationModel(first.pending.conversationId, ChatModel.LUNA)
        files.missing += image.id
        val next = service.prepareTurn(first.pending.conversationId, "new", ChatModel.LUNA, emptyList())
        assertEquals(listOf("new"), next.messages.map { it.content })
    }

    @Test
    fun configuredSystemPromptLeadsTextAndImageRequestsAndEmptyOrBlankOmitsIt() = runTest {
        val service = service()
        val image = importPng(service)

        val imageTurn = service.prepareTurn(null, "look", ChatModel.SOL, listOf(image), systemPrompt = "be kind")
        assertEquals(listOf("system", "user"), imageTurn.messages.map { it.role })
        assertEquals("be kind", imageTurn.messages.first().content)
        assertTrue(imageTurn.messages.first().images.isEmpty())
        assertEquals(1, imageTurn.messages.last().images.size)

        val textTurn = service.prepareTurn(imageTurn.pending.conversationId, "again", ChatModel.SOL, emptyList(), systemPrompt = "be kind")
        assertEquals(listOf("system", "user", "user"), textTurn.messages.map { it.role })
        assertEquals("be kind", textTurn.messages.first().content)
        assertEquals("again", textTurn.messages.last().content)

        val emptyPrompt = service.prepareTurn(textTurn.pending.conversationId, "cleared", ChatModel.SOL, emptyList(), systemPrompt = "")
        assertTrue(emptyPrompt.messages.none { it.role == "system" })
        assertEquals("cleared", emptyPrompt.messages.last().content)

        val blankPrompt = service.prepareTurn(emptyPrompt.pending.conversationId, "blank", ChatModel.SOL, emptyList(), systemPrompt = "  \n\t ")
        assertTrue(blankPrompt.messages.none { it.role == "system" })
        assertEquals("blank", blankPrompt.messages.last().content)
    }

    @Test
    fun localTimelineNoticesNeverEnterNormalSendsOrRetries() = runTest {
        val service = service()
        val first = service.prepareTurn(null, "first", ChatModel.SOL, emptyList(), systemPrompt = "be kind")
        chats.seedMessage(first.pending.conversationId, "INITIAL_MODEL", "GPT-5.6 Sol", ChatModel.SOL.apiId)
        chats.seedMessage(first.pending.conversationId, "MODEL_CHANGE", "GPT-5.6 Luna", ChatModel.LUNA.apiId)
        chats.seedMessage(first.pending.conversationId, "SYSTEM_PROMPT_CHANGE", "fingerprint-content", null)

        val next = service.prepareTurn(first.pending.conversationId, "second", ChatModel.SOL, emptyList(), systemPrompt = "be kind")
        assertEquals(listOf("system", "user", "user"), next.messages.map { it.role })
        assertEquals(listOf("be kind", "first", "second"), next.messages.map { it.content })
        assertTrue(next.messages.none { it.content.contains("Luna") || it.content.contains("fingerprint") })

        val failed = service.prepareTurn(null, "setup", ChatModel.SOL, emptyList())
        chats.seedMessage(failed.pending.conversationId, "INITIAL_MODEL", "GPT-5.6 Sol", ChatModel.SOL.apiId)
        chats.seedMessage(failed.pending.conversationId, "MODEL_CHANGE", "GPT-5.6 Luna", ChatModel.LUNA.apiId)
        chats.seedMessage(failed.pending.conversationId, "SYSTEM_PROMPT_CHANGE", "fingerprint-content", null)
        val later = service.prepareTurn(failed.pending.conversationId, "failed", ChatModel.SOL, emptyList())
        chats.failZdrTurn(later.pending.assistantMessageId)
        val retry = service.prepareRetry(failed.pending.conversationId, later.pending.userMessageId, ChatModel.LUNA, systemPrompt = "be kind")
        assertEquals(listOf("system", "user", "user"), retry.messages.map { it.role })
        assertEquals(listOf("be kind", "setup", "failed"), retry.messages.map { it.content })
        assertTrue(retry.messages.none { it.content.contains("Luna") || it.content.contains("fingerprint") })
    }

    @Test
    fun alternativeEditCapturesOnlySelectedPrefixAndLeavesPendingImport() = runTest {
        val service = service()
        val pending = importPng(service)
        val ancestorImage = storedImage("edit-ancestor-image", bytes = byteArrayOf(1, 2, 3, 4))
        val targetImage = storedImage("edit-target-image", bytes = byteArrayOf(5, 6, 7, 8))
        val laterImage = storedImage("edit-later-image")
        val siblingImage = storedImage("edit-sibling-image", mime = "image/gif")
        val ancestor = Message(
            id = "edit-ancestor-user",
            conversationId = "c",
            role = "USER",
            content = "ancestor",
            modelId = null,
            createdAt = 1,
            status = "COMPLETE",
            attachments = listOf(ancestorImage),
        )
        val ancestorReply = Message("edit-ancestor-reply", "c", "ASSISTANT", "ancestor answer", ChatModel.SOL.apiId, 2, "COMPLETE")
        val notice = Message("edit-notice", "c", "SYSTEM_PROMPT_CHANGE", "fingerprint", null, 3, "COMPLETE")
        val target = Message(
            id = "edit-target-user",
            conversationId = "c",
            role = "USER",
            content = "original user",
            modelId = null,
            createdAt = 4,
            status = "COMPLETE",
            attachments = listOf(targetImage),
        )
        val oldResponse = Message(
            id = "edit-old-response",
            conversationId = "c",
            role = "ASSISTANT",
            content = "original assistant",
            modelId = ChatModel.SOL.apiId,
            createdAt = 5,
            status = "COMPLETE",
            replyToMessageId = target.id,
        )
        val later = Message(
            id = "edit-later-user",
            conversationId = "c",
            role = "USER",
            content = "later",
            modelId = null,
            createdAt = 6,
            status = "COMPLETE",
            attachments = listOf(laterImage),
        )
        val sibling = Message(
            id = "edit-sibling-user",
            conversationId = "c",
            role = "USER",
            content = "sibling",
            modelId = null,
            createdAt = 7,
            status = "COMPLETE",
            attachments = listOf(siblingImage),
        )
        listOf(ancestor, ancestorReply, notice, target, oldResponse, later, sibling).forEach(chats::seed)
        chats.alternativeSourceValue = AlternativeSource(
            target = target,
            prefix = listOf(ancestor, ancestorReply, notice),
            expectedPathIds = listOf("selected-prefix-token", target.id),
        )

        val prepared = service.prepareAlternative(
            "c",
            AlternativeRequest.Edit(target.id, "edited user"),
            ChatModel.SOL,
            "current prompt",
        )

        assertEquals(listOf("system", "user", "assistant", "user"), prepared.messages.map { it.role })
        assertEquals(listOf("current prompt", "ancestor", "ancestor answer", "edited user"), prepared.messages.map { it.content })
        assertEquals(listOf(0, 1, 0, 1), prepared.messages.map { it.images.size })
        assertTrue(prepared.messages.none { message ->
            message.content in setOf("fingerprint", "original user", "original assistant", "later", "sibling")
        })
        val editedImageOutput = ByteArrayOutputStream()
        prepared.messages.last().images.single().writeBase64(editedImageOutput)
        assertEquals(
            Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7, 8)),
            editedImageOutput.toByteArray().toString(Charsets.UTF_8),
        )
        assertEquals(1, chats.alternativeCreateCalls)
        assertEquals(listOf("selected-prefix-token", target.id), chats.lastExpectedPathIds)
        assertTrue(files.stored.containsKey(pending.id))
        assertFalse(dao.rows.containsKey(pending.id))
    }

    @Test
    fun alternativeRetryCapturesOnlyPrefixThroughOriginalUser() = runTest {
        val service = service()
        val pending = importPng(service)
        val ancestorImage = storedImage("retry-ancestor-image")
        val targetImage = storedImage("retry-target-image", bytes = byteArrayOf(9, 8, 7, 6))
        val laterImage = storedImage("retry-later-image")
        val siblingImage = storedImage("retry-sibling-image", mime = "image/gif")
        val ancestor = Message("retry-ancestor-user", "c", "USER", "ancestor", null, 1, "COMPLETE", listOf(ancestorImage))
        val targetUser = Message("retry-target-user", "c", "USER", "original user", null, 2, "COMPLETE", listOf(targetImage))
        val notice = Message("retry-notice", "c", "MODEL_CHANGE", "GPT-5.6 Luna", ChatModel.LUNA.apiId, 3, "COMPLETE")
        val oldResponse = Message(
            id = "retry-old-response",
            conversationId = "c",
            role = "ASSISTANT",
            content = "original assistant",
            modelId = ChatModel.SOL.apiId,
            createdAt = 4,
            status = "COMPLETE",
            replyToMessageId = targetUser.id,
        )
        val later = Message("retry-later-user", "c", "USER", "later", null, 5, "COMPLETE", listOf(laterImage))
        val sibling = Message("retry-sibling-user", "c", "USER", "sibling", null, 6, "COMPLETE", listOf(siblingImage))
        listOf(ancestor, targetUser, notice, oldResponse, later, sibling).forEach(chats::seed)
        chats.alternativeSourceValue = AlternativeSource(
            target = oldResponse,
            prefix = listOf(ancestor, targetUser, notice),
            expectedPathIds = listOf("retry-prefix-token", oldResponse.id),
        )

        val prepared = service.prepareAlternative(
            "c",
            AlternativeRequest.Retry(oldResponse.id),
            ChatModel.SOL,
            "current prompt",
        )

        assertEquals(listOf("system", "user", "user"), prepared.messages.map { it.role })
        assertEquals(listOf("current prompt", "ancestor", "original user"), prepared.messages.map { it.content })
        assertEquals(listOf(0, 1, 1), prepared.messages.map { it.images.size })
        assertTrue(prepared.messages.none { message ->
            message.content in setOf("GPT-5.6 Luna", "original assistant", "later", "sibling")
        })
        val originalImageOutput = ByteArrayOutputStream()
        prepared.messages.last().images.single().writeBase64(originalImageOutput)
        assertEquals(
            Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7, 6)),
            originalImageOutput.toByteArray().toString(Charsets.UTF_8),
        )
        assertEquals(1, chats.alternativeCreateCalls)
        assertEquals(listOf("retry-prefix-token", oldResponse.id), chats.lastExpectedPathIds)
        assertTrue(files.stored.containsKey(pending.id))
        assertFalse(dao.rows.containsKey(pending.id))
    }

    @Test
    fun corruptAncestorBlocksAlternativeBeforeDatabaseCommitAndKeepsDraft() = runTest {
        val service = service()
        val pending = importPng(service)
        val ancestorImage = storedImage("corrupt-ancestor-image")
        val ancestor = Message("corrupt-ancestor", "c", "USER", "ancestor", null, 1, "COMPLETE", listOf(ancestorImage))
        val target = Message("corrupt-target", "c", "USER", "target", null, 2, "COMPLETE")
        chats.seed(ancestor)
        chats.seed(target)
        chats.alternativeSourceValue = AlternativeSource(target, listOf(ancestor), listOf("corrupt-token"))
        files.missing += ancestorImage.id

        val failure = captureFailure {
            service.prepareAlternative("c", AlternativeRequest.Edit(target.id, "edited"), ChatModel.SOL)
        }

        assertAttachmentFailure(AttachmentFailure.INCOMPATIBLE_HISTORY, failure)
        assertEquals(0, chats.alternativeCreateCalls)
        assertTrue(files.stored.containsKey(pending.id))
        assertTrue(dao.rows.containsKey(ancestorImage.id))
    }

    @Test
    fun corruptTargetBlocksAlternativeBeforeDatabaseCommitAndKeepsDraft() = runTest {
        val service = service()
        val pending = importPng(service)
        val targetImage = storedImage("corrupt-target-image")
        val target = Message("corrupt-target", "c", "USER", "target", null, 1, "COMPLETE", listOf(targetImage))
        chats.seed(target)
        chats.alternativeSourceValue = AlternativeSource(target, emptyList(), listOf("corrupt-target-token"))
        files.missing += targetImage.id

        val failure = captureFailure {
            service.prepareAlternative("c", AlternativeRequest.Edit(target.id, "edited"), ChatModel.SOL)
        }

        assertAttachmentFailure(AttachmentFailure.INCOMPATIBLE_HISTORY, failure)
        assertEquals(0, chats.alternativeCreateCalls)
        assertTrue(files.stored.containsKey(pending.id))
        assertTrue(dao.rows.containsKey(targetImage.id))
    }

    @Test
    fun incompatibleModelBlocksAlternativeBeforeDatabaseCommitAndKeepsDraft() = runTest {
        val service = service()
        val pending = importPng(service)
        val targetImage = storedImage("incompatible-model-image")
        val target = Message("incompatible-model-target", "c", "USER", "target", null, 1, "COMPLETE", listOf(targetImage))
        chats.seed(target)
        chats.alternativeSourceValue = AlternativeSource(target, emptyList(), listOf("model-token"))
        val unknownModel = ChatModel("example/retired", "Retired", ChatModel.SOL.apiFamily)

        val failure = captureFailure {
            service.prepareAlternative("c", AlternativeRequest.Edit(target.id, "edited"), unknownModel)
        }

        assertAttachmentFailure(AttachmentFailure.UNVERIFIED_MODEL, failure)
        assertEquals(0, chats.alternativeCreateCalls)
        assertTrue(files.stored.containsKey(pending.id))
        assertTrue(dao.rows.containsKey(targetImage.id))
    }

    @Test
    fun missingAlternativeAttachmentMetadataBlocksBeforeDatabaseCommitAndKeepsDraft() = runTest {
        val service = service()
        val pending = importPng(service)
        val missingImage = image("missing-alternative-image")
        val target = Message("missing-metadata-target", "c", "USER", "target", null, 1, "COMPLETE", listOf(missingImage))
        chats.seed(target)
        chats.alternativeSourceValue = AlternativeSource(target, emptyList(), listOf("metadata-token"))

        val failure = captureFailure {
            service.prepareAlternative("c", AlternativeRequest.Edit(target.id, "edited"), ChatModel.SOL)
        }

        assertAttachmentFailure(AttachmentFailure.INCOMPATIBLE_HISTORY, failure)
        assertEquals(0, chats.alternativeCreateCalls)
        assertTrue(files.stored.containsKey(pending.id))
        assertFalse(dao.rows.containsKey(missingImage.id))
    }

    @Test
    fun staleAlternativeCommitFailureLeavesLinksBranchesAndDraftUntouched() = runTest {
        val service = service()
        val pending = importPng(service)
        val targetImage = storedImage("stale-alternative-image")
        val target = Message("stale-target", "c", "USER", "target", null, 1, "COMPLETE", listOf(targetImage))
        chats.seed(target)
        chats.alternativeSourceValue = AlternativeSource(target, emptyList(), listOf("stale-token"))
        chats.alternativeFailure = IOException("alternative commit rejected")
        val beforeRows = dao.rows.mapValues { it.value.copy() }
        val beforeLinks = dao.links.mapValues { it.value.toSet() }
        val beforeStored = files.stored.mapValues { it.value.copyOf() }

        val failure = captureFailure {
            service.prepareAlternative("c", AlternativeRequest.Edit(target.id, "edited"), ChatModel.SOL)
        }

        assertTrue(failure is IOException)
        assertEquals(1, chats.alternativeCreateCalls)
        assertEquals(beforeRows, dao.rows)
        assertEquals(beforeLinks, dao.links.mapValues { it.value.toSet() })
        assertTrue(beforeStored.keys == files.stored.keys)
        beforeStored.forEach { (id, bytes) -> assertTrue(files.stored.getValue(id).contentEquals(bytes)) }
        assertTrue(files.stored.containsKey(pending.id))
    }

    @Test
    fun editingAtPhysicalAttachmentLimitReusesImagesWithoutCountingOrDuplicatingThem() = runTest {
        val service = service()
        val images = (1..20).map { index -> storedImage("limit-image-$index") }
        val target = Message(
            id = "limit-target",
            conversationId = "c",
            role = "USER",
            content = "target",
            modelId = null,
            createdAt = 1,
            status = "COMPLETE",
            attachments = listOf(images.first()),
        )
        chats.seed(target)
        chats.alternativeSourceValue = AlternativeSource(target, emptyList(), listOf("limit-token"))

        val prepared = service.prepareAlternative("c", AlternativeRequest.Edit(target.id, "edited"), ChatModel.SOL)

        assertEquals(listOf("edited"), prepared.messages.map { it.content })
        assertEquals(20, dao.rows.size)
        assertEquals(0, dao.countCalls)
        assertEquals(1, chats.alternativeCreateCalls)
    }

    @Test
    fun deletingFailedAlternativeKeepsSharedEncryptedBlobAndCountsUnchanged() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        val repository = ChatRepository(database)
        val realFiles = EncryptedAttachmentStore(
            temporaryFolder.newFolder("shared-attachment-files"),
            ServiceKeyProvider(),
        )
        val realChats = RepositoryChatStore(repository)
        val realService = AttachmentService(
            chatStore = realChats,
            attachmentDao = database.attachments(),
            imageImporter = ImageImporter(context.contentResolver, ImageInspector(), realFiles),
            files = realFiles,
        )
        realService.initialise()
        val bytes = ImageTestFactory.png()
        val image = realService.importImage(registerProvider(bytes, "shared.png", "image/png"), ChatModel.SOL.apiId)
        val original = realService.prepareTurn(null, "original", ChatModel.SOL, listOf(image))
        repository.completeTurn(original.pending.assistantMessageId, "original answer", null)
        val edited = realService.prepareAlternative(
            original.pending.conversationId,
            AlternativeRequest.Edit(original.pending.userMessageId, "edited"),
            ChatModel.SOL,
        )
        repository.failZdrTurn(edited.pending.assistantMessageId)

        realService.deleteFailedMessage(original.pending.conversationId, edited.pending.userMessageId)

        assertTrue(database.messageAttachments().listForMessage(original.pending.userMessageId).any {
            it.attachmentId == image.id
        })
        assertTrue(database.messageAttachments().listForMessage(edited.pending.userMessageId).isEmpty())
        assertEquals(listOf(image.id), database.attachments().listIds())
        val conversation = repository.observeConversations().first().single()
        assertEquals(1, conversation.attachmentCount)
        assertEquals(image.encryptedBytes, conversation.attachmentBytes)
        var recovered: ByteArray? = null
        realFiles.withPlaintext(image.id) { plaintext -> recovered = plaintext.copyOf() }
        assertTrue(recovered!!.contentEquals(bytes))

        realService.deleteConversation(original.pending.conversationId)
        assertTrue(runCatching { realFiles.withPlaintext(image.id) { } }.isFailure)
    }

    @Test
    fun alternativeEditOfFailedUserRetainsItsAttachmentLinksAndCreatesARecoverableBranch() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        val repository = ChatRepository(database)
        val realFiles = EncryptedAttachmentStore(
            temporaryFolder.newFolder("failed-edit-attachment-files"),
            ServiceKeyProvider(),
        )
        val realChats = RepositoryChatStore(repository)
        val realService = AttachmentService(
            chatStore = realChats,
            attachmentDao = database.attachments(),
            imageImporter = ImageImporter(context.contentResolver, ImageInspector(), realFiles),
            files = realFiles,
        )
        realService.initialise()
        val image = realService.importImage(
            registerProvider(ImageTestFactory.png(), "failed-edit.png", "image/png"),
            ChatModel.SOL.apiId,
        )
        val original = realService.prepareTurn(null, "failed", ChatModel.SOL, listOf(image))
        repository.failZdrTurn(original.pending.assistantMessageId)

        val edited = realService.prepareAlternative(
            original.pending.conversationId,
            AlternativeRequest.Edit(original.pending.userMessageId, "edited"),
            ChatModel.SOL,
        )

        assertEquals(listOf("edited"), edited.messages.map { it.content })
        assertEquals(1, edited.messages.single().images.size)
        assertEquals(
            listOf(image.id),
            database.messageAttachments().listForMessage(original.pending.userMessageId).map { it.attachmentId },
        )
        assertEquals(
            listOf(image.id),
            database.messageAttachments().listForMessage(edited.pending.userMessageId).map { it.attachmentId },
        )
        assertEquals(listOf(image.id), database.attachments().listIds())
    }

    @Test
    fun deletingConversationRetainsCleanupCandidateWhenPostDeleteIdReadFails() = runTest {
        val service = service()
        val image = importPng(service)
        val turn = service.prepareTurn(null, "delete", ChatModel.SOL, listOf(image))
        dao.failListIds = IOException("post-delete id read failed")

        val failure = captureFailure { service.deleteConversation(turn.pending.conversationId) }

        assertTrue(failure is IOException)
        assertTrue(chats.conversations.value.none { it.id == turn.pending.conversationId })
        assertTrue(files.stored.containsKey(image.id))

        dao.failListIds = null
        service.deleteConversation(turn.pending.conversationId)

        assertFalse(files.stored.containsKey(image.id))
    }

    @Test
    fun deletingFailedTurnRetainsCleanupCandidateWhenPostDeleteIdReadFails() = runTest {
        val service = service()
        val image = importPng(service)
        val turn = service.prepareTurn(null, "failed", ChatModel.SOL, listOf(image))
        chats.failZdrTurn(turn.pending.assistantMessageId)
        dao.failListIds = IOException("post-delete id read failed")

        val failure = captureFailure {
            service.deleteFailedMessage(turn.pending.conversationId, turn.pending.userMessageId)
        }

        assertTrue(failure is IOException)
        assertTrue(files.stored.containsKey(image.id))
        assertTrue(dao.rows.isEmpty())

        dao.failListIds = null
        service.initialise()

        assertFalse(files.stored.containsKey(image.id))
    }

    @Test
    fun retryCleanupRefreshesDatabaseIdsBeforeRemovingAReReferencedFile() = runTest {
        val service = service()
        val image = importPng(service)
        val turn = service.prepareTurn(null, "failed", ChatModel.SOL, listOf(image))
        chats.failZdrTurn(turn.pending.assistantMessageId)
        files.removeFailures += image.id

        val failure = captureFailure {
            service.deleteFailedMessage(turn.pending.conversationId, turn.pending.userMessageId)
        }

        assertAttachmentFailure(AttachmentFailure.CLEANUP, failure)
        val row = AttachmentEntity(
            id = image.id,
            conversationId = turn.pending.conversationId,
            displayName = image.displayName,
            mimeType = image.mimeType,
            originalBytes = image.originalBytes,
            encryptedBytes = image.encryptedBytes,
            width = image.width,
            height = image.height,
        )
        dao.rows[image.id] = row
        dao.link("surviving-message", image.id)
        files.removeFailures.clear()

        service.initialise()

        assertTrue(files.stored.containsKey(image.id))
        assertFalse(files.removed.contains(image.id))
    }

    @Test
    fun alternativeSourceLinkSnapshotIsRecheckedBeforeCreatingAnAlternative() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        val repository = ChatRepository(database)
        val realFiles = EncryptedAttachmentStore(
            temporaryFolder.newFolder("source-link-files"),
            ServiceKeyProvider(),
        )
        val realChats = RepositoryChatStore(repository)
        val realService = AttachmentService(
            chatStore = realChats,
            attachmentDao = database.attachments(),
            imageImporter = ImageImporter(context.contentResolver, ImageInspector(), realFiles),
            files = realFiles,
        )
        realService.initialise()
        val original = realService.prepareTurn(null, "original", ChatModel.SOL, emptyList())
        repository.completeTurn(original.pending.assistantMessageId, "answer", null)
        val canonical = repository.alternativeSource(original.pending.conversationId, original.pending.userMessageId)
        val spoofedId = UUID.randomUUID().toString()
        val spoofedBytes = byteArrayOf(4, 3, 2, 1)
        val spoofedImage = ImageAttachment(
            id = spoofedId,
            displayName = "unlinked.png",
            mimeType = "image/png",
            originalBytes = spoofedBytes.size.toLong(),
            encryptedBytes = realFiles.write(spoofedId, spoofedBytes),
            width = 2,
            height = 2,
        )
        database.attachments().insert(
            listOf(
                AttachmentEntity(
                    id = spoofedImage.id,
                    conversationId = original.pending.conversationId,
                    displayName = spoofedImage.displayName,
                    mimeType = spoofedImage.mimeType,
                    originalBytes = spoofedImage.originalBytes,
                    encryptedBytes = spoofedImage.encryptedBytes,
                    width = spoofedImage.width,
                    height = spoofedImage.height,
                ),
            ),
        )
        var sourceCalls = 0
        realChats.alternativeSourceOverride = { _, _ ->
            sourceCalls++
            if (sourceCalls == 1) {
                canonical.copy(target = canonical.target.copy(attachments = listOf(spoofedImage)))
            } else {
                canonical
            }
        }
        val beforeMessages = database.messages().listForConversation(original.pending.conversationId)
        val beforeNodes = database.branches().listNodes(original.pending.conversationId)
        val beforePlacements = database.branches().listPlacements(original.pending.conversationId)

        val failure = captureFailure {
            realService.prepareAlternative(
                original.pending.conversationId,
                AlternativeRequest.Edit(original.pending.userMessageId, "edited"),
                ChatModel.SOL,
            )
        }

        assertTrue(failure is IllegalStateException)
        assertEquals(2, sourceCalls)
        assertEquals(beforeMessages, database.messages().listForConversation(original.pending.conversationId))
        assertEquals(beforeNodes, database.branches().listNodes(original.pending.conversationId))
        assertEquals(beforePlacements, database.branches().listPlacements(original.pending.conversationId))
        assertTrue(database.messageAttachments().listForMessage(original.pending.userMessageId).isEmpty())
        assertTrue(database.attachments().listIds().contains(spoofedImage.id))
        assertTrue(realFiles.withPlaintext(spoofedImage.id) { true })
    }

    @Test
    fun staleAlternativeTokenLeavesRealBranchesLinksAndDraftUntouched() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        val repository = ChatRepository(database)
        val realFiles = EncryptedAttachmentStore(
            temporaryFolder.newFolder("stale-alternative-files"),
            ServiceKeyProvider(),
        )
        val realChats = RepositoryChatStore(repository)
        val realService = AttachmentService(
            chatStore = realChats,
            attachmentDao = database.attachments(),
            imageImporter = ImageImporter(context.contentResolver, ImageInspector(), realFiles),
            files = realFiles,
        )
        realService.initialise()
        val bytes = ImageTestFactory.png()
        val image = realService.importImage(registerProvider(bytes, "stale.png", "image/png"), ChatModel.SOL.apiId)
        val original = realService.prepareTurn(null, "original", ChatModel.SOL, listOf(image))
        repository.completeTurn(original.pending.assistantMessageId, "answer", null)
        val initialSource = repository.alternativeSource(original.pending.conversationId, original.pending.userMessageId)
        val existing = repository.createAlternative(
            original.pending.conversationId,
            AlternativeRequest.Edit(original.pending.userMessageId, "existing alternative"),
            ChatModel.SOL,
            "",
            initialSource.expectedPathIds,
        )
        repository.completeTurn(existing.assistantMessageId, "existing answer", null)
        repository.selectAlternative(original.pending.conversationId, original.pending.userMessageId)
        val pending = realService.importImage(registerProvider(bytes, "pending.png", "image/png"), ChatModel.SOL.apiId)
        val beforeMessages = database.messages().listForConversation(original.pending.conversationId)
        val beforeNodes = database.branches().listNodes(original.pending.conversationId)
        val beforePlacements = database.branches().listPlacements(original.pending.conversationId)
        val beforeLinks = database.messageAttachments().listForMessage(original.pending.userMessageId)
        val beforeUsage = database.usageEvents().observeForConversation(original.pending.conversationId).first()
        realChats.beforeAlternativeCreate = {
            repository.selectAlternative(original.pending.conversationId, existing.userMessageId)
        }

        val failure = captureFailure {
            realService.prepareAlternative(
                original.pending.conversationId,
                AlternativeRequest.Edit(original.pending.userMessageId, "stale edit"),
                ChatModel.SOL,
            )
        }

        assertTrue("stale selection failure: $failure", failure is IllegalArgumentException)
        assertEquals(initialSource.expectedPathIds, realChats.lastExpectedPathIds)
        assertEquals(beforeMessages, database.messages().listForConversation(original.pending.conversationId))
        assertEquals(beforeNodes, database.branches().listNodes(original.pending.conversationId))
        assertEquals(beforePlacements, database.branches().listPlacements(original.pending.conversationId))
        assertEquals(beforeLinks, database.messageAttachments().listForMessage(original.pending.userMessageId))
        assertEquals(beforeUsage, database.usageEvents().observeForConversation(original.pending.conversationId).first())
        assertTrue(realFiles.withPlaintext(pending.id) { true })

        repository.selectAlternative(original.pending.conversationId, original.pending.userMessageId)
        realChats.beforeAlternativeCreate = null
    }

    @Test
    fun realRoomAlternativeAtPhysicalLimitPreservesSharedLinkTotals() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        val repository = ChatRepository(database)
        val realFiles = EncryptedAttachmentStore(
            temporaryFolder.newFolder("limit-alternative-files"),
            ServiceKeyProvider(),
        )
        var nextId = 0
        val realChats = RepositoryChatStore(repository)
        val realService = AttachmentService(
            chatStore = realChats,
            attachmentDao = database.attachments(),
            imageImporter = ImageImporter(context.contentResolver, ImageInspector(), realFiles),
            files = realFiles,
            importImageBlocking = { _, _ ->
                val id = UUID.randomUUID().toString()
                val bytes = byteArrayOf(nextId++.toByte(), 2, 3, 4)
                val encryptedBytes = realFiles.write(id, bytes)
                ImageAttachment(id, "Image.png", "image/png", bytes.size.toLong(), encryptedBytes, 2, 2)
            },
        )
        realService.initialise()
        val images = (1..20).map {
            realService.importImage(Uri.parse("content://fixture/limit-$it"), ChatModel.SOL.apiId)
        }
        val original = realService.prepareTurn(null, "original", ChatModel.SOL, images)
        repository.completeTurn(original.pending.assistantMessageId, "answer", null)
        val before = repository.observeConversations().first().single()
        val beforePhysicalIds = database.attachments().listIds()
        val edited = realService.prepareAlternative(
            original.pending.conversationId,
            AlternativeRequest.Edit(original.pending.userMessageId, "edited"),
            ChatModel.SOL,
        )
        val after = repository.observeConversations().first().single()

        assertEquals(20, before.attachmentCount)
        assertEquals(before.attachmentCount, after.attachmentCount)
        assertEquals(before.attachmentBytes, after.attachmentBytes)
        assertEquals(beforePhysicalIds, database.attachments().listIds())
        assertEquals(20, database.messageAttachments().listForMessage(original.pending.userMessageId).size)
        assertEquals(20, database.messageAttachments().listForMessage(edited.pending.userMessageId).size)
        assertEquals(40, database.messageAttachments().listForMessage(original.pending.userMessageId).size +
            database.messageAttachments().listForMessage(edited.pending.userMessageId).size)
    }

    @Test
    fun failedMessageDeletionDoesNotRemoveFilesUntilDatabaseSucceeds() = runTest {
        val service = service()
        val image = importPng(service)
        val first = service.prepareTurn(null, "failed", ChatModel.SOL, listOf(image))
        chats.failZdrTurn(first.pending.assistantMessageId)
        chats.deleteFailure = IOException("database unavailable")
        assertTrue(captureFailure { service.deleteFailedMessage(first.pending.conversationId, first.pending.userMessageId) } is IOException)
        assertTrue(files.stored.containsKey(image.id))
        chats.deleteFailure = null
        service.deleteFailedMessage(first.pending.conversationId, first.pending.userMessageId)
        assertFalse(files.stored.containsKey(image.id))
        assertTrue(dao.rows.isEmpty())
        assertEquals(1, chats.conversations.value.size)
    }

    @Test
    fun failedMessageFileCleanupRemainsRetryableAfterDatabaseDelete() = runTest {
        val service = service()
        val image = importPng(service)
        val turn = service.prepareTurn(null, "failed", ChatModel.SOL, listOf(image))
        chats.failZdrTurn(turn.pending.assistantMessageId)
        files.removeFailures += image.id

        val failure = captureFailure {
            service.deleteFailedMessage(turn.pending.conversationId, turn.pending.userMessageId)
        }

        assertAttachmentFailure(AttachmentFailure.CLEANUP, failure)
        assertTrue(files.stored.containsKey(image.id))
        assertTrue(dao.rows.isEmpty())

        files.removeFailures.clear()
        service.initialise()

        assertFalse(files.stored.containsKey(image.id))
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
    fun messagesModelCannotSendExistingImageHistoryAndDoesNotDropItsAttachment() = runTest {
        val service = service()
        val image = importPng(service)
        val first = service.prepareTurn(null, "first", ChatModel.SOL, listOf(image))
        val messagesModel = ChatModel("new/messages-model", "New Messages model", ApiFamily.ANTHROPIC_MESSAGES)
        chats.changeConversationModel(first.pending.conversationId, messagesModel)
        val beginCallsBeforeRetry = chats.beginCalls

        val failure = captureFailure {
            service.prepareTurn(first.pending.conversationId, "second", messagesModel, emptyList())
        }

        assertAttachmentFailure(AttachmentFailure.UNVERIFIED_MODEL, failure)
        assertEquals(beginCallsBeforeRetry, chats.beginCalls)
        assertTrue(files.stored.containsKey(image.id))
        assertEquals(
            listOf(image.id),
            chats.messagesSnapshot(first.pending.conversationId).flatMap { it.attachments }.map(ImageAttachment::id),
        )
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

    private fun storedImage(
        id: String,
        conversationId: String = "c",
        mime: String = "image/png",
        bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): ImageAttachment {
        val image = image(id).copy(mimeType = mime, originalBytes = bytes.size.toLong())
        dao.rows[id] = AttachmentEntity(
            id = image.id,
            conversationId = conversationId,
            displayName = image.displayName,
            mimeType = image.mimeType,
            originalBytes = image.originalBytes,
            encryptedBytes = image.encryptedBytes,
            width = image.width,
            height = image.height,
        )
        files.stored[id] = bytes.copyOf()
        return image
    }

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

    private class RepositoryChatStore(
        private val repository: ChatRepository,
    ) : ChatStore {
        var alternativeSourceOverride: (suspend (String, String) -> AlternativeSource)? = null
        var beforeAlternativeCreate: (suspend () -> Unit)? = null
        var lastExpectedPathIds: List<String>? = null

        override fun observeConversations() = repository.observeConversations()

        override fun observeMessages(conversationId: String) = repository.observeMessages(conversationId)

        override suspend fun messagesSnapshot(conversationId: String) = repository.messagesSnapshot(conversationId)

        override suspend fun beginTurn(
            conversationId: String?,
            text: String,
            model: ChatModel,
            attachments: List<ImageAttachment>,
            systemPrompt: String,
        ) = repository.beginTurn(conversationId, text, model, attachments, systemPrompt)

        override suspend fun checkpointAssistant(messageId: String, text: String) =
            repository.checkpointAssistant(messageId, text)

        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?) =
            repository.completeTurn(messageId, text, usage)

        override suspend fun interruptTurn(
            messageId: String,
            partialText: String,
            reason: String,
            usage: TokenUsage?,
        ) = repository.interruptTurn(messageId, partialText, reason, usage)

        override suspend fun deleteConversation(id: String) = repository.deleteConversation(id)

        override suspend fun alternativeSource(conversationId: String, messageId: String) =
            alternativeSourceOverride?.invoke(conversationId, messageId)
                ?: repository.alternativeSource(conversationId, messageId)

        override suspend fun createAlternative(
            conversationId: String,
            request: AlternativeRequest,
            model: ChatModel,
            systemPrompt: String,
            expectedPathIds: List<String>,
        ): PendingTurn {
            lastExpectedPathIds = expectedPathIds
            beforeAlternativeCreate?.invoke()
            return repository.createAlternative(conversationId, request, model, systemPrompt, expectedPathIds)
        }

        override suspend fun selectAlternative(conversationId: String, messageId: String) =
            repository.selectAlternative(conversationId, messageId)

        override suspend fun failZdrTurn(assistantMessageId: String, usage: TokenUsage?) =
            repository.failZdrTurn(assistantMessageId, usage)

        override suspend fun retryFailedTurn(userMessageId: String, model: ChatModel, systemPrompt: String) =
            repository.retryFailedTurn(userMessageId, model, systemPrompt)

        override suspend fun deleteFailedTurn(userMessageId: String) = repository.deleteFailedTurn(userMessageId)

        override suspend fun changeConversationModel(conversationId: String, model: ChatModel) =
            repository.changeConversationModel(conversationId, model)

        override suspend fun backfillInitialModel(conversationId: String) = repository.backfillInitialModel(conversationId)
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
        val links = linkedMapOf<String, MutableSet<String>>()
        var failListIds: Throwable? = null
        var countCalls = 0

        override fun listIds(): List<String> {
            failListIds?.let { throw it }
            return rows.keys.toList()
        }

        override fun listForConversation(conversationId: String): List<AttachmentEntity> =
            rows.values.filter { it.conversationId == conversationId }

        override fun countForConversation(conversationId: String): Int {
            countCalls++
            return listForConversation(conversationId).size
        }

        override fun insert(values: List<AttachmentEntity>) {
            values.forEach { rows[it.id] = it }
        }

        override fun deleteUnreferenced(ids: List<String>): Int = ids.distinct().count { id ->
            if (links.values.any { id in it }) false else rows.remove(id) != null
        }

        fun link(messageId: String, attachmentId: String) {
            links.getOrPut(messageId) { linkedSetOf() } += attachmentId
        }

        fun unlink(messageId: String): Set<String> = links.remove(messageId).orEmpty()
    }

    private class ServiceChatStore(private val dao: ServiceAttachmentDao) : ChatStore {
        val conversations = MutableStateFlow<List<Conversation>>(emptyList())
        private val messages = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        private val messageAttachmentIds = mutableMapOf<String, Set<String>>()
        var alternativeSourceValue: AlternativeSource? = null
        var alternativeFailure: Throwable? = null
        var alternativeCreateCalls = 0
        var lastExpectedPathIds: List<String>? = null
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
            systemPrompt: String,
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
            messageAttachmentIds[userId] = attachments.map { it.id }.toSet()
            dao.insert(attachments.map { image ->
                AttachmentEntity(
                    id = image.id,
                    conversationId = id,
                    displayName = image.displayName,
                    mimeType = image.mimeType,
                    originalBytes = image.originalBytes,
                    encryptedBytes = image.encryptedBytes,
                    width = image.width,
                    height = image.height,
                )
            })
            attachments.forEach { image -> dao.link(userId, image.id) }
            persistedTurns++
            return PendingTurn(id, userId, assistantId)
        }

        override suspend fun alternativeSource(conversationId: String, messageId: String): AlternativeSource =
            alternativeSourceValue?.also {
                check(it.target.conversationId == conversationId)
                check(it.target.id == messageId)
            } ?: error("alternative source was not configured")

        override suspend fun createAlternative(
            conversationId: String,
            request: AlternativeRequest,
            model: ChatModel,
            systemPrompt: String,
            expectedPathIds: List<String>,
        ): PendingTurn {
            lastExpectedPathIds = expectedPathIds
            alternativeCreateCalls++
            alternativeFailure?.let { throw it }
            return PendingTurn(conversationId, "alternative-user", "alternative-assistant")
        }

        override suspend fun checkpointAssistant(messageId: String, text: String) = Unit

        override suspend fun completeTurn(messageId: String, text: String, usage: TokenUsage?): Boolean = true

        override suspend fun interruptTurn(messageId: String, partialText: String, reason: String, usage: TokenUsage?) = Unit

        override suspend fun failZdrTurn(assistantMessageId: String, usage: TokenUsage?) {
            val rows = messages.values.single { list -> list.value.any { it.id == assistantMessageId } }
            val index = rows.value.indexOfFirst { it.id == assistantMessageId }
            rows.value = rows.value.mapIndexed { i, m -> if (i == index || i == index - 1) m.copy(status = "FAILED_ZDR") else m }
        }

        override suspend fun retryFailedTurn(userMessageId: String, model: ChatModel, systemPrompt: String): PendingTurn {
            val rows = messages.values.single { list -> list.value.any { it.id == userMessageId } }
            val index = rows.value.indexOfFirst { it.id == userMessageId }
            val user = rows.value[index]
            check(user.status == "FAILED_ZDR")
            val reply = rows.value[index + 1].copy(id = "retry-$userMessageId", modelId = model.apiId, status = "PENDING")
            rows.value = rows.value.mapIndexed { i, m -> when (i) { index -> m.copy(status = "COMPLETE"); index + 1 -> reply; else -> m } }
            return PendingTurn(user.conversationId, user.id, reply.id)
        }

        override suspend fun deleteFailedTurn(userMessageId: String) {
            deleteFailure?.let { throw it }
            val rows = messages.values.single { list -> list.value.any { it.id == userMessageId } }
            val index = rows.value.indexOfFirst { it.id == userMessageId }
            check(rows.value[index].status == "FAILED_ZDR")
            rows.value = rows.value.filterIndexed { i, _ -> i != index && i != index + 1 }
            val attachmentIds = dao.unlink(userMessageId)
            messageAttachmentIds.remove(userMessageId)
            dao.deleteUnreferenced(attachmentIds.toList())
        }

        override suspend fun changeConversationModel(conversationId: String, model: ChatModel) {
            val rows = messages.getValue(conversationId)
            rows.value += Message("event", conversationId, "MODEL_CHANGE", model.displayName, model.apiId, 99, "COMPLETE")
        }

        fun seed(message: Message) {
            val rows = messages.getOrPut(message.conversationId) { MutableStateFlow(emptyList()) }
            rows.value += message
            val attachmentIds = message.attachments.map { it.id }.toSet()
            messageAttachmentIds[message.id] = attachmentIds
            attachmentIds.forEach { dao.link(message.id, it) }
        }

        fun seedMessage(conversationId: String, role: String, content: String, modelId: String?) {
            val rows = messages.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
            seed(Message("seeded-${rows.value.size}", conversationId, role, content, modelId, rows.value.size.toLong() + 100L, "COMPLETE"))
        }

        override suspend fun deleteConversation(id: String) {
            deleteCalls++
            deleteFailure?.let { throw it }
            conversations.value = conversations.value.filterNot { it.id == id }
            val removedMessages = messages.remove(id)?.value.orEmpty()
            val attachmentIds = removedMessages.flatMap { dao.unlink(it.id) }.toSet()
            dao.deleteUnreferenced(attachmentIds.toList())
        }
    }
}
