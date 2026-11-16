package com.aistudio.oggresource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

import androidx.compose.ui.platform.LocalContext

@Composable
fun AudioPlayerBarContainer(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val activeTrack by viewModel.activeTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val isLooping by viewModel.isLooping.collectAsState()
    val context = LocalContext.current

    AudioPlayerBar(
        activeTrack = activeTrack,
        isPlaying = isPlaying,
        currentPositionMs = currentPositionMs,
        durationMs = durationMs,
        isLooping = isLooping,
        onTogglePlayPause = { viewModel.togglePlayPause() },
        onSeekTo = { viewModel.seekTo(it) },
        onRewind = { viewModel.rewind(5) },
        onForward = { viewModel.forward(5) },
        onToggleLoop = { viewModel.toggleLoop() },
        onClose = { viewModel.stopPlayback() },
        onNextTrack = { viewModel.playNextStream(context) },
        onPreviousTrack = { viewModel.playPreviousStream(context) },
        modifier = modifier
    )
}

@Composable
fun AudioPlayerBar(
    activeTrack: ActiveAudioTrack?,
    isPlaying: Boolean,
    currentPositionMs: Int,
    durationMs: Int,
    isLooping: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onToggleLoop: () -> Unit,
    onClose: () -> Unit,
    onNextTrack: (() -> Unit)? = null,
    onPreviousTrack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = activeTrack != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        if (activeTrack == null) return@AnimatedVisibility

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

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Top row: Equalizer / Icon, Track Info, and Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dynamic Equalizer visualizer
                    AudioEqualizerVisualizer(
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(8.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Track details
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeTrack.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = activeTrack.codec,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Text(
                            text = activeTrack.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Stop/Close Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("button_player_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close player",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Seek slider
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
                        .testTag("slider_audio_position"),
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

                Spacer(modifier = Modifier.height(6.dp))

                // Transport Controls
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
                            .testTag("button_player_loop")
                    ) {
                        Icon(
                            imageVector = if (isLooping) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = if (isLooping) "Repeat enabled" else "Repeat disabled",
                            tint = if (isLooping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // Previous Track Button
                    IconButton(
                        onClick = { onPreviousTrack?.invoke() },
                        enabled = onPreviousTrack != null,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_player_previous")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = if (onPreviousTrack != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }

                    // Rewind 5s
                    IconButton(
                        onClick = onRewind,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_player_rewind")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay5,
                            contentDescription = "Rewind 5s",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Play/Pause Main Button
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("button_player_play_pause")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Forward 5s
                    IconButton(
                        onClick = onForward,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_player_forward")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward5,
                            contentDescription = "Forward 5s",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Next Track Button
                    IconButton(
                        onClick = { onNextTrack?.invoke() },
                        enabled = onNextTrack != null,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_player_next")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = if (onNextTrack != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }

                    // Stop button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("button_player_stop")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioEqualizerVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "eq_bars")
    
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isPlaying) 0.95f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = if (isPlaying) 1.0f else 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (isPlaying) 0.85f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    val bar4 by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (isPlaying) 0.9f else 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        EqualizerBar(heightFraction = if (isPlaying) bar1 else 0.3f)
        EqualizerBar(heightFraction = if (isPlaying) bar2 else 0.6f)
        EqualizerBar(heightFraction = if (isPlaying) bar3 else 0.4f)
        EqualizerBar(heightFraction = if (isPlaying) bar4 else 0.2f)
    }
}

@Composable
fun EqualizerBar(heightFraction: Float) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .fillMaxHeight(heightFraction.coerceIn(0.1f, 1f))
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary)
    )
}

fun formatTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
