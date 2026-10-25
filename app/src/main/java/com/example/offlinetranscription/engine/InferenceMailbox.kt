package com.example.offlinetranscription.engine

import com.example.offlinetranscription.audio.SegmentEvent
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class InferenceRequest(val event: SegmentEvent, val revision: Long, val enqueuedAtMs: Long)

/** Final requests are lossless; previews occupy one replaceable slot. */
internal class InferenceMailbox {
    private val finals = Channel<InferenceRequest>(4)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var partial: InferenceRequest? = null
    private var finished = false
    private val finalized = AtomicLong(-1)
    val busy = AtomicBoolean(false)
    val finalCount = AtomicInteger(0)
    val canPreview: Boolean get() = !busy.get() && finalCount.get() == 0

    fun preview(request: InferenceRequest) {
        synchronized(lock) {
            if (!finished && request.event.utteranceId > finalized.get()) partial = request
        }
        wake.trySend(Unit)
    }

    suspend fun commit(request: InferenceRequest) {
        finalized.set(request.event.utteranceId)
        synchronized(lock) { partial = null }
        finalCount.incrementAndGet()
        finals.send(request)
        wake.trySend(Unit)
    }

    fun isCurrentPreview(id: Long): Boolean = id > finalized.get()

    suspend fun next(): InferenceRequest? {
        while (true) {
            finals.tryReceive().getOrNull()?.let {
                finalCount.decrementAndGet()
                return it
            }
            synchronized(lock) {
                partial?.let { partial = null; return it }
                if (finished) return null
            }
            wake.receive()
        }
    }

    fun finish() {
        synchronized(lock) { finished = true; partial = null }
        wake.trySend(Unit)
    }
}
