package com.example.offlinetranscription.nemo

class NemoSpeechJni {

    companion object {
        private val loadFailure: LinkageError? = try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("ggml")
                System.loadLibrary("nemo_speech_asr")
                System.loadLibrary("nemo_speech_asr_c")
                System.loadLibrary("nemo_speech_jni")
                null
            } catch (e: LinkageError) { e }
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
        private val handle: Long
    ) : SpeechRecognizer {
        private var closed = false

        @Synchronized
        override fun recognize(samples: FloatArray, sampleRate: Int, isFinal: Boolean): TranscriptionResult {
            check(!closed) { "RecognizerSession has been closed" }
            return jni.nativeRecognizeChunk(handle, samples, sampleRate, isFinal)
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                closed = true
                jni.nativeDestroy(handle)
            }
        }
    }

    fun createSession(modelPath: String, numThreads: Int = 0): RecognizerSession {
        loadFailure?.let { throw IllegalStateException("Native speech runtime is unavailable on this device", it) }
        val handle = nativeInit(modelPath, numThreads)
        if (handle == 0L) {
            throw IllegalStateException("Failed to initialize NeMo-Speech Recognizer from path: $modelPath")
        }
        val session = RecognizerSession(this, handle)
        try {
            // Build the two preview graph shapes before accepting microphone audio.
            session.recognize(FloatArray(10240), 16000, false)
            session.recognize(FloatArray(30720), 16000, false)
            return session
        } catch (e: Exception) {
            session.close()
            throw e
        }
    }
}
