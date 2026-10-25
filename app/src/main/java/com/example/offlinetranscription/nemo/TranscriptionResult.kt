package com.example.offlinetranscription.nemo

/**
 * Result returned from native NeMo-Speech recognition.
 * Matches JNI signature: (Ljava/lang/String;FFJZ)V. NaN confidence means unavailable.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float,
    val audioProcessedSec: Float,
    val latencyMs: Long = 0L,
    val isFinal: Boolean = false
) {
    val rtf: Float
        get() = if (audioProcessedSec > 0.001f) (latencyMs / 1000f) / audioProcessedSec else 0f
}
