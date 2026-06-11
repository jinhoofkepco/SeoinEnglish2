package com.seoin.emojienglish.content

import android.content.Context
import com.seoin.emojienglish.model.Book
import com.seoin.emojienglish.model.BookManifest
import com.seoin.emojienglish.model.LessonUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundled-assets implementation of [LessonRepository] (§11.3: "초기 콘텐츠는
 * assets에 내장하고 같은 Validator를 통과시킨다"). Reads `assets/books/<id>/...`,
 * parses, runs [ContentValidator], and caches the result.
 *
 * Skeleton scope: assets only, loaded lazily on first access. SAF-imported books
 * from `filesDir/books/` (§11.3) and off-main-thread loading come in M2/M7.
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

    private fun load(): List<Book> {
        val assets = context.assets
        val bookDirs = assets.list("books")?.toList().orEmpty()
        return bookDirs.mapNotNull { dir -> loadBook("books/$dir") }
    }

    private fun loadBook(dir: String): Book? {
        val manifestPath = "$dir/book.json"
        val manifest = runCatching {
            json.decodeFromString<BookManifest>(readAsset(manifestPath))
        }.getOrNull() ?: return null

        val units = manifest.units.mapNotNull { ref ->
            runCatching {
                json.decodeFromString<LessonUnit>(readAsset("$dir/${ref.file}"))
            }.getOrNull()?.also { unit ->
                // Same gate the master import uses (§11.3). For now we log; M7
                // surfaces these to the master UI and refuses the import.
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

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}
