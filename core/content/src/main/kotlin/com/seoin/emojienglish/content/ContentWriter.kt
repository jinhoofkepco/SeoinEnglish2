package com.seoin.emojienglish.content

import android.content.Context
import com.seoin.emojienglish.model.BookManifest
import com.seoin.emojienglish.model.LessonUnit
import com.seoin.emojienglish.model.UnitRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes runtime content into `filesDir/books/<bookId>/` — the same layout the
 * [AssetLessonRepository] merges on top of bundled assets. The master authoring
 * tool uses this to persist a generated unit (+ its images); after [LessonRepository.refresh]
 * the content shows up in the app immediately.
 *
 * Layout written:
 * ```
 * filesDir/books/<bookId>/book.json                 (manifest, units[] merged)
 * filesDir/books/<bookId>/units/<unitId>.json       (the unit)
 * filesDir/books/<bookId>/images/<unitId>/<file>    (word/scene images)
 * ```
 */
@Singleton
class ContentWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    private fun bookDir(bookId: String): File =
        File(context.filesDir, "books/$bookId").apply { mkdirs() }

    /** Asset-relative path for a unit image, e.g. `books/b/images/u/word.png`. */
    fun imageAssetPath(bookId: String, unitId: String, fileName: String): String =
        "books/$bookId/images/$unitId/$fileName"

    /** Absolute file for a unit image (creates parent dirs). */
    fun imageFile(bookId: String, unitId: String, fileName: String): File {
        val dir = File(bookDir(bookId), "images/$unitId").apply { mkdirs() }
        return File(dir, fileName)
    }

    fun writeImage(bookId: String, unitId: String, fileName: String, bytes: ByteArray): File =
        imageFile(bookId, unitId, fileName).apply { writeBytes(bytes) }

    /**
     * Write [unit] under [bookId] and merge it into that book's manifest (create
     * the manifest if missing). [bookTitle]/[level]/[coverEmoji] seed a new manifest;
     * an existing manifest keeps its own meta and only gets the unit added/replaced.
     */
    fun writeUnit(
        bookId: String,
        bookTitle: String,
        unit: LessonUnit,
        level: String = "",
        coverEmoji: String = "📘",
    ): File {
        val dir = bookDir(bookId)
        File(dir, "units").mkdirs()
        val unitFileRel = "units/${unit.unitId}.json"
        File(dir, unitFileRel).writeText(json.encodeToString(LessonUnit.serializer(), unit))

        // Merge manifest.
        val manifestFile = File(dir, "book.json")
        val existing = manifestFile.takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(BookManifest.serializer(), it.readText()) }.getOrNull() }
        val ref = UnitRef(unitId = unit.unitId, title = unit.title, file = unitFileRel)
        val manifest = if (existing == null) {
            BookManifest(
                bookId = bookId,
                title = bookTitle.ifBlank { bookId },
                level = level,
                coverEmoji = coverEmoji,
                units = listOf(ref),
            )
        } else {
            val units = existing.units.filterNot { it.unitId == unit.unitId } + ref
            existing.copy(title = existing.title.ifBlank { bookTitle }, units = units)
        }
        manifestFile.writeText(json.encodeToString(BookManifest.serializer(), manifest))
        return File(dir, unitFileRel)
    }

    /**
     * Delete a runtime-authored unit from filesDir only. Bundled asset units are
     * read-only and remain untouched; this only removes the generated override,
     * manifest entry, and unit image folder if they exist.
     */
    fun deleteUnit(bookId: String, unitId: String): Boolean {
        val dir = File(context.filesDir, "books/$bookId")
        if (!dir.exists()) return false

        var changed = false
        val manifestFile = File(dir, "book.json")
        val existing = manifestFile.takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(BookManifest.serializer(), it.readText()) }.getOrNull() }
        if (existing != null) {
            val units = existing.units.filterNot { it.unitId == unitId }
            if (units.size != existing.units.size) {
                manifestFile.writeText(json.encodeToString(BookManifest.serializer(), existing.copy(units = units)))
                changed = true
            }
        }

        val unitFile = File(dir, "units/$unitId.json")
        if (unitFile.isFile && unitFile.delete()) changed = true

        val imageDir = File(dir, "images/$unitId")
        if (imageDir.exists() && imageDir.deleteRecursively()) changed = true

        return changed
    }
}
