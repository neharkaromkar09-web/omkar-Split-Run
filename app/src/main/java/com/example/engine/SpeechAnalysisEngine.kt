package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.data.model.AnalysisSettings
import com.example.data.model.SplitPoint
import com.example.data.model.SplitReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioSegment(
    val startMs: Long,
    val endMs: Long,
    val isSpeech: Boolean,
    val avgRms: Double,
    val pitchTrend: Double, // Negative: falling (sentence end), Positive: rising, Near zero: flat
    val spectralCentroid: Double // Voice timbre measure for speaker detection
)

data class SceneChange(
    val timestampMs: Long,
    val diffScore: Double
)

object SpeechAnalysisEngine {

    suspend fun analyzeVideo(
        context: Context,
        uri: Uri,
        durationMs: Long,
        settings: AnalysisSettings,
        onProgress: (stage: String, progress: Float) -> Unit
    ): List<SplitPoint> = withContext(Dispatchers.IO) {
        if (durationMs < 1000L) return@withContext emptyList()

        onProgress("Extracting audio...", 0.10f)
        val audioAnalysis = analyzeAcoustics(context, uri, durationMs) { progress ->
            onProgress("Extracting audio...", 0.10f + progress * 0.25f)
        }

        onProgress("Analyzing speech & rhythm...", 0.38f)
        val speechSegments = audioAnalysis.first
        val rawPauses = audioAnalysis.second

        onProgress("Detecting sentences & thoughts...", 0.52f)
        val sentenceCandidates = detectSentenceBoundaries(speechSegments, rawPauses, settings)

        onProgress("Detecting speaker changes...", 0.65f)
        val speakerCandidates = if (settings.speakerDetection) {
            detectSpeakerChanges(speechSegments, rawPauses)
        } else emptyList()

        val sceneCandidates = if (settings.sceneAssistance && durationMs > 3000L) {
            onProgress("Analyzing video scene cuts...", 0.78f)
            detectSceneChanges(context, uri, durationMs)
        } else emptyList()

        onProgress("Fusing signals & calculating splits...", 0.90f)
        val fusedSplits = fuseSplitSignals(
            durationMs = durationMs,
            sentenceCandidates = sentenceCandidates,
            speakerCandidates = speakerCandidates,
            pauses = rawPauses,
            sceneChanges = sceneCandidates,
            settings = settings
        )

        onProgress("Analysis complete.", 1.0f)
        return@withContext fusedSplits
    }

    private suspend fun analyzeAcoustics(
        context: Context,
        uri: Uri,
        durationMs: Long,
        onProgress: (Float) -> Unit
    ): Pair<List<AudioSegment>, List<Pair<Long, Long>>> = withContext(Dispatchers.IO) {
        val segments = mutableListOf<AudioSegment>()
        val pauses = mutableListOf<Pair<Long, Long>>() // startMs to endMs

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
                // If video has no audio, return empty
                return@withContext Pair(emptyList(), emptyList())
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEOS = false
            val timeoutUs = 5000L

            // 50ms frames for speech acoustic analysis
            val frameDurationMs = 50L
            var currentFramePts = 0L
            var currentFrameSamples = mutableListOf<Double>()

            var lastReportMs = 0L

            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                    if (inBuffer != null) {
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()

                            val currentMs = sampleTime / 1000L
                            if (currentMs - lastReportMs > 500) {
                                onProgress((currentMs.toFloat() / durationMs).coerceIn(0f, 1f))
                                lastReportMs = currentMs
                            }
                        }
                    }
                }

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
                        val shortBuf = outBuffer.asShortBuffer()
                        val stride = 2

                        var i = 0
                        while (i < shortBuf.remaining()) {
                            val s = shortBuf.get(i).toDouble() / 32768.0
                            currentFrameSamples.add(s)
                            i += stride

                            if (currentFrameSamples.size >= 800) { // roughly 50ms at 16kHz
                                val seg = processFrame(currentFramePts, currentFramePts + frameDurationMs, currentFrameSamples)
                                segments.add(seg)
                                currentFramePts += frameDurationMs
                                currentFrameSamples = mutableListOf()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }

            // Flush remaining frame
            if (currentFrameSamples.isNotEmpty()) {
                segments.add(processFrame(currentFramePts, durationMs, currentFrameSamples))
            }

            // Extract real conversational pauses from non-speech contiguous sequences
            var pauseStart = -1L
            for (seg in segments) {
                if (!seg.isSpeech) {
                    if (pauseStart == -1L) pauseStart = seg.startMs
                } else {
                    if (pauseStart != -1L) {
                        val pauseDuration = seg.startMs - pauseStart
                        if (pauseDuration >= 200L) { // minimum pause
                            pauses.add(Pair(pauseStart, seg.startMs))
                        }
                        pauseStart = -1L
                    }
                }
            }
            if (pauseStart != -1L && durationMs - pauseStart >= 200L) {
                pauses.add(Pair(pauseStart, durationMs))
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        return@withContext Pair(segments, pauses)
    }

    private fun processFrame(startMs: Long, endMs: Long, samples: List<Double>): AudioSegment {
        var sumSq = 0.0
        var zeroCrossings = 0
        var prev = 0.0

        for (s in samples) {
            sumSq += s * s
            if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) {
                zeroCrossings++
            }
            prev = s
        }

        val rms = sqrt(sumSq / samples.size.coerceAtLeast(1))
        val zcr = zeroCrossings.toDouble() / samples.size.coerceAtLeast(1)

        // Voice Activity Detection heuristic:
        // Voice is present when RMS > 0.02 and zero crossing rate matches typical vocal range
        val isSpeech = rms > 0.022 && zcr < 0.45

        // Pitch inflection trend: early half energy vs late half energy
        val half = samples.size / 2
        var firstHalfSum = 0.0
        var secondHalfSum = 0.0
        for (i in 0 until half) firstHalfSum += abs(samples[i])
        for (i in half until samples.size) secondHalfSum += abs(samples[i])
        val pitchTrend = (secondHalfSum - firstHalfSum) / (firstHalfSum + secondHalfSum + 1e-6)

        // Spectral centroid approximation via high vs low frequency distribution
        val spectralCentroid = (zcr * 1000.0) + (rms * 500.0)

        return AudioSegment(
            startMs = startMs,
            endMs = endMs,
            isSpeech = isSpeech,
            avgRms = rms,
            pitchTrend = pitchTrend,
            spectralCentroid = spectralCentroid
        )
    }

    private fun detectSentenceBoundaries(
        segments: List<AudioSegment>,
        pauses: List<Pair<Long, Long>>,
        settings: AnalysisSettings
    ): List<SplitPoint> {
        val candidates = mutableListOf<SplitPoint>()
        val minPause = settings.sensitivity.pauseThresholdMs

        for (pause in pauses) {
            val pauseDuration = pause.second - pause.first
            if (pauseDuration >= minPause) {
                // Find speech chunk immediately preceding this pause
                val precedingSpeech = segments.filter { it.endMs <= pause.first && it.endMs >= pause.first - 800L && it.isSpeech }
                val followingSpeech = segments.filter { it.startMs >= pause.second && it.startMs <= pause.second + 800L && it.isSpeech }

                if (precedingSpeech.isNotEmpty() && followingSpeech.isNotEmpty()) {
                    // Check pitch trend in preceding speech
                    val avgTrend = precedingSpeech.map { it.pitchTrend }.average()
                    // Falling pitch trend before pause is the acoustic marker of completed sentence/thought
                    val isFallingIntonation = avgTrend < -0.05

                    val confidence = when {
                        isFallingIntonation && pauseDuration >= 700L -> 0.96f
                        isFallingIntonation -> 0.91f
                        pauseDuration >= 1000L -> 0.88f
                        else -> 0.82f
                    }

                    val splitTimestamp = pause.first + (pauseDuration / 2)
                    candidates.add(
                        SplitPoint(
                            timestampMs = splitTimestamp,
                            reason = SplitReason.SENTENCE_COMPLETED,
                            confidence = confidence,
                            isAi = true,
                            note = "Sentence ending cadence (${pauseDuration}ms pause)"
                        )
                    )
                }
            }
        }
        return candidates
    }

    private fun detectSpeakerChanges(
        segments: List<AudioSegment>,
        pauses: List<Pair<Long, Long>>
    ): List<SplitPoint> {
        val candidates = mutableListOf<SplitPoint>()

        for (pause in pauses) {
            val pauseDuration = pause.second - pause.first
            if (pauseDuration in 250L..2500L) {
                val preChunk = segments.filter { it.endMs <= pause.first && it.endMs >= pause.first - 1200L && it.isSpeech }
                val postChunk = segments.filter { it.startMs >= pause.second && it.startMs <= pause.second + 1200L && it.isSpeech }

                if (preChunk.size >= 4 && postChunk.size >= 4) {
                    val preCentroid = preChunk.map { it.spectralCentroid }.average()
                    val postCentroid = postChunk.map { it.spectralCentroid }.average()
                    val preRms = preChunk.map { it.avgRms }.average()
                    val postRms = postChunk.map { it.avgRms }.average()

                    val centroidDelta = abs(postCentroid - preCentroid) / (preCentroid + 1e-4)
                    val rmsDelta = abs(postRms - preRms) / (preRms + 1e-4)

                    // Significant timbre or vocal pitch register shift across conversational gap
                    if (centroidDelta > 0.32 || (centroidDelta > 0.22 && rmsDelta > 0.40)) {
                        val confidence = (0.85f + (centroidDelta.toFloat() * 0.15f)).coerceIn(0.85f, 0.95f)
                        val splitTimestamp = pause.first + (pauseDuration / 2)
                        candidates.add(
                            SplitPoint(
                                timestampMs = splitTimestamp,
                                reason = SplitReason.SPEAKER_CHANGED,
                                confidence = confidence,
                                isAi = true,
                                note = "Acoustic vocal shift across turn"
                            )
                        )
                    }
                }
            }
        }
        return candidates
    }

    private suspend fun detectSceneChanges(
        context: Context,
        uri: Uri,
        durationMs: Long
    ): List<SceneChange> = withContext(Dispatchers.IO) {
        val sceneChanges = mutableListOf<SceneChange>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            // Sample frames every 2 seconds
            val stepMs = 2000L
            var timeMs = 1000L
            var prevBmp: Bitmap? = null

            while (timeMs < durationMs) {
                val currentBmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (currentBmp != null && prevBmp != null) {
                    val diff = calculateBitmapDifference(prevBmp, currentBmp)
                    if (diff > 0.45) { // notable scene transition
                        sceneChanges.add(SceneChange(timeMs, diff))
                    }
                }
                prevBmp?.recycle()
                prevBmp = currentBmp
                timeMs += stepMs
            }
            prevBmp?.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            runCatching { retriever.release() }
        }
        return@withContext sceneChanges
    }

    private fun calculateBitmapDifference(bmp1: Bitmap, bmp2: Bitmap): Double {
        // Downsample for fast difference calculation
        val w = 32
        val h = 18
        val s1 = Bitmap.createScaledBitmap(bmp1, w, h, false)
        val s2 = Bitmap.createScaledBitmap(bmp2, w, h, false)

        var totalDiff = 0.0
        val count = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p1 = s1.getPixel(x, y)
                val p2 = s2.getPixel(x, y)
                val rDiff = abs(((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)) / 255.0
                val gDiff = abs(((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)) / 255.0
                val bDiff = abs((p1 and 0xff) - (p2 and 0xff)) / 255.0
                totalDiff += (rDiff + gDiff + bDiff) / 3.0
            }
        }
        s1.recycle()
        s2.recycle()
        return totalDiff / count
    }

    private fun fuseSplitSignals(
        durationMs: Long,
        sentenceCandidates: List<SplitPoint>,
        speakerCandidates: List<SplitPoint>,
        pauses: List<Pair<Long, Long>>,
        sceneChanges: List<SceneChange>,
        settings: AnalysisSettings
    ): List<SplitPoint> {
        val minIntervalMs = settings.minSplitDistanceSeconds * 1000L
        val candidatePool = mutableListOf<SplitPoint>()

        // 1. Sentence completions
        if (settings.sentenceDetection) {
            candidatePool.addAll(sentenceCandidates)
        }

        // 2. Speaker changes (highest priority when they coincide)
        if (settings.speakerDetection) {
            candidatePool.addAll(speakerCandidates)
        }

        // 3. Natural pauses / Silence if enabled and no candidate exists nearby
        if (settings.naturalPauseDetection) {
            for (pause in pauses) {
                val pauseDur = pause.second - pause.first
                val center = pause.first + (pauseDur / 2)
                if (pauseDur >= 1200L) {
                    val isSilence = pauseDur >= 2500L
                    val reason = if (isSilence) SplitReason.SILENCE_DETECTED else SplitReason.NATURAL_PAUSE
                    val conf = if (isSilence) 0.90f else 0.76f
                    candidatePool.add(
                        SplitPoint(
                            timestampMs = center,
                            reason = reason,
                            confidence = conf,
                            isAi = true,
                            note = "${pauseDur}ms acoustic quiet"
                        )
                    )
                }
            }
        }

        // Sort chronologically
        candidatePool.sortBy { it.timestampMs }

        // Filter too close to boundaries (keep at least 500ms from start or end)
        val validTimeRange = candidatePool.filter { it.timestampMs in 600L..(durationMs - 600L) }

        // Merge nearby candidate points within minIntervalMs
        val merged = mutableListOf<SplitPoint>()

        for (candidate in validTimeRange) {
            val last = merged.lastOrNull()
            if (last == null || (candidate.timestampMs - last.timestampMs) >= minIntervalMs) {
                // Check if a visual scene cut closely aligns to boost confidence
                val matchingScene = sceneChanges.find { abs(it.timestampMs - candidate.timestampMs) < 1200L }
                val finalConfidence = if (matchingScene != null) {
                    (candidate.confidence + 0.05f).coerceAtMost(0.99f)
                } else candidate.confidence

                merged.add(candidate.copy(confidence = finalConfidence))
            } else {
                // Collide: pick the higher priority one (Speaker > Sentence > Pause > Silence)
                val currentPriority = getPriority(candidate.reason)
                val lastPriority = getPriority(last.reason)
                if (currentPriority > lastPriority || (currentPriority == lastPriority && candidate.confidence > last.confidence)) {
                    merged[merged.lastIndex] = candidate
                }
            }
        }

        return merged
    }

    private fun getPriority(reason: SplitReason): Int {
        return when (reason) {
            SplitReason.SPEAKER_CHANGED -> 4
            SplitReason.SENTENCE_COMPLETED -> 3
            SplitReason.NATURAL_PAUSE -> 2
            SplitReason.SILENCE_DETECTED -> 1
            SplitReason.SCENE_CHANGE -> 1
            SplitReason.MANUAL -> 5
        }
    }
}
