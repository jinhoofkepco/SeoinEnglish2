package com.seoin.emojienglish.voice

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 그림창 엔진 — 음성 WebView와 **별개의** 단일 ChatGPT WebView(@Singleton).
 * 음성 게이트웨이([WebViewVoiceGateway])는 매우 민감하므로 건드리지 않고, 텍스트
 * 주입(prompt → 전송 클릭)만 독립적으로 복제했다. 보이스 모드는 쓰지 않는다(일반 채팅).
 *
 * 쿠키는 안드로이드 `CookieManager`가 앱 내 모든 WebView에서 공유하므로, 음성
 * WebView에서 로그인돼 있으면 이 WebView도 로그인 상태를 그대로 쓴다.
 */
@Singleton
class WebViewPictureGateway @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : PictureController {

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PictureState())
    override val state: StateFlow<PictureState> = _state.asStateFlow()

    private var webView: WebView? = null
    @Volatile private var pageLoaded = false

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
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { pageLoaded = true }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "renderer gone didCrash=${detail?.didCrash()} — dropping picture webview")
                if (view === webView) {
                    webView = null
                    pageLoaded = false
                }
                _state.value = _state.value.copy(visible = false)
                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                view?.destroy()
                return true
            }
        }
        wv.loadUrl(CHATGPT_URL)
        webView = wv
        return wv
    }

    override fun provideView(): WebView = ensureWebView()

    override fun addWord(word: PictureWord) {
        ensureWebView()
        val merged = (_state.value.words + word).distinctBy { it.id }
        _state.value = _state.value.copy(visible = true, words = merged)
    }

    override fun open() {
        ensureWebView()
        _state.value = _state.value.copy(visible = true)
    }

    override fun close() {
        // 닫으면 칩도 비운다 — 다음에 열 땐 다시 단어를 골라 추가한다.
        _state.value = _state.value.copy(visible = false, words = emptyList())
    }

    override fun toggle() {
        if (_state.value.visible) close() else open()
    }

    override fun requestPicture(word: PictureWord) {
        main.launch {
            ensureWebView()
            val ok = send(word.prompt)
            Log.d(TAG, "requestPicture \"${word.label}\" sent=$ok")
        }
    }

    // ------------------------------------------------------------ send + inject

    /** Inject [message] into the composer and click send (no bubble confirm needed). */
    private suspend fun send(message: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val loadDeadline = now() + PAGE_LOAD_TIMEOUT_MS
        while (!pageLoaded && now() < loadDeadline) delay(150)
        var clicked = false
        var attempts = INJECT_SEND_ATTEMPTS
        while (attempts-- > 0 && !clicked) {
            val res = evalObj(buildInjectionScript(message))
            clicked = res?.optBoolean("sendClicked") == true
            if (!clicked) delay(INJECT_SEND_POLL_MS)
        }
        if (!clicked) Log.w(TAG, "send: never clicked for \"${message.take(40)}\"")
        clicked
    }

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main.immediate) {
        val wv = ensureWebView()
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result ?: "null") }
        }
    }

    private suspend fun evalObj(js: String): JSONObject? = runCatching {
        val raw = eval(js)
        if (raw.isBlank() || raw == "null") null else JSONObject(raw)
    }.getOrNull()

    private fun now() = System.currentTimeMillis()

    /** Ported verbatim from [WebViewVoiceGateway] (injection only, self-contained). */
    private fun buildInjectionScript(message: String): String {
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

    private companion object {
        const val TAG = "SeoinPicture"
        const val CHATGPT_URL = "https://chatgpt.com/"
        const val CHATGPT_INPUT_SELECTOR =
            "#prompt-textarea, [data-testid='prompt-textarea'], textarea, div[contenteditable='true'], [contenteditable='true'], .ProseMirror"
        const val CHATGPT_SEND_BUTTON_SELECTOR =
            "button[data-testid='send-button'], button[data-testid='composer-submit-button'], button[data-testid='composer-send-button'], button[aria-label='Send prompt'], button[aria-label='Send message'], button[aria-label='Send']"
        const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        const val INJECT_SEND_ATTEMPTS = 12
        const val INJECT_SEND_POLL_MS = 120L
    }
}
