package com.pdfmaker.app.core

import android.net.Uri

enum class DocKind { IMAGE, PDF }

/**
 * One entry in the build list. [id] is stable for the lifetime of the list so that
 * Compose keys and the selection set survive re-sorting.
 */
data class DocItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val kind: DocKind,
    /** Page count for PDFs; images always contribute exactly one page. */
    val pageCount: Int = 1
)

enum class SortMode(val label: String) {
    MANUAL("Custom order"),
    NAME_ASC("Name (A → Z)"),
    NAME_DESC("Name (Z → A)"),
    DATE_DESC("Date (newest first)"),
    DATE_ASC("Date (oldest first)"),
    SIZE_DESC("Size (largest first)"),
    SIZE_ASC("Size (smallest first)")
}

/**
 * Compares strings the way a file manager does, so "page2" sorts before "page10".
 * Digit runs are compared as numbers, everything else case-insensitively.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var startA = i
                var startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                // Ignore leading zeros, but never consume the final digit.
                while (startA < i - 1 && a[startA] == '0') startA++
                while (startB < j - 1 && b[startB] == '0') startB++
                val lenA = i - startA
                val lenB = j - startB
                if (lenA != lenB) return lenA - lenB
                for (k in 0 until lenA) {
                    val diff = a[startA + k] - b[startB + k]
                    if (diff != 0) return diff
                }
            } else {
                val diff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (diff != 0) return diff
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

fun List<DocItem>.sortedBy(mode: SortMode): List<DocItem> = when (mode) {
    SortMode.MANUAL -> this
    SortMode.NAME_ASC -> sortedWith { x, y -> NaturalOrder.compare(x.name, y.name) }
    SortMode.NAME_DESC -> sortedWith { x, y -> NaturalOrder.compare(y.name, x.name) }
    SortMode.DATE_DESC -> sortedByDescending { it.lastModified }
    SortMode.DATE_ASC -> sortedBy { it.lastModified }
    SortMode.SIZE_DESC -> sortedByDescending { it.sizeBytes }
    SortMode.SIZE_ASC -> sortedBy { it.sizeBytes }
}
