package com.pdfmaker.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pdfmaker.app.core.DocItem
import com.pdfmaker.app.core.PreviewRenderer

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Full-screen page viewer. Pinch or double-tap to zoom, drag to pan, and — for a
 * multi-page PDF — swipe sideways to walk through the pages. Pages are rendered at
 * viewer resolution rather than reusing the list thumbnail, so zooming in actually
 * shows more of the paper.
 */
@Composable
fun PageViewerDialog(item: DocItem, onDismiss: () -> Unit) {
    val pageCount = item.pageCount.coerceAtLeast(1)
    var zoomed by remember(item.id) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
            contentColor = Color.White
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val pagerState = rememberPagerState(pageCount = { pageCount })

                HorizontalPager(
                    state = pagerState,
                    // Once the page is zoomed in, a horizontal drag means "pan", not
                    // "next page".
                    userScrollEnabled = !zoomed,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    ZoomablePage(
                        item = item,
                        pageIndex = page,
                        onZoomChanged = { isZoomed ->
                            if (page == pagerState.currentPage) zoomed = isZoomed
                        }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .systemBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close preview")
                    }
                    Spacer(Modifier.size(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (pageCount > 1) {
                                "Page ${pagerState.currentPage + 1} of $pageCount · " +
                                    "swipe for the next page"
                            } else {
                                "Pinch or double-tap to zoom"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePage(
    item: DocItem,
    pageIndex: Int,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(item.id, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.id, pageIndex) { mutableStateOf(false) }

    var scale by remember(item.id, pageIndex) { mutableStateOf(1f) }
    var offset by remember(item.id, pageIndex) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(item.id, pageIndex) {
        val rendered = PreviewRenderer.render(context, item, pageIndex)
        bitmap = rendered
        failed = rendered == null
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(item.id, pageIndex) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1.01f) 1f else DOUBLE_TAP_SCALE
                        offset = Offset.Zero
                        onZoomChanged(scale > 1.01f)
                    }
                )
            }
            .pointerInput(item.id, pageIndex, bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    val bounds = panBounds(bitmap, viewport, next)
                    scale = next
                    offset = Offset(
                        x = (offset.x + pan.x).coerceIn(-bounds.x, bounds.x),
                        y = (offset.y + pan.y).coerceIn(-bounds.y, bounds.y)
                    )
                    onZoomChanged(next > 1.01f)
                }
            }
    ) {
        val current = bitmap
        when {
            current != null -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )

            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This page could not be rendered.")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "It may be encrypted or damaged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * How far the page may be dragged before its edge would leave the screen. Based on the
 * letterboxed size the image actually occupies, not the viewport, so panning stops at
 * the paper's edge rather than somewhere in the black surround.
 */
private fun panBounds(bitmap: Bitmap?, viewport: IntSize, scale: Float): Offset {
    if (bitmap == null || viewport.width == 0 || viewport.height == 0) return Offset.Zero

    val imageRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    val viewportRatio = viewport.width.toFloat() / viewport.height.toFloat()
    val drawnWidth: Float
    val drawnHeight: Float
    if (imageRatio > viewportRatio) {
        drawnWidth = viewport.width.toFloat()
        drawnHeight = viewport.width / imageRatio
    } else {
        drawnHeight = viewport.height.toFloat()
        drawnWidth = viewport.height * imageRatio
    }

    return Offset(
        x = ((drawnWidth * scale - viewport.width) / 2f).coerceAtLeast(0f),
        y = ((drawnHeight * scale - viewport.height) / 2f).coerceAtLeast(0f)
    )
}
