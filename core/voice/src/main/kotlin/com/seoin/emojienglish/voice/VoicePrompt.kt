package com.seoin.emojienglish.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A request to open the global coaching sheet for the current context — the
 * 🎙 entry point available on every screen (§3, §9.3). Carries the template id
 * and its variables; the persona/system text is filled from `templates.json`
 * (the `서인영어_brain2` doc, still pending — §14).
 */
data class VoicePrompt(
    val templateId: String,
    val variables: Map<String, String> = emptyMap(),
    val contextLabel: String = "",   // e.g. "At a Restaurant · order" — sheet header
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
