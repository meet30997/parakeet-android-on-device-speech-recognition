package com.example.offlinetranscription.nemo

import android.util.Log

class NemoSpeechJni {

    companion object {
        private const val TAG = "NemoSpeechJni"

        init {
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("ggml")
                System.loadLibrary("nemo_speech_asr")
                System.loadLibrary("nemo_speech_asr_c")
                System.loadLibrary("nemo_speech_jni")
                Log.i(TAG, "Successfully loaded native NeMo-Speech libraries")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load native NeMo-Speech libraries", t)
            }
        }
    }

    external fun nativeGetVersion(): String
    external fun nativeInit(modelPath: String, numThreads: Int): Long
    external fun nativeRecognizeChunk(
        handle: Long,
        audioSamples: FloatArray,
        sampleRate: Int,
        isFinal: Boolean
    ): TranscriptionResult
    external fun nativeDestroy(handle: Long)

    class RecognizerSession(
        private val jni: NemoSpeechJni,
        val handle: Long
    ) : AutoCloseable {
        private var closed = false

        fun recognize(samples: FloatArray, sampleRate: Int = 16000, isFinal: Boolean = false): TranscriptionResult {
            check(!closed) { "RecognizerSession has been closed" }
            return jni.nativeRecognizeChunk(handle, samples, sampleRate, isFinal)
        }

        override fun close() {
            if (!closed) {
                closed = true
                jni.nativeDestroy(handle)
            }
        }
    }

    fun createSession(modelPath: String, numThreads: Int = 0): RecognizerSession {
        val handle = nativeInit(modelPath, numThreads)
        if (handle == 0L) {
            throw IllegalStateException("Failed to initialize NeMo-Speech Recognizer from path: $modelPath")
        }
        return RecognizerSession(this, handle)
    }
}
