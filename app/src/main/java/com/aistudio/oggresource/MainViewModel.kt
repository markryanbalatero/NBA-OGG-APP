package com.aistudio.oggresource

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class OperationType {
    SCANNING,
    EXTRACTING,
    PATCHING
}

data class GlobalOperation(
    val type: OperationType,
    val title: String,
    val subtitle: String,
    val progress: Int,
    val isIndeterminate: Boolean = false
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

    val filteredStreams: StateFlow<List<StreamState>> = combine(
        _streams,
        _searchQuery,
        _selectedFilter,
        _sortField,
        _sortOrder
    ) { streamList, query, filterCategory, sortField, sortOrder ->
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
            val q = query.trim().lowercase()
            list = list.filter { item ->
                val s = item.stream
                val hexSerial = "0x${s.serial.toString(16)}".lowercase()
                val rawHex = s.serial.toString(16).lowercase()
                val decSerial = s.serial.toString()
                val channelText = if (s.channels == 1) "mono" else if (s.channels == 2) "stereo" else "${s.channels} ch"
                val sampleRateStr = s.sampleRate?.toString() ?: ""
                val sampleRateKhz = s.sampleRate?.let { "${it / 1000}k" } ?: ""
                val hasSwap = item.replacementUri != null

                s.fileName.lowercase().contains(q) ||
                s.codec.lowercase().contains(q) ||
                (s.embeddedTitle?.lowercase()?.contains(q) == true) ||
                decSerial.contains(q) ||
                rawHex.contains(q) ||
                hexSerial.contains(q) ||
                channelText.contains(q) ||
                sampleRateStr.contains(q) ||
                sampleRateKhz.contains(q) ||
                (q == "mono" && s.channels == 1) ||
                (q == "stereo" && s.channels == 2) ||
                (q.contains("swap") && hasSwap) ||
                (q.contains("replace") && hasSwap) ||
                "${s.pageCount} pages".contains(q) ||
                "offset ${s.offset}".contains(q)
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    private val _scanDetail = MutableStateFlow("Scanning byte chunks...")
    val scanDetail: StateFlow<String> = _scanDetail.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractProgress = MutableStateFlow(0)
    val extractProgress: StateFlow<Int> = _extractProgress.asStateFlow()

    private val _extractDetail = MutableStateFlow("Extracting OGG stream...")
    val extractDetail: StateFlow<String> = _extractDetail.asStateFlow()

    private val _isReplacing = MutableStateFlow(false)
    val isReplacing: StateFlow<Boolean> = _isReplacing.asStateFlow()

    private val _replaceProgress = MutableStateFlow(0)
    val replaceProgress: StateFlow<Int> = _replaceProgress.asStateFlow()

    private val _replaceDetail = MutableStateFlow("Patching resource file...")
    val replaceDetail: StateFlow<String> = _replaceDetail.asStateFlow()

    val globalOperation: StateFlow<GlobalOperation?> = combine(
        _isScanning, _scanProgress, _scanDetail,
        _isExtracting, _extractProgress, _extractDetail,
        _isReplacing, _replaceProgress, _replaceDetail
    ) { params ->
        val scanning = params[0] as Boolean
        val sProgress = params[1] as Int
        val sDetail = params[2] as String

        val extracting = params[3] as Boolean
        val eProgress = params[4] as Int
        val eDetail = params[5] as String

        val replacing = params[6] as Boolean
        val rProgress = params[7] as Int
        val rDetail = params[8] as String

        when {
            extracting -> GlobalOperation(
                type = OperationType.EXTRACTING,
                title = "Extracting Audio Stream",
                subtitle = eDetail,
                progress = eProgress
            )
            scanning -> GlobalOperation(
                type = OperationType.SCANNING,
                title = "Scanning Archive for OGG Streams",
                subtitle = sDetail,
                progress = sProgress
            )
            replacing -> GlobalOperation(
                type = OperationType.PATCHING,
                title = "Patching Resources",
                subtitle = rDetail,
                progress = rProgress
            )
            else -> null
        }
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
        _sourceFileUri.value = uri
        _sourceFileName.value = name
        _streams.value = emptyList()
        _recentStreams.value = emptyList()
        _searchQuery.value = ""
        _selectedFilter.value = FilterCategory.ALL
        stopPlayback()
        viewModelScope.launch {
            _sourceFileSize.value = getUriFileSize(context, uri)
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
            _scanProgress.value = 0
            _scanDetail.value = "Reading archive byte offsets..."
            try {
                val parsedStreams = parseOggFile(context, uri) { progress ->
                    _scanProgress.value = progress
                    _scanDetail.value = "Searching OggS markers... $progress%"
                }
                _streams.value = parsedStreams.map { stream ->
                    StreamState(stream = stream)
                }
                _statusMessage.value = "Scan complete: Found ${parsedStreams.size} OGG stream(s)"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Scan error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isScanning.value = false
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
            _extractProgress.value = 0
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
            }
        }
    }

    fun extractMultipleStreams(context: Context, streams: List<Stream>, treeUri: Uri) {
        val sourceUri = _sourceFileUri.value ?: return
        if (streams.isEmpty()) return

        viewModelScope.launch {
            _isExtracting.value = true
            _extractProgress.value = 0
            _extractDetail.value = "Preparing extraction for ${streams.size} streams..."
            try {
                saveMultipleStreamsToTree(context, sourceUri, streams, treeUri) { progress, currentFile ->
                    _extractProgress.value = progress
                    _extractDetail.value = "Extracting $currentFile ($progress%)"
                }
                _statusMessage.value = "Successfully extracted ${streams.size} stream(s)"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Batch extraction failed: ${e.localizedMessage ?: "I/O error"}"
            } finally {
                _isExtracting.value = false
            }
        }
    }

    fun performReplacement(context: Context, destUri: Uri) {
        val sourceUri = _sourceFileUri.value ?: return
        val validReplacements = _streams.value
            .filter { it.replacementUri != null && it.replacementSize <= it.stream.slotSize }
            .associate { it.stream to it.replacementUri!! }

        if (validReplacements.isEmpty()) {
            _statusMessage.value = "No valid replacements ready to patch"
            return
        }

        viewModelScope.launch {
            _isReplacing.value = true
            _replaceProgress.value = 0
            _replaceDetail.value = "Patching ${validReplacements.size} stream(s)..."
            try {
                replaceStreams(context, sourceUri, destUri, validReplacements) { progress ->
                    _replaceProgress.value = progress
                    _replaceDetail.value = "Patching archive resources... $progress%"
                }
                _statusMessage.value = "Successfully patched ${validReplacements.size} stream(s) into file!"
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
