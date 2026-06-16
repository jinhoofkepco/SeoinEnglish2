package com.seoin.emojienglish.content

import android.content.Context
import com.seoin.emojienglish.model.Book
import com.seoin.emojienglish.model.BookManifest
import com.seoin.emojienglish.model.LessonUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads books from **two sources, merged**:
 *  1. bundled `assets/books/<id>/...` (read-only, shipped in the APK), and
 *  2. **`filesDir/books/<id>/...`** — runtime content written by the master
 *     authoring tool (or dropped in manually). Same layout, same validator.
 *
 * Merge rule: filesDir wins. A filesDir book with a new `bookId` is added; one
 * that reuses an existing `bookId` has its units **merged into** that book
 * (filesDir units override/extend by `unitId`). So the authoring tool can either
 * start a fresh book or add a unit to a bundled one, and it shows up immediately
 * after [refresh].
 */
@Singleton
class AssetLessonRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LessonRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val lock = Any()
    @Volatile private var cache: List<Book>? = null

    private fun ensureLoaded(): List<Book> {
        cache?.let { return it }
        return synchronized(lock) {
            cache ?: load().also { cache = it }
        }
    }

    override fun refresh() {
        synchronized(lock) { cache = null }
        ensureLoaded()
    }

    override fun books(): List<Book> = ensureLoaded()

    override fun book(bookId: String): Book? =
        ensureLoaded().firstOrNull { it.bookId == bookId }

    override fun unitOrNull(bookId: String, unitId: String): LessonUnit? =
        book(bookId)?.units?.firstOrNull { it.unitId == unitId }

    private fun load(): List<Book> = mergeBooks(loadAssetBooks(), loadFileBooks())

    // ---------------------------------------------------------------- assets

    private fun loadAssetBooks(): List<Book> {
        val dirs = context.assets.list("books")?.toList().orEmpty()
        return dirs.mapNotNull { dir ->
            parseBook { rel -> runCatching { readAsset("books/$dir/$rel") }.getOrNull() }
        }
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    // --------------------------------------------------------------- filesDir

    private fun loadFileBooks(): List<Book> {
        val root = File(context.filesDir, "books")
        val dirs = root.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        return dirs.mapNotNull { bookDir ->
            parseBook { rel -> File(bookDir, rel).takeIf { it.isFile }?.readText() }
        }
    }

    // --------------------------------------------------------------- parsing

    /** Parse one book given a [read] that resolves a path **relative to the book dir**. */
    private fun parseBook(read: (rel: String) -> String?): Book? {
        val manifest = read("book.json")
            ?.let { runCatching { json.decodeFromString<BookManifest>(it) }.getOrNull() }
            ?: return null
        val units = manifest.units.mapNotNull { ref ->
            read(ref.file)
                ?.let { runCatching { json.decodeFromString<LessonUnit>(it) }.getOrNull() }
                ?.also { unit ->
                    val issues = ContentValidator.validate(unit)
                    if (issues.isNotEmpty()) {
                        android.util.Log.w(
                            "ContentValidator",
                            "Unit ${unit.unitId}: " + issues.joinToString { it.message },
                        )
                    }
                }
        }
        return Book(
            bookId = manifest.bookId,
            title = manifest.title,
            level = manifest.level,
            coverEmoji = manifest.coverEmoji,
            units = units,
        )
    }

    /** Asset books first, then filesDir books merged on top (filesDir wins). */
    private fun mergeBooks(assetBooks: List<Book>, fileBooks: List<Book>): List<Book> {
        val byId = LinkedHashMap<String, Book>()
        assetBooks.forEach { byId[it.bookId] = it }
        fileBooks.forEach { fb ->
            val existing = byId[fb.bookId]
            byId[fb.bookId] = if (existing == null) {
                fb
            } else {
                val units = LinkedHashMap<String, LessonUnit>()
                existing.units.forEach { units[it.unitId] = it }
                fb.units.forEach { units[it.unitId] = it } // filesDir overrides/extends
                existing.copy(units = units.values.toList())
            }
        }
        return byId.values.toList()
    }
}
