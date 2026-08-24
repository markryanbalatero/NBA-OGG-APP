package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class StreamPage(
    val offset: Long,
    val size: Int
)

data class Stream(
    val id: String,
    val serial: Int,
    val codec: String,
    val channels: Int?,
    val sampleRate: Int?,
    val durationMs: Long,
    val embeddedTitle: String? = null,
    val offset: Long,
    val size: Long,
    val slotSize: Long,
    val pageCount: Int,
    val fileName: String,
    val pages: List<StreamPage>
)

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000.0
    return if (totalSeconds < 60.0) {
        String.format("%.1fs", totalSeconds)
    } else {
        val minutes = (totalSeconds / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        String.format("%d:%02d", minutes, seconds)
    }
}

class StreamBuilder(
    val serial: Int,
    var codec: String,
    var channels: Int?,
    var sampleRate: Int?,
    var nominalBitrate: Int? = null,
    var maxGranule: Long = 0L,
    var embeddedTitle: String? = null,
    val pages: MutableList<StreamPage> = mutableListOf()
)

suspend fun parseOggFile(context: Context, uri: Uri, onProgress: (Int) -> Unit): List<Stream> = withContext(Dispatchers.IO) {
    val CHUNK_SIZE = 10 * 1024 * 1024
    val MAX_PAGE = 70000

    val found = mutableMapOf<Int, StreamBuilder>()

    context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
        val fileLength = fd.statSize
        val inputStream = FileInputStream(fd.fileDescriptor)

        var offset = 0L
        var remainingBuffer = ByteArray(0)

        while (offset < fileLength || remainingBuffer.isNotEmpty()) {
            if (remainingBuffer.size < MAX_PAGE && offset < fileLength) {
                val nextEnd = minOf(offset + CHUNK_SIZE, fileLength)
                val bytesToRead = (nextEnd - offset).toInt()
                val chunk = ByteArray(bytesToRead)
                var bytesReadTotal = 0
                while (bytesReadTotal < bytesToRead) {
                    val r = inputStream.read(chunk, bytesReadTotal, bytesToRead - bytesReadTotal)
                    if (r == -1) break
                    bytesReadTotal += r
                }

                val combined = ByteArray(remainingBuffer.size + bytesReadTotal)
                System.arraycopy(remainingBuffer, 0, combined, 0, remainingBuffer.size)
                System.arraycopy(chunk, 0, combined, remainingBuffer.size, bytesReadTotal)

                remainingBuffer = combined
                offset += bytesReadTotal
            }

            if (offset >= fileLength && remainingBuffer.size < 27) {
                break
            }

            val bufferBuffer = ByteBuffer.wrap(remainingBuffer).order(ByteOrder.LITTLE_ENDIAN)
            var cursor = 0

            while (cursor + 27 <= remainingBuffer.size) {
                if (remainingBuffer[cursor] != 79.toByte() ||
                    remainingBuffer[cursor + 1] != 103.toByte() ||
                    remainingBuffer[cursor + 2] != 103.toByte() ||
                    remainingBuffer[cursor + 3] != 83.toByte()
                ) {
                    cursor += 1
                    continue
                }

                val segmentCount = remainingBuffer[cursor + 26].toUByte().toInt()
                val tableEnd = cursor + 27 + segmentCount
                if (tableEnd > remainingBuffer.size) {
                    if (offset >= fileLength) {
                        cursor += 1
                        continue
                    }
                    break
                }

                var payloadLength = 0
                for (index in 0 until segmentCount) {
                    payloadLength += remainingBuffer[cursor + 27 + index].toUByte().toInt()
                }

                val end = tableEnd + payloadLength
                if (end > remainingBuffer.size) {
                    if (offset >= fileLength) {
                        cursor += 1
                        continue
                    }
                    break
                }

                val granule = bufferBuffer.getLong(cursor + 6)
                val serial = bufferBuffer.getInt(cursor + 14)
                val existing = found[serial]
                val payloadSize = minOf(end - tableEnd, 256)
                val payloadText = String(remainingBuffer, tableEnd, payloadSize, Charsets.UTF_8)

                var codec = existing?.codec ?: "OGG"
                var channels = existing?.channels
                var sampleRate = existing?.sampleRate
                var extractedTitle: String? = existing?.embeddedTitle

                val vorbis = payloadText.indexOf("vorbis")
                val opus = payloadText.indexOf("OpusHead")
                val opusTags = payloadText.indexOf("OpusTags")

                var nominalBitrate = existing?.nominalBitrate

                if (vorbis >= 1) {
                    codec = "Vorbis"
                    val idStart = tableEnd + vorbis - 1
                    if (idStart + 24 <= remainingBuffer.size) {
                        channels = remainingBuffer[idStart + 11].toUByte().toInt()
                        val sr = bufferBuffer.getInt(idStart + 12)
                        if (sr in 4000..192000) sampleRate = sr
                        val nb = bufferBuffer.getInt(idStart + 20)
                        if (nb in 8000..1000000) nominalBitrate = nb
                    } else if (idStart + 16 <= remainingBuffer.size) {
                        channels = remainingBuffer[idStart + 11].toUByte().toInt()
                        val sr = bufferBuffer.getInt(idStart + 12)
                        if (sr in 4000..192000) sampleRate = sr
                    }
                } else if (opus >= 0) {
                    codec = "Opus"
                    val idStart = tableEnd + opus
                    if (idStart + 16 <= remainingBuffer.size) {
                        channels = remainingBuffer[idStart + 9].toUByte().toInt()
                        val sr = bufferBuffer.getInt(idStart + 12)
                        sampleRate = if (sr in 4000..192000) sr else 48000
                    }
                }

                // Check for metadata tags in payload
                if (extractedTitle == null && (vorbis >= 0 || opusTags >= 0 || payloadText.contains("TITLE=") || payloadText.contains("EVENT="))) {
                    val titleIdx = payloadText.indexOf("TITLE=", ignoreCase = true)
                    val eventIdx = payloadText.indexOf("EVENT=", ignoreCase = true)
                    val nameIdx = payloadText.indexOf("NAME=", ignoreCase = true)
                    val commentIdx = payloadText.indexOf("COMMENT=", ignoreCase = true)

                    val targetIdx = listOf(titleIdx, eventIdx, nameIdx, commentIdx).filter { it >= 0 }.minOrNull()
                    if (targetIdx != null) {
                        val equalSign = payloadText.indexOf('=', targetIdx)
                        if (equalSign in 0 until payloadText.length - 1) {
                            val candidate = payloadText.substring(equalSign + 1)
                                .takeWhile { it.code in 32..126 && it != '\u0000' && it != '\n' && it != '\r' }
                                .trim()
                            if (candidate.length >= 2) {
                                extractedTitle = candidate
                            }
                        }
                    }
                }

                val absoluteOffset = (offset - remainingBuffer.size) + cursor
                val page = StreamPage(absoluteOffset, end - cursor)
                val validGranule = if (granule != -1L && granule > 0L) granule else 0L

                if (existing == null) {
                    found[serial] = StreamBuilder(
                        serial = serial,
                        codec = codec,
                        channels = channels,
                        sampleRate = sampleRate,
                        nominalBitrate = nominalBitrate,
                        maxGranule = validGranule,
                        embeddedTitle = extractedTitle,
                        pages = mutableListOf(page)
                    )
                } else {
                    existing.pages.add(page)
                    existing.codec = codec
                    if (channels != null) existing.channels = channels
                    if (sampleRate != null) existing.sampleRate = sampleRate
                    if (nominalBitrate != null) existing.nominalBitrate = nominalBitrate
                    if (validGranule > existing.maxGranule) existing.maxGranule = validGranule
                    if (extractedTitle != null && existing.embeddedTitle == null) existing.embeddedTitle = extractedTitle
                }

                cursor = end
            }

            if (cursor < remainingBuffer.size) {
                remainingBuffer = remainingBuffer.copyOfRange(cursor, remainingBuffer.size)
            } else {
                remainingBuffer = ByteArray(0)
            }

            if (offset >= fileLength && cursor == 0) {
                break
            }

            onProgress(((offset.toFloat() / fileLength) * 90).toInt())
        }
    }

    found.values.mapIndexed { index, entry ->
        val totalSize = entry.pages.sumOf { it.size.toLong() }
        val firstOffset = entry.pages.first().offset
        val lastPage = entry.pages.last()
        val slotSize = (lastPage.offset + lastPage.size) - firstOffset

        val rate = entry.sampleRate ?: 44100
        val durationMs = if (entry.codec.equals("Opus", ignoreCase = true) && entry.maxGranule > 0) {
            (entry.maxGranule * 1000L) / 48000L
        } else if (entry.maxGranule > 0 && rate > 0) {
            (entry.maxGranule * 1000L) / rate
        } else if ((entry.nominalBitrate ?: 0) > 0) {
            val bitrate = entry.nominalBitrate!!.toLong()
            ((totalSize * 8 * 1000L) / bitrate).coerceAtLeast(100L)
        } else {
            // Estimate based on size and channels (game audio averages ~128kbps stereo, ~64kbps mono)
            val assumedBitrate = if ((entry.channels ?: 2) == 1) 64000L else 128000L
            ((totalSize * 8 * 1000L) / assumedBitrate).coerceAtLeast(100L)
        }

        val baseName = entry.embeddedTitle?.let { "${it.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.ogg" }
            ?: "stream-${(index + 1).toString().padStart(2, '0')}.ogg"

        Stream(
            id = "${entry.serial}-$firstOffset",
            serial = entry.serial,
            codec = entry.codec,
            channels = entry.channels,
            sampleRate = entry.sampleRate,
            durationMs = durationMs,
            embeddedTitle = entry.embeddedTitle,
            offset = firstOffset,
            size = totalSize,
            slotSize = slotSize,
            pageCount = entry.pages.size,
            fileName = baseName,
            pages = entry.pages
        )
    }
}
