package com.example.offlinetranscription.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavDecoder {
    fun mono16k(input: InputStream): FloatArray {
        val bytes = input.readBytes()
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a RIFF/WAVE file" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riffEnd = 8L + (b.getInt(4).toLong() and 0xffffffffL)
        require(riffEnd in 12..bytes.size.toLong()) { "Truncated WAV container" }
        var offset = 12L
        var formatFound = false
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= riffEnd) {
            val at = offset.toInt()
            val size = b.getInt(at + 4).toLong() and 0xffffffffL
            val end = offset + 8 + size
            require(end <= riffEnd) { "Truncated WAV chunk" }
            when (String(bytes, at, 4, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format chunk" }
                    require(b.getShort(at + 8).toInt() == 1 && b.getShort(at + 10).toInt() == 1 &&
                        b.getInt(at + 12) == 16000 && b.getInt(at + 16) == 32000 &&
                        b.getShort(at + 20).toInt() == 2 && b.getShort(at + 22).toInt() == 16) {
                        "Expected PCM16 mono audio at 16000 Hz"
                    }
                    formatFound = true
                }
                "data" -> { dataOffset = at + 8; dataSize = size.toInt() }
            }
            offset = end + (size and 1)
        }
        require(formatFound && dataOffset >= 0 && dataSize > 0 && dataSize % 2 == 0) { "Missing or invalid WAV audio data" }
        b.position(dataOffset)
        return FloatArray(dataSize / 2) { b.short / 32768f }
    }
}
