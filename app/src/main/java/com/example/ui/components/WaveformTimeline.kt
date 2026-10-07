package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.SplitPoint
import com.example.data.model.SplitReason
import com.example.ui.theme.AutoSplitPrimary
import com.example.ui.theme.AutoSplitSecondary
import com.example.ui.theme.AutoSplitTertiary
import com.example.ui.theme.SplitMarkerSelected
import com.example.ui.theme.SplitReasonManual
import com.example.ui.theme.SplitReasonPause
import com.example.ui.theme.SplitReasonSentence
import com.example.ui.theme.SplitReasonSpeaker
import kotlin.math.abs

@Composable
fun WaveformTimeline(
    durationMs: Long,
    currentPlaybackMs: Long,
    waveform: FloatArray,
    splits: List<SplitPoint>,
    selectedSplitId: String?,
    zoom: Float,
    onSeek: (Long) -> Unit,
    onSelectSplit: (String?) -> Unit,
    onUpdateSplitTime: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0L) return

    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A))
            .testTag("waveform_timeline_container")
    ) {
        val baseWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(300f)
        val totalWidthPx = baseWidthPx * zoom
        val totalWidthDp = (maxWidth.value * zoom).dp

        var draggingSplitId by remember { mutableStateOf<String?>(null) }
        var isDraggingPlayhead by remember { mutableStateOf(false) }

        // Automatically keep playhead visible while playing if zoomed in
        LaunchedEffect(currentPlaybackMs) {
            if (zoom > 1.05f && !isDraggingPlayhead && draggingSplitId == null) {
                val progress = (currentPlaybackMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val targetScroll = (progress * totalWidthPx - baseWidthPx / 2f).coerceAtLeast(0f).toInt()
                if (abs(scrollState.value - targetScroll) > baseWidthPx / 3) {
                    scrollState.scrollTo(targetScroll)
                }
            }
        }

        Box(
            modifier = Modifier
                .width(totalWidthDp)
                .fillMaxHeight()
                .horizontalScroll(scrollState)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(totalWidthPx, durationMs, splits, zoom) {
                        detectTapGestures { offset ->
                            val tappedMs = ((offset.x / totalWidthPx) * durationMs).toLong().coerceIn(0L, durationMs)

                            // Check if a split marker was tapped (within 24dp hit tolerance)
                            val hitTolerancePx = 28f * density
                            val hitSplit = splits.find { split ->
                                val splitX = (split.timestampMs.toFloat() / durationMs) * totalWidthPx
                                abs(splitX - offset.x) <= hitTolerancePx
                            }

                            if (hitSplit != null) {
                                onSelectSplit(hitSplit.id)
                            } else {
                                onSelectSplit(null)
                                onSeek(tappedMs)
                            }
                        }
                    }
                    .pointerInput(totalWidthPx, durationMs, splits, zoom) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val hitTolerancePx = 28f * density
                                val hitSplit = splits.find { split ->
                                    val splitX = (split.timestampMs.toFloat() / durationMs) * totalWidthPx
                                    abs(splitX - startOffset.x) <= hitTolerancePx
                                }

                                if (hitSplit != null) {
                                    draggingSplitId = hitSplit.id
                                    onSelectSplit(hitSplit.id)
                                } else {
                                    val playheadX = (currentPlaybackMs.toFloat() / durationMs) * totalWidthPx
                                    if (abs(playheadX - startOffset.x) <= hitTolerancePx * 1.5f || startOffset.y < 40f * density) {
                                        isDraggingPlayhead = true
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingSplitId = null
                                isDraggingPlayhead = false
                            },
                            onDragCancel = {
                                draggingSplitId = null
                                isDraggingPlayhead = false
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val currentX = change.position.x
                                val newTimeMs = ((currentX / totalWidthPx) * durationMs).toLong().coerceIn(0L, durationMs)

                                if (draggingSplitId != null) {
                                    onUpdateSplitTime(draggingSplitId!!, newTimeMs)
                                } else if (isDraggingPlayhead) {
                                    onSeek(newTimeMs)
                                }
                            }
                        )
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // Draw background grid lines and timeline ruler at top
                drawRuler(durationMs, canvasWidth, canvasHeight)

                // Draw audio waveform bars in middle
                drawWaveform(waveform, canvasWidth, canvasHeight)

                // Draw AI and Manual split markers
                splits.forEachIndexed { index, split ->
                    val isSelected = split.id == selectedSplitId
                    val isDragging = split.id == draggingSplitId
                    drawSplitMarker(
                        split = split,
                        splitIndex = index + 1,
                        durationMs = durationMs,
                        totalWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        isSelected = isSelected || isDragging
                    )
                }

                // Draw current playback playhead
                drawPlayhead(
                    currentPlaybackMs = currentPlaybackMs,
                    durationMs = durationMs,
                    totalWidth = canvasWidth,
                    canvasHeight = canvasHeight
                )
            }
        }
    }
}

private fun DrawScope.drawRuler(durationMs: Long, totalWidth: Float, canvasHeight: Float) {
    val rulerHeight = 24.dp.toPx()

    // Ruler bar background
    drawRect(
        color = Color(0xFF1E293B),
        topLeft = Offset(0f, 0f),
        size = Size(totalWidth, rulerHeight)
    )

    // Calculate tick step based on duration & scale
    val totalSeconds = durationMs / 1000f
    val stepSeconds = when {
        totalWidth / totalSeconds > 80f -> 1 // 1 sec marks
        totalWidth / totalSeconds > 30f -> 2 // 2 sec marks
        totalWidth / totalSeconds > 10f -> 5 // 5 sec marks
        else -> 10 // 10 sec marks
    }

    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#94A3B8")
        textSize = 9.dp.toPx()
        isAntiAlias = true
    }

    var sec = 0
    while (sec <= totalSeconds) {
        val secMs = sec * 1000L
        val x = (secMs.toFloat() / durationMs) * totalWidth
        val isMajor = sec % (stepSeconds * 2) == 0

        val tickHeight = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
        drawLine(
            color = Color(0xFF64748B),
            start = Offset(x, rulerHeight - tickHeight),
            end = Offset(x, rulerHeight),
            strokeWidth = 1.dp.toPx()
        )

        if (isMajor) {
            val label = String.format("%02d:%02d", sec / 60, sec % 60)
            drawContext.canvas.nativeCanvas.drawText(label, x + 4f, rulerHeight - 6.dp.toPx(), paint)
        }

        sec += stepSeconds
    }
}

private fun DrawScope.drawWaveform(waveform: FloatArray, totalWidth: Float, canvasHeight: Float) {
    if (waveform.isEmpty()) return

    val rulerHeight = 26.dp.toPx()
    val waveAreaHeight = canvasHeight - rulerHeight - 8.dp.toPx()
    val centerY = rulerHeight + (waveAreaHeight / 2f)

    val barCount = waveform.size
    val barWidth = (totalWidth / barCount) * 0.75f
    val barGap = (totalWidth / barCount) * 0.25f

    val waveGradient = Brush.verticalGradient(
        colors = listOf(AutoSplitTertiary, AutoSplitPrimary),
        startY = centerY - (waveAreaHeight / 2f),
        endY = centerY + (waveAreaHeight / 2f)
    )

    for (i in 0 until barCount) {
        val amp = waveform[i].coerceIn(0.06f, 1.0f)
        val barHeight = (amp * waveAreaHeight * 0.85f).coerceAtLeast(4.dp.toPx())
        val x = i * (barWidth + barGap)

        drawRoundRect(
            brush = waveGradient,
            topLeft = Offset(x, centerY - (barHeight / 2f)),
            size = Size(barWidth.coerceAtLeast(1.5f), barHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

private fun DrawScope.drawSplitMarker(
    split: SplitPoint,
    splitIndex: Int,
    durationMs: Long,
    totalWidth: Float,
    canvasHeight: Float,
    isSelected: Boolean
) {
    val x = (split.timestampMs.toFloat() / durationMs) * totalWidth
    val rulerHeight = 26.dp.toPx()

    val markerColor = if (isSelected) {
        SplitMarkerSelected
    } else {
        when (split.reason) {
            SplitReason.SENTENCE_COMPLETED -> SplitReasonSentence
            SplitReason.SPEAKER_CHANGED -> SplitReasonSpeaker
            SplitReason.NATURAL_PAUSE -> SplitReasonPause
            SplitReason.SILENCE_DETECTED -> Color(0xFF94A3B8)
            SplitReason.MANUAL -> SplitReasonManual
            SplitReason.SCENE_CHANGE -> AutoSplitSecondary
        }
    }

    // Vertical cut line
    drawLine(
        color = markerColor,
        start = Offset(x, rulerHeight),
        end = Offset(x, canvasHeight),
        strokeWidth = if (isSelected) 3.dp.toPx() else 1.8.dp.toPx()
    )

    // Top Flag / Badge
    val badgeWidth = 28.dp.toPx()
    val badgeHeight = 16.dp.toPx()
    val badgeTop = 6.dp.toPx()

    // Draw Zoom Effect Region Highlight & Keyframes if present
    if (split.keyframes.isNotEmpty()) {
        val firstKfTime = split.keyframes.first().timestampMs
        val lastKfTime = split.keyframes.last().timestampMs
        val kfStartPx = (firstKfTime.toFloat() / durationMs) * totalWidth
        val kfEndPx = (lastKfTime.toFloat() / durationMs) * totalWidth

        // Draw translucent zoom active region
        drawRoundRect(
            color = markerColor.copy(alpha = if (isSelected) 0.22f else 0.12f),
            topLeft = Offset(kfStartPx, rulerHeight),
            size = Size((kfEndPx - kfStartPx).coerceAtLeast(4.dp.toPx()), canvasHeight - rulerHeight),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )

        // Draw keyframe diamonds on timeline
        for (kf in split.keyframes) {
            val kfX = (kf.timestampMs.toFloat() / durationMs) * totalWidth
            val kfY = rulerHeight + 12.dp.toPx()
            drawCircle(
                color = if (isSelected) SplitMarkerSelected else Color(0xFF38BDF8),
                radius = 3.5.dp.toPx(),
                center = Offset(kfX, kfY)
            )
        }
    }

    drawRoundRect(
        color = markerColor,
        topLeft = Offset(x - (badgeWidth / 2f), badgeTop),
        size = Size(badgeWidth, badgeHeight),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )

    // Badge text or icon
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 9.dp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    val label = if (split.isAi) {
        when (split.appliedPattern?.zoomDirection) {
            com.example.data.model.ZoomDirection.ZOOM_OUT -> "↓#$splitIndex"
            com.example.data.model.ZoomDirection.ZOOM_IN -> "↑#$splitIndex"
            com.example.data.model.ZoomDirection.ZOOM_OUT_THEN_IN -> "↕#$splitIndex"
            com.example.data.model.ZoomDirection.ZOOM_IN_THEN_OUT -> "↕#$splitIndex"
            else -> "#$splitIndex"
        }
    } else "✂"
    drawContext.canvas.nativeCanvas.drawText(
        label,
        x,
        badgeTop + badgeHeight - 4.dp.toPx(),
        textPaint
    )

    // Halo ring if selected
    if (isSelected) {
        drawCircle(
            color = SplitMarkerSelected.copy(alpha = 0.35f),
            radius = 16.dp.toPx(),
            center = Offset(x, rulerHeight + 16.dp.toPx())
        )
    }
}

private fun DrawScope.drawPlayhead(
    currentPlaybackMs: Long,
    durationMs: Long,
    totalWidth: Float,
    canvasHeight: Float
) {
    val progress = (currentPlaybackMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val x = progress * totalWidth

    // Scrubber top indicator (inverted triangle)
    val headPath = Path().apply {
        moveTo(x - 7.dp.toPx(), 0f)
        lineTo(x + 7.dp.toPx(), 0f)
        lineTo(x, 14.dp.toPx())
        close()
    }
    drawPath(path = headPath, color = Color.White)

    // Vertical line
    drawLine(
        color = Color.White,
        start = Offset(x, 14.dp.toPx()),
        end = Offset(x, canvasHeight),
        strokeWidth = 2.dp.toPx()
    )
}
