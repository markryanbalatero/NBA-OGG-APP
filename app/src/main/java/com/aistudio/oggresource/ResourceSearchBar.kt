package com.aistudio.oggresource

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedCategory: FilterCategory,
    onSelectCategory: (FilterCategory) -> Unit,
    sortField: SortField,
    onSelectSortField: (SortField) -> Unit,
    sortOrder: SortOrder,
    onToggleSortOrder: () -> Unit,
    totalStreams: Int,
    filteredCount: Int,
    onClearAll: () -> Unit,
    onBatchExtract: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isFiltered = query.isNotBlank() || selectedCategory != FilterCategory.ALL || sortField != SortField.DEFAULT || sortOrder != SortOrder.ASCENDING
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Main Search Input
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("input_search_streams"),
            placeholder = {
                Text(
                    "Search by filename, codec, kHz, stereo/mono, serial...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search icon",
                    tint = if (query.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.testTag("button_clear_search_text")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search query")
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

        // Quick Metadata Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterCategory.values().forEach { category ->
                val isSelected = selectedCategory == category
                val icon = when (category) {
                    FilterCategory.ALL -> Icons.Default.Apps
                    FilterCategory.REPLACED -> Icons.Default.SwapHoriz
                    FilterCategory.VORBIS -> Icons.Default.Audiotrack
                    FilterCategory.OPUS -> Icons.Default.Audiotrack
                    FilterCategory.STEREO -> Icons.Default.Headphones
                    FilterCategory.MONO -> Icons.Default.VolumeDown
                }

                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectCategory(category) },
                    label = {
                        Text(
                            text = category.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.Check else icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("filter_chip_${category.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Sort Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sort Dropdown Button
            Box {
                FilterChip(
                    selected = sortField != SortField.DEFAULT,
                    onClick = { sortMenuExpanded = true },
                    label = {
                        Text(
                            text = "Sort: ${sortField.label}",
                            fontSize = 12.sp,
                            fontWeight = if (sortField != SortField.DEFAULT) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Sort,
                            contentDescription = "Sort Menu",
                            modifier = Modifier.size(14.dp),
                            tint = if (sortField != SortField.DEFAULT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("button_sort_menu")
                )

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    SortField.values().forEach { field ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    field.label,
                                    fontWeight = if (sortField == field) FontWeight.Bold else FontWeight.Normal,
                                    color = if (sortField == field) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                if (sortField == field) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.width(18.dp))
                                }
                            },
                            onClick = {
                                onSelectSortField(field)
                                sortMenuExpanded = false
                            },
                            modifier = Modifier.testTag("sort_menu_item_${field.name.lowercase()}")
                        )
                    }
                }
            }

            // Quick Sort Shortcut Chips
            listOf(
                SortField.NAME to "Name",
                SortField.SIZE to "Size",
                SortField.SAMPLE_RATE to "Sample Rate",
                SortField.CODEC to "Codec"
            ).forEach { (field, shortLabel) ->
                val isSelected = sortField == field
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) {
                            onToggleSortOrder()
                        } else {
                            onSelectSortField(field)
                        }
                    },
                    label = {
                        Text(
                            text = shortLabel,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                if (sortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    } else null,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("quick_sort_chip_${field.name.lowercase()}")
                )
            }

            // Ascending / Descending Toggle Button
            OutlinedIconButton(
                onClick = onToggleSortOrder,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("button_toggle_sort_direction"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = if (sortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = "Toggle Sort Direction (${sortOrder.label})",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Metadata count status row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isFiltered) "Showing $filteredCount of $totalStreams streams" else "$totalStreams streams available",
                style = MaterialTheme.typography.labelSmall,
                color = if (isFiltered && filteredCount == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isFiltered) {
                    TextButton(
                        onClick = onClearAll,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("button_reset_all_filters")
                    ) {
                        Icon(
                            Icons.Default.FilterAltOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (onBatchExtract != null && filteredCount > 0) {
                    TextButton(
                        onClick = onBatchExtract,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("button_batch_extract_folder")
                    ) {
                        Icon(
                            Icons.Default.FolderZip,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isFiltered) "Extract $filteredCount" else "Extract All",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySearchResultsState(
    query: String,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "No Matching Streams Found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (query.isNotBlank()) "No OGG stream metadata matched '$query'." else "No streams match the selected filters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("button_empty_reset_search")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear Search & Filters")
            }
        }
    }
}
