package com.seoin.emojienglish.steps.storycomic

import com.seoin.emojienglish.step.StepSpec
import com.seoin.emojienglish.step.StepSpecParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/**
 * story_comic (전체만화) data model — ported from the proven V4 schema of the
 * previous app (`JSON_RULES_TABLE_V4_FULL_BODY_COMIC_GAME.md` + DataComicPanelView).
 *
 * The whole story comic lives inside this step's `params`, so the step is fully
 * self-contained (§0.2 — no shared-model change, no other module touched).
 *
 * Design intent carried over (전작의 고민):
 *  - one BIG panel at a time, captions read aloud — listening + reading together
 *  - today's words highlighted inside captions and tappable for instant meaning
 *  - rich but SAFE direction vocabulary: fixed lists for bg/anim/fx/zoom/mood,
 *    everything else clamped, so LLM- or hand-written JSON can never break the
 *    renderer (안정성 규칙).
 */
internal data class StoryComicData(
    val title: String,
    val meaning: String,
    /** Today's words (chips + caption highlight). */
    val words: List<String>,
    /** word(lowercase) → kid-friendly English explanation, read by TTS on tap. */
    val wordExplanations: Map<String, String>,
    /**
     * word(lowercase) → GPT voice discussion brief. Sent as a QUIZ_VOCAB payload:
     * the tutor explains briefly, asks ONE question, listens and judges — the
     * "설명 듣고 얘기 나눠보기" loop. Optional per word.
     */
    val voiceTalks: Map<String, String>,
    val panels: List<StoryPanel>,
)

internal data class StoryPanel(
    val bg: String,
    /** Emotional color grade overlay: "" | red | dusk | warm. */
    val mood: String,
    /** Climax panel gets the double gold border (전작: 하이라이트 컷 강조). */
    val climax: Boolean,
    /** Panel-wide effect: "" | focus | speed | shake. */
    val fx: String,
    /** Camera move: pushin | pan | kenburns | shakezoom | static scale. */
    val zoom: StoryZoom?,
    /** Onomatopoeia text ("Yum!", "Sizzle!") drawn with outline + rotation. */
    val sfx: List<StorySfx>,
    val caption: String,
    val sprites: List<StorySprite>,
    val bubble: StoryBubble?,
)

internal data class StoryZoom(val type: String, val scale: Float, val originX: Float, val originY: Float)

internal data class StorySfx(
    val text: String,
    val x: Float,
    val y: Float,
    /** Text size as % of panel width (clamped 5–18, 전작 동일). */
    val size: Float,
    val rotate: Float,
    val color: Int,
)

internal data class StorySprite(
    val char: String,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotate: Float,
    val flip: Boolean,
    val anim: String,
)

/** anchor = sprite index; explicit x/y (>= 0) overrides the anchor position. */
internal data class StoryBubble(val anchor: Int, val x: Float, val y: Float, val text: String)

/** Parsed spec. Not a data class so the internal payload stays module-private. */
class StoryComicSpec internal constructor(
    override val stepId: String,
    internal val data: StoryComicData,
) : StepSpec

// ---------------------------------------------------------------------- parse

internal val storyBgNames = setOf(
    "snow", "sky", "forest", "desert", "lava", "ocean", "night", "room", "sunset", "plain",
)
internal val storyAnimNames = setOf(
    "none", "bounce", "shiver", "float", "dash", "roll", "jump", "sway", "spin",
)

internal fun parseStoryComic(params: JsonObject): StoryComicData {
    val panelsJson = params["panels"] as? JsonArray
        ?: throw StepSpecParseException("story_comic: params.panels 가 없습니다")
    val panels = panelsJson.mapNotNull { it as? JsonObject }.map { it.toStoryPanel() }
    if (panels.isEmpty()) throw StepSpecParseException("story_comic: panels 가 비었습니다")

    fun stringMap(key: String): Map<String, String> =
        (params[key] as? JsonObject)?.entries
            ?.mapNotNull { (k, v) ->
                val text = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                k.lowercase().trim() to text
            }?.toMap() ?: emptyMap()

    return StoryComicData(
        title = params.str("title") ?: "",
        meaning = params.str("meaning") ?: "",
        words = (params["words"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
        wordExplanations = stringMap("wordExplanations"),
        voiceTalks = stringMap("voiceTalks"),
        panels = panels,
    )
}

private fun JsonObject.toStoryPanel(): StoryPanel {
    val sprites = (this["sprites"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { s ->
        StorySprite(
            char = s.str("char") ?: "⬜",
            x = s.num("x", 50f),
            y = s.num("y", 60f),
            scale = s.num("scale", 1f),
            rotate = s.num("rotate", 0f),
            flip = s.bool("flip"),
            anim = (s.str("anim") ?: "none").takeIf { it in storyAnimNames } ?: "none",
        )
    } ?: emptyList()

    val bubble = (this["bubble"] as? JsonObject)?.let { b ->
        StoryBubble(
            anchor = b.int("anchor", 0),
            x = b.num("x", -1f),
            y = b.num("y", -1f),
            text = b.str("text") ?: "",
        )
    }?.takeIf { it.text.isNotBlank() }

    val sfx = (this["sfx"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { s ->
        val text = s.str("text") ?: return@mapNotNull null
        if (text.isBlank()) return@mapNotNull null
        StorySfx(
            text = text,
            x = s.num("x", 50f),
            y = s.num("y", 50f),
            size = s.num("size", 9f),
            rotate = s.num("rotate", -8f),
            color = parseHexColor(s.str("color"), default = 0xFFFDE047.toInt()),
        )
    } ?: emptyList()

    val zoom = (this["zoom"] as? JsonObject)?.let { z ->
        StoryZoom(
            type = z.str("type") ?: "",
            scale = z.num("scale", 1.35f),
            originX = z.num("originX", 50f),
            originY = z.num("originY", 55f),
        )
    }

    return StoryPanel(
        bg = (str("bg") ?: "plain").takeIf { it in storyBgNames } ?: "plain",
        mood = str("mood") ?: "",
        climax = bool("climax"),
        fx = str("fx") ?: "",
        zoom = zoom,
        sfx = sfx,
        caption = str("caption") ?: "",
        sprites = sprites,
        bubble = bubble,
    )
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(key: String, default: Float): Float =
    (this[key] as? JsonPrimitive)?.floatOrNull ?: default
private fun JsonObject.int(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default
private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

internal fun parseHexColor(value: String?, default: Int): Int {
    val hex = value?.removePrefix("#") ?: return default
    return runCatching {
        when (hex.length) {
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> default
        }
    }.getOrDefault(default)
}
