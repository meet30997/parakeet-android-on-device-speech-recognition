package com.example.offlinetranscription.engine

import android.util.Log
import com.example.offlinetranscription.audio.AudioRecordSource
import com.example.offlinetranscription.audio.SegmentEvent
import com.example.offlinetranscription.audio.VadSegmenter
import com.example.offlinetranscription.nemo.NemoSpeechJni
import com.example.offlinetranscription.nemo.TranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TranscriptTurn(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val text: String,
    val confidence: Float,
    val durationSec: Float,
    val latencyMs: Long
)

class LiveTranscriptionEngine(
    private val jni: NemoSpeechJni = NemoSpeechJni()
) {
    companion object {
        private const val TAG = "LiveTranscriptionEngine"
    }

    private var session: NemoSpeechJni.RecognizerSession? = null
    private val audioSource = AudioRecordSource()
    private val vadSegmenter = VadSegmenter()

    private var engineScope: CoroutineScope? = null
    private var processingJob: Job? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _interimHypothesis = MutableStateFlow("")
    val interimHypothesis: StateFlow<String> = _interimHypothesis.asStateFlow()

    private val _completedTurns = MutableStateFlow<List<TranscriptTurn>>(emptyList())
    val completedTurns: StateFlow<List<TranscriptTurn>> = _completedTurns.asStateFlow()

    private val _latestRtf = MutableStateFlow(0f)
    val latestRtf: StateFlow<Float> = _latestRtf.asStateFlow()

    private val _latestLatencyMs = MutableStateFlow(0L)
    val latestLatencyMs: StateFlow<Long> = _latestLatencyMs.asStateFlow()

    val isRecording: StateFlow<Boolean> = audioSource.isRecording
    val rmsLevel: StateFlow<Float> = audioSource.rmsLevel

    suspend fun loadModel(modelPath: String, numThreads: Int = 0): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            session?.close()
            session = null
            _isModelLoaded.value = false

            Log.i(TAG, "Loading model from: $modelPath with $numThreads threads")
            session = jni.createSession(modelPath, numThreads)
            _isModelLoaded.value = true
            Log.i(TAG, "Model loaded successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            _isModelLoaded.value = false
            Result.failure(e)
        }
    }

    fun startLiveTranscription(scope: CoroutineScope) {
        val currentSession = session ?: run {
            Log.w(TAG, "Cannot start transcription: Model is not loaded")
            return
        }

        if (audioSource.isRecording.value) return

        engineScope = scope
        vadSegmenter.reset()
        _interimHypothesis.value = ""

        audioSource.start(scope)

        processingJob = scope.launch(Dispatchers.Default) {
            val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            audioSource.audioChunks.collect { chunk ->
                if (!isActive) return@collect

                val event = vadSegmenter.processChunk(chunk) ?: return@collect

                when (event) {
                    is SegmentEvent.Partial -> {
                        try {
                            val res = currentSession.recognize(
                                samples = event.samples,
                                sampleRate = 16000,
                                isFinal = false
                            )
                            if (res.text.isNotBlank()) {
                                _interimHypothesis.value = res.text.trim()
                                _latestRtf.value = res.rtf
                                _latestLatencyMs.value = res.latencyMs
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Partial recognition error", e)
                        }
                    }
                    is SegmentEvent.Endpoint -> {
                        try {
                            val res = currentSession.recognize(
                                samples = event.samples,
                                sampleRate = 16000,
                                isFinal = true
                            )
                            val finalText = res.text.trim()
                            if (finalText.isNotBlank()) {
                                val turn = TranscriptTurn(
                                    timestamp = timeFormatter.format(Date()),
                                    text = finalText,
                                    confidence = res.confidence,
                                    durationSec = res.audioProcessedSec,
                                    latencyMs = res.latencyMs
                                )
                                _completedTurns.update { it + turn }
                                _latestRtf.value = res.rtf
                                _latestLatencyMs.value = res.latencyMs
                            }
                            _interimHypothesis.value = ""
                        } catch (e: Exception) {
                            Log.e(TAG, "Endpoint recognition error", e)
                        }
                    }
                }
            }
        }
    }

    fun stopLiveTranscription() {
        audioSource.stop()
        processingJob?.cancel()
        processingJob = null
        vadSegmenter.reset()
        _interimHypothesis.value = ""
    }

    suspend fun transcribeAudioFile(samples: FloatArray, sampleRate: Int = 16000): Result<TranscriptionResult> =
        withContext(Dispatchers.Default) {
            val currentSession = session ?: return@withContext Result.failure(
                IllegalStateException("Model is not loaded")
            )
            try {
                val res = currentSession.recognize(samples, sampleRate, isFinal = true)
                _latestRtf.value = res.rtf
                _latestLatencyMs.value = res.latencyMs
                Result.success(res)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun addTurn(turn: TranscriptTurn) {
        _completedTurns.update { it + turn }
    }

    fun clearTranscripts() {
        _completedTurns.value = emptyList()
        _interimHypothesis.value = ""
    }

    fun release() {
        stopLiveTranscription()
        session?.close()
        session = null
        _isModelLoaded.value = false
    }
}
