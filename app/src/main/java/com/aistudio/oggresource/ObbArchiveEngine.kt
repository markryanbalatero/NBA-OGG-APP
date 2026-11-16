package com.aistudio.oggresource

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ObbArchiveEngine {

    private const val SIG_LOCAL_HEADER = 0x04034B50
    private const val SIG_CENTRAL_DIR = 0x02014B50
    private const val SIG_EOCD = 0x06054B50
    private const val SIG_ZIP64_EOCD_LOCATOR = 0x07064B50
    private const val SIG_ZIP64_EOCD = 0x06064B50

    private data class EocdInfo(
        val centralDirOffset: Long,
        val centralDirSize: Long,
        val totalEntries: Long
    )

    private class ChannelSliceInputStream(
        private val channel: FileChannel,
        private val startOffset: Long,
        private val length: Long
    ) : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            if (pos >= length) return -1
            val buf = ByteBuffer.allocate(1)
            val r = channel.read(buf, startOffset + pos)
            if (r <= 0) return -1
            pos++
            return buf.get(0).toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= length) return -1
            val toRead = minOf(len.toLong(), length - pos).toInt()
            if (toRead <= 0) return 0
            val buf = ByteBuffer.wrap(b, off, toRead)
            val r = channel.read(buf, startOffset + pos)
            if (r <= 0) return -1
            pos += r
            return r
        }
    }

    private fun readChannelExact(channel: FileChannel, offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val buf = ByteBuffer.wrap(bytes)
        var total = 0
        while (total < length) {
            val r = channel.read(buf, offset + total)
            if (r <= 0) break
            total += r
        }
        return if (total == length) bytes else bytes.copyOf(total)
    }

    private fun findEocd(channel: FileChannel, fileLength: Long): EocdInfo? {
        if (fileLength < 22L) return null
        val searchLen = minOf(fileLength, 65536L + 22L).toInt()
        val searchStart = fileLength - searchLen
        val tail = readChannelExact(channel, searchStart, searchLen)
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() &&
                tail[i + 1] == 0x4B.toByte() &&
                tail[i + 2] == 0x05.toByte() &&
                tail[i + 3] == 0x06.toByte()
            ) {
                val commentLen = buf.getShort(i + 20).toInt() and 0xFFFF
                if (i + 22 + commentLen <= tail.size) {
                    var totalEntries = (buf.getShort(i + 10).toInt() and 0xFFFF).toLong()
                    var cdSize = buf.getInt(i + 12).toLong() and 0xFFFFFFFFL
                    var cdOffset = buf.getInt(i + 16).toLong() and 0xFFFFFFFFL

                    // Check Zip64 EOCD Locator (20 bytes immediately before EOCD)
                    val eocdAbsOffset = searchStart + i
                    if (eocdAbsOffset >= 20L) {
                        val locBytes = readChannelExact(channel, eocdAbsOffset - 20L, 20)
                        if (locBytes.size == 20) {
                            val locBuf = ByteBuffer.wrap(locBytes).order(ByteOrder.LITTLE_ENDIAN)
                            if (locBuf.getInt(0) == SIG_ZIP64_EOCD_LOCATOR) {
                                val zip64EocdOffset = locBuf.getLong(8)
                                if (zip64EocdOffset in 0L until fileLength - 56L) {
                                    val z64Bytes = readChannelExact(channel, zip64EocdOffset, 56)
                                    if (z64Bytes.size == 56) {
                                        val z64Buf = ByteBuffer.wrap(z64Bytes).order(ByteOrder.LITTLE_ENDIAN)
                                        if (z64Buf.getInt(0) == SIG_ZIP64_EOCD) {
                                            if (totalEntries == 0xFFFFL) totalEntries = z64Buf.getLong(32)
                                            if (cdSize == 0xFFFFFFFFL) cdSize = z64Buf.getLong(40)
                                            if (cdOffset == 0xFFFFFFFFL) cdOffset = z64Buf.getLong(48)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    return EocdInfo(
                        centralDirOffset = cdOffset,
                        centralDirSize = cdSize,
                        totalEntries = totalEntries
                    )
                }
            }
        }
        return null
    }

    suspend fun hasZipSignature(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            UriUtils.openReadPfd(context, uri)?.use { pfd ->
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val fileLength = maxOf(pfd.statSize, runCatching { channel.size() }.getOrDefault(0L))
                if (fileLength >= 4L) {
                    val head = readChannelExact(channel, 0L, 4)
                    if (head.size == 4 &&
                        head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                        ((head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) ||
                            (head[2] == 0x05.toByte() && head[3] == 0x06.toByte()) ||
                            (head[2] == 0x07.toByte() && head[3] == 0x08.toByte()))
                    ) {
                        return@withContext true
                    }
                }
                if (findEocd(channel, fileLength) != null) {
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        false
    }

    /**
     * Parses the entire ZIP Central Directory (or falls back to local header walk)
     * and returns all entries + synthesized parent directories.
     */
    suspend fun parseZipDirectory(
        context: Context,
        uri: Uri,
        onStatusProgress: (Int, String, Boolean) -> Unit = { _, _, _ -> },
        onProgress: (Int) -> Unit = {}
    ): List<ObbEntry> = withContext(Dispatchers.IO) {
        val rawFiles = mutableListOf<ObbEntry>()
        val explicitDirs = mutableSetOf<String>()
        fun report(prog: Int, detail: String, indeterminate: Boolean = false) {
            onProgress(prog)
            onStatusProgress(prog, detail, indeterminate)
        }

        report(0, "Locating ZIP Central Directory...", true)

        UriUtils.openReadPfd(context, uri)?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val fileLength = maxOf(pfd.statSize, runCatching { channel.size() }.getOrDefault(0L))
            val eocd = findEocd(channel, fileLength)

            if (eocd != null && eocd.centralDirOffset in 0L until fileLength) {
                var cursor = eocd.centralDirOffset
                val cdEnd = minOf(fileLength, eocd.centralDirOffset + eocd.centralDirSize)
                val headerBuf = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                var count = 0L
                val progressStep = (eocd.totalEntries / 30L).coerceAtLeast(1L)

                report(5, "Reading Central Directory (${eocd.totalEntries} entries)...", false)

                while (cursor + 46L <= cdEnd) {
                    headerBuf.clear()
                    val read = channel.read(headerBuf, cursor)
                    if (read < 46) break
                    headerBuf.flip()

                    val sig = headerBuf.getInt()
                    if (sig != SIG_CENTRAL_DIR) break

                    headerBuf.getShort() // version made by
                    headerBuf.getShort() // version needed
                    val flags = headerBuf.getShort().toInt() and 0xFFFF
                    val method = headerBuf.getShort().toInt() and 0xFFFF
                    headerBuf.getInt() // mod time / date
                    val crc = headerBuf.getInt().toLong() and 0xFFFFFFFFL
                    var compSize = headerBuf.getInt().toLong() and 0xFFFFFFFFL
                    var uncompSize = headerBuf.getInt().toLong() and 0xFFFFFFFFL
                    val nameLen = headerBuf.getShort().toInt() and 0xFFFF
                    val extraLen = headerBuf.getShort().toInt() and 0xFFFF
                    val commentLen = headerBuf.getShort().toInt() and 0xFFFF
                    headerBuf.getShort() // disk start
                    headerBuf.getShort() // internal attrs
                    headerBuf.getInt() // external attrs
                    var localOffset = headerBuf.getInt().toLong() and 0xFFFFFFFFL

                    val charset = if ((flags and 0x0800) != 0) Charsets.UTF_8 else Charsets.UTF_8
                    val nameBytes = if (nameLen > 0) readChannelExact(channel, cursor + 46L, nameLen) else ByteArray(0)
                    val extraBytes = if (extraLen > 0) readChannelExact(channel, cursor + 46L + nameLen, extraLen) else ByteArray(0)

                    // Parse Zip64 extra field (0x0001) if needed
                    if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                        parseZip64Extra(extraBytes, uncompSize, compSize, localOffset).let { (u, c, l) ->
                            uncompSize = u
                            compSize = c
                            localOffset = l
                        }
                    }

                    val rawPath = String(nameBytes, charset).replace('\\', '/')
                    val normalized = rawPath.trimStart('/')
                    if (normalized.isNotEmpty()) {
                        if (normalized.endsWith("/")) {
                            val dirPath = normalized.trimEnd('/')
                            if (dirPath.isNotEmpty()) explicitDirs.add(dirPath)
                        } else {
                            val parent = normalized.substringBeforeLast('/', "")
                            val fileName = normalized.substringAfterLast('/')
                            rawFiles.add(
                                ObbEntry(
                                    path = normalized,
                                    name = fileName,
                                    parentPath = parent,
                                    isDirectory = false,
                                    compressionMethod = method,
                                    crc = crc,
                                    compressedSize = compSize,
                                    uncompressedSize = uncompSize,
                                    localHeaderOffset = localOffset,
                                    dataOffset = -1L,
                                    fileType = classifyObbFileType(fileName)
                                )
                            )
                        }
                    }

                    cursor += 46L + nameLen + extraLen + commentLen
                    count++
                    if (eocd.totalEntries > 0L && (count % progressStep == 0L || cursor + 46L > cdEnd)) {
                        val prog = ((count.toFloat() / eocd.totalEntries.toFloat()) * 85f + 5f).toInt().coerceIn(5, 90)
                        report(prog, "Indexing OBB entries... ${rawFiles.size} file(s)", false)
                    }
                }
            } else {
                // Fallback: walk local headers sequentially
                var offset = 0L
                val headerBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                while (offset + 30L <= fileLength) {
                    headerBuf.clear()
                    if (channel.read(headerBuf, offset) < 30) break
                    headerBuf.flip()
                    if (headerBuf.getInt() != SIG_LOCAL_HEADER) break

                    headerBuf.getShort()
                    headerBuf.getShort()
                    val method = headerBuf.getShort().toInt() and 0xFFFF
                    headerBuf.getInt()
                    val crc = headerBuf.getInt().toLong() and 0xFFFFFFFFL
                    val compSize = headerBuf.getInt().toLong() and 0xFFFFFFFFL
                    val uncompSize = headerBuf.getInt().toLong() and 0xFFFFFFFFL
                    val nameLen = headerBuf.getShort().toInt() and 0xFFFF
                    val extraLen = headerBuf.getShort().toInt() and 0xFFFF

                    val nameBytes = if (nameLen > 0) readChannelExact(channel, offset + 30L, nameLen) else ByteArray(0)
                    val dataOffset = offset + 30L + nameLen + extraLen
                    val normalized = String(nameBytes, Charsets.UTF_8).replace('\\', '/').trimStart('/')

                    if (normalized.isNotEmpty()) {
                        if (normalized.endsWith("/")) {
                            val dirPath = normalized.trimEnd('/')
                            if (dirPath.isNotEmpty()) explicitDirs.add(dirPath)
                        } else {
                            val parent = normalized.substringBeforeLast('/', "")
                            val fileName = normalized.substringAfterLast('/')
                            rawFiles.add(
                                ObbEntry(
                                    path = normalized,
                                    name = fileName,
                                    parentPath = parent,
                                    isDirectory = false,
                                    compressionMethod = method,
                                    crc = crc,
                                    compressedSize = compSize,
                                    uncompressedSize = uncompSize,
                                    localHeaderOffset = offset,
                                    dataOffset = dataOffset,
                                    fileType = classifyObbFileType(fileName)
                                )
                            )
                        }
                    }

                    if (compSize == 0L && method == 8) break
                    offset = dataOffset + compSize
                    val prog = if (fileLength > 0L) ((offset.toFloat() / fileLength.toFloat()) * 90f).toInt().coerceIn(5, 90) else 50
                    report(prog, "Scanning ZIP local headers... ${rawFiles.size} file(s)", false)
                }
            }
        }

        report(94, "Building OBB folder hierarchy (${rawFiles.size} files)...", false)

        // Synthesize all folder hierarchy nodes and compute direct child counts + recursive total sizes
        val folderPaths = linkedSetOf<String>()
        folderPaths.addAll(explicitDirs)
        for (file in rawFiles) {
            var p = file.parentPath
            while (p.isNotEmpty()) {
                if (!folderPaths.add(p)) break
                p = p.substringBeforeLast('/', "")
            }
        }

        val directChildrenCount = mutableMapOf<String, Int>()
        val folderSizes = mutableMapOf<String, Long>()

        for (file in rawFiles) {
            directChildrenCount[file.parentPath] = (directChildrenCount[file.parentPath] ?: 0) + 1
            var p = file.parentPath
            while (p.isNotEmpty()) {
                folderSizes[p] = (folderSizes[p] ?: 0L) + file.uncompressedSize
                p = p.substringBeforeLast('/', "")
            }
        }

        for (folder in folderPaths) {
            val parent = folder.substringBeforeLast('/', "")
            directChildrenCount[parent] = (directChildrenCount[parent] ?: 0) + 1
        }

        val folderEntries = folderPaths.map { folderPath ->
            val parent = folderPath.substringBeforeLast('/', "")
            val name = folderPath.substringAfterLast('/')
            ObbEntry(
                path = folderPath,
                name = name,
                parentPath = parent,
                isDirectory = true,
                fileType = ObbFileType.FOLDER,
                childCount = directChildrenCount[folderPath] ?: 0,
                folderTotalSize = folderSizes[folderPath] ?: 0L
            )
        }

        report(100, "Indexed ${rawFiles.size} file(s) in ${folderPaths.size} folder(s)", false)
        (folderEntries + rawFiles).sortedWith(compareBy<ObbEntry> { !it.isDirectory }.thenBy { it.path.lowercase() })
    }

    private fun parseZip64Extra(
        extra: ByteArray,
        origUncomp: Long,
        origComp: Long,
        origOffset: Long
    ): Triple<Long, Long, Long> {
        var uncomp = origUncomp
        var comp = origComp
        var offset = origOffset
        val buf = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 4 <= extra.size) {
            val headerId = buf.getShort(pos).toInt() and 0xFFFF
            val dataSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            pos += 4
            if (headerId == 0x0001) {
                var fieldPos = pos
                if (uncomp == 0xFFFFFFFFL && fieldPos + 8 <= pos + dataSize && fieldPos + 8 <= extra.size) {
                    uncomp = buf.getLong(fieldPos)
                    fieldPos += 8
                }
                if (comp == 0xFFFFFFFFL && fieldPos + 8 <= pos + dataSize && fieldPos + 8 <= extra.size) {
                    comp = buf.getLong(fieldPos)
                    fieldPos += 8
                }
                if (offset == 0xFFFFFFFFL && fieldPos + 8 <= pos + dataSize && fieldPos + 8 <= extra.size) {
                    offset = buf.getLong(fieldPos)
                }
                break
            }
            pos += dataSize
        }
        return Triple(uncomp, comp, offset)
    }

    private fun resolveDataOffset(channel: FileChannel, entry: ObbEntry): Long {
        if (entry.dataOffset >= 0L) return entry.dataOffset
        val localHdr = readChannelExact(channel, entry.localHeaderOffset, 30)
        if (localHdr.size < 30) throw IOException("Corrupt ZIP local header for ${entry.path}")
        val buf = ByteBuffer.wrap(localHdr).order(ByteOrder.LITTLE_ENDIAN)
        val sig = buf.getInt(0)
        if (sig != SIG_LOCAL_HEADER) throw IOException("Invalid ZIP local header signature for ${entry.path}")
        val nameLen = buf.getShort(26).toInt() and 0xFFFF
        val extraLen = buf.getShort(28).toInt() and 0xFFFF
        return entry.localHeaderOffset + 30L + nameLen + extraLen
    }

    private fun openDecompressedEntryStream(channel: FileChannel, entry: ObbEntry): InputStream {
        val dataOffset = resolveDataOffset(channel, entry)
        val rawSlice = ChannelSliceInputStream(channel, dataOffset, entry.compressedSize)
        return when (entry.compressionMethod) {
            0 -> rawSlice
            8 -> object : InflaterInputStream(rawSlice, Inflater(true), 64 * 1024) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        inf.end()
                    }
                }
            }
            else -> throw IOException("Unsupported ZIP compression method ${entry.compressionMethod} for ${entry.name}")
        }
    }

    suspend fun extractEntryToTempFile(
        context: Context,
        archiveUri: Uri,
        entry: ObbEntry,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val safeName = entry.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "entry.bin" }
        val tempDir = File(context.cacheDir, "obb_workspace")
        tempDir.mkdirs()
        val targetFile = File(tempDir, "${System.currentTimeMillis()}_$safeName")

        UriUtils.openReadPfd(context, archiveUri)?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            openDecompressedEntryStream(channel, entry).use { inStream ->
                FileOutputStream(targetFile).use { outStream ->
                    val buffer = ByteArray(256 * 1024)
                    var totalWritten = 0L
                    val expected = entry.uncompressedSize.coerceAtLeast(1L)
                    while (true) {
                        val read = inStream.read(buffer)
                        if (read == -1) break
                        outStream.write(buffer, 0, read)
                        totalWritten += read
                        onProgress(((totalWritten * 100L) / expected).toInt().coerceIn(0, 99))
                    }
                }
            }
        } ?: throw IOException("Unable to open OBB archive")

        onProgress(100)
        targetFile
    }

    suspend fun extractEntryToUri(
        context: Context,
        archiveUri: Uri,
        entry: ObbEntry,
        destUri: Uri,
        onProgress: (Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        UriUtils.openReadPfd(context, archiveUri)?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            openDecompressedEntryStream(channel, entry).use { inStream ->
                val outStream = UriUtils.openOutputStream(context, destUri)
                    ?: throw IOException("Unable to open destination file")
                outStream.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var totalWritten = 0L
                    val expected = entry.uncompressedSize.coerceAtLeast(1L)
                    while (true) {
                        val read = inStream.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        totalWritten += read
                        onProgress(((totalWritten * 100L) / expected).toInt().coerceIn(0, 99))
                    }
                }
            }
        } ?: throw IOException("Unable to open OBB archive")
        onProgress(100)
    }

    private fun mimeTypeForExport(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == fileName.lowercase()) return "*/*"
        val mapped = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return if (mapped.isNullOrBlank() || mapped == "application/octet-stream") "*/*" else mapped
    }

    suspend fun extractEntriesToTree(
        context: Context,
        archiveUri: Uri,
        entriesToExport: List<ObbEntry>,
        treeUri: Uri,
        preserveFolderStructure: Boolean = true,
        relativePrefixToStrip: String = "",
        onStatusProgress: (progress: Int, detail: String, badge: String, indeterminate: Boolean) -> Unit = { _, _, _, _ -> },
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val files = entriesToExport.filter { !it.isDirectory }
        if (files.isEmpty()) throw IOException("No matching files found to export")

        val total = files.size.coerceAtLeast(1)
        val totalBytes = files.sumOf { it.uncompressedSize.coerceAtLeast(1L) }.coerceAtLeast(1L)

        fun emit(prog: Int, detail: String, badge: String, indeterminate: Boolean = false) {
            onProgress(prog, detail)
            onStatusProgress(prog, detail, badge, indeterminate)
        }

        emit(0, "Opening destination folder...", "0/$total", true)

        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Cannot access selected destination folder")

        val dirCache = mutableMapOf<String, DocumentFile>("" to rootDoc)
        val flatNameCounts = mutableMapOf<String, Int>()

        fun ensureDirectory(relDir: String): DocumentFile {
            if (relDir.isEmpty()) return rootDoc
            dirCache[relDir]?.let { return it }
            val parts = relDir.split('/').filter { it.isNotEmpty() }
            var current = rootDoc
            var built = ""
            for (part in parts) {
                built = if (built.isEmpty()) part else "$built/$part"
                val cached = dirCache[built]
                if (cached != null) {
                    current = cached
                } else {
                    val existing = current.findFile(part)?.takeIf { it.isDirectory }
                    val created = existing ?: current.createDirectory(part)
                        ?: throw IOException("Failed to create folder $part")
                    dirCache[built] = created
                    current = created
                }
            }
            return current
        }

        UriUtils.openReadPfd(context, archiveUri)?.use { pfd ->
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val buffer = ByteArray(256 * 1024)
            var bytesCompletedBeforeCurrent = 0L
            var lastReportedPct = -1

            files.forEachIndexed { idx, entry ->
                val targetDir: DocumentFile
                val fileName: String

                if (preserveFolderStructure) {
                    val relPath = if (relativePrefixToStrip.isNotEmpty() && entry.path.startsWith("$relativePrefixToStrip/")) {
                        entry.path.removePrefix("$relativePrefixToStrip/").ifEmpty { entry.name }
                    } else {
                        entry.path
                    }
                    val relParent = relPath.substringBeforeLast('/', "")
                    fileName = relPath.substringAfterLast('/')
                    targetDir = ensureDirectory(relParent)
                } else {
                    targetDir = rootDoc
                    val key = entry.name.lowercase()
                    val seen = flatNameCounts[key] ?: 0
                    flatNameCounts[key] = seen + 1
                    fileName = if (seen == 0) {
                        entry.name
                    } else {
                        val dot = entry.name.lastIndexOf('.')
                        if (dot in 1 until entry.name.length - 1) {
                            "${entry.name.substring(0, dot)}_$seen${entry.name.substring(dot)}"
                        } else {
                            "${entry.name}_$seen"
                        }
                    }
                }

                val badge = "${idx + 1}/$total"
                val startPct = ((bytesCompletedBeforeCurrent * 100L) / totalBytes).toInt().coerceIn(0, 99)
                emit(startPct, "Exporting $fileName...", badge, false)
                lastReportedPct = startPct

                targetDir.findFile(fileName)?.delete()
                val outDoc = targetDir.createFile(mimeTypeForExport(fileName), fileName)
                    ?: throw IOException("Failed to create file $fileName")

                var entryWritten = 0L
                val expectedEntrySize = entry.uncompressedSize.coerceAtLeast(1L)
                openDecompressedEntryStream(channel, entry).use { inStream ->
                    UriUtils.openOutputStream(context, outDoc.uri)?.use { outStream ->
                        while (true) {
                            val r = inStream.read(buffer)
                            if (r == -1) break
                            outStream.write(buffer, 0, r)
                            entryWritten += r
                            val clampedEntryBytes = entryWritten.coerceAtMost(expectedEntrySize)
                            val pct = (((bytesCompletedBeforeCurrent + clampedEntryBytes) * 100L) / totalBytes)
                                .toInt()
                                .coerceIn(0, 99)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                emit(pct, "Exporting $fileName...", badge, false)
                            }
                        }
                    }
                }

                bytesCompletedBeforeCurrent += expectedEntrySize
                val donePct = if (idx == total - 1) 100 else ((bytesCompletedBeforeCurrent * 100L) / totalBytes).toInt().coerceIn(0, 99)
                lastReportedPct = donePct
                emit(donePct, "Exported $fileName", badge, false)
            }
        } ?: throw IOException("Unable to open OBB archive")
    }

    suspend fun extractFolderToTree(
        context: Context,
        archiveUri: Uri,
        allEntries: List<ObbEntry>,
        folderPrefix: String,
        treeUri: Uri,
        preserveFolderStructure: Boolean = true,
        onProgress: (Int, String) -> Unit
    ) {
        val filesToExtract = allEntries.filter { entry ->
            !entry.isDirectory && (folderPrefix.isEmpty() || entry.path.startsWith("$folderPrefix/"))
        }
        extractEntriesToTree(
            context = context,
            archiveUri = archiveUri,
            entriesToExport = filesToExtract,
            treeUri = treeUri,
            preserveFolderStructure = preserveFolderStructure,
            relativePrefixToStrip = folderPrefix,
            onProgress = onProgress
        )
    }

    suspend fun loadEntryPreview(
        context: Context,
        archiveUri: Uri,
        entry: ObbEntry
    ): ObbEntryPreview = withContext(Dispatchers.IO) {
        try {
            val maxBytes = when (entry.fileType) {
                ObbFileType.IMAGE -> 16 * 1024 * 1024
                ObbFileType.TEXT -> 256 * 1024
                else -> 8 * 1024
            }

            val bytes = UriUtils.openReadPfd(context, archiveUri)?.use { pfd ->
                val channel = FileInputStream(pfd.fileDescriptor).channel
                openDecompressedEntryStream(channel, entry).use { inStream ->
                    readUpTo(inStream, maxBytes)
                }
            } ?: return@withContext ObbEntryPreview.ErrorPreview(entry, "Could not open OBB archive")

            val magicLabel = detectMagicSignature(bytes)

            if (entry.fileType == ObbFileType.IMAGE) {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                    var sample = 1
                    while (boundsOpts.outWidth / sample > 1440 || boundsOpts.outHeight / sample > 1440) {
                        sample *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    if (bmp != null) {
                        return@withContext ObbEntryPreview.ImagePreview(
                            entry = entry,
                            bitmap = bmp,
                            width = boundsOpts.outWidth,
                            height = boundsOpts.outHeight
                        )
                    }
                }
            }

            if (entry.fileType == ObbFileType.TEXT || looksLikeUtf8Text(bytes)) {
                val text = String(bytes, Charsets.UTF_8)
                val lineCount = text.count { it == '\n' } + 1
                return@withContext ObbEntryPreview.TextPreview(
                    entry = entry,
                    text = text,
                    isTruncated = entry.uncompressedSize > bytes.size.toLong(),
                    lineCount = lineCount,
                    magicLabel = magicLabel
                )
            }

            val hasOgg = containsOggMagic(bytes)
            val dumpLen = minOf(bytes.size, 4096)
            return@withContext ObbEntryPreview.HexPreview(
                entry = entry,
                hexDump = formatHexDump(bytes, dumpLen),
                magicLabel = magicLabel,
                bytesInspected = dumpLen,
                hasEmbeddedOggSignature = hasOgg
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ObbEntryPreview.ErrorPreview(entry, e.localizedMessage ?: "Failed to inspect file contents")
        }
    }

    suspend fun inspectRawArchiveHeader(
        context: Context,
        archiveUri: Uri,
        fileName: String,
        fileSize: Long
    ): ObbEntryPreview = withContext(Dispatchers.IO) {
        val pseudoEntry = ObbEntry(
            path = fileName,
            name = fileName,
            parentPath = "",
            isDirectory = false,
            uncompressedSize = fileSize,
            compressedSize = fileSize
        )
        try {
            val bytes = UriUtils.openInputStream(context, archiveUri)?.use { inStream ->
                readUpTo(inStream, 4096)
            } ?: return@withContext ObbEntryPreview.ErrorPreview(pseudoEntry, "Unable to read archive header")

            val magicLabel = detectMagicSignature(bytes)
            ObbEntryPreview.HexPreview(
                entry = pseudoEntry,
                hexDump = formatHexDump(bytes, bytes.size),
                magicLabel = magicLabel,
                bytesInspected = bytes.size,
                hasEmbeddedOggSignature = containsOggMagic(bytes),
                isObbEntry = false
            )
        } catch (e: Exception) {
            ObbEntryPreview.ErrorPreview(pseudoEntry, e.localizedMessage ?: "Header inspection failed")
        }
    }

    /**
     * Rebuilds a ZIP Expansion OBB while replacing any entries in [replacements],
     * preserving original compression methods (STORED vs DEFLATED) and recalculating CRC32s.
     */
    suspend fun patchZipObb(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        entries: List<ObbEntry>,
        replacements: Map<String, StagedObbReplacement>,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val fileEntries = entries.filter { !it.isDirectory }
        val total = fileEntries.size.coerceAtLeast(1)

        UriUtils.openReadPfd(context, sourceUri)?.use { sourcePfd ->
            val sourceChannel = FileInputStream(sourcePfd.fileDescriptor).channel
            val outStream = UriUtils.openOutputStream(context, destUri)
                ?: throw IOException("Could not open destination OBB for writing")

            ZipOutputStream(BufferedOutputStream(outStream, 256 * 1024)).use { zos ->
                val buffer = ByteArray(512 * 1024)

                // Write explicit directories first if present
                entries.filter { it.isDirectory }.forEach { dir ->
                    val dirEntry = ZipEntry("${dir.path}/").apply {
                        method = ZipEntry.STORED
                        size = 0L
                        compressedSize = 0L
                        crc = 0L
                    }
                    zos.putNextEntry(dirEntry)
                    zos.closeEntry()
                }

                fileEntries.forEachIndexed { idx, entry ->
                    val staged = replacements[entry.path]
                    val targetMethod = if (entry.compressionMethod == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED

                    if (staged != null) {
                        val openReplacementStream: () -> InputStream = {
                            when {
                                staged.replacementFile != null && staged.replacementFile.exists() ->
                                    FileInputStream(staged.replacementFile)
                                staged.replacementUri != null ->
                                    UriUtils.openInputStream(context, staged.replacementUri)
                                        ?: throw IOException("Cannot read replacement for ${entry.path}")
                                else -> throw IOException("Missing replacement payload for ${entry.path}")
                            }
                        }

                        // Pass 1: calculate exact size & CRC32 for STORED compatibility
                        val crc = CRC32()
                        var byteCount = 0L
                        openReplacementStream().use { inS ->
                            while (true) {
                                val r = inS.read(buffer)
                                if (r == -1) break
                                crc.update(buffer, 0, r)
                                byteCount += r
                            }
                        }

                        val newEntry = ZipEntry(entry.path).apply {
                            method = targetMethod
                            if (targetMethod == ZipEntry.STORED) {
                                size = byteCount
                                compressedSize = byteCount
                                this.crc = crc.value
                            }
                        }

                        zos.putNextEntry(newEntry)
                        openReplacementStream().use { inS ->
                            while (true) {
                                val r = inS.read(buffer)
                                if (r == -1) break
                                zos.write(buffer, 0, r)
                            }
                        }
                        zos.closeEntry()
                    } else {
                        val copyEntry = ZipEntry(entry.path).apply {
                            method = targetMethod
                            if (targetMethod == ZipEntry.STORED && entry.uncompressedSize >= 0L) {
                                size = entry.uncompressedSize
                                compressedSize = entry.uncompressedSize
                                crc = entry.crc
                            }
                        }

                        zos.putNextEntry(copyEntry)
                        openDecompressedEntryStream(sourceChannel, entry).use { inS ->
                            while (true) {
                                val r = inS.read(buffer)
                                if (r == -1) break
                                zos.write(buffer, 0, r)
                            }
                        }
                        zos.closeEntry()
                    }

                    val prog = (((idx + 1).toFloat() / total.toFloat()) * 100f).toInt().coerceIn(1, 100)
                    onProgress(prog)
                }
            }
        } ?: throw IOException("Could not open source OBB archive")
    }

    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size, remaining))
            if (r == -1) break
            baos.write(buf, 0, r)
            remaining -= r
        }
        return baos.toByteArray()
    }

    private fun detectMagicSignature(bytes: ByteArray): String {
        if (bytes.size < 4) return "Small Binary (${bytes.size} B)"
        val ascii4 = String(bytes, 0, 4, Charsets.ISO_8859_1)
        return when {
            ascii4 == "OggS" -> "OggS Container (Vorbis / Opus Audio)"
            ascii4 == "RIFF" -> "RIFF / WAV Media Container"
            ascii4 == "FSB5" || ascii4 == "FSB4" -> "FMOD Sound Bank ($ascii4)"
            ascii4.startsWith("PK") -> "PKZIP / Android OBB Expansion"
            bytes.size >= 7 && String(bytes, 0, 7, Charsets.ISO_8859_1) == "UnityFS" -> "Unity AssetBundle (UnityFS)"
            bytes[0] == 0x89.toByte() && ascii4.substring(1, 4) == "PNG" -> "PNG Image"
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "JPEG Image"
            ascii4 == "FORM" -> "IFF Interchange File Format"
            ascii4 == "SQLi" -> "SQLite Database"
            bytes[0] == 0x7F.toByte() && ascii4.substring(1, 4) == "ELF" -> "ELF Native Library (.so)"
            ascii4 == "ID3\u0003" || ascii4 == "ID3\u0004" -> "MP3 Audio (ID3)"
            ascii4 == "fLaC" -> "FLAC Lossless Audio"
            else -> {
                val hex = bytes.take(4).joinToString(" ") { "%02X".format(it) }
                "Binary ($hex)"
            }
        }
    }

    private fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val checkLen = minOf(bytes.size, 2048)
        var controlCount = 0
        for (i in 0 until checkLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
                controlCount++
            }
        }
        return controlCount <= checkLen / 20
    }

    private fun containsOggMagic(bytes: ByteArray): Boolean {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x4F.toByte() && bytes[i + 1] == 0x67.toByte() &&
                bytes[i + 2] == 0x67.toByte() && bytes[i + 3] == 0x53.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun formatHexDump(bytes: ByteArray, limit: Int): String {
        val sb = StringBuilder()
        val len = minOf(bytes.size, limit)
        var offset = 0
        while (offset < len) {
            sb.append("%08X  ".format(offset))
            val lineLen = minOf(16, len - offset)
            for (i in 0 until 16) {
                if (i < lineLen) {
                    sb.append("%02X ".format(bytes[offset + i]))
                } else {
                    sb.append("   ")
                }
                if (i == 7) sb.append(" ")
            }
            sb.append(" |")
            for (i in 0 until lineLen) {
                val ch = bytes[offset + i].toInt() and 0xFF
                sb.append(if (ch in 32..126) ch.toChar() else '.')
            }
            sb.append("|\n")
            offset += 16
        }
        return sb.toString()
    }
}
