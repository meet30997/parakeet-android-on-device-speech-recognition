package com.example.offlinetranscription

import com.example.offlinetranscription.audio.*
import com.example.offlinetranscription.engine.*
import com.example.offlinetranscription.nemo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class EngineLifecycleTest {
    private class Source : AudioSource {
        override val rmsLevel = MutableStateFlow(0f)
        lateinit var accept: (AudioFrame) -> Boolean
        lateinit var finished: (Throwable?) -> Unit
        var active = false
        var starts = 0
        override suspend fun start(onFrame: (AudioFrame) -> Boolean, onFinished: (Throwable?) -> Unit) {
            check(!active)
            starts++
            active = true; accept = onFrame; finished = onFinished
        }
        override suspend fun stop() { if (active) { active = false; finished(null) } }
        override fun close() { check(!active) }
    }
    private class Recognizer(private val blockFirst: Boolean = false) : SpeechRecognizer {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (blockFirst) 1 else 0)
        @Volatile var recognizing = false
        @Volatile var closed = false
        val lengths = java.util.Collections.synchronizedList(mutableListOf<Int>())
        override fun recognize(samples: FloatArray, sampleRate: Int, isFinal: Boolean): TranscriptionResult {
            check(!closed)
            recognizing = true
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test inference was never released" }
            lengths.add(samples.size)
            recognizing = false
            return TranscriptionResult("words retained", Float.NaN, samples.size / sampleRate.toFloat(), 10, isFinal)
        }
        override fun close() { check(!recognizing); check(!closed); closed = true }
    }

    @Test fun stopFlushesAnUtteranceBeforeItsFirstPreview() = runTest {
        val source = Source()
        val recognizer = Recognizer()
        val engine = LiveTranscriptionEngine(RecognizerFactory { _, _ -> recognizer }, source,
            workerDispatcher = StandardTestDispatcher(testScheduler), nowMs = { testScheduler.currentTime })
        try {
            assertTrue(engine.loadModel("valid").isSuccess)
            engine.startLiveTranscription().getOrThrow()
            assertTrue(source.accept(AudioFrame(FloatArray(512) { 0.01f }, 32)))
            engine.stopLiveTranscription()
            assertEquals(listOf(512), recognizer.lengths)
            assertEquals("words retained", engine.state.value.completedTurns.single().text)
            assertFalse(engine.state.value.isRecording)
        } finally { engine.shutdown() }
    }

    @Test fun reloadWaitsForInferenceAndDrainsFinalAudioBeforeClosing() = runTest {
        val source = Source()
        val first = Recognizer(true)
        val second = Recognizer()
        var creates = 0
        val engine = LiveTranscriptionEngine(RecognizerFactory { _, _ -> if (creates++ == 0) first else second },
            source, workerDispatcher = StandardTestDispatcher(testScheduler), nowMs = { testScheduler.currentTime })
        try {
            engine.loadModel("first").getOrThrow()
            engine.startLiveTranscription().getOrThrow()
            repeat(20) { source.accept(AudioFrame(FloatArray(512) { 0.02f }, (it + 1) * 32L)); runCurrent() }
            assertTrue(first.entered.await(2, TimeUnit.SECONDS))
            // Segmentation continues while the native worker is blocked.
            repeat(40) { assertTrue(source.accept(AudioFrame(FloatArray(512) { 0.02f }, 672L + it * 32))); runCurrent() }
            val reload = async { engine.loadModel("second") }
            runCurrent()
            assertFalse(first.closed)
            assertFalse(reload.isCompleted)
            first.release.countDown()
            assertTrue(reload.await().isSuccess)
            assertTrue(first.closed)
            assertTrue(engine.state.value.completedTurns.isNotEmpty())
            assertFalse(engine.state.value.isRecording)
            assertFalse(second.closed)
        } finally { first.release.countDown(); engine.shutdown() }
    }

    @Test fun clearSuppressesAnInFlightResultAndResetsPendingSpeech() = runTest {
        val source = Source()
        val recognizer = Recognizer(true)
        val engine = LiveTranscriptionEngine(RecognizerFactory { _, _ -> recognizer }, source,
            workerDispatcher = StandardTestDispatcher(testScheduler), nowMs = { testScheduler.currentTime })
        try {
            engine.loadModel("model")
            engine.startLiveTranscription()
            repeat(20) { source.accept(AudioFrame(FloatArray(512) { 0.02f }, it * 32L)); runCurrent() }
            assertTrue(recognizer.entered.await(2, TimeUnit.SECONDS))
            engine.clearTranscripts()
            runCurrent()
            recognizer.release.countDown()
            engine.stopLiveTranscription()
            assertTrue(engine.state.value.completedTurns.isEmpty())
            assertEquals("", engine.state.value.interimHypothesis)
        } finally { recognizer.release.countDown(); engine.shutdown() }
    }

    @Test fun failedReplacementRetainsAUsableRecognizer() = runTest {
        val recognizer = Recognizer()
        val engine = LiveTranscriptionEngine(RecognizerFactory { path, _ ->
            if (path == "bad") error("invalid model") else recognizer
        }, Source(), workerDispatcher = StandardTestDispatcher(testScheduler), nowMs = { testScheduler.currentTime })
        try {
            engine.loadModel("good").getOrThrow()
            assertTrue(engine.loadModel("bad").isFailure)
            assertFalse(recognizer.closed)
            assertTrue(engine.state.value.isModelLoaded)
            assertTrue(engine.transcribeAudioFile(FloatArray(512)).isSuccess)
        } finally { engine.shutdown() }
    }
}
