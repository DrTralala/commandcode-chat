package com.commandcode.chat.data.attachments

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
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

/**
 * A test-only content source. Bytes are backed by a private test-APK file rather than
 * this process's static map, so a provider process can serve the URI to the target app.
 * Each target grant is explicit and is revoked when the source is removed.
 */
internal object OwnedImageSources {
    const val AUTHORITY = "com.commandcode.chat.test.image-source"

    private const val SOURCE_DIRECTORY = "task11-owned-image-sources"
    private const val READ_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val sources = ConcurrentHashMap<String, SourceRecord>()

    fun publish(format: AndroidImageTestFixtures.Format): OwnedImageSource {
        val identity = SourceIdentity.create(format)
        val file = sourceFile(testContext(), identity)
        file.parentFile?.mkdirs()
        check(file.parentFile?.isDirectory == true) { "test source directory unavailable" }
        check(file.createNewFile()) { "test source collision: ${identity.fileName}" }
        try {
            file.writeBytes(format.bytes)
            check(file.length() == format.bytes.size.toLong()) { "test source write was incomplete" }
        } catch (error: Exception) {
            file.delete()
            throw error
        }

        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .appendPath(identity.fileName)
            .build()
        sources[identity.fileName] = SourceRecord(
            uri = uri,
            file = file,
        )
        testContext().grantUriPermission(targetContext().packageName, uri, READ_GRANT)
        return OwnedImageSource(
            uri = uri,
            bytes = format.bytes.copyOf(),
            mimeType = format.mimeType,
            displayName = format.displayName,
        )
    }

    fun revoke(source: OwnedImageSource) {
        val identity = parse(source.uri)
        val record = sources.remove(identity.fileName)
        val file = record?.file ?: sourceFile(testContext(), identity)
        try {
            if (file.exists() && !file.delete() && file.exists()) {
                error("unable to revoke test source ${identity.fileName}")
            }
        } finally {
            testContext().revokeUriPermission(targetContext().packageName, source.uri, READ_GRANT)
        }
    }

    fun isAvailable(uri: Uri): Boolean {
        val identity = parse(uri)
        return sources[identity.fileName]?.file?.isFile == true
    }

    fun readThroughTargetResolver(source: OwnedImageSource): ByteArray {
        val identity = parse(source.uri)
        check(sources.containsKey(identity.fileName)) { "test source is not published" }
        return targetContext().contentResolver.openInputStream(source.uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("unable to read test source ${identity.fileName}")
    }

    fun clear() {
        sources.values.forEach { record ->
            runCatching {
                testContext().revokeUriPermission(targetContext().packageName, record.uri, READ_GRANT)
            }
            runCatching { record.file.delete() }
        }
        sources.clear()
        sourceRoot(testContext()).deleteRecursively()
    }

    internal fun sourceForProvider(context: Context, uri: Uri): ProviderSource {
        val identity = try {
            parse(uri)
        } catch (error: IllegalArgumentException) {
            throw FileNotFoundException("invalid test source URI").also { it.initCause(error) }
        }
        val root = sourceRoot(context)
        val file = File(root, identity.fileName)
        val canonicalRoot = try {
            root.canonicalFile
        } catch (error: IOException) {
            throw FileNotFoundException("test source root unavailable").also { it.initCause(error) }
        }
        val canonicalFile = try {
            file.canonicalFile
        } catch (error: IOException) {
            throw FileNotFoundException("test source unavailable").also { it.initCause(error) }
        }
        if (canonicalFile.parentFile != canonicalRoot || !canonicalFile.isFile) {
            throw FileNotFoundException("revoked test source ${identity.fileName}")
        }
        val metadata = metadata(identity.extension)
        return ProviderSource(canonicalFile, metadata.mimeType, metadata.displayName)
    }

    private fun parse(uri: Uri): SourceIdentity {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "test source must use content scheme" }
        require(uri.authority == AUTHORITY) { "test source authority mismatch" }
        require(uri.query == null && uri.fragment == null) { "test source URI must not contain query or fragment" }
        val segments = uri.pathSegments
        require(segments.size == 1) { "test source URI must contain exactly one path segment" }
        val fileName = segments.single()
        val dot = fileName.lastIndexOf('.')
        require(dot > 0 && dot == fileName.indexOf('.')) { "test source filename is invalid" }
        val id = fileName.substring(0, dot)
        val extension = fileName.substring(dot + 1)
        require(UUID.fromString(id).toString() == id) { "test source ID is invalid" }
        require(extension in EXTENSIONS) { "test source extension is invalid" }
        return SourceIdentity(id, extension, fileName)
    }

    private fun sourceFile(context: Context, identity: SourceIdentity): File =
        File(sourceRoot(context), identity.fileName)

    private fun sourceRoot(context: Context): File = File(context.filesDir, SOURCE_DIRECTORY)

    private fun testContext(): Context = InstrumentationRegistry.getInstrumentation().context

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun metadata(extension: String): SourceMetadata = when (extension) {
        "png" -> SourceMetadata("image/png", "owned.png")
        "jpg" -> SourceMetadata("image/jpeg", "owned.jpg")
        "webp" -> SourceMetadata("image/webp", "owned.webp")
        else -> error("unsupported test source extension: $extension")
    }

    private data class SourceIdentity(
        val id: String,
        val extension: String,
        val fileName: String,
    ) {
        companion object {
            fun create(format: AndroidImageTestFixtures.Format): SourceIdentity {
                val extension = when (format.mimeType) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    "image/webp" -> "webp"
                    else -> error("unsupported test source MIME type: ${format.mimeType}")
                }
                val id = UUID.randomUUID().toString()
                return SourceIdentity(id, extension, "$id.$extension")
            }
        }
    }

    private data class SourceRecord(
        val uri: Uri,
        val file: File,
    )

    private data class SourceMetadata(val mimeType: String, val displayName: String)

    internal data class ProviderSource(
        val file: File,
        val mimeType: String,
        val displayName: String,
    )

    private val EXTENSIONS = setOf("png", "jpg", "webp")
}

/** ContentProvider implementation for [OwnedImageSources]. */
class OwnedImageContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun source(uri: Uri): OwnedImageSources.ProviderSource =
        OwnedImageSources.sourceForProvider(requireNotNull(context), uri)

    override fun getType(uri: Uri): String = source(uri).mimeType

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val source = source(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = arrayOfNulls<Any>(columns.size)
        columns.forEachIndexed { index, column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> values[index] = source.displayName
                OpenableColumns.SIZE -> values[index] = source.file.length()
            }
        }
        return MatrixCursor(columns).also { it.addRow(values) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("test source is read-only")
        return ParcelFileDescriptor.open(source(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
}
