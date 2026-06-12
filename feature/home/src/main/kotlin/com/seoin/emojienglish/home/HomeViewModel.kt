package com.seoin.emojienglish.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seoin.emojienglish.content.LessonRepository
import com.seoin.emojienglish.data.MasterModeState
import com.seoin.emojienglish.data.PlanRepository
import com.seoin.emojienglish.data.TraceRepository
import com.seoin.emojienglish.model.Book
import com.seoin.emojienglish.model.TodayPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val lessonRepo: LessonRepository,
    private val planRepo: PlanRepository,
    traceRepo: TraceRepository,
    masterMode: MasterModeState,
) : ViewModel() {

    init {
        lessonRepo.refresh()
    }

    /** Master mode toggles the browse overlay (§11.5) without importing master. */
    val masterUnlocked: StateFlow<Boolean> = masterMode.unlocked

    /**
     * Units the child has finished — keyed "bookId/unitId". Derived from the
     * "session_end" trace the player writes on completion, so the "오늘의 할일"
     * list can show a "다했어요" mark (피드백 #3).
     */
    val completedUnits: StateFlow<Set<String>> =
        traceRepo.all()
            .map { events ->
                events.filter { it.action == "session_end" }
                    .map { "${it.bookId}/${it.unitId}" }
                    .toSet()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun books(): List<Book> = lessonRepo.books()
    fun book(bookId: String): Book? = lessonRepo.book(bookId)
    fun todayPlan(): TodayPlan? = planRepo.today()

    /** Demo helper: the first unit available, so the skeleton has a runnable path. */
    fun firstUnitRef(): Pair<String, String>? {
        val book = lessonRepo.books().firstOrNull() ?: return null
        val unit = book.units.firstOrNull() ?: return null
        return book.bookId to unit.unitId
    }
}
