package com.pdfmaker.app.core

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small memory-cached thumbnails for the build list. Images decode directly; PDFs get
 * their first page rendered, so a merged list previews correctly either way.
 */
object ThumbnailLoader {

    private const val THUMB_MAX_PX = 240

    private val cache: LruCache<Long, Bitmap> by lazy {
        val limitKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096)
        object : LruCache<Long, Bitmap>(limitKb) {
            override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    suspend fun load(context: Context, item: DocItem): Bitmap? {
        cache.get(item.id)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            when (item.kind) {
                DocKind.IMAGE -> ImageDecoder.decode(context.contentResolver, item.uri, THUMB_MAX_PX)
                DocKind.PDF -> PdfInspector.renderFirstPage(context, item.uri, THUMB_MAX_PX)
            }
        }
        if (bitmap != null) cache.put(item.id, bitmap)
        return bitmap
    }

    fun evict(id: Long) {
        cache.remove(id)
    }

    fun clear() {
        cache.evictAll()
    }
}
