package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

enum class ArchiveContainerType(val label: String, val description: String) {
    ZIP_EXPANSION_OBB("ZIP Expansion OBB", "Standard Android APK expansion / Unity ZIP archive"),
    RAW_OGG_BANK("Raw OGG Container", "Concatenated stream bank / Chained OGG resource")
}

data class StreamPage(
    val offset: Long,
    val size: Int
)

data class Stream(
    val id: String,
    val streamIndex: Int = 1,
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
    val pages: List<StreamPage>,
    val isZipEntry: Boolean = false,
    val zipEntryPath: String? = null,
    val zipCompressionMethod: Int = 0, // 0 = STORED, 8 = DEFLATED
    val zipCrc: Long = 0L,
    val zipLocalHeaderOffset: Long = 0L,
    val zipDataOffset: Long = 0L
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

data class ParsedOggMeta(
    val serial: Int?,
    val codec: String,
    val channels: Int?,
    val sampleRate: Int?,
    val nominalBitrate: Int?,
    val durationMs: Long,
    val embeddedTitle: String?,
    val pageCount: Int
)

fun inspectOggBytes(data: ByteArray, entryName: String): ParsedOggMeta {
    var serial: Int? = null
    var codec = "OGG"
    var channels: Int? = null
    var sampleRate: Int? = null
    var nominalBitrate: Int? = null
    var embeddedTitle: String? = null
    var pageCount = 0
    var maxGranule = 0L

    var cursor = 0
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    while (cursor + 27 <= data.size) {
        if (data[cursor] != 79.toByte() || data[cursor + 1] != 103.toByte() ||
            data[cursor + 2] != 103.toByte() || data[cursor + 3] != 83.toByte()
        ) {
            cursor++
            continue
        }

        pageCount++
        val granule = buf.getLong(cursor + 6)
        if (granule > maxGranule) maxGranule = granule
        val pageSerial = buf.getInt(cursor + 14)
        if (serial == null) serial = pageSerial

        val segmentCount = data[cursor + 26].toUByte().toInt()
        val tableEnd = cursor + 27 + segmentCount
        if (tableEnd > data.size) break

        var payloadLen = 0
        for (i in 0 until segmentCount) {
            payloadLen += data[cursor + 27 + i].toUByte().toInt()
        }

        val end = tableEnd + payloadLen
        if (end > data.size) break

        val inspectLen = minOf(payloadLen, 256)
        if (inspectLen > 0) {
            val text = String(data, tableEnd, inspectLen, Charsets.UTF_8)
            val vorbis = text.indexOf("vorbis")
            val opus = text.indexOf("OpusHead")
            val opusTags = text.indexOf("OpusTags")

            if (vorbis >= 1) {
                codec = "Vorbis"
                val idStart = tableEnd + vorbis - 1
                if (idStart + 24 <= data.size) {
                    channels = data[idStart + 11].toUByte().toInt()
                    val sr = buf.getInt(idStart + 12)
                    if (sr in 4000..192000) sampleRate = sr
                    val nb = buf.getInt(idStart + 20)
                    if (nb in 8000..1000000) nominalBitrate = nb
                }
            } else if (opus >= 0) {
                codec = "Opus"
                val idStart = tableEnd + opus
                if (idStart + 16 <= data.size) {
                    channels = data[idStart + 9].toUByte().toInt()
                    val sr = buf.getInt(idStart + 12)
                    sampleRate = if (sr in 4000..192000) sr else 48000
                }
            }

            if (embeddedTitle == null && (vorbis >= 0 || opusTags >= 0 || text.contains("TITLE=") || text.contains("EVENT="))) {
                val titleIdx = text.indexOf("TITLE=", ignoreCase = true)
                val eventIdx = text.indexOf("EVENT=", ignoreCase = true)
                val nameIdx = text.indexOf("NAME=", ignoreCase = true)
                val targetIdx = listOf(titleIdx, eventIdx, nameIdx).filter { it >= 0 }.minOrNull()
                if (targetIdx != null) {
                    val eq = text.indexOf('=', targetIdx)
                    if (eq in 0 until text.length - 1) {
                        val candidate = text.substring(eq + 1)
                            .takeWhile { it.code in 32..126 && it != '\u0000' && it != '\n' && it != '\r' }
                            .trim()
                        if (candidate.length >= 2) embeddedTitle = candidate
                    }
                }
            }
        }

        cursor = end
    }

    val rate = (sampleRate ?: 44100).coerceAtLeast(1)
    val durationMs = if (codec.equals("Opus", ignoreCase = true) && maxGranule > 0) {
        (maxGranule * 1000L) / 48000L
    } else if (maxGranule > 0) {
        (maxGranule * 1000L) / rate
    } else if ((nominalBitrate ?: 0) > 0) {
        ((data.size.toLong() * 8 * 1000L) / nominalBitrate!!.toLong().coerceAtLeast(1L)).coerceIn(100L, 3600000L)
    } else {
        val assumedBitrate = if ((channels ?: 2) == 1) 64000L else 128000L
        ((data.size.toLong() * 8 * 1000L) / assumedBitrate).coerceIn(100L, 3600000L)
    }

    return ParsedOggMeta(
        serial = serial,
        codec = codec,
        channels = channels,
        sampleRate = sampleRate,
        nominalBitrate = nominalBitrate,
        durationMs = durationMs,
        embeddedTitle = embeddedTitle,
        pageCount = pageCount
    )
}

suspend fun detectArchiveContainerType(context: Context, uri: Uri): ArchiveContainerType = withContext(Dispatchers.IO) {
    if (ObbArchiveEngine.hasZipSignature(context, uri)) {
        return@withContext ArchiveContainerType.ZIP_EXPANSION_OBB
    }
    ArchiveContainerType.RAW_OGG_BANK
}

suspend fun parseZipArchive(context: Context, uri: Uri, onProgress: (Int) -> Unit): List<Stream> = withContext(Dispatchers.IO) {
    val entries = ObbArchiveEngine.parseZipDirectory(context, uri) { prog ->
        onProgress((prog * 0.5f).toInt().coerceIn(5, 45))
    }
    val audioCandidates = entries.filter { !it.isDirectory && it.fileType == ObbFileType.AUDIO }
    val streams = mutableListOf<Stream>()

    UriUtils.openReadPfd(context, uri)?.use { pfd ->
        val channel = FileInputStream(pfd.fileDescriptor).channel
        val headerBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        val total = audioCandidates.size.coerceAtLeast(1)

        audioCandidates.forEachIndexed { idx, entry ->
            var dataOffset = entry.dataOffset
            if (dataOffset < 0L) {
                headerBuf.clear()
                if (channel.read(headerBuf, entry.localHeaderOffset) >= 30) {
                    headerBuf.flip()
                    if (headerBuf.getInt() == 0x04034B50) {
                        val nameLen = headerBuf.getShort(26).toInt() and 0xFFFF
                        val extraLen = headerBuf.getShort(28).toInt() and 0xFFFF
                        dataOffset = entry.localHeaderOffset + 30L + nameLen + extraLen
                    }
                }
            }

            val peekBytes = ByteArray(4096)
            var peekRead = 0
            if (dataOffset >= 0L && entry.compressionMethod == 0) {
                val toRead = minOf(entry.uncompressedSize.coerceAtLeast(0L), 4096L).toInt()
                if (toRead > 0) {
                    val peekBuf = ByteBuffer.wrap(peekBytes, 0, toRead)
                    peekRead = channel.read(peekBuf, dataOffset).coerceAtLeast(0)
                }
            }

            val meta = if (peekRead >= 28 &&
                peekBytes[0] == 79.toByte() && peekBytes[1] == 103.toByte() &&
                peekBytes[2] == 103.toByte() && peekBytes[3] == 83.toByte()
            ) {
                inspectOggBytes(peekBytes.copyOf(peekRead), entry.name)
            } else {
                ParsedOggMeta(
                    serial = idx + 1,
                    codec = entry.name.substringAfterLast('.', "AUDIO").uppercase(),
                    channels = null,
                    sampleRate = null,
                    nominalBitrate = null,
                    durationMs = 0L,
                    embeddedTitle = entry.name.substringBeforeLast('.'),
                    pageCount = 1
                )
            }

            val effectiveSize = entry.uncompressedSize.coerceAtLeast(entry.compressedSize)
            streams.add(
                Stream(
                    id = "zip_${idx}_${entry.path}",
                    streamIndex = idx + 1,
                    serial = meta.serial ?: (idx + 1),
                    codec = meta.codec,
                    channels = meta.channels,
                    sampleRate = meta.sampleRate,
                    durationMs = meta.durationMs,
                    embeddedTitle = meta.embeddedTitle ?: entry.name.substringBeforeLast('.'),
                    offset = if (dataOffset >= 0L) dataOffset else entry.localHeaderOffset,
                    size = effectiveSize,
                    slotSize = effectiveSize,
                    pageCount = meta.pageCount.coerceAtLeast(1),
                    fileName = entry.name,
                    pages = emptyList(),
                    isZipEntry = true,
                    zipEntryPath = entry.path,
                    zipCompressionMethod = entry.compressionMethod,
                    zipCrc = entry.crc,
                    zipLocalHeaderOffset = entry.localHeaderOffset,
                    zipDataOffset = if (dataOffset >= 0L) dataOffset else 0L
                )
            )

            val prog = 45 + (((idx + 1).toFloat() / total.toFloat()) * 45f).toInt()
            onProgress(prog.coerceIn(45, 90))
        }
    }
    streams
}

suspend fun parseOggFile(context: Context, uri: Uri, onProgress: (Int) -> Unit): List<Stream> = withContext(Dispatchers.IO) {
    val containerType = detectArchiveContainerType(context, uri)
    if (containerType == ArchiveContainerType.ZIP_EXPANSION_OBB) {
        val zipStreams = parseZipArchive(context, uri, onProgress)
        if (zipStreams.isNotEmpty()) {
            return@withContext zipStreams
        }
    }

    val CHUNK_SIZE = 10 * 1024 * 1024
    val MAX_PAGE = 70000

    val found = mutableMapOf<Int, StreamBuilder>()

    var lastReportedProgress = -1
    UriUtils.openReadPfd(context, uri)?.use { fd ->
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

            if (fileLength > 0L) {
                val prog = ((offset.toFloat() / fileLength.toFloat()) * 90).toInt().coerceIn(0, 90)
                if (prog != lastReportedProgress) {
                    lastReportedProgress = prog
                    onProgress(prog)
                }
            }
        }
    }

    found.values.mapIndexed { index, entry ->
        val totalSize = entry.pages.sumOf { it.size.toLong() }
        val firstOffset = entry.pages.firstOrNull()?.offset ?: 0L
        val lastPage = entry.pages.lastOrNull()
        val slotSize = if (lastPage != null) {
            ((lastPage.offset + lastPage.size) - firstOffset).coerceAtLeast(totalSize)
        } else totalSize

        val rate = (entry.sampleRate ?: 44100).coerceAtLeast(1)
        val durationMs = if (entry.codec.equals("Opus", ignoreCase = true) && entry.maxGranule > 0) {
            (entry.maxGranule * 1000L) / 48000L
        } else if (entry.maxGranule > 0) {
            (entry.maxGranule * 1000L) / rate
        } else if ((entry.nominalBitrate ?: 0) > 0) {
            val bitrate = entry.nominalBitrate!!.toLong().coerceAtLeast(1L)
            ((totalSize * 8 * 1000L) / bitrate).coerceIn(100L, 3600000L)
        } else {
            // Estimate based on size and channels (game audio averages ~128kbps stereo, ~64kbps mono)
            val assumedBitrate = if ((entry.channels ?: 2) == 1) 64000L else 128000L
            ((totalSize * 8 * 1000L) / assumedBitrate).coerceIn(100L, 3600000L)
        }

        val baseName = entry.embeddedTitle?.let { "${it.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.ogg" }
            ?: "stream-${(index + 1).toString().padStart(2, '0')}.ogg"

        Stream(
            id = "${entry.serial}-$firstOffset",
            streamIndex = index + 1,
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
            pages = entry.pages,
            isZipEntry = false
        )
    }
}

