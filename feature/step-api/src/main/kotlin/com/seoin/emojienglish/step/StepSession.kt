package com.seoin.emojienglish.step

import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.voice.VoicePrompt
import kotlinx.coroutines.flow.StateFlow

/**
 * The Step's only channel to the outside world (§4.2).
 *
 * A Step never navigates and never touches Activities (§0.3). It reads content,
 * records traces, and signals completion — the Player owns all movement via the
 * common CTA.
 */
interface StepSession {
    /** Read-only access to the unit's shared content (words, passage, comic…). */
    val content: LessonContent

    /** Result saved for THIS occurrence. Used to restore UI on re-entry. */
    val savedResult: StateFlow<StepResult?>

    /** Record one learning-trace event (delegated to TraceRepository). */
    fun trace(action: String, detail: Map<String, String> = emptyMap())

    /** ★ Completion signal. No navigation — the Player enables the CTA. */
    fun complete(result: StepResult)

    /** Open the global GPT-Voice coaching sheet for the current context (§9.3). */
    fun requestVoice(prompt: VoicePrompt)

    /** Simple TTS playback. */
    fun speak(text: String, lang: String = "en-US")
}
