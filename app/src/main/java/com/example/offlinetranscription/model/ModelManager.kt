package com.example.offlinetranscription.model

import android.content.Context
import android.net.Uri
import com.example.offlinetranscription.audio.WavDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val progress: Float) : DownloadState
    data object Completed : DownloadState
    data class Error(val message: String) : DownloadState
}

class ModelManager(private val context: Context) {
    companion object {
        const val MODEL_FILENAME = "parakeet-tdt-0.6b-v3.q8_0.gguf"
        const val MODEL_REVISION = "541d1f99c6b0c3cd0b11a95167540bb8edefd82b"
        const val MODEL_URL = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/$MODEL_REVISION/$MODEL_FILENAME"
        const val MODEL_SHA256 = "e3880d0aaaaf2c308ea2c35016b2b895c423eb3fda924c1b463d1c19b7f4d32e"
        const val MODEL_BYTES = 713975456L
    }
    private val modelsDir = File(context.noBackupFilesDir, "models")
    val modelFile = File(modelsDir, MODEL_FILENAME)
    private val mutation = Mutex()
    private val download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = download.asStateFlow()
    private val sizeMb = MutableStateFlow(0f)
    val modelSizeMb = sizeMb.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).followRedirects(true).build()

    suspend fun localModel(): Result<File?> = withContext(Dispatchers.IO) {
        mutation.withLock {
            try {
                check(modelsDir.isDirectory || modelsDir.mkdirs()) { "Cannot create model directory" }
                recoverModel(modelFile)
                val legacy = File(context.filesDir, "models/$MODEL_FILENAME")
                if (!modelFile.exists() && legacy.exists()) {
                    GgufValidator.validate(legacy)
                    Files.move(legacy.toPath(), modelFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                if (modelFile.exists()) GgufValidator.validate(modelFile)
                refreshSize()
                Result.success(modelFile.takeIf { it.exists() })
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    suspend fun downloadModel(activate: suspend (File) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        mutation.withLock {
            val part = File(modelsDir, "$MODEL_FILENAME.part")
            try {
                check(modelsDir.isDirectory || modelsDir.mkdirs())
                download.value = DownloadState.Downloading(part.length(), MODEL_BYTES, part.length().toFloat() / MODEL_BYTES)
                if (part.length() > MODEL_BYTES) check(part.delete())
                if (part.length() < MODEL_BYTES) fetch(part)
                require(part.length() == MODEL_BYTES) { "Model download is incomplete; retry to resume" }
                val digest = MessageDigest.getInstance("SHA-256")
                part.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (hash != MODEL_SHA256) {
                    part.delete()
                    error("Model checksum mismatch. Retry the download.")
                }
                GgufValidator.validate(part)
                installModel(part, modelFile, activate)
                refreshSize()
                download.value = DownloadState.Completed
                Result.success(modelFile)
            } catch (e: CancellationException) {
                download.value = DownloadState.Idle
                throw e
            } catch (e: Exception) {
                download.value = DownloadState.Error(e.message ?: "Model download failed")
                Result.failure(e)
            }
        }
    }

    private suspend fun fetch(part: File) = coroutineScope {
        val offset = part.length()
        val request = Request.Builder().url(MODEL_URL).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}: ${response.message}" }
                val append = offset > 0 && response.code == 206
                if (response.code == 206) require(response.header("Content-Range")?.startsWith("bytes $offset-") == true) {
                    "Server returned an invalid resume range"
                }
                val body = requireNotNull(response.body) { "Empty model download" }
                var total = if (append) offset else 0L
                var lastProgressAt = 0L
                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(total + read <= MODEL_BYTES) { "Unexpected model size" }
                            output.write(buffer, 0, read)
                            total += read
                            val now = System.nanoTime() / 1_000_000
                            if (now - lastProgressAt >= 100 || total == MODEL_BYTES) {
                                download.value = DownloadState.Downloading(total, MODEL_BYTES, total.toFloat() / MODEL_BYTES)
                                lastProgressAt = now
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        } finally { cancellation.cancel() }
    }

    suspend fun importFromUri(uri: Uri, activate: suspend (File) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        mutation.withLock {
            check(modelsDir.isDirectory || modelsDir.mkdirs())
            val candidate = File.createTempFile("model-import-", ".gguf", modelsDir)
            try {
                requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open model" }.use { input ->
                    FileOutputStream(candidate).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                GgufValidator.validate(candidate)
                installModel(candidate, modelFile, activate)
                refreshSize()
                download.value = DownloadState.Completed
                Result.success(modelFile)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            finally { candidate.delete() }
        }
    }

    suspend fun loadJfkSampleAudio(): Result<FloatArray> = withContext(Dispatchers.IO) {
        try { Result.success(context.assets.open("sample_jfk.wav").use(WavDecoder::mono16k)) }
        catch (e: Exception) { Result.failure(e) }
    }

    private fun refreshSize() { sizeMb.value = modelFile.length() / (1024f * 1024f) }
}
