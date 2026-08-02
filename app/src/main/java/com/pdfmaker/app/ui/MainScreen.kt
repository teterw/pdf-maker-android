package com.pdfmaker.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pdfmaker.app.MainViewModel
import com.pdfmaker.app.UiEvent
import com.pdfmaker.app.core.PdfBuilder
import com.pdfmaker.app.core.SortMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showSortMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // The system document picker already supports multi-select, which is what makes
    // "grab 30 scans at once" work without any storage permission.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addUris(uris, persist = true) }

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> viewModel.addUris(uris, persist = false) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let(viewModel::export) }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)

                is UiEvent.Exported -> {
                    val skippedNote = if (event.skipped.isEmpty()) {
                        ""
                    } else {
                        " (${event.skipped.size} skipped)"
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = "Saved ${event.pageCount} pages$skippedNote",
                        actionLabel = "Share",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        sharePdf(context, event.uri)
                    }
                }
            }
        }
    }

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                        }
                    },
                    title = { Text("${state.selectedIds.size} selected") },
                    actions = {
                        IconButton(
                            onClick = {
                                if (state.allSelected) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll()
                                }
                            }
                        ) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = viewModel::removeSelected) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("PDF Maker") },
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        leadingIcon = {
                                            if (state.sortMode == mode) {
                                                Icon(Icons.Filled.Check, contentDescription = null)
                                            } else {
                                                Spacer(Modifier.width(24.dp))
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSortMode(mode)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { showOptions = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "PDF settings")
                        }

                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Reverse order") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.SwapVert, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.reverseOrder()
                                        showOverflow = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.SelectAll, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.selectAll()
                                        showOverflow = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove all") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Delete, contentDescription = null)
                                    },
                                    onClick = {
                                        showOverflow = false
                                        showClearConfirm = true
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                BottomBar(
                    pageCount = state.totalPages,
                    exportProgress = state.exportProgress,
                    busy = state.isBusy,
                    onAddPhotos = {
                        pickPhotos.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onAddFiles = { pickFiles.launch(PICKER_MIME_TYPES) },
                    onExport = { createDocument.launch(PdfBuilder.suggestFileName()) },
                    onCancel = viewModel::cancelExport
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.items.isEmpty()) {
                EmptyState(
                    onPickPhotos = {
                        pickPhotos.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onPickFiles = { pickFiles.launch(PICKER_MIME_TYPES) }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = state.items, key = { it.id }) { item ->
                        val index = state.items.indexOfFirst { it.id == item.id }
                        DocRow(
                            index = index,
                            item = item,
                            selected = item.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.items.lastIndex,
                            onClick = {
                                if (state.selectionMode) viewModel.toggleSelection(item.id)
                            },
                            onLongClick = { viewModel.startSelection(item.id) },
                            onMoveUp = { viewModel.move(index, index - 1) },
                            onMoveDown = { viewModel.move(index, index + 1) },
                            onRemove = { viewModel.remove(item.id) }
                        )
                    }
                }
            }

            if (state.isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showOptions) {
        OptionsSheet(
            options = state.options,
            onChange = viewModel::updateOptions,
            onDismiss = { showOptions = false }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Remove all files?") },
            text = { Text("This only clears the list. Your original files are untouched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearConfirm = false
                    }
                ) { Text("Remove all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BottomBar(
    pageCount: Int,
    exportProgress: Pair<Int, Int>?,
    busy: Boolean,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (exportProgress != null) {
                val (done, total) = exportProgress
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Building PDF — $done of $total",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onAddPhotos, enabled = !busy) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "Add photos")
                    }
                    OutlinedButton(onClick = onAddFiles, enabled = !busy) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Add files")
                    }
                    Button(
                        onClick = onExport,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Create PDF · $pageCount page${if (pageCount == 1) "" else "s"}")
                    }
                }
            }
        }
    }
}

private val PICKER_MIME_TYPES = arrayOf("image/*", "application/pdf")

private fun sharePdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share PDF")) }
}
