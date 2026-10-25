package com.example.offlinetranscription.model

import java.io.File
import java.io.RandomAccessFile

object GgufValidator {
    data class Metadata(val name: String, val sampleRate: Int, val cacheStreaming: Boolean)

    fun validate(file: File): Metadata = RandomAccessFile(file, "r").use { f ->
        fun u32(): Long = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
        fun u64(): Long = java.lang.Long.reverseBytes(f.readLong()).also { require(it >= 0) { "GGUF integer overflow" } }
        fun text(): String {
            val count = u64()
            require(count <= 1024 * 1024 && f.filePointer + count <= f.length()) { "Invalid GGUF string" }
            return ByteArray(count.toInt()).also { f.readFully(it) }.toString(Charsets.UTF_8)
        }
        fun value(type: Int, depth: Int = 0): Any? {
            require(depth <= 2 && f.filePointer <= 16 * 1024 * 1024) { "GGUF metadata is too large" }
            return when (type) {
                0, 1 -> f.readByte()
                2, 3 -> java.lang.Short.reverseBytes(f.readShort())
                4, 5, 6 -> u32()
                7 -> f.readBoolean()
                8 -> text()
                9 -> { val element = u32().toInt(); val count = u64(); require(count <= 1_000_000)
                    repeat(count.toInt()) { value(element, depth + 1) }; null }
                10, 11, 12 -> java.lang.Long.reverseBytes(f.readLong())
                else -> error("Unknown GGUF metadata type $type")
            }
        }
        require(u32() == 0x46554747L && u32() == 3L) { "Expected a GGUF v3 model" }
        val tensors = u64()
        val fields = u64()
        require(tensors in 1..100_000 && fields in 1..100_000) { "Invalid GGUF table sizes" }
        val metadata = mutableMapOf<String, Any?>()
        repeat(fields.toInt()) { val key = text(); metadata[key] = value(u32().toInt()) }
        require(metadata["general.architecture"] == "asr") { "Expected a NeMo-Speech ASR GGUF model" }
        val rate = (metadata["asr.preprocessor.sample_rate"] as? Long)?.toInt() ?: 16000
        require(rate == 16000) { "This app requires a 16000-Hz ASR model" }
        val alignment = (metadata["general.alignment"] as? Long) ?: 32L
        require(alignment in 1..4096 && alignment and (alignment - 1) == 0L) { "Invalid GGUF alignment" }
        val ranges = ArrayList<Pair<Long, Long>>()
        // Block geometry for the supported F32/F16/BF16 and standard GGML quantizations.
        val types = mapOf(0 to (1 to 4), 1 to (1 to 2), 2 to (32 to 18), 3 to (32 to 20),
            6 to (32 to 22), 7 to (32 to 24), 8 to (32 to 34), 9 to (32 to 36),
            10 to (256 to 84), 11 to (256 to 110), 12 to (256 to 144),
            13 to (256 to 176), 14 to (256 to 210), 15 to (256 to 292), 30 to (1 to 2))
        repeat(tensors.toInt()) {
            text()
            val dimensions = u32()
            require(dimensions in 1..4) { "Invalid tensor dimensions" }
            val shape = LongArray(dimensions.toInt()) { u64().also { require(it > 0) } }
            val type = u32().toInt()
            val offset = u64()
            val (block, bytes) = types[type] ?: error("Unsupported model quantization ($type)")
            require(shape[0] % block == 0L && offset % alignment == 0L) { "Invalid tensor layout" }
            val elements = shape.fold(1L) { n, dim -> Math.multiplyExact(n, dim) }
            ranges.add(offset to Math.multiplyExact(elements / block, bytes.toLong()))
        }
        val start = Math.multiplyExact((f.filePointer + alignment - 1) / alignment, alignment)
        for ((offset, bytes) in ranges) {
            require(offset <= file.length() - start && bytes <= file.length() - start - offset) { "Truncated GGUF tensor data" }
        }
        Metadata(metadata["general.name"] as? String ?: file.name, rate, metadata["asr.encoder.cache_supported"] == true)
    }
}
