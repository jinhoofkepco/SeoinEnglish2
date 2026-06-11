package com.seoin.emojienglish.step

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Helpers for [StepFeature.parseSpec]. The Player passes the WHOLE step object
 * (`{ "id", "type", "params" }`) so a step can read both its id and its params.
 */

/** The step's `id`. @throws StepSpecParseException if absent. */
fun JsonObject.stepId(): String =
    (this["id"] as? JsonPrimitive)?.contentOrNull
        ?: throw StepSpecParseException("step is missing \"id\"")

/** The step's `params` object (empty if absent). */
fun JsonObject.params(): JsonObject =
    (this["params"] as? JsonObject) ?: JsonObject(emptyMap())

/** Read a string value from this object (e.g. a params object). */
fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** Required string; throws a parse error if missing. */
fun JsonObject.requireString(key: String): String =
    string(key) ?: throw StepSpecParseException("missing params.$key")

/** Read a list-of-strings value (e.g. panelIds / chunkIds). */
fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
