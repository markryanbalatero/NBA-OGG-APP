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

suspend fun saveStreamToUri(context: Context, sourceUri: Uri, stream: Stream, destUri: Uri) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                val outChannel = (outStream as FileOutputStream).channel
                for (page in stream.pages) {
                    val buffer = ByteBuffer.allocate(page.size)
                    channel.read(buffer, page.offset)
                    buffer.flip()
                    outChannel.write(buffer)
                }
            }
        }
    }
}
