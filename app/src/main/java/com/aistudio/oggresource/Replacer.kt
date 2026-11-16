package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class StagedOggMeta(
    val name: String,
    val size: Long,
    val codec: String,
    val sampleRate: Int?,
    val channels: Int?
)

enum class PatchEngineMode(val title: String, val subtitle: String) {
    PYTHON_DIRECT_INPLACE(
        "Python Binary In-Place (Recommended)",
        "Zero-shift fixed-offset binary patching with null-byte padding. Preserves 100% of game tables & file size."
    ),
    OGG_SERIAL_SYNC(
        "Auto OGG Serial Sync",
        "Synchronizes OGG bitstream serial and CRC checksums before injecting into the slot."
    )
}

suspend fun inspectReplacementOgg(context: Context, uri: Uri): StagedOggMeta = withContext(Dispatchers.IO) {
    var name = "staged_file.ogg"
    var size = 0L
    var codec = "OGG"
    var sampleRate: Int? = null
    var channels: Int? = null

    try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) {
                    cursor.getString(idx)?.let { name = it }
                }
            }
        }
    } catch (e: Exception) {
        uri.lastPathSegment?.let { name = it }
    }

    try {
        UriUtils.openReadPfd(context, uri)?.use { pfd ->
            size = pfd.statSize
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val headerBytes = ByteArray(1024)
            val read = inputStream.read(headerBytes)
            if (read >= 28 && headerBytes[0] == 79.toByte() && headerBytes[1] == 103.toByte() && headerBytes[2] == 103.toByte() && headerBytes[3] == 83.toByte()) {
                val bytesStr = String(headerBytes, 0, read, Charsets.ISO_8859_1)
                val vorbisIdx = bytesStr.indexOf("\u0001vorbis")
                if (vorbisIdx != -1 && vorbisIdx + 15 < read) {
                    codec = "Vorbis"
                    channels = headerBytes[vorbisIdx + 7].toInt() and 0xFF
                    sampleRate = (headerBytes[vorbisIdx + 8].toInt() and 0xFF) or
                            ((headerBytes[vorbisIdx + 9].toInt() and 0xFF) shl 8) or
                            ((headerBytes[vorbisIdx + 10].toInt() and 0xFF) shl 16) or
                            ((headerBytes[vorbisIdx + 11].toInt() and 0xFF) shl 24)
                } else {
                    val opusIdx = bytesStr.indexOf("OpusHead")
                    if (opusIdx != -1 && opusIdx + 16 < read) {
                        codec = "Opus"
                        channels = headerBytes[opusIdx + 9].toInt() and 0xFF
                        sampleRate = (headerBytes[opusIdx + 12].toInt() and 0xFF) or
                                ((headerBytes[opusIdx + 13].toInt() and 0xFF) shl 8) or
                                ((headerBytes[opusIdx + 14].toInt() and 0xFF) shl 16) or
                                ((headerBytes[opusIdx + 15].toInt() and 0xFF) shl 24)
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    StagedOggMeta(name, size, codec, sampleRate, channels)
}

suspend fun getUriFileSize(context: Context, uri: Uri): Long {
    return withContext(Dispatchers.IO) {
        UriUtils.openReadPfd(context, uri)?.use {
            it.statSize
        } ?: 0L
    }
}

/**
 * Rewrites bitstream serial numbers across all Ogg pages in [inputBytes] to match [targetSerial],
 * ensures the last page has the End-Of-Stream (EOS 0x04) bit flag, and recalculates 100% compliant
 * 32-bit CRCs for each page.
 */
fun rewriteOggStreamSerialAndFixCrc(
    inputBytes: ByteArray,
    targetSerial: Int,
    forceEosOnLastPage: Boolean = true
): ByteArray {
    if (inputBytes.size < 28) return inputBytes
    if (inputBytes[0] != 79.toByte() || inputBytes[1] != 103.toByte() ||
        inputBytes[2] != 103.toByte() || inputBytes[3] != 83.toByte()
    ) {
        return inputBytes
    }

    val output = inputBytes.copyOf()
    var cursor = 0
    val pageRanges = mutableListOf<Pair<Int, Int>>() // Pair(startOffset, length)

    while (cursor + 27 <= output.size) {
        if (output[cursor] != 79.toByte() || output[cursor + 1] != 103.toByte() ||
            output[cursor + 2] != 103.toByte() || output[cursor + 3] != 83.toByte()
        ) {
            cursor++
            continue
        }

        val segmentCount = output[cursor + 26].toInt() and 0xFF
        val tableEnd = cursor + 27 + segmentCount
        if (tableEnd > output.size) break

        var payloadLen = 0
        for (i in 0 until segmentCount) {
            payloadLen += output[cursor + 27 + i].toInt() and 0xFF
        }

        val pageSize = 27 + segmentCount + payloadLen
        if (cursor + pageSize > output.size) break

        pageRanges.add(Pair(cursor, pageSize))
        cursor += pageSize
    }

    if (pageRanges.isEmpty()) return output

    val lastIndex = pageRanges.size - 1
    for ((idx, page) in pageRanges.withIndex()) {
        val start = page.first
        val len = page.second
        val isLast = (idx == lastIndex)

        // Rewrite serial at offset 14..17 (little endian)
        output[start + 14] = (targetSerial and 0xFF).toByte()
        output[start + 15] = ((targetSerial shr 8) and 0xFF).toByte()
        output[start + 16] = ((targetSerial shr 16) and 0xFF).toByte()
        output[start + 17] = ((targetSerial shr 24) and 0xFF).toByte()

        // Set EOS flag (0x04) on header type (byte 5) of the final page
        if (isLast && forceEosOnLastPage) {
            output[start + 5] = (output[start + 5].toInt() or 0x04).toByte()
        }

        // Reset CRC placeholder (bytes 22..25) to zero before computing checksum
        output[start + 22] = 0
        output[start + 23] = 0
        output[start + 24] = 0
        output[start + 25] = 0

        val newCrc = OggCrc.checksum(output, start, len)
        output[start + 22] = (newCrc and 0xFF).toByte()
        output[start + 23] = ((newCrc ushr 8) and 0xFF).toByte()
        output[start + 24] = ((newCrc ushr 16) and 0xFF).toByte()
        output[start + 25] = ((newCrc ushr 24) and 0xFF).toByte()
    }

    return output
}

/**
 * Universal Python-Style Direct In-Place Binary Patcher.
 * Guaranteed:
 * 1. Zero shift: All byte offsets across the entire OBB/IFF archive remain 100% untouched.
 * 2. Total file size is perfectly preserved down to the exact byte.
 * 3. Null-byte padding (0x00) safely fills slot remainders.
 * 4. High-speed 4MB NIO streaming handles large multi-gigabyte archives with minimal RAM.
 */
suspend fun replaceStreams(
    context: Context,
    sourceUri: Uri,
    destUri: Uri,
    streamsToReplace: Map<Stream, Uri>,
    engineMode: PatchEngineMode = PatchEngineMode.PYTHON_DIRECT_INPLACE,
    onProgress: (Int) -> Unit
) = withContext(Dispatchers.IO) {
    val CHUNK_SIZE = 4 * 1024 * 1024

    UriUtils.openReadPfd(context, sourceUri)?.use { sourcePfd ->
        val sourceChannel = FileInputStream(sourcePfd.fileDescriptor).channel
        val fileLength = sourcePfd.statSize

        val destStream = UriUtils.openOutputStream(context, destUri)
            ?: throw IOException("Could not open destination stream for writing")

        val sortedStreams = streamsToReplace.keys.sortedBy { it.offset }

        // Pre-flight check: prepare and validate replacement payloads
        val preparedReplacements = mutableMapOf<Stream, ByteArray>()
        for (stream in sortedStreams) {
            val replacementUri = streamsToReplace[stream] ?: continue
            val repRawBytes = UriUtils.openInputStream(context, replacementUri)?.use { it.readBytes() }
                ?: throw IOException("Could not read replacement file for '${stream.fileName}'")

            val finalBytes = if (engineMode == PatchEngineMode.OGG_SERIAL_SYNC) {
                rewriteOggStreamSerialAndFixCrc(
                    inputBytes = repRawBytes,
                    targetSerial = stream.serial,
                    forceEosOnLastPage = true
                )
            } else {
                repRawBytes
            }

            val slotSize = if (stream.slotSize > 0L) stream.slotSize else stream.size
            if (finalBytes.size > slotSize && slotSize > 0L) {
                throw IOException("Replacement for '${stream.fileName}' (${formatBytes(finalBytes.size.toLong())}) exceeds the fixed slot limit (${formatBytes(slotSize)}). Use the Audio Converter to adjust bitrate or duration.")
            }

            preparedReplacements[stream] = finalBytes
        }

        destStream.use { streamOut ->
            val fos = streamOut as? FileOutputStream
            val destChannel = fos?.channel ?: java.nio.channels.Channels.newChannel(streamOut)

            var currentReadOffset = 0L

            for ((index, stream) in sortedStreams.withIndex()) {
                val patchedBytes = preparedReplacements[stream] ?: continue
                val targetOffset = stream.offset

                // Copy bytes before this stream verbatim
                while (currentReadOffset < targetOffset) {
                    val toRead = minOf(CHUNK_SIZE.toLong(), targetOffset - currentReadOffset).toInt()
                    val buffer = ByteBuffer.allocate(toRead)
                    sourceChannel.read(buffer, currentReadOffset)
                    buffer.flip()
                    destChannel.write(buffer)
                    currentReadOffset += toRead
                }

                // Write replacement payload
                val repBuffer = ByteBuffer.wrap(patchedBytes)
                destChannel.write(repBuffer)
                val replacementBytesWritten = patchedBytes.size.toLong()

                // Pad with zeros (0x00) up to slotSize to guarantee zero offset shifting
                val slotSize = if (stream.slotSize > 0L) stream.slotSize else stream.size
                if (replacementBytesWritten < slotSize) {
                    var remainingPadding = slotSize - replacementBytesWritten
                    val padChunkSize = 64 * 1024
                    val zeroChunk = ByteArray(padChunkSize)
                    while (remainingPadding > 0L) {
                        val toWrite = minOf(remainingPadding, padChunkSize.toLong()).toInt()
                        val zeroBuffer = ByteBuffer.wrap(zeroChunk, 0, toWrite)
                        destChannel.write(zeroBuffer)
                        remainingPadding -= toWrite
                    }
                }

                currentReadOffset = targetOffset + slotSize
                val prog = (((index + 1).toFloat() / (sortedStreams.size + 1).toFloat()) * 90).toInt().coerceIn(0, 90)
                onProgress(prog)
            }

            // Copy remaining bytes of the archive
            while (currentReadOffset < fileLength) {
                val toRead = minOf(CHUNK_SIZE.toLong(), fileLength - currentReadOffset).toInt()
                val buffer = ByteBuffer.allocate(toRead)
                sourceChannel.read(buffer, currentReadOffset)
                buffer.flip()
                destChannel.write(buffer)
                currentReadOffset += toRead
            }

            onProgress(100)
        }
    } ?: throw IOException("Could not open source file for reading")
}
