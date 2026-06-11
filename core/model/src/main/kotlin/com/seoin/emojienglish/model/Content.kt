package com.seoin.emojienglish.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * JSON content contract — see architecture §5.
 *
 * Rule §0.1: content is data, never code. These classes mirror `book.json` /
 * the unit JSON files 1:1. The renderer layer only maps `StepDef.type` → renderer.
 */

@Serializable
data class BookManifest(
    val schemaVersion: Int = 2,
    val bookId: String,
    val title: String,
    val level: String = "",
    val coverEmoji: String = "📘",
    val units: List<UnitRef> = emptyList(),
)

@Serializable
data class UnitRef(
    val unitId: String,
    val title: String,
    val file: String,
)

/** A book with its units fully resolved (manifest + loaded unit files). */
data class Book(
    val bookId: String,
    val title: String,
    val level: String,
    val coverEmoji: String,
    val units: List<LessonUnit>,
)

@Serializable
data class LessonUnit(
    val schemaVersion: Int = 2,
    val unitId: String,
    val title: String,
    val level: String = "",
    val content: LessonContent,
    val steps: List<StepDef> = emptyList(),
)

/** The shared "materials" of a unit. Steps reference these by id (§5.2). */
@Serializable
data class LessonContent(
    val words: List<Word> = emptyList(),
    val comic: Comic = Comic(),
    val passage: Passage? = null,
) {
    fun word(id: String): Word? = words.firstOrNull { it.id == id }
    fun panel(id: String): ComicPanel? = comic.panels.firstOrNull { it.id == id }
    fun chunk(id: String): Chunk? = passage?.chunks?.firstOrNull { it.id == id }
    fun question(id: String): Question? = passage?.questions?.firstOrNull { it.id == id }
}

@Serializable
data class Word(
    val id: String,
    val text: String,
    val meaningKo: String = "",
    val emoji: String = "",
    val example: String = "",
    val similarWords: List<String> = emptyList(),
)

@Serializable
data class Comic(
    val panels: List<ComicPanel> = emptyList(),
)

@Serializable
data class ComicPanel(
    val id: String,
    val speaker: String = "",
    val text: String = "",
    val emojiScene: String = "",
    val focusWordIds: List<String> = emptyList(),
)

@Serializable
data class Passage(
    val id: String,
    val title: String = "",
    val text: String = "",
    val sentences: List<String> = emptyList(),
    val chunks: List<Chunk> = emptyList(),
    val questions: List<Question> = emptyList(),
)

@Serializable
data class Chunk(
    val id: String,
    val text: String,
    val meaningKo: String = "",
)

@Serializable
data class Question(
    val id: String,
    val type: String = "multiple_choice",
    val question: String,
    val choices: List<String> = emptyList(),
    val answer: String,
)

/**
 * One step entry in a unit's `steps` array (§5.2).
 *
 * [params] stays as a raw [JsonObject]: each Step module parses its own schema
 * (StepFeature.parseSpec). core:model deliberately does NOT know any step's
 * parameter shape — that is what keeps steps independent (§0.2).
 */
@Serializable
data class StepDef(
    val id: String,
    val type: String,
    val params: JsonObject = JsonObject(emptyMap()),
)
