package com.commandcode.chat.ui.chat

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.commandcode.chat.R
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.domain.ChatModel
import com.commandcode.chat.domain.formatStorageBytes
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.ui.AppUiState

internal fun modelOptionTestTag(apiId: String): String = buildString {
    append("model_option_")
    apiId.forEach { character ->
        append(if (character.isLetterOrDigit()) character else '_')
    }
}

internal enum class MessageSide { START, END }

internal fun messageSide(role: String): MessageSide =
    if (role == "USER") MessageSide.END else MessageSide.START

internal fun messageContainerColour(role: String, colours: ColorScheme): Color =
    if (messageSide(role) == MessageSide.END) colours.secondaryContainer else colours.primaryContainer

internal fun messageContentColour(role: String, colours: ColorScheme): Color =
    if (messageSide(role) == MessageSide.END) colours.onSecondaryContainer else colours.onPrimaryContainer

internal fun chatHeading(state: AppUiState): String {
    val conversationId = state.currentConversationId ?: return "New Chat"
    return state.conversations.firstOrNull { it.id == conversationId }?.title ?: "New Chat"
}

internal fun sendButtonEnabled(state: AppUiState): Boolean =
    !state.sending &&
        !state.importingAttachments &&
        (state.draftText.isNotBlank() || state.pendingAttachments.isNotEmpty())

internal fun safeAttachmentDisplayName(value: String): String {
    val cleaned = buildString(value.length) {
        value.forEach { character ->
            if (Character.getType(character) != Character.CONTROL.toInt()) append(character)
        }
    }.trim()
    return cleaned.ifEmpty { "Image" }
}

@Composable
fun ChatScreen(
    state: AppUiState,
    onSelectModel: (ChatModel) -> Unit,
    onNewChat: () -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissAttachmentError: () -> Unit,
    loadAttachmentPreview: suspend (String, Int) -> Bitmap,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val messages = rememberLazyListState()
    var followResponse by remember(state.currentConversationId) { mutableStateOf(true) }
    val responseScroll = remember(messages, state.currentConversationId) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) followResponse = !messages.canScrollBackward
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(state.currentConversationId, state.visibleMessages.size, state.visibleStreamingText, state.visibleThinking) {
        if (followResponse) messages.scrollToItem(0)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val supportHeight = (maxHeight * 0.4f).coerceAtMost(240.dp)
    val composerLines = if (maxHeight < 320.dp) 2 else 4
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chatHeading(state),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedIconButton(
                        onClick = { menuOpen = true },
                        enabled = !state.sending && !state.importingAttachments,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("model_selector")
                            .semantics {
                                contentDescription =
                                    "Select chat model. Current: ${state.selectedModel.displayName}"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_brain),
                            contentDescription = null,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.testTag("model_menu"),
                    ) {
                        state.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = { onSelectModel(model); menuOpen = false },
                                modifier = Modifier
                                    .testTag(modelOptionTestTag(model.apiId))
                                    .semantics { contentDescription = "Use ${model.displayName}" },
                            )
                        }
                    }
                }
                OutlinedIconButton(
                    onClick = {
                        onNewChat()
                    },
                    enabled = !state.sending,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("new_chat")
                        .semantics { contentDescription = "New chat" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_square),
                        contentDescription = null,
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_messages").nestedScroll(responseScroll),
            state = messages,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
        ) {
            if (state.visibleThinking) {
                item(key = "thinking") { ThinkingIndicator() }
            }
            state.visibleStreamingText?.let { liveText ->
                item(key = "live_response") {
                    MessageRow(
                        message = Message(
                            id = "active_assistant_response",
                            conversationId = state.activeConversationId.orEmpty(),
                            role = "ASSISTANT",
                            content = liveText,
                            modelId = null,
                            createdAt = Long.MAX_VALUE,
                            status = "STREAMING",
                        ),
                        loadAttachmentPreview = loadAttachmentPreview,
                        modifier = Modifier.testTag("active_assistant_response"),
                    )
                }
            }
            items(state.visibleMessages.asReversed(), key = Message::id) { message ->
                MessageRow(
                    message = message,
                    loadAttachmentPreview = loadAttachmentPreview,
                    modifier = Modifier.testTag("message_${message.id}"),
                )
            }
        }
        if (state.pendingAttachments.isNotEmpty() || state.attachmentError != null || state.errorMessage != null) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = supportHeight).testTag("pending_attachments"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.attachmentError?.let { failure ->
                    item(key = "attachment_error") { AttachmentErrorBox(failure, onDismissAttachmentError) }
                }
                state.errorMessage?.let { errorMessage ->
                    val typedMessage = state.attachmentError?.let { attachmentErrorCopy(it).primary }
                    if (typedMessage == null || errorMessage != typedMessage) {
                        item(key = "send_error") { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                    }
                }
                items(state.pendingAttachments, key = ImageAttachment::id) { image ->
                    PendingAttachmentRow(
                        image = image,
                        enabled = !state.sending && !state.importingAttachments,
                        onRemove = onRemoveAttachment,
                        loadAttachmentPreview = loadAttachmentPreview,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.draftText,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f).testTag("message_composer"),
                label = { Text("Message") },
                maxLines = composerLines,
                enabled = !state.sending && !state.importingAttachments,
            )
            OutlinedIconButton(
                onClick = onAttach,
                enabled = !state.sending && !state.importingAttachments,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("attach_image")
                    .semantics { contentDescription = "Attach image" },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_paperclip),
                    contentDescription = null,
                )
            }
            if (state.sending) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("cancel_send")) { Text("Cancel") }
            } else {
                Button(
                    onClick = { onSend(state.draftText) },
                    enabled = sendButtonEnabled(state),
                    modifier = Modifier.testTag("send_message"),
                ) { Text("Send") }
            }
        }
    }
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "Thinking")
    Row(
        modifier = Modifier.padding(12.dp).testTag("thinking_indicator")
            .semantics { contentDescription = "Thinking" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(450, delayMillis = index * 150), RepeatMode.Reverse),
                label = "Thinking block $index",
            )
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
        }
        Text("Thinking", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun MessageRow(
    message: Message,
    loadAttachmentPreview: suspend (String, Int) -> Bitmap,
    modifier: Modifier = Modifier,
) {
    val isUser = messageSide(message.role) == MessageSide.END
    val colours = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = messageContainerColour(message.role, colours),
            contentColor = messageContentColour(message.role, colours),
            tonalElevation = if (isUser) 1.dp else 3.dp,
            modifier = modifier
                .fillMaxWidth(0.86f)
                .semantics { contentDescription = if (isUser) "User message" else "Assistant message" },
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.content.isNotEmpty()) Text(message.content)
                message.attachments.forEach { image ->
                    SavedAttachmentRow(image, loadAttachmentPreview)
                }
            }
        }
    }
}

@Composable
private fun PendingAttachmentRow(
    image: ImageAttachment,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    loadAttachmentPreview: suspend (String, Int) -> Bitmap,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_attachment_${image.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AttachmentPreview(
            image = image,
            maxEdge = THUMBNAIL_MAX_EDGE,
            load = loadAttachmentPreview,
            modifier = Modifier.size(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = safeAttachmentDisplayName(image.displayName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatStorageBytes(image.originalBytes), style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(
            onClick = { onRemove(image.id) },
            enabled = enabled,
            modifier = Modifier
                .testTag("remove_attachment_${image.id}")
                .semantics { contentDescription = "Remove ${safeAttachmentDisplayName(image.displayName)}" },
        ) { Text("Remove") }
    }
}

@Composable
private fun SavedAttachmentRow(
    image: ImageAttachment,
    loadAttachmentPreview: suspend (String, Int) -> Bitmap,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_attachment_${image.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttachmentPreview(
            image = image,
            maxEdge = THUMBNAIL_MAX_EDGE,
            load = loadAttachmentPreview,
            modifier = Modifier.size(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = safeAttachmentDisplayName(image.displayName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatStorageBytes(image.originalBytes), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val THUMBNAIL_MAX_EDGE = 256
