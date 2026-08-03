package com.pdfmaker.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmaker.app.MainViewModel
import com.pdfmaker.app.UiEvent
import com.pdfmaker.app.core.DocItem
import com.pdfmaker.app.core.ImageFormat
import com.pdfmaker.app.core.PageExporter
import com.pdfmaker.app.core.RenderDensity
import kotlin.math.roundToInt

/**
 * The reverse trip: one PDF in, a `.zip` of page images out. Kept in its own tab
 * because it works on a single document rather than the build list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen(
    viewModel: MainViewModel,
    currentTab: HomeTab,
    onTabChange: (HomeTab) -> Unit
) {
    val state by viewModel.extractState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var preview by remember { mutableStateOf<DocItem?>(null) }

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::setExtractSource) }

    val createZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::extractToZip) }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)

                is UiEvent.ExportedZip -> {
                    val skippedNote = if (event.skipped.isEmpty()) {
                        ""
                    } else {
                        " (${event.skipped.size} skipped)"
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = "Saved ${event.fileCount} image" +
                            "${if (event.fileCount == 1) "" else "s"}$skippedNote",
                        actionLabel = "Share",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        shareFile(context, event.uri, "application/zip", "Share ZIP")
                    }
                }

                // Belongs to the Create PDF tab, which collects it there.
                is UiEvent.Exported -> Unit
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("PDF → Images") }) },
        bottomBar = {
            Column {
                val source = state.source
                if (source != null) {
                    ExtractBottomBar(
                        pageCount = source.pageCount,
                        format = state.options.format,
                        progress = state.progress,
                        busy = state.isBusy,
                        onChangeFile = { pickPdf.launch(PDF_MIME_TYPES) },
                        onExport = {
                            createZip.launch(PageExporter.suggestZipName(source.name))
                        },
                        onCancel = viewModel::cancelExtract
                    )
                }
                HomeTabBar(current = currentTab, onTabChange = onTabChange)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val source = state.source
            if (source == null) {
                ExtractEmptyState(onPickPdf = { pickPdf.launch(PDF_MIME_TYPES) })
            } else {
                ExtractOptionsBody(
                    source = source,
                    format = state.options.format,
                    density = state.options.density,
                    jpegQuality = state.options.jpegQuality,
                    onFormat = { value ->
                        viewModel.updateExtractOptions { it.copy(format = value) }
                    },
                    onDensity = { value ->
                        viewModel.updateExtractOptions { it.copy(density = value) }
                    },
                    onJpegQuality = { value ->
                        viewModel.updateExtractOptions { it.copy(jpegQuality = value) }
                    },
                    onOpenPreview = { preview = source }
                )
            }

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    preview?.let { item ->
        PageViewerDialog(item = item, onDismiss = { preview = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtractOptionsBody(
    source: DocItem,
    format: ImageFormat,
    density: RenderDensity,
    jpegQuality: Int,
    onFormat: (ImageFormat) -> Unit,
    onDensity: (RenderDensity) -> Unit,
    onJpegQuality: (Int) -> Unit,
    onOpenPreview: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Thumbnail(
                    item = source,
                    modifier = Modifier
                        .size(width = 54.dp, height = 72.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenPreview)
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${source.pageCount} page" +
                            "${if (source.pageCount == 1) "" else "s"} · " +
                            formatBytes(source.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onOpenPreview,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("Preview pages")
                    }
                }
            }
        }

        SectionLabel("Image format")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageFormat.entries.forEach { option ->
                FilterChip(
                    selected = format == option,
                    onClick = { onFormat(option) },
                    label = { Text(option.label) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (format == ImageFormat.PNG) {
                "PNG is lossless — larger files, perfect for line art and text."
            } else {
                "JPEG keeps photos and scans small."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("Resolution")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RenderDensity.entries.forEach { option ->
                FilterChip(
                    selected = density == option,
                    onClick = { onDensity(option) },
                    label = { Text(option.label) }
                )
            }
        }

        if (format == ImageFormat.JPEG) {
            SectionLabel("JPEG quality · $jpegQuality")
            Slider(
                value = jpegQuality.toFloat(),
                onValueChange = { onJpegQuality(it.roundToInt()) },
                valueRange = 50f..100f,
                steps = 9
            )
        }

        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Creates one .${format.extension} per page — ${source.pageCount} " +
                    "file${if (source.pageCount == 1) "" else "s"} — packed into a single " +
                    ".zip you can save anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ExtractEmptyState(onPickPdf: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("PDF to images", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Turn a PDF into a ZIP of JPG or PNG files, one per page — handy when " +
                "something only accepts pictures. Everything runs on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))
        FilledTonalButton(onClick = onPickPdf, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Choose a PDF")
        }
    }
}

@Composable
private fun ExtractBottomBar(
    pageCount: Int,
    format: ImageFormat,
    progress: Pair<Int, Int>?,
    busy: Boolean,
    onChangeFile: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (progress != null) {
                val (done, total) = progress
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Rendering page $done of $total",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onChangeFile, enabled = !busy) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Choose another PDF")
                    }
                    Button(
                        onClick = onExport,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Export ZIP · $pageCount ${format.label}" +
                                (if (pageCount == 1) "" else "s")
                        )
                    }
                }
            }
        }
    }
}

private val PDF_MIME_TYPES = arrayOf("application/pdf")
