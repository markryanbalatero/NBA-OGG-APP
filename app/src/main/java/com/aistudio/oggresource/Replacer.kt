package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

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
