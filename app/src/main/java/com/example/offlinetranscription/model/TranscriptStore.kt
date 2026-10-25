package com.example.offlinetranscription.model

import android.util.AtomicFile
import com.example.offlinetranscription.engine.LiveTranscriptionEngine
import com.example.offlinetranscription.engine.TranscriptTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TranscriptStore(directory: File) {
    private val file = AtomicFile(File(directory, "transcripts.json"))

    suspend fun load(): List<TranscriptTurn> = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext emptyList()
        val rows = JSONArray(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        (maxOf(0, rows.length() - LiveTranscriptionEngine.MAX_TURNS) until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            TranscriptTurn(row.getString("id"), row.getString("timestamp"), row.getString("text"),
                Float.NaN, row.getDouble("duration").toFloat(), row.getLong("latency"))
        }
    }

    suspend fun save(turns: List<TranscriptTurn>) = withContext(Dispatchers.IO) {
        val rows = JSONArray()
        for (turn in turns.takeLast(LiveTranscriptionEngine.MAX_TURNS)) rows.put(JSONObject().apply {
            put("id", turn.id); put("timestamp", turn.timestamp); put("text", turn.text)
            put("duration", turn.durationSec.toDouble()); put("latency", turn.latencyMs)
        })
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(rows.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
}
