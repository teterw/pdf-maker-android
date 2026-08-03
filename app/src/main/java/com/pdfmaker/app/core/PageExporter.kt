package com.pdfmaker.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

enum class ImageFormat(val label: String, val extension: String) {
    JPEG("JPEG", "jpg"),
    PNG("PNG", "png");

    val compressFormat: Bitmap.CompressFormat
        get() = if (this == PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
}

/** Render density. PDF user units are 1/72 inch, so the scale factor is dpi / 72. */
enum class RenderDensity(val label: String, val dpi: Int) {
    SCREEN("Screen · 96 dpi", 96),
    GOOD("Good · 150 dpi", 150),
    PRINT("Print · 300 dpi", 300)
}

data class ExtractOptions(
    val format: ImageFormat = ImageFormat.JPEG,
    val density: RenderDensity = RenderDensity.GOOD,
    /** JPEG encoder quality, 50–100. Ignored for PNG, which is lossless. */
    val jpegQuality: Int = 90
)

sealed interface ExtractResult {
    data class Success(val fileCount: Int, val skipped: List<Int>) : ExtractResult
    data class Failure(val message: String) : ExtractResult
}

/**
 * Renders every page of a PDF to an image and packs the lot into a `.zip`, the reverse
 * of what [PdfBuilder] does. Pages are rendered one at a time and streamed straight
 * into the archive, so a 300-page scan never needs more than one bitmap in memory.
 */
object PageExporter {

    private const val TAG = "PageExporter"

    /** Guards against absurd bitmaps from oversized page boxes at 300 dpi. */
    private const val MAX_EDGE_PX = 5000

    suspend fun toZip(
        context: Context,
        source: DocItem,
        destination: Uri,
        options: ExtractOptions,
        onProgress: (done: Int, total: Int) -> Unit
    ): ExtractResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val skipped = mutableListOf<Int>()
        var written = 0

        var descriptor: ParcelFileDescriptor? = null
        try {
            val fd = resolver.openFileDescriptor(source.uri, "r")
                ?: return@withContext ExtractResult.Failure("Could not open ${source.name}.")
            descriptor = fd

            PdfRenderer(fd).use { renderer ->
                val total = renderer.pageCount
                if (total == 0) {
                    return@withContext ExtractResult.Failure("${source.name} has no pages.")
                }

                // Opened only once the source is known to be readable, so a failure here
                // does not leave a half-open stream on the destination.
                val output = resolver.openOutputStream(destination)
                    ?: return@withContext ExtractResult.Failure(
                        "Could not write to the chosen location."
                    )

                val baseName = baseNameOf(source.name)
                val digits = total.toString().length.coerceAtLeast(2)

                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    // JPEG and PNG payloads are already compressed; deflating them again
                    // costs CPU for no gain.
                    zip.setLevel(Deflater.NO_COMPRESSION)

                    for (index in 0 until total) {
                        coroutineContext.ensureActive()
                        val bitmap = try {
                            renderPage(renderer, index, options.density.dpi)
                        } catch (e: OutOfMemoryError) {
                            Log.w(TAG, "Out of memory on page ${index + 1}", e)
                            null
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not render page ${index + 1}", e)
                            null
                        }

                        if (bitmap == null) {
                            skipped += index + 1
                        } else {
                            try {
                                val name = String.format(
                                    Locale.US,
                                    "%s-%0${digits}d.%s",
                                    baseName,
                                    index + 1,
                                    options.format.extension
                                )
                                zip.putNextEntry(ZipEntry(name))
                                bitmap.compress(
                                    options.format.compressFormat,
                                    options.jpegQuality.coerceIn(1, 100),
                                    zip
                                )
                                zip.closeEntry()
                                written++
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        onProgress(index + 1, total)
                    }
                }

                if (written == 0) {
                    return@withContext ExtractResult.Failure("None of the pages could be rendered.")
                }
                ExtractResult.Success(fileCount = written, skipped = skipped)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed", e)
            ExtractResult.Failure(e.message ?: "Could not create the ZIP.")
        } finally {
            try {
                descriptor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed closing descriptor", e)
            }
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, dpi: Int): Bitmap =
        renderer.openPage(index).use { page ->
            val scale = dpi / 72f
            var width = (page.width * scale).toInt().coerceAtLeast(1)
            var height = (page.height * scale).toInt().coerceAtLeast(1)

            val longest = maxOf(width, height)
            if (longest > MAX_EDGE_PX) {
                val shrink = MAX_EDGE_PX.toFloat() / longest
                width = (width * shrink).toInt().coerceAtLeast(1)
                height = (height * shrink).toInt().coerceAtLeast(1)
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PdfRenderer composites onto whatever is already there, and JPEG has no
            // alpha channel — paper white is the right backdrop for both.
            bitmap.eraseColor(Color.WHITE)
            val mode = if (dpi >= 150) {
                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
            } else {
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            }
            page.render(bitmap, null, null, mode)
            bitmap
        }

    /** e.g. "Report.pdf" → "Report", so entries read `Report-01.jpg`. */
    private fun baseNameOf(fileName: String): String {
        val stem = fileName.substringBeforeLast('.', fileName).trim()
        val safe = stem.map { if (it.isLetterOrDigit() || it in "-_ ") it else '_' }
            .joinToString("")
            .trim()
        return safe.ifBlank { "page" }
    }

    fun suggestZipName(fileName: String): String = "${baseNameOf(fileName)}.zip"
}
