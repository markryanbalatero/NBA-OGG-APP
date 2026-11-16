package com.aistudio.oggresource

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class OperationType {
    OBB_SCANNING,
    SCANNING,
    BATCH_EXPORT,
    EXTRACTING,
    PATCHING
}

data class GlobalOperation(
    val type: OperationType,
    val title: String,
    val subtitle: String,
    val progress: Int,
    val isIndeterminate: Boolean = false,
    val statusBadge: String? = null
)

enum class FilterCategory(val label: String) {
    ALL("All"),
    REPLACED("With Swap"),
    VORBIS("Vorbis"),
    OPUS("Opus"),
    STEREO("Stereo"),
    MONO("Mono")
}

enum class SortField(val label: String) {
    DEFAULT("Archive Offset"),
    NAME("File Name"),
    SIZE("File Size"),
    SAMPLE_RATE("Sample Rate"),
    CHANNELS("Channels"),
    CODEC("Codec"),
    SERIAL("Serial ID")
}

enum class SortOrder(val label: String) {
    ASCENDING("Ascending (A-Z / Min-Max)"),
    DESCENDING("Descending (Z-A / Max-Min)")
}

data class StreamState(
    val stream: Stream,
    val isSelected: Boolean = false,
    val replacementUri: Uri? = null,
    val replacementName: String? = null,
    val replacementSize: Long = 0L,
    val replacementCodec: String? = null,
    val replacementSampleRate: Int? = null,
    val replacementChannels: Int? = null
)

data class ActiveAudioTrack(
    val title: String,
    val codec: String,
    val details: String,
    val streamId: String? = null,
    val isStaged: Boolean = false
)

class MainViewModel : ViewModel() {
    private val _sourceFileUri = MutableStateFlow<Uri?>(null)
    val sourceFileUri: StateFlow<Uri?> = _sourceFileUri.asStateFlow()

    private val _sourceFileName = MutableStateFlow<String?>(null)
    val sourceFileName: StateFlow<String?> = _sourceFileName.asStateFlow()

    private val _sourceFileSize = MutableStateFlow<Long>(0L)
    val sourceFileSize: StateFlow<Long> = _sourceFileSize.asStateFlow()

    private val _archiveType = MutableStateFlow<ArchiveContainerType>(ArchiveContainerType.RAW_OGG_BANK)
    val archiveType: StateFlow<ArchiveContainerType> = _archiveType.asStateFlow()

    private val _workspaceMode = MutableStateFlow(ArchiveWorkspaceMode.AUDIO_STREAMS)
    val workspaceMode: StateFlow<ArchiveWorkspaceMode> = _workspaceMode.asStateFlow()

    // Nested container navigation stack (e.g. main.obb -> assets/audio.iff)
    private val _containerStack = MutableStateFlow<List<ContainerLayer>>(emptyList())
    val containerStack: StateFlow<List<ContainerLayer>> = _containerStack.asStateFlow()

    // Whole OBB Contents Explorer state
    private val _obbEntries = MutableStateFlow<List<ObbEntry>>(emptyList())
    val obbEntries: StateFlow<List<ObbEntry>> = _obbEntries.asStateFlow()

    private val _currentObbFolder = MutableStateFlow("")
    val currentObbFolder: StateFlow<String> = _currentObbFolder.asStateFlow()

    private val _obbSearchQuery = MutableStateFlow("")
    val obbSearchQuery: StateFlow<String> = _obbSearchQuery.asStateFlow()

    private val _obbFilter = MutableStateFlow(ObbFilterCategory.ALL)
    val obbFilter: StateFlow<ObbFilterCategory> = _obbFilter.asStateFlow()

    private val _obbSortField = MutableStateFlow(ObbSortField.TYPE_THEN_NAME)
    val obbSortField: StateFlow<ObbSortField> = _obbSortField.asStateFlow()

    private val _obbSortAscending = MutableStateFlow(true)
    val obbSortAscending: StateFlow<Boolean> = _obbSortAscending.asStateFlow()

    private val _obbSearchWholeArchive = MutableStateFlow(true)
    val obbSearchWholeArchive: StateFlow<Boolean> = _obbSearchWholeArchive.asStateFlow()

    private val _stagedObbReplacements = MutableStateFlow<Map<String, StagedObbReplacement>>(emptyMap())
    val stagedObbReplacements: StateFlow<Map<String, StagedObbReplacement>> = _stagedObbReplacements.asStateFlow()

    private val _isObbSelectionMode = MutableStateFlow(false)
    val isObbSelectionMode: StateFlow<Boolean> = _isObbSelectionMode.asStateFlow()

    private val _selectedObbPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedObbPaths: StateFlow<Set<String>> = _selectedObbPaths.asStateFlow()

    private val _entryPreview = MutableStateFlow<ObbEntryPreview?>(null)
    val entryPreview: StateFlow<ObbEntryPreview?> = _entryPreview.asStateFlow()

    private val _streams = MutableStateFlow<List<StreamState>>(emptyList())
    val streams: StateFlow<List<StreamState>> = _streams.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(FilterCategory.ALL)
    val selectedFilter: StateFlow<FilterCategory> = _selectedFilter.asStateFlow()

    private val _sortField = MutableStateFlow(SortField.DEFAULT)
    val sortField: StateFlow<SortField> = _sortField.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.ASCENDING)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _exactNumericMode = MutableStateFlow(true)
    val exactNumericMode: StateFlow<Boolean> = _exactNumericMode.asStateFlow()

    val visibleObbEntries: StateFlow<List<ObbEntry>> = combine(
        _obbEntries,
        _currentObbFolder,
        _obbSearchQuery,
        _obbFilter,
        combine(_obbSortField, _obbSortAscending, _obbSearchWholeArchive, _stagedObbReplacements) { sf, asc, whole, staged ->
            listOf(sf, asc, whole, staged)
        }
    ) { entries, folder, query, filter, extra ->
        val sortField = extra[0] as ObbSortField
        val ascending = extra[1] as Boolean
        val searchWhole = extra[2] as Boolean
        @Suppress("UNCHECKED_CAST")
        val stagedMap = extra[3] as Map<String, StagedObbReplacement>

        val q = query.trim().lowercase()
        val isTypeFilter = filter != ObbFilterCategory.ALL && filter != ObbFilterCategory.FOLDERS
        val isGlobalScope = (q.isNotEmpty() && searchWhole) || filter == ObbFilterCategory.MODIFIED || (isTypeFilter && searchWhole)

        var list = if (isGlobalScope) {
            entries.filter { entry ->
                if (filter == ObbFilterCategory.FOLDERS) entry.isDirectory
                else !entry.isDirectory || entry.name.lowercase().contains(q)
            }
        } else {
            entries.filter { it.parentPath == folder }
        }

        if (q.isNotEmpty()) {
            list = list.filter {
                it.name.lowercase().contains(q) || it.path.lowercase().contains(q)
            }
        }

        list = when (filter) {
            ObbFilterCategory.ALL -> list
            ObbFilterCategory.FOLDERS -> list.filter { it.isDirectory }
            ObbFilterCategory.AUDIO -> list.filter { it.fileType == ObbFileType.AUDIO }
            ObbFilterCategory.CONTAINERS -> list.filter { it.fileType == ObbFileType.ARCHIVE_CONTAINER }
            ObbFilterCategory.IMAGES -> list.filter { it.fileType == ObbFileType.IMAGE }
            ObbFilterCategory.TEXT -> list.filter { it.fileType == ObbFileType.TEXT }
            ObbFilterCategory.MODIFIED -> list.filter { stagedMap.containsKey(it.path) }
        }

        val comparator = when (sortField) {
            ObbSortField.TYPE_THEN_NAME -> compareBy<ObbEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
            ObbSortField.NAME -> compareBy<ObbEntry> { it.name.lowercase() }
            ObbSortField.SIZE -> compareBy<ObbEntry> { if (it.isDirectory) it.folderTotalSize else it.uncompressedSize }
            ObbSortField.FILE_TYPE -> compareBy<ObbEntry> { it.fileType.ordinal }.thenBy { it.name.lowercase() }
        }

        if (ascending) list.sortedWith(comparator) else list.sortedWith(comparator.reversed())
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val obbExtensionStats: StateFlow<List<ObbExtensionStat>> = _obbEntries
        .combine(_obbEntries) { entries, _ ->
            entries.filter { !it.isDirectory }
                .groupBy { extractFileExtensionLabel(it.name) }
                .map { (ext, list) ->
                    ObbExtensionStat(
                        extension = ext,
                        fileCount = list.size,
                        totalSize = list.sumOf { it.uncompressedSize }
                    )
                }
                .sortedWith(compareByDescending<ObbExtensionStat> { it.fileCount }.thenBy { it.extension })
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredStreams: StateFlow<List<StreamState>> = combine(
        _streams,
        _searchQuery,
        _selectedFilter,
        _sortField,
        combine(_sortOrder, _exactNumericMode) { order, exact -> Pair(order, exact) }
    ) { streamList, query, filterCategory, sortField, (sortOrder, exactMode) ->
        var list = streamList

        // Apply quick category filter
        list = when (filterCategory) {
            FilterCategory.ALL -> list
            FilterCategory.REPLACED -> list.filter { it.replacementUri != null }
            FilterCategory.VORBIS -> list.filter { it.stream.codec.equals("Vorbis", ignoreCase = true) }
            FilterCategory.OPUS -> list.filter { it.stream.codec.equals("Opus", ignoreCase = true) }
            FilterCategory.STEREO -> list.filter { it.stream.channels == 2 }
            FilterCategory.MONO -> list.filter { it.stream.channels == 1 }
        }

        // Apply search query across metadata, format, and filename
        if (query.isNotBlank()) {
            list = list.filter { item ->
                matchStreamQuery(item, query, exactMode)
            }
        }

        // Apply sorting
        val comparator = when (sortField) {
            SortField.DEFAULT -> compareBy<StreamState> { it.stream.offset }
            SortField.NAME -> compareBy<StreamState> { it.stream.fileName.lowercase() }
            SortField.SIZE -> compareBy<StreamState> { it.stream.size }
            SortField.SAMPLE_RATE -> compareBy<StreamState> { it.stream.sampleRate ?: 0 }
            SortField.CHANNELS -> compareBy<StreamState> { it.stream.channels ?: 0 }
            SortField.CODEC -> compareBy<StreamState> { it.stream.codec.lowercase() }
            SortField.SERIAL -> compareBy<StreamState> { it.stream.serial }
        }

        if (sortOrder == SortOrder.DESCENDING) {
            list.sortedWith(comparator.reversed())
        } else {
            list.sortedWith(comparator)
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    private val _scanDetail = MutableStateFlow("Scanning byte chunks...")
    val scanDetail: StateFlow<String> = _scanDetail.asStateFlow()

    private val _scanTitle = MutableStateFlow("Scanning Archive")
    private val _scanType = MutableStateFlow(OperationType.SCANNING)
    private val _scanIndeterminate = MutableStateFlow(false)
    private val _scanStatusBadge = MutableStateFlow<String?>(null)

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractProgress = MutableStateFlow(0)
    val extractProgress: StateFlow<Int> = _extractProgress.asStateFlow()

    private val _extractDetail = MutableStateFlow("Preparing export...")
    val extractDetail: StateFlow<String> = _extractDetail.asStateFlow()

    private val _extractTitle = MutableStateFlow("Exporting File")
    private val _extractType = MutableStateFlow(OperationType.EXTRACTING)
    private val _extractIndeterminate = MutableStateFlow(false)
    private val _extractStatusBadge = MutableStateFlow<String?>(null)

    private val _isReplacing = MutableStateFlow(false)
    val isReplacing: StateFlow<Boolean> = _isReplacing.asStateFlow()

    private val _replaceProgress = MutableStateFlow(0)
    val replaceProgress: StateFlow<Int> = _replaceProgress.asStateFlow()

    private val _replaceDetail = MutableStateFlow("Patching resource file...")
    val replaceDetail: StateFlow<String> = _replaceDetail.asStateFlow()

    private val _replaceTitle = MutableStateFlow("Patching Archive")
    private val _replaceStatusBadge = MutableStateFlow<String?>(null)

    private val _patchEngineMode = MutableStateFlow(PatchEngineMode.PYTHON_DIRECT_INPLACE)
    val patchEngineMode: StateFlow<PatchEngineMode> = _patchEngineMode.asStateFlow()

    fun setPatchEngineMode(mode: PatchEngineMode) {
        _patchEngineMode.value = mode
    }

    private val scanOperationFlow = combine(
        _isScanning, _scanType, _scanTitle, _scanDetail,
        combine(_scanProgress, _scanIndeterminate, _scanStatusBadge) { prog, indet, badge ->
            Triple(prog, indet, badge)
        }
    ) { active, type, title, detail, (prog, indet, badge) ->
        if (!active) null
        else GlobalOperation(
            type = type,
            title = title,
            subtitle = detail,
            progress = prog,
            isIndeterminate = indet,
            statusBadge = badge
        )
    }

    private val extractOperationFlow = combine(
        _isExtracting, _extractType, _extractTitle, _extractDetail,
        combine(_extractProgress, _extractIndeterminate, _extractStatusBadge) { prog, indet, badge ->
            Triple(prog, indet, badge)
        }
    ) { active, type, title, detail, (prog, indet, badge) ->
        if (!active) null
        else GlobalOperation(
            type = type,
            title = title,
            subtitle = detail,
            progress = prog,
            isIndeterminate = indet,
            statusBadge = badge
        )
    }

    private val replaceOperationFlow = combine(
        _isReplacing, _replaceTitle, _replaceDetail, _replaceProgress, _replaceStatusBadge
    ) { active, title, detail, prog, badge ->
        if (!active) null
        else GlobalOperation(
            type = OperationType.PATCHING,
            title = title,
            subtitle = detail,
            progress = prog,
            isIndeterminate = false,
            statusBadge = badge
        )
    }

    val globalOperation: StateFlow<GlobalOperation?> = combine(
        extractOperationFlow,
        scanOperationFlow,
        replaceOperationFlow
    ) { extractOp, scanOp, replaceOp ->
        extractOp ?: scanOp ?: replaceOp
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Auditioned Recents
    private val _recentStreams = MutableStateFlow<List<Stream>>(emptyList())
    val recentStreams: StateFlow<List<Stream>> = _recentStreams.asStateFlow()

    // Player State
    private val _activeTrack = MutableStateFlow<ActiveAudioTrack?>(null)
    val activeTrack: StateFlow<ActiveAudioTrack?> = _activeTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _isLooping = MutableStateFlow(false)
    val isLooping: StateFlow<Boolean> = _isLooping.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Persistent Audio Converter State (preserves converted file across sheet toggles/auditions)
    private val _converterInputUri = MutableStateFlow<Uri?>(null)
    val converterInputUri: StateFlow<Uri?> = _converterInputUri.asStateFlow()

    private val _converterInputFileName = MutableStateFlow<String?>(null)
    val converterInputFileName: StateFlow<String?> = _converterInputFileName.asStateFlow()

    private val _converterInputFileSize = MutableStateFlow<Long>(0L)
    val converterInputFileSize: StateFlow<Long> = _converterInputFileSize.asStateFlow()

    private val _converterSelectedCodec = MutableStateFlow(AudioCodec.VORBIS)
    val converterSelectedCodec: StateFlow<AudioCodec> = _converterSelectedCodec.asStateFlow()

    private val _converterTargetSizeText = MutableStateFlow("700")
    val converterTargetSizeText: StateFlow<String> = _converterTargetSizeText.asStateFlow()

    private val _converterTargetSizeUnit = MutableStateFlow(FileSizeUnit.KB)
    val converterTargetSizeUnit: StateFlow<FileSizeUnit> = _converterTargetSizeUnit.asStateFlow()

    private val _converterSelectedChannelMode = MutableStateFlow(AudioChannelMode.MONO)
    val converterSelectedChannelMode: StateFlow<AudioChannelMode> = _converterSelectedChannelMode.asStateFlow()

    private val _converterSelectedSampleRate = MutableStateFlow(SampleRateOption.DEFAULT)
    val converterSelectedSampleRate: StateFlow<SampleRateOption> = _converterSelectedSampleRate.asStateFlow()

    private val _isConvertingAudio = MutableStateFlow(false)
    val isConvertingAudio: StateFlow<Boolean> = _isConvertingAudio.asStateFlow()

    private val _conversionProgress = MutableStateFlow(0)
    val conversionProgress: StateFlow<Int> = _conversionProgress.asStateFlow()

    private val _conversionResult = MutableStateFlow<ConversionResult?>(null)
    val conversionResult: StateFlow<ConversionResult?> = _conversionResult.asStateFlow()

    private val _conversionErrorMessage = MutableStateFlow<String?>(null)
    val conversionErrorMessage: StateFlow<String?> = _conversionErrorMessage.asStateFlow()

    private val audioPlayer = UniversalAudioPlayer()
    private var tempAudioFile: File? = null

    init {
        audioPlayer.setListener(object : UniversalAudioPlayer.PlayerListener {
            override fun onPrepared(durationMs: Int) {
                _durationMs.value = durationMs.coerceAtLeast(0)
                _isPlaying.value = true

                _activeTrack.value?.let { track ->
                    if (track.streamId != null) {
                        val streamId = track.streamId
                        _streams.value = _streams.value.map { st ->
                            if (st.stream.id == streamId && durationMs > 0) {
                                val updatedStream = st.stream.copy(durationMs = durationMs.toLong())
                                st.copy(stream = updatedStream)
                            } else st
                        }
                    }
                }
            }

            override fun onProgress(positionMs: Int, durationMs: Int) {
                _currentPositionMs.value = positionMs
                if (durationMs > 0 && _durationMs.value <= 0) {
                    _durationMs.value = durationMs
                }
            }

            override fun onCompletion() {
                if (!_isLooping.value) {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0
                }
            }

            override fun onError(errorMessage: String) {
                _statusMessage.value = errorMessage
                _isPlaying.value = false
            }
        })
    }

    fun setSourceFile(context: Context, uri: Uri, name: String) {
        _containerStack.value = emptyList()
        _sourceFileUri.value = uri
        _sourceFileName.value = name
        _streams.value = emptyList()
        _obbEntries.value = emptyList()
        _currentObbFolder.value = ""
        _obbSearchQuery.value = ""
        _obbFilter.value = ObbFilterCategory.ALL
        _stagedObbReplacements.value = emptyMap()
        _isObbSelectionMode.value = false
        _selectedObbPaths.value = emptySet()
        _recentStreams.value = emptyList()
        _searchQuery.value = ""
        _selectedFilter.value = FilterCategory.ALL
        stopPlayback()
        _isScanning.value = true
        _scanType.value = OperationType.OBB_SCANNING
        _scanTitle.value = "Opening Archive"
        _scanDetail.value = "Inspecting $name..."
        _scanProgress.value = 0
        _scanIndeterminate.value = true
        _scanStatusBadge.value = name
        viewModelScope.launch {
            try {
                _sourceFileSize.value = getUriFileSize(context, uri)
                val detectedType = detectArchiveContainerType(context, uri)
                _archiveType.value = detectedType
                if (detectedType == ArchiveContainerType.ZIP_EXPANSION_OBB) {
                    _workspaceMode.value = ArchiveWorkspaceMode.OBB_CONTENTS
                    loadObbContentsInternal(context, uri, name)
                } else {
                    _workspaceMode.value = ArchiveWorkspaceMode.AUDIO_STREAMS
                    _isScanning.value = false
                    _scanIndeterminate.value = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isScanning.value = false
                _scanIndeterminate.value = false
                _statusMessage.value = "Failed to open $name: ${e.localizedMessage ?: "Error"}"
            }
        }
    }

    private suspend fun loadObbContentsInternal(context: Context, uri: Uri, name: String) {
        _isScanning.value = true
        _scanType.value = OperationType.OBB_SCANNING
        _scanTitle.value = "Scanning OBB File"
        _scanProgress.value = 0
        _scanIndeterminate.value = true
        _scanStatusBadge.value = name
        _scanDetail.value = "Reading OBB directory index..."
        try {
            val entries = ObbArchiveEngine.parseZipDirectory(
                context = context,
                uri = uri,
                onStatusProgress = { progress, detail, indeterminate ->
                    _scanProgress.value = progress
                    _scanDetail.value = detail
                    _scanIndeterminate.value = indeterminate
                }
            )
            _obbEntries.value = entries
            val fileCount = entries.count { !it.isDirectory }
            val folderCount = entries.count { it.isDirectory }
            _scanStatusBadge.value = "$fileCount files"
            _statusMessage.value = "Opened $name: $fileCount file(s) in $folderCount folder(s)"
        } catch (e: Exception) {
            e.printStackTrace()
            _statusMessage.value = "Failed to open OBB directory: ${e.localizedMessage ?: "Unknown error"}"
        } finally {
            _isScanning.value = false
            _scanIndeterminate.value = false
        }
    }

    fun setWorkspaceMode(context: Context, mode: ArchiveWorkspaceMode) {
        _workspaceMode.value = mode
        if (mode == ArchiveWorkspaceMode.AUDIO_STREAMS && _streams.value.isEmpty() && !_isScanning.value) {
            scanSource(context)
        }
    }

    fun navigateToObbFolder(folderPath: String) {
        _currentObbFolder.value = folderPath.trim('/')
    }

    fun navigateObbFolderUp(): Boolean {
        val current = _currentObbFolder.value
        if (current.isEmpty()) return false
        _currentObbFolder.value = current.substringBeforeLast('/', "")
        return true
    }

    fun setObbSearchQuery(query: String) {
        _obbSearchQuery.value = query
    }

    fun setObbFilter(filter: ObbFilterCategory) {
        _obbFilter.value = filter
    }

    fun setObbSortField(field: ObbSortField) {
        _obbSortField.value = field
    }

    fun toggleObbSortDirection() {
        _obbSortAscending.value = !_obbSortAscending.value
    }

    fun toggleObbSearchWholeArchive() {
        _obbSearchWholeArchive.value = !_obbSearchWholeArchive.value
    }

    fun resetObbFilters() {
        _obbSearchQuery.value = ""
        _obbFilter.value = ObbFilterCategory.ALL
        _obbSortField.value = ObbSortField.TYPE_THEN_NAME
        _obbSortAscending.value = true
    }

    fun openObbEntry(context: Context, entry: ObbEntry) {
        if (entry.isDirectory) {
            navigateToObbFolder(entry.path)
            return
        }
        when (entry.fileType) {
            ObbFileType.AUDIO -> playObbAudioEntry(context, entry)
            ObbFileType.ARCHIVE_CONTAINER -> openEntryAsNestedContainer(context, entry)
            ObbFileType.IMAGE, ObbFileType.TEXT, ObbFileType.BINARY -> inspectObbEntry(context, entry)
            ObbFileType.FOLDER -> navigateToObbFolder(entry.path)
        }
    }

    fun playObbAudioEntry(context: Context, entry: ObbEntry) {
        val archiveUri = _sourceFileUri.value ?: return
        val trackId = "obb_entry_${entry.path}"
        if (_activeTrack.value?.streamId == trackId && audioPlayer.isPlaying()) {
            togglePlayPause()
            return
        }
        stopPlayback()
        _activeTrack.value = ActiveAudioTrack(
            title = entry.name,
            codec = entry.name.substringAfterLast('.', "AUDIO").uppercase(),
            details = "${entry.path} · ${formatBytes(entry.uncompressedSize)}",
            streamId = trackId,
            isStaged = false
        )
        viewModelScope.launch {
            _isExtracting.value = true
            _extractType.value = OperationType.EXTRACTING
            _extractTitle.value = "Buffering Audio"
            _extractProgress.value = 0
            _extractIndeterminate.value = false
            _extractStatusBadge.value = formatBytes(entry.uncompressedSize)
            _extractDetail.value = "Buffering ${entry.name} from OBB..."
            try {
                val temp = ObbArchiveEngine.extractEntryToTempFile(context, archiveUri, entry) { prog ->
                    _extractProgress.value = prog
                }
                tempAudioFile = temp
                audioPlayer.playFile(context, temp, _isLooping.value)
            } catch (e: Exception) {
                e.printStackTrace()
                _activeTrack.value = null
                _statusMessage.value = "Cannot play ${entry.name}: ${e.localizedMessage ?: "Unsupported format"}"
            } finally {
                _isExtracting.value = false
                _extractIndeterminate.value = false
            }
        }
    }

    fun inspectObbEntry(context: Context, entry: ObbEntry) {
        val archiveUri = _sourceFileUri.value ?: return
        _entryPreview.value = ObbEntryPreview.Loading
        viewModelScope.launch {
            _entryPreview.value = ObbArchiveEngine.loadEntryPreview(context, archiveUri, entry)
        }
    }

    fun inspectCurrentRawHeader(context: Context) {
        val archiveUri = _sourceFileUri.value ?: return
        val name = _sourceFileName.value ?: "archive.bin"
        val size = _sourceFileSize.value
        _entryPreview.value = ObbEntryPreview.Loading
        viewModelScope.launch {
            _entryPreview.value = ObbArchiveEngine.inspectRawArchiveHeader(context, archiveUri, name, size)
        }
    }

    fun dismissEntryPreview() {
        _entryPreview.value = null
    }

    fun openEntryAsNestedContainer(context: Context, entry: ObbEntry) {
        val parentUri = _sourceFileUri.value ?: return
        dismissEntryPreview()
        viewModelScope.launch {
            _isExtracting.value = true
            _extractType.value = OperationType.EXTRACTING
            _extractTitle.value = "Unpacking Container"
            _extractProgress.value = 0
            _extractIndeterminate.value = false
            _extractStatusBadge.value = formatBytes(entry.uncompressedSize)
            _extractDetail.value = "Opening ${entry.name} inside archive..."
            try {
                val extractedFile = ObbArchiveEngine.extractEntryToTempFile(context, parentUri, entry) { prog ->
                    _extractProgress.value = prog
                    _extractDetail.value = "Unpacking ${entry.name}... $prog%"
                }
                _isExtracting.value = false
                _extractIndeterminate.value = false

                // Push current container state onto stack
                val snapshot = ContainerLayer(
                    id = "${System.currentTimeMillis()}_${_sourceFileName.value}",
                    displayName = _sourceFileName.value ?: "archive.obb",
                    sourceUri = parentUri,
                    sourceSize = _sourceFileSize.value,
                    archiveType = _archiveType.value,
                    workspaceMode = _workspaceMode.value,
                    parentEntryPath = _activeNestedParentPath.value,
                    tempBackingFile = _activeNestedTempFile.value,
                    obbEntries = _obbEntries.value,
                    currentObbFolder = _currentObbFolder.value,
                    streams = _streams.value,
                    stagedObbReplacements = _stagedObbReplacements.value
                )
                _containerStack.value = _containerStack.value + snapshot

                val innerUri = Uri.fromFile(extractedFile)
                _sourceFileUri.value = innerUri
                _sourceFileName.value = entry.name
                _sourceFileSize.value = extractedFile.length()
                _streams.value = emptyList()
                _obbEntries.value = emptyList()
                _currentObbFolder.value = ""
                _obbSearchQuery.value = ""
                _obbFilter.value = ObbFilterCategory.ALL
                _stagedObbReplacements.value = emptyMap()
                _isObbSelectionMode.value = false
                _selectedObbPaths.value = emptySet()
                _recentStreams.value = emptyList()
                _searchQuery.value = ""
                _selectedFilter.value = FilterCategory.ALL
                stopPlayback()

                // Store current nested entry path via temporary marker on stack's top or separate state
                _activeNestedParentPath.value = entry.path
                _activeNestedTempFile.value = extractedFile

                val innerType = detectArchiveContainerType(context, innerUri)
                _archiveType.value = innerType
                if (innerType == ArchiveContainerType.ZIP_EXPANSION_OBB && isZipBasedExtension(entry.name)) {
                    _workspaceMode.value = ArchiveWorkspaceMode.OBB_CONTENTS
                    loadObbContentsInternal(context, innerUri, entry.name)
                } else {
                    _workspaceMode.value = ArchiveWorkspaceMode.AUDIO_STREAMS
                    scanSource(context)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isExtracting.value = false
                _statusMessage.value = "Failed to open ${entry.name}: ${e.localizedMessage ?: "Error"}"
            }
        }
    }

    private val _activeNestedParentPath = MutableStateFlow<String?>(null)
    val activeNestedParentPath: StateFlow<String?> = _activeNestedParentPath.asStateFlow()

    private val _activeNestedTempFile = MutableStateFlow<File?>(null)

    fun hasNestedPendingChanges(): Boolean {
        if (_containerStack.value.isEmpty()) return false
        val hasStreamSwaps = _streams.value.any { it.replacementUri != null }
        val hasObbSwaps = _stagedObbReplacements.value.isNotEmpty()
        return hasStreamSwaps || hasObbSwaps
    }

    fun popNestedContainer(context: Context, commitChangesToParent: Boolean) {
        val stack = _containerStack.value
        if (stack.isEmpty()) return

        val parentLayer = stack.last()
        val entryPath = _activeNestedParentPath.value
        val nestedUri = _sourceFileUri.value
        val nestedName = _sourceFileName.value ?: "nested.bin"
        val nestedArchiveType = _archiveType.value
        val nestedObbEntries = _obbEntries.value
        val nestedObbSwaps = _stagedObbReplacements.value
        val nestedStreamSwaps = _streams.value
            .filter { it.replacementUri != null && (it.replacementSize <= it.stream.slotSize || it.stream.slotSize <= 0L) }
            .associate { it.stream to it.replacementUri!! }

        if (commitChangesToParent && entryPath != null && nestedUri != null &&
            (nestedStreamSwaps.isNotEmpty() || nestedObbSwaps.isNotEmpty())
        ) {
            viewModelScope.launch {
                _isReplacing.value = true
                _replaceTitle.value = "Updating Embedded File"
                _replaceStatusBadge.value = nestedName
                _replaceProgress.value = 0
                _replaceDetail.value = "Applying changes to $nestedName..."
                try {
                    val outDir = File(context.cacheDir, "obb_patched_nested").apply { mkdirs() }
                    val patchedFile = File(outDir, "patched_${System.currentTimeMillis()}_$nestedName")
                    val patchedUri = Uri.fromFile(patchedFile)

                    if (nestedArchiveType == ArchiveContainerType.ZIP_EXPANSION_OBB && nestedObbSwaps.isNotEmpty()) {
                        ObbArchiveEngine.patchZipObb(
                            context = context,
                            sourceUri = nestedUri,
                            destUri = patchedUri,
                            entries = nestedObbEntries,
                            replacements = nestedObbSwaps
                        ) { _replaceProgress.value = it }
                    } else if (nestedStreamSwaps.isNotEmpty()) {
                        replaceStreams(
                            context = context,
                            sourceUri = nestedUri,
                            destUri = patchedUri,
                            streamsToReplace = nestedStreamSwaps,
                            engineMode = _patchEngineMode.value
                        ) { _replaceProgress.value = it }
                    }

                    restoreParentLayer(parentLayer, stack.dropLast(1))
                    if (patchedFile.exists() && patchedFile.length() > 0L) {
                        val updatedMap = _stagedObbReplacements.value.toMutableMap()
                        updatedMap[entryPath] = StagedObbReplacement(
                            entryPath = entryPath,
                            replacementFile = patchedFile,
                            replacementName = nestedName,
                            replacementSize = patchedFile.length(),
                            fromNestedContainer = true
                        )
                        _stagedObbReplacements.value = updatedMap
                        _statusMessage.value = "✓ Staged patched $nestedName into ${parentLayer.displayName}"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _statusMessage.value = "Failed to commit $nestedName: ${e.localizedMessage ?: "Error"}"
                } finally {
                    _isReplacing.value = false
                }
            }
        } else {
            restoreParentLayer(parentLayer, stack.dropLast(1))
        }
    }

    private fun restoreParentLayer(parent: ContainerLayer, remainingStack: List<ContainerLayer>) {
        stopPlayback()
        _containerStack.value = remainingStack
        _sourceFileUri.value = parent.sourceUri
        _sourceFileName.value = parent.displayName
        _sourceFileSize.value = parent.sourceSize
        _archiveType.value = parent.archiveType
        _workspaceMode.value = parent.workspaceMode
        _obbEntries.value = parent.obbEntries
        _currentObbFolder.value = parent.currentObbFolder
        _streams.value = parent.streams
        _stagedObbReplacements.value = parent.stagedObbReplacements
        _activeNestedParentPath.value = parent.parentEntryPath
        _activeNestedTempFile.value = parent.tempBackingFile
        _isObbSelectionMode.value = false
        _selectedObbPaths.value = emptySet()
        _obbSearchQuery.value = ""
        _searchQuery.value = ""
    }

    fun stageObbEntryReplacement(context: Context, entry: ObbEntry, replacementUri: Uri, name: String) {
        viewModelScope.launch {
            val size = getUriFileSize(context, replacementUri)
            val updated = _stagedObbReplacements.value.toMutableMap()
            updated[entry.path] = StagedObbReplacement(
                entryPath = entry.path,
                replacementUri = replacementUri,
                replacementName = name,
                replacementSize = size,
                fromNestedContainer = false
            )
            _stagedObbReplacements.value = updated
            _statusMessage.value = "✓ Staged replacement for ${entry.name}: $name (${formatBytes(size)})"
        }
    }

    fun clearObbEntryReplacement(entryPath: String) {
        val updated = _stagedObbReplacements.value.toMutableMap()
        updated.remove(entryPath)
        _stagedObbReplacements.value = updated
        _statusMessage.value = "Cleared staged replacement"
    }

    fun toggleObbSelectionMode() {
        val next = !_isObbSelectionMode.value
        _isObbSelectionMode.value = next
        if (!next) {
            _selectedObbPaths.value = emptySet()
        }
    }

    fun toggleObbEntrySelection(entry: ObbEntry) {
        val current = _selectedObbPaths.value.toMutableSet()
        if (current.contains(entry.path)) {
            current.remove(entry.path)
        } else {
            current.add(entry.path)
        }
        _selectedObbPaths.value = current
        if (!_isObbSelectionMode.value && current.isNotEmpty()) {
            _isObbSelectionMode.value = true
        }
    }

    fun selectAllVisibleObbEntries() {
        val visiblePaths = visibleObbEntries.value.map { it.path }.toSet()
        _selectedObbPaths.value = _selectedObbPaths.value + visiblePaths
        _isObbSelectionMode.value = true
    }

    fun clearObbSelection() {
        _selectedObbPaths.value = emptySet()
        _isObbSelectionMode.value = false
    }

    fun resolveMatchingEntriesForExtensions(
        selectedExtensions: Set<String>,
        customFilter: String
    ): List<ObbEntry> {
        val customExts = customFilter
            .split(',', ' ', ';', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { token ->
                when {
                    token == "(no ext)" -> token
                    token.startsWith("*.") -> ".${token.removePrefix("*.")}"
                    token.startsWith(".") -> token
                    else -> ".$token"
                }
            }
            .toSet()

        val targetExts = selectedExtensions + customExts
        if (targetExts.isEmpty()) return emptyList()

        return _obbEntries.value.filter { entry ->
            !entry.isDirectory && targetExts.contains(extractFileExtensionLabel(entry.name))
        }
    }

    fun executePendingObbBatchExport(
        context: Context,
        pending: PendingObbBatchExport,
        treeUri: Uri
    ) {
        val archiveUri = _sourceFileUri.value ?: return
        val allEntries = _obbEntries.value
        if (allEntries.isEmpty()) return

        val initialTitle = when (pending) {
            is PendingObbBatchExport.Folder -> if (pending.folderPrefix.isEmpty()) "Exporting Full OBB" else "Exporting OBB Folder"
            is PendingObbBatchExport.Selected -> "Batch Exporting Selected"
            is PendingObbBatchExport.ByExtensions -> "Batch Exporting by Type"
            is PendingObbBatchExport.FilteredVisible -> "Batch Exporting Filtered"
        }

        _isExtracting.value = true
        _extractType.value = OperationType.BATCH_EXPORT
        _extractTitle.value = initialTitle
        _extractProgress.value = 0
        _extractIndeterminate.value = true
        _extractStatusBadge.value = "Preparing"
        _extractDetail.value = "Collecting matching files from OBB..."

        viewModelScope.launch {
            data class ExportPlan(
                val files: List<ObbEntry>,
                val preserveFolders: Boolean,
                val prefixToStrip: String,
                val label: String
            )

            try {
                val plan = withContext(Dispatchers.Default) {
                    val allFiles = allEntries.filter { !it.isDirectory }
                    when (pending) {
                        is PendingObbBatchExport.Folder -> {
                            val list = allFiles.filter {
                                pending.folderPrefix.isEmpty() || it.path.startsWith("${pending.folderPrefix}/")
                            }
                            val lbl = if (pending.folderPrefix.isEmpty()) "entire OBB" else pending.folderPrefix
                            ExportPlan(list, pending.preserveFolders, pending.folderPrefix, lbl)
                        }
                        is PendingObbBatchExport.Selected -> {
                            val dirPrefixes = pending.paths.filter { p ->
                                allEntries.any { it.isDirectory && it.path == p }
                            }
                            val list = allFiles.filter { file ->
                                pending.paths.contains(file.path) || dirPrefixes.any { dir -> file.path.startsWith("$dir/") }
                            }
                            ExportPlan(list, pending.preserveFolders, "", "${list.size} selected file(s)")
                        }
                        is PendingObbBatchExport.ByExtensions -> {
                            val list = resolveMatchingEntriesForExtensions(pending.extensions, pending.customFilter)
                            ExportPlan(list, pending.preserveFolders, "", "${list.size} matching file(s)")
                        }
                        is PendingObbBatchExport.FilteredVisible -> {
                            val visible = visibleObbEntries.value
                            val visibleFiles = visible.filter { !it.isDirectory }.map { it.path }.toSet()
                            val visibleDirs = visible.filter { it.isDirectory }.map { it.path }
                            val list = allFiles.filter { file ->
                                visibleFiles.contains(file.path) || visibleDirs.any { dir -> file.path.startsWith("$dir/") }
                            }
                            ExportPlan(list, pending.preserveFolders, _currentObbFolder.value, "${list.size} filtered file(s)")
                        }
                    }
                }

                val filesToExport = plan.files
                val preserveFolders = plan.preserveFolders
                val prefixToStrip = plan.prefixToStrip
                val label = plan.label

                if (filesToExport.isEmpty()) {
                    _statusMessage.value = "No matching files found to export"
                    return@launch
                }

                _extractStatusBadge.value = "0/${filesToExport.size}"
                _extractDetail.value = "Preparing $label (${filesToExport.size} files)..."

                ObbArchiveEngine.extractEntriesToTree(
                    context = context,
                    archiveUri = archiveUri,
                    entriesToExport = filesToExport,
                    treeUri = treeUri,
                    preserveFolderStructure = preserveFolders,
                    relativePrefixToStrip = prefixToStrip,
                    onStatusProgress = { prog, msg, badge, indeterminate ->
                        _extractProgress.value = prog
                        _extractDetail.value = msg
                        _extractStatusBadge.value = badge
                        _extractIndeterminate.value = indeterminate
                    }
                )
                if (pending is PendingObbBatchExport.Selected) {
                    clearObbSelection()
                }
                _statusMessage.value = "✓ Exported ${filesToExport.size} file(s) ($label)"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Export failed: ${e.localizedMessage ?: "I/O error"}"
            } finally {
                _isExtracting.value = false
                _extractIndeterminate.value = false
            }
        }
    }

    fun extractObbEntry(context: Context, entry: ObbEntry, destUri: Uri) {
        val archiveUri = _sourceFileUri.value ?: return
        viewModelScope.launch {
            _isExtracting.value = true
            _extractType.value = OperationType.EXTRACTING
            _extractTitle.value = "Exporting File"
            _extractProgress.value = 0
            _extractIndeterminate.value = false
            _extractStatusBadge.value = formatBytes(entry.uncompressedSize)
            _extractDetail.value = "Exporting ${entry.name}..."
            try {
                ObbArchiveEngine.extractEntryToUri(context, archiveUri, entry, destUri) { prog ->
                    _extractProgress.value = prog
                    _extractDetail.value = "Exporting ${entry.name}... $prog%"
                }
                _statusMessage.value = "✓ Exported ${entry.name} successfully"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Export failed: ${e.localizedMessage ?: "I/O error"}"
            } finally {
                _isExtracting.value = false
                _extractIndeterminate.value = false
            }
        }
    }

    fun extractObbFolder(context: Context, folderPrefix: String, treeUri: Uri) {
        executePendingObbBatchExport(
            context = context,
            pending = PendingObbBatchExport.Folder(folderPrefix = folderPrefix, preserveFolders = true),
            treeUri = treeUri
        )
    }

    fun savePatchedObbArchive(context: Context, destUri: Uri) {
        val sourceUri = _sourceFileUri.value ?: return
        val entries = _obbEntries.value
        val replacements = _stagedObbReplacements.value
        if (replacements.isEmpty()) {
            _statusMessage.value = "No staged file replacements to save"
            return
        }

        viewModelScope.launch {
            _isReplacing.value = true
            _replaceTitle.value = "Rebuilding OBB Archive"
            _replaceStatusBadge.value = "${replacements.size} modified"
            _replaceProgress.value = 0
            _replaceDetail.value = "Rebuilding OBB archive (${replacements.size} modified entries)..."
            try {
                ObbArchiveEngine.patchZipObb(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    entries = entries,
                    replacements = replacements
                ) { prog ->
                    _replaceProgress.value = prog
                    _replaceDetail.value = "Writing OBB entries & CRC32... $prog%"
                }
                _statusMessage.value = "✓ Saved patched OBB with ${replacements.size} updated file(s)!"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "OBB save failed: ${e.localizedMessage ?: "I/O error"}"
            } finally {
                _isReplacing.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilterCategory(category: FilterCategory) {
        _selectedFilter.value = category
    }

    fun setSortField(field: SortField) {
        _sortField.value = field
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun toggleSortOrder() {
        _sortOrder.value = if (_sortOrder.value == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _selectedFilter.value = FilterCategory.ALL
        _sortField.value = SortField.DEFAULT
        _sortOrder.value = SortOrder.ASCENDING
        _exactNumericMode.value = true
    }

    fun setExactNumericMode(enabled: Boolean) {
        _exactNumericMode.value = enabled
    }

    fun toggleExactNumericMode() {
        _exactNumericMode.value = !_exactNumericMode.value
    }

    fun clearRecentStreams() {
        _recentStreams.value = emptyList()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun scanSource(context: Context) {
        val uri = _sourceFileUri.value ?: return
        viewModelScope.launch {
            _isScanning.value = true
            _scanType.value = OperationType.SCANNING
            _scanTitle.value = "Scanning for OGG Streams"
            _scanProgress.value = 0
            _scanIndeterminate.value = false
            _scanStatusBadge.value = _sourceFileName.value
            _scanDetail.value = "Inspecting archive container structure..."
            try {
                val detectedType = detectArchiveContainerType(context, uri)
                _archiveType.value = detectedType
                val parsedStreams = parseOggFile(context, uri) { progress ->
                    _scanProgress.value = progress
                    _scanDetail.value = if (detectedType == ArchiveContainerType.ZIP_EXPANSION_OBB) {
                        "Reading ZIP expansion entries... $progress%"
                    } else {
                        "Searching OggS markers... $progress%"
                    }
                }
                _streams.value = parsedStreams.map { stream ->
                    StreamState(stream = stream)
                }
                val typeName = if (detectedType == ArchiveContainerType.ZIP_EXPANSION_OBB) "ZIP OBB" else "Raw Bank"
                _statusMessage.value = "Scan complete: Found ${parsedStreams.size} stream(s) in $typeName"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Scan error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isScanning.value = false
                _scanIndeterminate.value = false
            }
        }
    }

    fun playNextStream(context: Context) {
        val currentList = filteredStreams.value
        if (currentList.isEmpty()) return
        val currentStreamId = _activeTrack.value?.streamId?.removePrefix("staged_")
        val currentIndex = currentList.indexOfFirst { it.stream.id == currentStreamId }
        val nextIndex = if (currentIndex in 0 until currentList.size - 1) currentIndex + 1 else 0
        playStream(context, currentList[nextIndex].stream)
    }

    fun playPreviousStream(context: Context) {
        val currentList = filteredStreams.value
        if (currentList.isEmpty()) return
        val currentStreamId = _activeTrack.value?.streamId?.removePrefix("staged_")
        val currentIndex = currentList.indexOfFirst { it.stream.id == currentStreamId }
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else currentList.size - 1
        playStream(context, currentList[prevIndex].stream)
    }

    fun setReplacement(context: Context, streamId: String, replacementUri: Uri?, autoPreview: Boolean = true) {
        viewModelScope.launch {
            if (replacementUri != null) {
                val meta = inspectReplacementOgg(context, replacementUri)
                var updatedStreamState: StreamState? = null

                _streams.value = _streams.value.map {
                    if (it.stream.id == streamId) {
                        val state = it.copy(
                            replacementUri = replacementUri,
                            replacementName = meta.name,
                            replacementSize = meta.size,
                            replacementCodec = meta.codec,
                            replacementSampleRate = meta.sampleRate,
                            replacementChannels = meta.channels
                        )
                        updatedStreamState = state
                        state
                    } else {
                        it
                    }
                }

                val currentStream = _streams.value.find { it.stream.id == streamId }?.stream
                val fits = if (currentStream != null) meta.size <= currentStream.slotSize else true
                val fitMsg = if (fits) "✓ Staged & fits slot" else "⚠ Staged (exceeds slot by ${formatBytes(meta.size - (currentStream?.slotSize ?: 0L))})"

                _statusMessage.value = "$fitMsg: ${meta.name} (${formatBytes(meta.size)})"

                if (autoPreview && updatedStreamState != null) {
                    playReplacementStream(context, updatedStreamState!!)
                }
            } else {
                _streams.value = _streams.value.map {
                    if (it.stream.id == streamId) {
                        it.copy(
                            replacementUri = null,
                            replacementName = null,
                            replacementSize = 0L,
                            replacementCodec = null,
                            replacementSampleRate = null,
                            replacementChannels = null
                        )
                    } else {
                        it
                    }
                }
                if (_activeTrack.value?.streamId == "staged_$streamId") {
                    stopPlayback()
                }
                _statusMessage.value = "Cleared staged replacement"
            }
        }
    }

    fun findDuplicateReplacements(
        targetStreamId: String?,
        uri: Uri?,
        fileName: String?,
        fileSize: Long?
    ): List<StreamState> {
        val currentList = _streams.value
        return currentList.filter { state ->
            if (state.stream.id == targetStreamId || state.replacementUri == null) return@filter false
            val matchesUri = uri != null && state.replacementUri == uri
            val matchesNameAndSize = fileName != null && fileName.isNotBlank() &&
                    state.replacementName?.equals(fileName, ignoreCase = true) == true &&
                    (fileSize == null || fileSize <= 0L || state.replacementSize == fileSize)
            val matchesName = fileName != null && fileName.isNotBlank() &&
                    state.replacementName?.equals(fileName, ignoreCase = true) == true
            matchesUri || matchesNameAndSize || matchesName
        }
    }

    fun getDuplicatesForStream(streamId: String): List<StreamState> {
        val currentList = _streams.value
        val target = currentList.find { it.stream.id == streamId } ?: return emptyList()
        if (target.replacementUri == null) return emptyList()
        return findDuplicateReplacements(
            targetStreamId = streamId,
            uri = target.replacementUri,
            fileName = target.replacementName,
            fileSize = target.replacementSize
        )
    }

    fun playReplacementStream(context: Context, streamState: StreamState) {
        val repUri = streamState.replacementUri ?: return
        val stream = streamState.stream
        val trackId = "staged_${stream.id}"

        if (_activeTrack.value?.streamId == trackId && audioPlayer.isPlaying()) {
            togglePlayPause()
            return
        }

        stopPlayback()
        val repName = streamState.replacementName ?: "replacement.ogg"
        val rateInfo = if (streamState.replacementSampleRate != null) "${streamState.replacementSampleRate} Hz · " else ""
        val chanInfo = if (streamState.replacementChannels != null) {
            if (streamState.replacementChannels == 1) "Mono · " else if (streamState.replacementChannels == 2) "Stereo · " else "${streamState.replacementChannels} ch · "
        } else ""

        _activeTrack.value = ActiveAudioTrack(
            title = "Staged: $repName",
            codec = streamState.replacementCodec ?: "OGG",
            details = "Staged swap for ${stream.fileName} · $rateInfo$chanInfo${formatBytes(streamState.replacementSize)} (Slot: ${formatBytes(stream.slotSize)})",
            streamId = trackId,
            isStaged = true
        )

        audioPlayer.playUri(
            context = context,
            uri = repUri,
            title = repName,
            loop = _isLooping.value
        )
    }

    fun playStream(context: Context, stream: Stream) {
        val uri = _sourceFileUri.value ?: return

        // Update recent audition list (keep last 5 unique)
        _recentStreams.value = (listOf(stream) + _recentStreams.value.filter { it.id != stream.id }).take(5)

        if (_activeTrack.value?.streamId == stream.id && audioPlayer.isPlaying()) {
            togglePlayPause()
            return
        }

        stopPlayback()
        _activeTrack.value = ActiveAudioTrack(
            title = stream.fileName,
            codec = stream.codec,
            details = "${stream.sampleRate ?: "Unknown"} Hz · ${if (stream.channels == 1) "Mono" else if (stream.channels == 2) "Stereo" else "${stream.channels ?: "?"} ch"} · ${formatBytes(stream.size)} · ${formatDuration(stream.durationMs)}",
            streamId = stream.id,
            isStaged = false
        )

        viewModelScope.launch {
            try {
                tempAudioFile = extractStreamToTempFile(context, uri, stream)
                audioPlayer.playFile(context, tempAudioFile!!, _isLooping.value)
            } catch (e: Exception) {
                e.printStackTrace()
                _activeTrack.value = null
                _statusMessage.value = "Playback error: ${e.localizedMessage ?: "Decoding failed"}"
            }
        }
    }

    fun playConvertedFile(context: Context, file: File, title: String) {
        stopPlayback()
        _activeTrack.value = ActiveAudioTrack(
            title = title,
            codec = "OGG / Vorbis",
            details = "Converted audio track · ${formatBytes(file.length())}",
            streamId = null
        )

        audioPlayer.playFile(
            context = context,
            file = file,
            loop = _isLooping.value
        )
    }

    fun playAudioFile(context: Context, uri: Uri, title: String) {
        stopPlayback()
        _activeTrack.value = ActiveAudioTrack(
            title = title,
            codec = "OGG / Audio",
            details = "Standalone audio file",
            streamId = null
        )

        audioPlayer.playUri(
            context = context,
            uri = uri,
            title = title,
            loop = _isLooping.value
        )
    }

    fun togglePlayPause() {
        try {
            if (audioPlayer.isPlaying()) {
                audioPlayer.pause()
                _isPlaying.value = false
            } else {
                audioPlayer.resume()
                _isPlaying.value = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            audioPlayer.seekTo(positionMs)
            _currentPositionMs.value = positionMs
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun rewind(seconds: Int = 5) {
        val current = _currentPositionMs.value
        val target = (current - seconds * 1000).coerceAtLeast(0)
        seekTo(target)
    }

    fun forward(seconds: Int = 5) {
        val current = _currentPositionMs.value
        val total = _durationMs.value
        val target = if (total > 0) (current + seconds * 1000).coerceAtMost(total) else current + seconds * 1000
        seekTo(target)
    }

    fun toggleLoop() {
        val newLoop = !_isLooping.value
        _isLooping.value = newLoop
        audioPlayer.setLooping(newLoop)
    }

    fun pausePlayback() {
        try {
            audioPlayer.pause()
            _isPlaying.value = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopPlayback() {
        try {
            audioPlayer.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _isPlaying.value = false
        _currentPositionMs.value = 0
        _durationMs.value = 0
        _activeTrack.value = null
        tempAudioFile?.delete()
        tempAudioFile = null
    }

    fun extractStream(context: Context, stream: Stream, destUri: Uri, onResult: (Boolean) -> Unit) {
        val sourceUri = _sourceFileUri.value ?: return
        viewModelScope.launch {
            _isExtracting.value = true
            _extractType.value = OperationType.EXTRACTING
            _extractTitle.value = "Extracting Audio Stream"
            _extractProgress.value = 0
            _extractIndeterminate.value = false
            _extractStatusBadge.value = formatBytes(stream.size)
            _extractDetail.value = "Extracting ${stream.fileName}..."
            try {
                saveStreamToUri(context, sourceUri, stream, destUri) { progress ->
                    _extractProgress.value = progress
                    _extractDetail.value = "Extracting ${stream.fileName}... $progress%"
                }
                _statusMessage.value = "Extracted ${stream.fileName} successfully"
                onResult(true)
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Extraction failed: ${e.localizedMessage ?: "I/O error"}"
                onResult(false)
            } finally {
                _isExtracting.value = false
                _extractIndeterminate.value = false
            }
        }
    }

    fun extractMultipleStreams(context: Context, streams: List<Stream>, treeUri: Uri) {
        val sourceUri = _sourceFileUri.value ?: return
        if (streams.isEmpty()) return

        viewModelScope.launch {
            _isExtracting.value = true
            _extractType.value = OperationType.BATCH_EXPORT
            _extractTitle.value = "Batch Exporting Streams"
            _extractProgress.value = 0
            _extractIndeterminate.value = false
            _extractStatusBadge.value = "${streams.size} streams"
            _extractDetail.value = "Preparing export for ${streams.size} streams..."
            try {
                saveMultipleStreamsToTree(context, sourceUri, streams, treeUri) { progress, currentFile ->
                    _extractProgress.value = progress
                    _extractDetail.value = "Exporting $currentFile ($progress%)"
                }
                _statusMessage.value = "Successfully extracted ${streams.size} stream(s)"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Batch extraction failed: ${e.localizedMessage ?: "I/O error"}"
            } finally {
                _isExtracting.value = false
                _extractIndeterminate.value = false
            }
        }
    }

    fun performReplacement(context: Context, destUri: Uri) {
        val sourceUri = _sourceFileUri.value ?: return

        // If we are patching a ZIP OBB in OBB Contents mode or with ZIP entry stream swaps:
        if (_archiveType.value == ArchiveContainerType.ZIP_EXPANSION_OBB && _obbEntries.value.isNotEmpty()) {
            val combinedReplacements = _stagedObbReplacements.value.toMutableMap()
            _streams.value.forEach { state ->
                val path = state.stream.zipEntryPath
                val repUri = state.replacementUri
                if (path != null && repUri != null && !combinedReplacements.containsKey(path)) {
                    combinedReplacements[path] = StagedObbReplacement(
                        entryPath = path,
                        replacementUri = repUri,
                        replacementName = state.replacementName ?: state.stream.fileName,
                        replacementSize = state.replacementSize,
                        fromNestedContainer = false
                    )
                }
            }
            if (combinedReplacements.isNotEmpty()) {
                viewModelScope.launch {
                    _isReplacing.value = true
                    _replaceTitle.value = "Rebuilding OBB Archive"
                    _replaceStatusBadge.value = "${combinedReplacements.size} modified"
                    _replaceProgress.value = 0
                    _replaceDetail.value = "Rebuilding OBB archive (${combinedReplacements.size} file(s))..."
                    try {
                        ObbArchiveEngine.patchZipObb(
                            context = context,
                            sourceUri = sourceUri,
                            destUri = destUri,
                            entries = _obbEntries.value,
                            replacements = combinedReplacements
                        ) { prog ->
                            _replaceProgress.value = prog
                            _replaceDetail.value = "Writing OBB & updating CRC32... $prog%"
                        }
                        _statusMessage.value = "✓ Saved patched OBB with ${combinedReplacements.size} updated file(s)!"
                    } catch (e: Exception) {
                        e.printStackTrace()
                        _statusMessage.value = "OBB save failed: ${e.localizedMessage ?: "I/O error"}"
                    } finally {
                        _isReplacing.value = false
                    }
                }
                return
            }
        }

        val validReplacements = _streams.value
            .filter { it.replacementUri != null && (it.replacementSize <= it.stream.slotSize || it.stream.slotSize <= 0L) }
            .associate { it.stream to it.replacementUri!! }

        if (validReplacements.isEmpty()) {
            _statusMessage.value = "No valid replacements ready to patch"
            return
        }

        viewModelScope.launch {
            _isReplacing.value = true
            _replaceTitle.value = "Patching Audio Streams"
            _replaceStatusBadge.value = "${validReplacements.size} swaps"
            _replaceProgress.value = 0
            _replaceDetail.value = "Patching ${validReplacements.size} stream(s)..."
            try {
                replaceStreams(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    streamsToReplace = validReplacements,
                    engineMode = _patchEngineMode.value
                ) { progress ->
                    _replaceProgress.value = progress
                    _replaceDetail.value = "Python Binary Patching in progress... $progress%"
                }
                _statusMessage.value = "✓ Successfully patched ${validReplacements.size} stream(s) with Python Zero-Shift Mode (100% byte & offset matched)!"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Replacement failed: ${e.localizedMessage ?: "I/O error"}"
            } finally {
                _isReplacing.value = false
            }
        }
    }

    // Converter Actions
    fun setConverterInput(uri: Uri?, name: String?, size: Long) {
        _converterInputUri.value = uri
        _converterInputFileName.value = name
        _converterInputFileSize.value = size
        _conversionErrorMessage.value = null
    }

    fun setConverterCodec(codec: AudioCodec) {
        _converterSelectedCodec.value = codec
    }

    fun setConverterTargetSizeText(text: String) {
        _converterTargetSizeText.value = text
    }

    fun setConverterTargetSizeUnit(unit: FileSizeUnit) {
        _converterTargetSizeUnit.value = unit
    }

    fun setConverterChannelMode(mode: AudioChannelMode) {
        _converterSelectedChannelMode.value = mode
    }

    fun setConverterSampleRate(rate: SampleRateOption) {
        _converterSelectedSampleRate.value = rate
    }

    fun clearConverterResult() {
        _conversionResult.value = null
        _conversionErrorMessage.value = null
    }

    fun startAudioConversion(context: Context) {
        val uri = _converterInputUri.value ?: return
        val name = _converterInputFileName.value ?: "audio.mp3"
        val sizeVal = _converterTargetSizeText.value.toDoubleOrNull() ?: 700.0

        _isConvertingAudio.value = true
        _conversionProgress.value = 0
        _conversionErrorMessage.value = null

        val settings = ConversionSettings(
            codec = _converterSelectedCodec.value,
            targetSizeValue = sizeVal,
            targetSizeUnit = _converterTargetSizeUnit.value,
            channelMode = _converterSelectedChannelMode.value,
            sampleRateOption = _converterSelectedSampleRate.value
        )

        viewModelScope.launch {
            try {
                val result = AudioConverter.convertAudio(
                    context = context,
                    inputUri = uri,
                    originalFileName = name,
                    originalFileSize = _converterInputFileSize.value,
                    settings = settings,
                    onProgress = { _conversionProgress.value = it }
                )
                _conversionResult.value = result
            } catch (e: Exception) {
                e.printStackTrace()
                _conversionErrorMessage.value = "Conversion failed: ${e.localizedMessage ?: e.message}"
            } finally {
                _isConvertingAudio.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        audioPlayer.release()
    }
}

fun matchStreamQuery(
    item: StreamState,
    query: String,
    exactNumericMode: Boolean
): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val lowerQ = q.lowercase()
    val s = item.stream

    // 1. Check for explicit hex serial query like "0x578", "0x1400"
    if (lowerQ.startsWith("0x")) {
        val hexStr = lowerQ.removePrefix("0x")
        val hexVal = hexStr.toLongOrNull(16)
        val sHex = s.serial.toString(16).lowercase()
        return sHex == hexStr || (hexVal != null && s.serial.toLong() == hexVal)
    }

    // 2. Check for prefixed ID / number queries: e.g. "#1400", "id:1400", "stream 1400", "no. 1400"
    val idPrefixRegex = Regex("""^(?:#|id[:\s_-]*|s[:\s_-]*|stream[:\s_-]*|track[:\s_-]*|ogg[:\s_-]*|song[:\s_-]*|no[:.\s_-]*)(\d+)$""", RegexOption.IGNORE_CASE)
    val idPrefixMatch = idPrefixRegex.find(q)
    val pureNumber = idPrefixMatch?.groupValues?.get(1)?.toLongOrNull() ?: q.toLongOrNull()

    if (pureNumber != null) {
        if (exactNumericMode) {
            // Strict exact match for this number:
            // a) Exact 1-based stream index in archive (e.g. #1400)
            if (s.streamIndex.toLong() == pureNumber) return true
            // b) Exact decimal serial
            if (s.serial.toLong() == pureNumber) return true
            // c) Exact hex serial (e.g. serial is 0x1400 or hex representation is "1400")
            if (s.serial.toString(16).equals(pureNumber.toString(), ignoreCase = true)) return true
            // d) Exact discrete number token in filename, zipEntryPath, embeddedTitle, or id
            val filenameNumbers = Regex("""\d+""").findAll(s.fileName).mapNotNull { it.value.toLongOrNull() }
            if (filenameNumbers.any { it == pureNumber }) return true

            val titleNumbers = s.embeddedTitle?.let { title ->
                Regex("""\d+""").findAll(title).mapNotNull { it.value.toLongOrNull() }
            } ?: emptySequence()
            if (titleNumbers.any { it == pureNumber }) return true

            val zipNumbers = s.zipEntryPath?.let { path ->
                val name = path.substringAfterLast('/')
                Regex("""\d+""").findAll(name).mapNotNull { it.value.toLongOrNull() }
            } ?: emptySequence()
            if (zipNumbers.any { it == pureNumber }) return true

            // If user searched a number, do NOT fall through to substring matching on offsets, page counts, or unrelated numbers.
            return false
        }
    }

    // 3. Check for multi-token search with numbers (e.g. "bgm 1400", "stream 1400", "audio 1400")
    val tokens = lowerQ.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
    if (tokens.size > 1) {
        return tokens.all { token ->
            val numToken = token.removePrefix("#").removePrefix("id:").removePrefix("s:").toLongOrNull()
            if (numToken != null && exactNumericMode) {
                // Must match the exact number
                s.streamIndex.toLong() == numToken ||
                s.serial.toLong() == numToken ||
                s.serial.toString(16).equals(token, ignoreCase = true) ||
                Regex("""\d+""").findAll(s.fileName).mapNotNull { it.value.toLongOrNull() }.any { it == numToken } ||
                (s.embeddedTitle?.let { title -> Regex("""\d+""").findAll(title).mapNotNull { it.value.toLongOrNull() }.any { it == numToken } } == true)
            } else {
                matchTextToken(item, token)
            }
        }
    }

    // 4. General text / keyword matching (when not a pure number or when exactNumericMode is off)
    return matchTextToken(item, lowerQ)
}

private fun matchTextToken(item: StreamState, q: String): Boolean {
    val s = item.stream
    val hexSerial = "0x${s.serial.toString(16)}".lowercase()
    val rawHex = s.serial.toString(16).lowercase()
    val decSerial = s.serial.toString()
    val channelText = if (s.channels == 1) "mono" else if (s.channels == 2) "stereo" else "${s.channels} ch"
    val sampleRateStr = s.sampleRate?.toString() ?: ""
    val sampleRateKhz = s.sampleRate?.let { "${it / 1000}k" } ?: ""
    val hasSwap = item.replacementUri != null
    val swapName = item.replacementName?.lowercase() ?: ""

    return s.fileName.lowercase().contains(q) ||
            (s.zipEntryPath?.lowercase()?.contains(q) == true) ||
            s.codec.lowercase().contains(q) ||
            (s.embeddedTitle?.lowercase()?.contains(q) == true) ||
            (q == "mono" && s.channels == 1) ||
            (q == "stereo" && s.channels == 2) ||
            channelText.contains(q) ||
            (q.startsWith("offset") && "offset ${s.offset}".contains(q)) ||
            (q.startsWith("page") && "${s.pageCount} pages".contains(q)) ||
            (q.contains("swap") && hasSwap) ||
            (q.contains("replace") && hasSwap) ||
            swapName.contains(q) ||
            sampleRateStr == q ||
            sampleRateKhz == q ||
            decSerial == q ||
            rawHex == q ||
            hexSerial == q
}
