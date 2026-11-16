package com.aistudio.oggresource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NestedContainerBanner(
    containerStack: List<ContainerLayer>,
    currentName: String,
    hasPendingChanges: Boolean,
    onBackWithoutSaving: () -> Unit,
    onCommitToParent: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (containerStack.isEmpty()) return

    val chain = (containerStack.map { it.displayName } + currentName).joinToString(" › ")
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("nested_container_banner"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Nested container depth",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Inside Embedded File",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = chain,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onBackWithoutSaving,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("button_nested_back"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to parent archive",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Back", fontSize = 12.sp)
                    }

                    if (hasPendingChanges) {
                        Button(
                            onClick = onCommitToParent,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("button_commit_nested_changes"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Apply to OBB", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ObbWorkspaceModeBar(
    workspaceMode: ArchiveWorkspaceMode,
    totalFilesCount: Int,
    streamCount: Int,
    onSelectMode: (ArchiveWorkspaceMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val isContents = workspaceMode == ArchiveWorkspaceMode.OBB_CONTENTS
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onSelectMode(ArchiveWorkspaceMode.OBB_CONTENTS) }
                    .testTag("tab_obb_contents"),
                shape = RoundedCornerShape(9.dp),
                color = if (isContents) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = null,
                        tint = if (isContents) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (totalFilesCount > 0) "OBB Contents ($totalFilesCount)" else "OBB Contents",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isContents) FontWeight.Bold else FontWeight.Medium,
                        color = if (isContents) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val isStreams = workspaceMode == ArchiveWorkspaceMode.AUDIO_STREAMS
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onSelectMode(ArchiveWorkspaceMode.AUDIO_STREAMS) }
                    .testTag("tab_audio_streams"),
                shape = RoundedCornerShape(9.dp),
                color = if (isStreams) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = if (isStreams) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (streamCount > 0) "Audio Streams ($streamCount)" else "Audio Streams",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isStreams) FontWeight.Bold else FontWeight.Medium,
                        color = if (isStreams) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ObbExplorerContent(
    allEntries: List<ObbEntry>,
    visibleEntries: List<ObbEntry>,
    extensionStats: List<ObbExtensionStat>,
    currentFolder: String,
    searchQuery: String,
    selectedFilter: ObbFilterCategory,
    sortField: ObbSortField,
    sortAscending: Boolean,
    searchWholeArchive: Boolean,
    stagedReplacements: Map<String, StagedObbReplacement>,
    isSelectionMode: Boolean,
    selectedPaths: Set<String>,
    activeTrack: ActiveAudioTrack?,
    isPlaying: Boolean,
    listBottomPadding: androidx.compose.ui.unit.Dp,
    onNavigateFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (ObbFilterCategory) -> Unit,
    onSelectSortField: (ObbSortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleSearchScope: () -> Unit,
    onResetFilters: () -> Unit,
    onOpenEntry: (ObbEntry) -> Unit,
    onInspectEntry: (ObbEntry) -> Unit,
    onExtractEntry: (ObbEntry) -> Unit,
    onExtractFolder: (String) -> Unit,
    onReplaceEntry: (ObbEntry) -> Unit,
    onClearReplacement: (String) -> Unit,
    onPauseAudio: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onToggleSelectEntry: (ObbEntry) -> Unit,
    onSelectAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenExportByType: () -> Unit,
    onExportSelected: (Boolean) -> Unit,
    onExportFiltered: (Boolean) -> Unit
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Breadcrumb bar
        ObbBreadcrumbBar(
            currentFolder = currentFolder,
            onNavigateFolder = onNavigateFolder,
            onNavigateUp = onNavigateUp,
            onExtractCurrentFolder = { onExtractFolder(currentFolder) }
        )

        // Search input + Scope toggle
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_search_obb_contents"),
                placeholder = {
                    Text(
                        "Search .ros, .iff, .cdf, .ogg, folders...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search OBB entries",
                        tint = if (searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotBlank()) {
                            FilterChip(
                                selected = searchWholeArchive,
                                onClick = onToggleSearchScope,
                                label = {
                                    Text(
                                        if (searchWholeArchive) "All OBB" else "Folder",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .height(28.dp)
                                    .testTag("button_obb_search_scope")
                            )
                            IconButton(
                                onClick = { onSearchQueryChange("") },
                                modifier = Modifier.testTag("button_clear_obb_search")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Category chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ObbFilterCategory.values().forEach { category ->
                    val isSelected = selectedFilter == category
                    val label = if (category == ObbFilterCategory.MODIFIED && stagedReplacements.isNotEmpty()) {
                        "Modified (${stagedReplacements.size})"
                    } else {
                        category.label
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectFilter(category) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        } else null,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("obb_filter_${category.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Sort + Summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box {
                        FilterChip(
                            selected = sortField != ObbSortField.TYPE_THEN_NAME,
                            onClick = { sortMenuExpanded = true },
                            label = { Text("Sort: ${sortField.label}", fontSize = 11.5.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("button_obb_sort_menu")
                        )
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            ObbSortField.values().forEach { field ->
                                DropdownMenuItem(
                                    text = { Text(field.label) },
                                    leadingIcon = {
                                        if (sortField == field) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    onClick = {
                                        onSelectSortField(field)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedIconButton(
                        onClick = onToggleSortDirection,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("button_obb_sort_direction"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = "Toggle sort direction",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                val totalFilesInArchive = remember(allEntries) { allEntries.count { !it.isDirectory } }
                Text(
                    text = "${visibleEntries.size} shown · $totalFilesInArchive total files",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Export Actions Row: Export by Type, Multi-Select, Export Shown
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onOpenExportByType,
                    enabled = extensionStats.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("button_export_by_type"),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (extensionStats.isNotEmpty()) "Export by Type (${extensionStats.size})" else "Export by Type",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onToggleSelectionMode,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("button_toggle_obb_select"),
                    shape = RoundedCornerShape(9.dp),
                    colors = if (isSelectionMode) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Icon(
                        imageVector = if (isSelectionMode) Icons.Default.CheckBox else Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isSelectionMode) "Selecting" else "Select",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (!isSelectionMode && (searchQuery.isNotBlank() || selectedFilter != ObbFilterCategory.ALL) && visibleEntries.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { onExportFiltered(true) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("button_export_shown"),
                        shape = RoundedCornerShape(9.dp)
                    ) {
                        Icon(Icons.Default.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Export Shown (${visibleEntries.size})",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (isSelectionMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("obb_selection_action_bar"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${selectedPaths.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextButton(
                            onClick = onSelectAllVisible,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("button_select_all_visible")
                        ) {
                            Text("All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onExportSelected(true) },
                            enabled = selectedPaths.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("button_export_selected"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export (${selectedPaths.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = onClearSelection,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("button_cancel_obb_selection")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel selection",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        if (visibleEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOff,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (searchQuery.isNotBlank() || selectedFilter != ObbFilterCategory.ALL) {
                                "No Matching Files Found"
                            } else {
                                "This Folder Is Empty"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No entries inside the OBB matched '$searchQuery'."
                            } else {
                                "Try clearing active filters or navigating back up."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        if (searchQuery.isNotBlank() || selectedFilter != ObbFilterCategory.ALL) {
                            OutlinedButton(onClick = onResetFilters, shape = RoundedCornerShape(10.dp)) {
                                Text("Reset Filters")
                            }
                        } else if (currentFolder.isNotEmpty()) {
                            OutlinedButton(onClick = onNavigateUp, shape = RoundedCornerShape(10.dp)) {
                                Text("Go Up a Folder")
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = listBottomPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visibleEntries, key = { "${if (it.isDirectory) "dir" else "file"}_${it.path}" }) { entry ->
                    val isSelected = selectedPaths.contains(entry.path)
                    if (entry.isDirectory) {
                        ObbFolderCard(
                            entry = entry,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onToggleSelect = { onToggleSelectEntry(entry) },
                            onOpen = { onNavigateFolder(entry.path) },
                            onExtractFolder = { onExtractFolder(entry.path) }
                        )
                    } else {
                        val staged = stagedReplacements[entry.path]
                        val isTrackActive = activeTrack?.streamId == "obb_entry_${entry.path}"
                        val isTrackPlaying = isTrackActive && isPlaying
                        ObbFileCard(
                            entry = entry,
                            stagedReplacement = staged,
                            showFullPath = (searchQuery.isNotBlank() && searchWholeArchive) ||
                                (selectedFilter != ObbFilterCategory.ALL && selectedFilter != ObbFilterCategory.FOLDERS && searchWholeArchive),
                            isAudioPlaying = isTrackPlaying,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onToggleSelect = { onToggleSelectEntry(entry) },
                            onPrimaryOpen = {
                                if (isTrackPlaying) onPauseAudio() else onOpenEntry(entry)
                            },
                            onInspect = { onInspectEntry(entry) },
                            onExtract = { onExtractEntry(entry) },
                            onReplace = { onReplaceEntry(entry) },
                            onClearReplacement = { onClearReplacement(entry.path) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObbBreadcrumbBar(
    currentFolder: String,
    onNavigateFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onExtractCurrentFolder: () -> Unit
) {
    val segments = remember(currentFolder) {
        if (currentFolder.isEmpty()) emptyList() else currentFolder.split('/').filter { it.isNotEmpty() }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (segments.isNotEmpty()) {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("button_obb_folder_up")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go up folder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateFolder("") }
                        .testTag("breadcrumb_obb_root"),
                    shape = RoundedCornerShape(8.dp),
                    color = if (segments.isEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = "OBB Root",
                            tint = if (segments.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "OBB Root",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (segments.isEmpty()) FontWeight.Bold else FontWeight.Medium,
                            color = if (segments.isEmpty()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                var built = ""
                segments.forEachIndexed { index, part ->
                    built = if (built.isEmpty()) part else "$built/$part"
                    val targetPath = built
                    val isLast = index == segments.lastIndex
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onNavigateFolder(targetPath) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isLast) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = part,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                            color = if (isLast) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            FilledTonalButton(
                onClick = onExtractCurrentFolder,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("button_extract_obb_all_or_folder"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (currentFolder.isEmpty()) "Export All" else "Export",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ObbFolderCard(
    entry: ObbEntry,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onExtractFolder: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { if (isSelectionMode) onToggleSelect() else onOpen() },
                onLongClick = { onToggleSelect() }
            )
            .testTag("obb_folder_card_${entry.path}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entry.childCount} item(s) · ${formatBytes(entry.folderTotalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isSelectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onExtractFolder,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export folder ${entry.name}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ObbFileCard(
    entry: ObbEntry,
    stagedReplacement: StagedObbReplacement?,
    showFullPath: Boolean,
    isAudioPlaying: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onPrimaryOpen: () -> Unit,
    onInspect: () -> Unit,
    onExtract: () -> Unit,
    onReplace: () -> Unit,
    onClearReplacement: () -> Unit
) {
    val hasReplacement = stagedReplacement != null
    val ext = entry.name.substringAfterLast('.', "").uppercase().ifEmpty { "BIN" }
    val methodLabel = if (entry.compressionMethod == 0) "STORED" else "DEFLATED"

    val (typeIcon, typeTint, typeBg) = when (entry.fileType) {
        ObbFileType.AUDIO -> Triple(Icons.Default.MusicNote, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        ObbFileType.ARCHIVE_CONTAINER -> Triple(Icons.Default.Inventory2, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer)
        ObbFileType.IMAGE -> Triple(Icons.Default.Image, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        ObbFileType.TEXT -> Triple(Icons.Default.Description, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
        else -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .combinedClickable(
                onClick = { if (isSelectionMode) onToggleSelect() else onPrimaryOpen() },
                onLongClick = { onToggleSelect() }
            )
            .then(
                when {
                    isSelected -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(15.dp))
                    hasReplacement -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(15.dp))
                    isAudioPlaying -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(15.dp))
                    else -> Modifier
                }
            )
            .testTag("obb_file_card_${entry.path}"),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                isAudioPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                hasReplacement -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.16f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect() },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(typeBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = entry.fileType.label,
                            tint = typeTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = ext,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        if (showFullPath && entry.parentPath.isNotEmpty()) {
                            Text(
                                text = entry.parentPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "${formatBytes(entry.uncompressedSize)} · $methodLabel · CRC ${entry.crc.toString(16).uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                if (entry.fileType == ObbFileType.AUDIO) {
                    IconButton(
                        onClick = onPrimaryOpen,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isAudioPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isAudioPlaying) "Pause" else "Play audio",
                            tint = if (isAudioPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                } else {
                    IconButton(
                        onClick = onInspect,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Inspect file contents",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (stagedReplacement != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (stagedReplacement.fromNestedContainer) {
                                "Patched internal streams (${formatBytes(stagedReplacement.replacementSize)})"
                            } else {
                                "Staged swap: ${stagedReplacement.replacementName} (${formatBytes(stagedReplacement.replacementSize)})"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val primaryLabel = when (entry.fileType) {
                    ObbFileType.AUDIO -> if (isAudioPlaying) "Pause" else "Play"
                    ObbFileType.ARCHIVE_CONTAINER -> "Open Inside"
                    else -> "View"
                }
                val primaryIcon = when (entry.fileType) {
                    ObbFileType.AUDIO -> if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                    ObbFileType.ARCHIVE_CONTAINER -> Icons.Default.FolderOpen
                    else -> Icons.Default.Preview
                }

                FilledTonalButton(
                    onClick = onPrimaryOpen,
                    modifier = Modifier
                        .weight(1.15f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(primaryIcon, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(primaryLabel, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onExtract,
                    modifier = Modifier
                        .weight(0.95f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export", fontSize = 11.5.sp)
                }

                OutlinedButton(
                    onClick = onReplace,
                    modifier = Modifier
                        .weight(0.95f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Replace", fontSize = 11.5.sp)
                }

                if (hasReplacement) {
                    OutlinedButton(
                        onClick = onClearReplacement,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear replacement", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObbEntryPreviewSheet(
    preview: ObbEntryPreview,
    onDismiss: () -> Unit,
    onOpenAsContainer: (ObbEntry) -> Unit,
    onExtractEntry: (ObbEntry) -> Unit,
    onReplaceEntry: (ObbEntry) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (preview) {
                is ObbEntryPreview.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Reading file contents from OBB...", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is ObbEntryPreview.TextPreview -> {
                    PreviewHeader(entry = preview.entry, subtitle = "${preview.magicLabel} · ${preview.lineCount} lines")
                    if (preview.isTruncated) {
                        Text(
                            text = "Showing first 256 KB of ${formatBytes(preview.entry.uncompressedSize)}. Export file to inspect everything.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 380.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = preview.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText(preview.entry.name, preview.text))
                                Toast.makeText(context, "Copied text preview", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy")
                        }
                        OutlinedButton(
                            onClick = { onExtractEntry(preview.entry) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export")
                        }
                        Button(
                            onClick = { onReplaceEntry(preview.entry) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Replace")
                        }
                    }
                }

                is ObbEntryPreview.ImagePreview -> {
                    PreviewHeader(entry = preview.entry, subtitle = "Image · ${preview.width} × ${preview.height} px")
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 360.dp)
                    ) {
                        Image(
                            bitmap = preview.bitmap.asImageBitmap(),
                            contentDescription = preview.entry.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onExtractEntry(preview.entry) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export Image")
                        }
                        Button(
                            onClick = { onReplaceEntry(preview.entry) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Replace Image")
                        }
                    }
                }

                is ObbEntryPreview.HexPreview -> {
                    PreviewHeader(
                        entry = preview.entry,
                        subtitle = "${preview.magicLabel} · First ${preview.bytesInspected} bytes"
                    )

                    if (preview.hasEmbeddedOggSignature) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Detected embedded OggS audio signature inside this file!",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 300.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = preview.hexDump.ifEmpty { "(Empty file)" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (preview.isObbEntry) {
                        Button(
                            onClick = { onOpenAsContainer(preview.entry) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("button_scan_entry_container"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open & Scan for Embedded Audio / Sub-Archive")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onExtractEntry(preview.entry) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export File")
                            }
                            OutlinedButton(
                                onClick = { onReplaceEntry(preview.entry) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Replace File")
                            }
                        }
                    }
                }

                is ObbEntryPreview.ErrorPreview -> {
                    PreviewHeader(entry = preview.entry, subtitle = "Inspection Error")
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = preview.message,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    entry: ObbEntry,
    subtitle: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${entry.path} · ${formatBytes(entry.uncompressedSize)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ObbExportByTypeSheet(
    extensionStats: List<ObbExtensionStat>,
    resolveMatchingEntries: (Set<String>, String) -> List<ObbEntry>,
    onConfirmExport: (selectedExtensions: Set<String>, customFilter: String, preserveFolders: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedExtensions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customFilter by remember { mutableStateOf("") }
    var preserveFolders by remember { mutableStateOf(true) }

    val matchingEntries = remember(selectedExtensions, customFilter, extensionStats) {
        resolveMatchingEntries(selectedExtensions, customFilter)
    }
    val totalExportSize = remember(matchingEntries) {
        matchingEntries.sumOf { it.uncompressedSize }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export Files by Type",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Export .ros, .iff, .cdf, audio, textures, or any custom extension with exact filenames preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detected Extensions (${extensionStats.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { selectedExtensions = extensionStats.map { it.extension }.toSet() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Select All", fontSize = 12.sp)
                    }
                    if (selectedExtensions.isNotEmpty() || customFilter.isNotBlank()) {
                        TextButton(
                            onClick = {
                                selectedExtensions = emptySet()
                                customFilter = ""
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (extensionStats.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            extensionStats.forEach { stat ->
                                val isChecked = selectedExtensions.contains(stat.extension)
                                FilterChip(
                                    selected = isChecked,
                                    onClick = {
                                        selectedExtensions = if (isChecked) {
                                            selectedExtensions - stat.extension
                                        } else {
                                            selectedExtensions + stat.extension
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = "${stat.extension} (${stat.fileCount})",
                                            fontSize = 12.sp,
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    leadingIcon = if (isChecked) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("export_ext_chip_${stat.extension}")
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = customFilter,
                onValueChange = { customFilter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_custom_export_extensions"),
                label = { Text("Custom extensions (optional)") },
                placeholder = { Text(".ros, .iff, .cdf, .ogg, .dds") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (customFilter.isNotEmpty()) {
                        IconButton(onClick = { customFilter = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear custom extensions")
                        }
                    }
                }
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Preserve OBB folder structure",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (preserveFolders) {
                                "Recreates subfolders inside your chosen directory"
                            } else {
                                "Flattens all matched files into a single folder"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = preserveFolders,
                        onCheckedChange = { preserveFolders = it },
                        modifier = Modifier.testTag("switch_preserve_export_folders")
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (matchingEntries.isNotEmpty()) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (matchingEntries.isNotEmpty()) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (matchingEntries.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (matchingEntries.isNotEmpty()) {
                            "Ready to export ${matchingEntries.size} file(s) · ${formatBytes(totalExportSize)}"
                        } else {
                            "Select one or more extensions above or type extensions to export"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(0.4f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onConfirmExport(selectedExtensions, customFilter, preserveFolders) },
                    enabled = matchingEntries.isNotEmpty(),
                    modifier = Modifier
                        .weight(0.6f)
                        .height(48.dp)
                        .testTag("button_confirm_export_by_type"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Choose Folder & Export", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
