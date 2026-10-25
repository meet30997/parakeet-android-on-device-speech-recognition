package com.example.offlinetranscription.audio

import kotlinx.coroutines.flow.StateFlow

data class AudioFrame(val samples: FloatArray, val capturedAtMs: Long)

interface AudioSource : AutoCloseable {
    val rmsLevel: StateFlow<Float>
    suspend fun start(onFrame: (AudioFrame) -> Boolean, onFinished: (Throwable?) -> Unit)
    suspend fun stop()
}
