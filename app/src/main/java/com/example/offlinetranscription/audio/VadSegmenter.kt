package com.example.offlinetranscription.audio

import kotlin.math.max
import kotlin.math.sqrt

sealed interface SegmentEvent {
    val samples: FloatArray
    val utteranceId: Long
    val startedAtMs: Long
    val lastVoiceAtMs: Long
    val overlapSamples: Int

    data class Partial(
        override val samples: FloatArray,
        override val utteranceId: Long = 0,
        override val startedAtMs: Long = 0,
        override val lastVoiceAtMs: Long = 0,
        override val overlapSamples: Int = 0
    ) : SegmentEvent

    data class Endpoint(
        override val samples: FloatArray,
        override val utteranceId: Long = 0,
        override val startedAtMs: Long = 0,
        override val lastVoiceAtMs: Long = 0,
        override val overlapSamples: Int = 0,
        val forced: Boolean = false
    ) : SegmentEvent
}

/** Confined to the segmentation worker; production capture uses 32-ms frames. */
class VadSegmenter(
    private val sampleRate: Int = 16000,
    private val speechEnergyThreshold: Float = 0.003f,
    private val silenceCommitDurationMs: Long = 400,
    private val partialIntervalMs: Long = 1280,
    private val maxUtteranceDurationMs: Long = 8000,
    preRollMs: Int = 256,
    private val firstPartialMs: Int = 640,
    private val maxPartials: Int = 2,
    private val softLimitMs: Long = 6000,
    private val overlapMs: Int = 384
) {
    init {
        require(sampleRate > 0 && speechEnergyThreshold > 0)
        require(silenceCommitDurationMs > 0 && partialIntervalMs > 0)
        require(maxUtteranceDurationMs > 0 && preRollMs >= 0 && overlapMs >= 0)
        require(overlapMs < maxUtteranceDurationMs)
    }
    private val samples = FloatArray((sampleRate * maxUtteranceDurationMs / 1000).toInt() + sampleRate)
    private val preRoll = FloatArray(sampleRate * preRollMs / 1000)
    private var preRollCount = 0
    private var preRollPosition = 0
    private var size = 0
    private var active = false
    private var silenceSamples = 0L
    private var nextPartialSamples = sampleRate * firstPartialMs / 1000L
    private var partials = 0
    private var noiseFloor = speechEnergyThreshold / 4
    private var id = 0L
    private var startedAt = 0L
    private var lastVoiceAt = 0L
    private var leadingOverlap = 0

    fun processChunk(chunk: FloatArray, capturedAtMs: Long = 0, emitPartial: Boolean = true): SegmentEvent? {
        if (chunk.isEmpty()) return null
        var energy = 0.0
        for (s in chunk) energy += s.toDouble() * s
        val rms = sqrt(energy / chunk.size).toFloat()
        val threshold = if (active) max(speechEnergyThreshold * 0.6f, noiseFloor * 1.8f)
            else max(speechEnergyThreshold, noiseFloor * 3f)
        val voiced = rms >= threshold
        if (!active) {
            if (!voiced) {
                noiseFloor = noiseFloor * 0.98f + rms * 0.02f
                remember(chunk)
                return null
            }
            active = true
            id++
            startedAt = capturedAtMs - chunk.size * 1000L / sampleRate
            appendPreRoll()
        }
        append(chunk)
        if (voiced) {
            silenceSamples = 0
            lastVoiceAt = capturedAtMs
        } else {
            silenceSamples += chunk.size
        }
        val durationMs = size * 1000L / sampleRate
        val quietBoundary = durationMs >= softLimitMs && silenceSamples * 1000L / sampleRate >= 128
        val silent = silenceSamples * 1000L / sampleRate >= silenceCommitDurationMs
        if (silent || quietBoundary || durationMs >= maxUtteranceDurationMs) {
            val forced = !silent && !quietBoundary
            val event = endpoint(forced)
            if (forced) {
                val overlap = minOf(event.samples.size, sampleRate * overlapMs / 1000)
                append(event.samples.copyOfRange(event.samples.size - overlap, event.samples.size))
                leadingOverlap = overlap
                active = true
                id++
                startedAt = capturedAtMs - overlap * 1000L / sampleRate
                lastVoiceAt = capturedAtMs
            }
            return event
        }
        if (emitPartial && partials < maxPartials && size >= nextPartialSamples) {
            partials++
            nextPartialSamples = size + sampleRate * partialIntervalMs / 1000
            return SegmentEvent.Partial(samples.copyOf(size), id, startedAt, lastVoiceAt, leadingOverlap)
        }
        return null
    }

    fun flush(): SegmentEvent.Endpoint? = if (active && size > leadingOverlap) endpoint(false) else {
        clearUtterance()
        null
    }

    private fun endpoint(forced: Boolean): SegmentEvent.Endpoint {
        val event = SegmentEvent.Endpoint(samples.copyOf(size), id, startedAt, lastVoiceAt, leadingOverlap, forced)
        clearUtterance()
        return event
    }

    private fun append(chunk: FloatArray) {
        require(size + chunk.size <= samples.size) { "Audio frame exceeds segment capacity" }
        chunk.copyInto(samples, size)
        size += chunk.size
    }

    private fun remember(chunk: FloatArray) {
        if (preRoll.isEmpty()) return
        for (sample in chunk) {
            preRoll[preRollPosition] = sample
            preRollPosition = (preRollPosition + 1) % preRoll.size
            preRollCount = minOf(preRollCount + 1, preRoll.size)
        }
    }

    private fun appendPreRoll() {
        val start = (preRollPosition - preRollCount + preRoll.size) % max(1, preRoll.size)
        for (i in 0 until preRollCount) samples[size++] = preRoll[(start + i) % preRoll.size]
        preRollCount = 0
        preRollPosition = 0
    }

    private fun clearUtterance() {
        size = 0
        active = false
        silenceSamples = 0
        partials = 0
        nextPartialSamples = sampleRate * firstPartialMs / 1000L
        leadingOverlap = 0
    }

    fun reset() {
        clearUtterance()
        preRollCount = 0
        preRollPosition = 0
        noiseFloor = speechEnergyThreshold / 4
    }
}
