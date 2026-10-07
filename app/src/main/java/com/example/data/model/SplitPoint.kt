package com.example.data.model

import java.util.UUID

enum class SplitReason(val displayName: String, val icon: String, val description: String) {
    SENTENCE_COMPLETED("Sentence Completed", "📝", "Full grammatical thought finished naturally"),
    SPEAKER_CHANGED("Speaker Changed", "🗣️", "Voice acoustic transition detected"),
    NATURAL_PAUSE("Natural Pause", "⏸️", "Meaningful conversational breath pause"),
    SILENCE_DETECTED("Silence Detected", "🔇", "Extended quiet period in audio"),
    SCENE_CHANGE("Scene Transition", "🎬", "Visual camera cut matching cadence"),
    MANUAL("Manual Cut", "✂️", "User placed custom split point")
}

enum class ConfidenceLevel(val label: String) {
    HIGH("HIGH"),
    MEDIUM("MEDIUM"),
    LOW("LOW");

    companion object {
        fun fromScore(score: Float): ConfidenceLevel {
            return when {
                score >= 0.85f -> HIGH
                score >= 0.70f -> MEDIUM
                else -> LOW
            }
        }
    }
}

enum class ZoomDirection(val label: String) {
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    ZOOM_OUT_THEN_IN("Zoom Out → In"),
    ZOOM_IN_THEN_OUT("Zoom In → Out"),
    NO_ZOOM("No Zoom")
}

enum class ZoomTiming(val label: String) {
    BEFORE_SPLIT("Starts Before Split"),
    AT_SPLIT("Starts At Split"),
    AFTER_SPLIT("Starts After Split"),
    SPANNING_SPLIT("Spans Across Split"),
    NO_ZOOM("No Zoom")
}

enum class EasingType(val label: String) {
    EASE_IN_OUT("Ease In-Out"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    LINEAR("Linear")
}

data class TransformKeyframe(
    val timestampMs: Long,
    val scale: Float, // 1.0 = normal, >1.0 = zoomed in, <1.0 = zoomed out
    val positionX: Float = 0f, // Normalized [-1.0 .. 1.0]
    val positionY: Float = 0f, // Normalized [-1.0 .. 1.0]
    val rotation: Float = 0f
) {
    val formattedTime: String
        get() = SplitPoint.formatTimestamp(timestampMs)
}

data class NormalizedCurvePoint(
    val progress: Float, // 0.0 to 1.0
    val scale: Float // e.g. 1.00 -> 1.18 -> 1.00
)

data class ReferenceSegmentPattern(
    val segmentIndex: Int,
    val cutTimestampMs: Long,
    val speechEndToSplitOffsetMs: Long = 0L,
    val splitToZoomOffsetMs: Long = -150L, // negative = starts before split
    val zoomDurationMs: Long = 650L,
    val zoomDirection: ZoomDirection = ZoomDirection.ZOOM_OUT_THEN_IN,
    val zoomTiming: ZoomTiming = ZoomTiming.SPANNING_SPLIT,
    val scaleMin: Float = 0.92f,
    val scaleMax: Float = 1.15f,
    val easingType: EasingType = EasingType.EASE_IN_OUT,
    val normalizedCurve: List<NormalizedCurvePoint> = emptyList()
)

data class ReferenceEditProfile(
    val referenceVideoUri: String,
    val referenceDurationMs: Long,
    val totalCutsDetected: Int,
    val patterns: List<ReferenceSegmentPattern> = emptyList()
)

data class SplitPoint(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long,
    val reason: SplitReason = SplitReason.SENTENCE_COMPLETED,
    val confidence: Float = 0.92f, // 0.0 - 1.0
    val isAi: Boolean = true,
    val note: String = "",
    val keyframes: List<TransformKeyframe> = emptyList(),
    val appliedPattern: ReferenceSegmentPattern? = null,
    val zoomIntensityMultiplier: Float = 1.0f
) {
    val confidenceLevel: ConfidenceLevel
        get() = ConfidenceLevel.fromScore(confidence)

    val confidencePercent: Int
        get() = (confidence * 100).toInt().coerceIn(0, 100)

    val formattedTime: String
        get() = formatTimestamp(timestampMs)

    fun getScaleAt(targetMs: Long): Float {
        if (keyframes.isEmpty()) return 1.0f
        if (targetMs <= keyframes.first().timestampMs) return keyframes.first().scale
        if (targetMs >= keyframes.last().timestampMs) return keyframes.last().scale

        for (i in 0 until keyframes.size - 1) {
            val kf1 = keyframes[i]
            val kf2 = keyframes[i + 1]
            if (targetMs in kf1.timestampMs..kf2.timestampMs) {
                val span = (kf2.timestampMs - kf1.timestampMs).toFloat().coerceAtLeast(1f)
                val fraction = (targetMs - kf1.timestampMs) / span
                // Apply easing
                val eased = when (appliedPattern?.easingType ?: EasingType.EASE_IN_OUT) {
                    EasingType.EASE_IN_OUT -> {
                        // Smooth cubic easing: 3t^2 - 2t^3
                        (fraction * fraction * (3f - 2f * fraction))
                    }
                    EasingType.EASE_IN -> fraction * fraction
                    EasingType.EASE_OUT -> fraction * (2f - fraction)
                    EasingType.LINEAR -> fraction
                }
                val rawScale = kf1.scale + (kf2.scale - kf1.scale) * eased
                return 1.0f + (rawScale - 1.0f) * zoomIntensityMultiplier
            }
        }
        return 1.0f
    }

    companion object {
        fun formatTimestamp(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millis = ms % 1000
            return String.format("%02d:%02d.%03d", minutes, seconds, millis)
        }

        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}

data class VideoClip(
    val clipIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long = endMs - startMs,
    val splitReason: SplitReason = SplitReason.SENTENCE_COMPLETED,
    val exportPath: String? = null,
    val isExported: Boolean = false,
    val keyframes: List<TransformKeyframe> = emptyList()
) {
    val formattedRange: String
        get() = "${SplitPoint.formatTimestamp(startMs)} → ${SplitPoint.formatTimestamp(endMs)}"

    val formattedDuration: String
        get() = String.format("%.1fs", durationMs / 1000.0)
}

data class VideoMetadata(
    val uriString: String,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val fps: Float,
    val hasAudio: Boolean,
    val mimeType: String = "video/mp4"
) {
    val formattedDuration: String
        get() = SplitPoint.formatDuration(durationMs)

    val formattedSize: String
        get() {
            val mb = fileSizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format("%.1f MB", mb) else "${fileSizeBytes / 1024} KB"
        }

    val resolutionText: String
        get() = "${width}x${height}"
}

enum class Sensitivity(val label: String, val pauseThresholdMs: Long) {
    LOW("Low", 800L),
    BALANCED("Balanced", 500L),
    HIGH("High", 350L)
}

data class AnalysisSettings(
    val autoSpeechDetection: Boolean = true,
    val sentenceDetection: Boolean = true,
    val speakerDetection: Boolean = true,
    val naturalPauseDetection: Boolean = true,
    val sceneAssistance: Boolean = true,
    val showConfidence: Boolean = true,
    val sensitivity: Sensitivity = Sensitivity.BALANCED,
    val minSplitDistanceSeconds: Int = 2,
    val exportFormat: String = "MP4",
    val exportQuality: String = "Original",
    val exportFps: String = "Original"
)
