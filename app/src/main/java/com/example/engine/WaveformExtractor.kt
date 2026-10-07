package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object WaveformExtractor {

    suspend fun extractWaveform(
        context: Context,
        uri: Uri,
        durationMs: Long,
        bucketCount: Int = 120,
        onProgress: (Float) -> Unit = {}
    ): FloatArray = withContext(Dispatchers.IO) {
        val result = FloatArray(bucketCount) { 0.1f }
        if (durationMs <= 0) return@withContext result

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                // No audio track in video
                return@withContext FloatArray(bucketCount) { 0.05f }
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bucketDurationMs = durationMs.toDouble() / bucketCount
            val bucketSums = DoubleArray(bucketCount)
            val bucketCounts = IntArray(bucketCount)

            val info = MediaCodec.BufferInfo()
            var isEOS = false
            val timeoutUs = 5000L

            var lastReportTime = System.currentTimeMillis()

            while (!isEOS) {
                // Feed input buffers
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()

                            // Report progress periodically
                            val now = System.currentTimeMillis()
                            if (now - lastReportTime > 250) {
                                val currentMs = sampleTime / 1000L
                                val progress = (currentMs.toFloat() / durationMs).coerceIn(0f, 1f)
                                onProgress(progress)
                                lastReportTime = now
                            }
                        }
                    }
                }

                // Process output buffers
                var outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                while (outIndex >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                    }

                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        outBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val ptsMs = (info.presentationTimeUs / 1000L).coerceAtLeast(0L)
                        val bucketIdx = (ptsMs / bucketDurationMs).toInt().coerceIn(0, bucketCount - 1)

                        // Calculate RMS of PCM 16-bit samples in this chunk
                        var sumSquares = 0.0
                        var count = 0
                        val shortBuffer = outBuffer.asShortBuffer()
                        val stride = 4 // sample every 4th to remain fast
                        var i = 0
                        while (i < shortBuffer.remaining()) {
                            val sample = shortBuffer.get(i).toDouble() / 32768.0
                            sumSquares += sample * sample
                            count++
                            i += stride
                        }

                        if (count > 0) {
                            val rms = sqrt(sumSquares / count)
                            bucketSums[bucketIdx] += rms
                            bucketCounts[bucketIdx]++
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }

            // Normalize results
            var maxRms = 0.001
            for (i in 0 until bucketCount) {
                val avg = if (bucketCounts[i] > 0) bucketSums[i] / bucketCounts[i] else 0.0
                if (avg > maxRms) maxRms = avg
            }

            for (i in 0 until bucketCount) {
                val avg = if (bucketCounts[i] > 0) bucketSums[i] / bucketCounts[i] else 0.0
                result[i] = (avg / maxRms).toFloat().coerceIn(0.05f, 1.0f)
            }
            onProgress(1f)

        } catch (e: Exception) {
            e.printStackTrace()
            // If decoding failed, fallback to packet-size inspection via MediaExtractor
            return@withContext extractWaveformFromSamplePackets(context, uri, durationMs, bucketCount)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        return@withContext result
    }

    private fun extractWaveformFromSamplePackets(
        context: Context,
        uri: Uri,
        durationMs: Long,
        bucketCount: Int
    ): FloatArray {
        val result = FloatArray(bucketCount) { 0.15f }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex == -1) return result
            extractor.selectTrack(audioTrackIndex)

            val bucketDurationMs = durationMs.toDouble() / bucketCount
            val bucketSums = LongArray(bucketCount)
            val bucketCounts = IntArray(bucketCount)

            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val ptsMs = (extractor.sampleTime / 1000L).coerceAtLeast(0L)
                val bucketIdx = (ptsMs / bucketDurationMs).toInt().coerceIn(0, bucketCount - 1)
                bucketSums[bucketIdx] += sampleSize
                bucketCounts[bucketIdx]++
                extractor.advance()
            }

            var maxVal = 1L
            for (i in 0 until bucketCount) {
                val avg = if (bucketCounts[i] > 0) bucketSums[i] / bucketCounts[i] else 0L
                if (avg > maxVal) maxVal = avg
            }

            for (i in 0 until bucketCount) {
                val avg = if (bucketCounts[i] > 0) bucketSums[i] / bucketCounts[i] else 0L
                result[i] = (avg.toFloat() / maxVal).coerceIn(0.08f, 1.0f)
            }
        } catch (e: Exception) {
            // Keep default gently undulating profile
        } finally {
            runCatching { extractor.release() }
        }
        return result
    }
}
