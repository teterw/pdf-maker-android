package com.pdfmaker.app.core

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PageSizeOption(val label: String, val widthPt: Float, val heightPt: Float) {
    FIT_IMAGE("Fit to image", 0f, 0f),
    A4("A4", 595f, 842f),
    LETTER("US Letter", 612f, 792f),
    LEGAL("US Legal", 612f, 1008f)
}

enum class MarginOption(val label: String, val points: Float) {
    NONE("None", 0f),
    SMALL("Small", 18f),
    MEDIUM("Medium", 36f),
    LARGE("Large", 54f)
}

/**
 * Trades file size against fidelity. [LOSSLESS] embeds the pixels untouched (best for
 * screenshots and line art); the others re-encode as JPEG.
 */
enum class QualityOption(
    val label: String,
    val jpegQuality: Float,
    val maxDimensionPx: Int
) {
    LOSSLESS("Lossless", 1f, 4000),
    HIGH("High", 0.92f, 3000),
    MEDIUM("Medium", 0.80f, 2000),
    COMPACT("Compact", 0.65f, 1400)
}

data class BuildOptions(
    val pageSize: PageSizeOption = PageSizeOption.FIT_IMAGE,
    val margin: MarginOption = MarginOption.NONE,
    val quality: QualityOption = QualityOption.HIGH,
    /** Use landscape pages for landscape images when a fixed page size is chosen. */
    val autoRotate: Boolean = true
)

sealed interface BuildResult {
    data class Success(val pageCount: Int, val skipped: List<String>) : BuildResult
    data class Failure(val message: String) : BuildResult
}

object PdfBuilder {

    private const val TAG = "PdfBuilder"

    /** Longest edge of a "fit to image" page, in points — matches A4's long side. */
    private const val FIT_LONG_EDGE_PT = 842f

    suspend fun build(
        context: Context,
        items: List<DocItem>,
        destination: Uri,
        options: BuildOptions,
        onProgress: (done: Int, total: Int) -> Unit
    ): BuildResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext BuildResult.Failure("Add at least one file first.")

        val resolver = context.contentResolver
        val output = PDDocument()
        // Sources are held open until after save(); PDFBox resolves some indirect objects
        // lazily while writing.
        val sources = mutableListOf<PDDocument>()
        val skipped = mutableListOf<String>()

        try {
            val merger = PDFMergerUtility()

            items.forEachIndexed { index, item ->
                ensureActive()
                try {
                    when (item.kind) {
                        DocKind.PDF -> {
                            val source = resolver.openInputStream(item.uri)?.use { stream ->
                                PDDocument.load(stream)
                            } ?: throw IOException("Cannot open ${item.name}")
                            sources += source
                            merger.appendDocument(output, source)
                        }

                        DocKind.IMAGE -> addImagePage(output, resolver, item, options)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One unreadable file shouldn't sink the whole export.
                    Log.w(TAG, "Skipping ${item.name}", e)
                    skipped += item.name
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Out of memory on ${item.name}", e)
                    skipped += item.name
                }
                onProgress(index + 1, items.size)
            }

            ensureActive()

            if (output.numberOfPages == 0) {
                return@withContext BuildResult.Failure(
                    "None of the selected files could be read."
                )
            }

            val wrote = resolver.openOutputStream(destination)?.use { stream ->
                output.save(stream)
                true
            } ?: false

            if (!wrote) {
                return@withContext BuildResult.Failure("Could not write to the chosen location.")
            }

            BuildResult.Success(pageCount = output.numberOfPages, skipped = skipped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            BuildResult.Failure(e.message ?: "PDF export failed.")
        } finally {
            sources.forEach { doc -> runCatching { doc.close() } }
            runCatching { output.close() }
        }
    }

    private fun addImagePage(
        document: PDDocument,
        resolver: ContentResolver,
        item: DocItem,
        options: BuildOptions
    ) {
        var bitmap: Bitmap = ImageDecoder.decode(resolver, item.uri, options.quality.maxDimensionPx)
            ?: throw IOException("Could not decode ${item.name}")

        val image: PDImageXObject = try {
            if (options.quality == QualityOption.LOSSLESS) {
                LosslessFactory.createFromImage(document, bitmap)
            } else {
                bitmap = ImageDecoder.flattenOntoWhite(bitmap)
                JPEGFactory.createFromImage(document, bitmap, options.quality.jpegQuality)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        val margin = options.margin.points
        val pageRect = pageRectFor(image.width, image.height, options, margin)

        val availableWidth = (pageRect.width - margin * 2).coerceAtLeast(1f)
        val availableHeight = (pageRect.height - margin * 2).coerceAtLeast(1f)
        val scale = minOf(
            availableWidth / image.width.toFloat(),
            availableHeight / image.height.toFloat()
        )
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val x = (pageRect.width - drawWidth) / 2f
        val y = (pageRect.height - drawHeight) / 2f

        val page = PDPage(pageRect)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.drawImage(image, x, y, drawWidth, drawHeight)
        }
    }

    private fun pageRectFor(
        imageWidth: Int,
        imageHeight: Int,
        options: BuildOptions,
        margin: Float
    ): PDRectangle {
        if (options.pageSize == PageSizeOption.FIT_IMAGE) {
            // Keep the image's aspect ratio exactly, normalised to a sane page size —
            // mapping pixels straight to points would produce absurdly large pages.
            val longest = maxOf(imageWidth, imageHeight).coerceAtLeast(1)
            val scale = FIT_LONG_EDGE_PT / longest
            return PDRectangle(
                imageWidth * scale + margin * 2,
                imageHeight * scale + margin * 2
            )
        }

        val portrait = PDRectangle(options.pageSize.widthPt, options.pageSize.heightPt)
        val landscape = PDRectangle(options.pageSize.heightPt, options.pageSize.widthPt)
        return if (options.autoRotate && imageWidth > imageHeight) landscape else portrait
    }

    /** e.g. "PDF Maker 2026-08-02 14-30.pdf" — safe on every Android file system. */
    fun suggestFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        return "PDF Maker $stamp.pdf"
    }
}
