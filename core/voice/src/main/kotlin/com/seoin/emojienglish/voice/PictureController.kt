package com.seoin.emojienglish.voice

import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 그림창 — 음성 WebView와 **별도의** 2번째 ChatGPT WebView를 띄워, 학생이 보고 있는
 * 문장/컷 안의 단어 그림을 찾아(없으면 그려서) 보여준다. 안드로이드 WebView는 쿠키를
 * `CookieManager`로 공유하므로 로그인은 음성 WebView와 함께 유지된다.
 *
 * 계약 동결(`StepSession` 수정 금지)을 지키기 위해, **스텝이 이 싱글톤에 직접** 단어를
 * 추가한다([addWord]). 스텝의 "단어 길게 누르기 → 그림" 메뉴가 호출하며, 어떤 단어를
 * 띄울지는 각 스텝이 정의한다(스텝별 "그림 연결 동작").
 */
interface PictureController {
    val state: StateFlow<PictureState>

    /**
     * 단어를 그림창 칩으로 **추가**하고 패널을 연다(없으면 중복 추가 안 함).
     * 스텝의 "단어 길게 누르기 → 그림" 메뉴가 이걸 호출한다. **요청은 아직 안 보낸다** —
     * 칩이 생기기만 하고, 그 칩을 눌러야([requestPicture]) 실제 그림을 요청한다(이 외에도
     * 칩에서 다른 요청을 붙일 수 있게).
     */
    fun addWord(word: PictureWord)

    fun open()
    fun close()
    fun toggle()

    /** 한 단어의 그림을 ChatGPT에 요청(찾기 → 없으면 그리기). 칩 탭 시 호출. */
    fun requestPicture(word: PictureWord)

    /** 패널이 host 할 WebView (없으면 null). */
    fun provideView(): WebView?
}

/** 그림 칩 하나 — [label]은 버튼에 표시, [prompt]는 웹뷰에 주입할 요청문. */
data class PictureWord(
    val id: String,
    val label: String,
    val prompt: String,
)

/** 그림창이 그리는 스냅샷. [visible]=패널 열림, [words]=현재 포커스의 칩들. */
data class PictureState(
    val visible: Boolean = false,
    val words: List<PictureWord> = emptyList(),
)

/** 프리뷰/테스트용 no-op — 스텝을 앱 없이 단독 프리뷰할 때 주입한다. */
object NoopPictureController : PictureController {
    private val _state = MutableStateFlow(PictureState())
    override val state: StateFlow<PictureState> = _state.asStateFlow()
    override fun addWord(word: PictureWord) {}
    override fun open() {}
    override fun close() {}
    override fun toggle() {}
    override fun requestPicture(word: PictureWord) {}
    override fun provideView(): WebView? = null
}
