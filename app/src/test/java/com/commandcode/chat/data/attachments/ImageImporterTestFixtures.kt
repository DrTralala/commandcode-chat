package com.commandcode.chat.data.attachments

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Minimal content:// fixture provider. [size] reports OpenableColumns.SIZE to the
 * importer while [bytes] are what openInputStream actually serves: the stream is
 * authoritative and metadata may lie.
 */
class AttachmentContentProvider(
    private val bytes: ByteArray,
    private val size: Long?,
    private val declaredMime: String? = null,
    private val displayName: String? = null,
    private val streamSupplier: (() -> InputStream)? = null,
) : android.content.ContentProvider() {

    override fun onCreate(): Boolean = true

    /** Registers with Robolectric and attachInfo's so the real ContentResolver transport works. */
    fun registerWith(context: android.content.Context, authority: String) {
        val info = android.content.pm.ProviderInfo()
        info.authority = authority
        info.packageName = context.packageName
        info.name = javaClass.name
        attachInfo(context, info)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, this)
        // openInputStream consults the registered stream map first; serve a fresh stream
        // per call so bounded reads of any size are reliable (the shadow pipe EOFs on
        // large reads).
        val resolver = context.contentResolver
        val uri = android.net.Uri.parse("content://$authority/doc/1")
        org.robolectric.Shadows.shadowOf(resolver)
            .registerInputStreamSupplier(uri) { streamSupplier?.invoke() ?: ByteArrayInputStream(bytes) }
    }

    override fun getType(uri: Uri): String? = declaredMime

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): android.database.Cursor {
        val columns = projection ?: arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME,
            android.provider.OpenableColumns.SIZE,
        )
        val matrix = android.database.MatrixCursor(columns)
        val values = arrayOfNulls<Any>(columns.size)
        columns.forEachIndexed { i, column ->
            when (column) {
                android.provider.OpenableColumns.DISPLAY_NAME -> values[i] = displayName
                android.provider.OpenableColumns.SIZE ->
                    values[i] = size?.let { java.lang.Long.valueOf(it) }
            }
        }
        matrix.addRow(values)
        return matrix
    }

    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun update(
        uri: Uri,
        values: android.content.ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
}

/** Tracks whether close() was called on a wrapped stream. */
class CloseTrackingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}

/** Fails every read with an IOException to exercise the importer's read-failure path. */
class FailingReadInputStream : InputStream() {
    override fun read(): Int = throw IOException("injected read failure")
}

/** Throws when closed; tracks close attempts for cleanup-ordering assertions. */
class CloseThrowingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    var closeAttempts = 0
        private set

    override fun close() {
        closeAttempts++
        super.close()
        throw IOException("injected close failure")
    }
}
