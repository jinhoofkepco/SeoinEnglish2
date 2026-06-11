package com.seoin.emojienglish.step

import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.voice.VoicePrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Standalone-dev kit (§12.1): lets each Step module be built and previewed with
 * **no app, no Player, no DB**. Records traces/voice calls so previews and unit
 * tests can assert on them.
 */
class FakeStepSession(
    override val content: LessonContent = SampleContent.restaurant,
    initialResult: StepResult? = null,
) : StepSession {

    private val _saved = MutableStateFlow(initialResult)
    override val savedResult: StateFlow<StepResult?> = _saved

    val traceLog = mutableListOf<Pair<String, Map<String, String>>>()
    val voiceRequests = mutableListOf<VoicePrompt>()
    val spoken = mutableListOf<String>()

    override fun trace(action: String, detail: Map<String, String>) {
        traceLog += action to detail
    }

    override fun complete(result: StepResult) {
        _saved.value = result
    }

    override fun requestVoice(prompt: VoicePrompt) {
        voiceRequests += prompt
    }

    override fun speak(text: String, lang: String) {
        spoken += text
    }
}
