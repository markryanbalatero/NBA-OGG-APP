package com.aistudio.oggresource

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StreamState(
    val stream: Stream,
    val isSelected: Boolean = false,
    val replacementUri: Uri? = null,
    val replacementSize: Long = 0L
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

    val filteredStreams: StateFlow<List<StreamState>> = combine(_streams, _searchQuery) { streamList, query ->
        if (query.isBlank()) {
            streamList
        } else {
            val q = query.trim().lowercase()
            streamList.filter {
                it.stream.fileName.lowercase().contains(q) ||
                it.stream.codec.lowercase().contains(q) ||
                it.stream.serial.toString().contains(q) ||
                "0x${it.stream.serial.toString(16)}".lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    private val _isReplacing = MutableStateFlow(false)
    val isReplacing: StateFlow<Boolean> = _isReplacing.asStateFlow()

    private val _replaceProgress = MutableStateFlow(0)
    val replaceProgress: StateFlow<Int> = _replaceProgress.asStateFlow()

    private val _currentlyPlaying = MutableStateFlow<Stream?>(null)
    val currentlyPlaying: StateFlow<Stream?> = _currentlyPlaying.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var tempAudioFile: java.io.File? = null

    fun setSourceFile(context: Context, uri: Uri, name: String) {
        _sourceFileUri.value = uri
        _sourceFileName.value = name
        _streams.value = emptyList()
        _searchQuery.value = ""
        stopPlayback()
        viewModelScope.launch {
            _sourceFileSize.value = getUriFileSize(context, uri)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun scanSource(context: Context) {
        val uri = _sourceFileUri.value ?: return
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0
            try {
                val parsedStreams = parseOggFile(context, uri) { progress ->
                    _scanProgress.value = progress
                }
                _streams.value = parsedStreams.map { StreamState(it) }
                _statusMessage.value = "Found ${parsedStreams.size} OGG stream(s)"
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Scan error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun setReplacement(context: Context, streamId: String, replacementUri: Uri?) {
        viewModelScope.launch {
            val size = if (replacementUri != null) getUriFileSize(context, replacementUri) else 0L
            _streams.value = _streams.value.map {
                if (it.stream.id == streamId) {
                    it.copy(replacementUri = replacementUri, replacementSize = size)
                } else {
                    it
                }
            }
            if (replacementUri != null) {
                _statusMessage.value = "Replacement file attached"
            }
        }
    }

    fun playStream(context: Context, stream: Stream) {
        val uri = _sourceFileUri.value ?: return
        if (_currentlyPlaying.value?.id == stream.id && mediaPlayer?.isPlaying == true) {
            pausePlayback()
            return
        }
        
        stopPlayback()
        _currentlyPlaying.value = stream
        
        viewModelScope.launch {
            try {
                tempAudioFile = extractStreamToTempFile(context, uri, stream)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempAudioFile!!.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        _currentlyPlaying.value = null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _currentlyPlaying.value = null
                _statusMessage.value = "Playback failed: Audio format may require native codec"
            }
        }
    }

    fun pausePlayback() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _currentlyPlaying.value = null
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        _currentlyPlaying.value = null
        tempAudioFile?.delete()
        tempAudioFile = null
    }

    fun extractStream(context: Context, stream: Stream, destUri: Uri, onResult: (Boolean) -> Unit) {
        val sourceUri = _sourceFileUri.value ?: return
        viewModelScope.launch {
            try {
                saveStreamToUri(context, sourceUri, stream, destUri)
                _statusMessage.value = "Extracted ${stream.fileName} successfully"
                onResult(true)
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Extraction failed: ${e.localizedMessage ?: "I/O error"}"
                onResult(false)
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
            try {
                replaceStreams(context, sourceUri, destUri, validReplacements) { progress ->
                    _replaceProgress.value = progress
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

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
