package com.pdfmaker.app.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a single page at screen-filling resolution for the full-screen viewer, where
 * the point is to read the page rather than recognise it.
 *
 * Deliberately not cached: these bitmaps are far too large to keep alongside the list
 * thumbnails, and only one or two are alive at a time.
 */
object PreviewRenderer {

    private const val TAG = "PreviewRenderer"

    /** Enough detail to stay sharp at roughly 2× zoom on a 1080p phone. */
    private const val FULL_PX = 1800

    /** Retry size when a device cannot allocate the full-resolution bitmap. */
    private const val FALLBACK_PX = 900

    suspend fun render(context: Context, item: DocItem, pageIndex: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            attempt(context, item, pageIndex, FULL_PX)
                ?: attempt(context, item, pageIndex, FALLBACK_PX)
        }

    private fun attempt(context: Context, item: DocItem, pageIndex: Int, maxPx: Int): Bitmap? =
        try {
            when (item.kind) {
                DocKind.IMAGE -> ImageDecoder.decode(context.contentResolver, item.uri, maxPx)
                DocKind.PDF -> PdfInspector.renderPage(context, item.uri, pageIndex, maxPx)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory rendering ${item.name} at $maxPx px", e)
            null
        }
}
