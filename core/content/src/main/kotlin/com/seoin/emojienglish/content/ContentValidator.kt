package com.seoin.emojienglish.content

import com.seoin.emojienglish.model.LessonUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Referential-integrity check for a unit (§5.2). Every id a step's `params`
 * references (panelIds, wordId, chunkIds, questionIds, passageId, targetId…)
 * must exist in `content`. Violations block book import and are shown verbatim
 * on the master screen (§11.3).
 *
 * This is a generic cross-check over well-known reference keys; each step's own
 * `parseSpec` still does the authoritative, type-specific validation.
 */
object ContentValidator {

    data class Issue(val stepId: String, val message: String)

    /** Keys whose values reference content ids, mapped to the id-set that must contain them. */
    private enum class RefKind { WORD, PANEL, CHUNK, QUESTION, PASSAGE }

    private val singleKeyKinds = mapOf(
        "wordId" to RefKind.WORD,
        "passageId" to RefKind.PASSAGE,
        "panelId" to RefKind.PANEL,
    )
    private val listKeyKinds = mapOf(
        "panelIds" to RefKind.PANEL,
        "wordIds" to RefKind.WORD,
        "chunkIds" to RefKind.CHUNK,
        "questionIds" to RefKind.QUESTION,
    )

    fun validate(unit: LessonUnit): List<Issue> {
        val content = unit.content
        val wordIds = content.words.map { it.id }.toSet()
        val panelIds = content.comic.panels.map { it.id }.toSet()
        val chunkIds = content.passage?.chunks?.map { it.id }?.toSet().orEmpty()
        val questionIds = content.passage?.questions?.map { it.id }?.toSet().orEmpty()
        val passageIds = listOfNotNull(content.passage?.id).toSet()

        fun setFor(kind: RefKind) = when (kind) {
            RefKind.WORD -> wordIds
            RefKind.PANEL -> panelIds
            RefKind.CHUNK -> chunkIds
            RefKind.QUESTION -> questionIds
            RefKind.PASSAGE -> passageIds
        }

        val issues = mutableListOf<Issue>()
        for (step in unit.steps) {
            for ((key, kind) in singleKeyKinds) {
                val v = (step.params[key] as? JsonPrimitive)?.contentOrNull ?: continue
                if (v !in setFor(kind)) {
                    issues += Issue(step.id, "${step.id}.params.$key=$v 가 content에 없음")
                }
            }
            for ((key, kind) in listKeyKinds) {
                val arr = step.params[key] as? JsonArray ?: continue
                val set = setFor(kind)
                arr.forEach { el ->
                    val v = (el as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    if (v !in set) {
                        issues += Issue(step.id, "${step.id}.params.$key 의 '$v' 가 content에 없음")
                    }
                }
            }
        }
        return issues
    }
}
