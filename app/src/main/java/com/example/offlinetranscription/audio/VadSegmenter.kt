package com.example.offlinetranscription.audio

import kotlin.math.sqrt

sealed interface SegmentEvent {
    data class Partial(val samples: FloatArray) : SegmentEvent
    data class Endpoint(val samples: FloatArray) : SegmentEvent
}

class VadSegmenter(
    private val sampleRate: Int = 16000,
    private val speechEnergyThreshold: Float = 0.012f,
    private val silenceCommitDurationMs: Long = 550L,
    private val partialIntervalMs: Long = 850L,
    private val maxUtteranceDurationMs: Long = 6000L
) {
    private val accumulatedSamples = ArrayList<Float>()
    private var isSpeechActive = false
    private var silenceAccumulatedMs = 0L
    private var lastPartialEmittedSampleCount = 0

    fun processChunk(chunk: FloatArray): SegmentEvent? {
        val chunkDurationMs = (chunk.size * 1000L) / sampleRate

        // Compute RMS energy of this chunk
        var sumSquares = 0.0
        for (sample in chunk) {
            sumSquares += (sample * sample)
        }
        val chunkRms = sqrt(sumSquares / chunk.size).toFloat()
        val isVoiceInChunk = chunkRms >= speechEnergyThreshold

        if (isVoiceInChunk) {
            if (!isSpeechActive) {
                isSpeechActive = true
            }
            silenceAccumulatedMs = 0L
            for (s in chunk) {
                accumulatedSamples.add(s)
            }
        } else {
            if (isSpeechActive) {
                silenceAccumulatedMs += chunkDurationMs
                for (s in chunk) {
                    accumulatedSamples.add(s)
                }
            }
        }

        if (!isSpeechActive || accumulatedSamples.isEmpty()) {
            return null
        }

        val totalDurationMs = (accumulatedSamples.size * 1000L) / sampleRate

        // Check if silence endpoint or max utterance duration triggered commit
        if (silenceAccumulatedMs >= silenceCommitDurationMs || totalDurationMs >= maxUtteranceDurationMs) {
            val endpointSamples = accumulatedSamples.toFloatArray()
            reset()
            return SegmentEvent.Endpoint(endpointSamples)
        }

        // Check if rolling partial interval reached
        val samplesSinceLastPartial = accumulatedSamples.size - lastPartialEmittedSampleCount
        val msSinceLastPartial = (samplesSinceLastPartial * 1000L) / sampleRate
        if (msSinceLastPartial >= partialIntervalMs && totalDurationMs >= 500L) {
            lastPartialEmittedSampleCount = accumulatedSamples.size
            return SegmentEvent.Partial(accumulatedSamples.toFloatArray())
        }

        return null
    }

    fun reset() {
        accumulatedSamples.clear()
        isSpeechActive = false
        silenceAccumulatedMs = 0L
        lastPartialEmittedSampleCount = 0
    }
}
