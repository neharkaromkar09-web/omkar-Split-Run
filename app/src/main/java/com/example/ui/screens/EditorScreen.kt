package com.example.ui.screens

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.SplitPoint
import com.example.data.model.VideoClip
import com.example.ui.components.AppHeader
import com.example.ui.components.SplitMarkerDetailsCard
import com.example.ui.components.WaveformTimeline
import com.example.ui.theme.AutoSplitPrimary
import com.example.ui.theme.AutoSplitSecondary
import com.example.viewmodel.EditorUiState
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onNavigateToExport: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: (Boolean) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitTimeline: () -> Unit,
    onAddSplit: () -> Unit,
    onSelectSplit: (String?) -> Unit,
    onUpdateSplitTime: (String, Long) -> Unit,
    onDeleteSplit: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPreviewClip: (VideoClip?) -> Unit
) {
    BackHandler { onBack() }

    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    val durationMs = state.metadata?.durationMs ?: 1L

    // Sync playback position while playing
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            videoViewRef?.let { vv ->
                if (vv.isPlaying) {
                    val pos = vv.currentPosition.toLong()
                    onSeek(pos)

                    // If previewing a single clip and reached endMs, pause
                    state.previewClip?.let { clip ->
                        if (pos >= clip.endMs) {
                            vv.pause()
                            onTogglePlay(false)
                        }
                    }
                }
            }
            delay(80)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef?.stopPlayback()
        }
    }

    val selectedSplit = state.splits.find { it.id == state.selectedSplitId }
    val selectedSplitIndex = if (selectedSplit != null) state.splits.indexOf(selectedSplit) + 1 else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TOP APP BAR WITH EXPORT BUTTON
        AppHeader(
            title = "Video Editor",
            subtitle = state.metadata?.fileName ?: "Editor",
            showBack = true,
            onBackClick = onBack,
            trailingActions = {
                Button(
                    onClick = onNavigateToExport,
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("editor_export_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AutoSplitPrimary,
                        contentColor = Color.White
                    )
                ) {
                    Text(text = "Export", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)
        ) {
            // 1. VIDEO PREVIEW PLAYER
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("video_player_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.selectedVideoUri != null) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoURI(state.selectedVideoUri)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = false
                                            seekTo(state.currentPlaybackMs.toInt())
                                        }
                                        setOnCompletionListener {
                                            onTogglePlay(false)
                                        }
                                        videoViewRef = this
                                    }
                                },
                                update = { vv ->
                                    videoViewRef = vv
                                    if (state.isPlaying && !vv.isPlaying) {
                                        vv.start()
                                    } else if (!state.isPlaying && vv.isPlaying) {
                                        vv.pause()
                                    }
                                    // Keep video synced when paused seek occurs
                                    if (!state.isPlaying && kotlin.math.abs(vv.currentPosition - state.currentPlaybackMs) > 250) {
                                        vv.seekTo(state.currentPlaybackMs.toInt())
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Play/Pause Overlay Button
                        IconButton(
                            onClick = {
                                val nextPlay = !state.isPlaying
                                onTogglePlay(nextPlay)
                                videoViewRef?.let { vv ->
                                    if (nextPlay) vv.start() else vv.pause()
                                }
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .testTag("play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Time pill at bottom left
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${SplitPoint.formatTimestamp(state.currentPlaybackMs)} / ${SplitPoint.formatDuration(durationMs)}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // 2. TIMELINE CONTROLS ROW (Zoom -, Zoom label, Zoom +, Fit, Add Split, Undo, Redo)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Zoom Controls Group
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onZoomOut,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("zoom_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomOut,
                                contentDescription = "Zoom Out",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = String.format("%.1fx", state.timelineZoom),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        IconButton(
                            onClick = onZoomIn,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("zoom_in_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomIn,
                                contentDescription = "Zoom In",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = onFitTimeline,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("fit_timeline_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitScreen,
                                contentDescription = "Fit Timeline",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Undo / Redo / Add Split
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onUndo,
                            enabled = state.canUndo,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("undo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Undo,
                                contentDescription = "Undo",
                                tint = if (state.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                            )
                        }

                        IconButton(
                            onClick = onRedo,
                            enabled = state.canRedo,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("redo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Redo,
                                contentDescription = "Redo",
                                tint = if (state.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        FilledTonalButton(
                            onClick = onAddSplit,
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("add_split_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Add Split", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 3. EDITABLE WAVEFORM TIMELINE CANVAS
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    WaveformTimeline(
                        durationMs = durationMs,
                        currentPlaybackMs = state.currentPlaybackMs,
                        waveform = state.waveform,
                        splits = state.splits,
                        selectedSplitId = state.selectedSplitId,
                        zoom = state.timelineZoom,
                        onSeek = { seekMs ->
                            onSeek(seekMs)
                            videoViewRef?.seekTo(seekMs.toInt())
                        },
                        onSelectSplit = onSelectSplit,
                        onUpdateSplitTime = onUpdateSplitTime
                    )
                }
            }

            // 4. SELECTED SPLIT DETAILS POPUP CARD
            if (selectedSplit != null) {
                item {
                    SplitMarkerDetailsCard(
                        split = selectedSplit,
                        splitIndex = selectedSplitIndex,
                        onMoveTimeOffset = { deltaMs ->
                            onUpdateSplitTime(selectedSplit.id, selectedSplit.timestampMs + deltaMs)
                        },
                        onDelete = {
                            onDeleteSplit(selectedSplit.id)
                        },
                        onClose = { onSelectSplit(null) }
                    )
                }
            }

            // 5. CLIP LIST SECTION
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Clips (${state.clips.size})",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "${state.splits.size} Split Markers",
                        style = MaterialTheme.typography.bodySmall,
                        color = AutoSplitPrimary
                    )
                }
            }

            items(state.clips) { clip ->
                ClipListItem(
                    clip = clip,
                    isPlaying = state.previewClip?.clipIndex == clip.clipIndex && state.isPlaying,
                    onPlay = {
                        onPreviewClip(clip)
                        videoViewRef?.seekTo(clip.startMs.toInt())
                        videoViewRef?.start()
                    }
                )
            }
        }
    }
}

@Composable
fun ClipListItem(
    clip: VideoClip,
    isPlaying: Boolean,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .testTag("clip_item_${clip.clipIndex}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clip Index badge
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AutoSplitPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = String.format("%02d", clip.clipIndex),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = AutoSplitPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Clip ${clip.clipIndex}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = clip.formattedRange,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Duration: ${clip.formattedDuration} • ${clip.splitReason.displayName}",
                    style = MaterialTheme.typography.labelSmall.copy(color = AutoSplitSecondary)
                )
            }

            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(if (isPlaying) AutoSplitPrimary else Color(0xFFF1F5F9))
                    .testTag("play_clip_${clip.clipIndex}")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Preview Clip",
                    tint = if (isPlaying) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
