package com.example.offlinetranscription.nemo

interface SpeechRecognizer : AutoCloseable {
    fun recognize(samples: FloatArray, sampleRate: Int = 16000, isFinal: Boolean = false): TranscriptionResult
}

fun interface RecognizerFactory {
    fun create(modelPath: String, numThreads: Int): SpeechRecognizer
}
