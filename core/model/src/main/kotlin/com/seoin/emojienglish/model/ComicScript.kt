package com.seoin.emojienglish.model

import kotlinx.serialization.Serializable

/**
 * Declarative 4-panel comic (공유 설계: "프롬프트 JSON으로 4컷 만화").
 *
 * The whole comic is data: a fixed renderer assembles primitives (background,
 * emoji/image sprites, caption, speech bubble, highlighted word) from this JSON.
 * Phase 1 = static script + renderer. Phase 2 = animations. Phase 3 = an LLM
 * produces this JSON from keywords. The schema deliberately accepts both [ComicSprite.char]
 * (emoji) and [ComicSprite.src] (image path) so we can swap art later without
 * touching the renderer.
 */
@Serializable
data class ComicScript(
    val panels: List<ComicScene> = emptyList(),
)

@Serializable
data class ComicScene(
    /** Background preset name (fixed list → gradient), e.g. "swamp", "sky". */
    val bg: String = "sky",
    val caption: String = "",
    val sprites: List<ComicSprite> = emptyList(),
    val bubble: ComicBubble? = null,
    /** A word to emphasize in the caption (the panel's target vocabulary). */
    val highlight: String? = null,
)

@Serializable
data class ComicSprite(
    /** Emoji glyph. Use this OR [src]. */
    val char: String? = null,
    /** Image path/URL (future art swap). Use this OR [char]. */
    val src: String? = null,
    /** Position as a percentage of the panel (0..100). Clamped on render. */
    val x: Float = 50f,
    val y: Float = 70f,
    /** Size multiplier (renderer caps it so nothing overflows). */
    val scale: Float = 1f,
    /** Animation name from a fixed list (Phase 2); ignored for now. */
    val anim: String? = null,
)

@Serializable
data class ComicBubble(
    /** Index into the scene's [ComicScene.sprites] the bubble points at. */
    val anchor: Int = 0,
    val text: String = "",
)
