package com.aistudio.oggresource

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.oggresource.ui.theme.OggResourceTheme
import com.aistudio.oggresource.ui.theme.persistDarkTheme
import com.aistudio.oggresource.ui.theme.readSavedDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var isDarkTheme by remember {
                mutableStateOf(readSavedDarkTheme(context, default = true))
            }

            OggResourceTheme(darkTheme = isDarkTheme) {
                val viewModel: MainViewModel = viewModel()
                val sourceFileUri by viewModel.sourceFileUri.collectAsState()
                val sourceFileName by viewModel.sourceFileName.collectAsState()
                val sourceFileSize by viewModel.sourceFileSize.collectAsState()
                val streams by viewModel.streams.collectAsState()
                val filteredStreams by viewModel.filteredStreams.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val selectedFilter by viewModel.selectedFilter.collectAsState()
                val sortField by viewModel.sortField.collectAsState()
                val sortOrder by viewModel.sortOrder.collectAsState()
                val exactNumericMode by viewModel.exactNumericMode.collectAsState()
                val isScanning by viewModel.isScanning.collectAsState()
                val scanProgress by viewModel.scanProgress.collectAsState()
                val scanDetail by viewModel.scanDetail.collectAsState()
                val isReplacing by viewModel.isReplacing.collectAsState()
                val replaceProgress by viewModel.replaceProgress.collectAsState()
                val replaceDetail by viewModel.replaceDetail.collectAsState()
                val archiveType by viewModel.archiveType.collectAsState()
                val workspaceMode by viewModel.workspaceMode.collectAsState()
                val containerStack by viewModel.containerStack.collectAsState()
                val obbEntries by viewModel.obbEntries.collectAsState()
                val visibleObbEntries by viewModel.visibleObbEntries.collectAsState()
                val currentObbFolder by viewModel.currentObbFolder.collectAsState()
                val obbSearchQuery by viewModel.obbSearchQuery.collectAsState()
                val obbFilter by viewModel.obbFilter.collectAsState()
                val obbSortField by viewModel.obbSortField.collectAsState()
                val obbSortAscending by viewModel.obbSortAscending.collectAsState()
                val obbSearchWholeArchive by viewModel.obbSearchWholeArchive.collectAsState()
                val stagedObbReplacements by viewModel.stagedObbReplacements.collectAsState()
                val obbExtensionStats by viewModel.obbExtensionStats.collectAsState()
                val isObbSelectionMode by viewModel.isObbSelectionMode.collectAsState()
                val selectedObbPaths by viewModel.selectedObbPaths.collectAsState()
                val entryPreview by viewModel.entryPreview.collectAsState()
                val patchEngineMode by viewModel.patchEngineMode.collectAsState()
                val globalOperation by viewModel.globalOperation.collectAsState()
                var isSourceHeaderExpanded by remember { mutableStateOf(false) }
                var showVerificationSheet by remember { mutableStateOf(false) }
                var showConverterSheet by remember { mutableStateOf(false) }
                var showExportByTypeSheet by remember { mutableStateOf(false) }
                var showAboutDialog by remember { mutableStateOf(false) }
                var showBugReportDialog by remember { mutableStateOf(false) }
                var showNestedBackConfirmDialog by remember { mutableStateOf(false) }
                val statusMessage by viewModel.statusMessage.collectAsState()

                // Audio Player states
                val activeTrack by viewModel.activeTrack.collectAsState()
                val isPlaying by viewModel.isPlaying.collectAsState()
                val recentStreams by viewModel.recentStreams.collectAsState()

                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(statusMessage) {
                    statusMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearStatusMessage()
                    }
                }

                // Back navigation for OBB selection, OBB folders & nested containers
                BackHandler(
                    enabled = isObbSelectionMode ||
                        (workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS && currentObbFolder.isNotEmpty()) ||
                        containerStack.isNotEmpty()
                ) {
                    if (isObbSelectionMode) {
                        viewModel.clearObbSelection()
                        return@BackHandler
                    }
                    if (workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS && viewModel.navigateObbFolderUp()) {
                        return@BackHandler
                    }
                    if (containerStack.isNotEmpty()) {
                        if (viewModel.hasNestedPendingChanges()) {
                            showNestedBackConfirmDialog = true
                        } else {
                            viewModel.popNestedContainer(context, commitChangesToParent = false)
                        }
                    }
                }

                // File Pickers
                val sourceFilePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                it,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (ignored: Exception) {}
                        val name = getFileName(context, it) ?: "resource.obb"
                        viewModel.setSourceFile(context, it, name)
                    }
                }

                val directAudioPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                it,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (ignored: Exception) {}
                        val name = getFileName(context, it) ?: "audio.ogg"
                        viewModel.playAudioFile(context, it, name)
                    }
                }

                val patchSavePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("*/*")
                ) { uri: Uri? ->
                    uri?.let {
                        viewModel.performReplacement(context, it)
                    }
                }

                // OBB entry extraction & replacement pickers
                var obbEntryToExtract by remember { mutableStateOf<ObbEntry?>(null) }
                val extractObbEntryPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("*/*")
                ) { uri: Uri? ->
                    val target = obbEntryToExtract
                    if (uri != null && target != null) {
                        viewModel.extractObbEntry(context, target, uri)
                    }
                    obbEntryToExtract = null
                }

                var pendingBatchExport by remember { mutableStateOf<PendingObbBatchExport?>(null) }
                val batchExportTreePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { treeUri: Uri? ->
                    val pending = pendingBatchExport
                    if (treeUri != null && pending != null) {
                        viewModel.executePendingObbBatchExport(context, pending, treeUri)
                    }
                    pendingBatchExport = null
                }

                var obbEntryToReplace by remember { mutableStateOf<ObbEntry?>(null) }
                val replaceObbEntryPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    val target = obbEntryToReplace
                    if (uri != null && target != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (ignored: Exception) {}
                        val name = getFileName(context, uri) ?: target.name
                        viewModel.stageObbEntryReplacement(context, target, uri, name)
                    }
                    obbEntryToReplace = null
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

                // Batch stream extractor picker
                val batchExtractPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { treeUri: Uri? ->
                    if (treeUri != null) {
                        val targets = if (filteredStreams.isNotEmpty()) filteredStreams.map { it.stream } else streams.map { it.stream }
                        viewModel.extractMultipleStreams(context, targets, treeUri)
                    }
                }

                // Stream being replaced & Duplicate Protection Prompt
                var streamToReplace by remember { mutableStateOf<Stream?>(null) }
                var pendingDuplicatePrompt by remember { mutableStateOf<DuplicateReplacementPrompt?>(null) }
                val scope = rememberCoroutineScope()

                val replaceFilePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    val currentTarget = streamToReplace
                    if (uri != null && currentTarget != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (ignored: Exception) {}
                        val name = getFileName(context, uri) ?: "audio.ogg"
                        scope.launch {
                            val size = getUriFileSize(context, uri)
                            val duplicates = viewModel.findDuplicateReplacements(
                                targetStreamId = currentTarget.id,
                                uri = uri,
                                fileName = name,
                                fileSize = size
                            )
                            if (duplicates.isNotEmpty()) {
                                pendingDuplicatePrompt = DuplicateReplacementPrompt(
                                    targetStream = currentTarget,
                                    replacementUri = uri,
                                    replacementName = name,
                                    replacementSize = size,
                                    conflictingStreams = duplicates
                                )
                            } else {
                                viewModel.setReplacement(context, currentTarget.id, uri)
                                streamToReplace = null
                            }
                        }
                    } else {
                        streamToReplace = null
                    }
                }

                val isInsideNested = containerStack.isNotEmpty()
                val validStreamSwapCount = streams.count {
                    it.replacementUri != null && (it.replacementSize <= it.stream.slotSize || it.stream.slotSize <= 0L || archiveType == ArchiveContainerType.ZIP_EXPANSION_OBB)
                }
                val readyToPatchCount = validStreamSwapCount + stagedObbReplacements.size

                // Dynamic bottom spacing calculation to prevent overlap
                val fabBottomPadding = when {
                    globalOperation != null && activeTrack != null -> 240.dp
                    globalOperation != null || activeTrack != null -> 140.dp
                    else -> 0.dp
                }

                val listBottomPadding = when {
                    globalOperation != null && activeTrack != null -> 260.dp
                    globalOperation != null || activeTrack != null -> 180.dp
                    else -> 88.dp
                }

                if (showConverterSheet) {
                    AudioConverterScreen(
                        onNavigateBack = { showConverterSheet = false },
                        viewModel = viewModel,
                        onStageConvertedAsReplacement = if (streamToReplace != null) {
                            { uri: Uri, name: String, size: Long ->
                                val currentTarget = streamToReplace!!
                                val duplicates = viewModel.findDuplicateReplacements(
                                    targetStreamId = currentTarget.id,
                                    uri = uri,
                                    fileName = name,
                                    fileSize = size
                                )
                                if (duplicates.isNotEmpty()) {
                                    pendingDuplicatePrompt = DuplicateReplacementPrompt(
                                        targetStream = currentTarget,
                                        replacementUri = uri,
                                        replacementName = name,
                                        replacementSize = size,
                                        conflictingStreams = duplicates
                                    )
                                    showConverterSheet = false
                                } else {
                                    viewModel.setReplacement(context, currentTarget.id, uri)
                                    streamToReplace = null
                                    showConverterSheet = false
                                }
                            }
                        } else null
                    )
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_2k_beats_logo),
                                        contentDescription = "2K BEATS Logo",
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .padding(end = 6.dp)
                                    )
                                    Column {
                                        Text(
                                            "2K BEATS",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "OBB & IFF Audio Stream Modifier",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        isDarkTheme = !isDarkTheme
                                        persistDarkTheme(context, isDarkTheme)
                                    },
                                    modifier = Modifier.testTag("button_toggle_theme")
                                ) {
                                    Icon(
                                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                        contentDescription = if (isDarkTheme) "Switch to logo theme" else "Switch to dark theme",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = { showAboutDialog = true },
                                    modifier = Modifier.testTag("button_about_developer")
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "About Developer & Tool",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (sourceFileUri != null) {
                                    IconButton(
                                        onClick = { showConverterSheet = true },
                                        modifier = Modifier.testTag("button_open_converter")
                                    ) {
                                        Icon(
                                            Icons.Default.Transform,
                                            contentDescription = "Convert MP3 to OGG",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { directAudioPicker.launch(arrayOf("audio/*", "application/ogg", "*/*")) },
                                        modifier = Modifier.testTag("button_audition_file")
                                    ) {
                                        Icon(
                                            Icons.Default.Headphones,
                                            contentDescription = "Audition audio file",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { sourceFilePicker.launch(arrayOf("*/*")) },
                                        modifier = Modifier.testTag("button_open_file")
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Open archive")
                                    }
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
                                    if (isInsideNested) {
                                        viewModel.popNestedContainer(context, commitChangesToParent = true)
                                    } else {
                                        showVerificationSheet = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        if (isInsideNested) Icons.Default.DoneAll else Icons.Default.CheckCircle,
                                        contentDescription = null
                                    )
                                },
                                text = {
                                    Text(
                                        if (isInsideNested) "Apply to OBB ($readyToPatchCount)"
                                        else "Verify & Save ($readyToPatchCount)"
                                    )
                                },
                                modifier = Modifier
                                    .padding(bottom = fabBottomPadding)
                                    .testTag("fab_save_patched"),
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    bottomBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                        ) {
                            GlobalProgressBarContainer(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxWidth()
                            )
                            AudioPlayerBarContainer(
                                viewModel = viewModel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("audio_player_bar")
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // System Permissions & Access Banner (Legit app runtime permission flow)
                        PermissionCheckBanner()

                        if (sourceFileUri == null) {
                            // Welcome / Empty State
                            EmptyWelcomeState(
                                onPickFile = { sourceFilePicker.launch(arrayOf("*/*")) },
                                onAuditionDirect = { directAudioPicker.launch(arrayOf("audio/*", "application/ogg", "*/*")) },
                                onOpenConverter = { showConverterSheet = true },
                                onOpenBugReport = { showBugReportDialog = true }
                            )
                        } else {
                            val isZipObbWithEntries = archiveType == ArchiveContainerType.ZIP_EXPANSION_OBB && obbEntries.isNotEmpty()
                            val isScannedAndReady = (streams.isNotEmpty() || isZipObbWithEntries) && !isScanning && !isReplacing

                            if (containerStack.isNotEmpty()) {
                                NestedContainerBanner(
                                    containerStack = containerStack,
                                    currentName = sourceFileName ?: "embedded.bin",
                                    hasPendingChanges = readyToPatchCount > 0,
                                    onBackWithoutSaving = {
                                        if (readyToPatchCount > 0) {
                                            showNestedBackConfirmDialog = true
                                        } else {
                                            viewModel.popNestedContainer(context, commitChangesToParent = false)
                                        }
                                    },
                                    onCommitToParent = {
                                        viewModel.popNestedContainer(context, commitChangesToParent = true)
                                    }
                                )
                            }

                            if (isScannedAndReady && !isSourceHeaderExpanded) {
                                // Sleek compact bar when scanned to maximize space for contents
                                CompactSourceFileBar(
                                    fileName = sourceFileName ?: "resource.obb",
                                    fileSize = sourceFileSize,
                                    streamCount = if (isZipObbWithEntries && workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS) {
                                        obbEntries.count { !it.isDirectory }
                                    } else streams.size,
                                    itemUnitLabel = if (isZipObbWithEntries && workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS) "files" else "streams",
                                    archiveType = archiveType,
                                    isExpanded = false,
                                    onToggleExpand = { isSourceHeaderExpanded = true },
                                    onChangeFile = { sourceFilePicker.launch(arrayOf("*/*")) }
                                )
                            } else {
                                // Full Active File Card with collapse option when scanned
                                SourceFileHeaderCard(
                                    fileName = sourceFileName ?: "resource.obb",
                                    fileSize = sourceFileSize,
                                    streamCount = if (isZipObbWithEntries && workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS) {
                                        obbEntries.count { !it.isDirectory }
                                    } else streams.size,
                                    itemUnitLabel = if (isZipObbWithEntries && workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS) "files" else "streams",
                                    archiveType = archiveType,
                                    isScanning = isScanning,
                                    scanProgress = scanProgress,
                                    scanDetail = scanDetail,
                                    isReplacing = isReplacing,
                                    replaceProgress = replaceProgress,
                                    replaceDetail = replaceDetail,
                                    onScanClick = { viewModel.scanSource(context) },
                                    onInspectHeader = if (!isZipObbWithEntries) {
                                        { viewModel.inspectCurrentRawHeader(context) }
                                    } else null,
                                    onChangeFile = { sourceFilePicker.launch(arrayOf("*/*")) },
                                    onCollapse = if (isScannedAndReady) { { isSourceHeaderExpanded = false } } else null
                                )
                            }

                            if (isZipObbWithEntries) {
                                ObbWorkspaceModeBar(
                                    workspaceMode = workspaceMode,
                                    totalFilesCount = obbEntries.count { !it.isDirectory },
                                    streamCount = streams.size,
                                    onSelectMode = { viewModel.setWorkspaceMode(context, it) }
                                )
                            }

                            if (isZipObbWithEntries && workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS) {
                                ObbExplorerContent(
                                    allEntries = obbEntries,
                                    visibleEntries = visibleObbEntries,
                                    extensionStats = obbExtensionStats,
                                    currentFolder = currentObbFolder,
                                    searchQuery = obbSearchQuery,
                                    selectedFilter = obbFilter,
                                    sortField = obbSortField,
                                    sortAscending = obbSortAscending,
                                    searchWholeArchive = obbSearchWholeArchive,
                                    stagedReplacements = stagedObbReplacements,
                                    isSelectionMode = isObbSelectionMode,
                                    selectedPaths = selectedObbPaths,
                                    activeTrack = activeTrack,
                                    isPlaying = isPlaying,
                                    listBottomPadding = listBottomPadding,
                                    onNavigateFolder = { viewModel.navigateToObbFolder(it) },
                                    onNavigateUp = { viewModel.navigateObbFolderUp() },
                                    onSearchQueryChange = { viewModel.setObbSearchQuery(it) },
                                    onSelectFilter = { viewModel.setObbFilter(it) },
                                    onSelectSortField = { viewModel.setObbSortField(it) },
                                    onToggleSortDirection = { viewModel.toggleObbSortDirection() },
                                    onToggleSearchScope = { viewModel.toggleObbSearchWholeArchive() },
                                    onResetFilters = { viewModel.resetObbFilters() },
                                    onOpenEntry = { viewModel.openObbEntry(context, it) },
                                    onInspectEntry = { viewModel.inspectObbEntry(context, it) },
                                    onExtractEntry = { entry ->
                                        obbEntryToExtract = entry
                                        extractObbEntryPicker.launch(entry.name)
                                    },
                                    onExtractFolder = { folder ->
                                        pendingBatchExport = PendingObbBatchExport.Folder(folderPrefix = folder, preserveFolders = true)
                                        batchExportTreePicker.launch(null)
                                    },
                                    onReplaceEntry = { entry ->
                                        obbEntryToReplace = entry
                                        replaceObbEntryPicker.launch(arrayOf("*/*"))
                                    },
                                    onClearReplacement = { path -> viewModel.clearObbEntryReplacement(path) },
                                    onPauseAudio = { viewModel.pausePlayback() },
                                    onToggleSelectionMode = { viewModel.toggleObbSelectionMode() },
                                    onToggleSelectEntry = { viewModel.toggleObbEntrySelection(it) },
                                    onSelectAllVisible = { viewModel.selectAllVisibleObbEntries() },
                                    onClearSelection = { viewModel.clearObbSelection() },
                                    onOpenExportByType = { showExportByTypeSheet = true },
                                    onExportSelected = { preserveFolders ->
                                        pendingBatchExport = PendingObbBatchExport.Selected(
                                            paths = selectedObbPaths,
                                            preserveFolders = preserveFolders
                                        )
                                        batchExportTreePicker.launch(null)
                                    },
                                    onExportFiltered = { preserveFolders ->
                                        pendingBatchExport = PendingObbBatchExport.FilteredVisible(preserveFolders = preserveFolders)
                                        batchExportTreePicker.launch(null)
                                    }
                                )
                            } else if (streams.isNotEmpty()) {
                                // Search bar & Metadata Filter Chips
                                ResourceSearchBar(
                                    query = searchQuery,
                                    onQueryChange = { viewModel.setSearchQuery(it) },
                                    exactNumericMode = exactNumericMode,
                                    onToggleExactNumericMode = { viewModel.toggleExactNumericMode() },
                                    selectedCategory = selectedFilter,
                                    onSelectCategory = { viewModel.setFilterCategory(it) },
                                    sortField = sortField,
                                    onSelectSortField = { viewModel.setSortField(it) },
                                    sortOrder = sortOrder,
                                    onToggleSortOrder = { viewModel.toggleSortOrder() },
                                    totalStreams = streams.size,
                                    filteredCount = filteredStreams.size,
                                    onClearAll = { viewModel.clearFilters() },
                                    onBatchExtract = { batchExtractPicker.launch(null) }
                                )

                                if (filteredStreams.isEmpty()) {
                                    EmptySearchResultsState(
                                        query = searchQuery,
                                        onReset = { viewModel.clearFilters() }
                                    )
                                } else {
                                    // Stream List
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = listBottomPadding),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        if (recentStreams.isNotEmpty()) {
                                            item(key = "recent_auditions_section") {
                                                RecentAuditionsSection(
                                                    recentStreams = recentStreams,
                                                    activeTrack = activeTrack,
                                                    isPlaying = isPlaying,
                                                    onPlay = { viewModel.playStream(context, it) },
                                                    onPause = { viewModel.pausePlayback() },
                                                    onExtract = {
                                                        streamToExtract = it
                                                        extractSinglePicker.launch(it.fileName)
                                                    },
                                                    onClearRecents = { viewModel.clearRecentStreams() },
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                            }
                                        }

                                        items(filteredStreams, key = { it.stream.id }) { streamState ->
                                            val isOriginalActive = activeTrack?.streamId == streamState.stream.id
                                            val isOriginalPlaying = isOriginalActive && isPlaying
                                            val isStagedActive = activeTrack?.streamId == "staged_${streamState.stream.id}"
                                            val isStagedPlaying = isStagedActive && isPlaying
                                            val duplicateMatches = viewModel.getDuplicatesForStream(streamState.stream.id)

                                            StreamItemCard(
                                                streamState = streamState,
                                                duplicateMatches = duplicateMatches,
                                                isOriginalPlaying = isOriginalPlaying,
                                                isOriginalActive = isOriginalActive,
                                                isStagedPlaying = isStagedPlaying,
                                                isStagedActive = isStagedActive,
                                                onPlayOriginal = { viewModel.playStream(context, streamState.stream) },
                                                onPlayStaged = { viewModel.playReplacementStream(context, streamState) },
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

                                        item(key = "footer_developer_credit") {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 12.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                FeedbackPromptChip(
                                                    onClick = { showBugReportDialog = true },
                                                    testTag = "button_report_bug_list_footer"
                                                )

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Default.Code,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "Developed by: Mark Ryan Balatero",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showVerificationSheet) {
                        PatchVerificationSheet(
                            sourceFileName = sourceFileName,
                            streams = streams,
                            stagedObbReplacements = stagedObbReplacements,
                            obbEntries = obbEntries,
                            activeTrack = activeTrack,
                            isPlaying = isPlaying,
                            archiveType = archiveType,
                            patchEngineMode = patchEngineMode,
                            onSelectEngineMode = { viewModel.setPatchEngineMode(it) },
                            onPlayOriginal = { stream -> viewModel.playStream(context, stream) },
                            onPlayStaged = { state -> viewModel.playReplacementStream(context, state) },
                            onPause = { viewModel.pausePlayback() },
                            onRemoveReplacement = { streamId -> viewModel.setReplacement(context, streamId, null) },
                            onRemoveObbReplacement = { path -> viewModel.clearObbEntryReplacement(path) },
                            onConfirmSave = {
                                val base = sourceFileName?.replace(Regex("\\.(obb|iff|zip)$", RegexOption.IGNORE_CASE), "") ?: "audio"
                                val ext = when {
                                    sourceFileName?.endsWith(".iff", ignoreCase = true) == true -> "iff"
                                    sourceFileName?.endsWith(".zip", ignoreCase = true) == true -> "zip"
                                    else -> "obb"
                                }
                                patchSavePicker.launch("$base.patched.$ext")
                            },
                            onDismiss = { showVerificationSheet = false }
                        )
                    }

                    entryPreview?.let { preview ->
                        ObbEntryPreviewSheet(
                            preview = preview,
                            onDismiss = { viewModel.dismissEntryPreview() },
                            onOpenAsContainer = { entry -> viewModel.openEntryAsNestedContainer(context, entry) },
                            onExtractEntry = { entry ->
                                obbEntryToExtract = entry
                                extractObbEntryPicker.launch(entry.name)
                            },
                            onReplaceEntry = { entry ->
                                obbEntryToReplace = entry
                                replaceObbEntryPicker.launch(arrayOf("*/*"))
                            }
                        )
                    }

                    if (showExportByTypeSheet) {
                        ObbExportByTypeSheet(
                            extensionStats = obbExtensionStats,
                            resolveMatchingEntries = { exts, custom ->
                                viewModel.resolveMatchingEntriesForExtensions(exts, custom)
                            },
                            onConfirmExport = { exts, custom, preserveFolders ->
                                showExportByTypeSheet = false
                                pendingBatchExport = PendingObbBatchExport.ByExtensions(
                                    extensions = exts,
                                    customFilter = custom,
                                    preserveFolders = preserveFolders
                                )
                                batchExportTreePicker.launch(null)
                            },
                            onDismiss = { showExportByTypeSheet = false }
                        )
                    }

                    if (showNestedBackConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showNestedBackConfirmDialog = false },
                            title = { Text("Unsaved Nested Changes", fontWeight = FontWeight.Bold) },
                            text = {
                                Text("You modified audio inside ${sourceFileName ?: "this embedded file"}. Apply these changes back into the parent OBB before leaving?")
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showNestedBackConfirmDialog = false
                                        viewModel.popNestedContainer(context, commitChangesToParent = true)
                                    }
                                ) {
                                    Text("Apply to OBB")
                                }
                            },
                            dismissButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { showNestedBackConfirmDialog = false }) {
                                        Text("Stay")
                                    }
                                    TextButton(
                                        onClick = {
                                            showNestedBackConfirmDialog = false
                                            viewModel.popNestedContainer(context, commitChangesToParent = false)
                                        }
                                    ) {
                                        Text("Discard", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        )
                    }

                    if (showAboutDialog) {
                        AboutDeveloperDialog(
                            onDismiss = { showAboutDialog = false },
                            onReportBug = { showBugReportDialog = true }
                        )
                    }

                    if (showBugReportDialog) {
                        BugReportDialog(
                            onDismiss = { showBugReportDialog = false }
                        )
                    }

                    if (pendingDuplicatePrompt != null) {
                        val prompt = pendingDuplicatePrompt!!
                        DuplicateAudioWarningDialog(
                            prompt = prompt,
                            onDismiss = {
                                pendingDuplicatePrompt = null
                                streamToReplace = null
                            },
                            onUseAnyway = {
                                viewModel.setReplacement(context, prompt.targetStream.id, prompt.replacementUri)
                                pendingDuplicatePrompt = null
                                streamToReplace = null
                            },
                            onMoveToSlot = {
                                prompt.conflictingStreams.forEach { conflict ->
                                    viewModel.setReplacement(context, conflict.stream.id, null, autoPreview = false)
                                }
                                viewModel.setReplacement(context, prompt.targetStream.id, prompt.replacementUri, autoPreview = true)
                                pendingDuplicatePrompt = null
                                streamToReplace = null
                            }
                        )
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

    private fun getUriFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            return cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

@Composable
fun EmptyWelcomeState(
    onPickFile: () -> Unit,
    onAuditionDirect: () -> Unit,
    onOpenConverter: () -> Unit,
    onOpenBugReport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // App Hero Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_2k_beats_logo),
                        contentDescription = "2K BEATS Logo",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "2K BEATS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        "Open whole .OBB archives, browse folders & inspect assets, or extract and replace embedded OGG audio inside .OBB and .IFF files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Streamlined Badge Row (No emojis, clear padding)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                "OBB Explorer & Patching",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                "Vorbis Transcoding",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Archive Management Actions Card (Positioned Above Converter)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Archive & Audition Tools",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Button(
                        onClick = onPickFile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("button_select_file"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open .OBB, .ZIP, or .IFF Archive", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onAuditionDirect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("button_audition_direct"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Audition External Audio File", fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Conversion Feature Card (Quick Access, Positioned Below Archive Tools)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenConverter() }
                    .testTag("card_start_screen_converter"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Transform,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Convert Audio to OGG",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Vorbis · 700 KB · Mono preset ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = onOpenConverter,
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("button_start_converter"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text("Convert", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Footer Section: Bug Report Link & Developer Credit
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FeedbackPromptChip(
                onClick = onOpenBugReport,
                testTag = "button_report_bug_welcome"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Developed by: Mark Ryan Balatero",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun AboutDeveloperDialog(
    onDismiss: () -> Unit,
    onReportBug: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                painter = painterResource(R.drawable.ic_2k_beats_logo),
                contentDescription = "2K BEATS Logo",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            )
        },
        title = {
            Text(
                "2K BEATS",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Inspect, extract, audition, and replace embedded OGG audio streams inside game .OBB and .IFF archives, with built-in Vorbis audio transcoding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                // Workflow Guide Section
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Workflow Guide",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "1. Convert your custom MP3/WAV audio to OGG using the converter tool.\n" +
                            "2. Select your game .OBB or .IFF archive to scan streams.\n" +
                            "3. Replace any audio slot and audition before saving.\n" +
                            "4. Verify slot capacity and save the modified archive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 17.sp,
                            fontSize = 12.sp
                        )
                    }
                }

                // Developer Credit Card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Developed by",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Mark Ryan Balatero",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            onReportBug()
                        }
                        .testTag("button_report_bug_about")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Feedback,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Bug found or have a suggestion?",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Send it to $DEVELOPER_EMAIL",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportDialog(
    onDismiss: () -> Unit
) {
    var feedbackType by remember { mutableStateOf(FeedbackType.BUG) }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var replyEmail by remember { mutableStateOf("") }
    var includeDeviceInfo by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var canEmailFallback by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isBug = feedbackType == FeedbackType.BUG

    fun openMailtoFallback() {
        openFeedbackMailto(
            context = context,
            type = feedbackType,
            subject = subject,
            message = message,
            replyEmail = replyEmail,
            includeDeviceInfo = includeDeviceInfo
        )
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isSending) onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSuccess) MaterialTheme.colorScheme.primaryContainer
                            else if (isBug) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isSuccess -> Icons.Default.CheckCircle
                            isBug -> Icons.Default.BugReport
                            else -> Icons.Default.Lightbulb
                        },
                        contentDescription = null,
                        tint = when {
                            isSuccess -> MaterialTheme.colorScheme.primary
                            isBug -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.secondary
                        },
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isSuccess -> "Sent to $DEVELOPER_EMAIL"
                            isBug -> "Bug found or have a suggestion?"
                            else -> "Share a suggestion"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isSuccess) {
                            "Thanks. Your note was delivered to the developer inbox."
                        } else {
                            "Choose a type, add details, and send. It goes to $DEVELOPER_EMAIL."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }

            if (isSuccess) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Delivery confirmed",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "If you included a reply email, you can expect a response there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("button_bug_done"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done")
                }
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeedbackTypeChip(
                    selected = isBug,
                    enabled = !isSending,
                    icon = Icons.Default.BugReport,
                    label = "Bug found",
                    modifier = Modifier.weight(1f),
                    onClick = { feedbackType = FeedbackType.BUG }
                )
                FeedbackTypeChip(
                    selected = !isBug,
                    enabled = !isSending,
                    icon = Icons.Default.Lightbulb,
                    label = "Suggestion",
                    modifier = Modifier.weight(1f),
                    onClick = { feedbackType = FeedbackType.SUGGESTION }
                )
            }

            if (errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (canEmailFallback) {
                            OutlinedButton(
                                onClick = { openMailtoFallback() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Email instead")
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = subject,
                onValueChange = {
                    subject = it
                    errorMessage = null
                    canEmailFallback = false
                },
                label = { Text(if (isBug) "Bug summary" else "Suggestion title") },
                placeholder = {
                    Text(if (isBug) "e.g. Conversion failed on MP3 file" else "e.g. Add batch convert for a folder")
                },
                singleLine = true,
                enabled = !isSending,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_bug_subject"),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = message,
                onValueChange = {
                    message = it
                    errorMessage = null
                    canEmailFallback = false
                },
                label = { Text(if (isBug) "What happened and how to reproduce it" else "Your idea and why it would help") },
                placeholder = {
                    Text(
                        if (isBug) {
                            "Describe the steps, what you expected, and what actually happened."
                        } else {
                            "Describe the improvement and where it would show up in the app."
                        }
                    )
                },
                minLines = 4,
                maxLines = 8,
                enabled = !isSending,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_bug_message"),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = replyEmail,
                onValueChange = { replyEmail = it },
                label = { Text("Reply email (optional)") },
                placeholder = { Text("you@email.com") },
                singleLine = true,
                enabled = !isSending,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done
                ),
                supportingText = {
                    Text("Used only if a follow-up is needed. Leave blank to send anonymously.")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !isSending) { includeDeviceInfo = !includeDeviceInfo }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = includeDeviceInfo,
                    onCheckedChange = { if (!isSending) includeDeviceInfo = it },
                    enabled = !isSending
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        "Include device and OS details",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Helps reproduce bugs. Optional for suggestions.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Sending to $DEVELOPER_EMAIL...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSending,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        if (subject.isBlank() && message.isBlank()) {
                            errorMessage = "Add a short summary or details before sending."
                            canEmailFallback = false
                            return@Button
                        }
                        if (replyEmail.isNotBlank() && !replyEmail.contains("@")) {
                            errorMessage = "Reply email looks incomplete. Fix it or leave it blank."
                            canEmailFallback = false
                            return@Button
                        }

                        isSending = true
                        errorMessage = null
                        canEmailFallback = false

                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                sendFeedbackToDeveloper(
                                    type = feedbackType,
                                    subject = subject,
                                    message = message,
                                    replyEmail = replyEmail,
                                    includeDeviceInfo = includeDeviceInfo
                                )
                            }
                            isSending = false
                            if (result.success) {
                                isSuccess = true
                            } else {
                                errorMessage = result.message
                                canEmailFallback = true
                            }
                        }
                    },
                    enabled = !isSending,
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("button_submit_bug_report"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isSending) "Sending..." else if (isBug) "Send bug" else "Send idea")
                }
            }
        }
    }
}

@Composable
fun CompactSourceFileBar(
    fileName: String,
    fileSize: Long,
    streamCount: Int,
    itemUnitLabel: String = "streams",
    archiveType: ArchiveContainerType = ArchiveContainerType.RAW_OGG_BANK,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onChangeFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggleExpand() }
            .testTag("compact_source_file_bar"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = fileName,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatBytes(fileSize)} · $streamCount $itemUnitLabel · ${archiveType.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = onChangeFile,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("button_change_file_compact"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Change", style = MaterialTheme.typography.labelMedium)
                }

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("button_toggle_header_expand")
                ) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse file details" else "Expand file details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
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
    itemUnitLabel: String = "streams",
    archiveType: ArchiveContainerType = ArchiveContainerType.RAW_OGG_BANK,
    isScanning: Boolean,
    scanProgress: Int,
    scanDetail: String = "Scanning byte chunks...",
    isReplacing: Boolean,
    replaceProgress: Int,
    replaceDetail: String = "Writing patched file...",
    onScanClick: () -> Unit,
    onInspectHeader: (() -> Unit)? = null,
    onChangeFile: () -> Unit,
    onCollapse: (() -> Unit)? = null
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
                        Icons.AutoMirrored.Filled.InsertDriveFile,
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
                            "${formatBytes(fileSize)} · ${archiveType.label} · ${if (streamCount > 0) "$streamCount $itemUnitLabel" else "Ready to scan"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onChangeFile) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Change")
                    }

                    if (onCollapse != null) {
                        IconButton(
                            onClick = onCollapse,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("button_collapse_header")
                        ) {
                            Icon(
                                Icons.Default.ExpandLess,
                                contentDescription = "Collapse file details",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

                if (onInspectHeader != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onInspectHeader,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("button_inspect_raw_header"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Inspect Raw Binary Header & Hex")
                    }
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
                    text = "$scanDetail ($scanProgress%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                    text = "$replaceDetail ($replaceProgress%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun StreamItemCard(
    streamState: StreamState,
    duplicateMatches: List<StreamState> = emptyList(),
    isOriginalPlaying: Boolean,
    isOriginalActive: Boolean,
    isStagedPlaying: Boolean,
    isStagedActive: Boolean,
    onPlayOriginal: () -> Unit,
    onPlayStaged: () -> Unit,
    onPause: () -> Unit,
    onExtract: () -> Unit,
    onReplace: () -> Unit,
    onClearReplacement: () -> Unit
) {
    val stream = streamState.stream
    val hasReplacement = streamState.replacementUri != null
    val isOversized = hasReplacement && streamState.replacementSize > stream.slotSize
    val hasDuplicates = hasReplacement && duplicateMatches.isNotEmpty()

    val cardBorder = when {
        isOversized || hasDuplicates -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
        isStagedActive -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(16.dp))
        isOriginalActive -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
        hasReplacement -> Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
        else -> Modifier
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(cardBorder),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isStagedActive -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                isOriginalActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                hasReplacement && !isOversized && !hasDuplicates -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Main stream header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "#${stream.streamIndex}",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            stream.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
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
                        "ID: #${stream.streamIndex} · Serial: 0x${stream.serial.toString(16).uppercase()} (${stream.serial}) · ${stream.sampleRate ?: "Unknown"} Hz · ${if (stream.channels == 1) "Mono" else if (stream.channels == 2) "Stereo" else "${stream.channels ?: "?"} ch"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Size: ${formatBytes(stream.size)} · Slot capacity: ${formatBytes(stream.slotSize)} · ${stream.pageCount} pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Primary Original Audition Button
                IconButton(
                    onClick = { if (isOriginalPlaying) onPause() else onPlayOriginal() },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isOriginalPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                        .testTag("button_play_${stream.id}")
                ) {
                    Icon(
                        imageVector = if (isOriginalPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isOriginalPlaying) "Pause Original" else "Play Original",
                        tint = if (isOriginalPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Staged Replacement Verification Section
            if (hasReplacement) {
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isOversized || hasDuplicates)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isOversized || hasDuplicates) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("staged_preview_card_${stream.id}")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Status & File details
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isOversized || hasDuplicates) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isOversized || hasDuplicates) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = streamState.replacementName ?: "staged_audio.ogg",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val pct = if (stream.slotSize > 0) (streamState.replacementSize * 100 / stream.slotSize) else 0
                                    Text(
                                        text = if (isOversized)
                                            "Oversized: ${formatBytes(streamState.replacementSize)} (Slot max: ${formatBytes(stream.slotSize)})"
                                        else
                                            "Staged: ${formatBytes(streamState.replacementSize)} / ${formatBytes(stream.slotSize)} (${pct}% slot used)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = if (isOversized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (streamState.replacementCodec != null) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${streamState.replacementCodec}${if (streamState.replacementSampleRate != null) " ${streamState.replacementSampleRate / 1000}k" else ""}",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        if (hasDuplicates) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .testTag("warning_duplicate_${stream.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Duplicate Song: Also used in ${duplicateMatches.joinToString { it.stream.fileName }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Audition / Verify Controls: A/B Listen Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Listen to Staged Preview Button
                            Button(
                                onClick = { if (isStagedPlaying) onPause() else onPlayStaged() },
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(38.dp)
                                    .testTag("button_preview_staged_${stream.id}"),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isStagedPlaying) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = if (isStagedPlaying) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            ) {
                                Icon(
                                    imageVector = if (isStagedPlaying) Icons.Default.Pause else Icons.Default.Headphones,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isStagedPlaying) "Pause Preview" else "Listen to Staged",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Quick Original A/B Check Button
                            OutlinedButton(
                                onClick = { if (isOriginalPlaying) onPause() else onPlayOriginal() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("button_ab_original_${stream.id}"),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                colors = if (isOriginalPlaying) ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ) else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Icon(
                                    imageVector = if (isOriginalPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Original", fontSize = 11.sp)
                            }
                        }
                    }
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("button_discard_swap_${stream.id}")
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Discard replacement", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

data class DuplicateReplacementPrompt(
    val targetStream: Stream,
    val replacementUri: Uri,
    val replacementName: String,
    val replacementSize: Long,
    val conflictingStreams: List<StreamState>
)

@Composable
fun DuplicateAudioWarningDialog(
    prompt: DuplicateReplacementPrompt,
    onDismiss: () -> Unit,
    onUseAnyway: () -> Unit,
    onMoveToSlot: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Duplicate Audio Warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "Duplicate Song Detected",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "The audio file \"${prompt.replacementName}\" is already inserted into another stream slot in this archive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Currently assigned to
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Already used in:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        prompt.conflictingStreams.forEach { conflict ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${conflict.stream.fileName} (Serial: 0x${conflict.stream.serial.toString(16).uppercase()})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Target slot
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Target Slot:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${prompt.targetStream.fileName} (Capacity: ${formatBytes(prompt.targetStream.slotSize)})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Text(
                    text = "Inserting duplicate songs will cause the exact same track to repeat in-game. To keep songs unique, choose \"Move to This Slot\" to unassign it from previous slot(s), or \"Cancel\" to pick a different audio file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onMoveToSlot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("button_duplicate_move_slot"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.SwapCalls, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Move to This Slot (Keep Unique)", fontSize = 12.5.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("button_duplicate_cancel"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }

                    FilledTonalButton(
                        onClick = onUseAnyway,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("button_duplicate_use_anyway"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Use Anyway", fontSize = 12.sp)
                    }
                }
            }
        },
        dismissButton = null
    )
}

const val DEVELOPER_EMAIL = "mrkrynbltr.dev@gmail.com"
const val FEEDBACK_FORM_URL = "https://formsubmit.co/ajax/mrkrynbltr.dev@gmail.com"
const val APP_VERSION_LABEL = "1.0"

enum class FeedbackType { BUG, SUGGESTION }

data class FeedbackSubmitResult(
    val success: Boolean,
    val message: String
)

@Composable
fun FeedbackPromptChip(
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Feedback,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Bug found or have a suggestion?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FeedbackTypeChip(
    selected: Boolean,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        shape = RoundedCornerShape(12.dp)
    )
}

private fun sendFeedbackToDeveloper(
    type: FeedbackType,
    subject: String,
    message: String,
    replyEmail: String,
    includeDeviceInfo: Boolean
): FeedbackSubmitResult {
    val typeLabel = if (type == FeedbackType.BUG) "Bug" else "Suggestion"
    val summary = subject.ifBlank { "2K BEATS $typeLabel" }
    val details = message.ifBlank { "No detailed description provided." }
    val senderEmail = replyEmail.trim().ifBlank { DEVELOPER_EMAIL }

    return try {
        val payload = JSONObject().apply {
            put("name", "2K BEATS User")
            put("email", senderEmail)
            put("_replyto", senderEmail)
            put("_subject", "2K BEATS $typeLabel: $summary")
            put("type", typeLabel)
            put("subject", summary)
            put("message", details)
            put("app_version", APP_VERSION_LABEL)
            if (includeDeviceInfo) {
                put("device_manufacturer", android.os.Build.MANUFACTURER)
                put("device_model", android.os.Build.MODEL)
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("sdk_int", android.os.Build.VERSION.SDK_INT)
            }
            put("_template", "table")
            put("_captcha", "false")
        }

        val url = URL(FEEDBACK_FORM_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "2K-BEATS-Android")
            doOutput = true
            doInput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()

        val json = runCatching { JSONObject(body) }.getOrNull()
        val jsonSuccess = parseFormSubmitSuccess(json, responseCode in 200..299)
        val jsonMessage = json?.optString("message").orEmpty()

        when {
            responseCode in 200..299 && jsonSuccess -> FeedbackSubmitResult(
                success = true,
                message = jsonMessage.ifBlank { "Sent to $DEVELOPER_EMAIL" }
            )
            jsonMessage.contains("activate", ignoreCase = true) ||
                jsonMessage.contains("confirm", ignoreCase = true) -> FeedbackSubmitResult(
                success = false,
                message = "FormSubmit needs a one-time inbox confirmation for $DEVELOPER_EMAIL. Check that inbox, then tap Send again. You can also email instead."
            )
            else -> FeedbackSubmitResult(
                success = false,
                message = jsonMessage.ifBlank {
                    "Could not confirm delivery (HTTP $responseCode). Use Email instead so it still reaches $DEVELOPER_EMAIL."
                }
            )
        }
    } catch (e: Exception) {
        FeedbackSubmitResult(
            success = false,
            message = "Network send failed. Use Email instead so it still reaches $DEVELOPER_EMAIL."
        )
    }
}

private fun parseFormSubmitSuccess(json: JSONObject?, httpOk: Boolean): Boolean {
    if (json == null) return httpOk
    return when (val raw = json.opt("success")) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.equals("true", ignoreCase = true) || raw == "1"
        else -> httpOk && !json.has("error")
    }
}

private fun openFeedbackMailto(
    context: Context,
    type: FeedbackType,
    subject: String,
    message: String,
    replyEmail: String,
    includeDeviceInfo: Boolean
) {
    val typeLabel = if (type == FeedbackType.BUG) "Bug" else "Suggestion"
    val summary = subject.ifBlank { "2K BEATS $typeLabel" }
    val body = buildString {
        appendLine(message.ifBlank { "No detailed description provided." })
        appendLine()
        if (replyEmail.isNotBlank()) {
            appendLine("Reply-to: ${replyEmail.trim()}")
        }
        appendLine("App version: $APP_VERSION_LABEL")
        if (includeDeviceInfo) {
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        }
    }
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "2K BEATS $typeLabel: $summary")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Send to $DEVELOPER_EMAIL"))
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${(bytes / 1024.0).toInt()} KB"
    return "${String.format("%.2f", bytes / (1024.0 * 1024.0))} MB"
}
