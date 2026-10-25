package com.example.offlinetranscription

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.offlinetranscription.audio.*
import com.example.offlinetranscription.engine.LiveTranscriptionEngine
import com.example.offlinetranscription.model.ModelManager
import com.example.offlinetranscription.model.installModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePipelineTest {
    private class ReplaySource(private val samples: FloatArray) : AudioSource {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var job: Job? = null
        override val rmsLevel = MutableStateFlow(0f)
        override suspend fun start(onFrame: (AudioFrame) -> Boolean, onFinished: (Throwable?) -> Unit) {
            job = scope.launch {
                try {
                    var at = 0
                    while (at < samples.size) {
                        val end = minOf(at + 512, samples.size)
                        delay((end - at) * 1000L / 16000)
                        check(onFrame(AudioFrame(samples.copyOfRange(at, end), SystemClock.elapsedRealtime())))
                        at = end
                    }
                    onFinished(null)
                } catch (e: CancellationException) { onFinished(null); throw e }
                catch (e: Exception) { onFinished(e) }
            }
        }
        override suspend fun stop() { job?.cancelAndJoin(); job = null }
        override fun close() { scope.cancel() }
    }

    @Test fun bundledSpeechSurvivesTheLiveSegmentationAndNativeJniPipeline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = ModelManager(context)
        val model = manager.localModel().getOrThrow()
        assumeNotNull(model)
        val samples = manager.loadJfkSampleAudio().getOrThrow()
        val threads = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("threads")?.toInt() ?: 4
        val engine = LiveTranscriptionEngine(audioSource = ReplaySource(samples))
        try {
            engine.loadModel(model!!.absolutePath, threads).getOrThrow()
            engine.startLiveTranscription().getOrThrow()
            withTimeout(90_000) {
                while (engine.state.value.isRecording || engine.state.value.isBusy) delay(100)
            }
            assertNull(engine.state.value.error)
            val transcript = engine.state.value.completedTurns.joinToString(" ") { it.text }
            val reference = "And so my fellow Americans ask not what your country can do for you ask what you can do for your country"
            val wer = wordErrorRate(reference, transcript)
            assertTrue("Unexpected live transcript: $transcript", wer <= 0.15)
            java.io.File(context.cacheDir, "native-replay-metrics.json").writeText(org.json.JSONObject().apply {
                put("wer", wer); put("transcript", transcript); put("threads", threads)
                put("firstPartialMs", engine.state.value.firstPartialMs)
                put("finalLatencyMs", engine.state.value.finalLatencyMs)
                put("queueAgeMs", engine.state.value.queueAgeMs)
                put("inferenceRtf", engine.state.value.latestRtf)
            }.toString())
            assertTrue(engine.state.value.finalLatencyMs > 0)
            assertTrue(engine.state.value.completedTurns.all { it.confidence.isNaN() })
        } finally { engine.shutdown() }
    }

    @Test fun nativeModelLoadsFromThePermanentPathAfterTransactionalInstallation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = ModelManager(context)
        val model = manager.localModel().getOrThrow()
        assumeNotNull(model)
        val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "model-install-").toFile()
        val candidate = java.io.File(directory, "candidate.gguf")
        val installed = java.io.File(directory, "installed.gguf")
        java.nio.file.Files.copy(model!!.toPath(), candidate.toPath())
        val engine = LiveTranscriptionEngine(audioSource = ReplaySource(FloatArray(0)))
        try {
            installModel(candidate, installed) { engine.loadModel(it.absolutePath, 4).getOrThrow() }
            val result = engine.transcribeAudioFile(manager.loadJfkSampleAudio().getOrThrow()).getOrThrow()
            assertTrue(result.text.contains("Americans", ignoreCase = true))
        } finally {
            engine.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test fun bundledAudioBenchmarkRecordsRepeatedNativeInference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = ModelManager(context)
        val model = manager.localModel().getOrThrow()
        assumeNotNull(model)
        val samples = manager.loadJfkSampleAudio().getOrThrow()
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val threads = arguments.getString("threads")?.toInt() ?: 4
        val runCount = arguments.getString("runs")?.toInt() ?: 5
        val engine = LiveTranscriptionEngine(audioSource = ReplaySource(FloatArray(0)))
        try {
            val startedAt = SystemClock.elapsedRealtime()
            engine.loadModel(model!!.absolutePath, threads).getOrThrow()
            val initializationMs = SystemClock.elapsedRealtime() - startedAt
            val runs = org.json.JSONArray()
            repeat(runCount) {
                val result = engine.transcribeAudioFile(samples).getOrThrow()
                assertTrue(result.text.contains("Americans", ignoreCase = true))
                assertTrue(result.confidence.isNaN())
                runs.put(org.json.JSONObject().apply {
                    put("inferenceMs", result.latencyMs)
                    put("inferenceRtf", result.rtf.toDouble())
                    put("transcript", result.text)
                })
            }
            java.io.File(context.cacheDir, "native-benchmark-metrics.json").writeText(org.json.JSONObject().apply {
                put("device", android.os.Build.MODEL)
                put("androidVersion", android.os.Build.VERSION.RELEASE)
                put("sdk", android.os.Build.VERSION.SDK_INT)
                put("threads", threads)
                put("audioSeconds", samples.size / 16000.0)
                put("modelFilename", ModelManager.MODEL_FILENAME)
                put("modelRevision", ModelManager.MODEL_REVISION)
                put("initializationIncludingPreviewWarmupMs", initializationMs)
                put("runs", runs)
            }.toString())
        } finally { engine.shutdown() }
    }

    private fun wordErrorRate(reference: String, actual: String): Double {
        fun words(s: String) = s.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ")
            .trim().split(Regex("\\s+"))
        val expected = words(reference)
        val found = words(actual)
        var previous = IntArray(found.size + 1) { it }
        for (i in expected.indices) {
            val next = IntArray(found.size + 1)
            next[0] = i + 1
            for (j in found.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                previous[j] + if (expected[i] == found[j]) 0 else 1)
            previous = next
        }
        return previous.last().toDouble() / expected.size
    }
}
