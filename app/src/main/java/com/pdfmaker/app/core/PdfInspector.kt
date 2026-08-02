package com.pdfmaker.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Cheap read-only inspection of a PDF using the platform renderer. Used for the page
 * count badge and list thumbnails only — the actual merge goes through PDFBox so that
 * text and vectors stay intact.
 */
object PdfInspector {

    private const val TAG = "PdfInspector"

    fun pageCount(context: Context, uri: Uri): Int = withRenderer(context, uri) { it.pageCount } ?: 1

    /** Renders page 0 into a bitmap whose longest side is [maxDimension] px. */
    fun renderFirstPage(context: Context, uri: Uri, maxDimension: Int): Bitmap? =
        withRenderer(context, uri) { renderer ->
            if (renderer.pageCount == 0) return@withRenderer null
            renderer.openPage(0).use { page ->
                val scale = maxDimension.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                // PdfRenderer composites onto whatever is already there; PDFs assume paper.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }

    private fun <T> withRenderer(context: Context, uri: Uri, block: (PdfRenderer) -> T?): T? {
        var descriptor: ParcelFileDescriptor? = null
        return try {
            descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            PdfRenderer(descriptor).use { block(it) }
        } catch (e: Exception) {
            // Encrypted, malformed, or a provider that cannot supply a seekable fd.
            Log.w(TAG, "Cannot open PDF $uri", e)
            null
        } finally {
            try {
                descriptor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed closing descriptor", e)
            }
        }
    }
}
