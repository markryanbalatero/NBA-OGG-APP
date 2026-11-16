package com.aistudio.oggresource

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioConverterScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel,
    onStageConvertedAsReplacement: ((Uri, String, Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Collect persistent ViewModel state
    val inputUri by viewModel.converterInputUri.collectAsStateWithLifecycle()
    val inputFileName by viewModel.converterInputFileName.collectAsStateWithLifecycle()
    val inputFileSize by viewModel.converterInputFileSize.collectAsStateWithLifecycle()
    val selectedCodec by viewModel.converterSelectedCodec.collectAsStateWithLifecycle()
    val targetSizeText by viewModel.converterTargetSizeText.collectAsStateWithLifecycle()
    val targetSizeUnit by viewModel.converterTargetSizeUnit.collectAsStateWithLifecycle()
    val selectedChannelMode by viewModel.converterSelectedChannelMode.collectAsStateWithLifecycle()
    val selectedSampleRate by viewModel.converterSelectedSampleRate.collectAsStateWithLifecycle()

    val isConverting by viewModel.isConvertingAudio.collectAsStateWithLifecycle()
    val conversionProgress by viewModel.conversionProgress.collectAsStateWithLifecycle()
    val conversionResult by viewModel.conversionResult.collectAsStateWithLifecycle()
    val errorMessage by viewModel.conversionErrorMessage.collectAsStateWithLifecycle()

    // Player state
    val activeTrack by viewModel.activeTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val isLooping by viewModel.isLooping.collectAsStateWithLifecycle()

    // Picker for source MP3 / Audio
    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {}
            val name = getFileNameFromUri(context, uri) ?: "audio.mp3"
            val size = getFileSizeFromUri(context, uri)
            viewModel.setConverterInput(uri, name, size)
            viewModel.clearConverterResult()
        }
    }

    // Save Converted OGG Picker
    val saveFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/ogg")
    ) { destUri: Uri? ->
        if (destUri != null && conversionResult != null) {
            coroutineScope.launch {
                try {
                    UriUtils.openOutputStream(context, destUri)?.use { out ->
                        FileInputStream(conversionResult!!.outputFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("audio_converter_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Transform,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Audio to OGG Converter",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Transcode audio with calibrated tempo & pitch",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("button_close_converter")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back to Archive View"
                        )
                    }
                },
                actions = {
                    if (inputUri != null) {
                        IconButton(
                            onClick = {
                                viewModel.clearConverterResult()
                                viewModel.setConverterInput(null, null, 0L)
                            },
                            modifier = Modifier.testTag("button_reset_converter")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset Converter",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // ----------------------------------------------------
            // TOP PROGRESS BAR & STATUS SECTION (Moved to top!)
            // ----------------------------------------------------
            if (isConverting) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Converting to OGG...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = "$conversionProgress%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { conversionProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    }
                }
            } else if (errorMessage != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else if (conversionResult != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Converted Audio Ready",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "${formatBytes(conversionResult!!.convertedSize)} · ${conversionResult!!.codec.name}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (conversionResult != null) {
                    // ----------------------------------------------------
                    // CONVERSION RESULT & PLAYBACK TOOLS SECTION
                    // ----------------------------------------------------
                    item {
                        ConversionSuccessCard(
                            result = conversionResult!!,
                            isPlaying = isPlaying && activeTrack?.title == "${conversionResult!!.originalFileName}.converted.ogg",
                            currentPositionMs = currentPositionMs,
                            durationMs = durationMs,
                            isLooping = isLooping,
                            onTogglePlay = {
                                val targetTitle = "${conversionResult!!.originalFileName}.converted.ogg"
                                if (activeTrack?.title == targetTitle) {
                                    viewModel.togglePlayPause()
                                } else {
                                    viewModel.playConvertedFile(context, conversionResult!!.outputFile, targetTitle)
                                }
                            },
                            onSeekTo = { viewModel.seekTo(it) },
                            onRewind = { viewModel.rewind(5) },
                            onForward = { viewModel.forward(5) },
                            onToggleLoop = { viewModel.toggleLoop() },
                            onSaveAs = {
                                val base = conversionResult!!.originalFileName.substringBeforeLast('.')
                                saveFilePicker.launch("$base.converted.ogg")
                            },
                            onStage = if (onStageConvertedAsReplacement != null) {
                                {
                                    val uri = Uri.fromFile(conversionResult!!.outputFile)
                                    onStageConvertedAsReplacement(
                                        uri,
                                        "${conversionResult!!.originalFileName.substringBeforeLast('.')}.ogg",
                                        conversionResult!!.convertedSize
                                    )
                                    onNavigateBack()
                                }
                            } else null,
                            onConvertAnother = {
                                viewModel.clearConverterResult()
                            }
                        )
                    }
                } else {
                    // ----------------------------------------------------
                    // CONVERSION CONFIGURATION SETTINGS
                    // ----------------------------------------------------
                    // Section 1: Source File Selection Card
                    item {
                        SourceAudioPickerCard(
                            fileName = inputFileName,
                            fileSize = inputFileSize,
                            onPickFile = {
                                audioFilePicker.launch(
                                    arrayOf(
                                        "audio/*",
                                        "audio/mpeg",
                                        "audio/mp3",
                                        "audio/mp4",
                                        "audio/x-m4a",
                                        "audio/wav",
                                        "audio/x-wav",
                                        "audio/ogg",
                                        "application/ogg",
                                        "*/*"
                                    )
                                )
                            }
                        )
                    }

                    // Section 2: Codec Setting (FLAC, Opus, Speex, Vorbis - default Vorbis)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Audio Codec",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Default: Vorbis",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AudioCodec.values().forEach { codec ->
                                        val isSelected = selectedCodec == codec
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { viewModel.setConverterCodec(codec) },
                                            label = {
                                                Text(
                                                    text = when (codec) {
                                                        AudioCodec.VORBIS -> "Vorbis"
                                                        AudioCodec.OPUS -> "Opus"
                                                        AudioCodec.FLAC -> "FLAC"
                                                        AudioCodec.SPEEX -> "Speex"
                                                    },
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("chip_codec_${codec.name.lowercase()}"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 3: Target File Size (KB, MB - default 700 KB)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Target File Size",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Default: 700 KB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = targetSizeText,
                                        onValueChange = {
                                            viewModel.setConverterTargetSizeText(it.filter { char -> char.isDigit() || char == '.' })
                                        },
                                        label = { Text("Size target") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("input_target_size")
                                    )

                                    // Unit toggle chips (KB / MB)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilterChip(
                                            selected = targetSizeUnit == FileSizeUnit.KB,
                                            onClick = { viewModel.setConverterTargetSizeUnit(FileSizeUnit.KB) },
                                            label = { Text("KB", fontWeight = FontWeight.Bold) },
                                            modifier = Modifier.testTag("chip_unit_kb"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                        FilterChip(
                                            selected = targetSizeUnit == FileSizeUnit.MB,
                                            onClick = { viewModel.setConverterTargetSizeUnit(FileSizeUnit.MB) },
                                            label = { Text("MB", fontWeight = FontWeight.Bold) },
                                            modifier = Modifier.testTag("chip_unit_mb"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                val targetVal = targetSizeText.toDoubleOrNull() ?: 700.0
                                val targetBytes = (targetVal * targetSizeUnit.multiplier).toLong()
                                Text(
                                    text = "Target: ${formatBytes(targetBytes)} · Encoder auto-tunes compression bitrate to fit",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Section 4: Audio Channel (Original, Mono, Stereo - default Mono)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Audio Channels",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Default: Mono",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AudioChannelMode.values().forEach { mode ->
                                        val isSelected = selectedChannelMode == mode
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { viewModel.setConverterChannelMode(mode) },
                                            label = {
                                                Text(
                                                    text = when (mode) {
                                                        AudioChannelMode.MONO -> "Mono (1 ch)"
                                                        AudioChannelMode.STEREO -> "Stereo (2 ch)"
                                                        AudioChannelMode.ORIGINAL -> "Original"
                                                    },
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("chip_channel_${mode.name.lowercase()}"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 5: Audio Sample Rate (Hz - default Default / Keep Original)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Audio Sample Rate (Hz)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Default: Original",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val topOptions = listOf(
                                        SampleRateOption.DEFAULT,
                                        SampleRateOption.HZ_48000,
                                        SampleRateOption.HZ_44100,
                                        SampleRateOption.HZ_22050
                                    )
                                    topOptions.forEach { opt ->
                                        val isSelected = selectedSampleRate == opt
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { viewModel.setConverterSampleRate(opt) },
                                            label = {
                                                Text(
                                                    text = when (opt) {
                                                        SampleRateOption.DEFAULT -> "Original"
                                                        SampleRateOption.HZ_48000 -> "48 kHz"
                                                        SampleRateOption.HZ_44100 -> "44.1 kHz"
                                                        SampleRateOption.HZ_22050 -> "22 kHz"
                                                        else -> opt.label
                                                    },
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("chip_samplerate_${opt.name.lowercase()}"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Main Action Button (Convert Now) when not yet converted
            if (conversionResult == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("button_cancel_converter"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Back")
                        }

                        Button(
                            onClick = {
                                viewModel.startAudioConversion(context)
                            },
                            enabled = inputUri != null && !isConverting,
                            modifier = Modifier
                                .weight(2f)
                                .height(50.dp)
                                .testTag("button_start_convert"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.Transform, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isConverting) "Converting..." else "Convert to OGG",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioConverterSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    viewModel: MainViewModel,
    onStageConvertedAsReplacement: ((Uri, String, Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return
    AudioConverterScreen(
        onNavigateBack = onDismiss,
        viewModel = viewModel,
        onStageConvertedAsReplacement = onStageConvertedAsReplacement,
        modifier = modifier
    )
}

@Composable
fun SourceAudioPickerCard(
    fileName: String?,
    fileSize: Long,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onPickFile() }
            .testTag("card_select_source_audio"),
        shape = RoundedCornerShape(14.dp),
        color = if (fileName != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(
            1.dp,
            if (fileName != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (fileName != null) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (fileName != null) Icons.Default.Audiotrack else Icons.Default.Add,
                        contentDescription = null,
                        tint = if (fileName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName ?: "Select MP3 or Audio File",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (fileName != null) "Size: ${formatBytes(fileSize)} · Ready for conversion"
                        else "Tap to browse audio (.mp3, .wav, .m4a, .aac)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FilledTonalButton(
                onClick = onPickFile,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(if (fileName != null) "Change" else "Browse", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun ConversionSuccessCard(
    result: ConversionResult,
    isPlaying: Boolean,
    currentPositionMs: Int,
    durationMs: Int,
    isLooping: Boolean,
    onTogglePlay: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onToggleLoop: () -> Unit,
    onSaveAs: () -> Unit,
    onStage: (() -> Unit)?,
    onConvertAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val currentMs = if (isDragging) {
        (dragProgress * durationMs.coerceAtLeast(1)).toInt()
    } else {
        currentPositionMs
    }

    val sliderValue = if (durationMs > 0) {
        if (isDragging) dragProgress else (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Result Summary Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_conversion_result"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Conversion Complete!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "${formatBytes(result.convertedSize)}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${result.originalFileName} → ${result.codec.label} · ${if (result.channels == 1) "Mono" else "Stereo"} · ${result.sampleRate} Hz · ~${result.calculatedBitrateKbps} kbps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. Playback Tools Card (Matches same size & full interactive controls)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_converter_playback_tools"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Top row: Equalizer / Title / Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AudioEqualizerVisualizer(
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(8.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${result.originalFileName}.converted.ogg",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isPlaying) "Auditioning converted track..." else "Tap play to audition output",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Interactive Seek Slider
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isDragging = true
                        dragProgress = it
                    },
                    onValueChangeFinished = {
                        if (durationMs > 0) {
                            onSeekTo((dragProgress * durationMs).toInt())
                        }
                        isDragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("slider_converter_audio_position"),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )

                // Time labels
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (durationMs > 0) formatTime(durationMs) else "--:--",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Transport Controls with identical standard button size (38-44dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Loop Toggle
                    IconButton(
                        onClick = onToggleLoop,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_converter_player_loop")
                    ) {
                        Icon(
                            imageVector = if (isLooping) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = if (isLooping) "Repeat enabled" else "Repeat disabled",
                            tint = if (isLooping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    // Rewind 5s
                    IconButton(
                        onClick = onRewind,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_converter_player_rewind")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay5,
                            contentDescription = "Rewind 5 seconds",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Play / Pause Button (Standard 44dp size)
                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("button_converter_play_pause"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Forward 5s
                    IconButton(
                        onClick = onForward,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_converter_player_forward")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward5,
                            contentDescription = "Forward 5 seconds",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 3. Action Buttons Section (Moved BELOW playback tools, all same height 48.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSaveAs,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("button_save_converted"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save As OGG", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }

            if (onStage != null) {
                FilledTonalButton(
                    onClick = onStage,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("button_stage_converted"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Use as Swap", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = onConvertAnother,
                modifier = Modifier
                    .weight(0.9f)
                    .height(48.dp)
                    .testTag("button_convert_another"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
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

private fun getFileSizeFromUri(context: Context, uri: Uri): Long {
    var size: Long = 0L
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    if (size == 0L) {
        try {
            UriUtils.openReadPfd(context, uri)?.use { pfd ->
                size = pfd.statSize
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return size
}
