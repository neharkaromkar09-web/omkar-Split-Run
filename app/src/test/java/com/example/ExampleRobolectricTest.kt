package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConfidenceLevel
import com.example.data.model.EasingType
import com.example.data.model.ReferenceEditProfile
import com.example.data.model.ReferenceSegmentPattern
import com.example.data.model.SplitPoint
import com.example.data.model.SplitReason
import com.example.data.model.TransformKeyframe
import com.example.data.model.ZoomDirection
import com.example.data.model.ZoomTiming
import com.example.data.repository.ProjectRepository
import com.example.engine.EffectTransferEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppNameResource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AutoSplit AI", appName)
    }

    @Test
    fun testSplitPointFormatting() {
        val split = SplitPoint(
            timestampMs = 78420L, // 1m 18s 420ms
            reason = SplitReason.SENTENCE_COMPLETED,
            confidence = 0.96f
        )
        assertEquals("01:18.420", split.formattedTime)
        assertEquals(ConfidenceLevel.HIGH, split.confidenceLevel)
        assertEquals(96, split.confidencePercent)
    }

    @Test
    fun testSplitsSerializationWithKeyframes() {
        val keyframes = listOf(
            TransformKeyframe(timestampMs = 18100L, scale = 1.0f),
            TransformKeyframe(timestampMs = 18420L, scale = 0.88f),
            TransformKeyframe(timestampMs = 18700L, scale = 1.0f)
        )

        val splits = listOf(
            SplitPoint(
                timestampMs = 18420L,
                reason = SplitReason.SENTENCE_COMPLETED,
                confidence = 0.96f,
                isAi = true,
                note = "Sentence ending cadence",
                keyframes = keyframes,
                zoomIntensityMultiplier = 1.2f
            )
        )

        val serialized = ProjectRepository.serializeSplits(splits)
        val deserialized = ProjectRepository.deserializeSplits(serialized)

        assertEquals(1, deserialized.size)
        assertEquals(18420L, deserialized[0].timestampMs)
        assertEquals(1.2f, deserialized[0].zoomIntensityMultiplier, 0.01f)
        assertEquals(3, deserialized[0].keyframes.size)
        assertEquals(0.88f, deserialized[0].keyframes[1].scale, 0.01f)
    }

    @Test
    fun testEffectTransferPreservesNewTimestampsAndTransfersZoomPattern() {
        // Reference video template with cut at 8950ms and zoom-out behavior
        val refPattern = ReferenceSegmentPattern(
            segmentIndex = 1,
            cutTimestampMs = 8950L,
            splitToZoomOffsetMs = -100L,
            zoomDurationMs = 600L,
            zoomDirection = ZoomDirection.ZOOM_OUT_THEN_IN,
            zoomTiming = ZoomTiming.SPANNING_SPLIT,
            scaleMin = 0.88f,
            scaleMax = 1.0f,
            easingType = EasingType.EASE_IN_OUT
        )

        val refProfile = ReferenceEditProfile(
            referenceVideoUri = "content://media/reference.mp4",
            referenceDurationMs = 15000L,
            totalCutsDetected = 1,
            patterns = listOf(refPattern)
        )

        // New video with speech cut detected at 4320ms (completely different duration and timing)
        val newSplits = listOf(
            SplitPoint(
                timestampMs = 4320L,
                reason = SplitReason.SENTENCE_COMPLETED,
                confidence = 0.94f
            )
        )

        val transferredSplits = EffectTransferEngine.mapReferenceEffectsToSplits(
            detectedSplits = newSplits,
            referenceProfile = refProfile,
            newVideoDurationMs = 30000L
        )

        assertEquals(1, transferredSplits.size)
        val result = transferredSplits[0]

        // CRITICAL CHECK: Reference timestamp 8950ms must NOT be copied.
        assertNotEquals(8950L, result.timestampMs)
        // Must use the new video's speech split timestamp:
        assertEquals(4320L, result.timestampMs)

        // Must transfer the zoom keyframes around the new split timestamp:
        assertTrue(result.keyframes.isNotEmpty())
        val peakKf = result.keyframes.minByOrNull { it.scale }
        assertEquals(0.88f, peakKf?.scale ?: 1f, 0.01f)
    }

    @Test
    fun testRealTimeZoomScaleInterpolation() {
        val keyframes = listOf(
            TransformKeyframe(timestampMs = 4000L, scale = 1.0f),
            TransformKeyframe(timestampMs = 4300L, scale = 1.20f),
            TransformKeyframe(timestampMs = 4600L, scale = 1.0f)
        )

        val split = SplitPoint(
            timestampMs = 4300L,
            keyframes = keyframes
        )

        // Before zoom effect starts: scale should be 1.0f
        assertEquals(1.0f, split.getScaleAt(3500L), 0.01f)

        // At peak of zoom effect: scale should be 1.20f
        assertEquals(1.20f, split.getScaleAt(4300L), 0.01f)

        // Midway between 4000ms and 4300ms: scale should be smoothly transitioning
        val midwayScale = split.getScaleAt(4150L)
        assertTrue(midwayScale > 1.0f && midwayScale < 1.20f)

        // After zoom effect completes: scale returns to 1.0f
        assertEquals(1.0f, split.getScaleAt(5000L), 0.01f)
    }
}
