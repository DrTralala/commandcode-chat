package com.commandcode.chat.ui.chat

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.commandcode.chat.R
import com.commandcode.chat.data.database.BranchChoice
import com.commandcode.chat.data.database.Message
import com.commandcode.chat.domain.ImageAttachment
import com.commandcode.chat.domain.MessageState
import com.commandcode.chat.domain.formatStorageBytes
import com.commandcode.chat.ui.MessageEdit

private const val USER_ROLE = "USER"
private const val ASSISTANT_ROLE = "ASSISTANT"
private const val COPY_ACTION = "Copy"
private const val SELECT_TEXT_ACTION = "Select text"
private const val THUMBNAIL_MAX_EDGE = 256

internal fun messageMenuActions(role: String): List<String> = when (role) {
    USER_ROLE -> listOf("Edit message", COPY_ACTION)
    ASSISTANT_ROLE -> listOf(COPY_ACTION, "Retry")
    else -> emptyList()
}

internal fun messageMenuActionEnabled(action: String, content: String): Boolean =
    (action != COPY_ACTION && action != SELECT_TEXT_ACTION) || content.isNotEmpty()

internal fun branchCountLabel(choice: BranchChoice): String =
    "${choice.selectedIndex + 1}/${choice.messageIds.size}"

internal fun branchEndpointEnabled(
    choice: BranchChoice,
    previous: Boolean,
    actionsEnabled: Boolean,
): Boolean = actionsEnabled && (if (previous) choice.previousId else choice.nextId) != null

private fun isConversationalMessage(message: Message): Boolean =
    message.role == USER_ROLE || message.role == ASSISTANT_ROLE

internal data class MessageSelectionKey(
    val messageId: String,
    val conversationId: String,
    val content: String,
    val branchChoice: BranchChoice?,
    val responseBranchChoice: BranchChoice?,
)

internal class MessageSelectionOwner {
    var activeKey: MessageSelectionKey? by mutableStateOf(null)

    fun select(key: MessageSelectionKey) {
        activeKey = key
    }

    fun clear(key: MessageSelectionKey? = null) {
        if (key == null || activeKey == key) activeKey = null
    }
}

@Composable
internal fun rememberMessageSelectionOwner(): MessageSelectionOwner = remember { MessageSelectionOwner() }

private fun Message.selectionKey(): MessageSelectionKey = MessageSelectionKey(
    messageId = id,
    conversationId = conversationId,
    content = content,
    branchChoice = branchChoice,
    responseBranchChoice = responseBranchChoice,
)

@Composable
internal fun MessageRow(
    message: Message,
    loadAttachmentPreview: suspend (String, Int) -> Bitmap,
    selectionOwner: MessageSelectionOwner,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = false,
    editorEnabled: Boolean = true,
    editEnabled: Boolean = false,
    retryEnabled: Boolean = false,
    failedActionsEnabled: Boolean = false,
    branchActionsEnabled: Boolean = false,
    messageEdit: MessageEdit? = null,
    onEditChange: (String) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onSubmitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSelectAlternative: (String) -> Unit = {},
) {
    val isUser = messageSide(message.role) == MessageSide.END
    val failed = isUser && message.status == MessageState.FAILED_ZDR
    val editing = messageEdit?.messageId == message.id
    val menuActions = messageMenuActions(message.role).let { actions ->
        if (actions.isEmpty()) actions else actions + SELECT_TEXT_ACTION
    }
    val clipboard = LocalClipboardManager.current
    val selectionKey = message.selectionKey()
    val selectingText = selectionOwner.activeKey == selectionKey
    var actionsOpen by remember(message.id) { mutableStateOf(false) }
    var failureActionsOpen by remember(message.id) { mutableStateOf(false) }
    val colours = MaterialTheme.colorScheme

    DisposableEffect(selectionKey) {
        onDispose { selectionOwner.clear(selectionKey) }
    }

    LaunchedEffect(
        message.id,
        message.conversationId,
        message.status,
        message.branchChoice,
        message.responseBranchChoice,
        messageEdit,
        actionsEnabled,
        editorEnabled,
        editEnabled,
        retryEnabled,
        failedActionsEnabled,
        branchActionsEnabled,
    ) {
        actionsOpen = false
        failureActionsOpen = false
        selectionOwner.clear(selectionKey)
    }

    BackHandler(enabled = selectingText) { selectionOwner.clear(selectionKey) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (failed) 0.8f else 0.86f)
                .then(modifier)
                .semantics {
                    contentDescription = if (isUser) "User message" else "Assistant message"
                },
        ) {
            Column {
                Surface(
                    color = messageContainerColour(message.role, colours),
                    contentColor = messageContentColour(message.role, colours),
                    tonalElevation = if (isUser) 1.dp else 3.dp,
                    modifier = Modifier
                        .testTag("message_text_${message.id}")
                        .fillMaxWidth()
                        .then(
                            if (isConversationalMessage(message) && actionsEnabled && !selectingText) {
                                Modifier.combinedClickable(
                                    enabled = true,
                                    onClick = {},
                                    onLongClickLabel = "Message actions",
                                        onLongClick = {
                                            selectionOwner.clear()
                                            actionsOpen = true
                                        },
                                )
                            } else {
                                Modifier
                            },
                        )
                        .semantics {
                            contentDescription = if (isUser) "User message" else "Assistant message"
                        },
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (editing) {
                            MessageEditor(
                                message = message,
                                edit = requireNotNull(messageEdit),
                                enabled = editorEnabled,
                                loadAttachmentPreview = loadAttachmentPreview,
                                onChange = { selectionOwner.clear(); onEditChange(it) },
                                onSubmit = { selectionOwner.clear(); onSubmitEdit() },
                                onCancel = { selectionOwner.clear(); onCancelEdit() },
                            )
                        } else {
                            if (message.content.isNotEmpty()) {
                                if (selectingText) {
                                    SelectionContainer(
                                        modifier = Modifier.testTag("message_selection_${message.id}"),
                                    ) {
                                        Text(
                                            message.content,
                                            modifier = Modifier.testTag("message_selectable_text_${message.id}"),
                                        )
                                    }
                                } else {
                                    Text(
                                        message.content,
                                        modifier = Modifier.testTag("message_text_${message.id}"),
                                    )
                                }
                            }
                            message.attachments.forEach { image ->
                                SavedAttachmentRow(image, loadAttachmentPreview)
                            }
                        }
                    }
                }

                message.branchChoice?.let { choice ->
                    BranchSelector(
                        choice = choice,
                        selectorId = message.id,
                        actionsEnabled = branchActionsEnabled,
                        onSelectAlternative = {
                            selectionOwner.clear()
                            onSelectAlternative(it)
                        },
                    )
                }
                message.responseBranchChoice?.let { choice ->
                    val selectedResponseId = choice.messageIds[choice.selectedIndex]
                    BranchSelector(
                        choice = choice,
                        selectorId = selectedResponseId,
                        actionsEnabled = branchActionsEnabled,
                        onSelectAlternative = {
                            selectionOwner.clear()
                            onSelectAlternative(it)
                        },
                    )
                }
            }

            if (menuActions.isNotEmpty()) {
                DropdownMenu(
                    expanded = actionsOpen,
                    onDismissRequest = { actionsOpen = false },
                ) {
                    menuActions.forEach { action ->
                        val actionEnabled = when (action) {
                            "Edit message" -> editEnabled
                            "Retry" -> retryEnabled
                            else -> actionsEnabled
                        }
                        val enabled = actionEnabled && messageMenuActionEnabled(action, message.content)
                        DropdownMenuItem(
                            text = { Text(action) },
                            onClick = {
                                actionsOpen = false
                                when (action) {
                                    "Edit message" -> {
                                        selectionOwner.clear()
                                        onStartEdit()
                                    }
                                    COPY_ACTION -> {
                                        selectionOwner.clear()
                                        clipboard.setText(AnnotatedString(message.content))
                                    }
                                    "Retry" -> {
                                        selectionOwner.clear()
                                        onRetry()
                                    }
                                    SELECT_TEXT_ACTION -> selectionOwner.select(selectionKey)
                                }
                            },
                            enabled = enabled,
                            modifier = Modifier.testTag(messageMenuTag(action, message.id)),
                        )
                    }
                }
            }
        }
        if (failed) {
            Box {
                IconButton(
                    onClick = {
                        selectionOwner.clear()
                        failureActionsOpen = true
                    },
                    enabled = failedActionsEnabled,
                    modifier = Modifier.testTag("failed_message_${message.id}"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_message_failed),
                        "Message not sent. No ZDR-capable provider is available. Message actions",
                        tint = Color(0xFFEF4444),
                    )
                }
                DropdownMenu(
                    expanded = failureActionsOpen,
                    onDismissRequest = { failureActionsOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Retry") },
                        onClick = {
                            failureActionsOpen = false
                            selectionOwner.clear()
                            onRetry()
                        },
                        enabled = failedActionsEnabled,
                        modifier = Modifier.testTag("failed_message_retry_${message.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            failureActionsOpen = false
                            selectionOwner.clear()
                            onDelete()
                        },
                        enabled = failedActionsEnabled,
                        modifier = Modifier.testTag("failed_message_delete_${message.id}"),
                    )
                }
            }
        }
    }
}

private fun messageMenuTag(action: String, messageId: String): String = when (action) {
    "Edit message" -> "message_menu_edit_$messageId"
    COPY_ACTION -> "message_menu_copy_$messageId"
    "Retry" -> "message_menu_retry_$messageId"
    else -> "message_menu_${action.lowercase().replace(' ', '_')}_$messageId"
}

@Composable
private fun MessageEditor(
    message: Message,
    edit: MessageEdit,
    enabled: Boolean,
    loadAttachmentPreview: suspend (String, Int) -> Bitmap,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = edit.text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().testTag("message_editor_${message.id}"),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 8,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    innerTextField()
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSubmit,
                enabled = enabled && (edit.text.isNotBlank() || message.attachments.isNotEmpty()),
                modifier = Modifier.testTag("message_edit_save_${message.id}"),
            ) { Text("Save & send") }
            OutlinedButton(
                onClick = onCancel,
                enabled = enabled,
                modifier = Modifier.testTag("message_edit_cancel_${message.id}"),
            ) { Text("Cancel") }
        }
        message.attachments.forEach { image ->
            SavedAttachmentRow(image, loadAttachmentPreview)
        }
    }
}

@Composable
private fun BranchSelector(
    choice: BranchChoice,
    selectorId: String,
    actionsEnabled: Boolean,
    onSelectAlternative: (String) -> Unit,
) {
    if (choice.messageIds.size <= 1) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(
            onClick = { choice.previousId?.let(onSelectAlternative) },
            enabled = branchEndpointEnabled(choice, previous = true, actionsEnabled),
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("branch_previous_$selectorId")
                .semantics { contentDescription = "Previous message alternative" },
        ) { Text("<") }
        Text(
            branchCountLabel(choice),
            modifier = Modifier.testTag("branch_count_$selectorId"),
        )
        TextButton(
            onClick = { choice.nextId?.let(onSelectAlternative) },
            enabled = branchEndpointEnabled(choice, previous = false, actionsEnabled),
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("branch_next_$selectorId")
                .semantics { contentDescription = "Next message alternative" },
        ) { Text(">") }
    }
}

@Composable
internal fun PendingAttachmentRow(
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
    load: suspend (String, Int) -> Bitmap,
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
            load = load,
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
