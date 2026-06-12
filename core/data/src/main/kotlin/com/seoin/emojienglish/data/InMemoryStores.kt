package com.seoin.emojienglish.data

import com.seoin.emojienglish.model.ComicScript
import com.seoin.emojienglish.model.QueueItem
import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.model.StepResultRecord
import com.seoin.emojienglish.model.TodayPlan
import com.seoin.emojienglish.model.TraceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide holder for the last LLM-generated [ComicScript] (Phase 3).
 * Null = not yet generated; falls back to the static sample in the step.
 * M5+ can persist this to DataStore/Room without any caller changes.
 */
@Singleton
class ComicScriptHolder @Inject constructor() {
    val script: MutableStateFlow<ComicScript?> = MutableStateFlow(null)
}

/**
 * In-memory data stores for the skeleton. Behaviour is the contract; storage is
 * a stand-in. M5 replaces these with Room (§10) — interfaces stay identical.
 *
 * Trace and progress are the single source of truth; per-unit progress shown on
 * the sets screen is derived from them, not stored separately (§10).
 */

@Singleton
class InMemoryTraceRepository @Inject constructor() : TraceRepository {
    private val events = MutableStateFlow<List<TraceEvent>>(emptyList())

    override fun add(event: TraceEvent) {
        events.value = events.value + event
    }

    override fun forStep(bookId: String, unitId: String, stepId: String): List<TraceEvent> =
        events.value.filter { it.bookId == bookId && it.unitId == unitId && it.stepId == stepId }

    override fun all(): Flow<List<TraceEvent>> = events.asStateFlow()
    override fun totalCount(): Flow<Int> = events.map { it.size }
}

@Singleton
class InMemoryProgressRepository @Inject constructor() : ProgressRepository {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<StepResult?>>()
    private val records = MutableStateFlow<List<StepResultRecord>>(emptyList())

    private fun key(sessionId: String, queueIndex: Int) = "$sessionId#$queueIndex"

    private fun flowFor(sessionId: String, queueIndex: Int): MutableStateFlow<StepResult?> =
        flows.getOrPut(key(sessionId, queueIndex)) { MutableStateFlow(null) }

    override fun save(sessionId: String, queueIndex: Int, item: QueueItem, result: StepResult) {
        flowFor(sessionId, queueIndex).value = result
        val record = StepResultRecord(
            sessionId = sessionId,
            queueIndex = queueIndex,
            bookId = item.bookId,
            unitId = item.unitId,
            stepId = item.stepId,
            occurrence = item.occurrence,
            result = result,
            completedAt = System.currentTimeMillis(),
        )
        // Replace any existing record for the same (sessionId, queueIndex).
        records.value = records.value.filterNot {
            it.sessionId == sessionId && it.queueIndex == queueIndex
        } + record
    }

    override fun result(sessionId: String, queueIndex: Int): StateFlow<StepResult?> =
        flowFor(sessionId, queueIndex).asStateFlow()

    override fun resultsForStep(bookId: String, unitId: String, stepId: String): List<StepResultRecord> =
        records.value.filter { it.bookId == bookId && it.unitId == unitId && it.stepId == stepId }

    override fun completedCount(): Flow<Int> =
        records.map { list -> list.count { it.result.completed } }
}

@Singleton
class InMemoryPlanRepository @Inject constructor() : PlanRepository {
    private val plans = MutableStateFlow<List<TodayPlan>>(emptyList())

    override fun today(): TodayPlan? = plans.value.lastOrNull()
    override fun all(): List<TodayPlan> = plans.value
    override fun save(plan: TodayPlan) {
        plans.value = plans.value.filterNot { it.planId == plan.planId } + plan
    }
}

@Singleton
class InMemoryMasterModeState @Inject constructor() : MasterModeState {
    private val _unlocked = MutableStateFlow(false)
    override val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _keepUnlocked = MutableStateFlow(false)
    override val keepUnlocked: StateFlow<Boolean> = _keepUnlocked.asStateFlow()

    // TODO(M7): store a hashed PIN in DataStore + auto-lock on inactivity (§11.1).
    private val defaultPin = "0000"

    override fun verifyPin(pin: String): Boolean = pin == defaultPin

    override fun enterMaster(keep: Boolean) {
        _unlocked.value = true
        if (keep) _keepUnlocked.value = true
    }

    override fun exitMaster() { _unlocked.value = false }

    override fun setKeep(keep: Boolean) { _keepUnlocked.value = keep }
}
