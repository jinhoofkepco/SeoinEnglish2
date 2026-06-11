package com.seoin.emojienglish.model

/**
 * The core abstraction (§6). The Player never knows whether a queue came from a
 * single unit or from "today's plan" — it only ever consumes a [PlayQueue].
 */
data class QueueItem(
    val bookId: String,
    val unitId: String,
    val stepId: String,
    val occurrence: Int,        // 1-based repeat round
    val totalOccurrences: Int,  // for "Shadowing 2/3" style labels
)

data class PlayQueue(
    val sessionId: String,      // UUID — the trace/result grouping key
    val sourceLabel: String,    // "At a Restaurant" or "오늘의 할 공부"
    val items: List<QueueItem>,
) {
    val size: Int get() = items.size
    fun itemAt(index: Int): QueueItem? = items.getOrNull(index)
}
