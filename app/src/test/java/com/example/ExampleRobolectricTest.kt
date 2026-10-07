package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConfidenceLevel
import com.example.data.model.SplitPoint
import com.example.data.model.SplitReason
import com.example.data.repository.ProjectRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun testSplitsSerialization() {
        val splits = listOf(
            SplitPoint(
                timestampMs = 18420L,
                reason = SplitReason.SENTENCE_COMPLETED,
                confidence = 0.96f,
                isAi = true,
                note = "Sentence ending cadence"
            ),
            SplitPoint(
                timestampMs = 42150L,
                reason = SplitReason.SPEAKER_CHANGED,
                confidence = 0.91f,
                isAi = true,
                note = "Vocal shift"
            )
        )

        val serialized = ProjectRepository.serializeSplits(splits)
        val deserialized = ProjectRepository.deserializeSplits(serialized)

        assertEquals(2, deserialized.size)
        assertEquals(18420L, deserialized[0].timestampMs)
        assertEquals(SplitReason.SENTENCE_COMPLETED, deserialized[0].reason)
        assertEquals(0.96f, deserialized[0].confidence, 0.01f)
        assertEquals(42150L, deserialized[1].timestampMs)
        assertEquals(SplitReason.SPEAKER_CHANGED, deserialized[1].reason)
    }

    @Test
    fun testConfidenceClassification() {
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.fromScore(0.95f))
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.fromScore(0.85f))
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.fromScore(0.84f))
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.fromScore(0.70f))
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.fromScore(0.69f))
    }
}
