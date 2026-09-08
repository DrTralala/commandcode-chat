package com.commandcode.chat.data.attachments

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Generated, app-owned image inputs used by the compile-only device fixtures. */
internal object AndroidImageTestFixtures {
    data class Format(
        val label: String,
        val mimeType: String,
        val displayName: String,
        val bytes: ByteArray,
    )

    fun all(): List<Format> = listOf(
        Format("png", "image/png", "owned.png", encode(android.graphics.Bitmap.CompressFormat.PNG)),
        Format("jpeg", "image/jpeg", "owned.jpg", encode(android.graphics.Bitmap.CompressFormat.JPEG)),
        Format("webp", "image/webp", "owned.webp", encode(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS)),
    )

    private fun encode(format: android.graphics.Bitmap.CompressFormat): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(3, 2, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixel(0, 0, 0xFFFF0000.toInt())
            bitmap.setPixel(1, 0, 0xFF00FF00.toInt())
            bitmap.setPixel(2, 0, 0xFF0000FF.toInt())
            bitmap.setPixel(0, 1, 0xFFFFFFFF.toInt())
            bitmap.setPixel(1, 1, 0xFF808080.toInt())
            bitmap.setPixel(2, 1, 0xFF000000.toInt())
            return ByteArrayOutputStream().also { output ->
                check(bitmap.compress(format, 90, output)) { "generated image compression failed" }
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}

internal data class OwnedImageSource(
    val uri: Uri,
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
)

/** Test-only content sources backed by MediaStore rows owned by the target application. */
internal object OwnedImageSources {
    private val imageCollection =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val insertedUris = ConcurrentHashMap.newKeySet<Uri>()
    private val relativePath = "${Environment.DIRECTORY_PICTURES}/CommandCodeChatTest/"

    /** Test-only delete fault seam; production code never references this fixture. */
    internal var deleteForTests: ((Uri) -> Int)? = null

    fun publish(format: AndroidImageTestFixtures.Format): OwnedImageSource {
        val resolver = targetContext().contentResolver
        val uri = resolver.insert(
            imageCollection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, format.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("unable to insert generated MediaStore image")
        insertedUris += uri

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(format.bytes)
            } ?: error("unable to open generated MediaStore image")
            check(
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1,
            ) { "unable to publish generated MediaStore image" }
            return OwnedImageSource(
                uri = uri,
                bytes = format.bytes.copyOf(),
                mimeType = format.mimeType,
                displayName = format.displayName,
            )
        } catch (error: Throwable) {
            runCatching { deleteOwned(resolver, uri) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    fun revoke(source: OwnedImageSource) {
        if (!insertedUris.contains(source.uri)) return
        deleteOwned(targetContext().contentResolver, source.uri)
    }

    fun isAvailable(uri: Uri): Boolean {
        if (!insertedUris.contains(uri)) return false
        return uriExists(targetContext().contentResolver, uri)
    }

    fun readThroughTargetResolver(source: OwnedImageSource): ByteArray {
        check(insertedUris.contains(source.uri)) { "test source is not published" }
        return targetContext().contentResolver.openInputStream(source.uri)?.use { it.readBytes() }
            ?: error("unable to read generated MediaStore image")
    }

    fun clear() {
        val resolver = targetContext().contentResolver
        val failures = mutableListOf<Throwable>()
        insertedUris.toList().forEach { uri ->
            try {
                deleteOwned(resolver, uri)
            } catch (error: Throwable) {
                failures += IllegalStateException("unable to delete generated MediaStore image $uri", error)
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("unable to clear ${failures.size} generated MediaStore image(s)").also {
                failures.forEach(it::addSuppressed)
            }
        }
    }

    private fun deleteOwned(resolver: ContentResolver, uri: Uri) {
        val deleted = deleteForTests?.invoke(uri) ?: resolver.delete(uri, null, null)
        if (deleted == 0) {
            if (!uriExists(resolver, uri)) {
                insertedUris.remove(uri)
                return
            }
            throw IllegalStateException("generated MediaStore image remains after zero-count delete: $uri")
        }
        check(deleted == 1) { "unexpected generated MediaStore delete count: $deleted" }
        check(!uriExists(resolver, uri)) { "generated MediaStore image remains after delete: $uri" }
        insertedUris.remove(uri)
    }

    private fun uriExists(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext
}
