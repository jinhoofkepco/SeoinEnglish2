package com.seoin.emojienglish.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Storage contract for a finished step (§4.3).
 *
 * Steps produce one of these and hand it to [com.seoin.emojienglish.step] via
 * `session.complete(result)`. The Player persists it and lights up the CTA.
 */
@Serializable
sealed interface StepResult {
    val completed: Boolean

    @Serializable
    @SerialName("Completed")
    data class Completed(override val completed: Boolean = true) : StepResult

    @Serializable
    @SerialName("Scored")
    data class Scored(
        val selected: String,
        val answer: String,
        val score: Int,
        val maxScore: Int,
        override val completed: Boolean = true,
    ) : StepResult

    @Serializable
    @SerialName("ShadowingRecorded")
    data class ShadowingRecorded(
        val practicedSentences: Int,
        val totalSentences: Int,
        val audioFilePaths: List<String> = emptyList(),
        override val completed: Boolean = true,
    ) : StepResult

    @Serializable
    @SerialName("VoiceCompleted")
    data class VoiceCompleted(
        val targetType: String,
        val targetId: String,
        override val completed: Boolean = true,
    ) : StepResult
}
