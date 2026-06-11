package com.seoin.emojienglish.content

import com.seoin.emojienglish.model.Book
import com.seoin.emojienglish.model.LessonUnit

/**
 * Loads, parses and caches book/unit JSON (§2). Content is **never** in the DB —
 * it lives as files (assets + filesDir) and is parsed here (§10).
 *
 * Skeleton note: accessors are synchronous and lazily backed by an in-memory
 * cache. M2 will move loading off the main thread and add SAF/`filesDir`
 * imported books on top of the bundled assets.
 */
interface LessonRepository {
    /** (Re)load all books from sources. Safe to call repeatedly. */
    fun refresh()

    fun books(): List<Book>
    fun book(bookId: String): Book?
    fun unitOrNull(bookId: String, unitId: String): LessonUnit?

    /** @throws IllegalArgumentException if the unit is not found. */
    fun unit(bookId: String, unitId: String): LessonUnit =
        unitOrNull(bookId, unitId)
            ?: throw IllegalArgumentException("Unit not found: $bookId/$unitId")
}
