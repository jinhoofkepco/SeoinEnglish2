package com.seoin.emojienglish.step

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.StepTraceSnapshot
import kotlinx.serialization.json.JsonObject

/**
 * The single interface every Step module implements (§4.1).
 *
 * Once this file is frozen (M1), the 6 step modules + Player + Master can be
 * written **simultaneously and unaware of each other**. Do not change this
 * contract without stopping and reporting (§13 standard-prompt rule).
 */
interface StepFeature {
    /** Matches JSON `steps[].type` 1:1. e.g. "word_comic". */
    val type: String

    /** Parse & validate this step's own schema. Throws [StepSpecParseException] on failure. */
    fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec

    /** The student-facing learning screen. */
    @Composable
    fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier)

    /** Read-only master view of this step's learning trace (§0.4, §11.2). */
    @Composable
    fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier)
}

/** Marker for a step's parsed parameters. Each module defines its own data class. */
interface StepSpec {
    val stepId: String
}

class StepSpecParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
