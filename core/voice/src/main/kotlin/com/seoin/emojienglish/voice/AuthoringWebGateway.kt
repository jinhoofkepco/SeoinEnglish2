package com.seoin.emojienglish.voice

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class AuthoringQueryFailureReason {
    SEND_FAILED,
    RENDERER_GONE,
    RESPONSE_NOT_STARTED,
    RESPONSE_TIMEOUT,
}

/**
 * 마스터 저작 도구 전용 ChatGPT WebView(@Singleton) — 음성/그림 웹뷰와 **별개**.
 * 프롬프트를 주입해 보내고 **응답 텍스트를 읽어 돌려준다**([query]). 음성 게이트웨이는
 * 매우 민감하므로 손대지 않고, 주입/응답 프로빙 JS만 독립 복제했다(보이스 모드 미사용).
 *
 * 쿠키는 `CookieManager`로 공유되므로 로그인은 다른 웹뷰와 함께 유지된다.
 */
@Singleton
class AuthoringWebGateway @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    private var webView: WebView? = null
    @Volatile private var pageLoaded = false
    /** 파일 선택창 폴백용 사진 URI(보통은 JS 주입을 쓴다). */
    @Volatile private var pendingUploadUri: Uri? = null
    /** 고른 사진 URI들 — attachImages 때 base64 data URL로 읽어 JS 주입한다. */
    @Volatile private var pendingPhotoUris: List<Uri> = emptyList()
    @Volatile private var pendingImageDataUrls: List<String> = emptyList()
    /** JS가 캡처한 생성 이미지 base64를 비동기로 돌려줄 통로. */
    @Volatile private var captureDeferred: CompletableDeferred<String?>? = null
    private val textCaptureLock = Any()
    @Volatile private var textCaptureDeferred: CompletableDeferred<String?>? = null
    @Volatile private var textCaptureId: String? = null
    private var textCaptureExpected = 0
    private val textCaptureChunks = mutableMapOf<Int, String>()
    @Volatile private var renderEpoch = 0
    @Volatile private var lastQueryFailure: AuthoringQueryFailureReason? = null
    @Volatile private var lastQueryFailureDetail: String = ""
    @Volatile private var fileChooserOpenCount = 0

    /** 렌더러가 죽어 웹뷰를 새로 만들 때마다 증가 — 화면이 AndroidView를 다시 붙이게 한다. */
    private val _reloads = MutableStateFlow(0)
    val reloads: StateFlow<Int> = _reloads.asStateFlow()

    fun lastQueryFailureMessage(action: String): String {
        val reason = lastQueryFailure
        val message = when (reason) {
            AuthoringQueryFailureReason.SEND_FAILED ->
                "$action 중 ChatGPT에 프롬프트 전송 확인이 안 됐습니다. 아래 ChatGPT 창의 입력창/로그인 상태를 확인하고 다시 시도하세요."
            AuthoringQueryFailureReason.RENDERER_GONE ->
                "$action 중 ChatGPT WebView가 재시작됐습니다. 앱은 살아 있으니 다시 시도하세요."
            AuthoringQueryFailureReason.RESPONSE_NOT_STARTED ->
                "$action 중 ChatGPT가 답변 생성을 시작하지 않았습니다. 자동 재시도 후에도 응답이 없어 멈췄습니다."
            AuthoringQueryFailureReason.RESPONSE_TIMEOUT ->
                "$action 중 ChatGPT 응답을 120초 안에 읽지 못했습니다. 아래 ChatGPT 창이 답변 중인지, 초기 화면으로 돌아갔는지 확인하고 다시 시도하세요."
            null ->
                "$action 중 ChatGPT 응답을 가져오지 못했습니다. 아래 ChatGPT 창 상태를 확인하고 다시 시도하세요."
        }
        val detail = lastQueryFailureDetail
        if (detail.isNotBlank()) Log.w(TAG, "last query failure reason=$reason detail=$detail")
        return message
    }

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
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowContentAccess = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                installSingleWindowGuard(view)
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // 렌더러가 죽어도 앱이 같이 죽지 않게 우리가 처리(true). 웹뷰는 버리고 다음에 재생성.
                Log.w(TAG, "renderer gone didCrash=${detail?.didCrash()} — dropping authoring webview")
                dropWebView(
                    view = view,
                    reason = "renderer-gone",
                    failure = AuthoringQueryFailureReason.RENDERER_GONE,
                    detail = "didCrash=${detail?.didCrash()}",
                )
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val uris = pendingPhotoUris.ifEmpty { pendingUploadUri?.let { listOf(it) }.orEmpty() }
                fileChooserOpenCount += 1
                Log.d(
                    TAG,
                    "onShowFileChooser fired count=${uris.size} mode=${fileChooserParams?.mode} accept=${fileChooserParams?.acceptTypes?.joinToString()}",
                )
                return if (uris.isNotEmpty()) {
                    filePathCallback?.onReceiveValue(uris.toTypedArray()); true
                } else {
                    // 무장된 사진이 없으면 평소대로 시스템 선택을 거부(취소).
                    filePathCallback?.onReceiveValue(null); true
                }
            }
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                Log.d(TAG, "blocked authoring WebView new window isDialog=$isDialog gesture=$isUserGesture")
                return false
            }
        }
        // JS → Kotlin 브릿지: 주입할 사진을 base64 data URL 로 건넨다.
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun getPendingImage(): String = pendingImageDataUrls.firstOrNull().orEmpty()

            @JavascriptInterface
            fun getPendingImages(): String = org.json.JSONArray(pendingImageDataUrls).toString()

            /** JS가 생성 이미지를 base64 data URL로 캡처해 돌려준다(비동기 완료). */
            @JavascriptInterface
            fun onImageCaptured(dataUrl: String) {
                Log.d(TAG, "onImageCaptured len=${dataUrl.length}")
                captureDeferred?.complete(dataUrl.ifBlank { null })
            }

            @JavascriptInterface
            fun onTextCaptureStart(id: String, total: Int) {
                synchronized(textCaptureLock) {
                    if (id != textCaptureId) return
                    textCaptureExpected = total.coerceAtLeast(0)
                    textCaptureChunks.clear()
                    if (textCaptureExpected == 0) {
                        textCaptureDeferred?.complete(null)
                    }
                }
            }

            @JavascriptInterface
            fun onTextChunk(id: String, index: Int, total: Int, chunk: String) {
                val completed: String? = synchronized(textCaptureLock) {
                    if (id != textCaptureId) return
                    textCaptureExpected = total.coerceAtLeast(0)
                    if (index in 0 until textCaptureExpected) textCaptureChunks[index] = chunk
                    if (textCaptureExpected > 0 && textCaptureChunks.size >= textCaptureExpected) {
                        (0 until textCaptureExpected).joinToString("") { textCaptureChunks[it].orEmpty() }
                    } else {
                        null
                    }
                }
                if (completed != null) {
                    Log.d(TAG, "onTextCapture complete len=${completed.length}")
                    textCaptureDeferred?.complete(completed)
                }
            }

            @JavascriptInterface
            fun onTextCaptureError(id: String, message: String) {
                if (id != textCaptureId) return
                Log.d(TAG, "onTextCaptureError $message")
                textCaptureDeferred?.complete(null)
            }
        }, "SeoinBridge")
        wv.loadUrl(CHATGPT_URL)
        webView = wv
        return wv
    }

    fun provideView(): WebView = ensureWebView()

    /**
     * 저작 자동화는 긴 사진/JSON 요청을 연달아 보내므로 ChatGPT DOM/V8 힙이 누적될 수 있다.
     * 쿠키와 로그인 저장소는 건드리지 않고 WebView 인스턴스만 버려 다음 배치를 새 렌더러에서 시작한다.
     */
    suspend fun recycleForAuthoringBatch(reason: String) = withContext(Dispatchers.Main.immediate) {
        if (webView == null) {
            clearTransientState(clearPhotos = true)
            return@withContext
        }
        dropWebView(view = webView, reason = "recycle:$reason")
    }

    /** 고른 사진을 기억한다(실제 주입은 [attachImage]에서 base64로 읽어 JS 주입). */
    fun armPhoto(uri: Uri?) {
        armPhotos(uri?.let { listOf(it) }.orEmpty())
    }

    fun armPhotos(uris: List<Uri>) {
        ensureWebView()
        pendingPhotoUris = uris
        pendingUploadUri = uris.firstOrNull()          // 파일창 폴백은 첫 장만
        pendingImageDataUrls = emptyList()             // 새로 읽도록
        Log.d(TAG, "armPhotos count=${uris.size}")
    }

    /**
     * 사진을 **파일 선택창 없이** 작성창에 첨부 — 사용자 제스처가 필요한 file chooser를
     * 피해, JS로 `input.files` 세팅 + change/paste 디스패치한다(웹 검색에서 검증된 기법).
     * base64 data URL은 [getPendingImage] 브릿지로 건넨다.
     */
    suspend fun attachImage(): String = withContext(Dispatchers.Main.immediate) {
        attachImages()
    }

    suspend fun attachImages(): String = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        val deadline = now() + PAGE_LOAD_TIMEOUT_MS
        while (!pageLoaded && now() < deadline) delay(150)
        val uris = pendingPhotoUris
        if (uris.isEmpty()) return@withContext "no-uri"
        if (pendingImageDataUrls.size != uris.size) {
            pendingImageDataUrls = withContext(Dispatchers.IO) {
                uris.mapNotNull { readDataUrl(it) }
            }
        }
        if (pendingImageDataUrls.size != uris.size) return@withContext "read-fail files=${pendingImageDataUrls.size}/${uris.size}"

        val expected = pendingImageDataUrls.size
        val attachDeadline = now() + ATTACH_READY_TIMEOUT_MS
        val chooserBefore = fileChooserOpenCount
        var attempt = 0
        var last = "not-started"
        while (now() < attachDeadline) {
            val probe = evalObj(buildAttachmentProbeScript())
            val currentAttached = probe?.optInt("attachedCount") ?: 0
            if (currentAttached >= expected) {
                clearPendingPhotos()
                val ok = "ok files=$expected attached=$currentAttached confirmed=true already=true"
                Log.d(TAG, "attachImages count=$expected -> $ok")
                return@withContext ok
            }
            val hasEditor = probe?.optBoolean("hasEditor") == true
            val hasInput = probe?.optBoolean("hasInput") == true
            // 검증된 첨부 경로(paste)는 ProseMirror **에디터가 mount돼야** 동작한다.
            // input만 있고 editor가 없을 때 진행하면 input.files 폴백으로 빠져 attached=0 이 된다.
            if (!hasEditor) {
                last = "waiting-editor editor=$hasEditor input=$hasInput attached=$currentAttached"
                delay(ATTACH_POLL_MS)
                continue
            }

            attempt += 1
            val result = evalObj(buildAttachImagesScript())
            val fileCount = result?.optInt("fileCount") ?: 0
            val usedPaste = result?.optBoolean("usedPaste") == true
            val usedInput = result?.optBoolean("usedInput") == true
            val attachedNow = result?.optInt("attachedCount") ?: currentAttached
            val error = result?.optString("error").orEmpty()
            last = "attempt=$attempt files=$fileCount paste=$usedPaste input=$usedInput attached=$attachedNow error=$error"
            Log.d(TAG, "attachImages $last")

            if (usedPaste) {
                // **첨부 confirm** — 썸네일(attachedCount)이 expected만큼 뜰 때까지 폴링.
                // 안 뜨면 사진을 보존하고 다음 시도로(루프) — 빈손 전송을 막는다.
                var confirmed = 0
                val confirmDeadline = now() + ATTACH_CONFIRM_TIMEOUT_MS
                while (now() < confirmDeadline) {
                    confirmed = evalObj(buildAttachmentProbeScript())?.optInt("attachedCount") ?: 0
                    if (confirmed >= expected) break
                    delay(ATTACH_POLL_MS)
                }
                if (confirmed >= expected) {
                    clearPendingPhotos()
                    val ok = "ok files=$expected paste=$usedPaste attached=$confirmed confirmed=true"
                    Log.d(TAG, "attachImages count=$expected -> $ok")
                    return@withContext ok
                }
                last = "attempt=$attempt unconfirmed paste=$usedPaste attached=$confirmed/$expected"
                Log.d(TAG, "attachImages $last")
                delay(ATTACH_POLL_MS)
                continue
            }

            delay(ATTACH_POLL_MS)
        }

        val manualDeadline = now() + ATTACH_MANUAL_CHOOSER_TIMEOUT_MS
        var manualLastLog = 0L
        var sawChooser = fileChooserOpenCount > chooserBefore
        while (now() < manualDeadline) {
            val confirmed = evalObj(buildAttachmentProbeScript())?.optInt("attachedCount") ?: 0
            sawChooser = sawChooser || fileChooserOpenCount > chooserBefore
            if (confirmed >= expected) {
                clearPendingPhotos()
                val ok = "ok files=$expected manual=true chooser=$sawChooser attached=$confirmed confirmed=true"
                Log.d(TAG, "attachImages count=$expected -> $ok")
                return@withContext ok
            }
            val shouldLog = sawChooser || now() - manualLastLog >= ATTACH_MANUAL_LOG_MS
            if (shouldLog) {
                manualLastLog = now()
                last = "waiting-manual chooser=$sawChooser attached=$confirmed/$expected"
                Log.d(TAG, "attachImages $last")
            }
            delay(ATTACH_POLL_MS)
        }

        val fail = "attach-fail files=$expected $last"
        Log.w(TAG, "attachImages count=$expected -> $fail")
        fail
    }

    /**
     * 마지막 어시스턴트 메시지의 생성 이미지를 **앱이 직접 가져온다** — JS가 `<img>`를
     * fetch→blob→base64 로 읽어 [onImageCaptured] 콜백으로 돌려준다(blob: URL이라
     * same-origin fetch 가능). null이면 못 찾음/CORS 실패.
     */
    suspend fun captureLastImageDataUrl(minImageCount: Int = 0): String? = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        val d = CompletableDeferred<String?>()
        captureDeferred = d
        eval(buildCaptureScript(minImageCount))
        val r = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { d.await() }
        captureDeferred = null
        Log.d(TAG, "captureLastImageDataUrl -> ${if (r.isNullOrBlank()) "null" else "len=${r.length}"}")
        r
    }

    suspend fun largeAssistantImageCount(): Int = withContext(Dispatchers.Main.immediate) {
        evalObj(
            """
            (function(){
              const imgs=Array.from(document.querySelectorAll("[data-message-author-role='assistant'] img, main img"))
                .filter(function(i){return (i.naturalWidth||i.width)>=200;});
              const canvases=Array.from(document.querySelectorAll("main canvas"))
                .filter(function(c){return (c.width||0)>=200 && (c.height||0)>=200;});
              return { count: imgs.length + canvases.length };
            })();
            """.trimIndent(),
        )?.optInt("count") ?: 0
    }

    /**
     * 어시스턴트가 방금 만든 **다운로드 가능한 파일 링크**(JSON/이미지)를 fetch→base64로
     * 가져온다 — 큰 내용을 DOM에 인라인 렌더하지 않게 해 메모리/크래시를 피하는 경로.
     * 못 찾으면 null. (data URL의 mime 으로 JSON/이미지 구분)
     */
    suspend fun captureLastDownloadDataUrl(): String? = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        val d = CompletableDeferred<String?>()
        captureDeferred = d
        eval(buildDownloadCaptureScript())
        val r = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { d.await() }
        captureDeferred = null
        Log.d(TAG, "captureLastDownloadDataUrl -> ${if (r.isNullOrBlank()) "null" else "len=${r.length}"}")
        r
    }

    /**
     * 마지막 어시스턴트 메시지에서 JSON만 골라 JS 브릿지로 작은 조각들로 받는다.
     * 큰 문자열을 evaluateJavascript 결과로 직접 넘기지 않아 WebView/Binder 부담을 줄인다.
     */
    suspend fun captureLastAssistantJsonChunked(minAssistantCount: Int = 0): String? =
        withContext(Dispatchers.Main.immediate) {
            ensureWebView()
            val id = "json-${now()}"
            val d = CompletableDeferred<String?>()
            synchronized(textCaptureLock) {
                textCaptureId = id
                textCaptureExpected = 0
                textCaptureChunks.clear()
                textCaptureDeferred = d
            }
            val start = eval(buildJsonChunkCaptureScript(id, minAssistantCount))
            Log.d(TAG, "captureLastAssistantJsonChunked start=$start")
            val r = withTimeoutOrNull(TEXT_CAPTURE_TIMEOUT_MS) { d.await() }
            synchronized(textCaptureLock) {
                if (textCaptureId == id) {
                    textCaptureId = null
                    textCaptureExpected = 0
                    textCaptureChunks.clear()
                    textCaptureDeferred = null
                }
            }
            r
        }

    suspend fun assistantMessageCount(): Int = withContext(Dispatchers.Main.immediate) {
        evalObj(
            """
            (function(){
              return { count: document.querySelectorAll("[data-message-author-role='assistant']").length };
            })();
            """.trimIndent(),
        )?.optInt("count") ?: 0
    }

    private fun buildJsonChunkCaptureScript(id: String, minAssistantCount: Int): String {
        val quotedId = JSONObject.quote(id)
        return """
            (function() {
              const id=$quotedId;
              const minCount=$minAssistantCount;
              const bridge=window.SeoinBridge;
              const fail=function(msg){
                try{ bridge.onTextCaptureError(id, String(msg)); }catch(_){}
                return String(msg);
              };
              try {
                if(!bridge || !bridge.onTextChunk) return "no-bridge";
                const assistants=Array.from(document.querySelectorAll("[data-message-author-role='assistant']"));
                if(assistants.length < minCount) return fail("waiting-new-assistant");
                const last=assistants[assistants.length-1];
                if(!last) return fail("no-assistant");
                const candidates=Array.from(last.querySelectorAll("pre code, pre, code"))
                  .map(function(el){return el.innerText || el.textContent || "";})
                  .filter(function(t){return t.trim().length>0;});
                candidates.push(last.innerText || last.textContent || "");

                const balancedJson=function(raw) {
                  const text=String(raw||"")
                    .replace(/```json/gi, "```")
                    .replace(/```/g, "")
                    .trim();
                  let best=-1;
                  for(let i=0;i<text.length;i++){
                    const ch=text[i];
                    if(ch!=="{" && ch!=="[") continue;
                    const close=ch==="{" ? "}" : "]";
                    const stack=[close];
                    let inStr=false, esc=false;
                    for(let j=i+1;j<text.length;j++){
                      const c=text[j];
                      if(inStr){
                        if(esc){ esc=false; continue; }
                        if(c==="\\"){ esc=true; continue; }
                        if(c==="\"") inStr=false;
                        continue;
                      }
                      if(c==="\""){ inStr=true; continue; }
                      if(c==="{" ) stack.push("}");
                      else if(c==="[") stack.push("]");
                      else if(c==="}" || c==="]"){
                        if(stack.length===0 || c!==stack[stack.length-1]) break;
                        stack.pop();
                        if(stack.length===0) return text.slice(i, j+1);
                      }
                    }
                    if(best<0) best=i;
                  }
                  return "";
                };

                let json="";
                for(const c of candidates){
                  json=balancedJson(c);
                  if(json) break;
                }
                if(!json) return fail("no-json");
                const chunkSize=8192;
                const total=Math.max(1, Math.ceil(json.length / chunkSize));
                bridge.onTextCaptureStart(id, total);
                for(let i=0;i<total;i++){
                  bridge.onTextChunk(id, i, total, json.slice(i*chunkSize, (i+1)*chunkSize));
                }
                return "chunking:"+total;
              } catch(e) {
                return fail("err:"+String(e));
              }
            })();
        """.trimIndent()
    }

    private fun installSingleWindowGuard(view: WebView?) {
        view?.evaluateJavascript(SINGLE_WINDOW_GUARD_JS, null)
    }

    private fun buildDownloadCaptureScript(): String = """
        (function() {
          try {
            const anchors=Array.from(document.querySelectorAll("a[href]")).filter(function(a){
              const h=a.getAttribute("href")||"";
              return h.startsWith("blob:")||h.startsWith("data:")||/\/mnt\/data|sandbox:|files|download/.test(h)||a.hasAttribute("download");
            });
            const a=anchors[anchors.length-1];
            if(!a){ SeoinBridge.onImageCaptured(""); return "no-link"; }
            fetch(a.href).then(function(r){return r.blob();}).then(function(blob){
              const fr=new FileReader();
              fr.onload=function(){ SeoinBridge.onImageCaptured(String(fr.result||"")); };
              fr.onerror=function(){ SeoinBridge.onImageCaptured(""); };
              fr.readAsDataURL(blob);
            }).catch(function(e){ SeoinBridge.onImageCaptured(""); });
            return "fetching";
          } catch(e){ try{SeoinBridge.onImageCaptured("");}catch(_){} return "err:"+String(e); }
        })();
    """.trimIndent()

    private fun buildCaptureScript(minImageCount: Int): String = """
        (function() {
          try {
            const minCount=$minImageCount;
            const seen=new Set();
            const imgs=Array.from(document.querySelectorAll(
              "[data-message-author-role='assistant'] img, main img, img[alt*='Generated'], img[src^='blob:'], img[src^='data:']"
            )).filter(function(i){
              const src=i.currentSrc||i.src||"";
              if(!src || seen.has(src)) return false;
              seen.add(src);
              const r=i.getBoundingClientRect();
              const w=i.naturalWidth||r.width||i.width||0;
              const h=i.naturalHeight||r.height||i.height||0;
              return w>=120 && h>=120;
            });
            const canvases=Array.from(document.querySelectorAll("main canvas")).filter(function(c){
              const r=c.getBoundingClientRect();
              return (c.width||r.width)>=200 && (c.height||r.height)>=200;
            });
            const total=imgs.length+canvases.length;
            if(total < minCount){ SeoinBridge.onImageCaptured(""); return "waiting-img total="+total+" min="+minCount; }
            const canvas=canvases.slice(-1)[0];
            if(canvas){
              try {
                SeoinBridge.onImageCaptured(canvas.toDataURL("image/png"));
                return "canvas";
              } catch(e) {}
            }
            const img=imgs.slice(-1)[0];
            if(!img){ SeoinBridge.onImageCaptured(""); return "no-img total="+total; }
            fetch(img.src).then(function(r){return r.blob();}).then(function(blob){
              const fr=new FileReader();
              fr.onload=function(){ SeoinBridge.onImageCaptured(String(fr.result||"")); };
              fr.onerror=function(){ SeoinBridge.onImageCaptured(""); };
              fr.readAsDataURL(blob);
            }).catch(function(e){ SeoinBridge.onImageCaptured(""); });
            return "fetching";
          } catch(e){ try{SeoinBridge.onImageCaptured("");}catch(_){} return "err:"+String(e); }
        })();
    """.trimIndent()

    private fun readDataUrl(uri: Uri): String? = runCatching {
        val mime = appContext.contentResolver.getType(uri) ?: "image/png"
        val bytes = appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /** Inject the armed images into the composer via input.files + change + paste. */
    private fun buildAttachImageScript(): String = """
        ${buildAttachImagesScript()}
    """.trimIndent()

    private fun clearPendingPhotos() {
        pendingUploadUri = null
        pendingPhotoUris = emptyList()
        pendingImageDataUrls = emptyList()
    }

    private fun buildAttachmentProbeScript(): String = """
        (function() {
          try {
            const isVisible=function(el){
              if(!el) return false;
              const s=getComputedStyle(el);
              const r=el.getBoundingClientRect();
              return s.visibility!=="hidden" && s.display!=="none" && r.width>1 && r.height>1 && !el.closest("[aria-hidden='true']");
            };
            const pickEditor=function(){
              const candidates=Array.from(document.querySelectorAll(
                ".ProseMirror, #prompt-textarea, [data-testid='prompt-textarea'], [contenteditable='true'][role='textbox'], [contenteditable='true'], textarea"
              ));
              return candidates.find(function(el){ return isVisible(el) && !el.disabled && el.getAttribute("aria-disabled")!=="true"; }) || null;
            };
            const editor=pickEditor();
            const input=document.querySelector("input[type=file]");
            const root=document.body;
            const selectors=[
              "[data-testid*='attachment']",
              "[data-testid*='uploaded']",
              "[data-testid*='file']",
              "[aria-label*='Remove attachment']",
              "[aria-label*='Remove file']",
              "[aria-label*='파일 제거']",
              "[aria-label*='첨부']",
              "img[src^='blob:']",
              "img[src^='data:image']"
            ];
            const nodes=new Set();
            selectors.forEach(function(sel){
              root.querySelectorAll(sel).forEach(function(el){
                if(isVisible(el)) nodes.add(el.closest("[data-testid*='attachment'],[data-testid*='file'],button,div") || el);
              });
            });
            const labelOf=function(el){
              return [
                el.getAttribute("aria-label"),
                el.getAttribute("data-testid"),
                el.getAttribute("title"),
                el.textContent
              ].filter(Boolean).join(" ").toLowerCase();
            };
            const addAttachmentLike=function(el){
              if(!isVisible(el)) return;
              nodes.add(el.closest("[data-testid*='attachment'],[data-testid*='file'],[data-testid*='upload'],button,div") || el);
            };
            root.querySelectorAll("[data-testid*='upload'],[aria-label*='Remove image'],[aria-label*='Delete attachment'],[aria-label*='Delete file'],[aria-label*='Delete image']").forEach(addAttachmentLike);
            root.querySelectorAll("button,[role='button']").forEach(function(el){
              const label=labelOf(el);
              if(/remove|delete|attachment|uploaded|file|image/.test(label)) addAttachmentLike(el);
            });
            root.querySelectorAll("img").forEach(function(el){
              if(!isVisible(el)) return;
              const r=el.getBoundingClientRect();
              const src=(el.getAttribute("src")||"").toLowerCase();
              const alt=(el.getAttribute("alt")||"").toLowerCase();
              const thumbnail = r.width>=28 && r.height>=28 && r.bottom > window.innerHeight * 0.35 && !/avatar|icon|logo/.test(alt);
              if(thumbnail || src.startsWith("blob:") || src.startsWith("data:image")) addAttachmentLike(el);
            });
            root.querySelectorAll("div,button").forEach(function(el){
              if(!isVisible(el)) return;
              const r=el.getBoundingClientRect();
              if(r.width<28 || r.height<28 || r.bottom <= window.innerHeight * 0.35) return;
              const bg=getComputedStyle(el).backgroundImage || "";
              if(bg.includes("url(") && !/avatar|icon|logo/.test(labelOf(el))) addAttachmentLike(el);
            });
            return {
              hasEditor: !!editor,
              hasInput: !!input,
              attachedCount: nodes.size,
              url: location.href
            };
          } catch(e) {
            return {hasEditor:false,hasInput:false,attachedCount:0,error:String(e),url:location.href};
          }
        })();
    """.trimIndent()

    private fun buildOpenFileChooserScript(): String = """
        (function() {
          try {
            const isVisible=function(el){
              if(!el) return false;
              const s=getComputedStyle(el);
              const r=el.getBoundingClientRect();
              return s.visibility!=="hidden" && s.display!=="none" && r.width>1 && r.height>1 && !el.closest("[aria-hidden='true']");
            };
            const labelOf=function(el){
              return [
                el.getAttribute("aria-label"),
                el.getAttribute("data-testid"),
                el.getAttribute("title"),
                el.textContent
              ].filter(Boolean).join(" ").toLowerCase();
            };
            const buttons=Array.from(document.querySelectorAll("button,[role='button']")).filter(function(el){
              const label=labelOf(el);
              return isVisible(el) && !el.disabled && el.getAttribute("aria-disabled")!=="true" &&
                /attach|upload|file|image|photo|paperclip|첨부|파일|이미지|사진|업로드|\+/.test(label);
            });
            const button=buttons.find(function(el){
              const label=labelOf(el);
              return /attach|upload|file|image|photo|paperclip|첨부|파일|이미지|사진|업로드/.test(label);
            }) || buttons[0] || null;
            if(button){
              button.click();
              return {clicked:true,target:"button",label:labelOf(button),inputCount:document.querySelectorAll("input[type=file]").length};
            }
            const inputs=Array.from(document.querySelectorAll("input[type=file]"));
            const input=inputs.find(function(el){ return !el.disabled && el.getAttribute("aria-disabled")!=="true"; }) || inputs[0] || null;
            if(input){
              input.click();
              return {clicked:true,target:"input",inputCount:inputs.length,accept:input.getAttribute("accept")||"",multiple:!!input.multiple};
            }
            return {clicked:false,target:"none",inputCount:0,error:"no-file-entry"};
          } catch(e) {
            return {clicked:false,target:"err",error:String(e),inputCount:document.querySelectorAll("input[type=file]").length};
          }
        })();
    """.trimIndent()

    /** Inject the armed images into the composer via input.files + change + paste. */
    private fun buildAttachImagesScript(): String = """
        (function() {
          try {
            const raw = (window.SeoinBridge && SeoinBridge.getPendingImages) ? SeoinBridge.getPendingImages() : "";
            const dataUrls = raw ? JSON.parse(raw) : [];
            if(!dataUrls.length) return {ok:false,error:"no-image",fileCount:0,usedPaste:false,usedInput:false,attachedCount:0};
            const isVisible=function(el){
              if(!el) return false;
              const s=getComputedStyle(el);
              const r=el.getBoundingClientRect();
              return s.visibility!=="hidden" && s.display!=="none" && r.width>1 && r.height>1 && !el.closest("[aria-hidden='true']");
            };
            const countAttachments=function(root){
              const selectors=[
                "[data-testid*='attachment']",
                "[data-testid*='uploaded']",
                "[data-testid*='file']",
                "[aria-label*='Remove attachment']",
                "[aria-label*='Remove file']",
                "[aria-label*='파일 제거']",
                "[aria-label*='첨부']",
                "img[src^='blob:']",
                "img[src^='data:image']"
              ];
              const nodes=new Set();
              selectors.forEach(function(sel){
                root.querySelectorAll(sel).forEach(function(el){
                  if(isVisible(el)) nodes.add(el.closest("[data-testid*='attachment'],[data-testid*='file'],button,div") || el);
                });
              });
              const labelOf=function(el){
                return [
                  el.getAttribute("aria-label"),
                  el.getAttribute("data-testid"),
                  el.getAttribute("title"),
                  el.textContent
                ].filter(Boolean).join(" ").toLowerCase();
              };
              const addAttachmentLike=function(el){
                if(!isVisible(el)) return;
                nodes.add(el.closest("[data-testid*='attachment'],[data-testid*='file'],[data-testid*='upload'],button,div") || el);
              };
              root.querySelectorAll("[data-testid*='upload'],[aria-label*='Remove image'],[aria-label*='Delete attachment'],[aria-label*='Delete file'],[aria-label*='Delete image']").forEach(addAttachmentLike);
              root.querySelectorAll("button,[role='button']").forEach(function(el){
                const label=labelOf(el);
                if(/remove|delete|attachment|uploaded|file|image/.test(label)) addAttachmentLike(el);
              });
              root.querySelectorAll("img").forEach(function(el){
                if(!isVisible(el)) return;
                const r=el.getBoundingClientRect();
                const src=(el.getAttribute("src")||"").toLowerCase();
                const alt=(el.getAttribute("alt")||"").toLowerCase();
                const thumbnail = r.width>=28 && r.height>=28 && r.bottom > window.innerHeight * 0.35 && !/avatar|icon|logo/.test(alt);
                if(thumbnail || src.startsWith("blob:") || src.startsWith("data:image")) addAttachmentLike(el);
              });
              root.querySelectorAll("div,button").forEach(function(el){
                if(!isVisible(el)) return;
                const r=el.getBoundingClientRect();
                if(r.width<28 || r.height<28 || r.bottom <= window.innerHeight * 0.35) return;
                const bg=getComputedStyle(el).backgroundImage || "";
                if(bg.includes("url(") && !/avatar|icon|logo/.test(labelOf(el))) addAttachmentLike(el);
              });
              return nodes.size;
            };
            const dt=new DataTransfer();
            dataUrls.forEach(function(dataUrl, index){
              const comma=dataUrl.indexOf(',');
              const meta=dataUrl.substring(0,comma); const b64=dataUrl.substring(comma+1);
              const mime=(meta.match(/data:(.*?);base64/)||[])[1]||"image/png";
              const bin=atob(b64); const arr=new Uint8Array(bin.length);
              for(let i=0;i<bin.length;i++) arr[i]=bin.charCodeAt(i);
              const ext=mime.includes("jpeg")||mime.includes("jpg")?"jpg":(mime.includes("webp")?"webp":"png");
              dt.items.add(new File([arr], "photo_"+(index+1)+"."+ext, {type:mime}));
            });
            // **한 가지 방법만** 쓴다(둘 다 쓰면 같은 파일이 중복 업로드됨).
            // ChatGPT 작성창은 ProseMirror라 paste-with-file 이 정석 경로.
            let usedPaste=false;
            const pickEditor=function(){
              const candidates=Array.from(document.querySelectorAll(
                ".ProseMirror, #prompt-textarea, [data-testid='prompt-textarea'], [contenteditable='true'][role='textbox'], [contenteditable='true'], textarea"
              ));
              return candidates.find(function(el){ return isVisible(el) && !el.disabled && el.getAttribute("aria-disabled")!=="true"; }) || null;
            };
            const pickInput=function(){
              const candidates=Array.from(document.querySelectorAll("input[type=file]"));
              return candidates.find(function(el){
                const accept=(el.getAttribute("accept")||"").toLowerCase();
                return !el.disabled && el.getAttribute("aria-disabled")!=="true" && (!accept || accept.includes("image") || accept.includes("*"));
              }) || candidates.find(function(el){ return !el.disabled && el.getAttribute("aria-disabled")!=="true"; }) || candidates[0] || null;
            };
            const editor=pickEditor();
            const input=pickInput();
            const root=document.body;
            if(!editor) {
              return {
                ok: false,
                error: "no-editor",
                fileCount: dt.files.length,
                hasEditor: false,
                hasInput: !!input,
                usedPaste: false,
                usedInput: false,
                attachedCount: countAttachments(root)
              };
            }
            try{
              editor.focus();
              const pe=new ClipboardEvent('paste',{bubbles:true,cancelable:true});
              Object.defineProperty(pe,'clipboardData',{value:dt});
              editor.dispatchEvent(pe);
              usedPaste=true;
            }catch(e){}
            // ChatGPT WebView는 input.files+change 자동 주입을 실제 첨부로 받지 않는 경우가 많다.
            // 진단값으로 input 존재만 남기고, 성공 경로는 paste confirm 에만 의존한다.
            return {
              ok: usedPaste,
              error: "",
              fileCount: dt.files.length,
              hasEditor: !!editor,
              hasInput: !!input,
              usedPaste: usedPaste,
              usedInput: false,
              attachedCount: countAttachments(root)
            };
          } catch(e){ return {ok:false,error:String(e),fileCount:0,usedPaste:false,usedInput:false,attachedCount:0}; }
        })();
    """.trimIndent()

    /**
     * Send [prompt], wait for the assistant reply to finish, and return its text
     * (null on failure/timeout). One turn at a time — the caller serializes the
     * 3-step pipeline.
     */
    suspend fun query(prompt: String): String? = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        clearQueryFailure()
        val loadDeadline = now() + PAGE_LOAD_TIMEOUT_MS
        while (!pageLoaded && now() < loadDeadline) delay(150)

        val epoch = renderEpoch
        val before = lastAssistantText()
        var attempt = 0
        while (attempt < QUERY_SEND_ATTEMPTS) {
            if (attempt > 0) {
                Log.w(TAG, "query: retrying prompt after assistant did not start")
                clearQueryFailure()
                delay(RETRY_SEND_DELAY_MS)
            }
            if (!send(prompt, epoch)) {
                Log.w(TAG, "query: send failed attempt=${attempt + 1}")
                setQueryFailure(AuthoringQueryFailureReason.SEND_FAILED)
                return@withContext null
            }
            val response = awaitResponse(
                previousText = before,
                expectedEpoch = epoch,
                earlyNoStart = attempt < QUERY_SEND_ATTEMPTS - 1,
            )
            if (response != null) return@withContext response
            if (lastQueryFailure != AuthoringQueryFailureReason.RESPONSE_NOT_STARTED) {
                return@withContext null
            }
            attempt++
        }
        null
    }

    /**
     * 프롬프트만 보내고 **텍스트 응답은 기다리지 않는다** — 그림 생성처럼 응답이 이미지라
     * 긴 폴링이 무의미하고(렌더링 부하), 결과 이미지는 [captureLastImageDataUrl]로 따로 가져온다.
     */
    suspend fun sendOnly(prompt: String): Boolean = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        clearQueryFailure()
        val deadline = now() + PAGE_LOAD_TIMEOUT_MS
        while (!pageLoaded && now() < deadline) delay(150)
        val ok = send(prompt, renderEpoch)
        if (!ok) setQueryFailure(AuthoringQueryFailureReason.SEND_FAILED)
        ok
    }

    /**
     * 새 대화로 초기화 — 한 스레드에 사진+긴 JSON+그림이 쌓여 렌더러가 메모리로 터지는
     * 것을 막는다(단계마다 호출). 페이지를 새로 로드해 DOM/메모리를 비운다.
     */
    suspend fun newChat() = withContext(Dispatchers.Main.immediate) {
        clearTransientState(clearPhotos = true)
        clearQueryFailure()
        val wv = ensureWebView()
        pageLoaded = false
        wv.loadUrl(CHATGPT_TEMPORARY_URL)
        val deadline = now() + PAGE_LOAD_TIMEOUT_MS
        while (!pageLoaded && now() < deadline) delay(150)
        Log.d(TAG, "new temporary chat loaded pageLoaded=$pageLoaded")
        delay(1000) // 작성창이 준비될 시간
    }

    private suspend fun send(message: String, expectedEpoch: Int = renderEpoch): Boolean {
        val marker = injMarker(message)
        val beforeUserCount = userMessageCount()
        // 1) 초안 작성(몇 번 재시도).
        var wrote = false
        var w = 8
        while (w-- > 0 && !wrote) {
            if (renderEpoch != expectedEpoch) {
                Log.w(TAG, "send: renderer changed while writing")
                return false
            }
            wrote = evalObj(buildWriteDraftScript(message))?.optBoolean("draftWritten") == true
            if (!wrote) delay(200)
        }
        if (!wrote) { Log.w(TAG, "send: draft not written"); return false }
        val keyboardReason = tapComposerForKeyboard()
        Log.d(TAG, "send composer keyboard $keyboardReason")
        delay(KEYBOARD_SETTLE_MS)
        // 2) 전송 시도 — **사용자 버블이 실제로 떠야** 성공으로 본다(false-positive 방지).
        //    버튼 클릭 → form.requestSubmit → Enter 순. 매 루프 시작에 버블 먼저 확인(중복 전송 방지).
        var lastReason = "?"
        lastReason = tapSendButtonNative()
        val nativeConfirmDeadline = now() + NATIVE_TAP_CONFIRM_TIMEOUT_MS
        while (now() < nativeConfirmDeadline) {
            if (renderEpoch != expectedEpoch) {
                Log.w(TAG, "send: renderer changed while sending")
                return false
            }
            val probe = evalObj(buildUserProbeScript(marker))
            if (probe?.optBoolean("visible") == true && probe.optInt("count") > beforeUserCount) {
                Log.d(TAG, "send confirmed via $lastReason userCount=${probe.optInt("count")}")
                return true
            }
            delay(INJECT_SEND_POLL_MS)
        }
        Log.d(TAG, "send native tap not confirmed reason=$lastReason")

        var attempts = INJECT_SEND_ATTEMPTS
        while (attempts-- > 0) {
            if (renderEpoch != expectedEpoch) {
                Log.w(TAG, "send: renderer changed while sending")
                return false
            }
            val probe = evalObj(buildUserProbeScript(marker))
            if (probe?.optBoolean("visible") == true && probe.optInt("count") > beforeUserCount) {
                Log.d(TAG, "send confirmed via $lastReason userCount=${probe.optInt("count")}")
                return true
            }
            lastReason = evalObj(buildSendScript())?.optString("reason") ?: "null"
            delay(INJECT_SEND_POLL_MS)
        }
        val probe = evalObj(buildUserProbeScript(marker))
        val ok = probe?.optBoolean("visible") == true && probe.optInt("count") > beforeUserCount
        Log.w(TAG, "send: ${if (ok) "confirmed-late" else "NOT confirmed"} lastReason=$lastReason beforeUsers=$beforeUserCount users=${probe?.optInt("count")}")
        return ok
    }

    private fun injMarker(message: String): String =
        message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(60)
            ?: message.trim().take(60)

    private suspend fun userMessageCount(): Int =
        evalObj(buildUserCountScript())?.optInt("count") ?: 0

    /** Poll the latest assistant message until it stops streaming and stabilizes. */
    private suspend fun awaitResponse(
        previousText: String,
        expectedEpoch: Int,
        earlyNoStart: Boolean,
    ): String? {
        val deadline = now() + RESPONSE_TIMEOUT_MS
        val startDeadline = now() + ASSISTANT_START_TIMEOUT_MS
        var lastText = ""
        var lastProbeDetail = ""
        var stable = 0
        while (now() < deadline) {
            if (renderEpoch != expectedEpoch) {
                Log.w(TAG, "awaitResponse: renderer changed")
                setQueryFailure(AuthoringQueryFailureReason.RENDERER_GONE, "epoch $expectedEpoch -> $renderEpoch")
                return null
            }
            val obj = evalObj(buildResponseProbeScript())
            if (obj != null) {
                val text = obj.optString("assistantText")
                val busy = obj.optBoolean("busy")
                val assistantCount = obj.optInt("assistantCount")
                lastProbeDetail = "users=${obj.optInt("userCount")} assistants=${obj.optInt("assistantCount")} " +
                    "busy=$busy textLen=${text.length} url=${obj.optString("url").take(80)}"
                // Ignore the pre-existing reply until a *new* one starts.
                val isNew = text.isNotBlank() && text != previousText
                if (earlyNoStart && now() >= startDeadline && assistantCount == 0 && !busy && lastText.isBlank()) {
                    setQueryFailure(AuthoringQueryFailureReason.RESPONSE_NOT_STARTED, lastProbeDetail)
                    Log.w(TAG, "awaitResponse: assistant did not start $lastProbeDetail")
                    return null
                }
                if (isNew && !busy) {
                    if (text == lastText) {
                        stable++
                        if (stable >= RESPONSE_STABLE_POLLS) return text
                    } else {
                        lastText = text
                        stable = 0
                        Log.d(TAG, "awaitResponse: assistant text len=${text.length} busy=false")
                    }
                } else {
                    stable = 0
                    if (isNew && text != lastText) {
                        lastText = text
                        Log.d(TAG, "awaitResponse: assistant text len=${text.length} busy=$busy")
                    }
                }
            }
            delay(RESPONSE_POLL_MS)
        }
        if (lastText.isNotBlank()) {
            Log.w(TAG, "awaitResponse timeout with partial text len=${lastText.length} $lastProbeDetail")
            return lastText
        }
        setQueryFailure(AuthoringQueryFailureReason.RESPONSE_TIMEOUT, lastProbeDetail.ifBlank { "no response probe" })
        Log.w(TAG, "awaitResponse timeout without assistant text $lastProbeDetail")
        return null
    }

    private suspend fun lastAssistantText(): String =
        evalObj(buildResponseProbeScript())?.optString("assistantText").orEmpty()

    // -------------------------------------------------------------- JS bridge

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

    private suspend fun tapSendButtonNative(): String = withContext(Dispatchers.Main.immediate) {
        val wv = webView ?: return@withContext "native-tap:no-webview"
        val rect = evalObj(buildSendButtonRectScript())
            ?: return@withContext "native-tap:no-rect"
        if (!rect.optBoolean("ok")) {
            return@withContext "native-tap:${rect.optString("reason", "not-ok")}"
        }
        val cssX = rect.optDouble("x", Double.NaN)
        val cssY = rect.optDouble("y", Double.NaN)
        val innerWidth = rect.optDouble("innerWidth", 0.0)
        val innerHeight = rect.optDouble("innerHeight", 0.0)
        if (cssX.isNaN() || cssY.isNaN() || cssX.isInfinite() || cssY.isInfinite() || innerWidth <= 0.0 || innerHeight <= 0.0) {
            return@withContext "native-tap:bad-rect"
        }
        if (wv.width <= 0 || wv.height <= 0) {
            return@withContext "native-tap:bad-webview-size ${wv.width}x${wv.height}"
        }
        val viewX = cssX * wv.width / innerWidth
        val viewY = cssY * wv.height / innerHeight
        if (viewX < 0.0 || viewY < 0.0 || viewX > wv.width || viewY > wv.height) {
            return@withContext "native-tap:out-of-view x=${viewX.toInt()} y=${viewY.toInt()} size=${wv.width}x${wv.height}"
        }

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            viewX.toFloat(),
            viewY.toFloat(),
            0,
        )
        val downHandled = runCatching { wv.dispatchTouchEvent(down) }.getOrDefault(false)
        down.recycle()
        delay(NATIVE_TAP_UP_DELAY_MS)
        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(
            downTime,
            upTime,
            MotionEvent.ACTION_UP,
            viewX.toFloat(),
            viewY.toFloat(),
            0,
        )
        val upHandled = runCatching { wv.dispatchTouchEvent(up) }.getOrDefault(false)
        up.recycle()
        "native-tap:down=$downHandled up=$upHandled x=${viewX.toInt()} y=${viewY.toInt()}"
    }

    private suspend fun tapComposerForKeyboard(): String = withContext(Dispatchers.Main.immediate) {
        val wv = webView ?: return@withContext "composer-tap:no-webview"
        val rect = evalObj(buildComposerRectScript())
            ?: return@withContext "composer-tap:no-rect"
        if (!rect.optBoolean("ok")) {
            return@withContext "composer-tap:${rect.optString("reason", "not-ok")}"
        }
        val cssX = rect.optDouble("x", Double.NaN)
        val cssY = rect.optDouble("y", Double.NaN)
        val innerWidth = rect.optDouble("innerWidth", 0.0)
        val innerHeight = rect.optDouble("innerHeight", 0.0)
        if (cssX.isNaN() || cssY.isNaN() || cssX.isInfinite() || cssY.isInfinite() || innerWidth <= 0.0 || innerHeight <= 0.0) {
            return@withContext "composer-tap:bad-rect"
        }
        if (wv.width <= 0 || wv.height <= 0) {
            return@withContext "composer-tap:bad-webview-size ${wv.width}x${wv.height}"
        }
        val viewX = cssX * wv.width / innerWidth
        val viewY = cssY * wv.height / innerHeight
        if (viewX < 0.0 || viewY < 0.0 || viewX > wv.width || viewY > wv.height) {
            return@withContext "composer-tap:out-of-view x=${viewX.toInt()} y=${viewY.toInt()} size=${wv.width}x${wv.height}"
        }

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            viewX.toFloat(),
            viewY.toFloat(),
            0,
        )
        val downHandled = runCatching { wv.dispatchTouchEvent(down) }.getOrDefault(false)
        down.recycle()
        delay(NATIVE_TAP_UP_DELAY_MS)
        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(
            downTime,
            upTime,
            MotionEvent.ACTION_UP,
            viewX.toFloat(),
            viewY.toFloat(),
            0,
        )
        val upHandled = runCatching { wv.dispatchTouchEvent(up) }.getOrDefault(false)
        up.recycle()

        val focusFromTouch = runCatching { wv.requestFocusFromTouch() }.getOrDefault(false)
        val focus = runCatching { wv.requestFocus() }.getOrDefault(false)
        val imm = wv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val imeShown = runCatching { imm?.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT) == true }.getOrDefault(false)
        "composer-tap:down=$downHandled up=$upHandled focusFromTouch=$focusFromTouch focus=$focus ime=$imeShown x=${viewX.toInt()} y=${viewY.toInt()}"
    }

    /**
     * ChatGPT 모바일 WebView가 첫 자동 주입을 실제 사용자 입력으로 취급하지 않아
     * 사용자 말풍선만 생기고 assistant가 시작되지 않는 경우가 있다. 빈 작성창에 짧은
     * 키 입력과 삭제를 흘린 뒤 기존 JS draft 작성으로 덮어써서 프롬프트 내용은 유지한다.
     */
    private val jsInput =
        """const isVisible=function(el){if(!el)return false;const r=el.getBoundingClientRect();const s=window.getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
           const findInput=function(){const sels=Array.from(document.querySelectorAll(${JSONObject.quote(CHATGPT_INPUT_SELECTOR)})).filter(function(el){return isVisible(el)&&!el.closest("[aria-hidden='true']");});return sels.find(function(el){return el.id==="prompt-textarea"||el.getAttribute("data-testid")==="prompt-textarea";})||sels[0]||null;};"""

    private fun buildComposerRectScript(): String = """
        (function(){
          $jsInput
          const input=findInput();
          if(!input){
            return {ok:false,reason:"no-input",innerWidth:window.innerWidth,innerHeight:window.innerHeight};
          }
          try{ input.scrollIntoView({block:"nearest",inline:"nearest"}); }catch(e){}
          try{ input.focus(); }catch(e){}
          const r=input.getBoundingClientRect();
          const x=Math.max(r.left+12, Math.min(r.right-12, (r.left+r.right)/2));
          const y=(r.top+r.bottom)/2;
          const inViewport=x>=0 && y>=0 && x<=window.innerWidth && y<=window.innerHeight;
          return {
            ok:inViewport,
            reason:inViewport?"ok":"out-of-viewport",
            x:x,
            y:y,
            innerWidth:window.innerWidth,
            innerHeight:window.innerHeight,
            rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}
          };
        })();
    """.trimIndent()

    /** Write the draft text into the composer (no send). */
    private fun buildWriteDraftScript(message: String): String {
        val text = JSONObject.quote(message)
        return """
            (function(){
              $jsInput
              const text=$text; const result={draftWritten:false,reason:""};
              const input=findInput(); if(!input){result.reason="no-input";return result;}
              const readInput=function(i){return (i.isContentEditable||i.getAttribute("contenteditable")==="true")?(i.textContent||""):(i.value||"");};
              input.focus();
              const firstLine=text.split("\n").find(function(l){return l.trim().length>0;})||text;
              if(input.isContentEditable||input.getAttribute("contenteditable")==="true"){
                input.textContent="";
                const sel=window.getSelection();const range=document.createRange();range.selectNodeContents(input);sel.removeAllRanges();sel.addRange(range);
                let ok=false; try{ok=document.execCommand("insertText",false,text);}catch(e){ok=false;}
                if(!ok||!readInput(input).includes(firstLine)){input.textContent=text;}
              } else {
                const proto=input.tagName==="TEXTAREA"?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const setter=Object.getOwnPropertyDescriptor(proto,"value").set;setter.call(input,text);
              }
              input.dispatchEvent(new InputEvent("input",{bubbles:true,inputType:"insertText",data:text}));
              input.dispatchEvent(new Event("change",{bubbles:true}));
              result.draftWritten=readInput(input).includes(firstLine);
              return result;
            })();
        """.trimIndent()
    }

    /** Attempt to send: broadened button click → form.requestSubmit → Enter. */
    private fun buildSendButtonRectScript(): String {
        val sendSelector = JSONObject.quote(CHATGPT_SEND_BUTTON_SELECTOR)
        return """
            (function(){
              const isVisible=function(el){
                if(!el) return false;
                const r=el.getBoundingClientRect();
                const s=window.getComputedStyle(el);
                return r.width>0 && r.height>0 && s.visibility!=="hidden" && s.display!=="none" && !el.closest("[aria-hidden='true']");
              };
              const labelOf=function(b){
                return [
                  b.getAttribute("aria-label"),
                  b.getAttribute("data-testid"),
                  b.getAttribute("title"),
                  b.textContent
                ].filter(Boolean).join(" ").toLowerCase();
              };
              const isSendLabel=function(label){
                return label.indexOf("send")!==-1 ||
                  label.indexOf("submit")!==-1 ||
                  label.indexOf("\ubcf4\ub0b4")!==-1 ||
                  label.indexOf("\uc804\uc1a1")!==-1;
              };
              const direct=Array.from(document.querySelectorAll($sendSelector));
              const broad=Array.from(document.querySelectorAll("button,[role='button']")).filter(function(b){
                return isSendLabel(labelOf(b));
              });
              const button=direct.concat(broad).find(function(b){
                return isVisible(b) && !b.disabled && b.getAttribute("aria-disabled")!=="true";
              });
              if(!button){
                return {ok:false,reason:"no-button",innerWidth:window.innerWidth,innerHeight:window.innerHeight};
              }
              try{ button.scrollIntoView({block:"nearest",inline:"nearest"}); }catch(e){}
              const r=button.getBoundingClientRect();
              const x=(r.left+r.right)/2;
              const y=(r.top+r.bottom)/2;
              const inViewport=x>=0 && y>=0 && x<=window.innerWidth && y<=window.innerHeight;
              return {
                ok:inViewport,
                reason:inViewport?"ok":"out-of-viewport",
                x:x,
                y:y,
                innerWidth:window.innerWidth,
                innerHeight:window.innerHeight,
                label:labelOf(button),
                rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}
              };
            })();
        """.trimIndent()
    }

    private fun buildSendScript(): String {
        val sendSelector = JSONObject.quote(CHATGPT_SEND_BUTTON_SELECTOR)
        return """
            (function(){
              $jsInput
              const result={reason:""}; const input=findInput(); if(!input){result.reason="no-input";return result;}
              const labelOf=function(b){return [b.getAttribute("aria-label"),b.getAttribute("data-testid"),b.getAttribute("title"),b.textContent].filter(Boolean).join(" ").toLowerCase();};
              const isSendLabel=function(label){return label.indexOf("send")!==-1||label.indexOf("submit")!==-1||label.indexOf("\ubcf4\ub0b4")!==-1||label.indexOf("\uc804\uc1a1")!==-1;};
              const direct=Array.from(document.querySelectorAll($sendSelector));
              const broad=Array.from(document.querySelectorAll("button,[role='button']")).filter(function(b){return isSendLabel(labelOf(b));});
              const button=direct.concat(broad).find(function(b){return isVisible(b)&&!b.disabled&&b.getAttribute("aria-disabled")!=="true";});
              if(button){ button.click(); result.reason="clicked"; return result; }
              const form=input.closest("form");
              if(form){ try{ if(form.requestSubmit){form.requestSubmit();}else{form.submit();} result.reason="form"; return result; }catch(e){} }
              try{
                const opts={key:"Enter",code:"Enter",keyCode:13,which:13,bubbles:true,cancelable:true};
                input.dispatchEvent(new KeyboardEvent("keydown",opts));
                input.dispatchEvent(new KeyboardEvent("keypress",opts));
                input.dispatchEvent(new KeyboardEvent("keyup",opts));
                result.reason="enter"; return result;
              }catch(e){ result.reason="none:"+String(e); return result; }
            })();
        """.trimIndent()
    }

    /** True once a user message bubble containing [marker] is visible (= really sent). */
    private fun buildUserProbeScript(marker: String): String {
        val m = JSONObject.quote(marker)
        return """
            (function(){
              const marker=$m;
              const users=Array.from(document.querySelectorAll("[data-message-author-role='user']"));
              const last=users[users.length-1];
              const t=last?(last.innerText||last.textContent||"").replace(/\s+/g," ").trim():"";
              return { visible: marker.length>0 && t.includes(marker), count: users.length };
            })();
        """.trimIndent()
    }

    private fun buildUserCountScript(): String = """
        (function(){
          return { count: document.querySelectorAll("[data-message-author-role='user']").length };
        })();
    """.trimIndent()

    private fun buildResponseProbeScript(): String = """
        (function() {
          const assistantNodes=Array.from(document.querySelectorAll("[data-message-author-role='assistant']"));
          const lastAssistant=assistantNodes.length?assistantNodes[assistantNodes.length-1]:null;
          const assistantText=lastAssistant?(lastAssistant.innerText||"").trim():"";
          const userCount=document.querySelectorAll("[data-message-author-role='user']").length;
          const labels=Array.from(document.querySelectorAll("button, [role='button']")).map(function(el){return [el.getAttribute("aria-label"),el.getAttribute("data-testid"),el.getAttribute("title"),el.textContent].filter(Boolean).join(" ").toLowerCase();}).join("\n");
          const busy=/stop generating|stop streaming|generating|응답 중지|생성 중지|중지 생성/.test(labels);
          return { assistantText:assistantText, busy:busy, assistantCount:assistantNodes.length, userCount:userCount, url:String(location.href||"") };
        })();
    """.trimIndent()

    private fun clearQueryFailure() {
        lastQueryFailure = null
        lastQueryFailureDetail = ""
    }

    private fun clearTransientState(clearPhotos: Boolean) {
        if (clearPhotos) clearPendingPhotos()
        captureDeferred?.complete(null)
        captureDeferred = null
        synchronized(textCaptureLock) {
            textCaptureDeferred?.complete(null)
            textCaptureDeferred = null
            textCaptureId = null
            textCaptureExpected = 0
            textCaptureChunks.clear()
        }
    }

    private fun setQueryFailure(reason: AuthoringQueryFailureReason, detail: String = "") {
        lastQueryFailure = reason
        lastQueryFailureDetail = detail
    }

    private fun dropWebView(
        view: WebView?,
        reason: String,
        failure: AuthoringQueryFailureReason? = null,
        detail: String = "",
    ) {
        Log.d(TAG, "drop authoring webview reason=$reason")
        renderEpoch += 1
        failure?.let { setQueryFailure(it, detail) }
        clearTransientState(clearPhotos = true)
        if (view === webView) {
            webView = null
            pageLoaded = false
        }
        (view?.parent as? android.view.ViewGroup)?.removeView(view)
        runCatching { view?.stopLoading() }
        runCatching { view?.destroy() }
        _reloads.value += 1
    }

    private companion object {
        const val TAG = "SeoinAuthoring"
        const val CHATGPT_URL = "https://chatgpt.com/"
        const val CHATGPT_TEMPORARY_URL = "https://chatgpt.com/?temporary-chat=true"
        const val CHATGPT_INPUT_SELECTOR =
            "#prompt-textarea, [data-testid='prompt-textarea'], textarea, div[contenteditable='true'], [contenteditable='true'], .ProseMirror"
        const val CHATGPT_SEND_BUTTON_SELECTOR =
            "button[data-testid='send-button'], button[data-testid='composer-submit-button'], button[data-testid='composer-send-button'], button[aria-label='Send prompt'], button[aria-label='Send message'], button[aria-label='Send']"
        const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        // 사진 업로드가 끝나야 전송 버튼이 활성화되므로 재시도 창을 넉넉히(≈8s).
        const val INJECT_SEND_ATTEMPTS = 40
        const val INJECT_SEND_POLL_MS = 200L
        const val NATIVE_TAP_UP_DELAY_MS = 60L
        const val NATIVE_TAP_CONFIRM_TIMEOUT_MS = 2_000L
        const val KEYBOARD_SETTLE_MS = 700L
        const val RESPONSE_TIMEOUT_MS = 120_000L
        const val ASSISTANT_START_TIMEOUT_MS = 30_000L
        const val RESPONSE_POLL_MS = 800L
        const val RESPONSE_STABLE_POLLS = 3
        const val QUERY_SEND_ATTEMPTS = 2
        const val RETRY_SEND_DELAY_MS = 1_000L
        const val ATTACH_READY_TIMEOUT_MS = 18_000L
        const val ATTACH_CONFIRM_TIMEOUT_MS = 8_000L
        const val ATTACH_MANUAL_CHOOSER_TIMEOUT_MS = 90_000L
        const val ATTACH_MANUAL_LOG_MS = 5_000L
        const val ATTACH_POLL_MS = 350L
        const val CAPTURE_TIMEOUT_MS = 15_000L
        const val TEXT_CAPTURE_TIMEOUT_MS = 12_000L
        val SINGLE_WINDOW_GUARD_JS = """
            (function(){
              if(window.__seoinSingleWindowGuard) return "already";
              window.__seoinSingleWindowGuard=true;
              window.open=function(){ return null; };
              const fix=function(){
                document.querySelectorAll("a[target]").forEach(function(a){ a.setAttribute("target","_self"); });
              };
              fix();
              setInterval(fix, 1000);
              return "ok";
            })();
        """.trimIndent()
    }
}
