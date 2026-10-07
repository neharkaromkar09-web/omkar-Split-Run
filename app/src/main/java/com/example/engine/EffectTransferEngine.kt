package com.example.engine

import com.example.data.model.EasingType
import com.example.data.model.ReferenceEditProfile
import com.example.data.model.ReferenceSegmentPattern
import com.example.data.model.SplitPoint
import com.example.data.model.TransformKeyframe
import com.example.data.model.ZoomDirection

object EffectTransferEngine {

    /**
     * Maps reference video edit patterns onto newly detected speech split timestamps.
     * Generates real transform keyframes for each split.
     */
    fun mapReferenceEffectsToSplits(
        detectedSplits: List<SplitPoint>,
        referenceProfile: ReferenceEditProfile?,
        newVideoDurationMs: Long
    ): List<SplitPoint> {
        if (detectedSplits.isEmpty()) return emptyList()

        val patterns = referenceProfile?.patterns
        val defaultPattern = ReferenceSegmentPattern(
            segmentIndex = 1,
            cutTimestampMs = 0L,
            splitToZoomOffsetMs = -100L,
            zoomDurationMs = 600L,
            zoomDirection = ZoomDirection.ZOOM_OUT_THEN_IN,
            scaleMin = 0.90f,
            scaleMax = 1.15f,
            easingType = EasingType.EASE_IN_OUT
        )

        return detectedSplits.mapIndexed { index, split ->
            // Match pattern from reference profile by cycling segments
            val pattern = if (!patterns.isNullOrEmpty()) {
                patterns[index % patterns.size]
            } else {
                defaultPattern
            }

            val keyframes = generateKeyframesForSplit(
                splitTimeMs = split.timestampMs,
                pattern = pattern,
                videoDurationMs = newVideoDurationMs
            )

            split.copy(
                keyframes = keyframes,
                appliedPattern = pattern,
                zoomIntensityMultiplier = 1.0f
            )
        }
    }

    private fun generateKeyframesForSplit(
        splitTimeMs: Long,
        pattern: ReferenceSegmentPattern,
        videoDurationMs: Long
    ): List<TransformKeyframe> {
        val keyframes = mutableListOf<TransformKeyframe>()
        val duration = pattern.zoomDurationMs.coerceIn(300L, 1200L)
        val peakTime = (splitTimeMs + pattern.splitToZoomOffsetMs).coerceIn(100L, videoDurationMs - 100L)

        val startTime = (peakTime - duration / 2).coerceAtLeast(0L)
        val endTime = (peakTime + duration / 2).coerceAtMost(videoDurationMs)

        if (pattern.zoomDirection == ZoomDirection.NO_ZOOM) {
            return emptyList()
        }

        // If the reference pattern contains measured normalized curve points, use them
        if (pattern.normalizedCurve.size >= 3) {
            val span = (endTime - startTime).toFloat().coerceAtLeast(100f)
            for (pt in pattern.normalizedCurve) {
                val t = (startTime + (pt.progress * span)).toLong()
                keyframes.add(
                    TransformKeyframe(
                        timestampMs = t,
                        scale = pt.scale
                    )
                )
            }
        } else {
            // Generate keyframes according to detected direction and intensity
            keyframes.add(TransformKeyframe(timestampMs = startTime, scale = 1.0f))

            when (pattern.zoomDirection) {
                ZoomDirection.ZOOM_OUT_THEN_IN -> {
                    val quarter = (endTime - startTime) / 4
                    val minScale = pattern.scaleMin.coerceIn(0.85f, 0.96f)
                    keyframes.add(TransformKeyframe(timestampMs = startTime + quarter, scale = (1.0f + minScale) / 2f))
                    keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = minScale))
                    keyframes.add(TransformKeyframe(timestampMs = endTime - quarter, scale = (1.0f + minScale) / 2f))
                    keyframes.add(TransformKeyframe(timestampMs = endTime, scale = 1.0f))
                }
                ZoomDirection.ZOOM_IN_THEN_OUT -> {
                    val quarter = (endTime - startTime) / 4
                    val maxScale = pattern.scaleMax.coerceIn(1.08f, 1.25f)
                    keyframes.add(TransformKeyframe(timestampMs = startTime + quarter, scale = (1.0f + maxScale) / 2f))
                    keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = maxScale))
                    keyframes.add(TransformKeyframe(timestampMs = endTime - quarter, scale = (1.0f + maxScale) / 2f))
                    keyframes.add(TransformKeyframe(timestampMs = endTime, scale = 1.0f))
                }
                ZoomDirection.ZOOM_IN -> {
                    val maxScale = pattern.scaleMax.coerceIn(1.08f, 1.25f)
                    keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = maxScale))
                    keyframes.add(TransformKeyframe(timestampMs = endTime, scale = 1.0f))
                }
                ZoomDirection.ZOOM_OUT -> {
                    val minScale = pattern.scaleMin.coerceIn(0.85f, 0.96f)
                    keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = minScale))
                    keyframes.add(TransformKeyframe(timestampMs = endTime, scale = 1.0f))
                }
                ZoomDirection.NO_ZOOM -> {
                    // No keyframes
                }
            }
        }

        return keyframes.sortedBy { it.timestampMs }
    }
}
