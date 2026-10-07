package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import com.example.data.model.VideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object VideoSplitter {

    sealed class SplitProgress {
        data class Progress(val clipIndex: Int, val totalClips: Int, val percent: Float, val clipName: String) : SplitProgress()
        data class Completed(val exportedFiles: List<File>) : SplitProgress()
        data class Failed(val error: String) : SplitProgress()
    }

    suspend fun exportClips(
        context: Context,
        sourceUri: Uri,
        clips: List<VideoClip>,
        onProgress: (SplitProgress) -> Unit
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        val totalClips = clips.size
        if (totalClips == 0) {
            val err = "No clips to export."
            onProgress(SplitProgress.Failed(err))
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        // Storage check
        val availableBytes = VideoProcessor.getAvailableStorageBytes(context)
        val estimatedNeededBytes = 50L * 1024L * 1024L * totalClips // rough estimate
        if (availableBytes < estimatedNeededBytes && availableBytes < 20L * 1024L * 1024L) {
            val err = "Not enough storage space on device."
            onProgress(SplitProgress.Failed(err))
            return@withContext Result.failure(IllegalStateException(err))
        }

        val exportDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "AutoSplit_Exports"
        ).apply { mkdirs() }

        val exportedFiles = mutableListOf<File>()

        try {
            for (i in clips.indices) {
                val clip = clips[i]
                val clipFileName = "AutoSplit_${System.currentTimeMillis()}_clip_${clip.clipIndex}.mp4"
                val outputFile = File(exportDir, clipFileName)

                onProgress(
                    SplitProgress.Progress(
                        clipIndex = i + 1,
                        totalClips = totalClips,
                        percent = 0.05f,
                        clipName = "Clip ${clip.clipIndex}"
                    )
                )

                val success = trimVideoSegment(
                    context = context,
                    sourceUri = sourceUri,
                    startMs = clip.startMs,
                    endMs = clip.endMs,
                    outputFile = outputFile
                ) { stepPercent ->
                    onProgress(
                        SplitProgress.Progress(
                            clipIndex = i + 1,
                            totalClips = totalClips,
                            percent = stepPercent,
                            clipName = "Clip ${clip.clipIndex}"
                        )
                    )
                }

                if (success && outputFile.exists() && outputFile.length() > 0) {
                    exportedFiles.add(outputFile)
                } else {
                    val err = "Failed exporting Clip ${clip.clipIndex}."
                    onProgress(SplitProgress.Failed(err))
                    return@withContext Result.failure(RuntimeException(err))
                }
            }

            onProgress(SplitProgress.Completed(exportedFiles))
            Result.success(exportedFiles)
        } catch (e: Exception) {
            e.printStackTrace()
            val msg = e.localizedMessage ?: "Export failed. Your original video is safe."
            onProgress(SplitProgress.Failed(msg))
            Result.failure(e)
        }
    }

    private fun trimVideoSegment(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, sourceUri, null)
            val trackCount = extractor.trackCount

            var videoTrackIndex = -1
            var audioTrackIndex = -1

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                }
            }

            if (videoTrackIndex == -1) return false

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackMap = HashMap<Int, Int>()

            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            muxerTrackMap[videoTrackIndex] = muxerVideoTrack

            var muxerAudioTrack = -1
            if (audioTrackIndex != -1) {
                val audioFormat = extractor.getTrackFormat(audioTrackIndex)
                muxerAudioTrack = muxer.addTrack(audioFormat)
                muxerTrackMap[audioTrackIndex] = muxerAudioTrack
            }

            muxer.start()

            // Seek extractor to startMs
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val durationUs = (endUs - startUs).coerceAtLeast(1000L)

            // Select both tracks for muxing
            extractor.selectTrack(videoTrackIndex)
            if (audioTrackIndex != -1) {
                extractor.selectTrack(audioTrackIndex)
            }
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val maxBufferSize = 1024 * 1024 // 1MB buffer
            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoPtsOffset = -1L
            var audioPtsOffset = -1L

            var lastReportMs = System.currentTimeMillis()

            while (true) {
                val sampleTrackIndex = extractor.sampleTrackIndex
                if (sampleTrackIndex < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs && sampleTrackIndex == videoTrackIndex) {
                    // Video has reached beyond clip boundary
                    break
                }

                val targetMuxerTrack = muxerTrackMap[sampleTrackIndex]
                if (targetMuxerTrack != null && sampleTimeUs in (startUs - 2_000_000L)..endUs) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize > 0) {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.flags = extractor.sampleFlags

                        if (sampleTrackIndex == videoTrackIndex) {
                            if (videoPtsOffset == -1L) videoPtsOffset = sampleTimeUs
                            bufferInfo.presentationTimeUs = (sampleTimeUs - videoPtsOffset).coerceAtLeast(0L)
                        } else if (sampleTrackIndex == audioTrackIndex) {
                            if (audioPtsOffset == -1L) audioPtsOffset = sampleTimeUs
                            bufferInfo.presentationTimeUs = (sampleTimeUs - audioPtsOffset).coerceAtLeast(0L)
                        }

                        muxer.writeSampleData(targetMuxerTrack, buffer, bufferInfo)

                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > 200) {
                            val curUs = (sampleTimeUs - startUs).coerceAtLeast(0L)
                            val pct = (curUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            onProgress(pct)
                            lastReportMs = now
                        }
                    }
                }

                extractor.advance()
            }

            onProgress(1f)
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            runCatching {
                muxer?.stop()
                muxer?.release()
            }
            runCatching { extractor.release() }
        }
    }
}
