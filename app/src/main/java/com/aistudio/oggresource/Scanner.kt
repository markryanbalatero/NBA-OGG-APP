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
    val offset: Long,
    val size: Long,
    val slotSize: Long,
    val pageCount: Int,
    val fileName: String,
    val pages: List<StreamPage>
)

class StreamBuilder(
    val serial: Int,
    var codec: String,
    var channels: Int?,
    var sampleRate: Int?,
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

                val serial = bufferBuffer.getInt(cursor + 14)
                val existing = found[serial]
                val payloadSize = minOf(end - tableEnd, 64)
                val payloadText = String(remainingBuffer, tableEnd, payloadSize, Charsets.UTF_8)

                var codec = existing?.codec ?: "OGG"
                var channels = existing?.channels
                var sampleRate = existing?.sampleRate

                val vorbis = payloadText.indexOf("vorbis")
                val opus = payloadText.indexOf("OpusHead")
                if (vorbis >= 1) {
                    codec = "Vorbis"
                    val idStart = tableEnd + vorbis - 1
                    if (idStart + 16 <= remainingBuffer.size) {
                        channels = remainingBuffer[idStart + 11].toUByte().toInt()
                        sampleRate = bufferBuffer.getInt(idStart + 12)
                    }
                } else if (opus >= 0) {
                    codec = "Opus"
                    val idStart = tableEnd + opus
                    channels = remainingBuffer[idStart + 9].toUByte().toInt()
                    if (idStart + 16 <= remainingBuffer.size) {
                        sampleRate = bufferBuffer.getInt(idStart + 12)
                    }
                }

                val absoluteOffset = (offset - remainingBuffer.size) + cursor
                val page = StreamPage(absoluteOffset, end - cursor)

                if (existing == null) {
                    found[serial] = StreamBuilder(serial, codec, channels, sampleRate, mutableListOf(page))
                } else {
                    existing.pages.add(page)
                    existing.codec = codec
                    if (channels != null) existing.channels = channels
                    if (sampleRate != null) existing.sampleRate = sampleRate
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
        Stream(
            id = "${entry.serial}-$firstOffset",
            serial = entry.serial,
            codec = entry.codec,
            channels = entry.channels,
            sampleRate = entry.sampleRate,
            offset = firstOffset,
            size = totalSize,
            slotSize = slotSize,
            pageCount = entry.pages.size,
            fileName = "stream-${(index + 1).toString().padStart(2, '0')}.ogg",
            pages = entry.pages
        )
    }
}
