package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

suspend fun extractStreamToTempFile(context: Context, sourceUri: Uri, stream: Stream): File {
    return withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, stream.fileName)
        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            FileOutputStream(tempFile).use { out ->
                val outChannel = out.channel
                for (page in stream.pages) {
                    val buffer = ByteBuffer.allocate(page.size)
                    channel.read(buffer, page.offset)
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
        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                val outChannel = (outStream as FileOutputStream).channel
                val totalPages = stream.pages.size.coerceAtLeast(1)
                for ((index, page) in stream.pages.withIndex()) {
                    val buffer = ByteBuffer.allocate(page.size)
                    channel.read(buffer, page.offset)
                    buffer.flip()
                    outChannel.write(buffer)
                    val progress = (((index + 1).toFloat() / totalPages) * 100).toInt()
                    onProgress(progress.coerceIn(0, 100))
                }
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
        val total = streams.size.coerceAtLeast(1)
        val docTreeUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        )
        for ((index, stream) in streams.withIndex()) {
            val fileProgress = (((index).toFloat() / total) * 100).toInt()
            onProgress(fileProgress, stream.fileName)
            try {
                val createdUri = android.provider.DocumentsContract.createDocument(
                    context.contentResolver,
                    docTreeUri,
                    "audio/ogg",
                    stream.fileName
                )
                if (createdUri != null) {
                    saveStreamToUri(context, sourceUri, stream, createdUri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onProgress(100, "All ${streams.size} streams extracted")
    }
}

