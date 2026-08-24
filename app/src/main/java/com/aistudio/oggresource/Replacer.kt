package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class StagedOggMeta(
    val name: String,
    val size: Long,
    val codec: String,
    val sampleRate: Int?,
    val channels: Int?
)

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
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
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
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: 0L
    }
}

suspend fun replaceStreams(
    context: Context,
    sourceUri: Uri,
    destUri: Uri,
    streamsToReplace: Map<Stream, Uri>,
    onProgress: (Int) -> Unit
) {
    withContext(Dispatchers.IO) {
        val CHUNK_SIZE = 4 * 1024 * 1024

        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { sourcePfd ->
            val sourceChannel = FileInputStream(sourcePfd.fileDescriptor).channel
            val fileLength = sourcePfd.statSize

            context.contentResolver.openOutputStream(destUri)?.use { destStream ->
                val destChannel = (destStream as FileOutputStream).channel

                val sortedStreams = streamsToReplace.keys.sortedBy { it.offset }
                var currentReadOffset = 0L

                for ((index, stream) in sortedStreams.withIndex()) {
                    val replacementUri = streamsToReplace[stream] ?: continue
                    val targetOffset = stream.offset

                    // Copy bytes before this stream
                    while (currentReadOffset < targetOffset) {
                        val toRead = minOf(CHUNK_SIZE.toLong(), targetOffset - currentReadOffset).toInt()
                        val buffer = ByteBuffer.allocate(toRead)
                        sourceChannel.read(buffer, currentReadOffset)
                        buffer.flip()
                        destChannel.write(buffer)
                        currentReadOffset += toRead
                    }

                    // Write replacement bytes
                    var replacementBytesWritten = 0L
                    context.contentResolver.openFileDescriptor(replacementUri, "r")?.use { repPfd ->
                        val repChannel = FileInputStream(repPfd.fileDescriptor).channel
                        val repLength = repPfd.statSize
                        var repOffset = 0L
                        
                        while (repOffset < repLength) {
                            val toRead = minOf(CHUNK_SIZE.toLong(), repLength - repOffset).toInt()
                            val buffer = ByteBuffer.allocate(toRead)
                            repChannel.read(buffer, repOffset)
                            buffer.flip()
                            destChannel.write(buffer)
                            repOffset += toRead
                            replacementBytesWritten += toRead
                        }
                    }

                    // Pad with zeros up to slotSize if replacement is smaller
                    if (replacementBytesWritten < stream.slotSize) {
                        val paddingSize = (stream.slotSize - replacementBytesWritten).toInt()
                        val zeroBuffer = ByteBuffer.allocate(paddingSize)
                        // By default ByteBuffer is initialized with zeros
                        destChannel.write(zeroBuffer)
                    }

                    currentReadOffset = targetOffset + stream.slotSize
                    onProgress(((index + 1).toFloat() / (sortedStreams.size + 1) * 100).toInt())
                }

                // Copy remaining bytes
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
        }
    }
}
