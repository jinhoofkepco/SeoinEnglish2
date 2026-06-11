package com.seoin.emojienglish.model

import kotlinx.serialization.Serializable

/**
 * "오늘의 할 공부" (Today's plan) — §5.3. Authored either by the master editor
 * or by hand-written JSON; both must compile to the identical PlayQueue (§11.4).
 */
@Serializable
data class TodayPlan(
    val schemaVersion: Int = 1,
    val planId: String,
    val title: String,
    val items: List<PlanItem> = emptyList(),
)

@Serializable
data class PlanItem(
    val bookId: String,
    val unitId: String,
    /** Use exactly one of [stepId] or [stepSelector]. */
    val stepId: String? = null,
    /** Currently only "all" is defined. Pairs with [exclude]. */
    val stepSelector: String? = null,
    val exclude: List<String> = emptyList(),
    val repeat: Int = 1,
)
