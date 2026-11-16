package com.aistudio.oggresource

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

enum class ObbFileType(val label: String) {
    FOLDER("Folder"),
    AUDIO("Audio"),
    ARCHIVE_CONTAINER("Archive / Bank"),
    IMAGE("Image"),
    TEXT("Text / Config"),
    BINARY("Binary Data")
}

enum class ArchiveWorkspaceMode(val label: String) {
    OBB_CONTENTS("OBB Contents"),
    AUDIO_STREAMS("Audio Streams")
}

enum class ObbFilterCategory(val label: String) {
    ALL("All"),
    FOLDERS("Folders"),
    AUDIO("Audio"),
    CONTAINERS("Archives & IFF"),
    IMAGES("Images"),
    TEXT("Text & Config"),
    MODIFIED("Modified")
}

enum class ObbSortField(val label: String) {
    TYPE_THEN_NAME("Folders First"),
    NAME("Name"),
    SIZE("Size"),
    FILE_TYPE("File Type")
}

data class ObbEntry(
    val path: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val compressionMethod: Int = 0, // 0 = STORED, 8 = DEFLATED
    val crc: Long = 0L,
    val compressedSize: Long = 0L,
    val uncompressedSize: Long = 0L,
    val localHeaderOffset: Long = 0L,
    val dataOffset: Long = -1L,
    val fileType: ObbFileType = if (isDirectory) ObbFileType.FOLDER else classifyObbFileType(name),
    val childCount: Int = 0,
    val folderTotalSize: Long = 0L
)

data class StagedObbReplacement(
    val entryPath: String,
    val replacementUri: Uri? = null,
    val replacementFile: File? = null,
    val replacementName: String,
    val replacementSize: Long,
    val fromNestedContainer: Boolean = false
)

data class ContainerLayer(
    val id: String,
    val displayName: String,
    val sourceUri: Uri,
    val sourceSize: Long,
    val archiveType: ArchiveContainerType,
    val workspaceMode: ArchiveWorkspaceMode,
    val parentEntryPath: String? = null,
    val tempBackingFile: File? = null,
    val obbEntries: List<ObbEntry> = emptyList(),
    val currentObbFolder: String = "",
    val streams: List<StreamState> = emptyList(),
    val stagedObbReplacements: Map<String, StagedObbReplacement> = emptyMap()
)

sealed class ObbEntryPreview {
    data object Loading : ObbEntryPreview()
    data class TextPreview(
        val entry: ObbEntry,
        val text: String,
        val isTruncated: Boolean,
        val lineCount: Int,
        val magicLabel: String
    ) : ObbEntryPreview()

    data class ImagePreview(
        val entry: ObbEntry,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int
    ) : ObbEntryPreview()

    data class HexPreview(
        val entry: ObbEntry,
        val hexDump: String,
        val magicLabel: String,
        val bytesInspected: Int,
        val hasEmbeddedOggSignature: Boolean,
        val isObbEntry: Boolean = true
    ) : ObbEntryPreview()

    data class ErrorPreview(
        val entry: ObbEntry,
        val message: String
    ) : ObbEntryPreview()
}

fun classifyObbFileType(fileName: String): ObbFileType {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".wav") ||
            lower.endsWith(".mp3") || lower.endsWith(".oga") || lower.endsWith(".flac") ||
            lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".wem") -> ObbFileType.AUDIO

        lower.endsWith(".iff") || lower.endsWith(".obb") || lower.endsWith(".zip") ||
            lower.endsWith(".apk") || lower.endsWith(".pak") || lower.endsWith(".bank") ||
            lower.endsWith(".fsb") || lower.endsWith(".wad") || lower.endsWith(".bundle") ||
            lower.endsWith(".assets") || lower.endsWith(".ress") || lower.endsWith(".res") ||
            lower.endsWith(".xnb") || lower.endsWith(".bsa") || lower.endsWith(".tar") -> ObbFileType.ARCHIVE_CONTAINER

        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif") -> ObbFileType.IMAGE

        lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".txt") ||
            lower.endsWith(".cfg") || lower.endsWith(".ini") || lower.endsWith(".lua") ||
            lower.endsWith(".csv") || lower.endsWith(".yaml") || lower.endsWith(".yml") ||
            lower.endsWith(".md") || lower.endsWith(".log") || lower.endsWith(".properties") ||
            lower.endsWith(".shader") || lower.endsWith(".glsl") || lower.endsWith(".hlsl") ||
            lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css") ||
            lower.endsWith(".manifest") || lower.endsWith(".plist") -> ObbFileType.TEXT

        else -> ObbFileType.BINARY
    }
}

data class ObbExtensionStat(
    val extension: String, // e.g. ".ros", ".iff", ".cdf", or "(no ext)"
    val fileCount: Int,
    val totalSize: Long
)

sealed class PendingObbBatchExport {
    data class Folder(val folderPrefix: String, val preserveFolders: Boolean = true) : PendingObbBatchExport()
    data class Selected(val paths: Set<String>, val preserveFolders: Boolean = true) : PendingObbBatchExport()
    data class ByExtensions(
        val extensions: Set<String>,
        val customFilter: String,
        val preserveFolders: Boolean = true
    ) : PendingObbBatchExport()
    data class FilteredVisible(val preserveFolders: Boolean = true) : PendingObbBatchExport()
}

fun extractFileExtensionLabel(fileName: String): String {
    val dotIdx = fileName.lastIndexOf('.')
    return if (dotIdx in 1 until fileName.length - 1) {
        fileName.substring(dotIdx).lowercase()
    } else {
        "(no ext)"
    }
}

fun isZipBasedExtension(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".obb") || lower.endsWith(".apk") || lower.endsWith(".jar")
}
