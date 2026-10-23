package com.example.offlinetranscription.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinetranscription.engine.LiveTranscriptionEngine
import com.example.offlinetranscription.engine.TranscriptTurn
import com.example.offlinetranscription.model.DownloadState
import com.example.offlinetranscription.model.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranscriptionUiState(
    val isModelReady: Boolean = false,
    val modelSizeMb: Float = 0f,
    val isRecording: Boolean = false,
    val rmsLevel: Float = 0f,
    val interimHypothesis: String = "",
    val completedTurns: List<TranscriptTurn> = emptyList(),
    val latestRtf: Float = 0f,
    val latestLatencyMs: Long = 0L,
    val threadCount: Int = 4,
    val downloadState: DownloadState = DownloadState.Idle,
    val isBenchmarkRunning: Boolean = false,
    val statusMessage: String? = null
)

class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TranscriptionViewModel"
    }

    val modelManager = ModelManager(application)
    val engine = LiveTranscriptionEngine()

    private val _threadCount = MutableStateFlow(4)
    private val _isBenchmarkRunning = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TranscriptionUiState> = combine(
        engine.isModelLoaded,
        engine.isRecording,
        engine.rmsLevel,
        engine.interimHypothesis,
        engine.completedTurns,
        engine.latestRtf,
        engine.latestLatencyMs,
        _threadCount,
        modelManager.downloadState,
        _isBenchmarkRunning,
        _statusMessage
    ) { params ->
        val isModelLoaded = params[0] as Boolean
        val isRecording = params[1] as Boolean
        val rmsLevel = params[2] as Float
        val interim = params[3] as String
        @Suppress("UNCHECKED_CAST")
        val turns = params[4] as List<TranscriptTurn>
        val rtf = params[5] as Float
        val latencyMs = params[6] as Long
        val threads = params[7] as Int
        val dlState = params[8] as DownloadState
        val isBench = params[9] as Boolean
        val statusMsg = params[10] as String?

        TranscriptionUiState(
            isModelReady = isModelLoaded,
            modelSizeMb = modelManager.getModelFileSizeMb(),
            isRecording = isRecording,
            rmsLevel = rmsLevel,
            interimHypothesis = interim,
            completedTurns = turns,
            latestRtf = rtf,
            latestLatencyMs = latencyMs,
            threadCount = threads,
            downloadState = dlState,
            isBenchmarkRunning = isBench,
            statusMessage = statusMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TranscriptionUiState()
    )

    init {
        checkAndLoadLocalModel()
    }

    fun checkAndLoadLocalModel() {
        if (modelManager.isModelReady()) {
            viewModelScope.launch {
                _statusMessage.value = "Initializing NeMo-Speech runtime..."
                val res = engine.loadModel(modelManager.modelFile.absolutePath, _threadCount.value)
                if (res.isSuccess) {
                    _statusMessage.value = null
                } else {
                    _statusMessage.value = "Failed to load model: ${res.exceptionOrNull()?.message}"
                }
            }
        }
    }

    fun setThreadCount(threads: Int) {
        if (_threadCount.value == threads) return
        _threadCount.value = threads
        if (modelManager.isModelReady()) {
            viewModelScope.launch {
                engine.loadModel(modelManager.modelFile.absolutePath, threads)
            }
        }
    }

    fun toggleRecording() {
        if (engine.isRecording.value) {
            engine.stopLiveTranscription()
        } else {
            engine.startLiveTranscription(viewModelScope)
        }
    }

    fun clearTranscripts() {
        engine.clearTranscripts()
    }

    fun startModelDownload() {
        viewModelScope.launch {
            val res = modelManager.downloadModel()
            if (res.isSuccess) {
                _statusMessage.value = "Model downloaded! Initializing..."
                engine.loadModel(res.getOrThrow().absolutePath, _threadCount.value)
                _statusMessage.value = null
            }
        }
    }

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            _statusMessage.value = "Importing model from device..."
            val res = modelManager.importFromUri(uri)
            if (res.isSuccess) {
                _statusMessage.value = "Model imported! Initializing..."
                engine.loadModel(res.getOrThrow().absolutePath, _threadCount.value)
                _statusMessage.value = null
            } else {
                _statusMessage.value = "Import failed: ${res.exceptionOrNull()?.message}"
            }
        }
    }

    fun runJfkBenchmark() {
        if (_isBenchmarkRunning.value || !modelManager.isModelReady()) return
        viewModelScope.launch {
            _isBenchmarkRunning.value = true
            _statusMessage.value = "Transcribing JFK benchmark audio on CPU..."
            try {
                val samplesRes = modelManager.loadJfkSampleAudio()
                if (samplesRes.isSuccess) {
                    val samples = samplesRes.getOrThrow()
                    val res = engine.transcribeAudioFile(samples, 16000)
                    if (res.isSuccess) {
                        val result = res.getOrThrow()
                        val turn = TranscriptTurn(
                            timestamp = "BENCHMARK",
                            text = result.text.trim(),
                            confidence = result.confidence,
                            durationSec = result.audioProcessedSec,
                            latencyMs = result.latencyMs
                        )
                        engine.clearTranscripts()
                        engine.addTurn(turn)
                        _statusMessage.value = String.format(
                            java.util.Locale.US,
                            "Benchmark Complete: RTF = %.2fx (processed in %d ms)",
                            result.rtf,
                            result.latencyMs
                        )
                    } else {
                        _statusMessage.value = "Inference failed: ${res.exceptionOrNull()?.message}"
                    }
                } else {
                    _statusMessage.value = "Failed to load sample JFK audio: ${samplesRes.exceptionOrNull()?.message}"
                }
            } finally {
                _isBenchmarkRunning.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.release()
    }
}
