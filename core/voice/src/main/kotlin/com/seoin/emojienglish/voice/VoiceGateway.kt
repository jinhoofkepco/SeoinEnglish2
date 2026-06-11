package com.seoin.emojienglish.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * GPT Voice gateway — Appendix A (§B-1), which **replaces** the v1 design §9.
 *
 * The confirmed mechanism is an in-app WebView driving chatgpt.com's voice mode
 * via JS automation. A Step never sees timing/JS/mic details: it only builds a
 * [VoiceTurnScript] and calls [runTurn]. Mic open/close, text watching, tag
 * branching and TTS fallback all live inside the turn state machine (§B-2) so
 * that no voice-shell knowledge leaks into step code (§0.2).
 *
 * The real WebView implementation (porting MainActivity.kt §G) is a later
 * milestone (M2′). For the skeleton we ship [FakeVoiceGateway] (step dev) and a
 * minimal [MockVoiceGateway] (TTS-only), both honouring this exact contract.
 */
interface VoiceGateway {
    val state: StateFlow<VoiceShellState>

    /** Inject the coaching persona/role. Once per session (§D step 5). */
    suspend fun prime(persona: String): PrimeResult

    /** Run one coaching turn. The state machine handles everything inside (§B-2). */
    suspend fun runTurn(script: VoiceTurnScript): TurnOutcome

    /** "다 말했어요" button — manual end of the child's turn (§B-1). */
    fun endChildTurnManually()

    /** Local TTS fallback / simple playback. */
    fun speak(text: String, lang: String = "en-US")
}

/** Voice-shell lifecycle (§B-1). Surfaced as the 🟢/🟡/🔴 health pill (§D). */
enum class VoiceShellState {
    NOT_LOGGED_IN,    // manual chatgpt.com login pending (§D step 1)
    CONNECTING,
    READY,            // voice UI ready, mic closed
    PRIMED,           // persona injected, CONVERSATION_READY
    COACHING,
    CHILD_TURN,
    RECONNECTING,
    FALLBACK_TTS,     // degraded to local TTS, learning continues (§B-2, mechanism 11)
}

/** Whether a turn opens the mic afterwards. Drives state-machine step 7 vs 8 (§B-2). */
enum class TurnRole {
    /** Explanation/reading only. Mic never opens. */
    READ_ONLY,

    /** Ask, then open mic for the child's turn + evaluate. Requires [EvalRubric]. */
    ASK_AND_LISTEN,
}

/** Text-tag protocol (§C-4). App branches on the tag, never on voice content. */
enum class FlowTag { NEXT, WAIT, RETRY }

/**
 * Evaluation rubric — mandatory for ASK_AND_LISTEN (§C-3). Without it the model
 * "lets wrong answers slide", a failure reproduced in operation. The builder
 * (TurnScriptBuilder) turns this into a within-budget judging instruction.
 */
data class EvalRubric(
    val praiseFirst: Boolean = true,
    val mustJudge: Boolean = true,
    val onWrong: String,
    val onRight: String,
)

/**
 * One complete coaching turn — the only unit a Step can produce (§B-1).
 *
 * [instruction] is capped to 4 sentences / 350 chars (§C-1, PromptBudget).
 * [payload] (the text to read) is exempt from the sentence cap.
 */
data class VoiceTurnScript(
    val role: TurnRole,
    val instruction: String,
    val payload: String = "",
    val evalRubric: EvalRubric? = null,
    val allowedTags: Set<FlowTag> = setOf(FlowTag.NEXT, FlowTag.RETRY),
) {
    init {
        require(role != TurnRole.ASK_AND_LISTEN || evalRubric != null) {
            "ASK_AND_LISTEN turns require an EvalRubric (§C-3)"
        }
    }
}

/** Outcome of [VoiceGateway.runTurn], decided by the text tag (§B-1, §C-4). */
sealed interface TurnOutcome {
    data class Advanced(val transcriptTail: String) : TurnOutcome   // [SEOIN_NEXT]
    data class Retry(val transcriptTail: String) : TurnOutcome      // [SEOIN_RETRY]
    data class Waiting(val transcriptTail: String) : TurnOutcome    // [SEOIN_WAIT]
    data class FellBackToTts(val reason: String) : TurnOutcome
    data class Failed(val reason: String) : TurnOutcome
}

sealed interface PrimeResult {
    data object Primed : PrimeResult
    data class Failed(val reason: String) : PrimeResult
}

/**
 * Volatile directives that must be re-injected every turn, not "remembered"
 * (§C-1, §C-2). TurnScriptBuilder prepends these — Step authors never type them.
 */
object StandingDirectives {
    const val SPEAK_STYLE = "Speak slowly, in very easy English, warm and short."
    const val READ_SLOWLY = "Read slowly and clearly."
    const val TAG_SILENCE = "Do not say the bracket tag out loud."
}
