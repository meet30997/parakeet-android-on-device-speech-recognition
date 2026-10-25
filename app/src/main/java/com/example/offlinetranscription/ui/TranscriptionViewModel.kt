package com.example.offlinetranscription.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinetranscription.engine.LiveTranscriptionEngine
import com.example.offlinetranscription.engine.TranscriptTurn
import com.example.offlinetranscription.model.DownloadState
import com.example.offlinetranscription.model.ModelManager
import com.example.offlinetranscription.model.TranscriptStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

data class TranscriptionUiState(
    val isModelReady: Boolean = false,
    val modelSizeMb: Float = 0f,
    val isRecording: Boolean = false,
    val isBusy: Boolean = false,
    val interimHypothesis: String = "",
    val completedTurns: List<TranscriptTurn> = emptyList(),
    val latestRtf: Float = 0f,
    val latestLatencyMs: Long = 0,
    val firstPartialMs: Long = 0,
    val finalLatencyMs: Long = 0,
    val queueAgeMs: Long = 0,
    val threadCount: Int = 4,
    val downloadState: DownloadState = DownloadState.Idle,
    val isBenchmarkRunning: Boolean = false,
    val statusMessage: String? = null
)

private data class Controls(val threads: Int = 4, val working: Boolean = false, val benchmark: Boolean = false, val status: String? = null)

class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {
    private val modelManager = ModelManager(application)
    private val history = TranscriptStore(application.filesDir)
    private val engine = LiveTranscriptionEngine(onTurnsChanged = history::save)
    private val controls = MutableStateFlow(Controls())
    private val operations = Mutex()
    val rmsLevel = engine.rmsLevel

    val uiState: StateFlow<TranscriptionUiState> = combine(
        engine.state, controls, modelManager.downloadState, modelManager.modelSizeMb
    ) { engine, control, download, size ->
        TranscriptionUiState(isModelReady = engine.isModelLoaded, modelSizeMb = size,
            isRecording = engine.isRecording, isBusy = engine.isBusy || control.working,
            interimHypothesis = engine.interimHypothesis, completedTurns = engine.completedTurns,
            latestRtf = engine.latestRtf, latestLatencyMs = engine.inferenceMs,
            firstPartialMs = engine.firstPartialMs, finalLatencyMs = engine.finalLatencyMs, queueAgeMs = engine.queueAgeMs,
            threadCount = control.threads, downloadState = download, isBenchmarkRunning = control.benchmark,
            statusMessage = engine.error ?: control.status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TranscriptionUiState(isBusy = true))

    init {
        operation("Loading saved transcripts and model...") {
            var historyWarning: String? = null
            try { engine.restoreTurns(history.load()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { historyWarning = "Saved history could not be loaded: ${e.message}" }
            // Validation/migration and model metadata I/O run off the UI thread.
            val file = modelManager.localModel().getOrThrow()
            if (file != null) engine.loadModel(file.absolutePath, controls.value.threads).getOrThrow()
            controls.update { it.copy(status = historyWarning) }
        }
    }

    private fun operation(message: String, work: suspend () -> Unit) {
        viewModelScope.launch {
            operations.withLock {
                controls.update { it.copy(working = true, status = message) }
                try { work() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { controls.update { it.copy(status = e.message ?: "Operation failed") } }
                finally { controls.update { it.copy(working = false, benchmark = false) } }
            }
        }
    }

    fun setThreadCount(threads: Int) {
        if (threads !in listOf(2, 4, 6, 8) || controls.value.threads == threads || engine.state.value.isRecording) return
        if (!engine.state.value.isModelLoaded) {
            controls.update { it.copy(threads = threads) }
            return
        }
        operation("Changing CPU thread count...") {
            engine.loadModel(modelManager.modelFile.absolutePath, threads).getOrThrow()
            controls.update { it.copy(threads = threads, status = null) }
        }
    }

    fun toggleRecording() {
        if (controls.value.working || engine.state.value.isBusy) return
        viewModelScope.launch {
            if (engine.state.value.isRecording) engine.stopLiveTranscription()
            else engine.startLiveTranscription()
        }
    }

    fun stopRecording() { viewModelScope.launch { engine.stopLiveTranscription() } }
    fun clearTranscripts() { viewModelScope.launch { engine.clearTranscripts() } }

    fun startModelDownload() = operation("Downloading and checking model...") {
        modelManager.downloadModel { engine.loadModel(it.absolutePath, controls.value.threads).getOrThrow() }.getOrThrow()
        controls.update { it.copy(status = null) }
    }

    fun importLocalModel(uri: Uri) = operation("Checking and importing model...") {
        modelManager.importFromUri(uri) { engine.loadModel(it.absolutePath, controls.value.threads).getOrThrow() }.getOrThrow()
        controls.update { it.copy(status = null) }
    }

    fun runJfkBenchmark() {
        if (engine.state.value.isRecording || controls.value.working) return
        operation("Transcribing JFK benchmark audio on CPU...") {
            controls.update { it.copy(benchmark = true) }
            val samples = modelManager.loadJfkSampleAudio().getOrThrow()
            val result = engine.transcribeAudioFile(samples).getOrThrow()
            engine.addTurn(TranscriptTurn(timestamp = "BENCHMARK", text = result.text.trim(),
                confidence = result.confidence, durationSec = result.audioProcessedSec, latencyMs = result.latencyMs))
            controls.update { it.copy(status = String.format(Locale.US,
                "Benchmark Complete: inference RTF = %.2fx (processed in %d ms)", result.rtf, result.latencyMs)) }
        }
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
