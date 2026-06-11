package com.seoin.emojienglish.step

import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.model.StepTraceSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A decoded, time-ordered trace event for a step's master view (요구사항 ⑦). */
data class TraceActivity(
    val timeMillis: Long,
    val action: String,
    val detail: String,
)

/** Per-occurrence result summaries, oldest occurrence first. */
fun StepTraceSnapshot.resultSummaries(): List<String> =
    results.sortedBy { it.occurrence }.map { rec ->
        val body = when (val r = rec.result) {
            is StepResult.Completed -> "완료"
            is StepResult.Scored -> "선택 ${r.selected} / 정답 ${r.answer} · ${r.score}/${r.maxScore}"
            is StepResult.ShadowingRecorded -> "연습 ${r.practicedSentences}/${r.totalSentences}"
            is StepResult.VoiceCompleted -> "음성 완료 (${r.targetType}:${r.targetId})"
        }
        "회차 ${rec.occurrence}: $body"
    }

/** Trace events as time-ordered activities with their detail flattened. */
fun StepTraceSnapshot.timeOrderedActivities(): List<TraceActivity> =
    events.sortedBy { it.at }.map { e ->
        TraceActivity(timeMillis = e.at, action = e.action, detail = flattenDetail(e.detailJson))
    }

private val json = Json { ignoreUnknownKeys = true }

private fun flattenDetail(detailJson: String): String =
    runCatching {
        (json.parseToJsonElement(detailJson) as? JsonObject)
            ?.entries
            ?.joinToString(", ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
            ?: ""
    }.getOrDefault("")
