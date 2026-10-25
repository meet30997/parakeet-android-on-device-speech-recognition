package com.example.offlinetranscription.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.sqrt

class AudioRecordSource(private val sampleRate: Int = 16000, private val chunkDurationMs: Int = 32) : AudioSource {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); task.run() }, "audio-capture")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    private val level = MutableStateFlow(0f)
    override val rmsLevel = level.asStateFlow()

    @SuppressLint("MissingPermission")
    override suspend fun start(onFrame: (AudioFrame) -> Boolean, onFinished: (Throwable?) -> Unit) {
        check(job == null) { "Recorder is already started" }
        val started = CompletableDeferred<Unit>()
        running = true
        job = scope.launch {
            var record: AudioRecord? = null
            var failure: Throwable? = null
            try {
                val chunkSamples = sampleRate * chunkDurationMs / 1000
                val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unsupported microphone format ($minimum)" }
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, chunkSamples * 8))
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                recorder = record
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                started.complete(Unit)
                val pcm = ShortArray(chunkSamples)
                var lastMeterAt = 0L
                while (running && isActive) {
                    val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) {
                        if (!running) break
                        error(if (read == AudioRecord.ERROR_DEAD_OBJECT) "Microphone disconnected. Start recording again."
                            else "Microphone read failed ($read)")
                    }
                    val samples = FloatArray(read)
                    var energy = 0.0
                    for (i in 0 until read) {
                        val sample = pcm[i] / 32768f
                        samples[i] = sample
                        energy += sample.toDouble() * sample
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMeterAt >= 64) {
                        level.value = (sqrt(energy / read).toFloat() * 3.5f).coerceIn(0f, 1f)
                        lastMeterAt = now
                    }
                    check(onFrame(AudioFrame(samples, now))) { "Audio processing fell behind. Recording stopped to prevent silent audio loss." }
                }
            } catch (e: Exception) {
                if (running) failure = e
                if (!started.isCompleted) started.completeExceptionally(e)
            } finally {
                running = false
                try { record?.stop() } catch (_: IllegalStateException) { }
                record?.release()
                recorder = null
                level.value = 0f
                onFinished(failure)
            }
        }
        try { started.await() } catch (e: Exception) { stop(); throw e }
    }

    override suspend fun stop() {
        running = false
        try { recorder?.stop() } catch (_: IllegalStateException) { }
        job?.join()
        job = null
    }

    override fun close() {
        check(job == null || job?.isCompleted == true) { "Stop recording before closing the source" }
        scope.cancel()
        dispatcher.close()
    }
}
