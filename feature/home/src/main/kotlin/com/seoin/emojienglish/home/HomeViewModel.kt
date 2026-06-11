package com.seoin.emojienglish.home

import androidx.lifecycle.ViewModel
import com.seoin.emojienglish.content.LessonRepository
import com.seoin.emojienglish.data.MasterModeState
import com.seoin.emojienglish.data.PlanRepository
import com.seoin.emojienglish.model.Book
import com.seoin.emojienglish.model.TodayPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val lessonRepo: LessonRepository,
    private val planRepo: PlanRepository,
    masterMode: MasterModeState,
) : ViewModel() {

    init {
        lessonRepo.refresh()
    }

    /** Master mode toggles the browse overlay (§11.5) without importing master. */
    val masterUnlocked: StateFlow<Boolean> = masterMode.unlocked

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
