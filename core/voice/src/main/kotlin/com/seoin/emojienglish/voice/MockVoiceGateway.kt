package com.seoin.emojienglish.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage-1 implementation (Appendix A §B-1, roadmap step "MockVoiceGateway"):
 * no WebView yet — it just advances the state machine and resolves each turn
 * optimistically. Lets the whole app run end-to-end before the real WebView
 * port (M2′) lands, without changing a single line of caller code.
 *
 * TODO(M2′): replace with Realtime/WebView gateway that ports the 12 verified
 * mechanisms (Appendix A §A, §G). Keep this class as a `@FallbackTts` binding.
 */
@Singleton
class MockVoiceGateway @Inject constructor() : VoiceGateway {

    private val _state = MutableStateFlow(VoiceShellState.READY)
    override val state: StateFlow<VoiceShellState> = _state.asStateFlow()

    override suspend fun prime(persona: String): PrimeResult {
        _state.value = VoiceShellState.PRIMED
        return PrimeResult.Primed
    }

    override suspend fun runTurn(script: VoiceTurnScript): TurnOutcome {
        _state.value = VoiceShellState.COACHING
        delay(150)
        if (script.role == TurnRole.ASK_AND_LISTEN) {
            _state.value = VoiceShellState.CHILD_TURN
            delay(150)
            _state.value = VoiceShellState.COACHING
        }
        _state.value = VoiceShellState.PRIMED
        return TurnOutcome.Advanced(transcriptTail = "[mock] ${script.instruction.take(40)}")
    }

    override fun endChildTurnManually() {
        if (_state.value == VoiceShellState.CHILD_TURN) {
            _state.value = VoiceShellState.COACHING
        }
    }

    override fun speak(text: String, lang: String) {
        // No-op in the mock. The real gateway routes this to Android TTS.
    }
}
