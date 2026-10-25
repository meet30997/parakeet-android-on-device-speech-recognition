package com.example.offlinetranscription

import com.example.offlinetranscription.audio.*
import com.example.offlinetranscription.engine.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioPipelineTest {
    @Test fun quietSpeechAndLowEnergyOnsetArePreserved() {
        val vad = VadSegmenter()
        repeat(4) { vad.processChunk(FloatArray(512) { 0.001f }) }
        repeat(3) { vad.processChunk(FloatArray(512) { 0.01f }) }
        val tail = vad.flush()!!
        assertEquals(7 * 512, tail.samples.size)
        assertEquals(0.001f, tail.samples.first(), 0f)
        assertNull(vad.flush())
    }

    @Test fun previewWorkIsBoundedAndForcedBoundariesKeepContext() {
        val vad = VadSegmenter()
        var submitted = 0
        var previews = 0
        var final: SegmentEvent.Endpoint? = null
        repeat(250) {
            when (val event = vad.processChunk(FloatArray(512) { 0.02f }, it * 32L)) {
                is SegmentEvent.Partial -> { previews++; submitted += event.samples.size }
                is SegmentEvent.Endpoint -> { final = event; submitted += event.samples.size }
                null -> Unit
            }
        }
        assertEquals(2, previews)
        assertTrue(final!!.forced)
        assertTrue(submitted / 128000f < 1.4f)
        vad.processChunk(FloatArray(512) { 0.02f })
        val resumed = vad.flush()!!
        assertEquals(6144, resumed.overlapSamples)
        assertEquals(6144 + 512, resumed.samples.size)
    }

    @Test fun silenceEndpointIsWithinOneCaptureFrameOfItsTarget() {
        val vad = VadSegmenter()
        vad.processChunk(FloatArray(512) { 0.01f })
        var endpoint: SegmentEvent? = null
        var silenceFrames = 0
        while (endpoint !is SegmentEvent.Endpoint) {
            endpoint = vad.processChunk(FloatArray(512))
            silenceFrames++
        }
        assertEquals(416, silenceFrames * 32)
    }

    @Test fun mailboxCoalescesPreviewsAndDeliversEveryFinal() = runTest {
        val mailbox = InferenceMailbox()
        mailbox.preview(InferenceRequest(SegmentEvent.Partial(floatArrayOf(1f), 1), 0, 0))
        mailbox.preview(InferenceRequest(SegmentEvent.Partial(floatArrayOf(2f), 1), 0, 0))
        assertEquals(2f, mailbox.next()!!.event.samples[0], 0f)
        repeat(4) { mailbox.commit(InferenceRequest(SegmentEvent.Endpoint(floatArrayOf(it.toFloat()), it.toLong()), 0, 0)) }
        val fifth = async { mailbox.commit(InferenceRequest(SegmentEvent.Endpoint(floatArrayOf(4f), 4), 0, 0)) }
        runCurrent()
        assertFalse(fifth.isCompleted)
        repeat(5) { assertEquals(it.toFloat(), mailbox.next()!!.event.samples[0], 0f) }
        fifth.await()
        mailbox.finish()
        assertNull(mailbox.next())
    }

    @Test fun overlappingTextIsReconciledOnlyWhenRequested() {
        assertEquals("can do", withoutOverlap("ask what your country", "your country can do"))
        assertEquals("new phrase", withoutOverlap("different words", "new phrase"))
    }
}
