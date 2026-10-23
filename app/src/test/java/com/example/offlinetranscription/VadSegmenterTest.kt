package com.example.offlinetranscription

import com.example.offlinetranscription.audio.SegmentEvent
import com.example.offlinetranscription.audio.VadSegmenter
import com.example.offlinetranscription.nemo.TranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {

    @Test
    fun `silence produces no events initially`() {
        val segmenter = VadSegmenter(sampleRate = 16000)
        val silenceChunk = FloatArray(1600) { 0.0f }

        val event = segmenter.processChunk(silenceChunk)
        assertNull("Silence should not trigger speech event", event)
    }

    @Test
    fun `speech followed by silence commits endpoint`() {
        val segmenter = VadSegmenter(
            sampleRate = 16000,
            silenceCommitDurationMs = 200L
        )

        // Generate synthetic speech chunk (sine wave, RMS ~0.7)
        val speechChunk = FloatArray(3200) { i ->
            kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0).toFloat()
        }
        val silenceChunk = FloatArray(1600) { 0.0f } // 100ms silence each

        // Feed speech
        segmenter.processChunk(speechChunk)

        // Feed silence chunks until silenceCommitDurationMs (200ms) is reached
        segmenter.processChunk(silenceChunk)
        val commitEvent = segmenter.processChunk(silenceChunk)

        assertNotNull("Silence after speech should trigger Endpoint event", commitEvent)
        assertTrue(commitEvent is SegmentEvent.Endpoint)
    }

    @Test
    fun `transcription result computes RTF correctly`() {
        val res = TranscriptionResult(
            text = "Testing offline transcription",
            confidence = 0.98f,
            audioProcessedSec = 2.0f,
            latencyMs = 500L,
            isFinal = true
        )

        // RTF = (500ms / 1000s) / 2.0s = 0.5 / 2.0 = 0.25x
        assertEquals(0.25f, res.rtf, 0.01f)
    }
}
