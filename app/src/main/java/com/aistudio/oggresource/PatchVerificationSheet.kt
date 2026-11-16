package com.aistudio.oggresource

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchVerificationSheet(
    sourceFileName: String?,
    streams: List<StreamState>,
    stagedObbReplacements: Map<String, StagedObbReplacement> = emptyMap(),
    obbEntries: List<ObbEntry> = emptyList(),
    activeTrack: ActiveAudioTrack?,
    isPlaying: Boolean,
    archiveType: ArchiveContainerType = ArchiveContainerType.RAW_OGG_BANK,
    patchEngineMode: PatchEngineMode = PatchEngineMode.PYTHON_DIRECT_INPLACE,
    onSelectEngineMode: (PatchEngineMode) -> Unit = {},
    onPlayOriginal: (Stream) -> Unit,
    onPlayStaged: (StreamState) -> Unit,
    onPause: () -> Unit,
    onRemoveReplacement: (String) -> Unit,
    onRemoveObbReplacement: (String) -> Unit = {},
    onConfirmSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isZip = archiveType == ArchiveContainerType.ZIP_EXPANSION_OBB || streams.any { it.stream.isZipEntry }
    val replacedStreams = streams.filter { it.replacementUri != null && !stagedObbReplacements.containsKey(it.stream.zipEntryPath ?: "") }
    val validStreamCount = replacedStreams.count { it.replacementSize <= it.stream.slotSize || it.stream.slotSize <= 0L || isZip }
    val oversizedCount = if (isZip) 0 else replacedStreams.count { it.replacementSize > it.stream.slotSize && it.stream.slotSize > 0L }
    val totalModifiedCount = validStreamCount + stagedObbReplacements.size

    // Detect duplicate song assignments across replaced streams
    val duplicateMap = replacedStreams
        .groupBy { (it.replacementName ?: it.replacementUri.toString()).lowercase() }
        .filter { it.value.size > 1 }
    val hasDuplicates = duplicateMap.isNotEmpty()

    val obbEntryMap = remember(obbEntries) { obbEntries.associateBy { it.path } }
    val totalOriginalSize = replacedStreams.sumOf { it.stream.size } +
        stagedObbReplacements.keys.sumOf { obbEntryMap[it]?.uncompressedSize ?: 0L }
    val totalReplacementSize = replacedStreams.sumOf { it.replacementSize } +
        stagedObbReplacements.values.sumOf { it.replacementSize }
    val sizeDelta = totalReplacementSize - totalOriginalSize

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.testTag("patch_verification_sheet"),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Verify Modified Files",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isZip) "Review modified files & CRC32 rebuild settings before saving OBB"
                        else "Review staged audio replacements and verify zero byte offsets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("button_close_verification")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close verification")
                }
            }

            if (isZip) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "ZIP OBB Rebuilder: Preserves STORED / DEFLATED entry layout and updates CRC32 checksums automatically.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            } else {
                // Engine Mode Selection Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Engineering,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Patching Engine Mode",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = patchEngineMode == PatchEngineMode.PYTHON_DIRECT_INPLACE,
                                onClick = { onSelectEngineMode(PatchEngineMode.PYTHON_DIRECT_INPLACE) },
                                label = { Text("Python Direct In-Place", fontSize = 11.5.sp) },
                                leadingIcon = {
                                    if (patchEngineMode == PatchEngineMode.PYTHON_DIRECT_INPLACE) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = patchEngineMode == PatchEngineMode.OGG_SERIAL_SYNC,
                                onClick = { onSelectEngineMode(PatchEngineMode.OGG_SERIAL_SYNC) },
                                label = { Text("Auto Serial Sync", fontSize = 11.5.sp) },
                                leadingIcon = {
                                    if (patchEngineMode == PatchEngineMode.OGG_SERIAL_SYNC) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = patchEngineMode.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Summary Info Banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (oversizedCount > 0)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                else
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(
                    1.dp,
                    if (oversizedCount > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (oversizedCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (oversizedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "$totalModifiedCount archive item(s) modified",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val deltaText = if (sizeDelta >= 0) "+${formatBytes(sizeDelta)}" else "-${formatBytes(-sizeDelta)}"
                            Text(
                                text = "Original: ${formatBytes(totalOriginalSize)} → New: ${formatBytes(totalReplacementSize)} ($deltaText)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (oversizedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.error
                        ) {
                            Text(
                                text = "$oversizedCount Oversized",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Duplicate warning banner if any songs are double-inserted
            if (hasDuplicates) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Duplicate Songs Detected",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "${duplicateMap.values.sumOf { it.size }} slots share identical replacement songs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // List of modified items (OBB entries + streams)
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(stagedObbReplacements.values.toList(), key = { "obb_${it.entryPath}" }) { staged ->
                    val origEntry = obbEntryMap[staged.entryPath]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = staged.entryPath,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (staged.fromNestedContainer) {
                                        "Nested Container Patched · ${formatBytes(origEntry?.uncompressedSize ?: 0L)} → ${formatBytes(staged.replacementSize)}"
                                    } else {
                                        "Replaced with ${staged.replacementName} · ${formatBytes(staged.replacementSize)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onRemoveObbReplacement(staged.entryPath) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Remove replacement",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                items(replacedStreams, key = { it.stream.id }) { streamState ->
                    val dupKey = (streamState.replacementName ?: streamState.replacementUri.toString()).lowercase()
                    val dupSlots = duplicateMap[dupKey]?.filter { it.stream.id != streamState.stream.id }?.map { it.stream.fileName } ?: emptyList()

                    VerificationItemCard(
                        streamState = streamState,
                        duplicateSlots = dupSlots,
                        activeTrack = activeTrack,
                        isPlaying = isPlaying,
                        onPlayOriginal = { onPlayOriginal(streamState.stream) },
                        onPlayStaged = { onPlayStaged(streamState) },
                        onPause = onPause,
                        onRemove = { onRemoveReplacement(streamState.stream.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("button_cancel_verification"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Back to List")
                }

                Button(
                    onClick = {
                        onDismiss()
                        onConfirmSave()
                    },
                    enabled = totalModifiedCount > 0,
                    modifier = Modifier
                        .weight(1.5f)
                        .height(48.dp)
                        .testTag("button_confirm_save_patch"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Patched Archive ($totalModifiedCount)")
                }
            }
        }
    }
}

@Composable
fun VerificationItemCard(
    streamState: StreamState,
    duplicateSlots: List<String> = emptyList(),
    activeTrack: ActiveAudioTrack?,
    isPlaying: Boolean,
    onPlayOriginal: () -> Unit,
    onPlayStaged: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stream = streamState.stream
    val isOversized = if (stream.isZipEntry) false else streamState.replacementSize > stream.slotSize
    val hasDuplicates = duplicateSlots.isNotEmpty()
    val isOrigActive = activeTrack?.streamId == stream.id
    val isOrigPlaying = isOrigActive && isPlaying
    val isStagedActive = activeTrack?.streamId == "staged_${stream.id}"
    val isStagedPlaying = isStagedActive && isPlaying

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("verification_item_${stream.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(
            1.dp,
            if (isOversized || hasDuplicates) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Target File Header & Remove Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stream.zipEntryPath ?: stream.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (stream.isZipEntry) "ZIP Entry · Serial: 0x${stream.serial.toString(16).uppercase()}"
                        else "Serial: 0x${stream.serial.toString(16).uppercase()} · Slot: ${formatBytes(stream.slotSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("button_remove_swap_${stream.id}")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Remove swap",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (hasDuplicates) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
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
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Duplicate: Also staged in ${duplicateSlots.joinToString()}",
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

            // Two-column side-by-side comparison
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Original Box
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isOrigActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (isOrigActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "ORIGINAL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${stream.codec} · ${formatBytes(stream.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${stream.sampleRate ?: "?"} Hz · ${if (stream.channels == 1) "Mono" else "Stereo"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FilledTonalButton(
                            onClick = { if (isOrigPlaying) onPause() else onPlayOriginal() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isOrigPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Icon(
                                if (isOrigPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isOrigPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isOrigPlaying) "Playing" else "Original",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOrigPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Replacement Box
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        isOversized -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                        isStagedActive -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isOversized -> MaterialTheme.colorScheme.error
                            isStagedActive -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "REPLACEMENT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isOversized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                fontSize = 9.sp
                            )
                            if (isOversized) {
                                Text(
                                    text = "OVERSIZED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 8.sp
                                )
                            } else {
                                Text(
                                    text = "✓ FITS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 8.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = streamState.replacementName ?: "replacement.ogg",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatBytes(streamState.replacementSize)} (${streamState.replacementCodec ?: "OGG"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOversized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FilledTonalButton(
                            onClick = { if (isStagedPlaying) onPause() else onPlayStaged() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isStagedPlaying) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Icon(
                                if (isStagedPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isStagedPlaying) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isStagedPlaying) "Playing" else "Staged",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isStagedPlaying) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onTertiaryContainer,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
