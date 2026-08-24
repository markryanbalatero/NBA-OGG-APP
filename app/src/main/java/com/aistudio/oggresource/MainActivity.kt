package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.oggresource.ui.theme.OggResourceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OggResourceTheme {
                val viewModel: MainViewModel = viewModel()
                val sourceFileUri by viewModel.sourceFileUri.collectAsState()
                val sourceFileName by viewModel.sourceFileName.collectAsState()
                val sourceFileSize by viewModel.sourceFileSize.collectAsState()
                val streams by viewModel.streams.collectAsState()
                val filteredStreams by viewModel.filteredStreams.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val isScanning by viewModel.isScanning.collectAsState()
                val scanProgress by viewModel.scanProgress.collectAsState()
                val isReplacing by viewModel.isReplacing.collectAsState()
                val replaceProgress by viewModel.replaceProgress.collectAsState()
                val currentlyPlaying by viewModel.currentlyPlaying.collectAsState()
                val statusMessage by viewModel.statusMessage.collectAsState()

                val context = LocalContext.current
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                LaunchedEffect(statusMessage) {
                    statusMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearStatusMessage()
                    }
                }

                // File Pickers
                val sourceFilePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let {
                        val name = getFileName(context, it) ?: "resource.obb"
                        viewModel.setSourceFile(context, it, name)
                    }
                }

                val patchSavePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("*/*")
                ) { uri: Uri? ->
                    uri?.let {
                        viewModel.performReplacement(context, it)
                    }
                }

                // Stream being extracted single
                var streamToExtract by remember { mutableStateOf<Stream?>(null) }
                val extractSinglePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("audio/ogg")
                ) { uri: Uri? ->
                    if (uri != null && streamToExtract != null) {
                        viewModel.extractStream(context, streamToExtract!!, uri) {}
                    }
                    streamToExtract = null
                }

                // Stream being replaced
                var streamToReplace by remember { mutableStateOf<Stream?>(null) }
                val replaceFilePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null && streamToReplace != null) {
                        viewModel.setReplacement(context, streamToReplace!!.id, uri)
                    }
                    streamToReplace = null
                }

                val readyToPatchCount = streams.count { it.replacementUri != null && it.replacementSize <= it.stream.slotSize }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 10.dp)
                                    )
                                    Column {
                                        Text(
                                            "NBA OGG Resource Tool",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            "OBB & IFF Audio Stream Modifier",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { sourceFilePicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.testTag("button_open_file")
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Open file")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    },
                    floatingActionButton = {
                        if (readyToPatchCount > 0 && !isReplacing) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    val base = sourceFileName?.replace(Regex("\\.(obb|iff)$", RegexOption.IGNORE_CASE), "") ?: "audio"
                                    val ext = if (sourceFileName?.endsWith(".iff", ignoreCase = true) == true) "iff" else "obb"
                                    patchSavePicker.launch("$base.patched.$ext")
                                },
                                icon = { Icon(Icons.Default.Save, contentDescription = null) },
                                text = { Text("Save Patched File ($readyToPatchCount)") },
                                modifier = Modifier.testTag("fab_save_patched"),
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (sourceFileUri == null) {
                            // Welcome / Empty State
                            EmptyWelcomeState(onPickFile = { sourceFilePicker.launch(arrayOf("*/*")) })
                        } else {
                            // Active File Card
                            SourceFileHeaderCard(
                                fileName = sourceFileName ?: "resource.obb",
                                fileSize = sourceFileSize,
                                streamCount = streams.size,
                                isScanning = isScanning,
                                scanProgress = scanProgress,
                                isReplacing = isReplacing,
                                replaceProgress = replaceProgress,
                                onScanClick = { viewModel.scanSource(context) },
                                onChangeFile = { sourceFilePicker.launch(arrayOf("*/*")) }
                            )

                            if (streams.isNotEmpty()) {
                                // Search bar & Filter
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.setSearchQuery(it) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("input_search_streams"),
                                        placeholder = { Text("Filter by stream name, codec, serial...") },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }

                                // Stream List
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(filteredStreams, key = { it.stream.id }) { streamState ->
                                        StreamItemCard(
                                            streamState = streamState,
                                            isPlaying = currentlyPlaying?.id == streamState.stream.id,
                                            onPlay = { viewModel.playStream(context, streamState.stream) },
                                            onPause = { viewModel.pausePlayback() },
                                            onExtract = {
                                                streamToExtract = streamState.stream
                                                extractSinglePicker.launch(streamState.stream.fileName)
                                            },
                                            onReplace = {
                                                streamToReplace = streamState.stream
                                                replaceFilePicker.launch(arrayOf("audio/ogg", "application/ogg", "*/*"))
                                            },
                                            onClearReplacement = {
                                                viewModel.setReplacement(context, streamState.stream.id, null)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }
}

@Composable
fun EmptyWelcomeState(onPickFile: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AudioFile,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "NBA OGG Resource Tool",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Inspect, audition, extract, and safely replace embedded OGG audio streams inside .obb and .iff game archive files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onPickFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("button_select_file"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select .OBB or .IFF File", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SourceFileHeaderCard(
    fileName: String,
    fileSize: Long,
    streamCount: Int,
    isScanning: Boolean,
    scanProgress: Int,
    isReplacing: Boolean,
    replaceProgress: Int,
    onScanClick: () -> Unit,
    onChangeFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            fileName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatBytes(fileSize)} · ${if (streamCount > 0) "$streamCount streams detected" else "Ready to scan"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(onClick = onChangeFile) {
                    Text("Change")
                }
            }

            if (streamCount == 0 && !isScanning) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onScanClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("button_scan_file"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan File for OGG Streams")
                }
            }

            if (isScanning) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { scanProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Scanning byte chunks... $scanProgress%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (isReplacing) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { replaceProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Writing patched file... $replaceProgress%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun StreamItemCard(
    streamState: StreamState,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onExtract: () -> Unit,
    onReplace: () -> Unit,
    onClearReplacement: () -> Unit
) {
    val stream = streamState.stream
    val hasReplacement = streamState.replacementUri != null
    val isOversized = hasReplacement && streamState.replacementSize > stream.slotSize

    val cardBorder = when {
        isOversized -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
        hasReplacement -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(12.dp))
        else -> Modifier
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(cardBorder),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasReplacement && !isOversized)
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stream.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                stream.codec,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        "Serial: 0x${stream.serial.toString(16).uppercase()} · ${stream.sampleRate ?: "Unknown"} Hz · ${if (stream.channels == 1) "Mono" else if (stream.channels == 2) "Stereo" else "${stream.channels ?: "?"} ch"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Size: ${formatBytes(stream.size)} · Slot capacity: ${formatBytes(stream.slotSize)} · ${stream.pageCount} pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Play / Audition Button
                IconButton(
                    onClick = { if (isPlaying) onPause() else onPlay() },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                        .testTag("button_play_${stream.id}")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (hasReplacement) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isOversized)
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isOversized) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isOversized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isOversized)
                            "Replacement (${formatBytes(streamState.replacementSize)}) exceeds original slot (${formatBytes(stream.slotSize)})"
                        else
                            "Replacement ready (${formatBytes(streamState.replacementSize)} / max ${formatBytes(stream.slotSize)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOversized) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExtract,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("button_extract_${stream.id}"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Extract", fontSize = 12.sp)
                }

                Button(
                    onClick = onReplace,
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("button_replace_${stream.id}"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasReplacement) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (hasReplacement) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (hasReplacement) "Change Swap" else "Replace", fontSize = 12.sp)
                }

                if (hasReplacement) {
                    OutlinedButton(
                        onClick = onClearReplacement,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear replacement", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${(bytes / 1024.0).toInt()} KB"
    return "${String.format("%.2f", bytes / (1024.0 * 1024.0))} MB"
}
