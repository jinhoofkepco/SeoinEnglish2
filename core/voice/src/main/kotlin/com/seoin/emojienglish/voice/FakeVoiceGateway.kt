package com.seoin.emojienglish.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dev/preview gateway (§12.1, Appendix A §F M3). Records every call so a Step
 * can be built and previewed with **no real voice dependency**. Distinct from
 * [MockVoiceGateway]: this one is for `@Preview`/unit tests, not DI.
 */
class FakeVoiceGateway(
    initial: VoiceShellState = VoiceShellState.PRIMED,
) : VoiceGateway {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<VoiceShellState> = _state.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _micOpen = MutableStateFlow(false)
    override val micOpen: StateFlow<Boolean> = _micOpen.asStateFlow()

    val turnLog = mutableListOf<VoiceTurnScript>()
    val spoken = mutableListOf<String>()
    val renamed = mutableListOf<String>()

    /** Test hook: decide what the next turn returns. */
    var nextOutcome: (VoiceTurnScript) -> TurnOutcome = { TurnOutcome.Advanced("[fake]") }

    override suspend fun connect(): VoiceShellState = _state.value

    override suspend fun prime(persona: String): PrimeResult {
        _state.value = VoiceShellState.PRIMED
        return PrimeResult.Primed
    }

    override suspend fun runTurn(script: VoiceTurnScript): TurnOutcome {
        turnLog += script
        return nextOutcome(script)
    }

    override fun endChildTurnManually() {}

    override fun setMicOpen(open: Boolean) { _micOpen.value = open }

    override fun renameConversation(name: String) { renamed += name }

    override fun setContentZoom(factor: Float) {}

    override fun speak(text: String, lang: String) { spoken += text }

    override fun provideView(): android.webkit.WebView? = null
}
