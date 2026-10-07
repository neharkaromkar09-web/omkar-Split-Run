package com.example.data.model

import java.util.UUID

/**
 * Authoritative Editing Blueprint / Editing DNA object representing the learned editing style
 * from a reference video. Independent of reference video pixels.
 * Contains:
 * - Split rhythm & pacing
 * - Speech-to-cut and split-to-zoom timing relationships
 * - Zoom in/out curves, hold durations, and scale variations
 * - Subject/Face-aware framing biases
 * - Keyframe easing and curve interpolation
 */
data class EditingDNA(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val referenceVideoName: String = "Reference Style",
    val referenceDurationMs: Long = 15000L,
    val pacingStyle: String = "Dynamic Conversational", // "High Energy Punch-In", "Clean Thought Flow", etc.
    val totalCutsDetected: Int = 4,
    val averageSentenceDurationMs: Long = 3200L,
    val speechToSplitOffsetMs: Long = 0L,
    val splitToZoomOffsetMs: Long = -120L,
    val zoomDurationMs: Long = 650L,
    val holdDurationMs: Long = 300L,
    val zoomDirection: ZoomDirection = ZoomDirection.ZOOM_OUT_THEN_IN,
    val zoomTiming: ZoomTiming = ZoomTiming.SPANNING_SPLIT,
    val scaleMin: Float = 0.90f,
    val scaleMax: Float = 1.18f,
    val positionXBias: Float = 0f,
    val positionYBias: Float = -0.08f, // Face awareness: centers speaker's face higher to prevent head cut
    val easingType: EasingType = EasingType.EASE_IN_OUT,
    val normalizedCurve: List<NormalizedCurvePoint> = defaultCurve(zoomDirection, scaleMin, scaleMax),
    val segmentPatterns: List<ReferenceSegmentPattern> = emptyList(),
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun defaultCurve(direction: ZoomDirection, minS: Float, maxS: Float): List<NormalizedCurvePoint> {
            return when (direction) {
                ZoomDirection.ZOOM_OUT_THEN_IN -> listOf(
                    NormalizedCurvePoint(0.0f, 1.0f),
                    NormalizedCurvePoint(0.25f, (1.0f + minS) / 2f),
                    NormalizedCurvePoint(0.5f, minS),
                    NormalizedCurvePoint(0.75f, (1.0f + minS) / 2f),
                    NormalizedCurvePoint(1.0f, 1.0f)
                )
                ZoomDirection.ZOOM_IN_THEN_OUT -> listOf(
                    NormalizedCurvePoint(0.0f, 1.0f),
                    NormalizedCurvePoint(0.25f, (1.0f + maxS) / 2f),
                    NormalizedCurvePoint(0.5f, maxS),
                    NormalizedCurvePoint(0.75f, (1.0f + maxS) / 2f),
                    NormalizedCurvePoint(1.0f, 1.0f)
                )
                ZoomDirection.ZOOM_IN -> listOf(
                    NormalizedCurvePoint(0.0f, 1.0f),
                    NormalizedCurvePoint(0.5f, (1.0f + maxS) / 2f),
                    NormalizedCurvePoint(1.0f, maxS)
                )
                ZoomDirection.ZOOM_OUT -> listOf(
                    NormalizedCurvePoint(0.0f, 1.0f),
                    NormalizedCurvePoint(0.5f, (1.0f + minS) / 2f),
                    NormalizedCurvePoint(1.0f, minS)
                )
                ZoomDirection.NO_ZOOM -> listOf(
                    NormalizedCurvePoint(0.0f, 1.0f),
                    NormalizedCurvePoint(1.0f, 1.0f)
                )
            }
        }

        fun getBuiltInPresets(): List<EditingDNA> {
            return listOf(
                EditingDNA(
                    id = "preset_punchy_talking_head",
                    name = "Punchy Talking Head",
                    description = "Dynamic speech splits with 1.18x punch-in on sentence emphasis & face-safe framing.",
                    referenceVideoName = "Preset: Viral Punchy",
                    referenceDurationMs = 30000L,
                    pacingStyle = "High Energy Punch-In",
                    totalCutsDetected = 8,
                    averageSentenceDurationMs = 2800L,
                    speechToSplitOffsetMs = 20L,
                    splitToZoomOffsetMs = -100L,
                    zoomDurationMs = 500L,
                    holdDurationMs = 250L,
                    zoomDirection = ZoomDirection.ZOOM_IN_THEN_OUT,
                    zoomTiming = ZoomTiming.AT_SPLIT,
                    scaleMin = 1.0f,
                    scaleMax = 1.18f,
                    positionXBias = 0.0f,
                    positionYBias = -0.09f,
                    easingType = EasingType.EASE_IN_OUT,
                    isBuiltIn = true
                ),
                EditingDNA(
                    id = "preset_clean_educational",
                    name = "Clean Educational",
                    description = "Thoughtful sentence boundaries with smooth 1.12x ease-in-out zoom transitions.",
                    referenceVideoName = "Preset: Studio Clean",
                    referenceDurationMs = 45000L,
                    pacingStyle = "Clean Thought Flow",
                    totalCutsDetected = 6,
                    averageSentenceDurationMs = 4200L,
                    speechToSplitOffsetMs = 0L,
                    splitToZoomOffsetMs = -150L,
                    zoomDurationMs = 700L,
                    holdDurationMs = 400L,
                    zoomDirection = ZoomDirection.ZOOM_OUT_THEN_IN,
                    zoomTiming = ZoomTiming.SPANNING_SPLIT,
                    scaleMin = 0.91f,
                    scaleMax = 1.12f,
                    positionXBias = 0.0f,
                    positionYBias = -0.07f,
                    easingType = EasingType.EASE_IN_OUT,
                    isBuiltIn = true
                ),
                EditingDNA(
                    id = "preset_documentary_cadence",
                    name = "Documentary Cadence",
                    description = "Deliberate pause cuts with gentle slow zoom motion across phrase closures.",
                    referenceVideoName = "Preset: Cinematic Doc",
                    referenceDurationMs = 60000L,
                    pacingStyle = "Cinematic Thought Pace",
                    totalCutsDetected = 5,
                    averageSentenceDurationMs = 5500L,
                    speechToSplitOffsetMs = -30L,
                    splitToZoomOffsetMs = -200L,
                    zoomDurationMs = 950L,
                    holdDurationMs = 600L,
                    zoomDirection = ZoomDirection.ZOOM_IN,
                    zoomTiming = ZoomTiming.AFTER_SPLIT,
                    scaleMin = 0.95f,
                    scaleMax = 1.10f,
                    positionXBias = 0.0f,
                    positionYBias = -0.06f,
                    easingType = EasingType.EASE_OUT,
                    isBuiltIn = true
                )
            )
        }
    }
}
