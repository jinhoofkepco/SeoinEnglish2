package com.seoin.emojienglish.model

/**
 * A single learning-trace record (§4.3). One row per meaningful student action.
 * Written by steps via `session.trace(...)`, read back by the master dashboard.
 */
data class TraceEvent(
    val studentId: String,
    val bookId: String,
    val unitId: String,
    val stepId: String,
    val occurrence: Int,        // repeat round (1-based) — distinguishes "same step ×3"
    val sessionId: String,      // groups a study session
    val action: String,         // step_visit / answer_submit / sentence_play / ...
    val detailJson: String,
    val at: Long,               // epoch millis
)

/**
 * A model-level snapshot of one step's stored result, independent of the Room
 * entity in core:data. Kept here so [StepTraceSnapshot] (consumed by step-api's
 * MasterView) does not drag a data-layer dependency into the step contract.
 */
data class StepResultRecord(
    val sessionId: String,
    val queueIndex: Int,
    val bookId: String,
    val unitId: String,
    val stepId: String,
    val occurrence: Int,
    val result: StepResult,
    val completedAt: Long,      // epoch millis
)

/**
 * Read-only bundle handed to a Step's MasterView (§11.2). Everything the master
 * screen draws comes from here — never from live student-screen state (§0.4).
 */
data class StepTraceSnapshot(
    val events: List<TraceEvent> = emptyList(),
    val results: List<StepResultRecord> = emptyList(),
)
