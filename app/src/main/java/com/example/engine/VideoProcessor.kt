package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import com.example.data.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object VideoProcessor {

    suspend fun extractMetadata(context: Context, uri: Uri): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Video file appears empty or unreadable."))
            }

            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            // Adjust width/height if rotated 90 or 270 degrees
            if (rotation == 90 || rotation == 270) {
                val temp = width
                width = height
                height = temp
            }

            val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val hasAudio = hasAudioStr?.equals("yes", ignoreCase = true) ?: true

            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"

            var fileName = "Video_${System.currentTimeMillis()}.mp4"
            var fileSize = 0L

            // Read display name and size from content resolver
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        fileName = cursor.getString(nameIdx) ?: fileName
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                }
            }

            if (fileSize == 0L) {
                // Try from file descriptor or cache
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        fileSize = pfd.statSize
                    }
                }
            }

            Result.success(
                VideoMetadata(
                    uriString = uri.toString(),
                    fileName = fileName,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    fileSizeBytes = fileSize,
                    fps = captureFps,
                    hasAudio = hasAudio,
                    mimeType = mimeType
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun extractThumbnail(
        context: Context,
        uri: Uri,
        timeMs: Long = 0L,
        targetWidth: Int = 360,
        targetHeight: Int = 202
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val timeUs = (timeMs * 1000L).coerceAtLeast(0L)
            val original = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                ?: retriever.frameAtTime

            original?.let { bmp ->
                val scaled = Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true)
                if (scaled != bmp) bmp.recycle()
                scaled
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun saveThumbnailToCache(context: Context, bitmap: Bitmap, prefix: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "${prefix}_thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun getAvailableStorageBytes(context: Context): Long {
        return try {
            val path = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val stat = StatFs(path.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            1024L * 1024L * 500L // 500MB fallback assumption
        }
    }
}
