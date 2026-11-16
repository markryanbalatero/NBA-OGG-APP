package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

suspend fun extractStreamToTempFile(context: Context, sourceUri: Uri, stream: Stream): File {
    return withContext(Dispatchers.IO) {
        val sanitized = stream.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
        val safeName = sanitized.ifEmpty { "stream_${stream.id}.ogg" }
        val tempFile = File(context.cacheDir, safeName)
        tempFile.parentFile?.mkdirs()
        if (tempFile.exists()) tempFile.delete()

        if (stream.isZipEntry && stream.zipEntryPath != null) {
            if (stream.zipCompressionMethod == 0 && stream.zipDataOffset > 0L && stream.size > 0L) {
                try {
                    UriUtils.openReadPfd(context, sourceUri)?.use { pfd ->
                        val ch = FileInputStream(pfd.fileDescriptor).channel
                        FileOutputStream(tempFile).use { out ->
                            val buf = ByteBuffer.allocate(256 * 1024)
                            var remaining = stream.size
                            var pos = stream.zipDataOffset
                            while (remaining > 0L) {
                                buf.clear()
                                if (remaining < buf.capacity()) buf.limit(remaining.toInt())
                                val r = ch.read(buf, pos)
                                if (r <= 0) break
                                buf.flip()
                                out.channel.write(buf)
                                pos += r
                                remaining -= r
                            }
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        return@withContext tempFile
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                UriUtils.openInputStream(context, sourceUri)?.use { rawIn ->
                    val bufferedIn = java.io.BufferedInputStream(rawIn, 64 * 1024)
                    val zis = java.util.zip.ZipInputStream(bufferedIn)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == stream.zipEntryPath) {
                            FileOutputStream(tempFile).use { out ->
                                zis.copyTo(out)
                            }
                            break
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    return@withContext tempFile
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        UriUtils.openReadPfd(context, sourceUri)?.use { pfd ->
            val fileChannel = FileInputStream(pfd.fileDescriptor).channel
            FileOutputStream(tempFile).use { out ->
                val outChannel = out.channel

                if (stream.pages.isNotEmpty()) {
                    for (page in stream.pages) {
                        val buffer = ByteBuffer.allocate(page.size)
                        fileChannel.read(buffer, page.offset)
                        buffer.flip()
                        outChannel.write(buffer)
                    }
                } else {
                    var currentOffset = stream.offset
                    val slotEnd = stream.offset + stream.slotSize
                    
                    while (currentOffset < slotEnd) {
                        if (currentOffset + 27 > slotEnd) break
                        val headerBuffer = ByteBuffer.allocate(27)
                        val bytesRead = fileChannel.read(headerBuffer, currentOffset)
                        if (bytesRead < 27) break
                        headerBuffer.flip()
                        
                        val b = headerBuffer.array()
                        if (b[0] != 0x4F.toByte() || b[1] != 0x67.toByte() || b[2] != 0x67.toByte() || b[3] != 0x53.toByte()) {
                            break
                        }
                        
                        val headerType = b[5].toInt()
                        val segmentCount = b[26].toInt() and 0xFF
                        
                        if (currentOffset + 27 + segmentCount > slotEnd) break
                        val segTableBuffer = ByteBuffer.allocate(segmentCount)
                        fileChannel.read(segTableBuffer, currentOffset + 27)
                        segTableBuffer.flip()
                        
                        val segTable = segTableBuffer.array()
                        var payloadSize = 0
                        for (seg in segTable) {
                            payloadSize += seg.toInt() and 0xFF
                        }
                        
                        val pageSize = 27 + segmentCount + payloadSize
                        if (currentOffset + pageSize > slotEnd) break
                        
                        val pageBuffer = ByteBuffer.allocate(pageSize)
                        fileChannel.read(pageBuffer, currentOffset)
                        pageBuffer.flip()
                        outChannel.write(pageBuffer)
                        
                        currentOffset += pageSize
                        
                        if ((headerType and 0x04) != 0) {
                            break
                        }
                    }
                }

                if (tempFile.length() < 28L && stream.size > 0) {
                    outChannel.truncate(0)
                    val fallbackLen = stream.size.toInt().coerceAtMost(50 * 1024 * 1024)
                    val buffer = ByteBuffer.allocate(fallbackLen)
                    fileChannel.read(buffer, stream.offset)
                    buffer.flip()
                    outChannel.write(buffer)
                }
            }
        }
        tempFile
    }
}

suspend fun saveStreamToUri(
    context: Context,
    sourceUri: Uri,
    stream: Stream,
    destUri: Uri,
    onProgress: (Int) -> Unit = {}
) {
    withContext(Dispatchers.IO) {
        if (stream.isZipEntry && stream.zipEntryPath != null) {
            if (stream.zipCompressionMethod == 0 && stream.zipDataOffset > 0L && stream.size > 0L) {
                try {
                    UriUtils.openReadPfd(context, sourceUri)?.use { pfd ->
                        val ch = FileInputStream(pfd.fileDescriptor).channel
                        UriUtils.openOutputStream(context, destUri)?.use { outStream ->
                            val buf = ByteArray(256 * 1024)
                            val bb = ByteBuffer.wrap(buf)
                            var remaining = stream.size
                            var pos = stream.zipDataOffset
                            var written = 0L
                            while (remaining > 0L) {
                                bb.clear()
                                if (remaining < buf.size) bb.limit(remaining.toInt())
                                val r = ch.read(bb, pos)
                                if (r <= 0) break
                                outStream.write(buf, 0, r)
                                pos += r
                                remaining -= r
                                written += r
                                onProgress(((written * 100L) / stream.size).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                    onProgress(100)
                    return@withContext
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                UriUtils.openInputStream(context, sourceUri)?.use { rawIn ->
                    val bufferedIn = java.io.BufferedInputStream(rawIn, 64 * 1024)
                    val zis = java.util.zip.ZipInputStream(bufferedIn)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == stream.zipEntryPath) {
                            UriUtils.openOutputStream(context, destUri)?.use { outStream ->
                                zis.copyTo(outStream)
                            }
                            onProgress(100)
                            return@withContext
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        UriUtils.openReadPfd(context, sourceUri)?.use { pfd ->
            val fileChannel = FileInputStream(pfd.fileDescriptor).channel
            val outStream = UriUtils.openOutputStream(context, destUri)
            outStream?.use { streamOut ->
                val fos = streamOut as? FileOutputStream
                val outChannel = fos?.channel ?: java.nio.channels.Channels.newChannel(streamOut)
                
                if (stream.pages.isNotEmpty()) {
                    val totalPages = stream.pages.size.coerceAtLeast(1)
                    for ((index, page) in stream.pages.withIndex()) {
                        val buffer = ByteBuffer.allocate(page.size)
                        fileChannel.read(buffer, page.offset)
                        buffer.flip()
                        outChannel.write(buffer)
                        val progress = (((index + 1).toFloat() / totalPages) * 100).toInt()
                        onProgress(progress.coerceIn(0, 100))
                    }
                } else {
                    var currentOffset = stream.offset
                    val slotEnd = stream.offset + stream.slotSize
                    val totalBytes = stream.slotSize.coerceAtLeast(1L)
                    
                    while (currentOffset < slotEnd) {
                        if (currentOffset + 27 > slotEnd) break
                        val headerBuffer = ByteBuffer.allocate(27)
                        val bytesRead = fileChannel.read(headerBuffer, currentOffset)
                        if (bytesRead < 27) break
                        headerBuffer.flip()
                        
                        val b = headerBuffer.array()
                        if (b[0] != 0x4F.toByte() || b[1] != 0x67.toByte() || b[2] != 0x67.toByte() || b[3] != 0x53.toByte()) {
                            break
                        }
                        
                        val headerType = b[5].toInt()
                        val segmentCount = b[26].toInt() and 0xFF
                        
                        if (currentOffset + 27 + segmentCount > slotEnd) break
                        val segTableBuffer = ByteBuffer.allocate(segmentCount)
                        fileChannel.read(segTableBuffer, currentOffset + 27)
                        segTableBuffer.flip()
                        
                        val segTable = segTableBuffer.array()
                        var payloadSize = 0
                        for (seg in segTable) {
                            payloadSize += seg.toInt() and 0xFF
                        }
                        
                        val pageSize = 27 + segmentCount + payloadSize
                        if (currentOffset + pageSize > slotEnd) break
                        
                        val pageBuffer = ByteBuffer.allocate(pageSize)
                        fileChannel.read(pageBuffer, currentOffset)
                        pageBuffer.flip()
                        outChannel.write(pageBuffer)
                        
                        currentOffset += pageSize
                        
                        val progress = (((currentOffset - stream.offset).toFloat() / totalBytes) * 100).toInt()
                        onProgress(progress.coerceIn(0, 100))
                        
                        if ((headerType and 0x04) != 0) {
                            break
                        }
                    }
                }
                onProgress(100)
            }
        }
    }
}

suspend fun saveMultipleStreamsToTree(
    context: Context,
    sourceUri: Uri,
    streams: List<Stream>,
    treeUri: Uri,
    onProgress: (Int, String) -> Unit
) {
    withContext(Dispatchers.IO) {
        val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        val total = streams.size.coerceAtLeast(1)
        
        streams.forEachIndexed { index, stream ->
            val prog = (((index + 1).toFloat() / total.toFloat()) * 100).toInt().coerceIn(0, 100)
            onProgress(prog, "Extracting ${stream.fileName} (${index + 1}/${streams.size})...")
            val targetName = stream.fileName.ifEmpty { "stream_${stream.id}.ogg" }
            val existingFile = pickedDir.findFile(targetName)
            existingFile?.delete()
            
            val newFile = pickedDir.createFile("audio/ogg", targetName)
            if (newFile != null) {
                saveStreamToUri(context, sourceUri, stream, newFile.uri)
            }
        }
        onProgress(100, "Extracted $total streams successfully!")
    }
}
