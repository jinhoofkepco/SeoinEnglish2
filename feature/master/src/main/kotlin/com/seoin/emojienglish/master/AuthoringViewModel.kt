package com.seoin.emojienglish.master

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seoin.emojienglish.content.ContentWriter
import com.seoin.emojienglish.content.LessonRepository
import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.LessonUnit
import com.seoin.emojienglish.model.StepDef
import com.seoin.emojienglish.voice.AuthoringWebGateway
import com.seoin.emojienglish.voice.WebViewPictureGateway
import com.seoin.emojienglish.voice.WebViewVoiceGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject

/**
 * 마스터 저작 도구 — story_comic(단어만화) 단원을 GPT로 만든다.
 *
 * 3단계 순차(요구사항): ① 소스에서 **단어 추출** → ② 그 단어로 **단원 JSON 생성** →
 * ③ 단어별 **그림 생성**(현재는 프롬프트만 보내고 수동 다운로드; 파일명·경로만 맞으면
 * 런타임 로더가 바로 반영). 산출물은 `filesDir/books/...`에 써서 즉시 앱에 보이게 한다.
 */
@HiltViewModel
class AuthoringViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gateway: AuthoringWebGateway,
    private val voiceGateway: WebViewVoiceGateway,
    private val pictureGateway: WebViewPictureGateway,
    private val writer: ContentWriter,
    private val lessonRepo: LessonRepository,
) : ViewModel() {

    data class RepairUnitChoice(
        val bookId: String,
        val bookTitle: String,
        val unitId: String,
        val unitTitle: String,
        val wordCount: Int,
        val missingImageCount: Int,
    ) {
        val label: String
            get() = "$unitTitle · 단어 $wordCount · 누락 $missingImageCount"
    }

    data class State(
        val bookId: String = "book_a",
        val bookTitle: String = "Emoji English Book A",
        val unitId: String = "",
        val unitTitle: String = "",
        val source: String = "",
        val running: Boolean = false,
        val status: String = "",
        val words: List<String> = emptyList(),
        val sentences: List<String> = emptyList(),
        val unitJsonPreview: String = "",
        val savedUnit: Boolean = false,
        val error: String? = null,
        val passagePhotoCount: Int = 0,
        val wordPhotoCount: Int = 0,
        val repairChoices: List<RepairUnitChoice> = emptyList(),
        val repairChoiceIndex: Int = 0,
        val wordCardPassage: String = "",
        val wordCardBracketedText: String = "",
        val wordCardCandidates: List<WordCardCandidate> = emptyList(),
        val wordCardPrompt: String = DEFAULT_WORD_CARD_PROMPT,
        val wordCardCandidateVersion: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var passagePhotoUris: List<Uri> = emptyList()
    private var wordPhotoUris: List<Uri> = emptyList()
    private var activeStepJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private data class PageSentences(
        val pageNumber: Int,
        val sentences: List<String>,
    )

    private data class PassageJsonBatch(
        val startNumber: Int,
        val sentences: List<String>,
    )

    private data class PassageDownloadResult(
        val downloads: List<String>,
        val finalJsonText: String,
    )

    private data class RepairWord(
        val key: String,
        val text: String,
        val definition: String,
    )

    data class WordCardCandidate(
        val word: String,
        val src: String = "",
        val selected: Boolean = false,
    )

    private data class ImageMeta(
        val uri: Uri,
        val id: Long,
        val modified: Long,
        val bucketId: String?,
        val relativePath: String?,
        val displayName: String?,
    )

    init {
        isolateAuthoringWebView()
    }

    fun provideView(): WebView {
        isolateAuthoringWebView()
        return gateway.provideView()
    }

    fun clearAuthoringDraftOnEntry() {
        if (_state.value.running) return
        viewModelScope.launch {
            runCatching { gateway.clearComposerDraftOnEntry() }
                .onFailure { e -> Log.d("AuthoringViewModel", "entry draft clear skipped: ${e.message}") }
        }
    }

    private fun isolateAuthoringWebView() {
        voiceGateway.shutdownForAuthoring()
        pictureGateway.shutdownForAuthoring()
    }

    /** 렌더러가 죽어 웹뷰가 새로 만들어진 횟수 — 화면이 AndroidView를 다시 붙이는 key. */
    val webReloads: StateFlow<Int> = gateway.reloads
    val http431State = gateway.http431State

    fun retryChatGptAfter431() {
        gateway.retryAfterHttp431()
        _state.update { it.copy(status = "ChatGPT를 다시 로드합니다.", error = null) }
    }

    fun openChatGptInBrowser() {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { e ->
            _state.update { it.copy(error = "브라우저 열기 실패: ${e.message}") }
        }
    }

    fun clearTemporaryChatCookiesAfter431() {
        viewModelScope.launch {
            val count = gateway.clearTemporaryChatCookiesAfter431()
            _state.update {
                it.copy(
                    status = if (count > 0) "임시 채팅 쿠키 ${count}개를 정리하고 ChatGPT를 다시 로드합니다." else "정리할 임시 채팅 쿠키가 없습니다.",
                    error = null,
                )
            }
        }
    }

    fun clearAllInAppWebViewCookiesAfterConfirmation() {
        viewModelScope.launch {
            gateway.clearAllWebViewCookiesAfterConfirmation()
            _state.update {
                it.copy(
                    status = "앱 내 WebView 쿠키를 전체 초기화하고 ChatGPT를 다시 로드합니다. 다시 로그인이 필요할 수 있습니다.",
                    error = null,
                )
            }
        }
    }

    fun setPhoto(uri: Uri?) {
        setPassagePhotos(uri?.let { listOf(it) }.orEmpty())
    }

    fun setPassagePhotos(uris: List<Uri>) {
        passagePhotoUris = uris
        _state.update {
            it.copy(
                passagePhotoCount = uris.size,
                status = if (uris.isNotEmpty()) {
                    "지문사진 ${uris.size}장 준비됨 — 전체자동 또는 문장 추출을 시작할 수 있습니다."
                } else it.status,
            )
        }
    }

    fun addPassagePhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val before = passagePhotoUris.size
        val merged = mergePhotoUris(passagePhotoUris, uris)
        passagePhotoUris = merged
        val added = merged.size - before
        _state.update {
            it.copy(
                passagePhotoCount = merged.size,
                error = null,
                status = "지문사진 ${merged.size}장 준비됨 — 이번 선택에서 ${added.coerceAtLeast(0)}장 추가됐습니다.",
            )
        }
    }

    fun setWordPhotos(uris: List<Uri>) {
        wordPhotoUris = uris
        _state.update {
            it.copy(
                wordPhotoCount = uris.size,
                status = if (uris.isNotEmpty()) {
                    "단어사진 ${uris.size}장 준비됨 — 전체자동 또는 단어 추출을 시작할 수 있습니다."
                } else it.status,
            )
        }
    }

    fun addWordPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val before = wordPhotoUris.size
        val merged = mergePhotoUris(wordPhotoUris, uris)
        wordPhotoUris = merged
        val added = merged.size - before
        _state.update {
            it.copy(
                wordPhotoCount = merged.size,
                error = null,
                status = "단어사진 ${merged.size}장 준비됨 — 이번 선택에서 ${added.coerceAtLeast(0)}장 추가됐습니다.",
            )
        }
    }

    fun addPassagePhotoRange(uris: List<Uri>) {
        addPhotoRange(uris, isPassage = true)
    }

    fun addWordPhotoRange(uris: List<Uri>) {
        addPhotoRange(uris, isPassage = false)
    }

    fun showPhotoRangePermissionDenied() {
        _state.update { it.copy(error = "사진 범위 선택은 사진 읽기 권한이 필요합니다.") }
    }

    private fun addPhotoRange(anchors: List<Uri>, isPassage: Boolean) {
        if (_state.value.running) return
        if (anchors.size != 2) {
            _state.update { it.copy(error = "범위 선택은 시작/끝 사진 2장을 골라야 합니다.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(error = null, status = "사진 범위 찾는 중…") }
            runCatching {
                withContext(Dispatchers.IO) { resolvePhotoRange(anchors) }
            }.onSuccess { range ->
                if (isPassage) {
                    val before = passagePhotoUris.size
                    val merged = mergePhotoUris(passagePhotoUris, range)
                    passagePhotoUris = merged
                    val added = merged.size - before
                    _state.update {
                        it.copy(
                            passagePhotoCount = merged.size,
                            error = null,
                            status = "지문사진 범위 ${range.size}장 확인 · 누적 ${merged.size}장(${added.coerceAtLeast(0)}장 추가).",
                        )
                    }
                } else {
                    val before = wordPhotoUris.size
                    val merged = mergePhotoUris(wordPhotoUris, range)
                    wordPhotoUris = merged
                    val added = merged.size - before
                    _state.update {
                        it.copy(
                            wordPhotoCount = merged.size,
                            error = null,
                            status = "단어사진 범위 ${range.size}장 확인 · 누적 ${merged.size}장(${added.coerceAtLeast(0)}장 추가).",
                        )
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: "사진 범위 선택 실패") }
            }
        }
    }

    fun setBookId(v: String) = _state.update { it.copy(bookId = slugId(v).ifBlank { "book_a" }) }
    fun setBookTitle(v: String) = _state.update { it.copy(bookTitle = v) }
    fun setUnitId(v: String) = _state.update { it.copy(unitId = slugId(v)) }
    fun setUnitTitle(v: String) = _state.update { it.copy(unitTitle = v) }
    fun setSource(v: String) = _state.update { it.copy(source = v) }

    /** ①a 소스 텍스트에서 단어 추출. */
    fun extractFromSource() {
        val s = _state.value
        if (s.running) return
        if (s.source.isBlank()) { _state.update { it.copy(error = "소스 텍스트를 입력하세요.") }; return }
        runStep("① 소스에서 단어 추출 중…") {
            val words = extractWords(s.source)
            if (words.isEmpty()) error("단어 추출 실패(응답에서 배열을 못 찾음).")
            _state.update { it.copy(words = words, status = "단어 ${words.size}개 추출됨. 다음 ‘② JSON 생성’.") }
        }
    }

    /** 사진이 있으면 사진, 없으면 소스 텍스트에서 단어만 추출. */
    fun extractWordsAuto() {
        val s = _state.value
        if (s.running) return
        when {
            s.wordPhotoCount > 0 -> extractFromPhoto()
            s.source.isNotBlank() -> extractFromSource()
            else -> _state.update { it.copy(error = "사진을 고르거나 소스 텍스트를 입력하세요.") }
        }
    }

    /** 사진이 있으면 사진, 없으면 소스 텍스트에서 지문 문장을 추출. */
    fun extractSentencesAuto() {
        val s = _state.value
        if (s.running) return
        when {
            s.passagePhotoCount > 0 -> extractSentencesFromPhoto()
            s.source.isNotBlank() -> extractSentencesFromSource()
            else -> _state.update { it.copy(error = "사진을 고르거나 지문 텍스트를 입력하세요.") }
        }
    }

    fun extractSentencesFromSource() {
        val s = _state.value
        if (s.running) return
        if (s.source.isBlank()) { _state.update { it.copy(error = "지문 텍스트를 입력하세요.") }; return }
        runStep("① 소스에서 문장 추출 중…") {
            val sentences = extractSentences(s.source)
            if (sentences.isEmpty()) error("문장 추출 실패(응답에서 배열을 못 찾음).")
            _state.update {
                it.copy(
                    sentences = sentences,
                    status = "문장 ${sentences.size}개 추출됨. 다음 ‘지문저장’.",
                )
            }
        }
    }

    fun extractSentencesFromPhoto() {
        val s = _state.value
        if (s.running) return
        if (s.passagePhotoCount <= 0) { _state.update { it.copy(error = "먼저 지문사진을 고르세요.") }; return }
        runStep("① 사진 첨부 중…") {
            val sentences = extractSentencesFromPhotoInternal()
            if (sentences.isEmpty()) error("사진에서 문장을 못 뽑음(사진이 첨부됐는지 확인).")
            _state.update {
                it.copy(
                    sentences = sentences,
                    status = "사진에서 문장 ${sentences.size}개 추출됨. 다음 ‘지문저장’.",
                )
            }
        }
    }

    /** ①a 웹뷰에 **이미 첨부된** 사진으로 단어 추출(비전). 첨부는 사용자가 📎로 직접. */
    fun extractFromPhoto() {
        val s = _state.value
        if (s.running) return
        if (s.wordPhotoCount <= 0) { _state.update { it.copy(error = "먼저 단어사진을 고르세요.") }; return }
        runStep("① 사진 첨부 중…") {
            val words = extractWordsFromPhotoInternal()
            if (words.isEmpty()) error("사진에서 단어를 못 뽑음(사진이 첨부됐는지 확인).")
            _state.update { it.copy(words = words, status = "사진에서 단어 ${words.size}개 추출됨. 다음 ‘② JSON 생성’.") }
        }
    }

    /** 입력된 묶음만 한 번에: 지문사진이면 지문, 단어사진이면 만화, 둘 다 있으면 한 단원에 둘 다. */
    fun runAllAuto() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        val hasPassageInput = s.passagePhotoCount > 0 || (s.wordPhotoCount <= 0 && s.source.isNotBlank())
        val hasWordInput = s.wordPhotoCount > 0
        if (!hasPassageInput && !hasWordInput) {
            _state.update { it.copy(error = "지문사진이나 단어사진을 고르세요.") }
            return
        }
        runStep("전체 자동 시작…") {
            var sentences: List<String> = emptyList()
            var passageParams: JsonObject? = null
            if (hasPassageInput) {
                sentences = if (s.passagePhotoCount > 0) {
                    extractSentencesFromPhotoInternal()
                } else {
                    extractSentences(s.source)
                }
                if (sentences.isEmpty()) error("문장 추출 실패.")
                _state.update { it.copy(sentences = sentences, status = "자동 ① 지문 문장 ${sentences.size}개 → 지문 JSON 생성 중…") }
                if (PASSAGE_JSON_USE_DOWNLOAD_FLOW) {
                    val downloadResult = downloadPassageParamsFilesForReview(sentences, s)
                    passageParams = parsePassageParams(downloadResult.finalJsonText)
                    _state.update {
                        it.copy(
                            sentences = sentences,
                            status = "지문 JSON 다운로드 확인 완료(${downloadResult.downloads.size}개) → 단어/그림 생성 계속 진행 중…",
                        )
                    }
                } else {
                    passageParams = generatePassageParams(sentences, s)
                }
            }

            var words: List<String> = emptyList()
            var storyParams: JsonObject? = null
            if (hasWordInput) {
                words = extractWordsFromPhotoInternal()
                if (words.isEmpty()) error("단어 추출 실패.")
                _state.update { it.copy(words = words, status = "자동 ② 단어 ${words.size}개 → 만화 JSON 생성 중…") }
                storyParams = generateStoryParams(words, s)
            }

            val unit = saveAuthoredUnit(storyParams, passageParams, words, sentences, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = if (storyParams != null) "자동 JSON 저장됨 → 그리드 그림 생성 요청 중…" else "전체 자동 완료: 지문 단원 저장됨.",
                )
            }

            if (storyParams != null) {
                val imageBytes = requestAndSaveGridImage(words, s, "auto-grid-image")
                _state.update {
                    it.copy(status = "전체 자동 완료: ${unit.steps.size}개 스텝 + grid.png 저장됨(${imageBytes.size}B). 책에서 바로 확인하세요.")
                }
            }
        }
    }

    fun cancelCurrentWork() {
        val job = activeStepJob
        if (job?.isActive == true) {
            _state.update { it.copy(status = "중지 요청됨…", error = null) }
            job.cancel(CancellationException("manual authoring stop"))
        }
    }

    fun runResponseMenuProbe() {
        val current = _state.value
        if (current.running) return
        runStep("높음 응답 완료 감지 테스트…") {
            prepareFreshChat("response-menu-probe")
            val highSelected = gateway.selectHighMode()
            _state.update {
                it.copy(status = "대기테스트: 높음=${if (highSelected) "선택됨" else "미확인"} · 더미 요청 전송 중…")
            }
            val response = gateway.query(
                "Give a compact but thoughtful JSON design plan for a child-friendly English passage reader. " +
                    "Return 6 numbered bullets, each with one concrete UI detail and one learning reason. " +
                    "Do not make an image."
            ) ?: error(gateway.lastQueryFailureMessage("대기테스트"))
            _state.update {
                it.copy(status = "대기테스트 완료: 답변 ${response.length}자 · 하단 복사/평가 메뉴 감지 후 반환됨.")
            }
        }
    }

    /** 사진/텍스트에서 문장을 뽑아 passage_read 단원만 저장. */
    fun runPassageAuto() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        if (s.passagePhotoCount <= 0 && s.source.isBlank()) {
            _state.update { it.copy(error = "사진을 고르거나 지문 텍스트를 입력하세요.") }
            return
        }
        runStep("지문 자동 시작…") {
            val sentences = if (s.passagePhotoCount > 0) {
                extractSentencesFromPhotoInternal()
            } else {
                extractSentences(s.source)
            }
            if (sentences.isEmpty()) error("문장 추출 실패.")
            _state.update { it.copy(sentences = sentences, status = "자동 ① 문장 ${sentences.size}개 → 지문 JSON 생성 중…") }

            val params = if (PASSAGE_JSON_USE_DOWNLOAD_FLOW) {
                val downloadResult = downloadPassageParamsFilesForReview(sentences, s)
                _state.update {
                    it.copy(sentences = sentences, status = "지문 JSON 다운로드 확인 완료(${downloadResult.downloads.size}개) → passage_read 저장 중…")
                }
                parsePassageParams(downloadResult.finalJsonText)
            } else {
                generatePassageParams(sentences, s)
            }
            val unit = savePassageUnit(params, sentences, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "지문 자동 완료: passage_read 단원 저장됨. 책에서 바로 확인하세요.",
                )
            }
        }
    }

    /** ② 현재 단어들로 단원 JSON 생성 → 청크로 수신 → filesDir 저장 → 즉시 반영. */
    fun buildAndSave() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        if (s.words.isEmpty()) { _state.update { it.copy(error = "먼저 단어를 추출하세요(소스/사진).") }; return }
        runStep("② 단원 JSON 생성 요청 → 청크 수신 준비… (${s.words.size}개 단어)") {
            val params = generateStoryParams(s.words, s)
            val unit = saveStoryUnit(params, s.words, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "저장됨 → 그리드 그림 자동 생성/저장 중…",
                )
            }
            val imageBytes = requestAndSaveGridImage(s.words, s, "json-grid-image")
            _state.update {
                it.copy(status = "단어 콘텐츠 완료: story_comic + grid.png 저장됨(${imageBytes.size}B). 책에서 바로 확인하세요.")
            }
        }
    }

    /** 현재 문장들로 passage_read 단원 JSON 생성 → 저장. */
    fun buildPassageAndSave() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        if (s.sentences.isEmpty()) { _state.update { it.copy(error = "먼저 문장을 추출하세요(사진/텍스트).") }; return }
        runStep("② 지문 JSON 생성 요청 → 청크 수신 준비… (${s.sentences.size}개 문장)") {
            val params = if (PASSAGE_JSON_USE_DOWNLOAD_FLOW) {
                val downloadResult = downloadPassageParamsFilesForReview(s.sentences, s)
                _state.update {
                    it.copy(status = "지문 JSON 다운로드 확인 완료(${downloadResult.downloads.size}개) → passage_read 저장 중…")
                }
                parsePassageParams(downloadResult.finalJsonText)
            } else {
                generatePassageParams(s.sentences, s)
            }
            val unit = savePassageUnit(params, s.sentences, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "지문 단원 저장됨 → 책에서 바로 확인하세요.",
                )
            }
        }
    }

    fun setWordCardPrompt(v: String) = _state.update { it.copy(wordCardPrompt = v) }

    fun toggleWordCardCandidate(index: Int) {
        if (_state.value.running) return
        _state.update { s ->
            val before = s.wordCardCandidates.getOrNull(index)
            val afterSelected = before?.selected?.not() ?: false
            Log.d(
                "AuthoringViewModel",
                "word card candidate toggle index=$index word=${before?.word.orEmpty()} selected=$afterSelected",
            )
            s.copy(
                wordCardCandidates = s.wordCardCandidates.mapIndexed { i, candidate ->
                    if (i == index) candidate.copy(selected = !candidate.selected) else candidate
                },
            )
        }
    }

    fun setAllWordCardCandidates(selected: Boolean) {
        if (_state.value.running) return
        _state.update { s ->
            Log.d("AuthoringViewModel", "word card candidate setAll selected=$selected total=${s.wordCardCandidates.size}")
            s.copy(wordCardCandidates = s.wordCardCandidates.map { it.copy(selected = selected) })
        }
    }

    fun clearWordCardCandidates() {
        if (_state.value.running) return
        _state.update {
            it.copy(
                wordCardBracketedText = "",
                wordCardCandidates = emptyList(),
                error = null,
            )
        }
    }

    /** 지문을 영어로 정리한 뒤, GPT가 고른 후보 단어를 팝업 선택 상태로 올린다. */
    fun prepareWordCardCandidates() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        if (s.passagePhotoCount <= 0 && s.source.isBlank()) {
            _state.update { it.copy(error = "단어카드용 지문 사진을 고르거나 텍스트를 넣어주세요.") }
            return
        }
        runStep("단어카드 후보 준비 중…") {
            val passage = wordCardEnglishPassage(s)
            if (passage.isBlank()) error("단어카드용 영어 지문을 만들지 못했습니다.")
            _state.update { it.copy(wordCardPassage = passage, status = "영어 지문 정리 완료 → 단어 후보 표시 요청 중…") }
            val candidateResponse = bracketWordCardCandidates(passage)
            val candidates = parseWordCardCandidates(candidateResponse)
            if (candidates.isEmpty()) error("단어카드 후보를 찾지 못했습니다. GPT 응답이 JSON 배열인지 확인하세요.")
            Log.d(
                "AuthoringViewModel",
                "word card candidates ready total=${candidates.size} selected=${candidates.count { it.selected }}",
            )
            _state.update {
                it.copy(
                    wordCardPassage = passage,
                    wordCardBracketedText = candidateResponse,
                    wordCardCandidates = candidates,
                    wordCardCandidateVersion = it.wordCardCandidateVersion + 1,
                    status = "단어카드 후보 ${candidates.size}개 준비됨. 팝업에서 고른 뒤 보내세요.",
                    error = null,
                )
            }
        }
    }

    /** 사용자가 고른 단어로 word_card 단원을 생성한다. */
    fun buildWordCardFromSelected() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        val selectedCandidates = s.wordCardCandidates.filter { it.selected }
        val selectedWords = selectedCandidates.map { it.word }
        Log.d(
            "AuthoringViewModel",
            "word card build selected=${selectedCandidates.size}/${s.wordCardCandidates.size} words=${selectedWords.joinToString("|")}",
        )
        if (selectedCandidates.isEmpty()) {
            _state.update { it.copy(error = "단어카드로 만들 단어를 하나 이상 골라주세요.") }
            return
        }
        val passage = s.wordCardPassage.ifBlank { s.source }
        if (passage.isBlank()) {
            _state.update { it.copy(error = "단어카드 설명에 사용할 지문이 비었습니다.") }
            return
        }
        runStep("단어카드 설명 JSON 생성 중… (${selectedWords.size}개)") {
            val params = generateWordCardParams(passage, selectedCandidates, s.wordCardPrompt, s)
            val unit = saveWordCardUnit(params, selectedWords, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    words = selectedWords,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "단어카드 단원 저장 완료: ${selectedWords.size}개 카드. 책에서 바로 확인하세요.",
                )
            }
        }
    }

    /** 단어+영어 뜻 사진을 바로 word_card 단원으로 만든다. 선택 팝업 없이 전부 사용. */
    fun buildWordCardFromDefinitionPhotos() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        if (s.wordPhotoCount <= 0) {
            _state.update { it.copy(error = "단카2는 단어와 영어 뜻이 함께 있는 단어사진을 먼저 골라야 해요.") }
            return
        }
        runStep("단카2: 단어+뜻 사진 분석 중…") {
            val candidates = extractWordDefinitionsFromPhotoInternal()
            val words = candidates.map { it.word }
            if (candidates.isEmpty()) error("사진에서 단어와 영어 뜻을 찾지 못했습니다.")
            Log.d(
                "AuthoringViewModel",
                "word card2 build definitions=${candidates.size} words=${words.joinToString("|")}",
            )
            _state.update {
                it.copy(
                    words = words,
                    wordCardCandidates = emptyList(),
                    wordCardBracketedText = "",
                    status = "단카2: 단어+뜻 ${candidates.size}개 추출됨 → 설명/예문 생성 중…",
                )
            }
            val params = generateWordCardParamsFromDefinitions(candidates, s.wordCardPrompt, s)
            val unit = saveWordCardUnit(params, words, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    words = words,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "단카2 완료: ${words.size}개 카드 저장됨. 책에서 바로 확인하세요.",
                )
            }
        }
    }

    /**
     * ②(실험) **청크 수신 방식** — GPT 응답에서 JSON만 찾아 JS 브릿지로 작은 조각씩 받는다.
     * 큰 응답 문자열을 evaluateJavascript 결과로 직접 받지 않아 WebView 다운 위험을 줄인다.
     */
    fun buildAndSaveViaLink() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        if (s.words.isEmpty()) { _state.update { it.copy(error = "먼저 단어를 추출하세요.") }; return }
        runStep("②(청크) JSON 생성 요청…") {
            val params = generateStoryParams(s.words, s)
            val unit = saveStoryUnit(params, s.words, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "청크 방식으로 저장됨 → 그리드 그림 자동 생성/저장 중…",
                )
            }
            val imageBytes = requestAndSaveGridImage(s.words, s, "chunk-grid-image")
            _state.update {
                it.copy(status = "청크 단어 콘텐츠 완료: story_comic + grid.png 저장됨(${imageBytes.size}B). 책에서 바로 확인하세요.")
            }
        }
    }

    private fun decodeDataUrlText(dataUrl: String): String? = runCatching {
        val comma = dataUrl.indexOf(',')
        val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        String(Base64.decode(b64, Base64.NO_WRAP))
    }.getOrNull()

    /** ③ 단어 전체를 담은 **한 장의 격자 그리드**를 요청(수동으로 grid.png 저장). */
    fun requestGridImage() {
        val current = _state.value
        if (current.running || current.words.isEmpty()) return
        val s = ensureUnitId(current)
        val side = gridSide(s.words.size)
        runStep("③ 그리드 그림 생성/저장 중… (${side}×$side, grid.png)") {
            val imageBytes = requestAndSaveGridImage(s.words, s, "manual-grid-image")
            _state.update {
                it.copy(
                    status = "grid.png 자동 저장됨(${imageBytes.size}B) → 칸별로 바로 표시됩니다.",
                )
            }
        }
    }

    /** 생성된 그리드 그림을 **앱이 직접 가져와** grid.png 로 저장(수동 다운로드 대체). */
    fun captureGridImage() {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        runStep("그림 가져오는 중…") {
            val bytes = decodeImageDataUrl(
                gateway.captureLastImageDataUrl()
                    ?: error("그림을 못 가져옴(생성된 이미지가 없거나 읽기 실패). 그림이 화면에 보이는지 확인."),
            )
            saveImageDownload(s, GRID_FILE, bytes)
            writer.writeImage(s.bookId, s.unitId, GRID_FILE, bytes)
            lessonRepo.refresh()
            _state.update { it.copy(status = "grid.png 저장됨(${bytes.size}B) → 칸별로 바로 표시됩니다.") }
        }
    }

    /** 완료된 story_comic 단원 전체를 그림 재생성 후보로 불러온다. */
    fun refreshRepairChoices() {
        if (_state.value.running) return
        runCatching {
            lessonRepo.refresh()
            lessonRepo.books().flatMap { book ->
                book.units.mapNotNull { unit -> repairChoiceFor(book.bookId, book.title, unit) }
            }.sortedWith(
                compareByDescending<RepairUnitChoice> { it.missingImageCount }
                    .thenBy { it.unitTitle },
            )
        }.onSuccess { choices ->
            _state.update {
                it.copy(
                    repairChoices = choices,
                    repairChoiceIndex = 0,
                    error = null,
                    status = if (choices.isEmpty()) {
                        "그림을 다시 만들 story_comic 단원을 찾지 못했습니다."
                    } else {
                        "그림 다시 만들 단원 ${choices.size}개 확인: 목록에서 고르세요."
                    },
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(error = "그림 다시 후보 확인 실패: ${e.message}") }
        }
    }

    fun selectRepairChoice(index: Int) {
        if (_state.value.running) return
        _state.update { s ->
            val choice = s.repairChoices.getOrNull(index)
            if (choice == null) {
                s.copy(error = "그림 다시 만들 단원을 다시 고르세요.")
            } else {
                s.copy(
                    repairChoiceIndex = index,
                    error = null,
                    status = "그림 다시 대상: ${choice.label}",
                )
            }
        }
    }

    /** 선택된 단원의 모든 단어 그림을 단어별 첫 번째 사진으로 다시 생성해 wordPopups에 매핑한다. */
    fun repairSelectedUnitImages() {
        val current = _state.value
        if (current.running) return
        val selected = current.repairChoices.getOrNull(current.repairChoiceIndex)
        if (selected == null) {
            _state.update { it.copy(error = "먼저 그림다시 버튼으로 단원을 고르세요.") }
            return
        }
        runStep("그림 다시 시작: ${selected.label}") {
            lessonRepo.refresh()
            var unit = lessonRepo.unitOrNull(selected.bookId, selected.unitId)
                ?: error("단원을 찾지 못했습니다: ${selected.bookId}/${selected.unitId}")
            val repairWords = allRepairWordsForUnit(unit)
            if (repairWords.isEmpty()) {
                _state.update { it.copy(status = "그림을 다시 만들 단어가 없습니다: ${selected.unitTitle}") }
                return@runStep
            }

            prepareFreshChat("repair-word-images")
            repairWords.forEachIndexed { wordIndex, word ->
                val wordNo = wordIndex + 1
                val fileName = repairWordFileName(wordNo, word)
                _state.update {
                    it.copy(status = "그림 다시 $wordNo/${repairWords.size}: ${word.text}")
                }
                val before = gateway.assistantMarker()
                if (!gateway.sendOnly(repairWordPhotoPrompt(word))) {
                    error(gateway.lastQueryFailureMessage("그림 다시 요청"))
                }
                val bytes = awaitFirstWordImageBytes(before)
                writer.writeImage(selected.bookId, selected.unitId, fileName, bytes)
                saveImageDownload(
                    _state.value.copy(bookId = selected.bookId, unitId = selected.unitId),
                    fileName,
                    bytes,
                )

                unit = patchStoryUnitWithRepairWordImage(
                    unit = unit,
                    bookId = selected.bookId,
                    word = word,
                    fileName = fileName,
                )
                writer.writeUnit(selected.bookId, selected.bookTitle, unit)
                lessonRepo.refresh()
            }

            val refreshedChoices = lessonRepo.books().flatMap { book ->
                book.units.mapNotNull { u -> repairChoiceFor(book.bookId, book.title, u) }
            }.sortedWith(
                compareByDescending<RepairUnitChoice> { it.missingImageCount }
                    .thenBy { it.unitTitle },
            )
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    repairChoices = refreshedChoices,
                    repairChoiceIndex = refreshedChoices.indexOfFirst {
                        it.bookId == selected.bookId && it.unitId == selected.unitId
                    }.coerceAtLeast(0),
                    status = "그림 다시 완료: ${repairWords.size}개 단어 사진을 저장했습니다.",
                )
            }
        }
    }

    /** ChatGPT에서 **수동 다운로드한 .json 파일을 가져와** 단원으로 저장(가장 견고한 경로). */
    fun importJson(uri: Uri) {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        runStep("JSON 파일 가져오는 중…") {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            }
            val jsonText = extractJson(text) ?: text
            if (looksLikeWordCardParams(jsonText)) {
                val params = parseWordCardParams(jsonText, s.source, parseWordCardWords(jsonText), s)
                val unit = LessonUnit(
                    unitId = s.unitId,
                    title = s.unitTitle.ifBlank { params["title"]?.jsonPrimitive?.contentOrNull ?: s.unitId },
                    content = LessonContent(),
                    steps = listOf(StepDef(id = "s_word_card", type = "word_card", params = params)),
                )
                writer.writeUnit(bookId = s.bookId, bookTitle = s.bookTitle, unit = unit)
                lessonRepo.refresh()
                _state.update {
                    it.copy(
                        savedUnit = true,
                        words = parseWordCardWords(jsonText),
                        unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                        status = "가져온 단어카드 JSON 저장됨 → 책에서 바로 보입니다.",
                    )
                }
                return@runStep
            }
            if (looksLikePassageParams(jsonText)) {
                val params = parsePassageParams(jsonText)
                val unit = LessonUnit(
                    unitId = s.unitId,
                    title = s.unitTitle.ifBlank { params["title"]?.jsonPrimitive?.contentOrNull ?: s.unitId },
                    content = LessonContent(),
                    steps = listOf(StepDef(id = "s_passage", type = "passage_read", params = params)),
                )
                writer.writeUnit(bookId = s.bookId, bookTitle = s.bookTitle, unit = unit)
                lessonRepo.refresh()
                _state.update {
                    it.copy(
                        savedUnit = true,
                        unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                        status = "가져온 지문 JSON 저장됨 → 앱에 바로 보입니다.",
                    )
                }
                return@runStep
            }
            val words = parseWordsFromParams(jsonText).ifEmpty { s.words }
            val params = enrichStoryParams(jsonText, words, s)
            val unit = LessonUnit(
                unitId = s.unitId,
                title = s.unitTitle.ifBlank { s.unitId },
                content = LessonContent(),
                steps = listOf(StepDef(id = "s_story", type = "story_comic", params = params)),
            )
            writer.writeUnit(bookId = s.bookId, bookTitle = s.bookTitle, unit = unit)
            lessonRepo.refresh()
            _state.update {
                it.copy(
                    savedUnit = true,
                    words = words,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "가져온 JSON 저장됨 → 앱에 바로 보입니다. 이제 그림(grid.png)도 가져오세요.",
                )
            }
        }
    }

    /** 수동 다운로드한 그리드 이미지를 grid.png 로 저장. */
    fun importGridImage(uri: Uri) {
        val current = _state.value
        if (current.running) return
        val s = ensureUnitId(current)
        runStep("그림 파일 가져오는 중…") {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }
            if (bytes.isEmpty()) error("이미지가 비었습니다.")
            writer.writeImage(s.bookId, s.unitId, GRID_FILE, bytes)
            lessonRepo.refresh()
            _state.update { it.copy(status = "grid.png 가져옴(${bytes.size}B) → 칸별로 바로 표시됩니다.") }
        }
    }

    private fun parseWordsFromParams(jsonText: String): List<String> = runCatching {
        json.parseToJsonElement(jsonText).jsonObject["words"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun parseWordCardWords(jsonText: String): List<String> = runCatching {
        json.parseToJsonElement(jsonText).jsonObject["cards"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("word")?.jsonPrimitive?.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun runStep(startStatus: String, block: suspend () -> Unit) {
        if (activeStepJob?.isActive == true) return
        val job = viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, status = startStatus) }
            try {
                block()
            } catch (e: CancellationException) {
                _state.update { it.copy(error = null, status = "작업이 중지됐습니다.") }
                throw e
            } catch (e: Exception) {
                Log.w("AuthoringViewModel", "runStep failed status=$startStatus message=${e.message}", e)
                _state.update { it.copy(error = e.message ?: "오류") }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
        activeStepJob = job
        job.invokeOnCompletion {
            if (activeStepJob === job) activeStepJob = null
        }
    }

    // ----------------------------------------------------------- GPT 단계

    private suspend fun extractWords(source: String): List<String> {
        prepareFreshChat("source-word-extract")
        gateway.selectInstantMode()
        val prompt = buildString {
            append("아래 글에서 초등학생이 배울 핵심 영어 단어를 8~14개 골라 ")
            append("JSON 배열로만 답해줘. 설명·코드펜스 없이 [\"word\", ...] 형식만.\n\n글:\n")
            append(source)
        }
        val resp = gateway.query(prompt) ?: return emptyList()
        return parseWordArray(resp)
    }

    private suspend fun extractSentences(source: String): List<String> {
        prepareFreshChat("source-sentence-extract")
        gateway.selectInstantMode()
        val prompt = buildString {
            append("아래 지문에서 아이가 읽을 영어 문장만 순서대로 추출해 ")
            append("JSON 배열로만 답해줘. 설명·코드펜스 없이 [\"sentence\", ...] 형식만.\n")
            append("원문 영어 문장을 가능한 한 고치지 말고, 문제 지시문/한국어 설명/번호/빈칸은 제외해줘.\n\n")
            append(source)
        }
        val resp = gateway.query(prompt) ?: return emptyList()
        return parseSentenceArray(resp)
    }

    private suspend fun extractWordsFromPhotoInternal(): List<String> {
        val batches = wordPhotoUris.chunked(PHOTO_BATCH_SIZE)
        val words = linkedSetOf<String>()
        batches.forEachIndexed { index, batch ->
            prepareFreshChat("word-photo-batch-${index + 1}")
            gateway.armPhotos(batch)
            val attach = gateway.attachImages()
            ensurePhotoAttachSucceeded(attach)
            val start = index * PHOTO_BATCH_SIZE + 1
            val end = start + batch.size - 1
            _state.update {
                it.copy(status = "① 단어사진 ${index + 1}/${batches.size} 첨부($attach) · ${start}~${end}쪽 분석…")
            }
            delay(uploadDelayMs(batch.size))
            gateway.selectInstantMode()
            val resp = gateway.query(photoWordBatchPrompt(start, end, batch.size))
                ?: error(gateway.lastQueryFailureMessage("${start}~${end}쪽 단어 분석"))
            words += parseWordArray(resp)
        }
        return words.toList()
    }

    private suspend fun extractWordDefinitionsFromPhotoInternal(): List<WordCardCandidate> {
        val batches = wordPhotoUris.chunked(PHOTO_BATCH_SIZE)
        val items = mutableListOf<WordCardCandidate>()
        batches.forEachIndexed { index, batch ->
            prepareFreshChat("word-def-photo-batch-${index + 1}")
            gateway.armPhotos(batch)
            val attach = gateway.attachImages()
            ensurePhotoAttachSucceeded(attach)
            val start = index * PHOTO_BATCH_SIZE + 1
            val end = start + batch.size - 1
            _state.update {
                it.copy(status = "단카2: 단어뜻사진 ${index + 1}/${batches.size} 첨부($attach) · ${start}~${end}쪽 추출…")
            }
            delay(uploadDelayMs(batch.size))
            gateway.selectInstantMode()
            val resp = gateway.query(photoWordDefinitionBatchPrompt(start, end, batch.size))
                ?: error(gateway.lastQueryFailureMessage("${start}~${end}쪽 단어 뜻 추출"))
            val parsed = parseWordDefinitionArray(resp)
            Log.d(
                "AuthoringViewModel",
                "word card2 definitions parsed batch=${index + 1}/${batches.size} respLen=${resp.length} items=${parsed.size}",
            )
            items += parsed
        }
        return items
            .filter { it.word.isNotBlank() && it.src.isNotBlank() }
            .distinctBy { "${it.word.lowercase()}|${it.src.lowercase()}" }
    }

    private suspend fun extractSentencesFromPhotoInternal(): List<String> {
        val batches = passagePhotoUris.chunked(PHOTO_BATCH_SIZE)
        val pages = mutableListOf<PageSentences>()
        batches.forEachIndexed { index, batch ->
            prepareFreshChat("passage-photo-batch-${index + 1}")
            gateway.armPhotos(batch)
            val attach = gateway.attachImages()
            ensurePhotoAttachSucceeded(attach)
            val start = index * PHOTO_BATCH_SIZE + 1
            val end = start + batch.size - 1
            _state.update {
                it.copy(status = "① 지문사진 ${index + 1}/${batches.size} 첨부($attach) · ${start}~${end}쪽 문장 추출…")
            }
            delay(uploadDelayMs(batch.size))
            gateway.selectInstantMode()
            val resp = gateway.query(photoSentenceBatchPrompt(start, end, batch.size))
                ?: error(gateway.lastQueryFailureMessage("${start}~${end}쪽 문장 추출"))
            val parsedPages = parsePageSentenceGroups(resp, start, batch.size)
            Log.d(
                "AuthoringViewModel",
                "photo sentence parsed batch=${index + 1}/${batches.size} respLen=${resp.length} " +
                    "pages=${parsedPages.size} sentences=${parsedPages.sumOf { it.sentences.size }}",
            )
            pages += parsedPages
        }
        if (pages.isEmpty()) return emptyList()
        _state.update { it.copy(status = "①b 페이지 순서 정리 요청 중… (${pages.size}쪽)") }
        return reorderSentencesWithGpt(pages)
            .ifEmpty { pages.sortedBy { it.pageNumber }.flatMap { it.sentences }.distinct() }
    }

    private fun parseWordArray(resp: String): List<String> {
        val arr = extractJson(resp) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(arr).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }.distinct()
    }

    private fun parseWordDefinitionArray(resp: String): List<WordCardCandidate> {
        val jsonText = extractJson(resp)
        if (jsonText != null) {
            val parsed = runCatching {
                val root = json.parseToJsonElement(jsonText)
                val array = when (root) {
                    is JsonArray -> root
                    is JsonObject -> {
                        (root["items"] as? JsonArray)
                            ?: (root["words"] as? JsonArray)
                            ?: (root["vocabulary"] as? JsonArray)
                            ?: (root["entries"] as? JsonArray)
                            ?: JsonArray(emptyList())
                    }
                    else -> JsonArray(emptyList())
                }
                array.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val word = obj.stringValue("word")
                        ?: obj.stringValue("term")
                        ?: obj.stringValue("vocab")
                        ?: obj.stringValue("expression")
                    val definition = obj.stringValue("definition")
                        ?: obj.stringValue("meaning")
                        ?: obj.stringValue("def")
                        ?: obj.stringValue("englishDefinition")
                        ?: obj.stringValue("src")
                    val cleanWord = word?.trim().orEmpty()
                    val cleanDefinition = definition?.normalizeSentenceText().orEmpty()
                    if (cleanWord.isBlank() || cleanDefinition.isBlank()) null else {
                        WordCardCandidate(word = cleanWord, src = cleanDefinition, selected = true)
                    }
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return parseLooseWordDefinitionLines(resp)
    }

    private fun parseLooseWordDefinitionLines(resp: String): List<WordCardCandidate> =
        resp.replace("```", "")
            .lineSequence()
            .mapNotNull { raw ->
                val line = raw.trim()
                    .replace(Regex("^[-*•]+\\s*"), "")
                    .replace(Regex("^\\d+[.)]\\s*"), "")
                    .trim()
                val match = Regex("^([A-Za-z][A-Za-z0-9 '\\-/]{0,60})\\s*[:\\-–—]\\s*(.{3,})$").find(line)
                    ?: return@mapNotNull null
                val word = match.groupValues[1].trim()
                val definition = match.groupValues[2].normalizeSentenceText()
                if (word.isBlank() || definition.isBlank() || !definition.any(Char::isLetter)) null else {
                    WordCardCandidate(word = word, src = definition, selected = true)
                }
            }
            .distinctBy { "${it.word.lowercase()}|${it.src.lowercase()}" }
            .toList()

    private fun parsePassageText(resp: String): String {
        val jsonText = extractJson(resp)
        if (jsonText != null) {
            runCatching {
                val element = json.parseToJsonElement(jsonText)
                when (element) {
                    is JsonObject -> element["passage"]?.jsonPrimitive?.contentOrNull
                        ?: element["text"]?.jsonPrimitive?.contentOrNull
                    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
                    else -> null
                }
            }.getOrNull()?.let { return it.trim() }
        }
        return resp.replace("```", "").trim()
    }

    private fun parseBracketedPassage(resp: String): String {
        val jsonText = extractJson(resp)
        if (jsonText != null) {
            runCatching {
                val obj = json.parseToJsonElement(jsonText).jsonObject
                obj["bracketedPassage"]?.jsonPrimitive?.contentOrNull
                    ?: obj["text"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.let { return it.trim() }
        }
        return resp.replace("```", "").trim()
    }

    private fun parseBracketedWords(text: String): List<String> =
        Regex("\\[([^\\[\\]]{1,80})]").findAll(text)
            .map { it.groupValues[1].trim().trim('.', ',', ';', ':', '!', '?', '"', '\'') }
            .filter { it.any(Char::isLetter) }
            .distinctBy { it.lowercase() }
            .toList()

    private fun parseWordCardCandidates(resp: String): List<WordCardCandidate> {
        val jsonText = extractJson(resp)
        if (jsonText != null) {
            runCatching {
                when (val element = json.parseToJsonElement(jsonText)) {
                    is JsonArray -> element.mapNotNull { item ->
                        when (item) {
                            is JsonObject -> {
                                val word = item["word"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                val src = item["src"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                if (word.isBlank()) null else WordCardCandidate(word = word, src = src)
                            }
                            is JsonPrimitive -> item.contentOrNull
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { WordCardCandidate(word = it) }
                            else -> null
                        }
                    }
                    is JsonObject -> {
                        val bracketed = element["bracketedPassage"]?.jsonPrimitive?.contentOrNull
                            ?: element["text"]?.jsonPrimitive?.contentOrNull
                        parseBracketedWords(bracketed.orEmpty()).map { WordCardCandidate(word = it) }
                    }
                    else -> emptyList()
                }
            }.getOrNull()?.let { candidates ->
                return candidates
                    .filter { it.word.isNotBlank() }
                    .distinctBy { "${it.word.lowercase()}|${it.src}" }
            }
        }
        return parseBracketedWords(resp).map { WordCardCandidate(word = it) }
    }

    private fun parseSentenceArray(resp: String): List<String> {
        val arr = extractJson(resp) ?: return emptyList()
        return runCatching {
            when (val root = json.parseToJsonElement(arr)) {
                is JsonArray -> root.stringListFromJson()
                is JsonObject -> root["sentences"].stringListFromJson()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
            .distinct()
    }

    private fun JsonElement?.stringListFromJson(): List<String> =
        when (this) {
            is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.normalizeSentenceText() }
                .filter { it.isNotBlank() }
            is JsonPrimitive -> contentOrNull
                ?.split(Regex("(?<=[.!?])\\s+"))
                ?.map { it.normalizeSentenceText() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            else -> emptyList()
        }

    private fun pageSentencesFromJson(element: JsonElement, fallbackPageNumber: Int): PageSentences? {
        val obj = element as? JsonObject ?: return null
        val pageNumber = obj["pageNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: obj["page"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: fallbackPageNumber
        val sentences = obj["sentences"].stringListFromJson()
            .ifEmpty { obj["text"].stringListFromJson() }
        return PageSentences(pageNumber, sentences)
    }

    private fun parseLooseSentenceLines(resp: String): List<String> =
        resp.replace("```", "")
            .lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^[-*•]+\\s*"), "")
                    .replace(Regex("^\\d+[.)]\\s*"), "")
                    .trim('"', '\'', ' ', '\t')
                    .normalizeSentenceText()
            }
            .filter { line ->
                line.length >= 8 &&
                    line.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                    line.none { it in '가'..'힣' } &&
                    line.lastOrNull() in listOf('.', '?', '!')
            }
            .distinct()
            .toList()

    private fun String.normalizeSentenceText(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun parsePageSentenceGroups(resp: String, startPage: Int, expectedCount: Int): List<PageSentences> {
        val parsed = extractJson(resp)?.let { jsonText ->
            runCatching {
                when (val root = json.parseToJsonElement(jsonText)) {
                    is JsonObject -> {
                        val direct = root["sentences"].stringListFromJson()
                        if (direct.isNotEmpty()) {
                            listOf(PageSentences(startPage, direct))
                        } else {
                            val pages = root["pages"] as? JsonArray ?: JsonArray(emptyList())
                            pages.mapIndexedNotNull { index, element ->
                                pageSentencesFromJson(element, startPage + index)
                            }
                        }
                    }
                    is JsonArray -> {
                        val flat = root.stringListFromJson()
                        if (flat.isNotEmpty()) {
                            listOf(PageSentences(startPage, flat))
                        } else {
                            root.mapIndexedNotNull { index, element ->
                                pageSentencesFromJson(element, startPage + index)
                            }
                        }
                    }
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }.orEmpty()

        val filtered = parsed
            .filter { it.pageNumber in startPage until startPage + expectedCount || it.sentences.isNotEmpty() }
            .filter { it.sentences.isNotEmpty() }
        if (filtered.isNotEmpty()) return filtered

        val flat = parseSentenceArray(resp).ifEmpty { parseLooseSentenceLines(resp) }
        if (flat.isEmpty()) {
            Log.w(
                "AuthoringViewModel",
                "photo sentence parse empty preview=${resp.take(500).replace(Regex("\\s+"), " ")}",
            )
            return emptyList()
        }
        return listOf(PageSentences(startPage, flat))
    }

    private suspend fun reorderSentencesWithGpt(pages: List<PageSentences>): List<String> {
        prepareFreshChat("page-order")
        gateway.selectInstantMode()
        val resp = gateway.query(pageOrderPrompt(pages)).orEmpty()
        return parseSentenceArray(resp)
    }

    private suspend fun generateStoryParams(words: List<String>, s: State): JsonObject {
        prepareFreshChat("story-json")
        requireAuthoringMode("만화 JSON 생성")
        val before = gateway.assistantMarker()
        if (!gateway.sendOnly(storyPrompt(words))) {
            throw IllegalStateException("JSON 요청 전송 실패(로그인/작성창 확인).")
        }
        val jsonText = awaitStoryJsonChunk(before)
        saveJsonDownload(s, "story_params.json", jsonText)
        return enrichStoryParams(jsonText, words, s)
    }

    private suspend fun generatePassageParams(sentences: List<String>, s: State): JsonObject {
        prepareFreshChat("passage-json")
        requireAuthoringMode("지문 JSON 생성")
        val before = gateway.assistantMarker()
        if (!gateway.sendOnly(passagePrompt(sentences, s))) {
            throw IllegalStateException("지문 JSON 요청 전송 실패(로그인/작성창 확인).")
        }
        val jsonText = runCatching { awaitPassageJsonChunk(before) }.getOrElse { firstError ->
            val retryBefore = gateway.assistantMarker()
            if (!gateway.sendOnly(passageRetryPrompt(sentences, s))) throw firstError
            runCatching { awaitPassageJsonChunk(retryBefore) }.getOrElse {
                return fallbackPassageParams(sentences, s)
            }
        }
        saveJsonDownload(s, "passage_params.json", jsonText)
        return runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { throw IllegalStateException("지문 JSON 파싱 실패: ${it.message}") }
    }

    private suspend fun wordCardEnglishPassage(s: State): String {
        if (s.passagePhotoCount > 0) {
            val sentences = extractSentencesFromPhotoInternal()
            _state.update { it.copy(sentences = sentences) }
            return sentences.joinToString("\n")
        }
        prepareFreshChat("word-card-english-passage")
        gateway.selectInstantMode()
        val resp = gateway.query(wordCardEnglishPassagePrompt(s.source))
            ?: error(gateway.lastQueryFailureMessage("단어카드 영어 지문 정리"))
        return parsePassageText(resp).ifBlank { s.source.trim() }
    }

    private suspend fun bracketWordCardCandidates(passage: String): String {
        prepareFreshChat("word-card-brackets")
        gateway.selectInstantMode()
        val resp = gateway.query(wordCardBracketPrompt(passage))
            ?: error(gateway.lastQueryFailureMessage("단어카드 후보 표시"))
        return parseBracketedPassage(resp).ifBlank { resp.trim() }
    }

    private suspend fun generateWordCardParams(
        passage: String,
        candidates: List<WordCardCandidate>,
        customPrompt: String,
        s: State,
    ): JsonObject {
        prepareFreshChat("word-card-json")
        requireAuthoringMode("단어카드 JSON 생성")
        val before = gateway.assistantMarker()
        if (!gateway.sendOnly(wordCardExplanationPrompt(passage, candidates, customPrompt, s))) {
            throw IllegalStateException("단어카드 JSON 요청 전송 실패(로그와 작성창 확인).")
        }
        val jsonText = awaitWordCardJsonChunk(before)
        saveJsonDownload(s, "word_card_params.json", jsonText)
        val words = candidates.map { it.word }
        return parseWordCardParams(jsonText, passage, words, s)
    }

    private suspend fun generateWordCardParamsFromDefinitions(
        candidates: List<WordCardCandidate>,
        customPrompt: String,
        s: State,
    ): JsonObject {
        prepareFreshChat("word-card2-json")
        requireAuthoringMode("단카2 JSON 생성")
        val definitionText = candidates.joinToString("\n") { "${it.word}: ${it.src}" }
        val before = gateway.assistantMarker()
        if (!gateway.sendOnly(wordCardDefinitionExplanationPrompt(candidates, customPrompt, s))) {
            throw IllegalStateException("단카2 JSON 요청 전송 실패(로그와 작성창 확인).")
        }
        val jsonText = awaitWordCardJsonChunk(before)
        saveJsonDownload(s, "word_card2_params.json", jsonText)
        val words = candidates.map { it.word }
        return parseWordCardParams(jsonText, definitionText, words, s)
    }

    private suspend fun downloadPassageParamsFilesForReview(sentences: List<String>, s: State): PassageDownloadResult {
        val batches = splitPassageJsonBatches(sentences)
        val results = mutableListOf<String>()
        var finalJsonText: String? = null
        val finalBaseName = downloadName(s, "passage_params").removeSuffix(".json")
        batches.forEachIndexed { index, batch ->
            prepareFreshChat("passage-json-download-${index + 1}")
            val highSelected = gateway.selectHighMode()
            if (!highSelected) {
                error("지문 JSON 파일 요청 ${index + 1}/${batches.size} 전에 ChatGPT '높음' 모드를 선택하지 못했습니다. 모델 메뉴에서 '높음'이 보이는지 확인하세요.")
            }
            val isFinal = index == batches.lastIndex
            val partBaseName = if (isFinal) finalBaseName else "${finalBaseName}_part_${index + 1}"
            _state.update {
                it.copy(
                    status = "지문 JSON 파일 요청 ${index + 1}/${batches.size} · 높음 대기 중 · 생성 버튼을 다시 누르면 중지",
                )
            }
            val resp = gateway.query(
                passageDownloadPrompt(batch, s, index + 1, batches.size, partBaseName, isFinal),
                timeoutMs = null,
            )
                ?: error(gateway.lastQueryFailureMessage("지문 JSON 파일 요청 ${index + 1}/${batches.size}"))
            val download = gateway.requestAssistantDownload(partBaseName, wantsImage = false, timeoutMs = null)
            val downloadedJson = if (download.contains("confirmed=true")) {
                gateway.readDownloadedText(download)
                    ?.let { extractJson(it) ?: it.trim() }
                    ?.takeIf(::looksLikePassageParams)
            } else {
                null
            }
            if (!download.contains("confirmed=true")) {
                val jsonText = extractJson(resp)
                if (jsonText != null && looksLikePassageParams(jsonText)) {
                    gateway.saveTextToDownloads("$partBaseName.json", jsonText, "application/json")
                    if (isFinal) finalJsonText = jsonText
                } else {
                    error(
                        "지문 JSON 파일 요청 ${index + 1}/${batches.size} 다운로드 확인 실패. " +
                            "ChatGPT 응답이 파일 링크나 passage_read JSON이 아니었습니다.",
                    )
                }
            } else if (isFinal) {
                finalJsonText = downloadedJson
                    ?: extractJson(resp)?.takeIf(::looksLikePassageParams)
                    ?: error(
                        "최종 지문 JSON 다운로드는 확인됐지만 앱이 JSON 내용을 읽지 못했습니다. " +
                            "다운로드 파일이 완전히 저장됐는지 확인하세요.",
                    )
            }
            results += "$partBaseName.json :: $download"
            _state.update {
                it.copy(status = "지문 JSON 다운로드 확인 ${index + 1}/${batches.size}: $partBaseName.json")
            }
        }
        return PassageDownloadResult(
            downloads = results,
            finalJsonText = finalJsonText ?: error("최종 지문 JSON이 비어 있습니다."),
        )
    }

    private fun parsePassageParams(jsonText: String): JsonObject =
        runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { throw IllegalStateException("지문 JSON 파싱 실패: ${it.message}") }

    private suspend fun requireAuthoringMode(action: String) {
        if (!gateway.selectHighMode()) {
            error("$action 전에 ChatGPT '높음' 모드를 선택하지 못했습니다. 모델 메뉴에서 '높음'이 보이는지 확인하세요.")
        }
    }

    private fun splitPassageJsonBatches(sentences: List<String>): List<PassageJsonBatch> {
        if (sentences.isEmpty()) return emptyList()
        val out = mutableListOf<PassageJsonBatch>()
        var start = 1
        var chars = 0
        val bucket = mutableListOf<String>()
        fun flush() {
            if (bucket.isEmpty()) return
            out += PassageJsonBatch(start, bucket.toList())
            start += bucket.size
            bucket.clear()
            chars = 0
        }
        sentences.forEach { sentence ->
            val nextChars = chars + sentence.length
            if (bucket.isNotEmpty() && (bucket.size >= PASSAGE_JSON_CHUNK_SENTENCES || nextChars > PASSAGE_JSON_CHUNK_CHARS)) {
                flush()
            }
            bucket += sentence
            chars += sentence.length
        }
        flush()
        return out
    }

    private fun saveStoryUnit(params: JsonObject, words: List<String>, s: State): LessonUnit {
        val unit = LessonUnit(
            unitId = s.unitId,
            title = s.unitTitle.ifBlank { s.unitId },
            content = LessonContent(),     // story_comic 은 params 자급자족
            steps = listOf(StepDef(id = "s_story", type = "story_comic", params = params)),
        )
        writer.writeUnit(bookId = s.bookId, bookTitle = s.bookTitle, unit = unit)
        lessonRepo.refresh()
        _state.update { it.copy(words = words) }
        return unit
    }

    private fun savePassageUnit(params: JsonObject, sentences: List<String>, s: State): LessonUnit {
        val unit = LessonUnit(
            unitId = s.unitId,
            title = s.unitTitle.ifBlank { params["title"]?.jsonPrimitive?.contentOrNull ?: s.unitId },
            content = LessonContent(),     // passage_read 도 params 자급자족
            steps = listOf(StepDef(id = "s_passage", type = "passage_read", params = params)),
        )
        writer.writeUnit(bookId = s.bookId, bookTitle = s.bookTitle, unit = unit)
        lessonRepo.refresh()
        _state.update { it.copy(sentences = sentences) }
        return unit
    }

    private fun saveWordCardUnit(params: JsonObject, words: List<String>, s: State): LessonUnit {
        val unit = LessonUnit(
            unitId = s.unitId,
            title = s.unitTitle.ifBlank { params["title"]?.jsonPrimitive?.contentOrNull ?: s.unitId },
            content = LessonContent(),     // word_card 는 params 자급자족
            steps = listOf(StepDef(id = "s_word_card", type = "word_card", params = params)),
        )
        writer.writeUnit(bookId = s.bookId, bookTitle = s.bookTitle, unit = unit)
        lessonRepo.refresh()
        _state.update { it.copy(words = words) }
        return unit
    }

    private fun saveAuthoredUnit(
        storyParams: JsonObject?,
        passageParams: JsonObject?,
        words: List<String>,
        sentences: List<String>,
        s: State,
    ): LessonUnit {
        val steps = buildList {
            if (storyParams != null) add(StepDef(id = "s_story", type = "story_comic", params = storyParams))
            if (passageParams != null) add(StepDef(id = "s_passage", type = "passage_read", params = passageParams))
        }
        if (steps.isEmpty()) throw IllegalStateException("저장할 스텝이 없습니다.")
        val inferredTitle = passageParams?.get("title")?.jsonPrimitive?.contentOrNull
            ?: storyParams?.get("title")?.jsonPrimitive?.contentOrNull
            ?: s.unitId
        val unit = LessonUnit(
            unitId = s.unitId,
            title = s.unitTitle.ifBlank { inferredTitle },
            content = LessonContent(),
            steps = steps,
        )
        writer.writeUnit(bookId = s.bookId, bookTitle = s.bookTitle, unit = unit)
        lessonRepo.refresh()
        _state.update { it.copy(words = words, sentences = sentences) }
        return unit
    }

    private fun repairChoiceFor(bookId: String, bookTitle: String, unit: LessonUnit): RepairUnitChoice? {
        val step = unit.storyStepOrNull() ?: return null
        val words = storyWordsForUnit(unit, step.params)
        if (words.isEmpty()) return null
        val missing = repairWordsForUnit(unit).size
        return RepairUnitChoice(
            bookId = bookId,
            bookTitle = bookTitle,
            unitId = unit.unitId,
            unitTitle = unit.title.ifBlank { unit.unitId },
            wordCount = words.size,
            missingImageCount = missing,
        )
    }

    private fun allRepairWordsForUnit(unit: LessonUnit): List<RepairWord> {
        val step = unit.storyStepOrNull() ?: return emptyList()
        val popups = step.params["wordPopups"] as? JsonObject
        return storyWordsForUnit(unit, step.params)
            .mapNotNull { word ->
                val key = word.lowercase().trim()
                if (key.isBlank()) {
                    null
                } else {
                    val definition = popupFor(popups, key)
                        ?.stringValue("definitionEn")
                        .orEmpty()
                        .ifBlank { "${word.trim()} is a word from this lesson." }
                    RepairWord(key = key, text = word.trim(), definition = definition)
                }
            }
            .distinctBy { it.key }
    }

    private fun repairWordsForUnit(unit: LessonUnit): List<RepairWord> {
        val step = unit.storyStepOrNull() ?: return emptyList()
        val params = step.params
        val popups = params["wordPopups"] as? JsonObject
        return storyWordsForUnit(unit, params).mapNotNull { word ->
            val key = word.lowercase().trim()
            if (key.isBlank()) return@mapNotNull null
            val popup = popups?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value as? JsonObject
            val assetPath = popup?.stringValue("imageAsset").orEmpty()
            if (assetPath.isNotBlank() && imageAssetExists(assetPath)) return@mapNotNull null
            val definition = popup
                ?.stringValue("definitionEn")
                .orEmpty()
                .ifBlank { "${word.trim()} is a word from this lesson." }
            RepairWord(key = key, text = word.trim(), definition = definition)
        }.distinctBy { it.key }
    }

    private fun storyWordsForUnit(unit: LessonUnit, params: JsonObject): List<String> {
        val fromPopups = (params["wordPopups"] as? JsonObject)?.keys.orEmpty()
        val fromParams = (params["words"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val fromContent = unit.content.words.map { it.text }
        return (fromPopups + fromParams + fromContent)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    private fun LessonUnit.storyStepOrNull(): StepDef? =
        steps.firstOrNull { it.type == "story_comic" }

    private fun popupFor(popups: JsonObject?, key: String): JsonObject? =
        popups?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value as? JsonObject

    private fun imageAssetExists(assetPath: String): Boolean {
        if (assetPath.isBlank()) return false
        val runtime = File(context.filesDir, assetPath)
        if (runtime.isFile) return true
        return runCatching {
            context.assets.open(assetPath).use { true }
        }.getOrDefault(false)
    }

    private fun patchStoryUnitWithRepairWordImage(
        unit: LessonUnit,
        bookId: String,
        word: RepairWord,
        fileName: String,
    ): LessonUnit {
        val assetPath = writer.imageAssetPath(bookId, unit.unitId, fileName)
        val steps = unit.steps.map { step ->
            if (step.type != "story_comic") return@map step
            step.copy(params = patchStoryParamsWithRepairWordImage(step.params, word, assetPath))
        }
        return unit.copy(steps = steps)
    }

    private fun patchStoryParamsWithRepairWordImage(
        params: JsonObject,
        word: RepairWord,
        assetPath: String,
    ): JsonObject {
        val existing = params["wordPopups"] as? JsonObject ?: JsonObject(emptyMap())
        val popups = buildJsonObject {
            existing.forEach { (key, value) ->
                if (!key.equals(word.key, ignoreCase = true)) put(key, value)
            }
            val old = popupFor(existing, word.key)
            put(word.key, buildJsonObject {
                old?.forEach { (key, value) -> if (key !in gridKeys) put(key, value) }
                if (old?.stringValue("definitionEn").isNullOrBlank()) {
                    put("definitionEn", JsonPrimitive(word.definition))
                }
                if (old?.stringValue("imageAlt").isNullOrBlank()) {
                    put("imageAlt", JsonPrimitive("A representative photo for ${word.text}."))
                }
                put("imageAsset", JsonPrimitive(assetPath))
            })
        }
        return buildJsonObject {
            params.forEach { (key, value) -> if (key != "wordPopups") put(key, value) }
            put("wordPopups", popups)
        }
    }

    private fun repairWordFileName(wordNo: Int, word: RepairWord): String =
        "repair_${wordNo.toString().padStart(2, '0')}_${slugId(word.text).ifBlank { "word" }.take(32)}.png"

    private suspend fun requestAndSaveGridImage(words: List<String>, s: State, chatReason: String): ByteArray {
        val side = gridSide(words.size)
        _state.update { it.copy(status = "③ 그리드 그림 생성 요청 중… (${side}×$side, grid.png)") }
        prepareFreshChat(chatReason)
        val before = gateway.assistantMarker()
        if (!gateway.sendOnly(gridPrompt(words, side))) {
            error(gateway.lastQueryFailureMessage("그리드 그림 요청"))
        }
        val bytes = awaitGridImageBytes(before)
        saveImageDownload(s, GRID_FILE, bytes)
        writer.writeImage(s.bookId, s.unitId, GRID_FILE, bytes)
        lessonRepo.refresh()
        return bytes
    }

    private suspend fun awaitGridImageBytes(before: AuthoringWebGateway.AssistantMarker): ByteArray {
        for (i in 1..60) {
            delay(if (i == 1) 6000 else 3000)
            _state.update { it.copy(status = "자동 ③ 그리드 답변 완료 확인 중… $i/60") }
            if (!gateway.generatedImageActionsReadyAfter(before, minImageCount = 1)) continue
            delay(IMAGE_ACTION_MENU_SETTLE_MS)
            _state.update { it.copy(status = "자동 ③ 그리드 완료 감지 → 저장 중…") }
            val dataUrl = gateway.captureFirstImageDataUrlAfter(before, minImageCount = 1) ?: continue
            return decodeImageDataUrl(dataUrl)
        }
        throw IllegalStateException("그리드 그림 완료를 자동으로 못 찾음. 답변 하단 복사/좋음/나쁨 메뉴가 보이면 ‘저장’ 버튼으로 다시 시도하세요.")
    }

    private suspend fun awaitFirstWordImageBytes(before: AuthoringWebGateway.AssistantMarker): ByteArray {
        for (i in 1..60) {
            delay(if (i == 1) 6000 else 3000)
            _state.update { it.copy(status = "단어 사진 답변 완료 확인 중… $i/60") }
            if (!gateway.generatedImageActionsReadyAfter(before, minImageCount = 1)) continue
            delay(IMAGE_ACTION_MENU_SETTLE_MS)
            if (!gateway.generatedImageActionsReadyAfter(before, minImageCount = 1)) continue
            _state.update { it.copy(status = "단어 사진 완료 감지 → 첫 번째 사진 저장 중…") }
            val dataUrl = gateway.captureFirstImageDataUrlAfter(before, minImageCount = 1) ?: continue
            return decodeImageDataUrl(dataUrl)
        }
        throw IllegalStateException("단어 사진 완료를 자동으로 못 찾음. 답변 하단 복사/좋음/나쁨 메뉴가 보이면 다시 시도하세요.")
    }

    private fun decodeImageDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) throw IllegalStateException("이미지 데이터가 비었습니다.")
        return bytes
    }

    private suspend fun awaitStoryJsonChunk(before: AuthoringWebGateway.AssistantMarker): String {
        val requiredCount = before.count + 1
        for (i in 1..STORY_JSON_CHUNK_ATTEMPTS) {
            delay(if (i == 1) JSON_CHUNK_FIRST_DELAY_MS else JSON_CHUNK_RETRY_DELAY_MS)
            _state.update { it.copy(status = "② JSON 청크 확인 중… $i/$STORY_JSON_CHUNK_ATTEMPTS") }
            val captured = gateway.captureLastAssistantJsonChunked(requiredCount, before) ?: continue
            val jsonText = extractJson(captured) ?: captured.trim()
            if (looksLikeStoryParams(jsonText)) return jsonText
        }
        throw IllegalStateException("JSON 청크를 못 받음(응답 생성이 끝났는지, 화면에 JSON이 보이는지 확인).")
    }

    private suspend fun awaitPassageJsonChunk(
        before: AuthoringWebGateway.AssistantMarker,
        attempts: Int = PASSAGE_JSON_CHUNK_ATTEMPTS,
    ): String {
        val requiredCount = before.count + 1
        for (i in 1..attempts) {
            delay(if (i == 1) JSON_CHUNK_FIRST_DELAY_MS else JSON_CHUNK_RETRY_DELAY_MS)
            _state.update { it.copy(status = "② 지문 JSON 청크 확인 중… $i/$attempts") }
            val captured = gateway.captureLastAssistantJsonChunked(requiredCount, before) ?: continue
            val jsonText = extractJson(captured) ?: captured.trim()
            if (looksLikePassageParams(jsonText)) return jsonText
        }
        throw IllegalStateException("지문 JSON 청크를 못 받음(응답 생성이 끝났는지, 화면에 JSON이 보이는지 확인).")
    }

    private suspend fun awaitWordCardJsonChunk(before: AuthoringWebGateway.AssistantMarker): String {
        val requiredCount = before.count + 1
        for (i in 1..PASSAGE_JSON_CHUNK_ATTEMPTS) {
            delay(if (i == 1) JSON_CHUNK_FIRST_DELAY_MS else JSON_CHUNK_RETRY_DELAY_MS)
            _state.update { it.copy(status = "단어카드 JSON 확인 중… $i/$PASSAGE_JSON_CHUNK_ATTEMPTS") }
            val captured = gateway.captureLastAssistantJsonChunked(requiredCount, before) ?: continue
            val jsonText = extractJson(captured) ?: captured.trim()
            if (looksLikeWordCardParams(jsonText)) return jsonText
        }
        throw IllegalStateException("단어카드 JSON을 받지 못했습니다. 화면에 cards JSON이 보이는지 확인하세요.")
    }

    private fun looksLikeStoryParams(jsonText: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        obj["panels"]?.jsonArray?.isNotEmpty() == true && obj["words"]?.jsonArray?.isNotEmpty() == true
    }.getOrDefault(false)

    private fun looksLikePassageParams(jsonText: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        obj["paragraphs"]?.jsonArray?.isNotEmpty() == true ||
            obj["sentences"]?.jsonArray?.isNotEmpty() == true
    }.getOrDefault(false)

    private fun looksLikeWordCardParams(jsonText: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        obj["cards"]?.jsonArray?.isNotEmpty() == true
    }.getOrDefault(false)

    private fun parseWordCardParams(
        jsonText: String,
        passage: String,
        words: List<String>,
        s: State,
    ): JsonObject {
        val raw = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { throw IllegalStateException("단어카드 JSON 파싱 실패: ${it.message}") }
        val rawCards = raw["cards"]?.jsonArray ?: JsonArray(emptyList())
        if (rawCards.isEmpty()) throw IllegalStateException("단어카드 JSON에 cards가 없습니다.")
        val cards = rawCards.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val word = obj["word"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (word.isBlank()) return@mapIndexedNotNull null
            buildJsonObject {
                put("id", JsonPrimitive(obj["id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "w${index + 1}_${slugId(word)}"))
                put("word", JsonPrimitive(word))
                put("meaningKo", normalizeStringArray(obj, "meaningKo", fallback = wordCardMeaningFallback(obj, word)))
                put("structureKo", normalizeStringArray(obj, "structureKo", fallback = wordCardStructureFallback(obj, word)))
                put("examples", normalizeExamples(obj, word, passage))
                put("checked", obj["checked"] ?: JsonPrimitive(false))
            }
        }
        if (cards.isEmpty()) throw IllegalStateException("단어카드 JSON에서 유효한 카드를 찾지 못했습니다.")
        return buildJsonObject {
            raw.forEach { (key, value) -> if (key !in setOf("title", "passage", "cards", "selectedWords")) put(key, value) }
            put("title", JsonPrimitive(raw["title"]?.jsonPrimitive?.contentOrNull ?: s.unitTitle.ifBlank { "Word Cards" }))
            put("passage", JsonPrimitive(raw["passage"]?.jsonPrimitive?.contentOrNull ?: passage))
            put("selectedWords", JsonArray(words.map { JsonPrimitive(it) }))
            put("cards", JsonArray(cards))
        }
    }

    private fun wordCardMeaningFallback(obj: JsonObject, word: String): List<String> {
        val koSense = obj["ko_sense"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val desc = obj["desc"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return listOf(koSense, desc)
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("${word} 뜻을 아주 쉬운 말로 확인해요.") }
    }

    private fun wordCardStructureFallback(obj: JsonObject, word: String): List<String> {
        val frame = obj["frame"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (frame.isBlank() || frame.equals("null", ignoreCase = true)) {
            return listOf("${word}는 뜻과 쓰임을 문장 안에서 떠올리면 돼요.")
        }
        return listOf("이 단어는 문장에서 $frame 모양으로 쓰여요.")
    }

    private fun normalizeStringArray(obj: JsonObject, key: String, fallback: List<String>): JsonArray {
        val values = when (val value = obj[key]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull?.trim())
            else -> emptyList()
        }.filter { it.isNotBlank() }
        return JsonArray((values.ifEmpty { fallback }).map { JsonPrimitive(it) })
    }

    private fun normalizeExamples(obj: JsonObject, word: String, passage: String): JsonArray {
        val raw = obj["examples"] as? JsonArray
        val exKo = obj.stringListValue("ex_ko")
        val examples = raw?.mapNotNull { element ->
            val index = raw.indexOf(element)
            when (element) {
                is JsonObject -> {
                    val en = element["en"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (en.isBlank()) null else buildJsonObject {
                        put("en", JsonPrimitive(en))
                        put("ko", JsonPrimitive(element["ko"]?.jsonPrimitive?.contentOrNull ?: exKo.getOrNull(index).orEmpty()))
                        put("noteKo", JsonPrimitive(element["noteKo"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    }
                }
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    buildJsonObject {
                        put("en", JsonPrimitive(it))
                        put("ko", JsonPrimitive(exKo.getOrNull(index).orEmpty()))
                        put("noteKo", JsonPrimitive(""))
                    }
                }
                else -> null
            }
        }.orEmpty()
        if (examples.isNotEmpty()) return JsonArray(examples)
        val sentence = passage.lineSequence().firstOrNull { it.contains(word, ignoreCase = true) }
            ?: passage.lineSequence().firstOrNull().orEmpty()
        return JsonArray(
            listOf(
                buildJsonObject {
                    put("en", JsonPrimitive(sentence.ifBlank { "This sentence uses $word." }))
                    put("ko", JsonPrimitive(""))
                    put("noteKo", JsonPrimitive("${word}를 지문 문장 속에서 찾아봐요."))
                },
            ),
        )
    }

    private fun fallbackPassageParams(sentences: List<String>, s: State): JsonObject {
        val sentenceObjects = sentences.mapIndexed { index, sentence ->
            val sentenceId = "s${index + 1}"
            buildJsonObject {
                put("id", JsonPrimitive(sentenceId))
                put("text", JsonPrimitive(sentence))
                put("chunks", JsonArray(chunkSentence(sentence, sentenceId)))
            }
        }
        val chunkSize = ((sentenceObjects.size + 1) / 2).coerceAtLeast(1)
        val paragraphs = sentenceObjects.chunked(chunkSize).mapIndexed { index, group ->
            buildJsonObject {
                put("id", JsonPrimitive("p${index + 1}"))
                put("title", JsonPrimitive(if (index == 0) "Read and Decode" else "Keep Decoding"))
                put("overlookQuestion", JsonPrimitive("What do these sentences help us understand?"))
                put("overlookHintKo", JsonPrimitive("문장을 작은 덩어리로 나누면 누가 무엇을 하는지 찾기 쉬워요."))
                put("sentences", JsonArray(group))
            }
        }
        return buildJsonObject {
            put("title", JsonPrimitive(s.unitTitle.ifBlank { "Shared Read" }))
            put("trackLabel", JsonPrimitive("Shared Read"))
            put("curiosityQuestion", JsonPrimitive("What can we understand by reading each chunk?"))
            put("coverVisual", JsonPrimitive("📖"))
            put("defaultChunkSetId", JsonPrimitive("short"))
            put(
                "processSteps",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", JsonPrimitive("notice"))
                            put("label", JsonPrimitive("Notice"))
                            put("caption", JsonPrimitive("Look for who does what."))
                            put("visual", JsonPrimitive("🔎"))
                        },
                        buildJsonObject {
                            put("id", JsonPrimitive("chunk"))
                            put("label", JsonPrimitive("Chunk"))
                            put("caption", JsonPrimitive("Read each sentence in small parts."))
                            put("visual", JsonPrimitive("🧩"))
                        },
                    ),
                ),
            )
            put("exploreItems", JsonArray(emptyList()))
            put("paragraphs", JsonArray(paragraphs))
        }
    }

    private fun chunkSentence(sentence: String, sentenceId: String): List<JsonObject> {
        val wordMatches = Regex("\\S+").findAll(sentence).toList()
        if (wordMatches.isEmpty()) return listOf(chunkObject(sentenceId, 1, sentence, 0, sentence.length, "object"))
        val targetChunks = wordMatches.size.coerceIn(2, 5)
        val wordsPerChunk = ((wordMatches.size + targetChunks - 1) / targetChunks).coerceAtLeast(1)
        return wordMatches.chunked(wordsPerChunk).mapIndexed { index, group ->
            val start = group.first().range.first
            val end = group.last().range.last + 1
            val role = when (index) {
                0 -> "subject"
                1 -> "action"
                2 -> "object"
                3 -> "place"
                else -> "linker"
            }
            chunkObject(sentenceId, index + 1, sentence.substring(start, end), start, end, role)
        }
    }

    private fun chunkObject(
        sentenceId: String,
        index: Int,
        text: String,
        start: Int,
        end: Int,
        role: String,
    ): JsonObject = buildJsonObject {
        put("id", JsonPrimitive("${sentenceId}_c$index"))
        put("text", JsonPrimitive(text))
        put("start", JsonPrimitive(start))
        put("end", JsonPrimitive(end))
        put("role", JsonPrimitive(role))
        put("meaningKo", JsonPrimitive(""))
        put("teacherPrompt", JsonPrimitive("Read this small part and ask what it tells us."))
    }

    /** GPT가 준 params JSON 문자열에 그리드 셀/이미지 경로를 채운다(query·링크 공용). */
    private fun enrichStoryParams(jsonText: String, words: List<String>, s: State): JsonObject {
        val raw = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { throw IllegalStateException("JSON 파싱 실패: ${it.message}") }

        // wordPopups 각 항목에 **한 장의 그리드** 경로 + 칸 정보(cell/cols/rows)를 채운다.
        // 칸은 단어 순서대로 0,1,2… 배정 → story_comic 이 grid.png 에서 그 칸만 잘라 표시.
        val side = gridSide(words.size)
        val gridPath = writer.imageAssetPath(s.bookId, s.unitId, GRID_FILE)
        val cellOf = words.mapIndexed { i, w -> w.lowercase().trim() to i }.toMap()
        val popups = raw["wordPopups"]?.jsonObject
        val newPopups = buildJsonObject {
            popups?.forEach { (w, v) ->
                val o = v.jsonObject
                val cell = cellOf[w.lowercase().trim()] ?: -1
                put(w, buildJsonObject {
                    o.forEach { (k, vv) -> if (k !in gridKeys) put(k, vv) }
                    put("imageAsset", JsonPrimitive(gridPath))
                    put("cell", JsonPrimitive(cell))
                    put("gridCols", JsonPrimitive(side))
                    put("gridRows", JsonPrimitive(side))
                })
            }
        }
        return buildJsonObject {
            raw.forEach { (k, v) -> if (k != "wordPopups") put(k, v) }
            put("wordPopups", newPopups)
        }
    }

    private fun storyPrompt(words: List<String>): String = buildString {
        append("다음 영어 단어들을 한 이야기로 엮은 '전체만화' 한 단원의 params JSON 객체만 만들어줘")
        append("(코드펜스·설명 없이 JSON 객체로만 시작해서 끝나게).\n")
        append("단어: ${words.joinToString(", ")}\n\n")
        append("스키마:\n")
        append("{\n")
        append("  \"title\": 영어 제목,\n")
        append("  \"meaning\": 한국어 한 줄 설명,\n")
        append("  \"words\": [위 단어들],\n")
        append("  \"wordExplanations\": { 소문자단어: 아이용 쉬운 영어 설명 1문장 },\n")
        append("  \"wordPopups\": { 소문자단어: { \"definitionEn\": 쉬운 영어 정의, \"imageAlt\": 그 단어를 나타낼 그림 묘사 1문장 } },\n")
        append("  \"panels\": [ { \"bg\": $BG_LIST 중 하나, \"caption\": 그 컷 영어 문장(오늘의 단어 포함), ")
        append("\"climax\": true/false, \"sprites\": [ { \"char\": 이모지, \"x\":0~100, \"y\":0~100, \"scale\":0.5~1.6, \"anim\": $ANIM_LIST 중 하나 } ], ")
        append("\"bubble\": { \"anchor\":0, \"text\": 말풍선 영어 }, \"zoom\": { \"type\": \"pushin|pan|kenburns|static\", \"scale\":1.2~1.4, \"originX\":50, \"originY\":55 } } ],\n")
        append("}\n")
        append("규칙: 4~8컷, 모든 단어가 캡션들에 자연스럽게 등장. imageAsset은 비워둬(우리가 채움). JSON만 출력.")
    }

    private fun passagePrompt(sentences: List<String>, s: State): String = buildString {
        append("아래 영어 문장들로 passage_read 스텝의 params JSON 객체만 만들어줘")
        append("(코드펜스·설명 없이 JSON 객체로만 시작해서 끝나게).\n")
        append("목표: 학생이 지문 내용을 외우는 것이 아니라 영어 문장을 청크 단위로 해독하는 힘을 기르는 것.\n")
        append("단원 제목 힌트: ${s.unitTitle.ifBlank { s.unitId }}\n\n")
        append("문장 목록(이 문장 text는 절대 바꾸지 말 것):\n")
        sentences.forEachIndexed { i, sentence -> append("${i + 1}. $sentence\n") }
        append("\n스키마:\n")
        append("{\n")
        append("  \"title\": 영어 제목,\n")
        append("  \"trackLabel\": \"Shared Read\",\n")
        append("  \"curiosityQuestion\": 아이가 궁금해할 질문 1개,\n")
        append("  \"coverVisual\": 이 지문을 상징하는 이모지 1개,\n")
        append("  \"defaultChunkSetId\": \"short\",\n")
        append("  \"processSteps\": [ { \"id\": 짧은 id, \"label\": 영어 라벨, \"caption\": 아주 짧은 설명, \"visual\": 이모지 } ],\n")
        append("  \"exploreItems\": [ { \"id\": 짧은 id, \"label\": 탐험할 대상, \"sentenceIds\": [관련 문장 id], \"searchContext\": 영어 설명, \"parentPrompt\": 보호자 대화 질문, \"visual\": 이모지 } ],\n")
        append("  \"paragraphs\": [ { \"id\": \"p1\", \"title\": 문단 제목, \"overlookQuestion\": 문단 확인 질문, \"overlookHintKo\": 한국어 힌트, \"sentences\": [ ... ] } ]\n")
        append("}\n\n")
        append("각 sentence 객체 규칙:\n")
        append("- id는 s1, s2처럼 순서대로.\n")
        append("- text는 위 문장과 글자 하나도 바꾸지 말 것.\n")
        append("- chunks는 2~5개. 각 chunk는 {id,text,startChar,endChar,role,decodeHint,meaningKo,exploreIds}.\n")
        append("- startChar/endChar는 Kotlin substring(startChar, endChar)가 chunk text와 정확히 같도록 0-based로 계산.\n")
        append("- role은 subject/action/object/time/place/linker 중 자연스럽게.\n")
        append("- decodeHint는 한국어로 짧게, meaningKo도 청크 단위 한국어 뜻으로.\n")
        append("- exploreIds는 관련 exploreItems가 있을 때만 연결하고, 없으면 [].\n")
        append("문장이 5개 이하면 문단 1개, 많으면 의미 흐름에 따라 2~3문단. JSON만 출력.")
    }

    private fun passageRetryPrompt(sentences: List<String>, s: State): String = buildString {
        append("Return ONLY one valid JSON object. No markdown. No code fence. No explanation.\n")
        append("Create passage_read params for sentence decoding practice.\n")
        append("Title hint: ${s.unitTitle.ifBlank { s.unitId }}\n")
        append("Do not alter sentence text.\n")
        append("Required top-level keys: title, trackLabel, curiosityQuestion, coverVisual, defaultChunkSetId, processSteps, exploreItems, paragraphs.\n")
        append("Each paragraph has id, title, overlookQuestion, overlookHintKo, sentences.\n")
        append("Each sentence has id, text, chunks.\n")
        append("Each chunk has id, text, startChar, endChar, role, decodeHint, meaningKo, exploreIds.\n")
        append("Sentences:\n")
        sentences.forEachIndexed { i, sentence -> append("${i + 1}. $sentence\n") }
    }

    private fun wordCardEnglishPassagePrompt(source: String): String = buildString {
        append("다음 지문을 아이 영어 학습용으로 깨끗한 영어 지문으로 옮겨줘.\n")
        append("이미 영어라면 문장을 자연스럽게 정리만 하고 뜻은 바꾸지 마.\n")
        append("문제, 선택지, 번호, 한국어 설명은 빼고 실제 읽을 영어 지문만 남겨줘.\n")
        append("반드시 JSON 객체 하나만 출력해: {\"passage\":\"...\"}\n\n")
        append(source)
    }

    private fun wordCardBracketPrompt(passage: String): String = buildString {
        append(
            """
            지문에서 공부할 단어 후보를 골라 버튼용 목록으로 만들어.

            [빼는 것]
            - 문장 "중간"에 대문자로 시작하는 말(이름·지명). 단 문장 맨 앞 단어는 빼지 마.
            - 따옴표 안이나 기울임꼴(제목·인용) 안의 단어.
            - 숫자, 글자로 쓴 수(Fifty-one, twenty), 하이픈으로 이은 복합어(three-headed).
            - the, a, is, and, to, in, of 같은 기본 기능어.

            [묶는 것]
            - 같은 기본형은 버튼 하나로: knocking·knocked → "knock", built·building → "build".
            - 구동사·여러 단어가 한 뜻이면 통째 한 버튼: "knock down", "figure out", "be made from", "look for".

            각 버튼: {"word":"기본형 또는 묶음", "src":"그 단어가 나온 문장 원문"}
            같은 단어가 다른 문장에서 다른 뜻으로 나오면 각각 버튼으로.
            JSON 배열만. 설명 금지.

            지문:
            """.trimIndent(),
        )
        append("\n\"\"\"\n")
        append(passage)
        append("\n\"\"\"")
    }

    private fun wordCardExplanationPrompt(
        passage: String,
        candidates: List<WordCardCandidate>,
        customPrompt: String,
        s: State,
    ): String = buildString {
        append(customPrompt.ifBlank { DEFAULT_WORD_CARD_PROMPT }.trim())
        append("\n\nReturn ONLY one valid JSON object. No markdown. No code fence. No explanation.\n")
        append("Create params for a word_card step.\n")
        append("Title hint: ${s.unitTitle.ifBlank { s.unitId }}\n")
        append("Use the selected word/src items below. Keep card order exactly the same.\n")
        append("Selected word/src JSON:\n")
        append(JsonArray(candidates.map { candidate ->
            buildJsonObject {
                put("word", JsonPrimitive(candidate.word))
                put("src", JsonPrimitive(candidate.src.ifBlank { passage.lineSequence().firstOrNull { it.contains(candidate.word, ignoreCase = true) }.orEmpty() }))
            }
        }).toString())
        append("\nPassage:\n")
        append(passage)
        append("\n\nRequired JSON schema:\n")
        append("{\n")
        append("  \"title\": \"short English title\",\n")
        append("  \"passage\": \"the English passage\",\n")
        append("  \"cards\": [\n")
        append("    {\n")
        append("      \"id\": \"w_word\",\n")
        append("      \"word\": \"selected word\",\n")
        append("      \"ko_sense\": \"이 문장에서의 뜻과 쓰임\",\n")
        append("      \"frame\": \"나온 모양 그대로 + [슬롯], or null\",\n")
        append("      \"desc\": \"세 문장짜리 순한글 설명\",\n")
        append("      \"examples\": [\"easy sentence\", \"one more piece\", \"near passage sentence\"],\n")
        append("      \"ex_ko\": [\"쉬운 뜻\", \"쉬운 뜻\", \"쉬운 뜻\"],\n")
        append("      \"checked\": false\n")
        append("    }\n")
        append("  ]\n")
        append("}\n")
    }

    private fun wordCardDefinitionExplanationPrompt(
        candidates: List<WordCardCandidate>,
        customPrompt: String,
        s: State,
    ): String = buildString {
        append(customPrompt.ifBlank { DEFAULT_WORD_CARD_PROMPT }.trim())
        append("\n\nReturn ONLY one valid JSON object. No markdown. No code fence. No explanation.\n")
        append("Create params for a word_card step from vocabulary-photo definitions.\n")
        append("Title hint: ${s.unitTitle.ifBlank { s.unitId }}\n")
        append("Use EVERY word/definition item below. Do not add extra words. Keep order exactly the same.\n")
        append("The definition field is the English meaning printed next to the word in the photo. Base the Korean sense, easy explanation, and examples on that definition.\n")
        append("Word/definition JSON:\n")
        append(JsonArray(candidates.map { candidate ->
            buildJsonObject {
                put("word", JsonPrimitive(candidate.word))
                put("definition", JsonPrimitive(candidate.src))
            }
        }).toString())
        append("\n\nRequired JSON schema:\n")
        append("{\n")
        append("  \"title\": \"short English title\",\n")
        append("  \"passage\": \"short summary of these vocabulary words\",\n")
        append("  \"cards\": [\n")
        append("    {\n")
        append("      \"id\": \"w_word\",\n")
        append("      \"word\": \"word from list\",\n")
        append("      \"ko_sense\": \"photo definition을 바탕으로 한 쉬운 한국어 뜻\",\n")
        append("      \"frame\": \"verb/pattern frame, or null for noun/adjective when not needed\",\n")
        append("      \"desc\": \"세 문장짜리 순한글 설명\",\n")
        append("      \"examples\": [\"easy sentence\", \"one more piece\", \"near real-use sentence\"],\n")
        append("      \"ex_ko\": [\"쉬운 뜻\", \"쉬운 뜻\", \"쉬운 뜻\"],\n")
        append("      \"checked\": false\n")
        append("    }\n")
        append("  ]\n")
        append("}\n")
    }

    private fun passageDownloadPrompt(
        batch: PassageJsonBatch,
        s: State,
        part: Int,
        total: Int,
        fileBaseName: String,
        isFinal: Boolean,
    ): String = buildString {
        if (part == 1) {
            append("Create the initial passage_read JSON object for a long passage.\n")
        } else {
            append("Continue from the previous JSON object in this same chat.\n")
            append("Append the new sentences below to the existing JSON. Keep all earlier paragraphs/sentences/chunks already produced.\n")
            append("Return the full updated JSON object, not only the added part.\n")
        }
        append("Provide the current full updated JSON as a downloadable file named \"$fileBaseName.json\".\n")
        append("If file creation is unavailable, answer with ONLY the current full updated raw JSON object so the app can save it.\n")
        if (isFinal) append("This is the final part.\n") else append("This is not the final part.\n")
        append("Do not make an image. Do not open a new chat. No markdown. No code fence. No explanation.\n\n")
        append("Task: create passage_read params for English decoding practice, not content memorization.\n")
        append("This is part $part of $total for a long passage.\n")
        append("For this part, add the sentences listed below to the JSON.\n")
        append("Keep the original sentence text exactly. Use sentence ids starting from s${batch.startNumber}.\n")
        append("Title hint: ${s.unitTitle.ifBlank { s.unitId }}\n\n")
        append("Required JSON schema:\n")
        append("{\n")
        append("  \"title\": \"English title\",\n")
        append("  \"trackLabel\": \"Shared Read\",\n")
        append("  \"curiosityQuestion\": \"one child-friendly question\",\n")
        append("  \"coverVisual\": \"one visual description\",\n")
        append("  \"defaultChunkSetId\": \"short\",\n")
        append("  \"processSteps\": [{\"id\":\"step1\",\"label\":\"English label\",\"caption\":\"short caption\",\"visual\":\"visual description\"}],\n")
        append("  \"exploreItems\": [{\"id\":\"item1\",\"label\":\"topic\",\"sentenceIds\":[\"s1\"],\"searchContext\":\"context\",\"parentPrompt\":\"guardian question\",\"visual\":\"visual description\"}],\n")
        append("  \"paragraphs\": [{\"id\":\"p$part\",\"title\":\"paragraph title\",\"overlookQuestion\":\"question\",\"overlookHintKo\":\"Korean hint\",\"sentences\":[...]}]\n")
        append("}\n\n")
        append("Each sentence object must have id, text, chunks.\n")
        append("Each chunk must have id, text, startChar, endChar, role, decodeHint, meaningKo, exploreIds.\n")
        append("startChar/endChar must match Kotlin substring(startChar, endChar) for the chunk text.\n")
        append("Use 2 to 5 chunks per sentence. role is one of subject/action/object/time/place/linker.\n\n")
        append("Sentences:\n")
        batch.sentences.forEachIndexed { i, sentence -> append("${batch.startNumber + i}. $sentence\n") }
    }

    private fun photoSentenceBatchPrompt(startPage: Int, endPage: Int, count: Int): String = buildString {
        append("첨부된 이미지 ${count}장은 영어책 지문 사진이며, 사용자가 선택한 순서대로 전체 책의 ")
        append("${startPage}쪽부터 ${endPage}쪽까지야.\n")
        append("각 이미지에서 아이가 읽을 영어 지문 문장만 추출해줘. 문제 지시문, 한국어 설명, 번호, 선택지, 빈칸, 낙서는 제외해.\n")
        append("문장은 가능한 한 원문 그대로 유지하고, 줄바꿈 때문에 끊긴 문장은 자연스럽게 한 문장으로 합쳐.\n")
        append("반드시 첨부 이미지 수($count)와 같은 수의 pages 항목을 만들어. 문장이 없는 페이지는 sentences: [] 로 둬.\n")
        append("설명·코드펜스 없이 아래 JSON 객체만 출력:\n")
        append("{\"pages\":[")
        append("{\"pageNumber\":$startPage,\"sentences\":[\"...\"]}")
        if (count > 1) append(",{\"pageNumber\":${startPage + 1},\"sentences\":[\"...\"]}")
        append("]}")
    }

    private fun photoWordBatchPrompt(startPage: Int, endPage: Int, count: Int): String = buildString {
        append("첨부된 이미지 ${count}장은 영어 단어/표현 사진이며, 선택 순서상 ${startPage}~${endPage}쪽이야.\n")
        append("초등학생이 배울 핵심 영어 단어와 표현을 8~20개 골라 JSON 배열로만 답해줘. ")
        append("중복은 제거하고, 책에 나온 원형 표현을 우선해. 설명·코드펜스 없이 [\"word\", ...] 형식만.")
    }

    private fun photoWordDefinitionBatchPrompt(startPage: Int, endPage: Int, count: Int): String = buildString {
        append("첨부된 이미지 ${count}장은 영어 단어와 그 옆/아래 영어 뜻(definition)이 함께 있는 단어장 사진이며, 선택 순서상 ${startPage}~${endPage}쪽이야.\n")
        append("이미지에 보이는 모든 단어/표현과 바로 옆에 적힌 영어 뜻을 빠짐없이 추출해줘.\n")
        append("품사 표시(adj., n., v. 등)는 definition에 포함해도 되지만, 단어 자체에는 넣지 마.\n")
        append("뜻이 여러 줄이면 자연스럽게 한 문장으로 합쳐. 임의로 새 단어를 추가하지 마.\n")
        append("설명·코드펜스 없이 JSON 배열만 출력:\n")
        append("[{\"word\":\"fizzy\",\"definition\":\"having a lot of gas bubbles\"}]")
    }

    private fun pageOrderPrompt(pages: List<PageSentences>): String = buildString {
        append("아래는 여러 번 나눠 추출한 페이지별 영어 문장 JSON이야.\n")
        append("pageNumber는 사용자가 사진을 선택한 순서지만, 페이지끼리 순서가 엉켰을 수도 있어. ")
        append("pageNumber와 이야기/지문 흐름을 함께 보고 최종 읽기 순서의 영어 문장 배열 하나로 정리해줘.\n")
        append("규칙: 원문 문장 자체는 고치지 말 것, 중복 문장은 제거, 지시문/문제/선택지는 제외, 설명·코드펜스 없이 JSON 배열만 출력.\n\n")
        append(pageGroupsJson(pages))
    }

    private fun pageGroupsJson(pages: List<PageSentences>): String =
        JsonArray(
            pages.sortedBy { it.pageNumber }.map { page ->
                buildJsonObject {
                    put("pageNumber", JsonPrimitive(page.pageNumber))
                    put("sentences", JsonArray(page.sentences.map { JsonPrimitive(it) }))
                }
            },
        ).toString()

    /** 단어 전체를 한 장에 담는 격자 그리드 프롬프트(칸마다 자잘한 이야깃거리). */
    private fun gridPrompt(words: List<String>, side: Int): String = buildString {
        append("Create ONE single image: a ${side}×$side grid (thin white gridlines, clean white background). ")
        append("Fill the cells LEFT-TO-RIGHT, TOP-TO-BOTTOM, one cell per English word IN THIS EXACT ORDER:\n")
        words.forEachIndexed { i, w -> append("${i + 1}. $w\n") }
        append("Leave any remaining cells empty (plain white). ")
        append("Each cell is a small, colorful flat illustration for a child that clearly shows the word's meaning, ")
        append("with little story details/props around it that hint at how the word is used. ")
        append("Do NOT put any letters or text inside the image. Same art style across all cells. ")
        append("After it's generated I'll save it as grid.png.")
    }

    /** 그림다시 단어별 사진 프롬프트. 반드시 이 한 줄만 보낸다. */
    private fun repairWordPhotoPrompt(word: RepairWord): String =
        "\"${word.text}\"(${word.definition}) 을 설명할 수 있는 사진 찾아줘."

    private suspend fun saveJsonDownload(s: State, suffix: String, text: String) {
        runCatching {
            gateway.saveTextToDownloads(downloadName(s, suffix), text, "application/json")
        }
    }

    private suspend fun saveImageDownload(s: State, suffix: String, bytes: ByteArray) {
        runCatching {
            gateway.saveBytesToDownloads(downloadName(s, suffix), bytes, "image/png")
        }
    }

    private fun downloadName(s: State, suffix: String): String {
        val stem = s.unitId.ifBlank { "unit" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${stem}_$suffix"
    }

    private fun ensureUnitId(s: State): State {
        if (s.unitId.isNotBlank()) return s
        val generated = autoUnitId(s)
        val next = s.copy(unitId = generated, error = null)
        _state.value = next
        return next
    }

    private fun autoUnitId(s: State): String {
        val seed = sequenceOf(
            s.unitTitle,
            s.source.lineSequence().firstOrNull().orEmpty(),
            s.words.firstOrNull().orEmpty(),
            s.sentences.firstOrNull().orEmpty(),
        )
            .map { slugId(it).take(32).trim('_') }
            .firstOrNull { it.isNotBlank() }
            ?: "unit"
        val suffix = (System.currentTimeMillis() % 1_000_000L).toString().padStart(6, '0')
        return "${seed}_$suffix"
    }

    private fun gridSide(n: Int): Int {
        var side = 4
        while (side * side < n) side++   // 16칸이면 4×4, 더 많으면 키운다
        return side
    }

    private fun uploadDelayMs(count: Int): Long =
        (2500L + count.coerceAtLeast(1) * 1200L).coerceAtMost(9000L)

    private suspend fun prepareFreshChat(reason: String) {
        isolateAuthoringWebView()
        gateway.provideView()
        runCatching { gateway.clearComposerDraftOnEntry() }
            .onFailure { Log.d("AuthoringViewModel", "fresh chat draft clear skipped reason=$reason error=${it.message}") }
    }

    private fun ensurePhotoAttachSucceeded(result: String) {
        if (result.startsWith("ok ")) return
        error("사진 첨부 실패($result). 다시 시도하세요.")
    }

    private fun resolvePhotoRange(anchors: List<Uri>): List<Uri> {
        return try {
            val first = readImageMeta(anchors[0]) ?: error("첫 번째 사진 정보를 읽지 못했습니다.")
            val second = readImageMeta(anchors[1]) ?: error("두 번째 사진 정보를 읽지 못했습니다.")
            val useRelativePath = first.relativePath?.takeIf { it.isNotBlank() } == second.relativePath?.takeIf { it.isNotBlank() }
            val useBucket = first.bucketId?.takeIf { it.isNotBlank() } == second.bucketId?.takeIf { it.isNotBlank() }
            if (!useRelativePath && !useBucket) {
                error("선택한 두 사진이 같은 폴더/앨범에 있지 않습니다.")
            }
            val minModified = minOf(first.modified, second.modified)
            val maxModified = maxOf(first.modified, second.modified)
            val projection = imageProjection()
            val dateColumn = MediaStore.Images.Media.DATE_MODIFIED
            val idColumn = MediaStore.Images.Media._ID
            val selectionParts = mutableListOf("$dateColumn BETWEEN ? AND ?")
            val selectionArgs = mutableListOf(minModified.toString(), maxModified.toString())
            if (useRelativePath && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selectionParts += "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                selectionArgs += first.relativePath.orEmpty()
            } else {
                selectionParts += "${MediaStore.Images.Media.BUCKET_ID} = ?"
                selectionArgs += first.bucketId.orEmpty()
            }
            val out = mutableListOf<Uri>()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                "$dateColumn ASC, $idColumn ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.longOrNull(idColumn) ?: continue
                    out += ContentUris.withAppendedId(collection, id)
                }
            }
            if (out.isEmpty()) error("같은 폴더에서 수정일 범위 사진을 찾지 못했습니다.")
            out.distinctBy { it.toString() }
        } catch (e: SecurityException) {
            throw IllegalStateException("사진 라이브러리 전체 접근 권한이 필요합니다. 권한을 허용한 뒤 다시 시도하세요.")
        }
    }

    private fun readImageMeta(uri: Uri): ImageMeta? {
        val direct = queryImageMeta(uri)
        if (direct != null && direct.id > 0L && direct.modified > 0L && direct.hasAlbumKey()) return direct.copy(uri = uri)
        val mediaId = direct?.id?.takeIf { it > 0L } ?: numericIdFromUri(uri)
        if (mediaId != null) {
            val byId = queryImageMeta(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                selection = "${MediaStore.Images.Media._ID} = ?",
                selectionArgs = arrayOf(mediaId.toString()),
            )
            if (byId != null) return byId
        }
        return direct?.copy(uri = uri)
    }

    private fun queryImageMeta(
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): ImageMeta? {
        val projection = imageProjection()
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.longOrNull(MediaStore.Images.Media._ID) ?: numericIdFromUri(uri) ?: 0L
            return ImageMeta(
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                id = id,
                modified = cursor.longOrNull(MediaStore.Images.Media.DATE_MODIFIED) ?: 0L,
                bucketId = cursor.stringOrNull(MediaStore.Images.Media.BUCKET_ID),
                relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.stringOrNull(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    cursor.stringOrNull(MediaStore.Images.Media.DATA)?.substringBeforeLast('/', missingDelimiterValue = "")
                },
                displayName = cursor.stringOrNull(MediaStore.Images.Media.DISPLAY_NAME),
            )
        }
        return null
    }

    private fun ImageMeta.hasAlbumKey(): Boolean =
        !relativePath.isNullOrBlank() || !bucketId.isNullOrBlank()

    private fun imageProjection(): Array<String> = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DATE_MODIFIED)
        add(MediaStore.Images.Media.BUCKET_ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            add(MediaStore.Images.Media.DATA)
        }
    }.toTypedArray()

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun numericIdFromUri(uri: Uri): Long? =
        uri.pathSegments.asReversed().firstNotNullOfOrNull { it.toLongOrNull() }

    // ----------------------------------------------------------- helpers

    /** Strip code fences and pull the first balanced JSON object/array. */
    private fun extractJson(text: String): String? {
        val t = text.replace("```json", "```").let {
            val fenced = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)
            (fenced ?: it).trim()
        }
        for (start in t.indices) {
            if (t[start] != '{' && t[start] != '[') continue
            val candidate = balancedJsonCandidate(t, start) ?: continue
            if (runCatching { json.parseToJsonElement(candidate) }.isSuccess) return candidate
        }
        return null
    }

    private fun balancedJsonCandidate(text: String, start: Int): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isEmpty() || stack.removeLast() != ch) return null
                    if (stack.isEmpty()) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun slugId(v: String): String =
        v.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringListValue(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun mergePhotoUris(current: List<Uri>, added: List<Uri>): List<Uri> =
        (current + added).distinctBy { it.toString() }

    private companion object {
        const val BG_LIST = "(snow/sky/forest/desert/lava/ocean/night/room/sunset/plain)"
        const val ANIM_LIST = "(none/bounce/shiver/float/dash/roll/jump/sway/spin)"
        const val GRID_FILE = "grid.png"
        const val PHOTO_BATCH_SIZE = 5
        const val PASSAGE_JSON_CHUNK_SENTENCES = 12
        const val PASSAGE_JSON_CHUNK_CHARS = 3_800
        const val STORY_JSON_CHUNK_ATTEMPTS = 480
        const val PASSAGE_JSON_CHUNK_ATTEMPTS = 480
        const val JSON_CHUNK_FIRST_DELAY_MS = 5_000L
        const val JSON_CHUNK_RETRY_DELAY_MS = 5_000L
        const val IMAGE_ACTION_MENU_SETTLE_MS = 2_000L
        const val PASSAGE_JSON_USE_DOWNLOAD_FLOW = true
        const val DEFAULT_WORD_CARD_PROMPT =
            "7세 남아용 단어 학습 카드를 만들어. 아이는 한자어를 못 알아들어.\n\n" +
                "[받는 것] word(또는 묶음)와 그 단어가 나온 문장(src).\n\n" +
                "[frame 규칙]\n" +
                "- frame은 그 단어가 직접 거느리는 부분만. 명사·사람·사물·장소 이름은 frame 없이 뜻만 줘(frame:null). 주어로 쓰였다고 뒤 문장을 frame에 끌어오지 마.\n" +
                "  예: contest·bulldozer·operator는 frame 없음. hold·knock·give만 frame 가짐.\n" +
                "- 동사·구동사는 지문에 나온 모양 그대로 한 덩어리로. 수동태·구동사 문법으로 풀지 마.\n" +
                "  be made from → frame: be made from + [재료]\n" +
                "  give (gave the people a great idea) → frame: give + [누구] + [무엇]\n" +
                "- ko_sense는 이 문장에서 쓰인 뜻·용법으로. 사전 1번 뜻 말고(hold=열리다 / hold=버티다 구분).\n\n" +
                "[설명(desc) 규칙 — 카드의 핵심]\n" +
                "- 그 단어의 느낌을 세 문장으로 풀어. 짧게 끊지 말고 그림이 그려지게.\n" +
                "- frame 있는 단어(동사 등): 뒤에 무엇이 오는지를 자연스럽게 녹여. 이 말을 보면 뒤에 ~가 오겠구나 하는 느낌이 들게.\n" +
                "- frame 없는 단어(명사 등): 그게 뭔지, 어떻게 생겼는지, 언제 쓰는지를 떠올리게.\n" +
                "- 모두 순한글 구어체 반말. 보호·위험·계획 같은 추상 한자어 금지(지켜주다·무서운·미리 정하다로). 창문·책상 같은 기본 명사는 OK.\n\n" +
                "[예문 규칙]\n" +
                "- 쉬운 단어로, 지문 어순을 그대로 따라. 동사는 3칸 모두 완전한 문장으로.\n" +
                "- 3단: ①제일 쉽게 ②한 조각 더 ③지문 문장에 가깝게.\n\n" +
                "각 카드에는 word, ko_sense, frame, desc, examples 3개, ex_ko 3개를 채워. 최종 출력 형식은 뒤에 붙는 앱 저장용 JSON 스키마를 따라."
        val gridKeys = setOf("imageAsset", "cell", "gridCols", "gridRows")
        const val PHOTO_WORD_PROMPT =
            "이 사진을 보고 초등학생이 배울 핵심 영어 단어를 8~14개 골라 JSON 배열로만 답해줘. " +
                "설명·코드펜스 없이 [\"word\", ...] 형식만."
        const val PHOTO_SENTENCE_PROMPT =
            "이 사진에서 아이가 읽을 영어 문장만 순서대로 추출해 JSON 배열로만 답해줘. " +
                "문장을 가능한 한 고치지 말고, 문제 지시문/한국어 설명/번호/빈칸은 제외해줘. " +
                "설명·코드펜스 없이 [\"sentence\", ...] 형식만."
    }
}
