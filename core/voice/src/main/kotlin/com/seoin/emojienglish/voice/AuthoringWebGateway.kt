package com.seoin.emojienglish.voice

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import java.io.File
import java.io.FileInputStream

enum class AuthoringQueryFailureReason {
    SEND_FAILED, RESPONSE_TIMEOUT, ATTACH_FAILED,
}

data class AuthoringHttp431State(
    val active: Boolean = false,
    val host: String = "",
    val statusCode: Int = 0,
    val requestUrlLength: Int = 0,
    val qQueryLength: Int = 0,
    val cookieHeaderLength: Int = 0,
    val cookieCount: Int = 0,
    val convKeyCookieCount: Int = 0,
    val autoReloadAttempted: Boolean = false,
)

/**
 * 저작용 ChatGPT WebView 드라이버 — **동작은 전부 webviewtest(WebView Test Bed) 기준**.
 *
 * 핵심 규칙:
 *  - **웹뷰는 단 1개**, 한 번 만들면 **파괴/재생성하지 않는다**(요구사항: newChat·recycle 없음).
 *    한 대화에 계속 이어 보낸다.
 *  - **사진은 네이티브 파일 선택기로 5장 한 번에** 첨부한다(armedUris → onShowFileChooser).
 *    JS paste 주입 방식(구버전)은 쓰지 않는다.
 *  - DOM 이 계속 늘어나므로 webviewtest 처럼 `window.__androidWtbTurns` 컨테이너를 캐시하고
 *    **마지막 turn 만** 읽어 응답/JSON 을 가져온다(snapshot 방식).
 */
@Singleton
class AuthoringWebGateway @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    data class AssistantMarker(
        val count: Int,
        val turnId: Int,
        val hash: String,
        val length: Int,
    )

    private var webView: WebView? = null
    @Volatile private var pageLoaded = false

    // 네이티브 파일 선택기 자동 응답(webviewtest autoUpload* 그대로).
    @Volatile private var autoUploadArmed = false
    @Volatile private var autoUploadDelivered = false
    @Volatile private var armedUris: List<Uri> = emptyList()

    // JSON/이미지 캡처 브릿지(저작 전용 추가 기능).
    @Volatile private var captureDeferred: CompletableDeferred<String?>? = null
    private val textCaptureLock = Any()
    @Volatile private var textCaptureDeferred: CompletableDeferred<String?>? = null
    @Volatile private var textCaptureId: String? = null
    private var textCaptureExpected = 0
    private val textCaptureChunks = mutableMapOf<Int, String>()
    @Volatile private var downloadSaveSeq = 0
    @Volatile private var lastDownloadSummary = ""

    @Volatile private var lastQueryFailure: AuthoringQueryFailureReason? = null
    @Volatile private var autoReloadedAfter431 = false

    private val _http431State = MutableStateFlow(AuthoringHttp431State())
    val http431State: StateFlow<AuthoringHttp431State> = _http431State.asStateFlow()

    /** 렌더러가 죽어 부득이 재생성한 경우에만 증가(정상 흐름에서는 절대 증가하지 않음). */
    private val _reloads = MutableStateFlow(0)
    val reloads: StateFlow<Int> = _reloads.asStateFlow()

    fun lastQueryFailureMessage(action: String): String = when (lastQueryFailure) {
        AuthoringQueryFailureReason.SEND_FAILED ->
            "$action 중 ChatGPT에 프롬프트 전송 확인이 안 됐습니다. 아래 ChatGPT 창의 입력창/로그인 상태를 확인하세요."
        AuthoringQueryFailureReason.ATTACH_FAILED ->
            "$action 중 사진 첨부가 확인되지 않았습니다. 아래 ChatGPT 창에서 + 버튼/사진 메뉴가 보이는지 확인하세요."
        AuthoringQueryFailureReason.RESPONSE_TIMEOUT ->
            "$action 중 ChatGPT 응답을 시간 안에 읽지 못했습니다. 아래 창이 답변 중인지 확인하고 다시 시도하세요."
        null -> "$action 중 응답을 가져오지 못했습니다. 아래 ChatGPT 창 상태를 확인하세요."
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        WebView.setWebContentsDebuggingEnabled(true)
        val wv = WebView(appContext)
        configureSettings(wv)
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadHttpUrl(url, userAgent, contentDisposition, mimeType)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                installRuntime(view)
                Log.d(TAG, "page finished url=$url")
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "main frame load error url=${request.url} code=${error?.errorCode} desc=${error?.description}")
                }
                super.onReceivedError(view, request, error)
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                val statusCode = errorResponse?.statusCode ?: 0
                val requestUrl = request?.url
                if (statusCode == 431 && requestUrl != null) {
                    handleHttp431(view, request.isForMainFrame, requestUrl, statusCode)
                } else if (request?.isForMainFrame == true) {
                    Log.w(TAG, "main frame http error url=${request.url} status=$statusCode reason=${errorResponse?.reasonPhrase}")
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // 렌더러가 실제로 죽었을 때만(앱 동반 종료 방지) 재생성. 정상 흐름에서는 발생하지 않음.
                Log.w(TAG, "renderer gone didCrash=${detail?.didCrash()} — recreating once")
                if (view === webView) {
                    webView = null
                    pageLoaded = false
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    runCatching { view?.destroy() }
                    _reloads.value += 1
                }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                // webviewtest 와 동일: 무장돼 있으면 시스템 선택창 없이 armedUris 를 그대로 전달.
                if (autoUploadArmed && armedUris.isNotEmpty()) {
                    filePathCallback?.onReceiveValue(armedUris.toTypedArray())
                    autoUploadDelivered = true
                    autoUploadArmed = false
                    Log.d(TAG, "auto file chooser delivered count=${armedUris.size}")
                    return true
                }
                filePathCallback?.onReceiveValue(null)
                return true
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                Log.d(TAG, "blocked new window isDialog=$isDialog gesture=$isUserGesture")
                return false // 요구사항: 창 1개만.
            }
            override fun onPermissionRequest(request: PermissionRequest?) {
                runCatching { request?.grant(request.resources) }
            }
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                val msg = m?.message().orEmpty()
                if (msg.contains("seoin", ignoreCase = true) || msg.contains("__seoin", ignoreCase = true)) {
                    Log.d(TAG, "console ${m?.sourceId()}:${m?.lineNumber()} $msg")
                }
                return true
            }
        }
        wv.addJavascriptInterface(CaptureBridge(), "SeoinBridge")
        loadChatGpt(wv)
        webView = wv
        return wv
    }

    private fun configureSettings(target: WebView) {
        // webviewtest configureWebViewSettings 그대로.
        target.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) target.settings.offscreenPreRaster = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            target.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, true)
    }

    fun provideView(): WebView = ensureWebView()

    fun retryAfterHttp431() {
        val wv = ensureWebView()
        pageLoaded = false
        _http431State.value = AuthoringHttp431State()
        loadChatGpt(wv)
    }

    suspend fun clearTemporaryChatCookiesAfter431(): Int = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        val cm = CookieManager.getInstance()
        val names = temporaryChatCookieNames(cm.getCookie(CHATGPT_URL).orEmpty())
        if (names.isEmpty()) return@withContext 0
        names.forEach { name ->
            expireCookie(cm, name, null)
            expireCookie(cm, name, ".chatgpt.com")
            expireCookie(cm, name, "chatgpt.com")
        }
        withContext(Dispatchers.IO) { cm.flush() }
        Log.w(TAG, "expired temporary chat cookies count=${names.size}")
        retryAfterHttp431()
        names.size
    }

    suspend fun clearAllWebViewCookiesAfterConfirmation(): Boolean = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        val cm = CookieManager.getInstance()
        val cleared = suspendCancellableCoroutine<Boolean> { cont ->
            cm.removeAllCookies { removed -> if (cont.isActive) cont.resume(removed) }
        }
        withContext(Dispatchers.IO) { cm.flush() }
        Log.w(TAG, "all in-app WebView cookies cleared by explicit user action removed=$cleared")
        retryAfterHttp431()
        cleared
    }

    suspend fun selectInstantMode(): Boolean = selectComposerMode("즉시")
    suspend fun selectHighMode(): Boolean = selectComposerMode("높음", aliases = listOf("High", "high"))

    suspend fun selectProExpansionMode(): Boolean = selectComposerMode("Pro", aliases = listOf("Pro 확장"))

    private suspend fun selectComposerMode(
        label: String,
        aliases: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        waitPageLoaded()
        if (!waitForEditor()) return@withContext false
        val labels = (listOf(label) + aliases).distinct()
        for (attempt in 0 until MODE_SELECT_MAX_ATTEMPTS) {
            val target = evalObj(buildComposerModeTargetJs(labels))
            Log.d(TAG, "mode select labels=${labels.joinToString("|")} attempt=$attempt target=$target")
            when (target?.optString("status")) {
                "selected" -> return@withContext true
                "option" -> {
                    tapTarget(target)
                    delay(1200)
                    val verified = evalObj(buildComposerModeTargetJs(labels))
                    Log.d(TAG, "mode select verify labels=${labels.joinToString("|")} target=$verified")
                    if (verified?.optString("status") == "selected") return@withContext true
                }
                "toggle" -> {
                    tapTarget(target)
                    delay(700)
                }
                else -> delay(500)
            }
        }
        evalObj(buildComposerModeTargetJs(labels))?.optString("status") == "selected"
    }

    /** 다음 첨부에 쓸 사진들을 기억(네이티브 선택기 자동응답용). 웹뷰는 건드리지 않는다. */
    fun armPhotos(uris: List<Uri>) {
        ensureWebView()
        armedUris = uris
    }

    // ---------------------------------------------------------------- 첨부

    /**
     * webviewtest 의 attach 흐름을 그대로: 무장 → 첨부 버튼 탭 → (필요시) 메뉴의 사진/파일 탭 →
     * onShowFileChooser 가 armedUris 를 전달할 때까지 대기 → 업로드 정착 대기.
     */
    suspend fun attachImages(): String = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        waitPageLoaded()
        val uris = armedUris
        if (uris.isEmpty()) return@withContext "no-uri"

        for (attempt in 0 until ATTACH_MAX_ATTEMPTS) {
            autoUploadArmed = true
            autoUploadDelivered = false

            // 1) 첨부(+) 버튼 찾아 네이티브 탭, 못 찾으면 JS 클릭 폴백.
            val target = evalObj(buildAttachTargetJs(attempt))
            Log.d(TAG, "attach attempt=$attempt target=$target")
            if (target?.optBoolean("found") == true) tapTarget(target)
            else Log.d(TAG, "attach fallback click=${eval(buildAttachClickJs("photo-menu"))}")
            delay(ATTACH_MENU_PROBE_DELAY_MS)

            // 2) 아직 안 떴으면 + 메뉴에서 '사진/파일' 항목을 탭.
            if (!autoUploadDelivered) {
                val menu = evalObj(buildUploadMenuTargetJs(attempt))
                Log.d(TAG, "attach attempt=$attempt menu=$menu")
                if (menu?.optBoolean("found") == true) {
                    val tapSafe = menu.optBoolean("tapSafe", true)
                    if (!tapSafe) {
                        Log.w(TAG, "attach menu target clipped label=${menu.optString("label")} visibleH=${menu.optDouble("visibleHeight")} rect=${menu.optDouble("left")},${menu.optDouble("top")},${menu.optDouble("right")},${menu.optDouble("bottom")}")
                    }
                    tapTarget(menu)
                    if (!tapSafe) {
                        delay(350)
                        if (!autoUploadDelivered) {
                            Log.d(TAG, "attach clipped menu fallback=${eval(buildAttachClickJs("direct-input-menu-clipped"))}")
                        }
                    }
                } else {
                    Log.d(TAG, "attach menu fallback=${eval(buildAttachClickJs("file-input-menu-fallback"))}")
                }
                delay(ATTACH_MENU_PROBE_DELAY_MS)
            }

            // 3) 전달 대기.
            val deadline = now() + ATTACH_RETRY_WAIT_MS
            while (now() < deadline && !autoUploadDelivered) delay(150)
            if (autoUploadDelivered) {
                autoUploadArmed = false
                Log.d(TAG, "attachImages delivered count=${uris.size} attempt=$attempt — settling")
                delay(UPLOAD_SETTLE_MS) // 업로드/썸네일 정착(전송 버튼 활성화까지).
                return@withContext "ok files=${uris.size}"
            }
            Log.w(TAG, "attachImages attempt=$attempt not delivered, retrying")
        }
        autoUploadArmed = false
        lastQueryFailure = AuthoringQueryFailureReason.ATTACH_FAILED
        "attach-fail files=${uris.size}"
    }

    // ---------------------------------------------------------------- 전송/응답

    /** 프롬프트 주입 → 전송 → 응답이 안정될 때까지 대기 후 마지막 어시스턴트 텍스트 반환. */
    suspend fun query(prompt: String, timeoutMs: Long? = RESPONSE_TIMEOUT_MS): String? = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        waitPageLoaded()
        Log.d(TAG, "query start promptChars=${prompt.length} timeoutMs=${timeoutMs ?: "none"}")
        if (!waitForEditor()) { lastQueryFailure = AuthoringQueryFailureReason.SEND_FAILED; return@withContext null }
        clearFailure()
        val pre = evalObj(buildSnapshotJs())
        val preCount = pre?.optInt("assistantCount") ?: 0
        val preLen = pre?.optInt("lastAssistantLen") ?: 0
        val preTail = pre?.optString("bodyTail")?.length ?: 0
        Log.d(TAG, "query pre count=$preCount len=$preLen queue=${pre?.optInt("domQueueDepth")} raf=${pre?.optLong("raf")} visible=${pre?.optString("visibilityState")}")

        if (!inject(prompt)) { lastQueryFailure = AuthoringQueryFailureReason.SEND_FAILED; return@withContext null }
        delay(1400)
        if (!sendWithRetry()) { lastQueryFailure = AuthoringQueryFailureReason.SEND_FAILED; return@withContext null }

        val sentAt = now()
        var lastText = ""
        var lastChange = sentAt
        delay(3500)
        val deadline = timeoutMs?.let { sentAt + it }
        while (deadline == null || now() < deadline) {
            val snap = evalObj(buildSnapshotJs())
            if (snap != null) {
                var text = snap.optString("lastAssistant")
                if (text.isEmpty()) text = snap.optString("bodyTail")
                if (text != lastText) { lastText = text; lastChange = now() }
                val len = snap.optInt("lastAssistantLen")
                val count = snap.optInt("assistantCount")
                val stop = snap.optBoolean("stopVisible")
                val uploading = snap.optBoolean("uploadingVisible")
                val actionsReady = snap.optBoolean("assistantActionsReady")
                val hasNew = (count > preCount && len > 3) || len > preLen + 20 || text.length > preTail + 20
                val stable = hasNew && now() - lastChange >= RESPONSE_STABLE_MS && !stop && !uploading && actionsReady
                if (stable) {
                    Log.d(TAG, "query stable len=$len count=$count actionsReady=$actionsReady queue=${snap.optInt("domQueueDepth")} lastSource=${snap.optString("lastSource")} raf=${snap.optLong("raf")}")
                    return@withContext snap.optString("lastAssistant").ifEmpty { text }
                }
            }
            delay(RESPONSE_POLL_MS)
        }
        lastQueryFailure = AuthoringQueryFailureReason.RESPONSE_TIMEOUT
        Log.w(TAG, "query timeout lastLen=${lastText.length}")
        null
    }

    /** 프롬프트만 보내고 응답은 기다리지 않는다(그림/JSON 생성은 capture* 로 따로 받는다). */
    suspend fun sendOnly(prompt: String): Boolean = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        waitPageLoaded()
        Log.d(TAG, "sendOnly start promptChars=${prompt.length}")
        if (!waitForEditor()) { lastQueryFailure = AuthoringQueryFailureReason.SEND_FAILED; return@withContext false }
        clearFailure()
        if (!inject(prompt)) { lastQueryFailure = AuthoringQueryFailureReason.SEND_FAILED; return@withContext false }
        delay(1400)
        val ok = sendWithRetry()
        if (!ok) lastQueryFailure = AuthoringQueryFailureReason.SEND_FAILED
        ok
    }

    private suspend fun inject(prompt: String): Boolean {
        repeat(6) {
            val r = eval(buildPromptInjectionJs(prompt))
            if (r.contains("prompt-injected")) return true
            delay(400)
        }
        return false
    }

    private suspend fun sendWithRetry(): Boolean {
        var mode = "button"
        for (attempt in 0 until SEND_MAX_ATTEMPTS) {
            val r = eval(buildSendJs(mode))
            Log.d(TAG, "send attempt=$attempt mode=$mode result=$r")
            if (r.contains("clicked-send-button")) return true
            val enter = r.contains("enter-dispatched")
            mode = if (enter || attempt >= 5) "button" else mode
            delay(if (enter) 1200 else 2500)
        }
        return false
    }

    // ---------------------------------------------------------------- 카운트/캡처

    suspend fun assistantMessageCount(): Int = withContext(Dispatchers.Main.immediate) {
        evalObj(buildSnapshotJs())?.optInt("assistantCount") ?: 0
    }

    suspend fun assistantMarker(): AssistantMarker = withContext(Dispatchers.Main.immediate) {
        val snap = evalObj(buildSnapshotJs())
        AssistantMarker(
            count = snap?.optInt("assistantCount") ?: 0,
            turnId = snap?.optInt("lastTurnId") ?: 0,
            hash = snap?.optString("lastHash").orEmpty(),
            length = snap?.optInt("lastAssistantLen") ?: 0,
        ).also {
            Log.d(TAG, "assistant marker count=${it.count} turn=${it.turnId} len=${it.length} hash=${it.hash.take(80)}")
        }
    }

    suspend fun largeAssistantImageCount(): Int = withContext(Dispatchers.Main.immediate) {
        evalObj(
            """
            (function(){
              const root=window.__seoinLastAssistant?window.__seoinLastAssistant():null;
              const scope=root||document;
              const imgs=Array.from(scope.querySelectorAll("img")).filter(function(i){return (i.naturalWidth||i.width)>=200;});
              const canvases=Array.from(document.querySelectorAll("main canvas")).filter(function(c){return (c.width||0)>=200 && (c.height||0)>=200;});
              return { count: imgs.length + canvases.length };
            })();
            """.trimIndent(),
        )?.optInt("count") ?: 0
    }

    suspend fun generatedImageActionsReady(minImageCount: Int = 0): Boolean = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        evalObj(buildImageCompletionProbeJs(minImageCount))?.optBoolean("ready") == true
    }

    /** 마지막 turn 에서 생성 이미지를 fetch→base64 로 직접 가져온다. */
    suspend fun captureLastImageDataUrl(minImageCount: Int = 0): String? = withContext(Dispatchers.Main.immediate) {
        ensureWebView()
        val d = CompletableDeferred<String?>()
        captureDeferred = d
        eval(buildCaptureScript(minImageCount))
        val r = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { d.await() }
        captureDeferred = null
        r
    }

    /** 마지막 turn 에서 JSON 만 골라 작은 조각으로 받아 합친다(큰 응답 안전 수신). */
    suspend fun captureLastAssistantJsonChunked(
        minAssistantCount: Int = 0,
        previous: AssistantMarker? = null,
    ): String? =
        withContext(Dispatchers.Main.immediate) {
            ensureWebView()
            val id = "json-${now()}"
            val d = CompletableDeferred<String?>()
            synchronized(textCaptureLock) {
                textCaptureId = id; textCaptureExpected = 0; textCaptureChunks.clear(); textCaptureDeferred = d
            }
            val evalResult = eval(buildJsonChunkCaptureScript(id, minAssistantCount, previous))
            Log.d(TAG, "json capture eval id=$id min=$minAssistantCount prevTurn=${previous?.turnId ?: 0} prevLen=${previous?.length ?: 0} result=$evalResult")
            val r = withTimeoutOrNull(TEXT_CAPTURE_TIMEOUT_MS) { d.await() }
            synchronized(textCaptureLock) {
                if (textCaptureId == id) {
                    textCaptureId = null; textCaptureExpected = 0; textCaptureChunks.clear(); textCaptureDeferred = null
                }
            }
            r
        }

    suspend fun saveTextToDownloads(fileName: String, text: String, mimeType: String = "application/json"): Uri? =
        withContext(Dispatchers.IO) {
            writeBytesToDownloads(text.toByteArray(Charsets.UTF_8), fileName, mimeType)
        }

    suspend fun saveBytesToDownloads(fileName: String, bytes: ByteArray, mimeType: String): Uri? =
        withContext(Dispatchers.IO) {
            writeBytesToDownloads(bytes, fileName, mimeType)
        }

    suspend fun probeDownloadFallback(baseName: String, wantsImage: Boolean): String =
        withContext(Dispatchers.Main.immediate) {
            ensureWebView()
            eval(buildDownloadProbeJs(baseName, wantsImage))
        }

    suspend fun requestAssistantDownload(baseName: String, wantsImage: Boolean, timeoutMs: Long? = 12_000L): String =
        withContext(Dispatchers.Main.immediate) {
            ensureWebView()
            val before = downloadSaveSeq
            val deadline = timeoutMs?.let { now() + it }
            var probe = ""
            while (downloadSaveSeq <= before && (deadline == null || now() < deadline)) {
                probe = eval(buildDownloadProbeJs(baseName, wantsImage))
                val settleUntil = now() + if (probe.contains("save-started") || probe.startsWith("clicked-download-button")) 2_000L else 900L
                while (downloadSaveSeq <= before && now() < settleUntil && (deadline == null || now() < deadline)) {
                    delay(250)
                }
            }
            val confirmed = downloadSaveSeq > before
            val summary = lastDownloadSummary
            Log.d(TAG, "assistant download probe=$probe confirmed=$confirmed timeoutMs=${timeoutMs ?: "none"} summary=$summary")
            "probe=$probe confirmed=$confirmed summary=$summary"
    }

    suspend fun readDownloadedText(downloadResult: String, timeoutMs: Long = DOWNLOAD_READ_TIMEOUT_MS): String? =
        withContext(Dispatchers.IO) {
            val uri = Regex("""uri=([^\s]+)""").find(downloadResult)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri != null) {
                return@withContext runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }.onFailure {
                    Log.w(TAG, "download text read failed uri=$uri", it)
                }.getOrNull()
            }

            val id = Regex("""downloadManagerId=(\d+)""").find(downloadResult)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: return@withContext null
            readDownloadManagerText(id, timeoutMs)
        }

    // ---------------------------------------------------------------- 내부

    private fun handleHttp431(view: WebView?, isMainFrame: Boolean, requestUrl: Uri, statusCode: Int) {
        val host = requestUrl.host.orEmpty()
        val cookies = CookieManager.getInstance().getCookie(requestUrl.toString()).orEmpty()
        val cookieNames = cookieNames(cookies)
        val convKeyCount = cookieNames.count { isTemporaryChatCookieName(it) }
        val qLength = runCatching { requestUrl.getQueryParameter("q")?.length ?: 0 }.getOrDefault(0)
        val requestUrlLength = requestUrl.toString().length
        val cookieHeaderLength = cookies.length
        val cookieCount = cookieNames.size

        Log.w(
            TAG,
            "http431 diagnostic host=$host status=$statusCode " +
                "urlChars=$requestUrlLength qChars=$qLength cookieChars=$cookieHeaderLength " +
                "cookieCount=$cookieCount convKeyCookieCount=$convKeyCount mainFrame=$isMainFrame",
        )

        if (!isMainFrame || !isChatGptRelatedHost(host)) return

        val shouldAutoReload = !autoReloadedAfter431
        autoReloadedAfter431 = true
        pageLoaded = false
        _http431State.value = AuthoringHttp431State(
            active = true,
            host = host,
            statusCode = statusCode,
            requestUrlLength = requestUrlLength,
            qQueryLength = qLength,
            cookieHeaderLength = cookieHeaderLength,
            cookieCount = cookieCount,
            convKeyCookieCount = convKeyCount,
            autoReloadAttempted = shouldAutoReload,
        )

        if (shouldAutoReload) {
            view?.post {
                Log.w(TAG, "HTTP 431: automatic recovery by about:blank -> $CHATGPT_URL once")
                view.loadUrl("about:blank")
                view.postDelayed({
                    pageLoaded = false
                    loadChatGpt(view)
                }, 800L)
            }
        }
    }

    private fun isChatGptRelatedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "chatgpt.com" || h.endsWith(".chatgpt.com") || h == "chat.openai.com"
    }

    private fun cookieNames(cookieHeader: String): List<String> =
        cookieHeader.split(';')
            .mapNotNull { part -> part.substringBefore('=', "").trim().takeIf { it.isNotBlank() } }

    private fun temporaryChatCookieNames(cookieHeader: String): List<String> =
        cookieNames(cookieHeader).filter(::isTemporaryChatCookieName).distinct()

    private fun isTemporaryChatCookieName(name: String): Boolean =
        name == "conv_key" || name.startsWith("conv_key_")

    private fun expireCookie(cm: CookieManager, name: String, domain: String?) {
        val domainPart = domain?.let { "; Domain=$it" }.orEmpty()
        val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT$domainPart; Path=/"
        cm.setCookie(CHATGPT_URL, expired)
    }

    private fun loadChatGpt(view: WebView, prompt: String? = null) {
        val trimmed = prompt?.takeIf { it.isNotBlank() }
        if (trimmed == null) {
            view.loadUrl(CHATGPT_URL)
            return
        }
        val promptUrl = Uri.parse(CHATGPT_URL).buildUpon()
            .appendQueryParameter("q", trimmed)
            .build()
            .toString()
        if (promptUrl.length > MAX_CHATGPT_Q_URL_CHARS) {
            copyPromptToClipboard(trimmed)
            Log.w(TAG, "ChatGPT q URL too long urlChars=${promptUrl.length}; copied prompt chars=${trimmed.length} and opening home")
            view.loadUrl(CHATGPT_URL)
        } else {
            Log.d(TAG, "opening ChatGPT q URL urlChars=${promptUrl.length} promptChars=${trimmed.length}")
            view.loadUrl(promptUrl)
        }
    }

    private fun copyPromptToClipboard(prompt: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ChatGPT prompt", prompt))
    }

    private inner class CaptureBridge {
        @JavascriptInterface fun onImageCaptured(dataUrl: String) {
            Log.d(TAG, "image captured len=${dataUrl.length}")
            captureDeferred?.complete(dataUrl.ifBlank { null })
        }
        @JavascriptInterface fun onTextCaptureStart(id: String, total: Int) {
            synchronized(textCaptureLock) {
                if (id != textCaptureId) return
                textCaptureExpected = total.coerceAtLeast(0)
                textCaptureChunks.clear()
                if (textCaptureExpected == 0) textCaptureDeferred?.complete(null)
                Log.d(TAG, "text capture start id=$id chunks=$textCaptureExpected")
            }
        }
        @JavascriptInterface fun onTextChunk(id: String, index: Int, total: Int, chunk: String) {
            val completed: String? = synchronized(textCaptureLock) {
                if (id != textCaptureId) return
                textCaptureExpected = total.coerceAtLeast(0)
                if (index in 0 until textCaptureExpected) textCaptureChunks[index] = chunk
                if (textCaptureExpected > 0 && textCaptureChunks.size >= textCaptureExpected) {
                    (0 until textCaptureExpected).joinToString("") { textCaptureChunks[it].orEmpty() }
                } else null
            }
            if (completed != null) {
                Log.d(TAG, "text capture complete id=$id len=${completed.length}")
                textCaptureDeferred?.complete(completed)
            }
        }
        @JavascriptInterface fun onTextCaptureError(id: String, message: String) {
            if (id != textCaptureId) return
            Log.d(TAG, "text capture error id=$id message=$message")
            textCaptureDeferred?.complete(null)
        }
        @JavascriptInterface fun saveBase64File(dataUrl: String, suggestedName: String, mimeType: String) {
            runCatching {
                val uri = writeDataUrlToDownloads(dataUrl, suggestedName, mimeType)
                downloadSaveSeq += 1
                lastDownloadSummary = "uri=$uri name=$suggestedName mime=$mimeType"
                Log.d(TAG, "download bridge saved uri=$uri name=$suggestedName mime=$mimeType")
            }.onFailure {
                Log.w(TAG, "download bridge save failed name=$suggestedName mime=$mimeType", it)
            }
        }
        @JavascriptInterface fun requestHttpDownload(url: String, suggestedName: String, mimeType: String) {
            val disposition = "attachment; filename=\"${safeFileName(suggestedName, "seoin-download.bin")}\""
            downloadHttpUrl(url, "", disposition, mimeType)
        }
        @JavascriptInterface fun onDownloadEvent(type: String, detail: String) {
            Log.d(TAG, "download bridge event type=$type detail=$detail")
        }
    }

    private fun installRuntime(view: WebView?) {
        view?.evaluateJavascript(SINGLE_WINDOW_GUARD_JS + "\n" + DOM_QUEUE_RUNTIME_JS + "\n" + DOWNLOAD_BRIDGE_JS, null)
    }

    private fun downloadHttpUrl(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (url.isNullOrBlank()) return
        runCatching {
            val resolvedMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val guessed = URLUtil.guessFileName(url, contentDisposition, resolvedMime)
            val fileName = safeFileName(guessed, "seoin-download-${now()}.bin")
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setMimeType(resolvedMime)
                addRequestHeader("User-Agent", userAgent.orEmpty())
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SeoinEnglish/$fileName")
            }
            val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            downloadSaveSeq += 1
            lastDownloadSummary = "downloadManagerId=$id file=$fileName mime=$resolvedMime"
            Log.d(TAG, "download enqueued id=$id file=$fileName url=$url")
        }.onFailure {
            Log.w(TAG, "download enqueue failed url=$url", it)
        }
    }

    private fun readDownloadManagerText(id: Long, timeoutMs: Long): String? {
        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val deadline = now() + timeoutMs
        var lastStatus = -1
        while (now() < deadline) {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    lastStatus = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                    when (lastStatus) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            return runCatching {
                                manager.openDownloadedFile(id).use { pfd ->
                                    FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }
                                }
                            }.onFailure {
                                Log.w(TAG, "download manager text read failed id=$id", it)
                            }.getOrNull()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0
                            Log.w(TAG, "download manager text read failed id=$id reason=$reason")
                            return null
                        }
                    }
                }
            }
            Thread.sleep(500)
        }
        Log.w(TAG, "download manager text read timed out id=$id status=$lastStatus timeoutMs=$timeoutMs")
        return null
    }

    private fun writeDataUrlToDownloads(dataUrl: String, suggestedName: String?, mimeType: String?): Uri? {
        val comma = dataUrl.indexOf(',')
        require(comma >= 0) { "Invalid data URL" }
        val header = dataUrl.substring(0, comma)
        val payload = dataUrl.substring(comma + 1)
        val inferredMime = Regex("^data:([^;,]+)").find(header)?.groupValues?.getOrNull(1)
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() } ?: inferredMime ?: "application/octet-stream"
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val extension = when {
            resolvedMime.contains("png", ignoreCase = true) -> ".png"
            resolvedMime.contains("jpeg", ignoreCase = true) || resolvedMime.contains("jpg", ignoreCase = true) -> ".jpg"
            resolvedMime.contains("json", ignoreCase = true) -> ".json"
            resolvedMime.contains("text", ignoreCase = true) -> ".txt"
            else -> ".bin"
        }
        val fallback = "seoin-download-${now()}$extension"
        return writeBytesToDownloads(bytes, suggestedName, resolvedMime, fallback)
    }

    private fun writeBytesToDownloads(
        bytes: ByteArray,
        suggestedName: String?,
        mimeType: String,
        fallbackName: String = "seoin-download-${now()}.bin",
    ): Uri? {
        val fileName = safeFileName(suggestedName, fallbackName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SeoinEnglish")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.d(TAG, "saved downloads uri=$uri file=$fileName bytes=${bytes.size}")
            uri
        } else {
            val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SeoinEnglish")
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            val uri = Uri.fromFile(file)
            Log.d(TAG, "saved app external downloads uri=$uri bytes=${bytes.size}")
            uri
        }
    }

    private fun safeFileName(name: String?, fallback: String): String {
        val raw = name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: fallback
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { fallback }
        return cleaned.take(140)
    }

    private suspend fun waitPageLoaded() {
        val deadline = now() + PAGE_LOAD_TIMEOUT_MS
        while (!pageLoaded && now() < deadline) delay(150)
    }

    private suspend fun waitForEditor(): Boolean {
        val deadline = now() + EDITOR_TIMEOUT_MS
        var poll = 0
        var last: JSONObject? = null
        var retriedContentLoad = false
        while (now() < deadline) {
            val probe = evalObj(buildEditorProbeJs())
            last = probe
            if (probe?.optBoolean("editorExists") == true) {
                _http431State.value = AuthoringHttp431State()
                Log.d(TAG, "editor ready poll=$poll selector=${probe.optString("selector")} tag=${probe.optString("tag")} id=${probe.optString("id")} visible=${probe.optBoolean("visible")}")
                return true
            }
            val bodyTail = probe?.optString("bodyTail").orEmpty()
            val needsReload =
                bodyTail.contains("Content failed to load", ignoreCase = true) ||
                    bodyTail.contains("ERR_HTTP_RESPONSE_CODE_FAILURE", ignoreCase = true) ||
                    bodyTail.contains("ERR_", ignoreCase = true) ||
                    bodyTail.contains("웹페이지를 사용할 수 없음")
            if (!retriedContentLoad && needsReload) {
                retriedContentLoad = true
                val retry = evalObj(buildContentFailedRetryJs())
                pageLoaded = false
                webView?.let { loadChatGpt(it) }
                Log.w(TAG, "editor wait load failed retry=$retry reload=$CHATGPT_URL")
                delay(4000)
                continue
            }
            if (poll % 10 == 0) Log.d(TAG, "editor wait poll=$poll probe=$probe")
            poll += 1
            delay(500)
        }
        Log.w(TAG, "editor wait timeout last=$last")
        return false
    }

    private fun clearFailure() { lastQueryFailure = null }
    private fun now() = System.currentTimeMillis()

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main.immediate) {
        val wv = ensureWebView()
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result ?: "null") }
        }
    }

    private suspend fun evalObj(js: String): JSONObject? = runCatching {
        val raw = eval(js)
        if (raw.isBlank() || raw == "null") null else JSONObject(unwrap(raw))
    }.getOrNull()

    /** evaluateJavascript 는 JSON 문자열을 한 번 더 따옴표로 감싸 돌려준다 — 한 겹 벗긴다. */
    private fun unwrap(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return org.json.JSONArray("[$value]").getString(0)
        }
        return value
    }

    private suspend fun tapTarget(obj: JSONObject) = withContext(Dispatchers.Main.immediate) {
        val wv = webView ?: return@withContext
        if (wv.width <= 0 || wv.height <= 0) return@withContext
        val vw = obj.optDouble("vw", 1.0).coerceAtLeast(1.0)
        val vh = obj.optDouble("vh", 1.0).coerceAtLeast(1.0)
        val cssX = obj.optDouble("tapX", obj.optDouble("x", 0.0)).coerceIn(1.0, (vw - 1.0).coerceAtLeast(1.0))
        val cssY = obj.optDouble("tapY", obj.optDouble("y", 0.0)).coerceIn(1.0, (vh - 1.0).coerceAtLeast(1.0))
        val x = (cssX / vw * wv.width).toFloat()
        val y = (cssY / vh * wv.height).toFloat()
        Log.d(TAG, "tap target label=${obj.optString("label")} css=$cssX,$cssY web=$x,$y rect=${obj.optDouble("left")},${obj.optDouble("top")},${obj.optDouble("right")},${obj.optDouble("bottom")} visible=${obj.optDouble("visibleWidth")},${obj.optDouble("visibleHeight")} safe=${obj.optBoolean("tapSafe", true)}")
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        runCatching { wv.dispatchTouchEvent(down) }; down.recycle()
        delay(50)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0)
        runCatching { wv.dispatchTouchEvent(up) }; up.recycle()
    }

    // ---------------------------------------------------------------- JS (webviewtest 이식)

    private fun buildEditorProbeJs(): String =
        """
        (function(){
          function rectOf(el){if(!el||!el.getBoundingClientRect)return {width:0,height:0,top:0,left:0,bottom:0,right:0}; const r=el.getBoundingClientRect(); return {width:r.width,height:r.height,top:r.top,left:r.left,bottom:r.bottom,right:r.right};}
          function visible(el){if(!el){return false;}const r=rectOf(el);const s=getComputedStyle(el);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth&&s.visibility!=="hidden"&&s.display!=="none";}
          const selectors=["#prompt-textarea","[data-testid='composer-text-input']","[data-testid='prompt-textarea']","textarea",".ProseMirror","[contenteditable]","[role='textbox']"];
          for(const q of selectors){
            const all=Array.from(document.querySelectorAll(q));
            const vis=all.filter(visible);
            const el=(vis.length?vis:all).slice(-1)[0];
            if(el){
              const r=rectOf(el);
              const text=("value" in el)?el.value:(el.innerText||el.textContent||"");
              return JSON.stringify({editorExists:true,selector:q,visible:visible(el),tag:el.tagName,id:el.id||"",role:el.getAttribute("role")||"",contenteditable:el.getAttribute("contenteditable")||"",textLen:String(text||"").length,rect:r,href:location.href,title:document.title});
            }
          }
          return JSON.stringify({editorExists:false,href:location.href,title:document.title,bodyTail:(document.body&&document.body.innerText||"").slice(-400),inputCount:document.querySelectorAll("textarea,[contenteditable],[role='textbox'],.ProseMirror").length});
        })();
        """.trimIndent()

    private fun buildComposerModeTargetJs(labels: List<String>): String {
        val targets = labels
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }
        return "(function(){" +
            "const targets=" + targets + ";" +
            "function rectOf(el){if(!el||!el.getBoundingClientRect)return {width:0,height:0,top:0,left:0,bottom:0,right:0};const r=el.getBoundingClientRect();return {width:r.width,height:r.height,top:r.top,left:r.left,bottom:r.bottom,right:r.right};}" +
            "function visible(el){const r=rectOf(el);const s=getComputedStyle(el);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "function labelOf(el){return String((el.innerText||el.ariaLabel||el.title||el.getAttribute&&el.getAttribute('aria-label')||el.textContent||'')).replace(/\\s+/g,' ').trim();}" +
            "function norm(txt){return String(txt||'').replace(/\\s+/g,' ').trim();}" +
            "function hit(txt){const n=norm(txt);return targets.some(function(t){const target=norm(t);if(!target)return false;if(/^pro$/i.test(target))return /^pro$/i.test(n);return n.indexOf(target)>=0;});}" +
            "function out(status,el,extra){const r=rectOf(el);return JSON.stringify(Object.assign({status:status,x:(r.left+r.right)/2,y:(r.top+r.bottom)/2,vw:window.innerWidth,vh:window.innerHeight,label:labelOf(el)},extra||{}));}" +
            "const editor=document.querySelector('#prompt-textarea,[data-testid=\"composer-text-input\"],[data-testid=\"prompt-textarea\"],textarea,.ProseMirror,[contenteditable],[role=\"textbox\"]');" +
            "const er=rectOf(editor);" +
            "const modeWords=/즉시|중간|높음|매우\\s*높음|Pro\\s*확장|\\bPro\\b|GPT-?5\\.5|instant|medium|high/i;" +
            "const controls=[...document.querySelectorAll('button,[role=\"button\"],[role=\"menuitem\"],[role=\"option\"],[data-radix-collection-item],label')].filter(visible);" +
            "const rows=controls.map(el=>({el:el,r:rectOf(el),txt:labelOf(el)})).filter(x=>x.txt);" +
            "const current=rows.filter(x=>modeWords.test(x.txt)&&x.r.top>window.innerHeight*0.70).sort((a,b)=>b.r.bottom-a.r.bottom||b.r.right-a.r.right)[0];" +
            "if(current&&hit(current.txt))return out('selected',current.el,{current:true});" +
            "const option=rows.filter(x=>hit(x.txt)&&(!current||x.r.bottom<current.r.top-8)&&x.r.top>0&&x.r.bottom<window.innerHeight*0.96).sort((a,b)=>a.r.top-b.r.top||a.r.left-b.r.left)[0];" +
            "if(option)return out('option',option.el);" +
            "const toggle=current||rows.filter(x=>modeWords.test(x.txt)&&x.r.top>er.bottom-180).sort((a,b)=>b.r.bottom-a.r.bottom||b.r.right-a.r.right)[0];" +
            "if(toggle)return out('toggle',toggle.el);" +
            "return JSON.stringify({status:'not-found',targets:targets.join('|'),labels:rows.slice(-20).map(x=>x.txt).join('|')});" +
            "})();"
    }

    /** 최신 MutationObserver 큐를 먼저 비우고, 필요한 경우에만 마지막 turn 폴백을 읽는다. */
    private fun buildContentFailedRetryJs(): String =
        "(function(){" +
            "function visible(el){if(!el||!el.getBoundingClientRect)return false;const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "const btn=[...document.querySelectorAll('button,[role=\"button\"]')].filter(visible).find(function(el){const t=String(el.innerText||el.ariaLabel||el.title||el.getAttribute('aria-label')||'');return /try again|다시 시도/i.test(t);});" +
            "if(btn){btn.click();return JSON.stringify({clicked:true,label:String(btn.innerText||btn.ariaLabel||'')});}" +
            "return JSON.stringify({clicked:false,bodyTail:String(document.body&&document.body.innerText||'').slice(-240)});" +
        "})();"

    private fun buildSnapshotJs(): String =
        DOM_QUEUE_RUNTIME_JS + "\n" + """
        (function(){
          const text=function(el){return (el&&((el.innerText||el.textContent)||"")).trim();};
          const visible=function(el){if(!el||!el.getBoundingClientRect)return false;const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";};
          const drained=window.__seoinDrainChangedTurns?window.__seoinDrainChangedTurns():{items:[],last:null,assistantCount:0,depth:0,seq:0};
          let last=drained.last;
          if(!last && window.__seoinLatestAssistantSnapshot){ last=window.__seoinLatestAssistantSnapshot(); }
          let lastAssistant=(last&&last.role==="assistant")?String(last.text||""):"";
          if(!lastAssistant && window.__seoinLastAssistant){ lastAssistant=text(window.__seoinLastAssistant()); }
          const editor=!!document.querySelector("#prompt-textarea,[data-testid='composer-text-input'],[data-testid='prompt-textarea'],textarea,.ProseMirror,[contenteditable],[role='textbox']");
          const buttons=Array.from(document.querySelectorAll("button,[role='button']")).filter(visible);
          const labels=buttons.map(function(b){return b.innerText||b.ariaLabel||b.title||b.getAttribute("aria-label")||"";}).join("\n");
          const stopVisible=/stop|중지|정지|생성 중지|응답 중지/i.test(labels);
          const uploadingVisible=/uploading|첨부 중|업로드|upload failed|파일을 처리/i.test(labels);
          function actionMenuReady(root){
            if(!root) return false;
            const rr=root.getBoundingClientRect?root.getBoundingClientRect():{top:0,bottom:0,left:0,right:0};
            const scope=root.closest&&(
              root.closest("[data-testid^='conversation-turn']") ||
              root.closest("article") ||
              root.parentElement
            );
            const actionWords=/copy|copied|good response|bad response|like|dislike|thumb|regenerate|share|edit|복사|좋은|나쁜|좋아요|싫어요|공유|다시 생성|편집/i;
            const candidates=[];
            if(scope&&scope.querySelectorAll){
              candidates.push(...Array.from(scope.querySelectorAll("button,[role='button']")));
            }
            candidates.push(...buttons.filter(function(b){
              const r=b.getBoundingClientRect();
              return r.top>=rr.top-24 && r.top<=rr.bottom+220 && r.left>=0 && r.right<=window.innerWidth+4;
            }));
            return candidates.some(function(b){
              const label=[b.innerText,b.ariaLabel,b.title,b.getAttribute&&b.getAttribute("aria-label"),b.getAttribute&&b.getAttribute("data-testid")].filter(Boolean).join(" ");
              return actionWords.test(label);
            });
          }
          const lastNode=window.__seoinLastAssistant?window.__seoinLastAssistant():null;
          const assistantActionsReady=actionMenuReady(lastNode);
          const bodyTail=lastAssistant.slice(-2400);
          return JSON.stringify({
            href:location.href,
            title:document.title,
            bodyLen:lastAssistant.length,
            bodyTail:bodyTail,
            assistantCount:drained.assistantCount||0,
            lastAssistant:lastAssistant,
            lastAssistantLen:lastAssistant.length,
            lastSource:last?last.source:"",
            lastTurnId:last?last.id:0,
            lastHash:last?last.hash:"",
            domQueueDepth:drained.depth||0,
            domQueueDrained:(drained.items||[]).length,
            domQueueSeq:drained.seq||0,
            editorExists:editor,
            stopVisible:stopVisible,
            uploadingVisible:uploadingVisible,
            assistantActionsReady:assistantActionsReady,
            visibilityState:document.visibilityState,
            hidden:document.hidden,
            hasFocus:document.hasFocus(),
            raf:window.__seoinRaf||0,
            ts:Date.now()
          });
        })();
        """.trimIndent()

    /** webviewtest buildPromptInjectionJs 그대로. */
    private fun buildPromptInjectionJs(prompt: String): String {
        val escaped = jsTemplate(prompt)
        return "(function(){" +
            "const text=`" + escaped + "`;" +
            "function visible(el){if(!el){return false;}const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "function pickEditor(){const selectors=['#prompt-textarea','[data-testid=\"composer-text-input\"]','[data-testid=\"prompt-textarea\"]','textarea','.ProseMirror','[contenteditable]','[role=\"textbox\"]'];for(const q of selectors){const all=[...document.querySelectorAll(q)];const list=all.filter(visible);if(list.length){return list[list.length-1];}if(all.length){return all[all.length-1];}}return null;}" +
            "function fire(el,type){try{el.dispatchEvent(new InputEvent(type,{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){el.dispatchEvent(new Event(type,{bubbles:true,cancelable:true}));}}" +
            "const el=pickEditor();" +
            "if(!el){return JSON.stringify({status:'no-editor'});}" +
            "el.focus();" +
            "if('value' in el){let proto=Object.getPrototypeOf(el);let desc=null;while(proto&&!desc){desc=Object.getOwnPropertyDescriptor(proto,'value');proto=Object.getPrototypeOf(proto);}if(desc&&desc.set){desc.set.call(el,text);}else{el.value=text;}fire(el,'beforeinput');fire(el,'input');el.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "else{const sel=window.getSelection();const range=document.createRange();el.innerHTML='';range.selectNodeContents(el);range.collapse(true);sel.removeAllRanges();sel.addRange(range);fire(el,'beforeinput');let inserted=false;try{inserted=document.execCommand&&document.execCommand('insertText',false,text);}catch(e){}if(!inserted){el.textContent=text;}fire(el,'input');el.dispatchEvent(new KeyboardEvent('keyup',{key:' ',bubbles:true}));}" +
            "const now=('value' in el)?el.value:(el.innerText||el.textContent||'');" +
            "const ok=now.length>0&&(now.indexOf(text.slice(0,Math.min(30,text.length)))>=0||text.indexOf(now.slice(0,Math.min(30,now.length)))>=0);" +
            "return JSON.stringify({status:ok?'prompt-injected':'prompt-empty',textLen:text.length,editorTextLen:now.length});" +
            "})();"
    }

    /** webviewtest buildSendJs 그대로. */
    private fun buildSendJs(mode: String): String {
        val m = jsString(mode)
        return "(function(){" +
            "const mode='" + m + "';" +
            "function visible(el){const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "const editor=document.querySelector('#prompt-textarea,[data-testid=\"composer-text-input\"],[data-testid=\"prompt-textarea\"],textarea,.ProseMirror,[contenteditable],[role=\"textbox\"]');" +
            "if(mode==='enter'&&editor){editor.focus();editor.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',which:13,keyCode:13,bubbles:true,cancelable:true}));editor.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',which:13,keyCode:13,bubbles:true,cancelable:true}));return 'enter-dispatched';}" +
            "const buttons=[...document.querySelectorAll('button')].filter(visible);" +
            "let btn=buttons.find(b=>!b.disabled&&(b.getAttribute('data-testid')==='send-button'||/send|보내기|전송/i.test(b.innerText||b.ariaLabel||b.title||b.getAttribute('aria-label')||'')));" +
            "if(!btn){btn=buttons.filter(b=>!b.disabled).map(b=>({b,r:b.getBoundingClientRect(),label:(b.innerText||b.ariaLabel||b.title||b.getAttribute('aria-label')||'')}))" +
            ".filter(x=>x.r.bottom>window.innerHeight*0.55&&x.r.right>window.innerWidth*0.55).sort((a,b)=>b.r.right-a.r.right)[0]?.b;}" +
            "if(btn){btn.click();return 'clicked-send-button';}" +
            "return 'no-enabled-send';" +
            "})();"
    }

    /** webviewtest buildAttachClickJs 그대로. */
    private fun buildAttachClickJs(mode: String): String {
        val m = jsString(mode)
        return "(function(){" +
            "const mode='" + m + "';" +
            "function label(el){return (el.innerText||el.ariaLabel||el.title||el.value||el.getAttribute('aria-label')||'').trim();}" +
            "function visible(el){const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "const words=/attach|upload|file|paperclip|photo|image|add|\\uCCA8\\uBD80|\\uD30C\\uC77C|\\uC5C5\\uB85C\\uB4DC|\\uC0AC\\uC9C4|\\uC774\\uBBF8\\uC9C0|\\uCD94\\uAC00/i;" +
            "const bad=/skip|content|login|sign in|voice|mic|dictation|extension|open image|\\bimage\\s+\\d+\\s*\\/\\s*\\d+\\b|\\.(jpe?g|png|webp|gif)\\b|\\uC774\\uBBF8\\uC9C0\\s*\\d+\\s*\\/\\s*\\d+|\\uC5F4\\uAE30\\s*:|\\uCF58\\uD150\\uCE20|\\uAC74\\uB108\\uB6F0|\\uB85C\\uADF8\\uC778|\\uD655\\uC7A5|\\uC74C\\uC131|\\uB9C8\\uC774\\uD06C/i;" +
            "const inputs=[...document.querySelectorAll('input[type=\"file\"]')];" +
            "if((mode.indexOf('direct-input')>=0||mode.indexOf('file-input')>=0)&&inputs.length){inputs[inputs.length-1].click();return 'clicked-file-input-js';}" +
            "const controls=[...document.querySelectorAll('button,[role=\"button\"],[role=\"menuitem\"],label')].filter(visible).filter(el=>!el.disabled);" +
            "let hit=controls.find(el=>words.test(label(el))&&!bad.test(label(el)));" +
            "if(!hit){const editor=document.querySelector('#prompt-textarea,[data-testid=\"composer-text-input\"],[data-testid=\"prompt-textarea\"],textarea,.ProseMirror,[contenteditable],[role=\"textbox\"]');" +
            " if(editor){const er=editor.getBoundingClientRect();hit=controls.filter(el=>!el.disabled).map(el=>({el,r:el.getBoundingClientRect()}))" +
            " .filter(x=>x.r.top<er.bottom+100&&x.r.bottom>er.top-120&&x.r.left>=0&&x.r.left<er.left+220&&!bad.test(label(x.el))).sort((a,b)=>a.r.left-b.r.left)[0]?.el;}}" +
            "if(hit){hit.click();return 'clicked-attach-control';}" +
            "if(inputs.length){inputs[inputs.length-1].click();return 'clicked-file-input-js-fallback';}" +
            "return 'no-attach-control';" +
            "})();"
    }

    /** webviewtest buildAttachTargetJs(attempt, "photo-menu") — 첨부 버튼 좌표를 돌려준다. */
    private fun buildAttachTargetJs(attempt: Int): String =
        "(function(){" +
            "const attempt=" + attempt + ";" +
            "function label(el){return (el.innerText||el.ariaLabel||el.title||el.value||el.getAttribute('aria-label')||'').trim();}" +
            "function visible(el){const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "const words=/attach|upload|file|paperclip|photo|image|add|\\uCCA8\\uBD80|\\uD30C\\uC77C|\\uC5C5\\uB85C\\uB4DC|\\uC0AC\\uC9C4|\\uC774\\uBBF8\\uC9C0|\\uCD94\\uAC00/i;" +
            "const bad=/skip|content|login|sign in|voice|mic|dictation|extension|open image|\\bimage\\s+\\d+\\s*\\/\\s*\\d+\\b|\\.(jpe?g|png|webp|gif)\\b|\\uC774\\uBBF8\\uC9C0\\s*\\d+\\s*\\/\\s*\\d+|\\uC5F4\\uAE30\\s*:|\\uCF58\\uD150\\uCE20|\\uAC74\\uB108\\uB6F0|\\uB85C\\uADF8\\uC778|\\uD655\\uC7A5|\\uC74C\\uC131|\\uB9C8\\uC774\\uD06C/i;" +
            "const controls=[...document.querySelectorAll('button,[role=\"button\"],label')].filter(visible).filter(el=>!el.disabled);" +
            "const editor=document.querySelector('#prompt-textarea,[data-testid=\"composer-text-input\"],[data-testid=\"prompt-textarea\"],textarea,.ProseMirror,[contenteditable],[role=\"textbox\"]');" +
            "const rows=controls.map(el=>({el,r:el.getBoundingClientRect(),label:label(el)})).filter(x=>x.r.top>=0&&!bad.test(x.label)).sort((a,b)=>a.r.top-b.r.top||a.r.left-b.r.left);" +
            "const wordRows=rows.filter(x=>words.test(x.label));" +
            "let nearRows=[];" +
            "if(editor){const er=editor.getBoundingClientRect();nearRows=rows.filter(x=>x.r.top<er.bottom+110&&x.r.bottom>er.top-140&&x.r.left>=0&&x.r.left<er.left+240&&x.r.right<er.left+320);}" +
            "let hit=null;" +
            "if(attempt===0){hit=(nearRows.find(x=>words.test(x.label))||wordRows[0]||nearRows.find(x=>x.label)||nearRows[0]);}" +
            "else if(attempt===1){hit=(wordRows[0]||nearRows.find(x=>words.test(x.label))||nearRows[0]);}" +
            "else{hit=(wordRows[0]||nearRows[0]);}" +
            "if(!hit){return JSON.stringify({found:false,vw:window.innerWidth,vh:window.innerHeight});}" +
            "function out(hit){const r=hit.r;const vt=Math.max(0,r.top),vb=Math.min(window.innerHeight,r.bottom),vl=Math.max(0,r.left),vr=Math.min(window.innerWidth,r.right);const visibleH=Math.max(0,vb-vt),visibleW=Math.max(0,vr-vl);const tapX=(vl+vr)/2,tapY=(vt+vb)/2;const clipped=r.top<0||r.bottom>window.innerHeight||r.left<0||r.right>window.innerWidth;const tapSafe=visibleH>=18&&visibleW>=18&&tapY>6&&tapY<window.innerHeight-6;return JSON.stringify({found:true,x:(r.left+r.right)/2,y:(r.top+r.bottom)/2,tapX:tapX,tapY:tapY,vw:window.innerWidth,vh:window.innerHeight,left:r.left,top:r.top,right:r.right,bottom:r.bottom,visibleWidth:visibleW,visibleHeight:visibleH,clipped:clipped,tapSafe:tapSafe,label:hit.label});}" +
            "return out(hit);" +
            "})();"

    /** webviewtest buildUploadMenuTargetJs(mode="photo-menu", attempt) — + 메뉴의 '사진/파일' 좌표. */
    private fun buildUploadMenuTargetJs(attempt: Int): String =
        "(function(){" +
            "const attempt=" + attempt + ";" +
            "function label(el){return (el.innerText||el.ariaLabel||el.title||el.value||el.getAttribute('aria-label')||'').trim();}" +
            "function visible(el){const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
            "function host(el){return el.closest('button,[role=\"button\"],[role=\"menuitem\"],label,[tabindex]')||el;}" +
            "const words=/upload|file|photo|image|browse|computer|device|\\uCCA8\\uBD80|\\uD30C\\uC77C|\\uC5C5\\uB85C\\uB4DC|\\uC0AC\\uC9C4|\\uC774\\uBBF8\\uC9C0|\\uAE30\\uAE30/i;" +
            "const photoExact=/^(photo|photos|image|images|add photos?|upload photo|\\uC0AC\\uC9C4|\\uC774\\uBBF8\\uC9C0)$/i;" +
            "const fileExact=/^(file|files|browse files?|upload file|upload from computer|\\uD30C\\uC77C)$/i;" +
            "const bad=/skip|content|login|sign in|voice|mic|dictation|extension|open image|\\bimage\\s+\\d+\\s*\\/\\s*\\d+\\b|\\.(jpe?g|png|webp|gif)\\b|\\uC774\\uBBF8\\uC9C0\\s*\\d+\\s*\\/\\s*\\d+|\\uC5F4\\uAE30\\s*:|\\uCF58\\uD150\\uCE20|\\uAC74\\uB108\\uB6F0|\\uB85C\\uADF8\\uC778|\\uD655\\uC7A5|\\uC74C\\uC131|\\uB9C8\\uC774\\uD06C/i;" +
            "const raw=[...document.querySelectorAll('button,[role=\"button\"],[role=\"menuitem\"],label,[tabindex],div,li,span')];" +
            "const seen=new Set();const rows=[];" +
            "for(const rawEl of raw){const el=host(rawEl);if(seen.has(el)||!visible(el)||el.disabled){continue;}seen.add(el);const l=norm(label(rawEl)||label(el));const r=el.getBoundingClientRect();const compact=r.width>=16&&r.height>=16&&r.width<=420&&r.height<=120&&l.length>0&&l.length<=90;if(r.top>=0&&!bad.test(l)&&(compact||photoExact.test(l)||fileExact.test(l))){rows.push({el,r,label:l});}}" +
            "rows.sort((a,b)=>a.r.top-b.r.top||a.r.left-b.r.left);" +
            "const photoRows=rows.filter(x=>photoExact.test(x.label));" +
            "const fileRows=rows.filter(x=>fileExact.test(x.label));" +
            "const uploadRows=rows.filter(x=>words.test(x.label)&&!/\\uD30C\\uC77C\\s*\\uCD94\\uAC00\\s*\\uBC0F\\s*\\uAE30\\uD0C0|add files? and more/i.test(x.label));" +
            "let hit=photoRows[0]||fileRows[0]||uploadRows[0]||null;" +
            "if(!hit){return JSON.stringify({found:false,vw:window.innerWidth,vh:window.innerHeight});}" +
            "function out(hit){const r=hit.r;const vt=Math.max(0,r.top),vb=Math.min(window.innerHeight,r.bottom),vl=Math.max(0,r.left),vr=Math.min(window.innerWidth,r.right);const visibleH=Math.max(0,vb-vt),visibleW=Math.max(0,vr-vl);const tapX=(vl+vr)/2,tapY=(vt+vb)/2;const clipped=r.top<0||r.bottom>window.innerHeight||r.left<0||r.right>window.innerWidth;const tapSafe=visibleH>=18&&visibleW>=18&&tapY>6&&tapY<window.innerHeight-6;return JSON.stringify({found:true,x:(r.left+r.right)/2,y:(r.top+r.bottom)/2,tapX:tapX,tapY:tapY,vw:window.innerWidth,vh:window.innerHeight,left:r.left,top:r.top,right:r.right,bottom:r.bottom,visibleWidth:visibleW,visibleHeight:visibleH,clipped:clipped,tapSafe:tapSafe,label:hit.label});}" +
            "return out(hit);" +
            "})();"

    private fun buildCaptureScript(minImageCount: Int): String = """
        (function() {
          try {
              const minCount=$minImageCount;
            if(window.__seoinEnsureDomQueue) window.__seoinEnsureDomQueue();
            const root=window.__seoinLastAssistant?window.__seoinLastAssistant():null;
            const scope=root||document;
            const seen=new Set();
            const imgs=Array.from(scope.querySelectorAll("img, img[src^='blob:'], img[src^='data:']")).filter(function(i){
              const src=i.currentSrc||i.src||""; if(!src||seen.has(src))return false; seen.add(src);
              const r=i.getBoundingClientRect();
              const w=i.naturalWidth||r.width||i.width||0; const h=i.naturalHeight||r.height||i.height||0;
              return w>=120 && h>=120;
            });
            const canvases=Array.from(document.querySelectorAll("main canvas")).filter(function(c){
              const r=c.getBoundingClientRect(); return (c.width||r.width)>=200 && (c.height||r.height)>=200;
            });
            const total=imgs.length+canvases.length;
            if(total < minCount){ SeoinBridge.onImageCaptured(""); return "waiting"; }
            const canvas=canvases.slice(-1)[0];
            if(canvas){ try { SeoinBridge.onImageCaptured(canvas.toDataURL("image/png")); return "canvas"; } catch(e){} }
            const img=imgs.slice(-1)[0];
            if(!img){ SeoinBridge.onImageCaptured(""); return "no-img"; }
            fetch(img.src).then(function(r){return r.blob();}).then(function(blob){
              const fr=new FileReader();
              fr.onload=function(){ SeoinBridge.onImageCaptured(String(fr.result||"")); };
              fr.onerror=function(){ SeoinBridge.onImageCaptured(""); };
              fr.readAsDataURL(blob);
            }).catch(function(e){ SeoinBridge.onImageCaptured(""); });
            return "fetching";
          } catch(e){ try{SeoinBridge.onImageCaptured("");}catch(_){} return "err"; }
        })();
    """.trimIndent()

    private fun buildImageCompletionProbeJs(minImageCount: Int): String = """
        (function() {
          try {
            const minCount=$minImageCount;
            const root=window.__seoinLastAssistant?window.__seoinLastAssistant():null;
            const scope=root||document;
            const actionWords=/copy|copied|good response|bad response|like|dislike|thumb|regenerate|share|edit|download|복사|좋은|좋음|나쁜|좋아요|싫어요|공유|다시 생성|편집|다운로드/i;
            function visible(el){
              if(!el) return false;
              const r=el.getBoundingClientRect();
              const s=getComputedStyle(el);
              return r.width>0 && r.height>0 && r.bottom>0 && r.right>0 && r.top<window.innerHeight && r.left<window.innerWidth && s.visibility!=="hidden" && s.display!=="none";
            }
            function label(el){
              return [el.innerText,el.ariaLabel,el.title,el.getAttribute&&el.getAttribute("aria-label"),el.getAttribute&&el.getAttribute("data-testid")]
                .filter(Boolean).join(" ").replace(/\s+/g," ").trim();
            }
            const seen=new Set();
            const imgs=Array.from(scope.querySelectorAll("img, img[src^='blob:'], img[src^='data:']")).filter(function(i){
              const src=i.currentSrc||i.src||""; if(!src||seen.has(src))return false; seen.add(src);
              const r=i.getBoundingClientRect();
              const w=i.naturalWidth||r.width||i.width||0; const h=i.naturalHeight||r.height||i.height||0;
              return w>=120 && h>=120;
            });
            const canvases=Array.from(document.querySelectorAll("main canvas")).filter(function(c){
              const r=c.getBoundingClientRect(); return (c.width||r.width)>=200 && (c.height||r.height)>=200;
            });
            const total=imgs.length+canvases.length;
            function allActionButtons(){
              const q="button,[role='button'],[aria-label],[data-testid]";
              const fromRoot=root?Array.from(root.querySelectorAll(q)):[];
              const fromDoc=Array.from(document.querySelectorAll(q));
              return Array.from(new Set(fromRoot.concat(fromDoc))).filter(visible);
            }
            function actionNearRect(rect){
              return allActionButtons().some(function(b){
                const br=b.getBoundingClientRect();
                const horizontal=br.right>=rect.left-40 && br.left<=rect.right+40;
                const below=br.top>=rect.bottom-24 && br.top<=rect.bottom+300;
                return horizontal && below && actionWords.test(label(b));
              });
            }
            function actionNearRoot(){
              if(!root) return false;
              const rr=root.getBoundingClientRect();
              return allActionButtons().some(function(b){
                const br=b.getBoundingClientRect();
                return br.top>=rr.top-24 && br.top<=rr.bottom+260 && actionWords.test(label(b));
              });
            }
            const visual=canvases.slice(-1)[0]||imgs.slice(-1)[0]||null;
            const visualReady=!!visual && actionNearRect(visual.getBoundingClientRect());
            const rootReady=actionNearRoot();
            const ready=total>=minCount && (visualReady || rootReady);
            return JSON.stringify({ready:ready,total:total,minCount:minCount,visualReady:visualReady,rootReady:rootReady});
          } catch(e) {
            return JSON.stringify({ready:false,error:String(e)});
          }
        })();
    """.trimIndent()

    private fun buildJsonChunkCaptureScript(
        id: String,
        minAssistantCount: Int,
        previous: AssistantMarker?,
    ): String {
        val quotedId = JSONObject.quote(id)
        val previousHash = JSONObject.quote(previous?.hash.orEmpty())
        val previousTurnId = previous?.turnId ?: 0
        return """
            (function() {
              const id=$quotedId; const minCount=$minAssistantCount; const prevId=$previousTurnId; const prevHash=$previousHash; const bridge=window.SeoinBridge;
              const fail=function(msg){ try{ bridge.onTextCaptureError(id, String(msg)); }catch(_){} return String(msg); };
              try {
                if(!bridge || !bridge.onTextChunk) return "no-bridge";
                if(window.__seoinEnsureDomQueue) window.__seoinEnsureDomQueue();
                const meta=window.__seoinDrainChangedTurns?window.__seoinDrainChangedTurns():{assistantCount:0,last:null};
                const count=meta.assistantCount||0;
                const latest=window.__seoinLatestAssistantSnapshot?window.__seoinLatestAssistantSnapshot():(meta.last||null);
                if(!latest) return fail("no-assistant-snapshot");
                const same=(!!prevHash && latest.hash===prevHash) || (prevId>0 && latest.id===prevId && (!prevHash || latest.hash===prevHash));
                if(count < minCount && !latest) return fail("waiting-new-assistant");
                const last=window.__seoinLastAssistant?window.__seoinLastAssistant():null;
                const roots=[];
                const seen=new Set();
                const addRoot=function(el){ if(el && !seen.has(el)){ seen.add(el); roots.push(el); } };
                addRoot(last);
                Array.from(document.querySelectorAll("[data-message-author-role='assistant'], article")).reverse().slice(0,8).forEach(addRoot);
                if(!roots.length) return fail("no-assistant");
                const candidates=[];
                roots.forEach(function(root){
                  Array.from(root.querySelectorAll("pre code, pre, code")).reverse()
                    .map(function(el){return el.innerText || el.textContent || "";})
                    .filter(function(t){return t.trim().length>0;})
                    .forEach(function(t){ candidates.push(t); });
                  candidates.push(root.innerText || root.textContent || "");
                });
                const main=document.querySelector("main");
                if(main) candidates.push(main.innerText || main.textContent || "");
                const balancedJson=function(raw) {
                  const text=String(raw||"").replace(/```json/gi, "```").replace(/```/g, "").trim();
                  let found="";
                  for(let i=0;i<text.length;i++){
                    const ch=text[i]; if(ch!=="{" && ch!=="[") continue;
                    const close=ch==="{" ? "}" : "]"; const stack=[close]; let inStr=false, esc=false;
                    for(let j=i+1;j<text.length;j++){
                      const c=text[j];
                      if(inStr){ if(esc){esc=false;continue;} if(c==="\\"){esc=true;continue;} if(c==="\"")inStr=false; continue; }
                      if(c==="\""){ inStr=true; continue; }
                      if(c==="{") stack.push("}"); else if(c==="[") stack.push("]");
                      else if(c==="}" || c==="]"){ if(stack.length===0||c!==stack[stack.length-1])break; stack.pop(); if(stack.length===0){ found=text.slice(i, j+1); i=j; break; } }
                    }
                  }
                  return found;
                };
                let json=""; let fallback="";
                for(const c of candidates){
                  const picked=balancedJson(c);
                  if(!picked) continue;
                  if(String(picked).trim().charAt(0)==="{"){ json=picked; break; }
                  fallback=picked;
                }
                if(!json) json=fallback;
                if(!json) return fail(same ? "same-assistant" : "no-json");
                const chunkSize=8192; const total=Math.max(1, Math.ceil(json.length / chunkSize));
                bridge.onTextCaptureStart(id, total);
                for(let i=0;i<total;i++){ bridge.onTextChunk(id, i, total, json.slice(i*chunkSize, (i+1)*chunkSize)); }
                return "chunking:"+total;
              } catch(e) { return fail("err:"+String(e)); }
            })();
        """.trimIndent()
    }

    private fun buildDownloadProbeJs(baseName: String, wantsImage: Boolean): String {
        val safeBase = jsString(baseName)
        val imageFlag = if (wantsImage) "true" else "false"
        return "(function(){" +
            "const baseName='" + safeBase + "';const wantsImage=" + imageFlag + ";" +
            "function label(el){return (el.innerText||el.ariaLabel||el.title||el.value||el.getAttribute('aria-label')||el.download||'').trim();}" +
            "function visible(el){if(!el){return false;}const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<window.innerHeight&&r.left<window.innerWidth&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "function text(){const nodes=[...document.querySelectorAll('[data-message-author-role=\"assistant\"], article')];const texts=nodes.map(n=>(n.innerText||'').trim()).filter(Boolean);return texts.length?texts[texts.length-1]:(document.body?document.body.innerText:'');}" +
            "function dataText(t){return 'data:text/plain;base64,'+btoa(unescape(encodeURIComponent(t||'')));}" +
            "function saveUrl(url,name,mime){if(window.__seoinSaveUrl){return window.__seoinSaveUrl(url,name,mime);}return 'no-save-url';}" +
            "function balancedJson(raw){const s=String(raw||'').replace(/```json/gi,'```').replace(/```/g,'').trim();for(let i=0;i<s.length;i++){const ch=s[i];if(ch!=='{'&&ch!=='[')continue;const stack=[ch==='{'?'}':']'];let inStr=false,esc=false;for(let j=i+1;j<s.length;j++){const c=s[j];if(inStr){if(esc){esc=false;continue;}if(c==='\\\\'){esc=true;continue;}if(c==='\\\"')inStr=false;continue;}if(c==='\\\"'){inStr=true;continue;}if(c==='{')stack.push('}');else if(c==='[')stack.push(']');else if(c==='}'||c===']'){if(!stack.length||c!==stack[stack.length-1])break;stack.pop();if(!stack.length)return s.slice(i,j+1);}}}return '';}" +
            "const downloadWords=/download|save|export|open image|view image|json|txt|text|다운로드|저장|받기|내보내기|텍스트|이미지\\s*열기/i;" +
            "const nonDownload=/add files?|add\\s*photos?|attach|upload|camera|photo|image\\s*upload|파일\\s*등\\s*추가|파일\\s*추가|첨부|업로드|카메라|사진|이미지\\s*추가|이미지\\s*만들기/i;" +
            "const nodes=[...document.querySelectorAll('a,button,[role=\"button\"],[download]')].filter(visible).map(el=>({el,txt:label(el),href:el.href||el.getAttribute('href')||''})).filter(x=>!nonDownload.test(x.txt));" +
            "const link=nodes.find(x=>x.href&&(x.el.download||/^blob:|^data:|\\.(png|jpe?g|webp|gif|json|txt|md|pdf)(\\?|#|$)/i.test(x.href)||downloadWords.test(x.txt)));" +
            "if(link){const name=link.el.download||baseName+(wantsImage?'.png':'.json');return saveUrl(link.href,name,wantsImage?'image/png':'application/json');}" +
            "const btn=nodes.find(x=>!x.href&&downloadWords.test(x.txt));" +
            "if(btn){btn.el.click();return 'clicked-download-button:'+btn.txt.slice(0,60);}" +
            "if(wantsImage){const imgs=[...document.images].filter(visible).map(img=>({src:img.currentSrc||img.src||'',area:(img.naturalWidth||0)*(img.naturalHeight||0)})).filter(x=>x.area>9000&&!/avatar|icon|logo/i.test(x.src)).sort((a,b)=>b.area-a.area);if(imgs.length){return saveUrl(imgs[0].src,baseName+'.png','image/png');}}" +
            "if(!wantsImage){const raw=text();const json=balancedJson(raw);if(json){SeoinBridge.saveBase64File(dataText(json),baseName+'.json','application/json');return 'assistant-json-save-started';}return 'no-json-download-source:textLen='+String(raw||'').length;}" +
            "return 'no-download-source';" +
            "})();"
    }

    // webviewtest jsTemplate / jsString 그대로.
    private fun jsTemplate(text: String?): String = text?.replace("\\", "\\\\")?.replace("`", "\\`")
        ?.replace("$", "\\$")?.replace("\r", "\\r")?.replace("\n", "\\n") ?: ""

    private fun jsString(text: String?): String = text?.replace("\\", "\\\\")?.replace("'", "\\'")
        ?.replace("\r", "\\r")?.replace("\n", "\\n") ?: ""

    private companion object {
        const val TAG = "SeoinAuthoring"
        const val CHATGPT_URL = "https://chatgpt.com/"
        const val MAX_CHATGPT_Q_URL_CHARS = 7_500
        const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        const val EDITOR_TIMEOUT_MS = 120_000L          // 로그인/에디터 등장 대기(첫 1회 로그인 포함).
        // 첨부(webviewtest 상수 그대로).
        const val ATTACH_MAX_ATTEMPTS = 4
        const val ATTACH_RETRY_WAIT_MS = 4_500L
        const val ATTACH_MENU_PROBE_DELAY_MS = 850L
        const val UPLOAD_SETTLE_MS = 14_000L
        // 응답.
        const val SEND_MAX_ATTEMPTS = 18
        const val MODE_SELECT_MAX_ATTEMPTS = 5
        const val RESPONSE_STABLE_MS = 12_000L
        const val RESPONSE_TIMEOUT_MS = 210_000L
        const val RESPONSE_POLL_MS = 2_200L
        const val CAPTURE_TIMEOUT_MS = 15_000L
        const val TEXT_CAPTURE_TIMEOUT_MS = 12_000L
        const val DOWNLOAD_READ_TIMEOUT_MS = 180_000L

        val DOWNLOAD_BRIDGE_JS = """
            (function(){
              if(window.__seoinDownloadBridgeInstalled) return "download-bridge-exists";
              window.__seoinDownloadBridgeInstalled=true;
              function nameOf(el,fallback){return (el&&(el.download||el.getAttribute&&el.getAttribute("download")||el.title||el.ariaLabel||el.innerText)||fallback||"seoin-download.bin").trim();}
              function saveUrl(url,name,mime){
                try{
                  if(!url) return "no-url";
                  name=name||"seoin-download.bin";
                  mime=mime||"application/octet-stream";
                  if(/^blob:/i.test(url)){
                    fetch(url).then(function(r){return r.blob();}).then(function(b){
                      const reader=new FileReader();
                      reader.onloadend=function(){ SeoinBridge.saveBase64File(String(reader.result||""),name,b.type||mime); };
                      reader.onerror=function(){ SeoinBridge.onDownloadEvent("blob-reader-error",name); };
                      reader.readAsDataURL(b);
                    }).catch(function(e){ SeoinBridge.onDownloadEvent("blob-fetch-error",String(e)); });
                    return "blob-save-started:"+name;
                  }
                  if(/^data:/i.test(url)){ SeoinBridge.saveBase64File(url,name,mime); return "data-save-started:"+name; }
                  if(/^https?:/i.test(url)){ SeoinBridge.requestHttpDownload(url,name,mime); return "http-download-requested:"+name; }
                  return "unsupported-url:"+String(url).slice(0,40);
                }catch(e){ try{SeoinBridge.onDownloadEvent("save-url-error",String(e));}catch(_){} return "save-url-error"; }
              }
              window.__seoinSaveUrl=saveUrl;
              document.addEventListener("click",function(e){
                const target=e.target&&e.target.closest?e.target.closest("a[href]"):null;
                if(!target) return;
                const href=target.href||target.getAttribute("href")||"";
                if(!/^blob:|^data:/i.test(href)) return;
                e.preventDefault();
                saveUrl(href,nameOf(target,"seoin-download.bin"),target.type||"application/octet-stream");
              },true);
              return "download-bridge-installed";
            })();
        """.trimIndent()

        val DOM_QUEUE_RUNTIME_JS = """
            (function(){
              if(window.__seoinEnsureDomQueue){ return window.__seoinEnsureDomQueue(); }
              const assistantRoleSelector="[data-message-author-role='assistant']";
              const userRoleSelector="[data-message-author-role='user']";
              const assistantSelector="[data-message-author-role='assistant'], article";
              const turnSelector="[data-message-author-role], article, [data-testid^='conversation-turn'], [data-testid*='conversation-turn']";
              const maxQueue=24;

              const state={
                queue:[],
                ids:new WeakMap(),
                seenAssistant:new Set(),
                nextId:1,
                seq:0,
                seeded:false,
                lastAssistantNode:null,
                lastAssistantSnapshot:null,
                lastTurnSnapshot:null,
                container:null
              };
              window.__seoinDomQueueState=state;

              const text=function(el){
                if(!el) return "";
                return String(el.innerText||el.textContent||"").replace(/\s+/g," ").trim();
              };
              const idOf=function(el){
                if(!el) return 0;
                let id=state.ids.get(el);
                if(!id){ id=state.nextId++; state.ids.set(el,id); }
                return id;
              };
              const hashOf=function(value){
                const s=String(value||"");
                return s.length+":"+s.slice(0,80)+":"+s.slice(-160);
              };
              const visible=function(el){
                if(!el||!el.getBoundingClientRect) return false;
                const r=el.getBoundingClientRect();
                const s=getComputedStyle(el);
                return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none";
              };
              const roleOf=function(el){
                if(!el||!el.matches) return "";
                const direct=el.matches("[data-message-author-role]")?el:null;
                const nested=direct||el.querySelector&&el.querySelector("[data-message-author-role]");
                const role=nested?nested.getAttribute("data-message-author-role"):"";
                if(role) return role;
                if(el.matches("article")) return "assistant";
                return "";
              };
              const findContainer=function(){
                if(state.container&&state.container.isConnected) return state.container;
                const cached=window.__androidWtbTurns;
                if(cached&&cached.isConnected){ state.container=cached; return cached; }
                const main=document.querySelector("main")||document.body||document.documentElement;
                const any=main&&main.querySelector?main.querySelector(assistantRoleSelector+","+userRoleSelector):null;
                state.container=any?(any.closest("main")||main):main;
                window.__androidWtbTurns=state.container;
                return state.container;
              };
              const lastMatch=function(root){
                if(!root) return null;
                if(root.matches&&root.matches(assistantSelector)) return root;
                const list=root.querySelectorAll?root.querySelectorAll(assistantSelector):[];
                return list.length?list[list.length-1]:null;
              };
              const findLastAssistant=function(){
                const cont=findContainer();
                for(let el=cont&&cont.lastElementChild;el;el=el.previousElementSibling){
                  const hit=lastMatch(el);
                  if(hit) return hit;
                }
                const all=(cont||document).querySelectorAll? (cont||document).querySelectorAll(assistantSelector):[];
                return all.length?all[all.length-1]:null;
              };
              const seedAssistantCount=function(){
                if(state.seeded) return;
                state.seeded=true;
                const cont=findContainer();
                const list=(cont||document).querySelectorAll?Array.from((cont||document).querySelectorAll(assistantRoleSelector)):[];
                list.forEach(function(el){ state.seenAssistant.add(idOf(el)); });
              };
              const closestTurn=function(node){
                let el=node&&node.nodeType===3?node.parentElement:node;
                if(!el||!el.closest) return null;
                const direct=el.closest(turnSelector);
                if(!direct) return null;
                if(direct.matches&&direct.matches(assistantSelector)) return direct;
                const hit=lastMatch(direct);
                return hit||direct;
              };
              const pickMutationNode=function(node){
                let el=node&&node.nodeType===3?node.parentElement:node;
                if(!el) return null;
                if(el.matches&&el.matches(assistantSelector+","+userRoleSelector)) return el;
                const hit=lastMatch(el);
                if(hit) return hit;
                return closestTurn(el);
              };
              const queueNode=function(node,source){
                const el=closestTurn(node)||node;
                if(!el||!el.isConnected) return null;
                const role=roleOf(el);
                if(role!=="assistant"&&role!=="user") return null;
                const value=text(el);
                if(value.length===0) return null;
                const id=idOf(el);
                if(role==="assistant") state.seenAssistant.add(id);
                const snap={
                  id:id,
                  seq:++state.seq,
                  role:role,
                  len:value.length,
                  hash:hashOf(value),
                  text:value,
                  source:String(source||"mutation"),
                  ts:Date.now()
                };
                const prev=state.lastTurnSnapshot;
                if(prev&&prev.id===snap.id&&prev.hash===snap.hash){
                  return prev;
                }
                state.queue.push(snap);
                while(state.queue.length>maxQueue) state.queue.shift();
                state.lastTurnSnapshot=snap;
                if(role==="assistant"){
                  state.lastAssistantNode=el;
                  state.lastAssistantSnapshot=snap;
                }
                return snap;
              };

              const ensure=function(){
                seedAssistantCount();
                if(!state.lastAssistantNode||!state.lastAssistantNode.isConnected){
                  const last=findLastAssistant();
                  if(last) queueNode(last,"ensure-last");
                }
                return "ok";
              };

              window.__seoinEnsureDomQueue=ensure;
              window.__seoinLastAssistant=function(){
                ensure();
                const fresh=findLastAssistant();
                return fresh||(state.lastAssistantNode&&state.lastAssistantNode.isConnected?state.lastAssistantNode:null);
              };
              window.__seoinLatestAssistantSnapshot=function(){
                ensure();
                const fresh=queueNode(window.__seoinLastAssistant(),"latest-poll");
                return fresh||state.lastAssistantSnapshot||null;
              };
              window.__seoinDrainChangedTurns=function(){
                ensure();
                const items=state.queue.splice(0,state.queue.length);
                return {
                  items:items,
                  last:state.lastAssistantSnapshot||null,
                  assistantCount:state.seenAssistant.size,
                  depth:state.queue.length,
                  seq:state.seq
                };
              };

              const observer=new MutationObserver(function(mutations){
                let processed=0;
                for(const m of mutations){
                  if(processed>16) break;
                  const target=pickMutationNode(m.target);
                  if(target){ queueNode(target,"target"); processed++; }
                  for(const added of Array.from(m.addedNodes||[])){
                    if(processed>16) break;
                    const picked=pickMutationNode(added);
                    if(picked){ queueNode(picked,"added"); processed++; }
                  }
                }
              });
              findContainer();
              const root=document.documentElement||document.body||state.container;
              if(root) observer.observe(root,{subtree:true,childList:true,characterData:true});

              if(!window.__seoinRafStarted){
                window.__seoinRafStarted=true;
                const tick=function(){ window.__seoinRaf=(window.__seoinRaf||0)+1; requestAnimationFrame(tick); };
                requestAnimationFrame(tick);
              }
              ensure();
              return "installed";
            })();
        """.trimIndent()

        val SINGLE_WINDOW_GUARD_JS = """
            (function(){
              if(window.__seoinSingleWindowGuard) return "already";
              window.__seoinSingleWindowGuard=true;
              window.open=function(){ return null; };
              const fix=function(){ document.querySelectorAll("a[target]").forEach(function(a){ a.setAttribute("target","_self"); }); };
              fix(); setInterval(fix, 1000); return "ok";
            })();
        """.trimIndent()
    }
}
