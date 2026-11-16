package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object UriUtils {

    fun openReadPfd(context: Context, uri: Uri): ParcelFileDescriptor? {
        if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
            val f = File(uri.path!!)
            if (f.exists()) {
                return try {
                    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: Exception) {
                    null
                }
            }
        }
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            if (!uri.path.isNullOrEmpty()) {
                val f = File(uri.path!!)
                if (f.exists()) {
                    try {
                        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    } catch (ignored: Exception) {
                        null
                    }
                } else null
            } else null
        }
    }

    fun openInputStream(context: Context, uri: Uri): InputStream? {
        if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
            val f = File(uri.path!!)
            if (f.exists()) {
                return try { FileInputStream(f) } catch (e: Exception) { null }
            }
        }
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            if (!uri.path.isNullOrEmpty()) {
                val f = File(uri.path!!)
                if (f.exists()) {
                    try { FileInputStream(f) } catch (ignored: Exception) { null }
                } else null
            } else null
        }
    }

    fun openOutputStream(context: Context, uri: Uri): OutputStream? {
        if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
            val f = File(uri.path!!)
            f.parentFile?.mkdirs()
            return try { FileOutputStream(f) } catch (e: Exception) { null }
        }
        return try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            if (!uri.path.isNullOrEmpty()) {
                val f = File(uri.path!!)
                f.parentFile?.mkdirs()
                try { FileOutputStream(f) } catch (ignored: Exception) { null }
            } else null
        }
    }
}
