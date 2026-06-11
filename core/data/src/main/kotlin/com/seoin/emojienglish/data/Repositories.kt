package com.seoin.emojienglish.data

import com.seoin.emojienglish.model.QueueItem
import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.model.StepResultRecord
import com.seoin.emojienglish.model.TodayPlan
import com.seoin.emojienglish.model.TraceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Data-layer contracts (§10). Implementations in this skeleton are in-memory;
 * M5 swaps in Room-backed versions with the same signatures — no caller changes.
 */

interface TraceRepository {
    fun add(event: TraceEvent)

    /** All traces for a step across every occurrence (master timeline). */
    fun forStep(bookId: String, unitId: String, stepId: String): List<TraceEvent>

    fun all(): Flow<List<TraceEvent>>
    fun totalCount(): Flow<Int>
}

interface ProgressRepository {
    /** Persist a step result. Key = (sessionId, queueIndex) per §6. */
    fun save(sessionId: String, queueIndex: Int, item: QueueItem, result: StepResult)

    /** Hot result for one occurrence. Drives CTA enablement + re-entry restore. */
    fun result(sessionId: String, queueIndex: Int): StateFlow<StepResult?>

    /** Stored results for a step across occurrences (master view). */
    fun resultsForStep(bookId: String, unitId: String, stepId: String): List<StepResultRecord>

    /** Completed-step count — one of the master dashboard metrics (§11.2). */
    fun completedCount(): Flow<Int>
}

interface PlanRepository {
    /** Today's plan, if one is set. */
    fun today(): TodayPlan?
    fun all(): List<TodayPlan>
    fun save(plan: TodayPlan)
}

/**
 * Master-mode state (§11.1). Lives in core:data so browse/study screens can
 * react to it **without importing feature:master**.
 *
 * Two flags:
 *  - [unlocked]: are we currently *viewing* as master (step screens show the
 *    activity view, navigator allows all steps).
 *  - [keepUnlocked]: ephemeral "마스터 모드 유지" — once set (with the PIN), the
 *    user can flip master↔student with no PIN, for observing side-by-side.
 *    In-memory only, so it resets on app restart by design.
 */
interface MasterModeState {
    val unlocked: StateFlow<Boolean>
    val keepUnlocked: StateFlow<Boolean>

    /** Check the PIN without changing state. */
    fun verifyPin(pin: String): Boolean

    /** Turn master viewing on. [keep] sets the PIN-free toggle for this run. */
    fun enterMaster(keep: Boolean)

    /** Turn master viewing off. Leaves [keepUnlocked] as-is. */
    fun exitMaster()

    fun setKeep(keep: Boolean)
}
