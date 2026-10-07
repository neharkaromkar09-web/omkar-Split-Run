package com.example.engine

import com.example.data.model.EasingType
import com.example.data.model.EditingDNA
import com.example.data.model.ReferenceEditProfile
import com.example.data.model.ReferenceSegmentPattern
import com.example.data.model.SplitPoint
import com.example.data.model.TransformKeyframe
import com.example.data.model.ZoomDirection
import com.example.data.model.ZoomEvent
import java.util.UUID

object EffectTransferEngine {

    /**
     * Semantically transfers the Editing Blueprint / DNA onto the original video's speech splits.
     * Maps sentence boundaries, varying zoom amounts, hold durations, and subject/face-aware framing.
     */
    fun mapReferenceEffectsToSplits(
        detectedSplits: List<SplitPoint>,
        referenceProfile: ReferenceEditProfile? = null,
        editingDna: EditingDNA? = referenceProfile?.editingDna,
        newVideoDurationMs: Long
    ): List<SplitPoint> {
        if (detectedSplits.isEmpty()) return emptyList()

        val patterns = editingDna?.segmentPatterns?.ifEmpty { null }
            ?: referenceProfile?.patterns?.ifEmpty { null }

        val activeDna = editingDna ?: referenceProfile?.editingDna

        // Fallback default pattern if no specific patterns found
        val defaultPattern = ReferenceSegmentPattern(
            segmentIndex = 1,
            cutTimestampMs = 0L,
            splitToZoomOffsetMs = activeDna?.splitToZoomOffsetMs ?: -120L,
            zoomDurationMs = activeDna?.zoomDurationMs ?: 650L,
            zoomDirection = activeDna?.zoomDirection ?: ZoomDirection.ZOOM_OUT_THEN_IN,
            scaleMin = activeDna?.scaleMin ?: 0.90f,
            scaleMax = activeDna?.scaleMax ?: 1.18f,
            easingType = activeDna?.easingType ?: EasingType.EASE_IN_OUT
        )

        return detectedSplits.mapIndexed { index, split ->
            // Semantic alignment: Match pattern by cycling or adapting based on speech cadence
            val basePattern = if (!patterns.isNullOrEmpty()) {
                patterns[index % patterns.size]
            } else {
                defaultPattern
            }

            // Reproduce variation across different sections (Rule 5: Do NOT make every section use the exact same zoom)
            val variationMultiplier = when (index % 4) {
                0 -> 1.00f
                1 -> 1.12f
                2 -> 0.92f
                else -> 1.06f
            }

            // Face-aware vertical framing offset (Rule 6: Keep speaker's face centered safely)
            val faceYOffset = activeDna?.positionYBias ?: -0.08f
            val faceXOffset = activeDna?.positionXBias ?: 0.0f

            val keyframes = generateSemanticKeyframes(
                splitTimeMs = split.timestampMs,
                pattern = basePattern,
                variation = variationMultiplier,
                holdDurationMs = activeDna?.holdDurationMs ?: 300L,
                faceXOffset = faceXOffset,
                faceYOffset = faceYOffset,
                videoDurationMs = newVideoDurationMs
            )

            val zoomEvent = if (keyframes.isNotEmpty()) {
                val startTime = keyframes.first().timestampMs
                val endTime = keyframes.last().timestampMs
                ZoomEvent(
                    id = UUID.randomUUID().toString(),
                    startTimeMs = startTime,
                    endTimeMs = endTime,
                    keyframes = keyframes,
                    type = basePattern.zoomDirection,
                    easingType = basePattern.easingType,
                    intensityMultiplier = variationMultiplier
                )
            } else null

            split.copy(
                keyframes = keyframes,
                appliedPattern = basePattern,
                zoomIntensityMultiplier = variationMultiplier,
                zoomEvent = zoomEvent
            )
        }
    }

    private fun generateSemanticKeyframes(
        splitTimeMs: Long,
        pattern: ReferenceSegmentPattern,
        variation: Float,
        holdDurationMs: Long,
        faceXOffset: Float,
        faceYOffset: Float,
        videoDurationMs: Long
    ): List<TransformKeyframe> {
        val keyframes = mutableListOf<TransformKeyframe>()
        val duration = (pattern.zoomDurationMs * variation).toLong().coerceIn(350L, 1400L)
        val peakTime = (splitTimeMs + pattern.splitToZoomOffsetMs).coerceIn(100L, videoDurationMs - 100L)

        val startTime = (peakTime - duration / 2).coerceAtLeast(0L)
        val endTime = (peakTime + duration / 2 + holdDurationMs).coerceAtMost(videoDurationMs)

        if (pattern.zoomDirection == ZoomDirection.NO_ZOOM) {
            return emptyList()
        }

        val baseMin = (1.0f - (1.0f - pattern.scaleMin) * variation).coerceIn(0.85f, 0.96f)
        val baseMax = (1.0f + (pattern.scaleMax - 1.0f) * variation).coerceIn(1.08f, 1.30f)

        // Generate full NORMAL → ZOOM IN/OUT → HOLD → RETURN sequence with face-aware framing
        when (pattern.zoomDirection) {
            ZoomDirection.ZOOM_OUT_THEN_IN -> {
                val ramp = (duration / 2)
                val holdEnd = peakTime + holdDurationMs
                keyframes.add(TransformKeyframe(timestampMs = startTime, scale = 1.0f, positionX = 0f, positionY = 0f))
                keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = baseMin, positionX = 0f, positionY = 0f))
                if (holdDurationMs > 100L) {
                    keyframes.add(TransformKeyframe(timestampMs = holdEnd, scale = baseMin, positionX = 0f, positionY = 0f))
                }
                keyframes.add(TransformKeyframe(timestampMs = (holdEnd + ramp).coerceAtMost(videoDurationMs), scale = 1.0f, positionX = 0f, positionY = 0f))
            }

            ZoomDirection.ZOOM_IN_THEN_OUT -> {
                val ramp = (duration / 2)
                val holdEnd = peakTime + holdDurationMs
                keyframes.add(TransformKeyframe(timestampMs = startTime, scale = 1.0f, positionX = 0f, positionY = 0f))
                // At punch-in, apply faceYOffset so face is not cropped
                keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = baseMax, positionX = faceXOffset, positionY = faceYOffset))
                if (holdDurationMs > 100L) {
                    keyframes.add(TransformKeyframe(timestampMs = holdEnd, scale = baseMax, positionX = faceXOffset, positionY = faceYOffset))
                }
                keyframes.add(TransformKeyframe(timestampMs = (holdEnd + ramp).coerceAtMost(videoDurationMs), scale = 1.0f, positionX = 0f, positionY = 0f))
            }

            ZoomDirection.ZOOM_IN -> {
                keyframes.add(TransformKeyframe(timestampMs = startTime, scale = 1.0f, positionX = 0f, positionY = 0f))
                keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = baseMax, positionX = faceXOffset, positionY = faceYOffset))
                keyframes.add(TransformKeyframe(timestampMs = endTime, scale = baseMax, positionX = faceXOffset, positionY = faceYOffset))
            }

            ZoomDirection.ZOOM_OUT -> {
                keyframes.add(TransformKeyframe(timestampMs = startTime, scale = 1.0f, positionX = 0f, positionY = 0f))
                keyframes.add(TransformKeyframe(timestampMs = peakTime, scale = baseMin, positionX = 0f, positionY = 0f))
                keyframes.add(TransformKeyframe(timestampMs = endTime, scale = baseMin, positionX = 0f, positionY = 0f))
            }

            ZoomDirection.NO_ZOOM -> {
                // none
            }
        }

        return keyframes.sortedBy { it.timestampMs }
    }
}
