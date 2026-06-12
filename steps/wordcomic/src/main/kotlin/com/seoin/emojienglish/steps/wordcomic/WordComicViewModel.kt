package com.seoin.emojienglish.steps.wordcomic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seoin.emojienglish.data.ComicScriptHolder
import com.seoin.emojienglish.model.ComicBubble
import com.seoin.emojienglish.model.ComicScene
import com.seoin.emojienglish.model.ComicScript
import com.seoin.emojienglish.model.ComicSprite
import com.seoin.emojienglish.voice.VoiceGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Holds the LLM-generated [ComicScript] state for the word_comic step (Phase 3).
 *
 * Scoped to the Activity so the generated script survives step navigation and is
 * shared between the student screen (renders it) and any master tools that may
 * also trigger generation. [ComicScriptHolder] persists the script across
 * ViewModel recreation until the app is killed.
 */
@HiltViewModel
class WordComicViewModel @Inject constructor(
    private val gateway: VoiceGateway,
    private val holder: ComicScriptHolder,
) : ViewModel() {

    val script: StateFlow<ComicScript?> = holder.script

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun generate(keywords: String) {
        if (_generating.value) return
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            val response = gateway.textQuery(buildPrompt(keywords.trim()))
            if (response != null) {
                val parsed = parseResponse(response)
                if (parsed != null) {
                    holder.script.value = parsed
                } else {
                    _error.value = "만화 JSON 파싱 실패 — 다시 시도해보세요"
                }
            } else {
                _error.value = "ChatGPT 응답 없음 — 음성 패널이 연결됐는지 확인하세요"
            }
            _generating.value = false
        }
    }

    fun clearError() { _error.value = null }

    // ---------------------------------------------------------------- prompt

    private fun buildPrompt(keywords: String): String = """
        다음 키워드로 영어 단어 학습용 4컷 만화 JSON을 만들어주세요.
        키워드: $keywords

        규칙:
        - panels 배열에 정확히 4개
        - bg는 반드시: swamp, pond, forest, sky, snow, lava, night, sunset 중 하나
        - x, y는 15~85 사이 실수 (스프라이트 위치 %)
        - scale은 0.6~1.4 사이 실수
        - anim은 bounce, float, jump 중 하나 또는 null
        - 각 칸에 2~3개 이모지 스프라이트
        - highlight는 해당 칸에서 가르칠 영어 단어
        - caption은 "영어(한국어)" 형식으로
        - bubble.text는 캐릭터 대사(짧게)
        - 4칸이 하나의 짧은 이야기를 이루도록
        - JSON만 출력 (설명이나 코드블록 없이)

        출력 형식:
        {"panels":[{"bg":"sky","caption":"caption text","highlight":"word","sprites":[{"char":"🐱","x":50.0,"y":70.0,"scale":1.0,"anim":null}],"bubble":{"anchor":0,"text":"대사"}}]}
    """.trimIndent()

    // ---------------------------------------------------------------- parser

    private fun parseResponse(response: String): ComicScript? = runCatching {
        val jsonStr = extractJson(response) ?: return@runCatching null
        val root = JSONObject(jsonStr)
        val panelsArr: JSONArray = root.optJSONArray("panels") ?: return@runCatching null
        val panels = (0 until panelsArr.length()).map { i ->
            val p = panelsArr.getJSONObject(i)
            val spritesArr: JSONArray = p.optJSONArray("sprites") ?: JSONArray()
            val sprites = (0 until spritesArr.length()).map { j ->
                val s = spritesArr.getJSONObject(j)
                ComicSprite(
                    char = s.optString("char").takeIf { it.isNotBlank() },
                    x = s.optDouble("x", 50.0).toFloat(),
                    y = s.optDouble("y", 70.0).toFloat(),
                    scale = s.optDouble("scale", 1.0).toFloat().coerceIn(0.4f, 1.6f),
                    anim = s.optString("anim").takeIf { it.isNotBlank() && it != "null" },
                )
            }
            val bubbleObj: JSONObject? = p.optJSONObject("bubble")
            ComicScene(
                bg = p.optString("bg", "sky"),
                caption = p.optString("caption"),
                highlight = p.optString("highlight").takeIf { it.isNotBlank() },
                sprites = sprites,
                bubble = bubbleObj?.let {
                    ComicBubble(anchor = it.optInt("anchor", 0), text = it.optString("text"))
                },
            )
        }
        ComicScript(panels = panels)
    }.getOrNull()

    /** Extract the outermost {...} from potentially-noisy LLM output. */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }
}
