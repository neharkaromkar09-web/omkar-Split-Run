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

data class SplitPoint(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long,
    val reason: SplitReason = SplitReason.SENTENCE_COMPLETED,
    val confidence: Float = 0.92f, // 0.0 - 1.0
    val isAi: Boolean = true,
    val note: String = ""
) {
    val confidenceLevel: ConfidenceLevel
        get() = ConfidenceLevel.fromScore(confidence)

    val confidencePercent: Int
        get() = (confidence * 100).toInt().coerceIn(0, 100)

    val formattedTime: String
        get() = formatTimestamp(timestampMs)

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
    val isExported: Boolean = false
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
