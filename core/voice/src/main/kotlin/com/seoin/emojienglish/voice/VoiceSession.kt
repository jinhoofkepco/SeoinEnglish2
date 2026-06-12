package com.seoin.emojienglish.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The **공통기능(메인) 계층** — one voice session per learning *set* (unit run),
 * living above the per-turn [VoiceGateway]. This is the single owner of the
 * manual/auto mic policy so the rule lives in exactly one place.
 *
 * Responsibilities (사용자 요구 매핑):
 * - **세트 동안 한 보이스**: [startSet] connects + primes once; step navigation
 *   never tears it down (the gateway keeps the same WebView).
 * - **마이크 수동 on/off** ([setMicManual]) — our panel button.
 * - **마이크 auto-gate** ([setAutoGate]) — the prompt layer enables auto handling;
 *   pressing the manual button **disables auto and it does not re-arm** until the
 *   next set / explicit re-enable (목표③ override 규칙).
 * - **빠른 프롬프트 실행** ([runPrompt]) — compiles a [VoicePrompt] and runs it,
 *   muting the child mic while the teacher voice plays through to media-quiet
 *   (목표②, delegated to the gateway), then opening the mic for ASK turns *only*
 *   when auto-gate is still allowed.
 * - **자유 대화** ([startFreeTalk]) — 목표③.
 */
interface VoiceSession {
    val state: StateFlow<VoiceSessionState>

    /** Connect + prime once for a whole set. Safe to call again (idempotent). */
    suspend fun startSet(persona: String, conversationName: String)

    /** Tear down the set (leaves the WebView; just resets session policy). */
    fun endSet()

    /** Run one prompt to completion inside the session. */
    suspend fun runPrompt(prompt: VoicePrompt)

    /** Our mic button. Disables auto-gate and remembers the manual override. */
    fun setMicManual(open: Boolean)

    /** Prompt layer asks to (dis)arm auto mic handling. Ignored if overridden. */
    fun setAutoGate(enable: Boolean)

    /** Open a free-talk session: mic fully manual, no auto-gate (목표③). */
    suspend fun startFreeTalk()

    /** Cycle the panel size: MINIMIZED → OPEN → CLOSED → MINIMIZED (접기 버튼). */
    fun cyclePanel()

    /** Set the panel size directly. */
    fun setPanelMode(mode: VoicePanelMode)

    /** Best-effort conversation rename (로그 관리); manual via sidebar otherwise. */
    fun rename(name: String)

    /** The retained ChatGPT [android.webkit.WebView] for the panel to host. */
    fun provideView(): android.webkit.WebView?
}

enum class VoiceSessionMode { NONE, COACHING, FREE_TALK }

/**
 * Panel size (요구: 최소화 열림/열림/닫힘 3-state).
 *  - [CLOSED]: thin status strip only — WebView detached.
 *  - [MINIMIZED]: small WebView window (default auto-open) — attached & working.
 *  - [OPEN]: full WebView window.
 * The WebView must be attached (MINIMIZED or OPEN) for voice to function (#1).
 */
enum class VoicePanelMode { CLOSED, MINIMIZED, OPEN }

/** Snapshot the panel renders. [micOpen]/[speaking]/[shell] mirror the gateway. */
data class VoiceSessionState(
    val mode: VoiceSessionMode = VoiceSessionMode.NONE,
    val shell: VoiceShellState = VoiceShellState.NOT_LOGGED_IN,
    val micOpen: Boolean = false,
    val micAutoGate: Boolean = true,
    val manualOverride: Boolean = false,
    val speaking: Boolean = false,
    val busy: Boolean = false,
    val panelMode: VoicePanelMode = VoicePanelMode.CLOSED,
    val conversationName: String = "",
)

@Singleton
class DefaultVoiceSession @Inject constructor(
    private val gateway: VoiceGateway,
) : VoiceSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Serializes gateway turns so rapid word taps QUEUE instead of overlapping. */
    private val turnMutex = Mutex()

    private val _state = MutableStateFlow(VoiceSessionState())
    override val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private fun applyZoom() {
        // No-op: the panel keeps the WebView at full content size and clips it, so
        // no CSS zoom is needed. Kept as a hook in case we revisit content scaling.
    }

    init {
        // Mirror gateway flows into the session snapshot.
        scope.launch { gateway.state.collect { s -> _state.value = _state.value.copy(shell = s) } }
        scope.launch { gateway.micOpen.collect { m -> _state.value = _state.value.copy(micOpen = m) } }
        scope.launch { gateway.speaking.collect { s -> _state.value = _state.value.copy(speaking = s) } }
    }

    override suspend fun startSet(persona: String, conversationName: String) {
        if (_state.value.mode == VoiceSessionMode.COACHING &&
            _state.value.shell == VoiceShellState.PRIMED
        ) return
        _state.value = _state.value.copy(
            mode = VoiceSessionMode.COACHING,
            busy = true,
            conversationName = conversationName,
            manualOverride = false,
            micAutoGate = true,
            // The WebView only works while attached/laid out, which needs the panel
            // open. Default to the MINIMIZED (최소화 열림) window (#1·#4).
            panelMode = VoicePanelMode.MINIMIZED,
        )
        turnMutex.withLock {
            gateway.connect()
            gateway.prime(persona)
            if (conversationName.isNotBlank()) gateway.renameConversation(conversationName)
        }
        applyZoom()
        _state.value = _state.value.copy(busy = false)
    }

    override fun endSet() {
        _state.value = _state.value.copy(
            mode = VoiceSessionMode.NONE,
            busy = false,
            panelMode = VoicePanelMode.CLOSED,
        )
        // Restore the device mic so leaving voice doesn't leave it muted system-wide.
        gateway.setMicOpen(true)
    }

    override suspend fun runPrompt(prompt: VoicePrompt) {
        if (_state.value.mode == VoiceSessionMode.NONE) return
        // One turn at a time: if a turn (or the initial connect/prime) is already
        // running, ignore the new tap rather than stacking a long queue (엉킴 방지).
        if (turnMutex.isLocked) {
            android.util.Log.d("SeoinVoice", "runPrompt ignored — a turn is already in progress")
            return
        }
        // Ensure the WebView is attached (panel open) or the turn can't run (#1).
        val openMode = if (_state.value.panelMode == VoicePanelMode.CLOSED) {
            VoicePanelMode.MINIMIZED
        } else {
            _state.value.panelMode
        }
        _state.value = _state.value.copy(busy = true, panelMode = openMode)
        applyZoom()
        // Queue behind any in-flight turn so rapid taps don't overlap on the gateway.
        turnMutex.withLock {
            // The gateway turn mutes the mic during speech and holds to media-quiet,
            // leaving the mic closed (목표② 필수). It never opens the child mic itself.
            gateway.runTurn(prompt.toTurnScript())
            // After the teacher finishes speaking, re-open the mic so the child/teacher
            // can talk back (요구: "설명 끝나면 다시 켜줘") — unless the teacher manually
            // overrode (목표③). Applies to every turn, not just quizzes.
            val s = _state.value
            if (s.micAutoGate && !s.manualOverride) {
                gateway.setMicOpen(true)
            }
        }
        _state.value = _state.value.copy(busy = false)
    }

    override fun setMicManual(open: Boolean) {
        // Teacher took control: disable auto-gate and remember the override so the
        // prompt layer cannot silently re-arm it (사용자 명시 규칙).
        _state.value = _state.value.copy(micAutoGate = false, manualOverride = true)
        gateway.setMicOpen(open)
    }

    override fun setAutoGate(enable: Boolean) {
        if (_state.value.manualOverride) return // override sticks until next set
        _state.value = _state.value.copy(micAutoGate = enable)
    }

    override suspend fun startFreeTalk() {
        _state.value = _state.value.copy(
            mode = VoiceSessionMode.FREE_TALK,
            micAutoGate = false,
            manualOverride = true,
            busy = true,
            panelMode = VoicePanelMode.OPEN,
        )
        turnMutex.withLock { gateway.connect() }
        gateway.setMicOpen(true) // free talk: mic on so the user can just speak
        applyZoom()
        _state.value = _state.value.copy(busy = false)
    }

    override fun cyclePanel() {
        val next = when (_state.value.panelMode) {
            VoicePanelMode.MINIMIZED -> VoicePanelMode.OPEN
            VoicePanelMode.OPEN -> VoicePanelMode.CLOSED
            VoicePanelMode.CLOSED -> VoicePanelMode.MINIMIZED
        }
        _state.value = _state.value.copy(panelMode = next)
        applyZoom()
    }

    override fun setPanelMode(mode: VoicePanelMode) {
        _state.value = _state.value.copy(panelMode = mode)
        applyZoom()
    }

    override fun rename(name: String) {
        _state.value = _state.value.copy(conversationName = name)
        gateway.renameConversation(name)
    }

    override fun provideView(): android.webkit.WebView? = gateway.provideView()
}
