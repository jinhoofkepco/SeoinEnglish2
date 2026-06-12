package com.seoin.emojienglish.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A request from a Step (or the chrome 🎙) to run **one coaching prompt** inside
 * the persistent voice session (§3, §9.3). Carries the template id + variables;
 * the persona/system text is filled from `templates.json` (the `서인영어_brain2`
 * doc, still pending — §14).
 *
 * [kind] picks the prompt *type* (read-along / explain / quiz…). The session's
 * [VoiceSession.runPrompt] turns this into a [VoiceTurnScript] via
 * `toTurnScript()` — Steps never build turn scripts or touch the mic. [payload]
 * is the exact text to read aloud (e.g. the current sentence), exempt from the
 * instruction sentence-cap.
 *
 * Adding [kind]/[payload] with defaults keeps every existing `VoicePrompt(...)`
 * call site (and the frozen `StepSession.requestVoice` signature) source-compatible.
 */
data class VoicePrompt(
    val templateId: String,
    val variables: Map<String, String> = emptyMap(),
    val contextLabel: String = "",   // e.g. "At a Restaurant · order" — sheet header
    val kind: StepPromptKind = StepPromptKind.EXPLAIN,
    val payload: String = "",
)

/**
 * Coordinates the global 🎙 bottom sheet. Steps and the chrome 🎙 button both
 * push a [VoicePrompt] here; the AppShell observes [active] and shows the sheet.
 *
 * This is intentionally separate from [VoiceGateway]: the gateway runs coaching
 * *turns*, while this just answers "which prompt is the sheet currently for".
 */
interface VoiceController {
    val active: StateFlow<VoicePrompt?>
    fun openSheet(prompt: VoicePrompt)
    fun close()
}

@Singleton
class DefaultVoiceController @Inject constructor() : VoiceController {
    private val _active = MutableStateFlow<VoicePrompt?>(null)
    override val active: StateFlow<VoicePrompt?> = _active.asStateFlow()
    override fun openSheet(prompt: VoicePrompt) { _active.value = prompt }
    override fun close() { _active.value = null }
}
