package com.commandcode.chat.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.commandcode.chat.domain.AttachmentFailure
import com.commandcode.chat.domain.AttachmentPolicy
import com.commandcode.chat.domain.ImageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.IdentityHashMap

private const val ENLARGED_MAX_EDGE = 2_048
private const val DEFAULT_THUMBNAIL_MAX_EDGE = 256

/**
 * Loads an authenticated image into memory only. The loader owns all disk and
 * decryption work; this composable owns the returned bitmaps until it leaves the
 * composition.
 */
@Composable
fun AttachmentPreview(
    image: ImageAttachment,
    maxEdge: Int,
    load: suspend (String, Int) -> Bitmap,
    modifier: Modifier = Modifier,
) {
    val ownership = remember { BitmapOwnership() }
    var enlarged by remember(image.id) { mutableStateOf(false) }

    DisposableEffect(ownership) {
        onDispose { ownership.dispose() }
    }

    Column(modifier) {
        PreviewBitmap(
            image = image,
            maxEdge = maxEdge.coerceIn(1, ENLARGED_MAX_EDGE),
            load = load,
            ownership = ownership,
            uiMaxEdge = DEFAULT_THUMBNAIL_MAX_EDGE.dp,
            modifier = Modifier
                .testTag("attachment_preview_${image.id}")
                .semantics { contentDescription = "Preview of ${safeAttachmentDisplayName(image.displayName)}" },
            onClick = { enlarged = true },
        )
    }

    if (enlarged) {
        Dialog(onDismissRequest = { enlarged = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .testTag("attachment_preview_dialog"),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = safeAttachmentDisplayName(image.displayName),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() },
                        )
                        TextButton(
                            onClick = { enlarged = false },
                            modifier = Modifier
                                .testTag("close_attachment_preview")
                                .semantics { contentDescription = "Close image preview" },
                        ) { Text("Close") }
                    }
                    PreviewBitmap(
                        image = image,
                        maxEdge = ENLARGED_MAX_EDGE,
                        load = load,
                        ownership = ownership,
                        uiMaxEdge = 512.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 512.dp)
                            .testTag("enlarged_attachment_preview"),
                        onClick = null,
                    )
                }
            }
        }
    }
}

internal data class AttachmentErrorCopy(
    val primary: String,
    val secondary: String? = null,
)

internal fun attachmentErrorCopy(kind: AttachmentFailure): AttachmentErrorCopy = when (kind) {
    AttachmentFailure.UNSUPPORTED_MODEL -> AttachmentErrorCopy(AttachmentPolicy.UNSUPPORTED_MESSAGE)
    AttachmentFailure.UNVERIFIED_MODEL -> AttachmentErrorCopy(
        primary = AttachmentPolicy.UNSUPPORTED_MESSAGE,
        secondary = "Attachment support for this model has not been verified.",
    )
    AttachmentFailure.INVALID_FORMAT -> AttachmentErrorCopy(
        "This image format is not supported. Choose JPEG, PNG, or WebP.",
    )
    AttachmentFailure.CORRUPT_IMAGE -> AttachmentErrorCopy("The image could not be read. Try another image.")
    AttachmentFailure.TOO_LARGE -> AttachmentErrorCopy("This image is too large. The maximum is 10 MB.")
    AttachmentFailure.TOO_MANY -> AttachmentErrorCopy("This chat already has the maximum number of images.")
    AttachmentFailure.EXCESSIVE_DIMENSIONS -> AttachmentErrorCopy("This image exceeds the 8000-pixel dimension limit.")
    AttachmentFailure.ANIMATED_IMAGE -> AttachmentErrorCopy("Animated images are not supported.")
    AttachmentFailure.STORAGE -> AttachmentErrorCopy("Attachment storage is unavailable. Try again.")
    AttachmentFailure.KEY_UNAVAILABLE -> AttachmentErrorCopy("Encrypted attachment storage requires recovery.")
    AttachmentFailure.STALE_SELECTION -> AttachmentErrorCopy("This attachment selection is no longer current. Try again.")
    AttachmentFailure.INCOMPATIBLE_HISTORY -> AttachmentErrorCopy("This chat contains incompatible attachment history.")
    AttachmentFailure.CLEANUP -> AttachmentErrorCopy("Attachment cleanup could not be completed. Try again.")
}

@Composable
fun AttachmentErrorBox(
    kind: AttachmentFailure,
    onDismiss: () -> Unit,
) {
    val copy = attachmentErrorCopy(kind)
    val shape = MaterialTheme.shapes.small
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.error, shape)
            .testTag("attachment_error_box")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Attachment error", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(copy.primary)
                copy.secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .testTag("dismiss_attachment_error")
                    .semantics { contentDescription = "Dismiss attachment error" },
            ) { Text("Dismiss") }
        }
    }
}

@Composable
private fun PreviewBitmap(
    image: ImageAttachment,
    maxEdge: Int,
    load: suspend (String, Int) -> Bitmap,
    ownership: BitmapOwnership,
    uiMaxEdge: Dp,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    var bitmap by remember(image.id, maxEdge) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(image.id, maxEdge) { mutableStateOf(true) }
    var failed by remember(image.id, maxEdge) { mutableStateOf(false) }

    DisposableEffect(bitmap) {
        val displayed = bitmap
        onDispose { displayed?.let(ownership::release) }
    }

    LaunchedEffect(image.id, maxEdge) {
        bitmap = null
        loading = true
        failed = false
        var candidate: Bitmap? = null
        var ownedByRegistry = false
        try {
            candidate = load(image.id, maxEdge)
            currentCoroutineContext().ensureActive()
            if (!ownership.acquire(candidate)) {
                candidate = null
                return@LaunchedEffect
            }
            ownedByRegistry = true
            currentCoroutineContext().ensureActive()
            bitmap = candidate
            candidate = null
            ownedByRegistry = false
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            loading = false
        } finally {
            candidate?.let { returned ->
                if (ownedByRegistry) ownership.release(returned) else recycleBitmap(returned)
            }
        }
    }

    val description = "Preview of ${safeAttachmentDisplayName(image.displayName)}"
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 72.dp, minHeight = 72.dp)
            .sizeIn(maxWidth = uiMaxEdge, maxHeight = uiMaxEdge)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickModifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            failed -> Text("Preview unavailable", color = MaterialTheme.colorScheme.error)
        }
    }
}

private class BitmapOwnership {
    private val references = IdentityHashMap<Bitmap, Int>()
    private var disposed = false

    @Synchronized
    fun acquire(bitmap: Bitmap): Boolean {
        if (disposed) {
            recycleBitmap(bitmap)
            return false
        }
        references[bitmap] = (references[bitmap] ?: 0) + 1
        return true
    }

    @Synchronized
    fun release(bitmap: Bitmap) {
        val count = references[bitmap] ?: return
        if (count <= 1) {
            references.remove(bitmap)
            recycleBitmap(bitmap)
        } else {
            references[bitmap] = count - 1
        }
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        val owned = references.keys.toList()
        references.clear()
        owned.forEach(::recycleBitmap)
    }
}

private fun recycleBitmap(bitmap: Bitmap) {
    runCatching {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
