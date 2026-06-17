package com.seoin.emojienglish.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Real GPT-Voice engine — a single in-app [WebView] driving chatgpt.com's voice
 * mode via JS automation. Ports the verified mechanisms from
 * `TRPG_webviewGPT/MainActivity.kt` (see `webview_voicecontrol_explain.md`):
 *
 * - voice mode open + **voice-ready vs conversation-ready** separation,
 * - inject + confirm the **user bubble actually appears** (clicked ≠ sent),
 * - response text start/complete + tag detection,
 * - **media-quiet gate** (RMS probe) so the mic stays muted until the *speaker*
 *   (not just the text) finishes — the core of 설명 중 마이크 auto-off (목표②),
 * - mic mute/unmute, audio duck during priming.
 *
 * The single WebView lives in this `@Singleton`, so it survives step navigation
 * (한 세트 한 보이스). The Compose panel hosts it via `AndroidView` + [provideView].
 *
 * Orchestration is rewritten as structured `suspend` functions (the reference was
 * callback-based) but keeps the same timings/thresholds.
 */
@Singleton
class WebViewVoiceGateway @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val localTts: LocalTts,
) : VoiceGateway {

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceShellState.NOT_LOGGED_IN)
    override val state: StateFlow<VoiceShellState> = _state.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _micOpen = MutableStateFlow(false)
    override val micOpen: StateFlow<Boolean> = _micOpen.asStateFlow()

    private var webView: WebView? = null
    @Volatile private var pageLoaded = false
    @Volatile private var primedOnce = false
    private val pendingAudio = mutableListOf<PermissionRequest>()

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(appContext)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio) { request.deny(); return }
                if (hasRecordAudio()) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    // App must grant RECORD_AUDIO first (MainActivity prompts).
                    pendingAudio += request
                    Log.w(TAG, "web mic request pending — RECORD_AUDIO not granted yet")
                }
            }
            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                pendingAudio.remove(request)
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "renderer gone didCrash=${detail?.didCrash()} — dropping voice webview")
                if (view === webView) {
                    webView = null
                    pageLoaded = false
                    primedOnce = false
                }
                pendingAudio.clear()
                _speaking.value = false
                _micOpen.value = false
                _state.value = VoiceShellState.RECONNECTING
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                view?.destroy()
                return true
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        wv.loadUrl(CHATGPT_URL)
        webView = wv
        return wv
    }

    override fun provideView(): WebView = ensureWebView()

    /**
     * Master authoring needs ChatGPT WebView memory to be reserved for the
     * authoring shell. Dropping this instance is safe: entering a lesson later
     * creates and primes a fresh voice WebView.
     */
    fun shutdownForAuthoring() {
        main.launch {
            val old = webView ?: return@launch
            Log.d(TAG, "shutdownForAuthoring: dropping voice webview")
            pendingAudio.clear()
            localTts.stop()
            _speaking.value = false
            _micOpen.value = false
            _state.value = VoiceShellState.NOT_LOGGED_IN
            webView = null
            pageLoaded = false
            primedOnce = false
            (old.parent as? android.view.ViewGroup)?.removeView(old)
            runCatching { old.stopLoading() }
            runCatching { old.destroy() }
        }
    }

    /** Call after the app grants RECORD_AUDIO so any queued web mic request lands. */
    fun flushPendingAudioPermission() {
        if (pendingAudio.isEmpty() || !hasRecordAudio()) return
        pendingAudio.toList().forEach { it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) }
        pendingAudio.clear()
    }

    private fun hasRecordAudio(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------- lifecycle

    override suspend fun connect(): VoiceShellState = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        // Wait for the page to load.
        val loadDeadline = now() + PAGE_LOAD_TIMEOUT_MS
        while (!pageLoaded && now() < loadDeadline) delay(200)

        if (_state.value == VoiceShellState.READY || _state.value == VoiceShellState.PRIMED ||
            _state.value == VoiceShellState.COACHING
        ) return@withContext _state.value

        _state.value = VoiceShellState.CONNECTING
        val deadline = now() + VOICE_CONFIRM_TIMEOUT_MS
        var clickedVoice = false
        while (now() < deadline) {
            val ready = evalBool(buildVoiceReadyScript())
            if (ready) {
                _state.value = VoiceShellState.READY
                muteInternal(false) // default to muted so GPT can't hear until we open
                return@withContext _state.value
            }
            if (!clickedVoice) {
                clickedVoice = evalBool(buildVoiceButtonScript())
                Log.d(TAG, "voice button click=$clickedVoice")
            }
            delay(VOICE_CONFIRM_POLL_MS)
        }
        // Never reached ready — most likely a manual chatgpt.com login is needed.
        _state.value = VoiceShellState.NOT_LOGGED_IN
        _state.value
    }

    // --------------------------------------------------------------- priming

    override suspend fun prime(persona: String): PrimeResult = withContext(Dispatchers.Main.immediate) {
        if (connect() == VoiceShellState.NOT_LOGGED_IN) {
            return@withContext PrimeResult.Failed("not logged in")
        }
        // Prime exactly once per app run. Re-entering a book must NOT re-send the
        // persona (피드백 #2) — the singleton WebView already holds the primed chat.
        if (primedOnce) {
            _state.value = VoiceShellState.PRIMED
            muteInternal(false)
            return@withContext PrimeResult.Primed
        }
        // Duck audio so the priming reply (if any) is silent (노하우: priming 음소거).
        eval(buildAudioDuckScript(ducked = true, targetVolume = 0.0))
        val sent = send(persona)
        if (!sent) {
            eval(buildAudioDuckScript(ducked = false, targetVolume = 0.0))
            return@withContext PrimeResult.Failed("priming not sent")
        }
        awaitResponseComplete(expected = null)
        // ★ 2-phase: a real keyboard input must hit the composer before the next
        // prompt or voice mode won't fully wake (사용자 명시 절차).
        keyboardNudge()
        eval(buildAudioDuckScript(ducked = false, targetVolume = 0.0))
        _state.value = VoiceShellState.PRIMED
        primedOnce = true
        muteInternal(false) // start muted; we open the mic only after a turn
        PrimeResult.Primed
    }

    /** Focus the composer and push a real keystroke so voice mode fully arms. */
    private suspend fun keyboardNudge() {
        withContext(Dispatchers.Main.immediate) { webView?.requestFocus() }
        eval(buildKeyboardNudgeScript())
        delay(KEYBOARD_NUDGE_SETTLE_MS)
    }

    // ----------------------------------------------------------------- turns

    override suspend fun runTurn(script: VoiceTurnScript): TurnOutcome =
        withContext(Dispatchers.Main.immediate) {
            if (_state.value == VoiceShellState.NOT_LOGGED_IN) {
                return@withContext TurnOutcome.Failed("not logged in")
            }
            _state.value = VoiceShellState.COACHING

            val message = buildList {
                if (script.instruction.isNotBlank()) add(script.instruction)
                if (script.payload.isNotBlank()) add(script.payload)
            }.joinToString("\n\n")

            if (!send(message)) {
                _state.value = VoiceShellState.PRIMED
                return@withContext TurnOutcome.Failed("send failed")
            }

            muteInternal(false)
            logDiagnostics("afterSend")
            // One loop watches BOTH the response text and the speaker audio so the
            // "설명 중" flag tracks real audio live (on as soon as it plays, off when
            // it stops) instead of starting only after the text finished (피드백).
            val response = awaitTurnComplete()

            val tail = response.assistantText.takeLast(TRANSCRIPT_TAIL)
            _state.value = VoiceShellState.PRIMED
            when {
                tail.contains("[SEOIN_RETRY]", ignoreCase = true) -> TurnOutcome.Retry(tail)
                tail.contains("[SEOIN_WAIT]", ignoreCase = true) -> TurnOutcome.Waiting(tail)
                else -> TurnOutcome.Advanced(tail)
            }
        }

    override fun endChildTurnManually() {
        main.launch { setMicOpen(false) }
        if (_state.value == VoiceShellState.CHILD_TURN) _state.value = VoiceShellState.COACHING
    }

    override fun setMicOpen(open: Boolean) {
        main.launch { muteInternal(open) }
    }

    /**
     * Mute/unmute the **system microphone** instead of clicking ChatGPT's own mic
     * button (피드백 #1·#3). Two wins:
     *  - our [micOpen] state is authoritative and never disagrees with the WebView,
     *  - we never touch the voice-mode UI, so the session stays clean and GPT keeps
     *    answering *by voice* (clicking its mic button was making it reply as text).
     * Requires only MODIFY_AUDIO_SETTINGS (already granted).
     */
    private fun muteInternal(open: Boolean) {
        runCatching { audioManager.isMicrophoneMute = !open }
            .onFailure { Log.w(TAG, "setMicrophoneMute failed: ${it.message}") }
        _micOpen.value = open
        if (open) _state.value = VoiceShellState.CHILD_TURN
        Log.d(TAG, "mic ${if (open) "open" else "muted"} (systemMicMute=${runCatching { audioManager.isMicrophoneMute }.getOrNull()})")
    }

    override fun renameConversation(name: String) {
        main.launch {
            val r = eval(buildRenameConversationScript(name))
            Log.d(TAG, "renameConversation -> $r (manual fallback via sidebar if 'manual')")
        }
    }

    override fun setContentZoom(factor: Float) {
        val z = factor.coerceIn(0.3f, 1.0f)
        main.launch {
            // CSS zoom on the root scales the whole rendered page (incl. the voice
            // overlay), so a small window shows more UI (#: 최소화 줌아웃).
            eval("(function(){try{document.documentElement.style.zoom='$z';return 'ok';}catch(e){return String(e);}})();")
            Log.d(TAG, "setContentZoom -> $z")
        }
    }

    override fun speak(text: String, lang: String) {
        // Instant local TTS — comic captions / word meanings (전체만화 낭독).
        // GPT voice stays for interactive coaching turns only.
        localTts.speak(text, lang)
    }

    override suspend fun textQuery(prompt: String): String? = withContext(Dispatchers.Main.immediate) {
        if (!send(prompt)) return@withContext null
        val response = awaitResponseComplete(null)
        response.assistantText.takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------- send + confirm

    /** Inject [message] and confirm the user bubble actually appears. */
    private suspend fun send(message: String): Boolean {
        // 1) inject + click send, retrying until the click lands.
        var clicked = false
        var attempts = INJECT_SEND_ATTEMPTS
        while (attempts-- > 0 && !clicked) {
            val res = evalObj(buildChatGptInjectionScript(message))
            clicked = res?.optBoolean("sendClicked") == true
            if (!clicked) delay(INJECT_SEND_POLL_MS)
        }
        if (!clicked) {
            Log.w(TAG, "send: never clicked for \"${message.take(40)}\"")
            return false
        }
        // 2) confirm the user message bubble shows (clicked ≠ sent).
        val deadline = now() + INJECT_USER_VISIBLE_TIMEOUT_MS
        while (now() < deadline) {
            val probe = evalObj(buildInjectedUserProbeScript(message))
            if (probe?.optBoolean("visible") == true) {
                Log.d(TAG, "send visible=true users=${probe.optInt("userMessageCount")}")
                return true
            }
            delay(INJECT_USER_VISIBLE_POLL_MS)
        }
        Log.w(TAG, "send: user bubble never confirmed visible")
        return false
    }

    /** Poll until the assistant text is stable / no longer streaming. */
    private suspend fun awaitResponseComplete(expected: String?): ResponseProbe {
        val deadline = now() + RESPONSE_TIMEOUT_MS
        var stable = 0
        var last = ResponseProbe("", busy = true, complete = false)
        while (now() < deadline) {
            val obj = evalObj(buildResponseProbeScript(expected))
            if (obj == null) { delay(RESPONSE_POLL_MS); continue }
            last = ResponseProbe(
                assistantText = obj.optString("assistantText"),
                busy = obj.optBoolean("busy"),
                complete = obj.optBoolean("sentenceComplete"),
            )
            if (!last.busy && last.complete && last.assistantText.isNotEmpty()) {
                stable++
                if (stable >= RESPONSE_STABLE_POLLS) {
                    delay(COMPLETION_PUNCTUATION_SETTLE_MS)
                    return last
                }
            } else {
                stable = 0
            }
            delay(RESPONSE_POLL_MS)
        }
        return last
    }

    /**
     * Single combined wait after a send: each tick probes BOTH the response text
     * and the speaker audio. [speaking] mirrors the live audio activity (on the
     * moment audio plays, off ~quietStreak after it stops) so the "설명 중" flag is
     * accurate. The mic is muted while audio plays (목표②). The turn ends when the
     * text is stable AND the speaker has gone quiet (or it was a text-only answer).
     */
    private suspend fun awaitTurnComplete(): ResponseProbe {
        val startWaitDeadline = now() + MEDIA_AUDIO_START_WAIT_MS
        val maxWait = now() + MEDIA_AUDIO_GATE_MAX_WAIT_MS
        var sawAudio = false
        var quietStreak = 0
        var polls = 0
        var last = ResponseProbe("", busy = true, complete = false)
        try {
            while (now() < maxWait) {
                val media = evalObj(buildMediaPlaybackProbeScript())
                val recentActive = media?.optBoolean("recentActive") == true
                val voiceOpen = media?.optBoolean("voiceOpen") == true

                // Live speaking flag + reactive mic mute while audio plays.
                _speaking.value = recentActive
                if (recentActive) {
                    sawAudio = true
                    quietStreak = 0
                    if (_micOpen.value) muteInternal(false)
                } else if (sawAudio) {
                    quietStreak++
                }

                // Capture the latest assistant text for tag parsing (not for ending).
                val resp = evalObj(buildResponseProbeScript(null))
                if (resp != null) {
                    last = ResponseProbe(
                        assistantText = resp.optString("assistantText"),
                        busy = resp.optBoolean("busy"),
                        complete = resp.optBoolean("sentenceComplete"),
                    )
                }
                if (polls < 6 || polls % 8 == 0) {
                    Log.d(TAG, "turn poll=$polls recent=$recentActive saw=$sawAudio quiet=$quietStreak voiceOpen=$voiceOpen mic=${_micOpen.value}")
                }
                polls++
                // In voice mode the SPEAKER finishing ends the turn — we do NOT wait
                // for the text response to "complete" (its stop-icon never settles in
                // voice mode, which left 코칭 stuck for ~45s). 피드백.
                when {
                    // Spoke, then went quiet for a couple ticks (ignores tiny gaps).
                    sawAudio && quietStreak >= QUIET_STREAK_POLLS -> break
                    // Never spoke: text-only answer or the overlay closed.
                    !sawAudio && !voiceOpen && polls >= 3 -> break
                    !sawAudio && now() > startWaitDeadline -> break
                }
                delay(TURN_POLL_MS)
            }
        } finally {
            eval(buildMediaProbeCleanupScript())
            Log.d(TAG, "turn finished sawAudio=$sawAudio polls=$polls")
            _speaking.value = false
        }
        return last
    }

    // -------------------------------------------------------------- JS bridge

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main.immediate) {
        val wv = ensureWebView()
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result ?: "null") }
        }
    }

    private suspend fun evalBool(js: String): Boolean = eval(js).trim().removeSurrounding("\"") == "true"

    private suspend fun evalObj(js: String): JSONObject? = runCatching {
        val raw = eval(js)
        if (raw.isBlank() || raw == "null") null else JSONObject(raw)
    }.getOrNull()

    private fun now() = System.currentTimeMillis()

    private data class ResponseProbe(val assistantText: String, val busy: Boolean, val complete: Boolean)

    // ===================================================================== JS
    // Ported verbatim from TRPG_webviewGPT/MainActivity.kt (markers genericised).

    private fun buildChatGptInjectionScript(message: String): String {
        val text = JSONObject.quote(message)
        val inputSelector = JSONObject.quote(CHATGPT_INPUT_SELECTOR)
        val sendSelector = JSONObject.quote(CHATGPT_SEND_BUTTON_SELECTOR)
        return """
            (function() {
              const text = $text;
              const selector = $inputSelector;
              const sendSelector = $sendSelector;
              const result = { ok:false, draftWritten:false, buttonReady:false, sendClicked:false, composerLength:0, reason:"" };
              const isVisible = function(el){ if(!el) return false; const r=el.getBoundingClientRect(); const s=window.getComputedStyle(el); return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none"; };
              const readInput = function(input){ if(input.isContentEditable||input.getAttribute("contenteditable")==="true"){return input.textContent||"";} return input.value||""; };
              const clearInput = function(input){ if(input.isContentEditable||input.getAttribute("contenteditable")==="true"){input.textContent="";} else { const proto=input.tagName==="TEXTAREA"?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; const setter=Object.getOwnPropertyDescriptor(proto,"value").set; setter.call(input,""); } input.dispatchEvent(new InputEvent("input",{bubbles:true,inputType:"deleteContentBackward",data:null})); input.dispatchEvent(new Event("change",{bubbles:true})); };
              const inputs = Array.from(document.querySelectorAll(selector)).filter(function(el){return isVisible(el)&&!el.closest("[aria-hidden='true']");});
              const input = inputs.find(function(el){return el.id==="prompt-textarea"||el.getAttribute("data-testid")==="prompt-textarea";})||inputs[0];
              if(!input){ result.reason="no-input"; return result; }
              input.focus();
              const firstLine = text.split("\n").find(function(line){return line.trim().length>0;})||text;
              const current = readInput(input);
              if(!current.includes(firstLine)||current.length<text.length){
                clearInput(input);
                if(input.isContentEditable||input.getAttribute("contenteditable")==="true"){
                  const sel=window.getSelection(); const range=document.createRange(); range.selectNodeContents(input); sel.removeAllRanges(); sel.addRange(range);
                  let inserted=false; try{inserted=document.execCommand("insertText",false,text);}catch(e){inserted=false;}
                  if(!inserted||!readInput(input).includes(firstLine)){ input.textContent=text; }
                } else {
                  const proto=input.tagName==="TEXTAREA"?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; const setter=Object.getOwnPropertyDescriptor(proto,"value").set; setter.call(input,text);
                }
                input.dispatchEvent(new InputEvent("input",{bubbles:true,inputType:"insertText",data:text})); input.dispatchEvent(new Event("change",{bubbles:true}));
              }
              const written=readInput(input); result.composerLength=written.length; result.draftWritten=written.includes(firstLine);
              const findSendButton=function(){ const direct=Array.from(document.querySelectorAll(sendSelector)); const broad=Array.from(document.querySelectorAll("button")).filter(function(b){const l=[b.getAttribute("aria-label"),b.getAttribute("data-testid"),b.textContent].filter(Boolean).join(" ").toLowerCase();return l.includes("send")||l.includes("submit");}); return direct.concat(broad).find(function(b){return isVisible(b)&&!b.disabled&&b.getAttribute("aria-disabled")!=="true";}); };
              if(!result.draftWritten){ result.reason="draft-missing"; return result; }
              const button=findSendButton(); result.buttonReady=!!button;
              if(!button){ result.reason="send-not-ready"; return result; }
              button.click(); result.ok=true; result.sendClicked=true; result.reason="clicked"; return result;
            })();
        """.trimIndent()
    }

    private fun buildInjectedUserProbeScript(message: String): String {
        val marker = JSONObject.quote(injectedUserProbeMarker(message))
        val inputSelector = JSONObject.quote(CHATGPT_INPUT_SELECTOR)
        return """
            (function() {
              const marker = $marker; const selector = $inputSelector;
              const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
              const readInput=function(input){if(input.isContentEditable||input.getAttribute("contenteditable")==="true"){return input.textContent||"";}return input.value||"";};
              const userNodes=Array.from(document.querySelectorAll("[data-message-author-role='user']"));
              const visibleUsers=userNodes.filter(isVisible);
              const lastUser=visibleUsers[visibleUsers.length-1]||userNodes[userNodes.length-1]||null;
              const userText=lastUser?(lastUser.innerText||lastUser.textContent||"").replace(/\s+/g," ").trim():"";
              const inputs=Array.from(document.querySelectorAll(selector)).filter(isVisible);
              const composerText=inputs.map(readInput).join("\n");
              return { visible: marker.length>0&&userText.includes(marker), composerHasMarker: marker.length>0&&composerText.includes(marker), userMessageCount:userNodes.length, userPreview:userText.slice(0,220) };
            })();
        """.trimIndent()
    }

    private fun injectedUserProbeMarker(message: String): String =
        message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
            ?: message.trim().take(80)

    private fun buildVoiceButtonScript(): String = """
        (function() {
          const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
          const labelOf=function(el){return [el.getAttribute("aria-label"),el.getAttribute("data-testid"),el.getAttribute("title"),el.textContent].filter(Boolean).join(" ").toLowerCase();};
          const score=function(el){ if(!isVisible(el)||el.disabled||el.getAttribute("aria-disabled")==="true")return 0; const label=labelOf(el);
            if(/send|submit|attach|upload|전송|보내기|첨부/.test(label))return 0;
            if(/dictate|dictation|음성 입력/.test(label)&&!/voice|voice mode|음성 모드|음성 대화/.test(label))return 0;
            let p=0; if(/voice mode|start voice|voice chat|voice conversation/.test(label))p+=120; if(/음성 모드|음성 대화|음성으로 대화|대화 시작/.test(label))p+=120; if(/\bvoice\b|음성/.test(label))p+=80; if(/speech/.test(label))p+=60; if(/microphone|\bmic\b|마이크/.test(label))p+=65; return p; };
          const candidates=Array.from(document.querySelectorAll("button, [role='button']")).map(function(el){return {el:el,points:score(el)};}).filter(function(i){return i.points>=60;}).sort(function(a,b){return b.points-a.points;});
          const target=candidates.length?candidates[0].el:null; if(!target)return false; target.click(); return true;
        })();
    """.trimIndent()

    private fun buildVoiceReadyScript(): String {
        val inputSelector = JSONObject.quote(CHATGPT_INPUT_SELECTOR)
        return """
            (function() {
              const inputSelector = $inputSelector;
              const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
              const text=(document.body&&document.body.innerText||"").toLowerCase();
              const visibleComposer=Array.from(document.querySelectorAll(inputSelector)).some(function(el){return isVisible(el)&&!el.closest("[aria-hidden='true']");});
              const labels=Array.from(document.querySelectorAll("button, [role='button']")).filter(isVisible).map(function(el){return [el.getAttribute("aria-label"),el.getAttribute("data-testid"),el.getAttribute("title"),el.textContent].filter(Boolean).join(" ").toLowerCase();}).join("\n");
              const hasReadyText=text.includes("대화를 시작")||text.includes("start speaking")||text.includes("speak now");
              const hasEndButton=/끝내기|end conversation|end voice|leave voice|disconnect/.test(labels);
              const hasVoiceMicControl=/mute|unmute|microphone|\bmic\b|마이크/.test(labels)&&hasEndButton;
              const hasMicError=text.includes("microphone access required")||text.includes("enable microphone access")||text.includes("마이크 액세스")||text.includes("마이크 권한");
              const ready=hasEndButton||hasVoiceMicControl||(hasReadyText&&!visibleComposer);
              return !hasMicError&&ready;
            })();
        """.trimIndent()
    }

    private fun buildVoiceMuteScript(targetMuted: Boolean): String {
        val target = if (targetMuted) "true" else "false"
        return """
            (function() {
              const targetMuted = $target;
              const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
              const labelOf=function(el){return [el.getAttribute("aria-label"),el.getAttribute("data-testid"),el.getAttribute("title"),el.textContent].filter(Boolean).join(" ").toLowerCase();};
              const controls=Array.from(document.querySelectorAll("button, [role='button']")).filter(function(el){const l=labelOf(el);const isMic=/mute|unmute|microphone|\bmic\b|마이크|음소거/.test(l);const isEnd=/end voice|end conversation|끝내기|disconnect|leave voice/.test(l);return isVisible(el)&&isMic&&!isEnd&&!el.disabled&&el.getAttribute("aria-disabled")!=="true";});
              if(!controls.length)return "missing";
              for(const c of controls){ const l=labelOf(c); let cur=null; if(/unmute|turn on|mic on|microphone on|마이크 켜기|음소거 해제/.test(l)){cur=true;} else if(/mute|turn off|mic off|microphone off|마이크 끄기|음소거/.test(l)){cur=false;} if(cur===null)continue; if(cur===targetMuted){return targetMuted?"already-muted":"already-open";} c.click(); return targetMuted?"muted":"open"; }
              return "unknown";
            })();
        """.trimIndent()
    }

    /** Read the current mic control state: "open" | "muted" | "missing" | "unknown". */
    private fun buildMicStateScript(): String = """
        (function() {
          const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
          const labelOf=function(el){return [el.getAttribute("aria-label"),el.getAttribute("data-testid"),el.getAttribute("title"),el.textContent].filter(Boolean).join(" ").toLowerCase();};
          const controls=Array.from(document.querySelectorAll("button, [role='button']")).filter(function(el){const l=labelOf(el);const isMic=/mute|unmute|microphone|\bmic\b|마이크|음소거/.test(l);const isEnd=/end voice|end conversation|끝내기|disconnect|leave voice/.test(l);return isVisible(el)&&isMic&&!isEnd;});
          if(!controls.length)return "missing";
          for(const c of controls){ const l=labelOf(c);
            if(/unmute|turn on|mic on|microphone on|마이크 켜기|음소거 해제/.test(l))return "muted";
            if(/mute|turn off|mic off|microphone off|마이크 끄기|음소거/.test(l))return "open"; }
          return "unknown";
        })();
    """.trimIndent()

    private fun buildAudioDuckScript(ducked: Boolean, targetVolume: Double): String {
        val duck = if (ducked) "true" else "false"
        return """
            (function() {
              const ducked=$duck; const targetVolume=$targetVolume; const key="__seoinDuck";
              const state=window[key]||(window[key]={originals:new WeakMap(),observer:null,originalPlay:null,active:false});
              const isMedia=function(el){return el&&typeof HTMLMediaElement!=="undefined"&&el instanceof HTMLMediaElement;};
              const applyDuck=function(el){if(!isMedia(el))return;if(!state.originals.has(el)){state.originals.set(el,{volume:el.volume,muted:el.muted});}el.muted=false;el.volume=Math.max(0,Math.min(1,targetVolume));};
              const restore=function(el){if(!isMedia(el))return;const o=state.originals.get(el);if(!o)return;el.volume=o.volume;el.muted=o.muted;state.originals.delete(el);};
              if(!state.originalPlay&&typeof HTMLMediaElement!=="undefined"){state.originalPlay=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){if(state.active)applyDuck(this);return state.originalPlay.apply(this,arguments);};}
              const mediaElements=function(){return Array.from(document.querySelectorAll("audio, video"));};
              if(ducked){ state.active=true; mediaElements().forEach(applyDuck); if(!state.observer&&document.documentElement){ state.observer=new MutationObserver(function(ms){ if(!state.active)return; ms.forEach(function(m){ m.addedNodes.forEach(function(n){ if(isMedia(n))applyDuck(n); if(n&&n.querySelectorAll){Array.from(n.querySelectorAll("audio, video")).forEach(applyDuck);} }); }); }); state.observer.observe(document.documentElement,{childList:true,subtree:true}); } return "ducked:"+mediaElements().length; }
              state.active=false; mediaElements().forEach(restore); if(state.observer){state.observer.disconnect();state.observer=null;} return "restored:"+mediaElements().length;
            })();
        """.trimIndent()
    }

    private fun buildMediaPlaybackProbeScript(): String = """
        (function() {
          const now=Date.now(); const key="__seoinMediaProbe"; const AudioCtx=window.AudioContext||window.webkitAudioContext;
          const state=window[key]||(window[key]={entries:new WeakMap(),ctx:null,nodes:[]});
          if(!state.ctx&&AudioCtx){try{state.ctx=new AudioCtx();}catch(e){state.ctx=null;}}
          if(state.ctx&&state.ctx.state==="suspended"){try{state.ctx.resume();}catch(e){}}
          const media=Array.from(document.querySelectorAll("audio, video"));
          const ensureAnalyser=function(el,entry){ if(entry.analyser||entry.probeError||!state.ctx)return; try{ const stream=typeof el.captureStream==="function"?el.captureStream():(typeof el.mozCaptureStream==="function"?el.mozCaptureStream():null); if(!stream||!stream.getAudioTracks||stream.getAudioTracks().length===0){entry.probeError="no-audio-stream";return;} const source=state.ctx.createMediaStreamSource(stream); const analyser=state.ctx.createAnalyser(); analyser.fftSize=512; analyser.smoothingTimeConstant=0.2; source.connect(analyser); entry.source=source; entry.analyser=analyser; entry.buffer=new Uint8Array(analyser.fftSize); state.nodes.push(source); state.nodes.push(analyser); }catch(e){entry.probeError=String(e&&e.message?e.message:e);} };
          const readRms=function(entry){ if(!entry.analyser||!entry.buffer)return 0; entry.analyser.getByteTimeDomainData(entry.buffer); let sum=0; for(let i=0;i<entry.buffer.length;i+=1){const c=(entry.buffer[i]-128)/128;sum+=c*c;} return Math.sqrt(sum/entry.buffer.length); };
          const items=media.map(function(el,index){ let entry=state.entries.get(el); if(!entry){entry={lastTime:Number(el.currentTime)||0,lastAdvanceAt:0,lastAudioAt:0};state.entries.set(el,entry);} ensureAnalyser(el,entry); const currentTime=Number(el.currentTime)||0; const advanced=currentTime>entry.lastTime+0.03; if(advanced)entry.lastAdvanceAt=now; const playing=!el.paused&&!el.ended&&!el.muted&&Number(el.volume)>0; const rms=readRms(entry); const audioActive=rms>=0.0025; if(audioActive)entry.lastAudioAt=now; entry.lastTime=currentTime; const recentMs=entry.lastAudioAt>0?now-entry.lastAudioAt:999999; return {index:index,hasProbe:!!entry.analyser,playing:playing,recent:recentMs<$MEDIA_AUDIO_RECENT_MS,recentMs:recentMs}; });
          const hasProbe=items.some(function(i){return i.hasProbe;});
          // Speaking = an element that is BOTH recently loud AND currently playing.
          // When GPT stops, the element pauses → recentActive drops immediately, so
          // "설명 중" doesn't linger after the voice actually finishes (피드백).
          const recentActive=items.some(function(i){return i.hasProbe&&i.recent&&i.playing;});
          const vlabels=Array.from(document.querySelectorAll("button, [role='button']")).map(function(b){return [b.getAttribute("aria-label"),b.getAttribute("title"),b.textContent].filter(Boolean).join(" ").toLowerCase();}).join("\n");
          const voiceOpen=/끝내기|end voice|end conversation|leave voice|disconnect/.test(vlabels);
          return { count:media.length, hasProbe:hasProbe, recentActive:recentActive, voiceOpen:voiceOpen };
        })();
    """.trimIndent()

    private fun buildMediaProbeCleanupScript(): String = """
        (function() {
          const key="__seoinMediaProbe"; const state=window[key]; if(!state)return false;
          try{ (state.nodes||[]).forEach(function(n){try{if(n&&n.disconnect)n.disconnect();}catch(e){}}); if(state.ctx&&state.ctx.close){try{state.ctx.close();}catch(e){}} }catch(e){}
          delete window[key]; return true;
        })();
    """.trimIndent()

    private fun buildResponseProbeScript(expectedQuestion: String?): String {
        val expected = expectedQuestion?.let { JSONObject.quote(it) } ?: "null"
        return """
            (function() {
              const expected=$expected;
              const assistantNodes=Array.from(document.querySelectorAll("[data-message-author-role='assistant']"));
              const lastAssistant=assistantNodes.length?assistantNodes[assistantNodes.length-1]:null;
              const assistantText=lastAssistant?(lastAssistant.innerText||"").trim():"";
              const bodyText=(document.body&&document.body.innerText||"").trim();
              const labels=Array.from(document.querySelectorAll("button, [role='button']")).map(function(el){return [el.getAttribute("aria-label"),el.getAttribute("data-testid"),el.getAttribute("title"),el.textContent].filter(Boolean).join(" ").toLowerCase();}).join("\n");
              const busy=/stop generating|stop streaming|generating|응답 중지|생성 중지|중지 생성/.test(labels)||/typing|thinking|생각 중/.test(bodyText.toLowerCase());
              const sentenceComplete=/[.!?。！？…]["'”’)\]]*${'$'}/.test(assistantText);
              return { assistantText:assistantText, expectedFound: expected?assistantText.includes(expected):false, busy:busy, sentenceComplete:sentenceComplete };
            })();
        """.trimIndent()
    }

    /** Push a real keystroke into the composer to fully arm voice mode (2-phase). */
    private fun buildKeyboardNudgeScript(): String {
        val inputSelector = JSONObject.quote(CHATGPT_INPUT_SELECTOR)
        return """
            (function() {
              const selector=$inputSelector;
              const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
              const input=Array.from(document.querySelectorAll(selector)).find(function(el){return isVisible(el)&&!el.closest("[aria-hidden='true']");});
              if(!input)return "no-input";
              input.focus();
              const dispatchKey=function(type,key){ input.dispatchEvent(new KeyboardEvent(type,{key:key,code:key===" "?"Space":key,bubbles:true})); };
              dispatchKey("keydown"," "); dispatchKey("keypress"," ");
              input.dispatchEvent(new InputEvent("input",{bubbles:true,inputType:"insertText",data:" "}));
              dispatchKey("keyup"," ");
              // remove the nudge character so we don't send a stray space
              if(input.isContentEditable||input.getAttribute("contenteditable")==="true"){input.textContent="";}else{input.value="";}
              input.dispatchEvent(new InputEvent("input",{bubbles:true,inputType:"deleteContentBackward",data:null}));
              return "nudged";
            })();
        """.trimIndent()
    }

    /**
     * Diagnostics dump for collaborative debugging — surfaces what the voice-mode
     * DOM actually looks like so we can fix the mic control + speaker detection
     * from real data instead of guessing. Logged to logcat tag [TAG].
     */
    private fun buildVoiceDiagnosticsScript(): String = """
        (function() {
          const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
          const labelOf=function(el){return [el.getAttribute("aria-label"),el.getAttribute("data-testid"),el.getAttribute("title"),el.getAttribute("aria-pressed"),el.textContent].filter(Boolean).join(" | ").slice(0,80);};
          const buttons=Array.from(document.querySelectorAll("button, [role='button']")).filter(isVisible);
          const buttonLabels=buttons.map(labelOf);
          const micRe=/mute|unmute|microphone|\bmic\b|마이크|음소거/i;
          const micCandidates=buttons.filter(function(b){return micRe.test(labelOf(b));}).map(function(b){return {label:labelOf(b),pressed:b.getAttribute("aria-pressed"),dataState:b.getAttribute("data-state")};});
          const media=Array.from(document.querySelectorAll("audio, video"));
          const AudioCtx=window.AudioContext||window.webkitAudioContext;
          // any element that looks like it carries a speaking/listening state
          const stateNodes=Array.from(document.querySelectorAll("[data-state], [aria-live], [class*='speaking'], [class*='listening']")).filter(isVisible).slice(0,12).map(function(el){return (el.getAttribute("data-state")||el.getAttribute("aria-live")||el.className||"").toString().slice(0,60);});
          return {
            buttonCount: buttons.length,
            buttonLabels: buttonLabels.slice(0,24),
            micCandidates: micCandidates,
            mediaCount: media.length,
            mediaInfo: media.slice(0,4).map(function(m){return {tag:(m.tagName||"").toLowerCase(),paused:!!m.paused,muted:!!m.muted,readyState:m.readyState,hasCapture:typeof m.captureStream==="function"};}),
            hasAudioCtx: !!AudioCtx,
            stateNodes: stateNodes
          };
        })();
    """.trimIndent()

    private suspend fun logDiagnostics(where: String) {
        val obj = evalObj(buildVoiceDiagnosticsScript()) ?: run { Log.d(TAG, "DIAG[$where] (null)"); return }
        Log.d(TAG, "DIAG[$where] buttons=${obj.optInt("buttonCount")} media=${obj.optInt("mediaCount")} audioCtx=${obj.optBoolean("hasAudioCtx")}")
        Log.d(TAG, "DIAG[$where] micCandidates=${obj.optJSONArray("micCandidates")}")
        Log.d(TAG, "DIAG[$where] stateNodes=${obj.optJSONArray("stateNodes")}")
        Log.d(TAG, "DIAG[$where] mediaInfo=${obj.optJSONArray("mediaInfo")}")
        Log.d(TAG, "DIAG[$where] buttonLabels=${obj.optJSONArray("buttonLabels")}")
    }

    /** Best-effort conversation rename. Fragile DOM — manual sidebar is the fallback. */
    private fun buildRenameConversationScript(name: String): String {
        val title = JSONObject.quote(name)
        return """
            (function() {
              const name=$title;
              try { document.title = name; } catch(e) {}
              // Real rename requires opening the sidebar conversation menu, which the
              // teacher does manually from the panel. We only set the tab title here.
              return "title-set";
            })();
        """.trimIndent()
    }

    private companion object {
        const val TAG = "SeoinVoice"
        const val CHATGPT_URL = "https://chatgpt.com/"
        const val CHATGPT_INPUT_SELECTOR =
            "#prompt-textarea, [data-testid='prompt-textarea'], textarea, div[contenteditable='true'], [contenteditable='true'], .ProseMirror"
        const val CHATGPT_SEND_BUTTON_SELECTOR =
            "button[data-testid='send-button'], button[data-testid='composer-submit-button'], button[data-testid='composer-send-button'], button[aria-label='Send prompt'], button[aria-label='Send message'], button[aria-label='Send']"
        const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        const val VOICE_CONFIRM_TIMEOUT_MS = 20_000L
        const val VOICE_CONFIRM_POLL_MS = 700L
        const val INJECT_SEND_POLL_MS = 75L
        const val INJECT_SEND_ATTEMPTS = 12
        const val INJECT_USER_VISIBLE_TIMEOUT_MS = 6_000L
        const val INJECT_USER_VISIBLE_POLL_MS = 100L
        const val RESPONSE_TIMEOUT_MS = 60_000L
        const val RESPONSE_POLL_MS = 1_000L
        const val RESPONSE_STABLE_POLLS = 2
        const val COMPLETION_PUNCTUATION_SETTLE_MS = 500L
        const val MEDIA_AUDIO_GATE_POLL_MS = 100L
        // How long to wait for the speaker to *start* after the text completes.
        const val MEDIA_AUDIO_START_WAIT_MS = 6_000L
        // Consecutive quiet polls required to call the explanation finished. GPT
        // audio rarely pauses, so keep this short: 2 × 150ms = 300ms (피드백).
        const val QUIET_STREAK_POLLS = 2
        const val MEDIA_AUDIO_RECENT_MS = 180L
        const val MEDIA_AUDIO_GATE_MAX_WAIT_MS = 45_000L
        // Combined turn loop (text + audio in one poll).
        const val TURN_POLL_MS = 150L
        const val TURN_TEXT_STABLE_POLLS = 3
        const val KEYBOARD_NUDGE_SETTLE_MS = 600L
        const val TRANSCRIPT_TAIL = 80
    }
}
