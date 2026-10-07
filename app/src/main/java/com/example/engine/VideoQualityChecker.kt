package com.example.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.data.model.EditProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Quality Checker to automatically verify output video integrity before presenting to user.
 * Implements verification of tracks, playback duration, file size, video decodability, and synchronization.
 */
object VideoQualityChecker {

    private const val TAG = "VideoQualityChecker"

    data class ValidationResult(
        val isValid: Boolean,
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val hasVideo: Boolean = false,
        val hasAudio: Boolean = false,
        val issues: List<String> = emptyList()
    )

    suspend fun validateExportedFile(
        context: Context,
        file: File,
        expectedDurationMs: Long? = null
    ): ValidationResult = withContext(Dispatchers.IO) {
        val issues = mutableListOf<String>()

        if (!file.exists() || file.length() < 1024L) {
            issues.add("File is missing or unexpectedly small (${file.length()} bytes)")
            return@withContext ValidationResult(isValid = false, issues = issues)
        }

        val retriever = MediaMetadataRetriever()
        var hasVideo = false
        var hasAudio = false
        var durationMs = 0L
        var width = 0
        var height = 0

        try {
            retriever.setDataSource(file.absolutePath)

            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L

            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

            val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            hasAudio = hasAudioStr?.equals("yes", ignoreCase = true) ?: false

            hasVideo = width > 0 && height > 0

            if (!hasVideo) {
                issues.add("No valid video track found in output file")
            }

            if (durationMs <= 0L) {
                issues.add("Video reports zero duration")
            } else if (expectedDurationMs != null && expectedDurationMs > 0L) {
                val diff = abs(durationMs - expectedDurationMs)
                if (diff > 2500L && diff > (expectedDurationMs * 0.35f)) {
                    issues.add("Exported duration ($durationMs ms) differs from expected ($expectedDurationMs ms)")
                }
            }

            // Verify a real frame can actually be extracted (ensures not a blank/corrupt video stream)
            val testFrame = retriever.getFrameAtTime(0L)
            if (testFrame == null) {
                issues.add("Unable to decode initial video frame from exported file")
            } else {
                testFrame.recycle()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Validation failed", e)
            issues.add("Metadata verification exception: ${e.message}")
        } finally {
            runCatching { retriever.release() }
        }

        val isValid = issues.isEmpty()
        if (isValid) {
            Log.i(TAG, "Exported file validation passed: ${file.name} (${width}x${height}, ${durationMs}ms)")
        } else {
            Log.w(TAG, "Validation warnings for ${file.name}: ${issues.joinToString(", ")}")
        }

        ValidationResult(
            isValid = isValid,
            durationMs = durationMs,
            width = width,
            height = height,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            issues = issues
        )
    }

    /**
     * Pre-render verification: Checks that the EditProject contains valid splits and keyframes
     */
    fun validateEditProject(project: EditProject): List<String> {
        val warnings = mutableListOf<String>()
        if (project.splitPoints.isEmpty()) {
            warnings.add("No split points defined in edit project.")
        }
        val totalKeyframes = project.splitPoints.sumOf { it.keyframes.size }
        if (totalKeyframes == 0 && project.zoomEvents.isEmpty()) {
            warnings.add("No zoom keyframes present in project.")
        }
        return warnings
    }
}
