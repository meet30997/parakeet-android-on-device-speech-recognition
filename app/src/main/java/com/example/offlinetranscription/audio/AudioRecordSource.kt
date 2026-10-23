package com.example.offlinetranscription.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioRecordSource(
    private val sampleRate: Int = 16000,
    private val chunkDurationMs: Int = 160
) {
    companion object {
        private const val TAG = "AudioRecordSource"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val _audioChunks = MutableSharedFlow<FloatArray>(extraBufferCapacity = 64)
    val audioChunks: SharedFlow<FloatArray> = _audioChunks.asSharedFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (_isRecording.value) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val chunkSizeSamples = (sampleRate * chunkDurationMs) / 1000
        val bufferSize = maxOf(minBufferSize, chunkSizeSamples * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        record.startRecording()
        audioRecord = record
        _isRecording.value = true

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(chunkSizeSamples)
            val floatBuffer = FloatArray(chunkSizeSamples)

            try {
                while (isActive && _isRecording.value) {
                    val read = record.read(shortBuffer, 0, chunkSizeSamples)
                    if (read > 0) {
                        var sumSquare = 0.0
                        for (i in 0 until read) {
                            val f = shortBuffer[i] / 32768.0f
                            floatBuffer[i] = f
                            sumSquare += (f * f)
                        }

                        // Calculate RMS [0.0 - 1.0]
                        val rms = sqrt(sumSquare / read).toFloat()
                        // Amplify slightly for visual impact on quiet mics
                        val normalizedRms = (rms * 3.5f).coerceIn(0f, 1f)
                        _rmsLevel.value = normalizedRms

                        // Copy valid audio chunk
                        val chunk = floatBuffer.copyOf(read)
                        _audioChunks.emit(chunk)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during audio recording loop", e)
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (ignored: Exception) {}
                audioRecord = null
                _isRecording.value = false
                _rmsLevel.value = 0f
            }
        }
    }

    fun stop() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
    }
}
