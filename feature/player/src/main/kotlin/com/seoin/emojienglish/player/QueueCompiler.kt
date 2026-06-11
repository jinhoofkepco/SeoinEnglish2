package com.seoin.emojienglish.player

import com.seoin.emojienglish.content.LessonRepository
import com.seoin.emojienglish.model.Book
import com.seoin.emojienglish.model.LessonUnit
import com.seoin.emojienglish.model.PlayQueue
import com.seoin.emojienglish.model.QueueItem
import com.seoin.emojienglish.model.TodayPlan
import java.util.UUID

/**
 * Unifies unit-study and "today's plan" into a single [PlayQueue] (§6). The
 * Player consumes only the queue and never learns where it came from.
 */
object QueueCompiler {

    /** Unit study: each step once, in order. */
    fun fromUnit(book: Book, unit: LessonUnit): PlayQueue =
        PlayQueue(
            sessionId = UUID.randomUUID().toString(),
            sourceLabel = unit.title,
            items = unit.steps.map {
                QueueItem(book.bookId, unit.unitId, it.id, occurrence = 1, totalOccurrences = 1)
            },
        )

    /** Today's plan: expand each item by `repeat`, honouring selector/exclude (§5.3). */
    fun fromPlan(plan: TodayPlan, repo: LessonRepository): PlayQueue {
        val items = plan.items.flatMap { item ->
            val unit = repo.unitOrNull(item.bookId, item.unitId) ?: return@flatMap emptyList()
            val stepIds: List<String> = item.stepId?.let { listOf(it) }
                ?: unit.steps.map { it.id }.filterNot { it in item.exclude }
            stepIds.flatMap { sid ->
                (1..item.repeat).map { occ ->
                    QueueItem(item.bookId, item.unitId, sid, occ, item.repeat)
                }
            }
        }
        return PlayQueue(UUID.randomUUID().toString(), plan.title, items)
    }
}
