package com.example.offlinetranscription.engine

import android.os.SystemClock
import com.example.offlinetranscription.audio.AudioFrame
import com.example.offlinetranscription.audio.AudioRecordSource
import com.example.offlinetranscription.audio.AudioSource
import com.example.offlinetranscription.audio.SegmentEvent
import com.example.offlinetranscription.audio.VadSegmenter
import com.example.offlinetranscription.nemo.NemoSpeechJni
import com.example.offlinetranscription.nemo.RecognizerFactory
import com.example.offlinetranscription.nemo.SpeechRecognizer
import com.example.offlinetranscription.nemo.TranscriptionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class TranscriptTurn(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val text: String,
    val confidence: Float = Float.NaN,
    val durationSec: Float,
    val latencyMs: Long
)

data class EngineState(
    val isModelLoaded: Boolean = false,
    val isRecording: Boolean = false,
    val isBusy: Boolean = false,
    val interimHypothesis: String = "",
    val completedTurns: List<TranscriptTurn> = emptyList(),
    val latestRtf: Float = 0f,
    val inferenceMs: Long = 0,
    val firstPartialMs: Long = 0,
    val finalLatencyMs: Long = 0,
    val queueAgeMs: Long = 0,
    val pendingFinals: Int = 0,
    val error: String? = null
)

class LiveTranscriptionEngine(
    private val factory: RecognizerFactory = RecognizerFactory { path, threads -> NemoSpeechJni().createSession(path, threads) },
    private val audioSource: AudioSource = AudioRecordSource(),
    private val nativeDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "speech-inference") }.asCoroutineDispatcher(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val segmenterFactory: () -> VadSegmenter = { VadSegmenter() },
    private val onTurnsChanged: suspend (List<TranscriptTurn>) -> Unit = {}
) {
    private sealed interface Input {
        data class Frame(val audio: AudioFrame) : Input
        data class Reset(val revision: Long) : Input
    }
    private class LiveRun {
        val frames = Channel<Input>(64)
        val mailbox = InferenceMailbox()
        val captureQueueAgeMs = AtomicLong(0)
        lateinit var segmentJob: Job
        lateinit var inferJob: Job
    }
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val control = Mutex()
    private val content = Mutex()
    private val revision = AtomicLong(0)
    private var session: SpeechRecognizer? = null
    private var live: LiveRun? = null
    private var released = false
    private val mutableState = MutableStateFlow(EngineState())
    val state = mutableState.asStateFlow()
    val rmsLevel = audioSource.rmsLevel

    suspend fun loadModel(modelPath: String, numThreads: Int = 4): Result<Unit> = control.withLock {
        check(!released) { "Engine is released" }
        stopLocked()
        mutableState.update { it.copy(isBusy = true, error = null) }
        try {
            withContext(NonCancellable + nativeDispatcher) {
                val replacement = factory.create(modelPath, numThreads)
                val previous = session
                session = replacement
                previous?.close()
            }
            mutableState.update { it.copy(isModelLoaded = true) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(error = "Model initialization failed: ${e.message}") }
            Result.failure(e)
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    suspend fun startLiveTranscription(): Result<Unit> = control.withLock {
        check(!released) { "Engine is released" }
        if (live != null && mutableState.value.isRecording) return@withLock Result.success(Unit)
        if (live != null) stopLocked()
        if (session == null) return@withLock Result.failure(IllegalStateException("Load a model first"))
        val run = LiveRun()
        live = run
        mutableState.update { it.copy(isRecording = true, interimHypothesis = "", error = null) }
        run.inferJob = scope.launch(nativeDispatcher) {
            var previousText = ""
            var previousForced = false
            var partialId = -1L
            while (true) {
                val request = run.mailbox.next() ?: break
                val event = request.event
                if (request.revision != revision.get() ||
                    (event is SegmentEvent.Partial && !run.mailbox.isCurrentPreview(event.utteranceId))) continue
                run.mailbox.busy.set(true)
                try {
                    val queueAge = (nowMs() - request.enqueuedAtMs).coerceAtLeast(0)
                    val result = session!!.recognize(event.samples, 16000, event is SegmentEvent.Endpoint)
                    content.withLock updateResult@ {
                        if (request.revision != revision.get()) return@updateResult
                        mutableState.update { it.copy(latestRtf = result.rtf, inferenceMs = result.latencyMs,
                            queueAgeMs = maxOf(queueAge, run.captureQueueAgeMs.getAndSet(0)), pendingFinals = run.mailbox.finalCount.get()) }
                        when (event) {
                            is SegmentEvent.Partial -> if (result.text.isNotBlank() && run.mailbox.isCurrentPreview(event.utteranceId)) {
                                mutableState.update { it.copy(interimHypothesis = result.text.trim(),
                                    firstPartialMs = if (partialId != event.utteranceId) (nowMs() - event.startedAtMs).coerceAtLeast(0) else it.firstPartialMs) }
                                partialId = event.utteranceId
                            }
                            is SegmentEvent.Endpoint -> {
                                val text = if (event.overlapSamples > 0 && previousForced)
                                    withoutOverlap(previousText, result.text) else result.text.trim()
                                previousText = result.text.trim()
                                previousForced = event.forced
                                if (text.isNotBlank()) appendTurn(TranscriptTurn(
                                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                                    text = text, confidence = result.confidence,
                                    durationSec = (event.samples.size - event.overlapSamples) / 16000f, latencyMs = result.latencyMs))
                                mutableState.update { it.copy(interimHypothesis = "", finalLatencyMs =
                                    (nowMs() - event.lastVoiceAtMs).coerceAtLeast(0)) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    mutableState.update { it.copy(error = "Recognition failed: ${e.message}") }
                } finally {
                    run.mailbox.busy.set(false)
                }
            }
        }
        run.segmentJob = scope.launch {
            val vad = segmenterFactory()
            var contentRevision = revision.get()
            try {
                for (input in run.frames) {
                    when (input) {
                        is Input.Reset -> { vad.reset(); contentRevision = input.revision }
                        is Input.Frame -> {
                            val age = (nowMs() - input.audio.capturedAtMs).coerceAtLeast(0)
                            run.captureQueueAgeMs.accumulateAndGet(age, ::maxOf)
                            val event = vad.processChunk(input.audio.samples, input.audio.capturedAtMs)
                            if (event != null) submit(run, event, contentRevision)
                        }
                    }
                }
                vad.flush()?.let { submit(run, it, contentRevision) }
            } catch (e: Exception) {
                mutableState.update { it.copy(error = "Audio processing failed: ${e.message}") }
                scope.launch { stopLiveTranscription() }
            } finally {
                run.mailbox.finish()
            }
        }
        try {
            audioSource.start({ run.frames.trySend(Input.Frame(it)).isSuccess }, { failure ->
                failure?.let { e -> mutableState.update { it.copy(error = e.message ?: "Microphone failed") } }
                run.frames.close()
                scope.launch { control.withLock { if (live === run) stopLocked() } }
            })
            Result.success(Unit)
        } catch (e: CancellationException) {
            stopLocked()
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(error = "Microphone failed: ${e.message}") }
            stopLocked()
            Result.failure(e)
        }
    }

    private suspend fun submit(run: LiveRun, event: SegmentEvent, contentRevision: Long) {
        val request = InferenceRequest(event, contentRevision, nowMs())
        when (event) {
            is SegmentEvent.Partial -> run.mailbox.preview(request)
            is SegmentEvent.Endpoint -> run.mailbox.commit(request)
        }
    }

    suspend fun stopLiveTranscription() = control.withLock { stopLocked() }

    private suspend fun stopLocked() = withContext(NonCancellable) {
        val run = live ?: return@withContext
        mutableState.update { it.copy(isRecording = false, isBusy = true) }
        audioSource.stop()
        run.frames.close()
        run.segmentJob.join()
        run.inferJob.join()
        live = null
        mutableState.update { it.copy(isBusy = false, interimHypothesis = "", pendingFinals = 0) }
    }

    suspend fun transcribeAudioFile(samples: FloatArray, sampleRate: Int = 16000): Result<TranscriptionResult> = control.withLock {
        if (live != null) return@withLock Result.failure(IllegalStateException("Stop live recording before running a benchmark"))
        val recognizer = session ?: return@withLock Result.failure(IllegalStateException("Model is not loaded"))
        mutableState.update { it.copy(isBusy = true, error = null) }
        try {
            val result = withContext(NonCancellable + nativeDispatcher) { recognizer.recognize(samples, sampleRate, true) }
            mutableState.update { it.copy(latestRtf = result.rtf, inferenceMs = result.latencyMs) }
            Result.success(result)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        finally { mutableState.update { it.copy(isBusy = false) } }
    }

    private suspend fun appendTurn(turn: TranscriptTurn) {
        mutableState.update { it.copy(completedTurns = (it.completedTurns + turn).takeLast(MAX_TURNS)) }
        persistTurns()
    }

    private suspend fun persistTurns() = withContext(NonCancellable) {
        try { onTurnsChanged(mutableState.value.completedTurns) }
        catch (e: Exception) { mutableState.update { it.copy(error = "History could not be saved: ${e.message}") } }
    }

    suspend fun addTurn(turn: TranscriptTurn) = control.withLock { content.withLock { appendTurn(turn) } }
    suspend fun restoreTurns(turns: List<TranscriptTurn>) = control.withLock {
        mutableState.update { it.copy(completedTurns = turns.takeLast(MAX_TURNS)) }
    }

    suspend fun clearTranscripts() = control.withLock {
        val next = content.withLock {
            val next = revision.incrementAndGet()
            mutableState.update { it.copy(completedTurns = emptyList(), interimHypothesis = "") }
            persistTurns()
            next
        }
        live?.frames?.send(Input.Reset(next))
    }

    suspend fun shutdown() = control.withLock {
        if (released) return@withLock
        released = true
        stopLocked()
        withContext(NonCancellable + nativeDispatcher) { session?.close(); session = null }
        audioSource.close()
        mutableState.update { it.copy(isModelLoaded = false) }
        scope.cancel()
        (nativeDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    fun release() { scope.launch { shutdown() } }

    companion object { const val MAX_TURNS = 500 }
}
