package com.aistudio.oggresource

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Universal Audio Player with Multi-Tier Engine:
 * 1. Primary Engine: Android Media3 ExoPlayer (Full Ogg/Vorbis/Opus/FLAC support, seeking, container demuxing)
 * 2. Fallback Engine 1: Android MediaPlayer
 * 3. Fallback Engine 2: MediaExtractor + MediaCodec + AudioTrack
 */
class UniversalAudioPlayer {

    private val tag = "UniversalAudioPlayer"

    private var exoPlayer: ExoPlayer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var fallbackJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Player state
    private var isPlayingState = false
    private var isLoopingState = false
    private var currentDurationMs = 0
    private var currentPositionMs = 0

    private var currentTempFile: File? = null

    interface PlayerListener {
        fun onPrepared(durationMs: Int)
        fun onProgress(positionMs: Int, durationMs: Int)
        fun onCompletion()
        fun onError(errorMessage: String)
    }

    private var listener: PlayerListener? = null

    fun setListener(listener: PlayerListener?) {
        this.listener = listener
    }

    fun isPlaying(): Boolean = isPlayingState
    fun isLooping(): Boolean = isLoopingState
    fun getDuration(): Int = currentDurationMs
    fun getCurrentPosition(): Int = currentPositionMs

    fun setLooping(loop: Boolean) {
        isLoopingState = loop
        exoPlayer?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        mediaPlayer?.isLooping = loop
    }

    fun playFile(
        context: Context,
        file: File,
        loop: Boolean = false,
        listener: PlayerListener? = null
    ) {
        if (listener != null) this.listener = listener
        isLoopingState = loop
        stop()

        if (!file.exists() || file.length() == 0L) {
            Log.e(tag, "Audio file does not exist or is empty: ${file.absolutePath}")
            this.listener?.onError("Audio file is empty or not found")
            return
        }

        val fileUri = Uri.fromFile(file)
        startExoPlayer(context, fileUri, file)
    }

    fun playUri(
        context: Context,
        uri: Uri,
        title: String = "audio.ogg",
        loop: Boolean = false,
        listener: PlayerListener? = null
    ) {
        if (listener != null) this.listener = listener
        isLoopingState = loop
        stop()

        // If it's a file URI pointing to an existing file, play directly
        if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
            val file = File(uri.path!!)
            if (file.exists() && file.length() > 0L) {
                playFile(context, file, loop, listener)
                return
            }
        }

        // Try playing the URI directly via ExoPlayer first
        try {
            startExoPlayer(context, uri, null)
        } catch (e: Exception) {
            Log.w(tag, "Direct URI ExoPlayer setup failed, caching to temp file: ${e.message}")
            copyToCacheAndPlay(context, uri, loop)
        }
    }

    private fun copyToCacheAndPlay(context: Context, uri: Uri, loop: Boolean) {
        playerScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "play_temp_${System.currentTimeMillis()}.ogg")
                tempFile.parentFile?.mkdirs()
                currentTempFile = tempFile

                var copied = false
                try {
                    UriUtils.openReadPfd(context, uri)?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        copied = true
                    }
                } catch (e: Exception) {
                    Log.w(tag, "openReadPfd copy failed: ${e.message}")
                }

                if (!copied) {
                    UriUtils.openInputStream(context, uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        playFile(context, tempFile, loop, listener)
                    } else {
                        listener?.onError("Failed to read audio file")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error reading audio URI: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("Playback error: ${e.localizedMessage ?: "File read error"}")
                }
            }
        }
    }

    private fun startExoPlayer(context: Context, uri: Uri, localFile: File?) {
        try {
            val player = ExoPlayer.Builder(context.applicationContext).build()
            exoPlayer = player

            player.repeatMode = if (isLoopingState) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            val duration = player.duration
                            currentDurationMs = if (duration > 0) duration.toInt() else 0
                            isPlayingState = player.isPlaying
                            listener?.onPrepared(currentDurationMs)
                            startProgressTracker()
                        }
                        Player.STATE_ENDED -> {
                            if (!isLoopingState) {
                                isPlayingState = false
                                currentPositionMs = 0
                                listener?.onCompletion()
                            }
                        }
                        Player.STATE_BUFFERING -> {}
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    isPlayingState = isPlaying
                    if (isPlaying) {
                        startProgressTracker()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(tag, "ExoPlayer error: ${error.errorCodeName} (${error.message}). Attempting fallback.")
                    exoPlayer?.release()
                    exoPlayer = null

                    // Fallback to MediaPlayer if we have a local file
                    if (localFile != null && localFile.exists()) {
                        tryMediaPlayer(localFile) { success ->
                            if (!success) {
                                tryMediaExtractorCodecFallback(localFile)
                            }
                        }
                    } else {
                        // Attempt to cache and fallback
                        copyToCacheAndPlay(context, uri, isLoopingState)
                    }
                }
            })

            player.prepare()
            player.play()
            isPlayingState = true
        } catch (e: Exception) {
            Log.w(tag, "ExoPlayer setup failed: ${e.message}. Attempting MediaPlayer fallback.")
            if (localFile != null && localFile.exists()) {
                tryMediaPlayer(localFile) { success ->
                    if (!success) {
                        tryMediaExtractorCodecFallback(localFile)
                    }
                }
            } else {
                copyToCacheAndPlay(context, uri, isLoopingState)
            }
        }
    }

    private fun tryMediaPlayer(file: File, onResult: (Boolean) -> Unit) {
        var preparedSuccessfully = false

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                isLooping = isLoopingState

                setOnPreparedListener { mp ->
                    preparedSuccessfully = true
                    currentDurationMs = mp.duration.coerceAtLeast(0)
                    mp.start()
                    isPlayingState = true
                    listener?.onPrepared(currentDurationMs)
                    startProgressTracker()
                    onResult(true)
                }

                setOnCompletionListener {
                    if (!isLoopingState) {
                        isPlayingState = false
                        currentPositionMs = 0
                        listener?.onCompletion()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Log.w(tag, "MediaPlayer onError: what=$what, extra=$extra")
                    if (!preparedSuccessfully) {
                        try {
                            mediaPlayer?.release()
                            mediaPlayer = null
                        } catch (e: Exception) {}
                        onResult(false)
                    } else {
                        isPlayingState = false
                        listener?.onError("Playback error ($what, $extra)")
                    }
                    true
                }
            }

            mediaPlayer = player
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(tag, "MediaPlayer exception during setup: ${e.message}")
            try {
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (ignored: Exception) {}
            onResult(false)
        }
    }

    private fun tryMediaExtractorCodecFallback(file: File) {
        fallbackJob?.cancel()
        fallbackJob = playerScope.launch(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var audioTrack: AudioTrack? = null

            try {
                extractor.setDataSource(file.absolutePath)
                var audioTrackIndex = -1
                var format: android.media.MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        format = f
                        break
                    }
                }

                if (audioTrackIndex < 0 || format == null) {
                    withContext(Dispatchers.Main) {
                        listener?.onError("Unable to decode audio stream")
                    }
                    return@launch
                }

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: "audio/vorbis"
                val sampleRate = if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) else 44100
                val channelCount = if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) else 2
                val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) format.getLong(android.media.MediaFormat.KEY_DURATION) else 0L

                currentDurationMs = (durationUs / 1000L).toInt()

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()
                isPlayingState = true

                withContext(Dispatchers.Main) {
                    listener?.onPrepared(currentDurationMs)
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var isEos = false
                val timeoutUs = 10000L

                while (isActive && isPlayingState) {
                    if (!isEos) {
                        val inIdx = codec.dequeueInputBuffer(timeoutUs)
                        if (inIdx >= 0) {
                            val inBuffer = codec.getInputBuffer(inIdx)
                            if (inBuffer != null) {
                                val sampleSize = extractor.readSampleData(inBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                    currentPositionMs = (extractor.sampleTime / 1000L).toInt()
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outIdx >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIdx)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.get(chunk)
                            audioTrack.write(chunk, 0, chunk.size)
                        }
                        codec.releaseOutputBuffer(outIdx, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (isLoopingState) {
                                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                codec.flush()
                                isEos = false
                            } else {
                                isPlayingState = false
                                withContext(Dispatchers.Main) {
                                    listener?.onCompletion()
                                }
                                break
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        listener?.onProgress(currentPositionMs, currentDurationMs)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "MediaExtractor fallback error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("Playback error: ${e.localizedMessage ?: "Decoding failed"}")
                }
            } finally {
                try { codec?.stop(); codec?.release() } catch (ignored: Exception) {}
                try { audioTrack?.stop(); audioTrack?.release() } catch (ignored: Exception) {}
                try { extractor.release() } catch (ignored: Exception) {}
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive && isPlayingState) {
                try {
                    exoPlayer?.let { player ->
                        if (player.isPlaying) {
                            currentPositionMs = player.currentPosition.toInt()
                            val duration = player.duration
                            if (duration > 0) {
                                currentDurationMs = duration.toInt()
                            }
                            listener?.onProgress(currentPositionMs, currentDurationMs)
                        }
                    } ?: mediaPlayer?.let { mp ->
                        if (mp.isPlaying) {
                            currentPositionMs = mp.currentPosition
                            if (currentDurationMs <= 0 && mp.duration > 0) {
                                currentDurationMs = mp.duration
                            }
                            listener?.onProgress(currentPositionMs, currentDurationMs)
                        }
                    }
                } catch (e: Exception) {}
                delay(200)
            }
        }
    }

    fun pause() {
        isPlayingState = false
        exoPlayer?.pause()
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {}
    }

    fun resume() {
        isPlayingState = true
        exoPlayer?.play()
        try {
            mediaPlayer?.start()
        } catch (e: Exception) {}
        startProgressTracker()
    }

    fun seekTo(positionMs: Int) {
        currentPositionMs = positionMs
        exoPlayer?.seekTo(positionMs.toLong())
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (e: Exception) {}
    }

    fun stop() {
        isPlayingState = false
        currentPositionMs = 0

        progressJob?.cancel()
        progressJob = null

        fallbackJob?.cancel()
        fallbackJob = null

        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (e: Exception) {}
        exoPlayer = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null

        currentTempFile?.delete()
        currentTempFile = null
    }

    fun release() {
        stop()
        playerScope.cancel()
    }
}
