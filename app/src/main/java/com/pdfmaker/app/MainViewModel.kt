package com.pdfmaker.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfmaker.app.core.BuildOptions
import com.pdfmaker.app.core.BuildResult
import com.pdfmaker.app.core.DocItem
import com.pdfmaker.app.core.DocKind
import com.pdfmaker.app.core.DocumentLoader
import com.pdfmaker.app.core.ExtractOptions
import com.pdfmaker.app.core.ExtractResult
import com.pdfmaker.app.core.PageExporter
import com.pdfmaker.app.core.PdfBuilder
import com.pdfmaker.app.core.SortMode
import com.pdfmaker.app.core.ThumbnailLoader
import com.pdfmaker.app.core.ViewMode
import com.pdfmaker.app.core.sortedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val items: List<DocItem> = emptyList(),
    val sortMode: SortMode = SortMode.MANUAL,
    val viewMode: ViewMode = ViewMode.COMPACT,
    val selectedIds: Set<Long> = emptySet(),
    val selectionMode: Boolean = false,
    val options: BuildOptions = BuildOptions(),
    val isImporting: Boolean = false,
    val exportProgress: Pair<Int, Int>? = null
) {
    val totalPages: Int get() = items.sumOf { it.pageCount }
    val allSelected: Boolean get() = items.isNotEmpty() && selectedIds.size == items.size
    val isExporting: Boolean get() = exportProgress != null
    val isBusy: Boolean get() = isImporting || isExporting
}

/** State for the "PDF → Images" tab, which works on one source document at a time. */
data class ExtractState(
    val source: DocItem? = null,
    val options: ExtractOptions = ExtractOptions(),
    val isLoading: Boolean = false,
    val progress: Pair<Int, Int>? = null
) {
    val isBusy: Boolean get() = isLoading || progress != null
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Exported(val uri: Uri, val pageCount: Int, val skipped: List<String>) : UiEvent
    data class ExportedZip(val uri: Uri, val fileCount: Int, val skipped: List<Int>) : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _extractState = MutableStateFlow(ExtractState())
    val extractState: StateFlow<ExtractState> = _extractState.asStateFlow()

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var exportJob: Job? = null
    private var extractJob: Job? = null

    // ---- Importing ------------------------------------------------------------------

    fun addUris(uris: List<Uri>, persist: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isImporting = true)
            val context = getApplication<Application>()
            val existing = _state.value.items.map { it.uri }.toHashSet()

            val loaded = withContext(Dispatchers.IO) {
                uris.filterNot { it in existing }
                    .mapNotNull { DocumentLoader.load(context, it, persist) }
            }

            val duplicates = uris.count { it in existing }
            val unreadable = uris.size - duplicates - loaded.size

            _state.update { current ->
                val merged = current.items + loaded
                current.copy(
                    items = merged.sortedBy(current.sortMode),
                    isImporting = false
                )
            }

            when {
                loaded.isEmpty() && duplicates > 0 ->
                    emit("Already added.")
                unreadable > 0 ->
                    emit("Added ${loaded.size}; $unreadable could not be read.")
                loaded.isNotEmpty() ->
                    emit("Added ${loaded.size} file${if (loaded.size == 1) "" else "s"}.")
            }
        }
    }

    // ---- List editing ---------------------------------------------------------------

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode, items = it.items.sortedBy(mode)) }
    }

    fun toggleViewMode() {
        _state.update { it.copy(viewMode = it.viewMode.toggled()) }
    }

    fun reverseOrder() {
        _state.update { it.copy(items = it.items.reversed(), sortMode = SortMode.MANUAL) }
    }

    fun move(fromIndex: Int, toIndex: Int) {
        _state.update { current ->
            if (fromIndex !in current.items.indices || toIndex !in current.items.indices) {
                return@update current
            }
            val reordered = current.items.toMutableList()
            reordered.add(toIndex, reordered.removeAt(fromIndex))
            current.copy(items = reordered, sortMode = SortMode.MANUAL)
        }
    }

    fun remove(id: Long) {
        ThumbnailLoader.evict(id)
        _state.update { current ->
            current.copy(
                items = current.items.filterNot { it.id == id },
                selectedIds = current.selectedIds - id
            )
        }
        exitSelectionIfEmpty()
    }

    fun removeSelected() {
        val selected = _state.value.selectedIds
        if (selected.isEmpty()) return
        selected.forEach(ThumbnailLoader::evict)
        _state.update { current ->
            current.copy(
                items = current.items.filterNot { it.id in selected },
                selectedIds = emptySet(),
                selectionMode = false
            )
        }
        emitNow("Removed ${selected.size} item${if (selected.size == 1) "" else "s"}.")
    }

    fun clearAll() {
        ThumbnailLoader.clear()
        _state.update {
            it.copy(items = emptyList(), selectedIds = emptySet(), selectionMode = false)
        }
    }

    // ---- Bulk selection -------------------------------------------------------------

    fun startSelection(id: Long) {
        _state.update { it.copy(selectionMode = true, selectedIds = it.selectedIds + id) }
    }

    fun toggleSelection(id: Long) {
        _state.update { current ->
            val next = if (id in current.selectedIds) {
                current.selectedIds - id
            } else {
                current.selectedIds + id
            }
            current.copy(selectedIds = next, selectionMode = next.isNotEmpty())
        }
    }

    fun selectAll() {
        _state.update { current ->
            current.copy(
                selectedIds = current.items.map { it.id }.toSet(),
                selectionMode = current.items.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet(), selectionMode = false) }
    }

    private fun exitSelectionIfEmpty() {
        _state.update {
            if (it.selectedIds.isEmpty()) it.copy(selectionMode = false) else it
        }
    }

    // ---- Options --------------------------------------------------------------------

    fun updateOptions(transform: (BuildOptions) -> BuildOptions) {
        _state.update { it.copy(options = transform(it.options)) }
    }

    // ---- Export ---------------------------------------------------------------------

    fun export(destination: Uri) {
        if (exportJob?.isActive == true) return
        val snapshot = _state.value
        if (snapshot.items.isEmpty()) {
            emitNow("Add at least one file first.")
            return
        }

        exportJob = viewModelScope.launch {
            _state.update { it.copy(exportProgress = 0 to snapshot.items.size) }
            val result = PdfBuilder.build(
                context = getApplication(),
                items = snapshot.items,
                destination = destination,
                options = snapshot.options,
                onProgress = { done, total ->
                    _state.update { it.copy(exportProgress = done to total) }
                }
            )
            _state.update { it.copy(exportProgress = null) }

            when (result) {
                is BuildResult.Success ->
                    events.send(UiEvent.Exported(destination, result.pageCount, result.skipped))

                is BuildResult.Failure -> emit(result.message)
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(exportProgress = null) }
        emitNow("Export cancelled.")
    }

    // ---- PDF → images ---------------------------------------------------------------

    fun setExtractSource(uri: Uri) {
        viewModelScope.launch {
            _extractState.update { it.copy(isLoading = true) }
            val context = getApplication<Application>()
            val item = withContext(Dispatchers.IO) {
                DocumentLoader.load(context, uri, persist = false)
            }

            if (item == null || item.kind != DocKind.PDF) {
                _extractState.update { it.copy(isLoading = false) }
                emit("That file could not be read as a PDF.")
                return@launch
            }

            _extractState.update { current ->
                current.source?.let { ThumbnailLoader.evict(it.id) }
                current.copy(source = item, isLoading = false)
            }
        }
    }

    fun updateExtractOptions(transform: (ExtractOptions) -> ExtractOptions) {
        _extractState.update { it.copy(options = transform(it.options)) }
    }

    fun extractToZip(destination: Uri) {
        if (extractJob?.isActive == true) return
        val snapshot = _extractState.value
        val source = snapshot.source
        if (source == null) {
            emitNow("Choose a PDF first.")
            return
        }

        extractJob = viewModelScope.launch {
            _extractState.update { it.copy(progress = 0 to source.pageCount) }
            val result = PageExporter.toZip(
                context = getApplication(),
                source = source,
                destination = destination,
                options = snapshot.options,
                onProgress = { done, total ->
                    _extractState.update { it.copy(progress = done to total) }
                }
            )
            _extractState.update { it.copy(progress = null) }

            when (result) {
                is ExtractResult.Success ->
                    events.send(UiEvent.ExportedZip(destination, result.fileCount, result.skipped))

                is ExtractResult.Failure -> emit(result.message)
            }
        }
    }

    fun cancelExtract() {
        extractJob?.cancel()
        extractJob = null
        _extractState.update { it.copy(progress = null) }
        emitNow("Export cancelled.")
    }

    // ---- Helpers --------------------------------------------------------------------

    private suspend fun emit(text: String) {
        events.send(UiEvent.Message(text))
    }

    private fun emitNow(text: String) {
        viewModelScope.launch { events.send(UiEvent.Message(text)) }
    }
}
