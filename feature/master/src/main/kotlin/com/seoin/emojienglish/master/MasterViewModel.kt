package com.seoin.emojienglish.master

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seoin.emojienglish.content.LessonRepository
import com.seoin.emojienglish.data.MasterModeState
import com.seoin.emojienglish.data.TraceRepository
import com.seoin.emojienglish.designsystem.ChipState
import com.seoin.emojienglish.designsystem.NavStepChip
import com.seoin.emojienglish.designsystem.formatClock
import com.seoin.emojienglish.designsystem.stepTypeEmoji
import com.seoin.emojienglish.designsystem.stepTypeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Central master dashboard (요구사항 ⑦). For now this is the **log only**: a
 * one-line-per-activity list. Clicking a line deep-links to that step's master
 * view. Later, JSON-input actions (book import, plan editor) attach here too.
 */
@HiltViewModel
class MasterViewModel @Inject constructor(
    private val masterMode: MasterModeState,
    traceRepo: TraceRepository,
    private val lessonRepo: LessonRepository,
) : ViewModel() {

    /** One log line. [stepIndex] is the step's position within its unit. */
    data class LogRow(
        val time: String,
        val title: String,
        val subtitle: String,
        val bookId: String,
        val unitId: String,
        val stepIndex: Int,
    )

    /**
     * The unit currently being observed (most recent activity), surfaced as the
     * SAME navigator bar as the study screen so master mode looks identical and is
     * easy to observe (피드백 #4). Chips deep-link into that unit's steps.
     */
    data class Observed(
        val title: String,
        val bookId: String,
        val unitId: String,
        val chips: List<NavStepChip>,
    )

    val unlocked: StateFlow<Boolean> = masterMode.unlocked

    val observed: StateFlow<Observed?> =
        traceRepo.all()
            .map { events ->
                val recent = events.maxByOrNull { it.at } ?: return@map null
                val unit = lessonRepo.unitOrNull(recent.bookId, recent.unitId) ?: return@map null
                Observed(
                    title = unit.title,
                    bookId = recent.bookId,
                    unitId = recent.unitId,
                    chips = unit.steps.map {
                        NavStepChip(stepTypeLabel(it.type), stepTypeEmoji(it.type), ChipState.DONE)
                    },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val log: StateFlow<List<LogRow>> =
        traceRepo.all()
            .map { events ->
                events.sortedByDescending { it.at }.map { e ->
                    val unit = lessonRepo.unitOrNull(e.bookId, e.unitId)
                    val stepDef = unit?.steps?.firstOrNull { it.id == e.stepId }
                    val index = unit?.steps?.indexOfFirst { it.id == e.stepId } ?: 0
                    LogRow(
                        time = formatClock(e.at),
                        title = "${stepTypeLabel(stepDef?.type ?: e.stepId)} · ${e.action}",
                        subtitle = "${unit?.title ?: e.unitId} · 회차 ${e.occurrence}",
                        bookId = e.bookId,
                        unitId = e.unitId,
                        stepIndex = index,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
