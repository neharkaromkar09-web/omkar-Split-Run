package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.data.model.EasingType
import com.example.data.model.NormalizedCurvePoint
import com.example.data.model.ReferenceEditProfile
import com.example.data.model.ReferenceSegmentPattern
import com.example.data.model.ZoomDirection
import com.example.data.model.ZoomTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ReferenceAnalysisEngine {

    // In-memory cache for analyzed reference edit profiles
    private val profileCache = ConcurrentHashMap<String, ReferenceEditProfile>()

    suspend fun analyzeReferenceVideo(
        context: Context,
        referenceUri: Uri,
        durationMs: Long,
        onProgress: (stage: String, progress: Float) -> Unit
    ): Result<ReferenceEditProfile> = withContext(Dispatchers.IO) {
        val cacheKey = "${referenceUri}_$durationMs"
        profileCache[cacheKey]?.let { cached ->
            onProgress("Loaded reference profile from cache.", 1.0f)
            return@withContext Result.success(cached)
        }

        if (durationMs <= 500L) {
            return@withContext Result.failure(IllegalArgumentException("Reference video is too short to analyze."))
        }

        onProgress("Analyzing reference video frame-by-frame...", 0.05f)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, referenceUri)

            // Step 1: Detect cuts / splits by analyzing frame transitions across the reference video
            val stepMs = if (durationMs > 60000L) 120L else 75L // High temporal resolution
            val frameTimestamps = mutableListOf<Long>()
            var curMs = 0L
            while (curMs < durationMs) {
                frameTimestamps.add(curMs)
                curMs += stepMs
            }

            val totalFrames = frameTimestamps.size
            val frameDiffs = mutableListOf<Pair<Long, Double>>() // timestamp to diff score
            val scaleTrajectory = mutableListOf<Pair<Long, Float>>() // timestamp to estimated optical scale

            var prevFrame: Bitmap? = null
            var baseFrame: Bitmap? = null
            var baseScale = 1.0f

            for (i in frameTimestamps.indices) {
                val tMs = frameTimestamps[i]
                val bmp = extractAnalysisFrame(retriever, tMs)

                if (bmp != null) {
                    if (prevFrame != null) {
                        val diff = computeFrameDifference(prevFrame, bmp)
                        frameDiffs.add(Pair(tMs, diff))

                        // Estimate optical scale change relative to base frame or previous frame
                        val relativeScale = estimateOpticalScale(prevFrame, bmp)
                        baseScale = (baseScale * relativeScale).coerceIn(0.75f, 1.45f)

                        // If a hard cut occurred, reset reference base scale
                        if (diff > 0.42) {
                            baseScale = 1.0f
                            baseFrame = bmp
                        }
                        scaleTrajectory.add(Pair(tMs, baseScale))
                    } else {
                        baseFrame = bmp
                        scaleTrajectory.add(Pair(tMs, 1.0f))
                    }

                    prevFrame?.recycle()
                    prevFrame = bmp
                }

                if (i % 8 == 0) {
                    val progressFraction = (i.toFloat() / totalFrames) * 0.50f
                    onProgress("Detecting edits & cuts...", 0.05f + progressFraction)
                }
            }
            prevFrame?.recycle()

            onProgress("Detecting zoom movements & curves...", 0.58f)

            // Step 2: Identify cut points from frame differences
            val cutTimestamps = mutableListOf<Long>()
            for (pair in frameDiffs) {
                if (pair.second > 0.38) { // threshold for visual cut
                    val lastCut = cutTimestamps.lastOrNull()
                    if (lastCut == null || (pair.first - lastCut) > 1200L) {
                        cutTimestamps.add(pair.first)
                    }
                }
            }

            // If no hard cuts found (e.g. single take with zooms), look for scale inflection peaks
            if (cutTimestamps.isEmpty()) {
                // Find prominent scale extrema points
                for (j in 2 until scaleTrajectory.size - 2) {
                    val prevS = scaleTrajectory[j - 1].second
                    val curS = scaleTrajectory[j].second
                    val nextS = scaleTrajectory[j + 1].second
                    if ((curS > prevS && curS > nextS && curS > 1.08f) || (curS < prevS && curS < nextS && curS < 0.94f)) {
                        cutTimestamps.add(scaleTrajectory[j].first)
                    }
                }
            }

            // Fallback: If reference is very continuous, generate periodic segment anchors
            if (cutTimestamps.isEmpty()) {
                val interval = (durationMs / 3).coerceAtLeast(2000L)
                var p = interval
                while (p < durationMs - 1000L) {
                    cutTimestamps.add(p)
                    p += interval
                }
            }

            onProgress("Measuring zoom curves & easing...", 0.75f)

            // Step 3: For each detected cut, measure the surrounding zoom behavior
            val segmentPatterns = mutableListOf<ReferenceSegmentPattern>()

            for (idx in cutTimestamps.indices) {
                val cutTime = cutTimestamps[idx]
                val windowStart = (cutTime - 1000L).coerceAtLeast(0L)
                val windowEnd = (cutTime + 1000L).coerceAtMost(durationMs)

                // Sub-trajectory in this window
                val windowScales = scaleTrajectory.filter { it.first in windowStart..windowEnd }

                var minScale = 1.0f
                var maxScale = 1.0f
                var minScaleTime = cutTime
                var maxScaleTime = cutTime

                for (ws in windowScales) {
                    if (ws.second < minScale) {
                        minScale = ws.second
                        minScaleTime = ws.first
                    }
                    if (ws.second > maxScale) {
                        maxScale = ws.second
                        maxScaleTime = ws.first
                    }
                }

                // Determine zoom direction and peak
                val hasZoomOut = minScale < 0.94f
                val hasZoomIn = maxScale > 1.06f

                val zoomDirection: ZoomDirection
                val peakTime: Long
                val peakScale: Float

                if (hasZoomOut && hasZoomIn) {
                    if (minScaleTime < maxScaleTime) {
                        zoomDirection = ZoomDirection.ZOOM_OUT_THEN_IN
                        peakTime = minScaleTime
                        peakScale = minScale
                    } else {
                        zoomDirection = ZoomDirection.ZOOM_IN_THEN_OUT
                        peakTime = maxScaleTime
                        peakScale = maxScale
                    }
                } else if (hasZoomIn) {
                    zoomDirection = ZoomDirection.ZOOM_IN
                    peakTime = maxScaleTime
                    peakScale = maxScale
                } else if (hasZoomOut) {
                    zoomDirection = ZoomDirection.ZOOM_OUT
                    peakTime = minScaleTime
                    peakScale = minScale
                } else {
                    zoomDirection = ZoomDirection.NO_ZOOM
                    peakTime = cutTime
                    peakScale = 1.0f
                }

                // Zoom timing relative to cut
                val timeOffset = peakTime - cutTime
                val zoomTiming: ZoomTiming = when {
                    zoomDirection == ZoomDirection.NO_ZOOM -> ZoomTiming.NO_ZOOM
                    timeOffset < -150L -> ZoomTiming.BEFORE_SPLIT
                    timeOffset > 150L -> ZoomTiming.AFTER_SPLIT
                    abs(timeOffset) <= 150L -> ZoomTiming.AT_SPLIT
                    else -> ZoomTiming.SPANNING_SPLIT
                }

                // Measured duration of zoom movement
                val zoomDuration = if (zoomDirection != ZoomDirection.NO_ZOOM) {
                    val activeStart = windowScales.find { abs(it.second - 1.0f) > 0.03f }?.first ?: (cutTime - 300L)
                    val activeEnd = windowScales.findLast { abs(it.second - 1.0f) > 0.03f }?.first ?: (cutTime + 300L)
                    (activeEnd - activeStart).coerceIn(400L, 1200L)
                } else 600L

                // Normalized effect curve sampling (5 key progress points: 0%, 25%, 50%, 75%, 100%)
                val curvePoints = mutableListOf<NormalizedCurvePoint>()
                val effectStart = (peakTime - zoomDuration / 2).coerceAtLeast(0L)
                val effectEnd = (peakTime + zoomDuration / 2).coerceAtMost(durationMs)
                val durationSpan = (effectEnd - effectStart).coerceAtLeast(100L)

                for (step in 0..4) {
                    val prog = step / 4f
                    val sampleTime = effectStart + (prog * durationSpan).toLong()
                    val nearest = windowScales.minByOrNull { abs(it.first - sampleTime) }
                    val scaleAtSample = nearest?.second ?: 1.0f
                    curvePoints.add(NormalizedCurvePoint(prog, scaleAtSample))
                }

                segmentPatterns.add(
                    ReferenceSegmentPattern(
                        segmentIndex = idx + 1,
                        cutTimestampMs = cutTime,
                        speechEndToSplitOffsetMs = 0L,
                        splitToZoomOffsetMs = timeOffset,
                        zoomDurationMs = zoomDuration,
                        zoomDirection = zoomDirection,
                        zoomTiming = zoomTiming,
                        scaleMin = minScale,
                        scaleMax = maxScale,
                        easingType = EasingType.EASE_IN_OUT,
                        normalizedCurve = curvePoints
                    )
                )
            }

            onProgress("Building reference edit profile...", 0.95f)

            val profile = ReferenceEditProfile(
                referenceVideoUri = referenceUri.toString(),
                referenceDurationMs = durationMs,
                totalCutsDetected = segmentPatterns.size,
                patterns = segmentPatterns
            )

            profileCache[cacheKey] = profile
            onProgress("Reference analysis complete.", 1.0f)

            Result.success(profile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractAnalysisFrame(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        return try {
            val original = retriever.getFrameAtTime(
                timeMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(timeMs * 1000L)
            original?.let {
                // Scale down to 48x27 for fast cross-correlation and diff without eating RAM
                val scaled = Bitmap.createScaledBitmap(it, 48, 27, false)
                if (scaled != it) it.recycle()
                scaled
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun computeFrameDifference(bmp1: Bitmap, bmp2: Bitmap): Double {
        val w = bmp1.width
        val h = bmp1.height
        var totalDiff = 0.0
        val count = w * h

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p1 = bmp1.getPixel(x, y)
                val p2 = bmp2.getPixel(x, y)
                val rDiff = abs(((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)) / 255.0
                val gDiff = abs(((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)) / 255.0
                val bDiff = abs((p1 and 0xff) - (p2 and 0xff)) / 255.0
                totalDiff += (rDiff + gDiff + bDiff) / 3.0
            }
        }
        return totalDiff / count
    }

    /**
     * Estimates optical scale factor between consecutive frames bmp1 and bmp2.
     * Compares center patch at various scale multipliers to find minimum MSE.
     */
    private fun estimateOpticalScale(bmp1: Bitmap, bmp2: Bitmap): Float {
        val w = bmp1.width
        val h = bmp1.height
        val cx = w / 2
        val cy = h / 2

        var bestScale = 1.0f
        var minDiff = Double.MAX_VALUE

        // Test scales: 0.94 (zoom-out), 1.00 (same), 1.06 (zoom-in)
        val candidateScales = floatArrayOf(0.92f, 0.96f, 1.00f, 1.04f, 1.08f)

        for (s in candidateScales) {
            var diff = 0.0
            var sampleCount = 0

            // Sample within central region
            val radiusX = (w * 0.35f).toInt()
            val radiusY = (h * 0.35f).toInt()

            for (dy in -radiusY..radiusY step 2) {
                for (dx in -radiusX..radiusX step 2) {
                    val x1 = cx + dx
                    val y1 = cy + dy
                    val x2 = (cx + dx * s).toInt().coerceIn(0, w - 1)
                    val y2 = (cy + dy * s).toInt().coerceIn(0, h - 1)

                    if (x1 in 0 until w && y1 in 0 until h) {
                        val p1 = bmp1.getPixel(x1, y1)
                        val p2 = bmp2.getPixel(x2, y2)
                        val l1 = (((p1 shr 16) and 0xff) + ((p1 shr 8) and 0xff) + (p1 and 0xff)) / 3.0
                        val l2 = (((p2 shr 16) and 0xff) + ((p2 shr 8) and 0xff) + (p2 and 0xff)) / 3.0
                        diff += abs(l1 - l2)
                        sampleCount++
                    }
                }
            }

            if (sampleCount > 0) {
                val avgDiff = diff / sampleCount
                if (avgDiff < minDiff) {
                    minDiff = avgDiff
                    bestScale = s
                }
            }
        }

        return bestScale
    }
}
