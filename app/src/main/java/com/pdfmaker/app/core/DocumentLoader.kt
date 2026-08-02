package com.pdfmaker.app.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns picked content URIs into [DocItem]s, pulling display name / size / modified
 * time out of whichever provider backs the URI.
 */
object DocumentLoader {

    private const val TAG = "DocumentLoader"
    private val idSource = AtomicLong(1)

    // DocumentsProvider exposes this; MediaStore exposes "date_modified" in seconds.
    private const val COLUMN_LAST_MODIFIED = "last_modified"
    private const val COLUMN_DATE_MODIFIED = "date_modified"

    fun load(context: Context, uri: Uri, persist: Boolean): DocItem? {
        val resolver = context.contentResolver
        if (persist) tryPersist(resolver, uri)

        val mime = resolver.getType(uri).orEmpty()
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        var modified = 0L

        try {
            resolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !c.isNull(it) }
                        ?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !c.isNull(it) }
                        ?.let { size = c.getLong(it) }
                    c.getColumnIndex(COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 && !c.isNull(it) }
                        ?.let { modified = c.getLong(it) }
                    if (modified == 0L) {
                        c.getColumnIndex(COLUMN_DATE_MODIFIED)
                            .takeIf { it >= 0 && !c.isNull(it) }
                            ?.let { modified = c.getLong(it) * 1000L }
                    }
                }
            }
        } catch (e: Exception) {
            // Some providers reject a null projection; the URI is still usable.
            Log.w(TAG, "Metadata query failed for $uri", e)
        }

        val kind = when {
            mime == "application/pdf" -> DocKind.PDF
            mime.startsWith("image/") -> DocKind.IMAGE
            name.endsWith(".pdf", ignoreCase = true) -> DocKind.PDF
            IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } -> DocKind.IMAGE
            else -> return null
        }

        if (name.isBlank()) {
            name = if (kind == DocKind.PDF) "document.pdf" else "image"
        }

        // Confirm the URI is actually readable before it reaches the build list.
        val readable = try {
            resolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable URI $uri", e)
            false
        }
        if (!readable) return null

        val pages = if (kind == DocKind.PDF) PdfInspector.pageCount(context, uri) else 1

        return DocItem(
            id = idSource.getAndIncrement(),
            uri = uri,
            name = name,
            sizeBytes = size,
            lastModified = if (modified > 0) modified else System.currentTimeMillis(),
            kind = kind,
            pageCount = pages
        )
    }

    private fun tryPersist(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Photo-picker and share-sheet URIs are not persistable. They stay valid for
            // this process's lifetime, which is all the build needs.
            Log.d(TAG, "URI not persistable: $uri")
        }
    }

    private val IMAGE_EXTENSIONS =
        listOf(".png", ".jpg", ".jpeg", ".webp", ".heic", ".heif", ".bmp", ".gif")
}
