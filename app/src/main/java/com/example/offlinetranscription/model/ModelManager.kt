package com.example.offlinetranscription.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    object Idle : DownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val progress: Float) : DownloadState
    object Completed : DownloadState
    data class Error(val message: String) : DownloadState
}

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        const val MODEL_FILENAME = "parakeet-tdt-0.6b-v3.q8_0.gguf"
        const val MODEL_URL = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/main/parakeet-tdt-0.6b-v3.q8_0.gguf"
    }

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile: File = File(modelsDir, MODEL_FILENAME)

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isModelReady(): Boolean {
        // Model file is around 680 MB; check for existence and valid size > 100 MB
        return modelFile.exists() && modelFile.length() > 100 * 1024 * 1024
    }

    fun getModelFileSizeMb(): Float {
        return if (modelFile.exists()) modelFile.length() / (1024f * 1024f) else 0f
    }

    suspend fun downloadModel(url: String = MODEL_URL): Result<File> = withContext(Dispatchers.IO) {
        val tempFile = File(modelsDir, "$MODEL_FILENAME.tmp")
        try {
            _downloadState.value = DownloadState.Downloading(0L, 0L, 0f)
            Log.i(TAG, "Starting download from $url")

            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = "HTTP error: ${response.code} ${response.message}"
                _downloadState.value = DownloadState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val body = response.body ?: run {
                val errorMsg = "Empty response body"
                _downloadState.value = DownloadState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val totalBytes = body.contentLength()
            var bytesReadTotal = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        bytesReadTotal += bytes
                        val progress = if (totalBytes > 0) bytesReadTotal.toFloat() / totalBytes else 0f
                        _downloadState.value = DownloadState.Downloading(bytesReadTotal, totalBytes, progress)
                    }
                    output.flush()
                }
            }

            if (tempFile.exists()) {
                if (modelFile.exists()) modelFile.delete()
                tempFile.renameTo(modelFile)
            }

            _downloadState.value = DownloadState.Completed
            Log.i(TAG, "Download completed successfully: ${modelFile.absolutePath}")
            Result.success(modelFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            if (tempFile.exists()) tempFile.delete()
            _downloadState.value = DownloadState.Error(e.localizedMessage ?: "Unknown error")
            Result.failure(e)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(modelsDir, "$MODEL_FILENAME.import.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Cannot open input stream for URI: $uri"))

            if (modelFile.exists()) modelFile.delete()
            tempFile.renameTo(modelFile)
            Result.success(modelFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadJfkSampleAudio(): Result<FloatArray> = withContext(Dispatchers.IO) {
        try {
            context.assets.open("sample_jfk.wav").use { inputStream ->
                readWavMono16kFloat(inputStream)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readWavMono16kFloat(inputStream: InputStream): Result<FloatArray> {
        val bytes = inputStream.readBytes()
        if (bytes.size < 44) {
            return Result.failure(IllegalArgumentException("WAV file too short"))
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Find "data" chunk
        var offset = 12
        var dataSize = 0
        var dataOffset = -1

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4)
            val chunkSize = buffer.getInt(offset + 4)
            if (chunkId == "data") {
                dataOffset = offset + 8
                dataSize = chunkSize
                break
            }
            offset += 8 + chunkSize
        }

        if (dataOffset < 0 || dataOffset + dataSize > bytes.size) {
            // Fallback: standard 44-byte WAV header
            dataOffset = 44
            dataSize = bytes.size - 44
        }

        val shortCount = dataSize / 2
        val floatSamples = FloatArray(shortCount)
        buffer.position(dataOffset)

        for (i in 0 until shortCount) {
            val s = buffer.short
            floatSamples[i] = s / 32768.0f
        }

        return Result.success(floatSamples)
    }
}
