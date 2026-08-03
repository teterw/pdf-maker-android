package com.pdfmaker.app.core

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Memory-cached previews for the build list. Images decode directly; PDFs get their
 * first page rendered, so a merged list previews correctly either way.
 *
 * Two sizes are cached side by side — a row-sized thumbnail and a card-sized preview
 * for the large view mode — so switching view mode does not re-decode everything.
 */
object ThumbnailLoader {

    /** Longest edge for the compact list rows. */
    const val SMALL_PX = 240

    /** Longest edge for the full-width previews in large view mode. */
    const val LARGE_PX = 1000

    private data class Key(val id: Long, val maxPx: Int)

    private val cache: LruCache<Key, Bitmap> by lazy {
        val limitKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096)
        object : LruCache<Key, Bitmap>(limitKb) {
            override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    suspend fun load(context: Context, item: DocItem, maxPx: Int = SMALL_PX): Bitmap? {
        val key = Key(item.id, maxPx)
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            when (item.kind) {
                DocKind.IMAGE -> ImageDecoder.decode(context.contentResolver, item.uri, maxPx)
                DocKind.PDF -> PdfInspector.renderFirstPage(context, item.uri, maxPx)
            }
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    /** Drops every cached size for one item. */
    fun evict(id: Long) {
        cache.snapshot().keys.filter { it.id == id }.forEach { cache.remove(it) }
    }

    fun clear() {
        cache.evictAll()
    }
}
