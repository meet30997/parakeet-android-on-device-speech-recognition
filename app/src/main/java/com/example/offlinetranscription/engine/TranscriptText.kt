package com.example.offlinetranscription.engine

/** Reconciles repeated context only for explicitly overlapping audio segments. */
internal fun withoutOverlap(previous: String, current: String): String {
    val before = previous.trim().split(Regex("\\s+"))
    val after = current.trim().split(Regex("\\s+"))
    fun normalized(word: String) = word.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
    for (count in minOf(before.size, after.size, 16) downTo 1) {
        if ((0 until count).all { normalized(before[before.size - count + it]) == normalized(after[it]) } &&
            normalized(after[count - 1]).isNotEmpty()) return after.drop(count).joinToString(" ")
    }
    return current.trim()
}
