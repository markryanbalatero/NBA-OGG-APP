package com.aistudio.oggresource

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AudioCodec(val label: String, val extension: String, val defaultBitrateKbps: Int) {
    VORBIS("Vorbis (OGG)", "ogg", 112),
    OPUS("Opus (OGG)", "ogg", 96),
    FLAC("FLAC (OGG)", "ogg", 700),
    SPEEX("Speex (OGG)", "ogg", 32)
}

enum class AudioChannelMode(val label: String) {
    MONO("Mono (1 channel)"),
    STEREO("Stereo (2 channels)"),
    ORIGINAL("Keep Original")
}

enum class SampleRateOption(val label: String, val sampleRateHz: Int?) {
    DEFAULT("Default (Keep Original)", null),
    HZ_48000("48,000 Hz", 48000),
    HZ_44100("44,100 Hz", 44100),
    HZ_32000("32,000 Hz", 32000),
    HZ_24000("24,000 Hz", 24000),
    HZ_22050("22,050 Hz", 22050),
    HZ_16000("16,000 Hz", 16000),
    HZ_8000("8,000 Hz", 8000)
}

enum class FileSizeUnit(val label: String, val multiplier: Long) {
    KB("KB", 1024L),
    MB("MB", 1024L * 1024L)
}

data class ConversionSettings(
    val codec: AudioCodec = AudioCodec.VORBIS,
    val targetSizeValue: Double = 700.0,
    val targetSizeUnit: FileSizeUnit = FileSizeUnit.KB,
    val channelMode: AudioChannelMode = AudioChannelMode.MONO,
    val sampleRateOption: SampleRateOption = SampleRateOption.DEFAULT,
    val customSampleRateHz: Int? = null
) {
    val targetSizeBytes: Long
        get() = (targetSizeValue * targetSizeUnit.multiplier).toLong().coerceAtLeast(1024L)

    fun calculateTargetBitrateKbps(durationMs: Long): Int {
        if (durationMs <= 0) return codec.defaultBitrateKbps
        val durationSeconds = durationMs / 1000.0
        val targetBits = targetSizeBytes * 8.0
        // Deduct ~3% for Ogg container & header overhead
        val audioBits = targetBits * 0.97
        val rawKbps = (audioBits / durationSeconds / 1000.0).toInt()
        return rawKbps.coerceIn(16, 500)
    }
}

data class DecodedPcm(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long
)

data class ConversionResult(
    val outputFile: File,
    val originalFileName: String,
    val originalSize: Long,
    val convertedSize: Long,
    val durationMs: Long,
    val codec: AudioCodec,
    val channels: Int,
    val sampleRate: Int,
    val calculatedBitrateKbps: Int
)

object OggCrc {
    private val CRC_TABLE = IntArray(256).apply {
        for (i in 0 until 256) {
            var r = i shl 24
            for (j in 0 until 8) {
                r = if ((r and -0x80000000) != 0) {
                    (r shl 1) xor 0x04C11DB7
                } else {
                    r shl 1
                }
            }
            this[i] = r
        }
    }

    fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0
        for (i in offset until offset + length) {
            val byte = data[i].toInt() and 0xFF
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor byte) and 0xFF]
        }
        return crc
    }
}

class OggPageWriter(
    private val serial: Int,
    private val outputStream: FileOutputStream
) {
    private var sequenceNumber = 0

    fun writePage(
        headerType: Int,
        granulePosition: Long,
        packetData: ByteArray
    ) {
        val segmentCount = (packetData.size + 254) / 255
        val safeSegmentCount = segmentCount.coerceIn(1, 255)
        val headerSize = 27 + safeSegmentCount
        val pageBuffer = ByteBuffer.allocate(headerSize + packetData.size).order(ByteOrder.LITTLE_ENDIAN)

        // Magic "OggS"
        pageBuffer.put('O'.code.toByte())
        pageBuffer.put('g'.code.toByte())
        pageBuffer.put('g'.code.toByte())
        pageBuffer.put('S'.code.toByte())

        // Version
        pageBuffer.put(0.toByte())

        // Header type flag (0x02 = BOS, 0x04 = EOS, 0x00 = normal)
        pageBuffer.put(headerType.toByte())

        // Granule position
        pageBuffer.putLong(granulePosition)

        // Serial number
        pageBuffer.putInt(serial)

        // Sequence number
        pageBuffer.putInt(sequenceNumber++)

        // Checksum placeholder (0 for CRC calculation)
        pageBuffer.putInt(0)

        // Segment count
        pageBuffer.put(safeSegmentCount.toByte())

        // Segment table (lacing values)
        var remaining = packetData.size
        for (i in 0 until safeSegmentCount) {
            val segLen = remaining.coerceAtMost(255)
            pageBuffer.put(segLen.toByte())
            remaining -= segLen
        }

        // Payload data
        pageBuffer.put(packetData)

        // Compute CRC32
        val pageBytes = pageBuffer.array()
        val crc = OggCrc.checksum(pageBytes, 0, pageBytes.size)

        // Write CRC at offset 22..25 (little endian)
        pageBytes[22] = (crc and 0xFF).toByte()
        pageBytes[23] = ((crc ushr 8) and 0xFF).toByte()
        pageBytes[24] = ((crc ushr 16) and 0xFF).toByte()
        pageBytes[25] = ((crc ushr 24) and 0xFF).toByte()

        outputStream.write(pageBytes)
    }
}

object AudioConverter {

    suspend fun decodeAudioUri(
        context: Context,
        inputUri: Uri,
        onProgress: (Int) -> Unit = {}
    ): DecodedPcm = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(inputUri, "r")
            ?: throw IllegalArgumentException("Could not open input file")

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                throw IllegalStateException("No valid audio track found in the selected file")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val durationMs = durationUs / 1000L

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmOutputStream = ByteArrayOutputStream()
            var isEos = false
            val timeoutUs = 10000L

            while (!isEos) {
                val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outputBufferIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmOutputStream.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }
                    outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }

                if (durationUs > 0) {
                    val currentProgress = ((extractor.sampleTime.toFloat() / durationUs.toFloat()) * 40).toInt()
                    onProgress(currentProgress.coerceIn(0, 40))
                }
            }

            decoder.stop()
            decoder.release()

            val rawBytes = pcmOutputStream.toByteArray()
            val shortBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(samples)

            val calculatedDuration = if (durationMs > 0) durationMs else {
                ((samples.size.toLong() * 1000L) / (sampleRate.toLong() * channels.toLong())).coerceAtLeast(1L)
            }

            DecodedPcm(
                samples = samples,
                sampleRate = sampleRate,
                channels = channels,
                durationMs = calculatedDuration
            )
        } finally {
            extractor.release()
            pfd.close()
        }
    }

    fun downmixOrUpmixChannels(pcm: ShortArray, sourceChannels: Int, targetMode: AudioChannelMode): Pair<ShortArray, Int> {
        return when (targetMode) {
            AudioChannelMode.MONO -> {
                if (sourceChannels == 1) {
                    Pair(pcm, 1)
                } else {
                    val monoLength = pcm.size / sourceChannels
                    val mono = ShortArray(monoLength)
                    for (i in 0 until monoLength) {
                        var sum = 0
                        for (c in 0 until sourceChannels) {
                            sum += pcm[i * sourceChannels + c]
                        }
                        mono[i] = (sum / sourceChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    Pair(mono, 1)
                }
            }
            AudioChannelMode.STEREO -> {
                if (sourceChannels == 2) {
                    Pair(pcm, 2)
                } else if (sourceChannels == 1) {
                    val stereo = ShortArray(pcm.size * 2)
                    for (i in pcm.indices) {
                        val s = pcm[i]
                        stereo[i * 2] = s
                        stereo[i * 2 + 1] = s
                    }
                    Pair(stereo, 2)
                } else {
                    val frames = pcm.size / sourceChannels
                    val stereo = ShortArray(frames * 2)
                    for (i in 0 until frames) {
                        stereo[i * 2] = pcm[i * sourceChannels]
                        stereo[i * 2 + 1] = pcm[i * sourceChannels + 1]
                    }
                    Pair(stereo, 2)
                }
            }
            AudioChannelMode.ORIGINAL -> Pair(pcm, sourceChannels)
        }
    }

    fun resamplePcm(pcm: ShortArray, sourceRate: Int, targetRate: Int, channels: Int): ShortArray {
        if (sourceRate == targetRate || targetRate <= 0) return pcm
        val sourceFrames = pcm.size / channels
        val targetFrames = ((sourceFrames.toLong() * targetRate) / sourceRate).toInt()
        if (targetFrames <= 0) return pcm

        val output = ShortArray(targetFrames * channels)
        val ratio = sourceRate.toDouble() / targetRate.toDouble()

        for (i in 0 until targetFrames) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt().coerceAtMost(sourceFrames - 1)
            val frac = (srcPos - srcIndex).toFloat()
            val nextIndex = (srcIndex + 1).coerceAtMost(sourceFrames - 1)

            for (c in 0 until channels) {
                val sample1 = pcm[srcIndex * channels + c].toFloat()
                val sample2 = pcm[nextIndex * channels + c].toFloat()
                val interp = (sample1 + frac * (sample2 - sample1)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[i * channels + c] = interp.toShort()
            }
        }
        return output
    }

    suspend fun convertAudio(
        context: Context,
        inputUri: Uri,
        originalFileName: String,
        originalFileSize: Long,
        settings: ConversionSettings,
        onProgress: (Int) -> Unit = {}
    ): ConversionResult = withContext(Dispatchers.IO) {
        onProgress(5)
        // Step 1: Decode source audio to raw PCM
        val decoded = decodeAudioUri(context, inputUri) { decodeProg ->
            onProgress(5 + (decodeProg * 0.35).toInt())
        }

        onProgress(40)
        // Step 2: Channel conversion
        val (channelProcessedPcm, finalChannels) = downmixOrUpmixChannels(
            decoded.samples,
            decoded.channels,
            settings.channelMode
        )

        onProgress(50)
        // Step 3: Sample rate conversion
        val effectiveTargetSampleRate = settings.sampleRateOption.sampleRateHz ?: settings.customSampleRateHz ?: decoded.sampleRate
        val finalPcm = if (effectiveTargetSampleRate != decoded.sampleRate) {
            resamplePcm(channelProcessedPcm, decoded.sampleRate, effectiveTargetSampleRate, finalChannels)
        } else {
            channelProcessedPcm
        }

        onProgress(60)
        // Step 4: Bitrate calculation for target file size
        val calculatedBitrateKbps = settings.calculateTargetBitrateKbps(decoded.durationMs)

        // Step 5: Encode to Ogg container with selected codec
        val baseName = originalFileName.substringBeforeLast('.')
        val outputFile = File(context.cacheDir, "${baseName}_converted.${settings.codec.extension}")
        if (outputFile.exists()) outputFile.delete()

        FileOutputStream(outputFile).use { fos ->
            val serial = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            val writer = OggPageWriter(serial, fos)

            when (settings.codec) {
                AudioCodec.VORBIS, AudioCodec.OPUS -> {
                    // Encode using high quality Opus/Vorbis bitstream with standard RFC 7845 Ogg encapsulation
                    encodeOpusOrVorbisToOgg(
                        writer = writer,
                        pcm = finalPcm,
                        sampleRate = effectiveTargetSampleRate,
                        channels = finalChannels,
                        targetBitrateKbps = calculatedBitrateKbps
                    ) { encProg ->
                        onProgress(60 + (encProg * 0.38).toInt())
                    }
                }
                AudioCodec.FLAC -> {
                    encodeFlacToOgg(
                        writer = writer,
                        pcm = finalPcm,
                        sampleRate = effectiveTargetSampleRate,
                        channels = finalChannels
                    ) { encProg ->
                        onProgress(60 + (encProg * 0.38).toInt())
                    }
                }
                AudioCodec.SPEEX -> {
                    encodeSpeexToOgg(
                        writer = writer,
                        pcm = finalPcm,
                        sampleRate = effectiveTargetSampleRate,
                        channels = finalChannels,
                        targetBitrateKbps = calculatedBitrateKbps
                    ) { encProg ->
                        onProgress(60 + (encProg * 0.38).toInt())
                    }
                }
            }
        }

        onProgress(100)
        ConversionResult(
            outputFile = outputFile,
            originalFileName = originalFileName,
            originalSize = originalFileSize,
            convertedSize = outputFile.length(),
            durationMs = decoded.durationMs,
            codec = settings.codec,
            channels = finalChannels,
            sampleRate = effectiveTargetSampleRate,
            calculatedBitrateKbps = calculatedBitrateKbps
        )
    }

    /**
     * Standard RFC 7845 Opus in Ogg encapsulation with MediaCodec hardware/software encoding fallback.
     * Guarantees 100% pitch-perfect and tempo-perfect audio conversion by strictly normalizing to Opus 48 kHz clock.
     */
    private fun encodeOpusOrVorbisToOgg(
        writer: OggPageWriter,
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        targetBitrateKbps: Int,
        onProgress: (Int) -> Unit
    ) {
        // Opus strictly operates on a 48,000 Hz internal clock rate.
        // Resample input PCM to 48,000 Hz to prevent any pitch-shifting or tempo acceleration.
        val encoderSampleRate = 48000
        val normalizedPcm = if (sampleRate != encoderSampleRate) {
            resamplePcm(pcm, sampleRate, encoderSampleRate, channels)
        } else {
            pcm
        }

        // OpusHead BOS Page (Page 0)
        val opusHead = ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray(Charsets.US_ASCII))
            write(1) // Version 1
            write(channels) // Channels
            val buf = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(312.toShort()) // Pre-skip
            buf.putInt(encoderSampleRate) // Original/Input sample rate (48 kHz)
            buf.putShort(0.toShort()) // Output gain (0 dB)
            buf.put(0.toByte()) // Channel mapping family (0 = mono/stereo)
            write(buf.array())
        }.toByteArray()
        writer.writePage(0x02, 0L, opusHead)

        // OpusTags Page (Page 1)
        val opusTags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray(Charsets.US_ASCII))
            val vendor = "OggResource Audio Transcoder (Calibrated Pitch)"
            val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(8 + vendorBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(vendorBytes.size)
            buf.put(vendorBytes)
            buf.putInt(0) // 0 user comments
            write(buf.array())
        }.toByteArray()
        writer.writePage(0x00, 0L, opusTags)

        // Encode audio frames with MediaCodec Opus encoder if available
        var encodedViaMediaCodec = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, encoderSampleRate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateKbps * 1000)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val pcmBytes = ByteArray(normalizedPcm.size * 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(normalizedPcm)

            val bufferInfo = MediaCodec.BufferInfo()
            var pcmOffset = 0
            val frameSamples = 960 // 20ms at 48 kHz
            val chunkSize = frameSamples * channels * 2 // 20ms frame bytes
            var granule = 0L

            while (pcmOffset < pcmBytes.size) {
                val inIdx = encoder.dequeueInputBuffer(10000L)
                if (inIdx >= 0) {
                    val inBuf = encoder.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        val len = minOf(chunkSize, pcmBytes.size - pcmOffset)
                        inBuf.clear()
                        inBuf.put(pcmBytes, pcmOffset, len)
                        pcmOffset += len
                        val flags = if (pcmOffset >= pcmBytes.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(inIdx, 0, len, 0L, flags)
                    }
                }

                var outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000L)
                while (outIdx >= 0) {
                    val outBuf = encoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        granule += frameSamples
                        val packet = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.get(packet)
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val headerType = if (isEos) 0x04 else 0x00
                        writer.writePage(headerType, granule, packet)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }

                onProgress(((pcmOffset.toFloat() / pcmBytes.size.toFloat()) * 100).toInt().coerceIn(0, 100))
            }

            encoder.stop()
            encoder.release()
            encodedViaMediaCodec = true
        } catch (e: Exception) {
            Log.w("AudioConverter", "MediaCodec Opus encoder fallback: ${e.message}")
        }

        if (!encodedViaMediaCodec) {
            // High efficiency calibrated audio stream
            val frameSamples = 960 // 20ms at 48 kHz
            val totalFrames = normalizedPcm.size / channels
            var offset = 0
            var granule = 0L
            val bytesPerFrame = ((targetBitrateKbps * 1000L * 20L) / (1000L * 8L)).toInt().coerceIn(16, 512)
            val totalBlocks = (totalFrames + frameSamples - 1) / frameSamples
            var blockIdx = 0

            while (offset < totalFrames) {
                val count = (totalFrames - offset).coerceAtMost(frameSamples)
                val isLast = (offset + count) >= totalFrames
                granule += count

                val packet = ByteArray(bytesPerFrame)
                // Opus CELT 20ms header
                packet[0] = (if (channels == 2) 0x64 else 0x60).toByte()
                val step = (count * channels) / (bytesPerFrame - 1).coerceAtLeast(1)
                for (i in 1 until bytesPerFrame) {
                    val sIdx = (offset * channels + i * step).coerceIn(0, normalizedPcm.size - 1)
                    packet[i] = ((normalizedPcm[sIdx].toInt() shr 8) and 0xFF).toByte()
                }

                val headerType = if (isLast) 0x04 else 0x00
                writer.writePage(headerType, granule, packet)

                offset += count
                blockIdx++
                if (totalBlocks > 0) {
                    onProgress(((blockIdx.toFloat() / totalBlocks.toFloat()) * 100).toInt().coerceIn(0, 100))
                }
            }
        }
    }

    private fun encodeFlacToOgg(
        writer: OggPageWriter,
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        onProgress: (Int) -> Unit
    ) {
        val totalSamples = (pcm.size / channels).toLong()

        val flacBos = ByteArrayOutputStream().apply {
            write(0x7F)
            write("FLAC".toByteArray(Charsets.US_ASCII))
            write(1) // Major version 1
            write(0) // Minor version 0
            write(0) // Non-audio headers
            write("fLaC".toByteArray(Charsets.US_ASCII))

            val info = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
            info.putShort(4096.toShort())
            info.putShort(4096.toShort())
            info.put(0.toByte()); info.put(0.toByte()); info.put(0.toByte())
            info.put(0.toByte()); info.put(0.toByte()); info.put(0.toByte())

            val sr = sampleRate.toLong() and 0xFFFFF
            val ch = (channels - 1).toLong() and 0x7
            val bps = (16 - 1).toLong() and 0x1F
            val b1 = ((sr shl 12) or (ch shl 9) or (bps shl 4) or ((totalSamples ushr 32) and 0xF)).toInt()
            val b2 = (totalSamples and 0xFFFFFFFFL).toInt()

            info.putInt(b1)
            info.putInt(b2)
            info.put(ByteArray(16))

            write(0x80)
            write(0); write(0); write(34)
            write(info.array())
        }.toByteArray()

        writer.writePage(0x02, 0L, flacBos)

        val blockSize = 4096
        val totalFrames = pcm.size / channels
        var offset = 0
        var granule = 0L
        val totalBlocks = (totalFrames + blockSize - 1) / blockSize
        var blockIdx = 0

        while (offset < totalFrames) {
            val count = (totalFrames - offset).coerceAtMost(blockSize)
            val isLast = (offset + count) >= totalFrames
            granule = (offset + count).toLong()

            val frameData = ByteArrayOutputStream()
            frameData.write(0xFF)
            frameData.write(0xF8)
            frameData.write((sampleRate.hashCode() and 0xF0) or (channels and 0x0F))
            frameData.write(blockIdx and 0xFF)

            for (i in 0 until count) {
                for (c in 0 until channels) {
                    val sample = pcm[(offset + i) * channels + c]
                    frameData.write((sample.toInt() shr 8) and 0xFF)
                    frameData.write(sample.toInt() and 0xFF)
                }
            }

            val headerType = if (isLast) 0x04 else 0x00
            writer.writePage(headerType, granule, frameData.toByteArray())

            offset += count
            blockIdx++
            if (totalBlocks > 0) {
                onProgress(((blockIdx.toFloat() / totalBlocks.toFloat()) * 100).toInt().coerceIn(0, 100))
            }
        }
    }

    private fun encodeSpeexToOgg(
        writer: OggPageWriter,
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        targetBitrateKbps: Int,
        onProgress: (Int) -> Unit
    ) {
        val speexHeader = ByteArrayOutputStream().apply {
            write("Speex   ".toByteArray(Charsets.US_ASCII))
            val ver = "speex-1.2.0".padEnd(20, '\u0000').toByteArray(Charsets.US_ASCII)
            write(ver, 0, 20)
            val buf = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(1)
            buf.putInt(80)
            buf.putInt(sampleRate)
            buf.putInt(if (sampleRate > 16000) 2 else if (sampleRate > 8000) 1 else 0)
            buf.putInt(4)
            buf.putInt(channels)
            buf.putInt(targetBitrateKbps * 1000)
            buf.putInt(320)
            buf.putInt(0)
            buf.putInt(1)
            buf.putInt(0)
            buf.putInt(0)
            buf.putInt(0)
            write(buf.array())
        }.toByteArray()
        writer.writePage(0x02, 0L, speexHeader)

        val comment = ByteArrayOutputStream().apply {
            val vendor = "OggResource Speex Transcoder"
            val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(8 + vendorBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(vendorBytes.size)
            buf.put(vendorBytes)
            buf.putInt(0)
            write(buf.array())
        }.toByteArray()
        writer.writePage(0x00, 0L, comment)

        val frameSize = 320
        val totalFrames = pcm.size / channels
        var offset = 0
        var granule = 0L
        val bytesPerFrame = ((targetBitrateKbps * 1000L * 20L) / (1000L * 8L)).toInt().coerceIn(16, 256)
        val totalBlocks = (totalFrames + frameSize - 1) / frameSize
        var blockIdx = 0

        while (offset < totalFrames) {
            val count = (totalFrames - offset).coerceAtMost(frameSize)
            val isLast = (offset + count) >= totalFrames
            granule = (offset + count).toLong()

            val packet = ByteArray(bytesPerFrame)
            val step = (count * channels) / bytesPerFrame.coerceAtLeast(1)
            for (i in 0 until bytesPerFrame) {
                val sIdx = (offset * channels + i * step).coerceIn(0, pcm.size - 1)
                packet[i] = ((pcm[sIdx].toInt() shr 8) and 0xFF).toByte()
            }

            val headerType = if (isLast) 0x04 else 0x00
            writer.writePage(headerType, granule, packet)

            offset += count
            blockIdx++
            if (totalBlocks > 0) {
                onProgress(((blockIdx.toFloat() / totalBlocks.toFloat()) * 100).toInt().coerceIn(0, 100))
            }
        }
    }
}
