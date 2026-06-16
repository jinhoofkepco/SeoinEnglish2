package com.seoin.emojienglish.master

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seoin.emojienglish.content.ContentWriter
import com.seoin.emojienglish.content.LessonRepository
import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.LessonUnit
import com.seoin.emojienglish.model.StepDef
import com.seoin.emojienglish.voice.AuthoringWebGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val writer: ContentWriter,
    private val lessonRepo: LessonRepository,
) : ViewModel() {

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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var passagePhotoUris: List<Uri> = emptyList()
    private var wordPhotoUris: List<Uri> = emptyList()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private data class PageSentences(
        val pageNumber: Int,
        val sentences: List<String>,
    )

    fun provideView(): WebView = gateway.provideView()

    /** 렌더러가 죽어 웹뷰가 새로 만들어진 횟수 — 화면이 AndroidView를 다시 붙이는 key. */
    val webReloads: StateFlow<Int> = gateway.reloads

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
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID를 입력하세요.") }; return }
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
                passageParams = generatePassageParams(sentences, s)
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
                val side = gridSide(words.size)
                prepareFreshChat("auto-grid-image")
                val beforeImages = gateway.largeAssistantImageCount()
                if (!gateway.sendOnly(gridPrompt(words, side))) {
                    error("그리드 그림 요청 전송 실패(로그인/작성창 확인).")
                }
                val bytes = awaitGridImageBytes(beforeImages + 1)
                writer.writeImage(s.bookId, s.unitId, GRID_FILE, bytes)
                lessonRepo.refresh()
                _state.update {
                    it.copy(status = "전체 자동 완료: ${unit.steps.size}개 스텝 + grid.png 저장됨(${bytes.size}B). 책에서 바로 확인하세요.")
                }
            }
        }
    }

    /** 사진/텍스트에서 문장을 뽑아 passage_read 단원만 저장. */
    fun runPassageAuto() {
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID를 입력하세요.") }; return }
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

            val params = generatePassageParams(sentences, s)
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
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID를 입력하세요.") }; return }
        if (s.words.isEmpty()) { _state.update { it.copy(error = "먼저 단어를 추출하세요(소스/사진).") }; return }
        runStep("② 단원 JSON 생성 요청 → 청크 수신 준비… (${s.words.size}개 단어)") {
            val params = generateStoryParams(s.words, s)
            val unit = saveStoryUnit(params, s.words, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "저장됨 → 앱에 바로 보입니다. 이제 ‘③ 그림(그리드)’.",
                )
            }
        }
    }

    /** 현재 문장들로 passage_read 단원 JSON 생성 → 저장. */
    fun buildPassageAndSave() {
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID를 입력하세요.") }; return }
        if (s.sentences.isEmpty()) { _state.update { it.copy(error = "먼저 문장을 추출하세요(사진/텍스트).") }; return }
        runStep("② 지문 JSON 생성 요청 → 청크 수신 준비… (${s.sentences.size}개 문장)") {
            val params = generatePassageParams(s.sentences, s)
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

    /**
     * ②(실험) **청크 수신 방식** — GPT 응답에서 JSON만 찾아 JS 브릿지로 작은 조각씩 받는다.
     * 큰 응답 문자열을 evaluateJavascript 결과로 직접 받지 않아 WebView 다운 위험을 줄인다.
     */
    fun buildAndSaveViaLink() {
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID를 입력하세요.") }; return }
        if (s.words.isEmpty()) { _state.update { it.copy(error = "먼저 단어를 추출하세요.") }; return }
        runStep("②(청크) JSON 생성 요청…") {
            val params = generateStoryParams(s.words, s)
            val unit = saveStoryUnit(params, s.words, s)
            _state.update {
                it.copy(
                    savedUnit = true,
                    unitJsonPreview = json.encodeToString(LessonUnit.serializer(), unit),
                    status = "청크 방식으로 저장됨 → 앱에 바로 보입니다.",
                )
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
        val s = _state.value
        if (s.running || s.words.isEmpty()) return
        val side = gridSide(s.words.size)
        runStep("③ 그리드 그림 생성 요청 중… (${side}×$side, grid.png)") {
            prepareFreshChat("manual-grid-image")
            if (!gateway.sendOnly(gridPrompt(s.words, side))) {
                error(gateway.lastQueryFailureMessage("그리드 그림 요청"))
            }
            _state.update {
                it.copy(
                    status = "그리드 그림 요청 완료. 생성된 이미지를 grid.png 로 받아 " +
                        "images/${s.unitId}/grid.png 에 두면 칸별로 바로 표시됩니다.",
                )
            }
        }
    }

    /** 생성된 그리드 그림을 **앱이 직접 가져와** grid.png 로 저장(수동 다운로드 대체). */
    fun captureGridImage() {
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID가 필요합니다.") }; return }
        runStep("그림 가져오는 중…") {
            val bytes = decodeImageDataUrl(
                gateway.captureLastImageDataUrl()
                    ?: error("그림을 못 가져옴(생성된 이미지가 없거나 읽기 실패). 그림이 화면에 보이는지 확인."),
            )
            writer.writeImage(s.bookId, s.unitId, GRID_FILE, bytes)
            lessonRepo.refresh()
            _state.update { it.copy(status = "grid.png 저장됨(${bytes.size}B) → 칸별로 바로 표시됩니다.") }
        }
    }

    /** ChatGPT에서 **수동 다운로드한 .json 파일을 가져와** 단원으로 저장(가장 견고한 경로). */
    fun importJson(uri: Uri) {
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID를 입력하세요.") }; return }
        runStep("JSON 파일 가져오는 중…") {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            }
            val jsonText = extractJson(text) ?: text
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
        val s = _state.value
        if (s.running) return
        if (s.unitId.isBlank()) { _state.update { it.copy(error = "단원 ID를 입력하세요.") }; return }
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

    private fun runStep(startStatus: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, status = startStatus) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "오류") }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    // ----------------------------------------------------------- GPT 단계

    private suspend fun extractWords(source: String): List<String> {
        prepareFreshChat("source-word-extract")
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
            val resp = gateway.query(photoWordBatchPrompt(start, end, batch.size))
                ?: error(gateway.lastQueryFailureMessage("${start}~${end}쪽 단어 분석"))
            words += parseWordArray(resp)
        }
        return words.toList()
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
            val resp = gateway.query(photoSentenceBatchPrompt(start, end, batch.size))
                ?: error(gateway.lastQueryFailureMessage("${start}~${end}쪽 문장 추출"))
            pages += parsePageSentenceGroups(resp, start, batch.size)
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

    private fun parseSentenceArray(resp: String): List<String> {
        val arr = extractJson(resp) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(arr).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
        }.getOrDefault(emptyList())
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun parsePageSentenceGroups(resp: String, startPage: Int, expectedCount: Int): List<PageSentences> {
        val jsonText = extractJson(resp) ?: return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(jsonText)
            val pageElements = when (root) {
                is JsonObject -> root["pages"]?.jsonArray ?: JsonArray(emptyList())
                is JsonArray -> root
                else -> JsonArray(emptyList())
            }
            pageElements.mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val pageNumber = obj["pageNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: obj["page"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: (startPage + index)
                val sentences = obj["sentences"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    ?.map { it.replace(Regex("\\s+"), " ").trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                PageSentences(pageNumber, sentences)
            }
        }.getOrDefault(emptyList())
            .ifEmpty {
                val flat = parseSentenceArray(resp)
                if (flat.isEmpty()) emptyList() else listOf(PageSentences(startPage, flat))
            }
            .filter { it.pageNumber in startPage until startPage + expectedCount || it.sentences.isNotEmpty() }
    }

    private suspend fun reorderSentencesWithGpt(pages: List<PageSentences>): List<String> {
        prepareFreshChat("page-order")
        val resp = gateway.query(pageOrderPrompt(pages)).orEmpty()
        return parseSentenceArray(resp)
    }

    private suspend fun generateStoryParams(words: List<String>, s: State): JsonObject {
        prepareFreshChat("story-json")
        val before = gateway.assistantMessageCount()
        if (!gateway.sendOnly(storyPrompt(words))) {
            throw IllegalStateException("JSON 요청 전송 실패(로그인/작성창 확인).")
        }
        val jsonText = awaitStoryJsonChunk(before)
        return enrichStoryParams(jsonText, words, s)
    }

    private suspend fun generatePassageParams(sentences: List<String>, s: State): JsonObject {
        prepareFreshChat("passage-json")
        val before = gateway.assistantMessageCount()
        if (!gateway.sendOnly(passagePrompt(sentences, s))) {
            throw IllegalStateException("지문 JSON 요청 전송 실패(로그인/작성창 확인).")
        }
        val jsonText = awaitPassageJsonChunk(before)
        return runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { throw IllegalStateException("지문 JSON 파싱 실패: ${it.message}") }
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

    private suspend fun awaitGridImageBytes(minImageCount: Int): ByteArray {
        for (i in 1..60) {
            delay(if (i == 1) 6000 else 3000)
            _state.update { it.copy(status = "자동 ③ 그리드 그림 확인 중… $i/60") }
            val dataUrl = gateway.captureLastImageDataUrl(minImageCount) ?: continue
            return decodeImageDataUrl(dataUrl)
        }
        throw IllegalStateException("그리드 그림을 자동으로 못 가져옴. 화면에 그림이 보이면 ‘저장’ 버튼으로 다시 시도하세요.")
    }

    private fun decodeImageDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) throw IllegalStateException("이미지 데이터가 비었습니다.")
        return bytes
    }

    private suspend fun awaitStoryJsonChunk(beforeAssistantCount: Int): String {
        val requiredCount = beforeAssistantCount + 1
        for (i in 1..45) {
            delay(if (i == 1) 2500 else 1800)
            _state.update { it.copy(status = "② JSON 청크 확인 중… $i/45") }
            val captured = gateway.captureLastAssistantJsonChunked(requiredCount) ?: continue
            val jsonText = extractJson(captured) ?: captured.trim()
            if (looksLikeStoryParams(jsonText)) return jsonText
        }
        throw IllegalStateException("JSON 청크를 못 받음(응답 생성이 끝났는지, 화면에 JSON이 보이는지 확인).")
    }

    private suspend fun awaitPassageJsonChunk(beforeAssistantCount: Int): String {
        val requiredCount = beforeAssistantCount + 1
        for (i in 1..45) {
            delay(if (i == 1) 2500 else 1800)
            _state.update { it.copy(status = "② 지문 JSON 청크 확인 중… $i/45") }
            val captured = gateway.captureLastAssistantJsonChunked(requiredCount) ?: continue
            val jsonText = extractJson(captured) ?: captured.trim()
            if (looksLikePassageParams(jsonText)) return jsonText
        }
        throw IllegalStateException("지문 JSON 청크를 못 받음(응답 생성이 끝났는지, 화면에 JSON이 보이는지 확인).")
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

    private fun gridSide(n: Int): Int {
        var side = 4
        while (side * side < n) side++   // 16칸이면 4×4, 더 많으면 키운다
        return side
    }

    private fun uploadDelayMs(count: Int): Long =
        (2500L + count.coerceAtLeast(1) * 1200L).coerceAtMost(9000L)

    private suspend fun prepareFreshChat(reason: String) {
        gateway.recycleForAuthoringBatch(reason)
        gateway.newChat()
    }

    private fun ensurePhotoAttachSucceeded(result: String) {
        if (result.startsWith("ok ")) return
        error("사진 첨부 실패($result). 다시 시도하세요.")
    }

    // ----------------------------------------------------------- helpers

    /** Strip code fences and pull the first balanced JSON object/array. */
    private fun extractJson(text: String): String? {
        val t = text.replace("```json", "```").let {
            val fenced = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)
            (fenced ?: it).trim()
        }
        val startObj = t.indexOf('{')
        val startArr = t.indexOf('[')
        val start = listOf(startObj, startArr).filter { it >= 0 }.minOrNull() ?: return null
        val open = t[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        for (i in start until t.length) {
            when (t[i]) {
                open -> depth++
                close -> { depth--; if (depth == 0) return t.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun slugId(v: String): String =
        v.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun mergePhotoUris(current: List<Uri>, added: List<Uri>): List<Uri> =
        (current + added).distinctBy { it.toString() }

    private companion object {
        const val BG_LIST = "(snow/sky/forest/desert/lava/ocean/night/room/sunset/plain)"
        const val ANIM_LIST = "(none/bounce/shiver/float/dash/roll/jump/sway/spin)"
        const val GRID_FILE = "grid.png"
        const val PHOTO_BATCH_SIZE = 1
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
