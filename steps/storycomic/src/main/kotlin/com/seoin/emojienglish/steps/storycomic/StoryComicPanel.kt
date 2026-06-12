package com.seoin.emojienglish.steps.storycomic

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Story-comic panel renderer — a Compose port of the previous app's
 * `DataComicPanelView`, keeping every direction technique that made the comic
 * feel alive for kids (전작의 연출 노하우 보존):
 *
 *  - bg gradient presets + **halftone dots** (만화 인쇄 질감)
 *  - **mood overlay** (red/dusk/warm) — emotional color grading per panel
 *  - **fx**: focus lines(집중선) / speed lines(속도선) / shake(흔들림)
 *  - **camera**: pushin / pan / kenburns / shakezoom — a still panel keeps moving
 *    slightly, which holds a child's attention without being busy
 *  - **sprites**: emoji with rotate/flip/scale + 9 looping anims
 *  - **sfx**: onomatopoeia text with outline + tilt ("Yum!", "Sizzle!")
 *  - **bubble**: white speech bubble anchored to a sprite (or explicit x/y)
 *  - **climax**: double gold border for the key panel of the story
 *  - night bg flips text to light colors
 *
 * All timings/thresholds match the original (camera 9s, sprites 2.4s, shake 180ms).
 */
@Composable
internal fun StoryComicPanelView(
    panel: StoryPanel,
    modifier: Modifier = Modifier,
    /** false = still thumbnail (찾기 퀴즈) — no frame loop, camera/anims at t=0. */
    animate: Boolean = true,
) {
    val animating = animate && (
        panel.zoom != null || panel.fx == "shake" ||
            panel.sprites.any { it.anim != "none" }
        )

    var timeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(panel, animating) {
        if (!animating) return@LaunchedEffect
        while (true) withFrameMillis { timeMs = it }
    }

    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val bubblePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Canvas(modifier) {
        val now = timeMs
        val corner = 16.dp.toPx()
        val outline = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner, corner))
        }

        clipPath(outline) {
            withCamera(panel, now) {
                drawPanelBackground(panel.bg)
                drawHalftone()
                drawMoodOverlay(panel.mood)
                if (panel.fx == "focus") drawFocusLines()
                if (panel.fx == "speed") drawSpeedLines()
                panel.sprites.forEachIndexed { i, sprite ->
                    drawSprite(sprite, i, panel.bg == "night", now, textPaint)
                }
                panel.sfx.forEach { drawSfx(it, textPaint) }
                panel.bubble?.let { drawBubble(it, panel.sprites, bubblePaint) }
            }
        }

        // Borders are drawn OUTSIDE the camera transform so they never wobble.
        if (panel.climax) {
            drawPath(
                Path().apply {
                    addRoundRect(
                        RoundRect(2.dp.toPx(), 2.dp.toPx(), size.width - 2.dp.toPx(), size.height - 2.dp.toPx(), 18.dp.toPx(), 18.dp.toPx()),
                    )
                },
                color = Color(0xFFFBBF24),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
        drawPath(
            outline,
            color = if (panel.climax) Color(0xFF292524) else Color(0xFF1C1917),
            style = Stroke(width = if (panel.climax) 5.dp.toPx() else 3.dp.toPx()),
        )
    }
}

// ------------------------------------------------------------------- camera

/** 전작 applyPanelCamera: shake + 4 zoom moves, same waves and clamps. */
private inline fun DrawScope.withCamera(panel: StoryPanel, now: Long, draw: DrawScope.() -> Unit) {
    val phase9s = (now % 9000L).toFloat() / 9000f
    val wave = sin(phase9s * Math.PI * 2.0).toFloat()

    drawContext.canvas.save()

    if (panel.fx == "shake") {
        val shakePhase = (now % 180L).toFloat() / 180f
        drawContext.canvas.translate(
            sin(shakePhase * Math.PI * 2.0).toFloat() * 8.dp.toPx() * 0.2f,
            sin(shakePhase * Math.PI * 2.0 + 1.7).toFloat() * 5.dp.toPx() * 0.2f,
        )
    }

    val zoom = panel.zoom
    if (zoom != null) {
        val px = size.width * (zoom.originX.coerceIn(0f, 100f) / 100f)
        val py = size.height * (zoom.originY.coerceIn(0f, 100f) / 100f)
        val canvas = drawContext.canvas
        when (zoom.type) {
            "pushin" -> {
                val p = (now % 5000L).toFloat() / 5000f
                val s = 1f + (zoom.scale.coerceIn(1f, 1.8f) - 1f) * p
                canvas.translate(px, py); canvas.scale(s, s); canvas.translate(-px, -py)
            }
            "pan" -> {
                canvas.translate(px, py); canvas.scale(1.3f, 1.3f); canvas.translate(-px, -py)
                canvas.translate(wave * size.width * 0.06f, 0f)
            }
            "kenburns" -> {
                val p = (wave + 1f) / 2f
                val s = 1.15f + 0.25f * p
                canvas.translate(px, py); canvas.scale(s, s); canvas.translate(-px, -py)
                canvas.translate((0.03f - 0.06f * p) * size.width, (0.02f - 0.05f * p) * size.height)
            }
            "shakezoom" -> {
                val shakePhase = (now % 180L).toFloat() / 180f
                val fastWave = sin(shakePhase * Math.PI * 2.0).toFloat()
                val fastWaveY = sin(shakePhase * Math.PI * 2.0 + 1.4).toFloat()
                val s = 1.29f + 0.009f * fastWave
                canvas.translate(px, py); canvas.scale(s, s); canvas.translate(-px, -py)
                canvas.translate(fastWave * size.width * 0.0048f, fastWaveY * size.height * 0.0036f)
            }
            else -> {
                val s = zoom.scale.coerceIn(1f, 1.8f)
                canvas.translate(px, py); canvas.scale(s, s); canvas.translate(-px, -py)
            }
        }
    }

    draw()
    drawContext.canvas.restore()
}

// --------------------------------------------------------------- background

/** Fixed bg gradients — exact colors of the previous renderer. */
private fun DrawScope.drawPanelBackground(bg: String) {
    val (top, bottom) = when (bg) {
        "snow" -> 0xFFDBEAFE to 0xFFEFF6FF
        "sky" -> 0xFFBAE6FD to 0xFFF0F9FF
        "forest" -> 0xFFBBF7D0 to 0xFFF0FDF4
        "desert" -> 0xFFFDE68A to 0xFFFFFBEA
        "lava" -> 0xFFFECACA to 0xFFFFF7ED
        "ocean" -> 0xFF7DD3FC to 0xFFE0F2FE
        "night" -> 0xFF1E293B to 0xFF475569
        "room" -> 0xFFFEF3C7 to 0xFFFFFBEA
        "sunset" -> 0xFFFDBA74 to 0xFFFFF1E6
        else -> 0xFFFFF7ED to 0xFFFFF7ED
    }
    // Oversize the fill so camera moves (pan/kenburns) never reveal an edge.
    val pad = size.maxDimension * 0.4f
    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, -pad, 0f, size.height + pad,
                top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP,
            )
        }
        drawRect(-pad, -pad, size.width + pad, size.height + pad, paint)
    }
}

/** 만화 인쇄 질감 — subtle halftone dot grid (전작 drawHalftone). */
private fun DrawScope.drawHalftone() {
    val step = 10.dp.toPx()
    val r = 1.dp.toPx()
    val color = Color.Black.copy(alpha = 14f / 255f)
    var y = 6.dp.toPx()
    while (y < size.height) {
        var x = 6.dp.toPx()
        while (x < size.width) {
            drawCircle(color, r, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** Per-panel emotional color grade (전작 drawMoodGrade). */
private fun DrawScope.drawMoodOverlay(mood: String) {
    val color = when (mood) {
        "red" -> Color(0xFFB91C1C).copy(alpha = 56f / 255f)
        "dusk" -> Color(0xFFF59E0B).copy(alpha = 34f / 255f)
        "warm" -> Color(0xFFFDE68A).copy(alpha = 18f / 255f)
        else -> return
    }
    val pad = size.maxDimension * 0.4f
    drawRect(color, Offset(-pad, -pad), Size(size.width + pad * 2, size.height + pad * 2))
}

// ----------------------------------------------------------------------- fx

/** 집중선 — 24 radial lines from the center (climax/긴장 연출). */
private fun DrawScope.drawFocusLines() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = max(size.width, size.height)
    for (i in 0 until 24) {
        val angle = (i / 24.0) * Math.PI * 2.0
        drawLine(
            color = Color(0xFFF97316).copy(alpha = 160f / 255f),
            start = Offset(cx, cy),
            end = Offset(cx + cos(angle).toFloat() * radius, cy + sin(angle).toFloat() * radius),
            strokeWidth = (if (i % 2 == 0) 3 else 2).dp.toPx(),
        )
    }
}

/** 속도선 — horizontal motion streaks (전작 drawSpeedLines). */
private fun DrawScope.drawSpeedLines() {
    listOf(18f, 32f, 46f, 60f, 74f).forEachIndexed { index, yPercent ->
        val y = size.height * yPercent / 100f
        drawLine(
            color = Color.White.copy(alpha = 178f / 255f),
            start = Offset(0f, y),
            end = Offset(size.width * (0.3f + index * 0.06f), y),
            strokeWidth = 3.dp.toPx(),
        )
    }
}

// ------------------------------------------------------------------ sprites

/**
 * Emoji sprite with looping motion. Per-sprite phase offset (`index * 0.17`)
 * keeps multiple sprites from moving in lockstep — 전작의 생동감 비결.
 */
private fun DrawScope.drawSprite(
    sprite: StorySprite,
    index: Int,
    nightBg: Boolean,
    now: Long,
    paint: Paint,
) {
    val elapsed = (now % 2400L).toFloat() / 2400f
    val phase = (elapsed + index * 0.17f) % 1f
    val pulse = sin(phase * Math.PI * 2.0).toFloat()
    val scale = sprite.scale.coerceIn(0.5f, 1.6f)
    var x = size.width * (sprite.x.coerceIn(13f, 87f) / 100f)
    var y = size.height * (sprite.y.coerceIn(18f, 82f) / 100f)
    var rotation = sprite.rotate
    var extraScale = 1f

    when (sprite.anim) {
        "bounce" -> y -= 12.dp.toPx() * ((pulse + 1f) / 2f)
        "float" -> y -= 8.dp.toPx() * ((pulse + 1f) / 2f)
        "shiver" -> { x += pulse * 3.dp.toPx(); rotation += pulse * 5f }
        "dash" -> x += pulse * 12.dp.toPx()
        "roll", "spin" -> rotation += phase * 360f
        "jump" -> {
            y -= max(0f, pulse) * 20.dp.toPx()
            extraScale = 1f + max(0f, pulse) * 0.08f
        }
        "sway" -> rotation += pulse * 7f
    }

    paint.textSize = size.width * 0.15f * scale
    paint.color = if (nightBg) android.graphics.Color.WHITE else 0xFF1C1917.toInt()
    paint.style = Paint.Style.FILL
    val label = sprite.char.ifBlank { "?" }
    val fm = paint.fontMetrics

    drawContext.canvas.nativeCanvas.apply {
        save()
        translate(x, y)
        rotate(rotation)
        scale(if (sprite.flip) -extraScale else extraScale, extraScale)
        drawText(label, 0f, -(fm.ascent + fm.descent) / 2f, paint)
        restore()
    }
}

// --------------------------------------------------------------------- sfx

/** Onomatopoeia text — dark outline + colored fill + tilt (만화 효과음). */
private fun DrawScope.drawSfx(sfx: StorySfx, paint: Paint) {
    val x = size.width * (sfx.x.coerceIn(0f, 100f) / 100f)
    val y = size.height * (sfx.y.coerceIn(0f, 100f) / 100f)
    paint.textSize = size.width * (sfx.size.coerceIn(5f, 18f) / 100f)
    paint.textAlign = Paint.Align.CENTER
    val fm = paint.fontMetrics
    val baseline = -(fm.ascent + fm.descent) / 2f

    drawContext.canvas.nativeCanvas.apply {
        save()
        translate(x, y)
        rotate(sfx.rotate)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.dp.toPx()
        paint.color = 0xFF292524.toInt()
        drawText(sfx.text, 0f, baseline, paint)
        paint.style = Paint.Style.FILL
        paint.color = sfx.color
        drawText(sfx.text, 0f, baseline, paint)
        restore()
    }
    paint.style = Paint.Style.FILL
}

// ------------------------------------------------------------------- bubble

/**
 * Speech bubble — white rounded box with dark border, max 2 wrapped lines,
 * positioned above the anchor sprite (or at explicit x/y), clamped inside.
 */
private fun DrawScope.drawBubble(bubble: StoryBubble, sprites: List<StorySprite>, paint: Paint) {
    val anchor = sprites.getOrNull(bubble.anchor)
    if (anchor == null && (bubble.x < 0f || bubble.y < 0f)) return
    val bx = if (bubble.x >= 0f) bubble.x else anchor?.x?.coerceIn(18f, 82f) ?: 50f
    val by = if (bubble.y >= 0f) bubble.y else ((anchor?.y?.coerceIn(18f, 82f) ?: 60f) - 28f).coerceAtLeast(8f)
    val x = size.width * (bx.coerceIn(12f, 88f) / 100f)
    val y = size.height * (by.coerceIn(6f, 82f) / 100f)

    paint.textSize = 13.dp.toPx()
    paint.textAlign = Paint.Align.LEFT
    paint.style = Paint.Style.FILL
    val maxWidth = size.width * 0.62f
    val lines = wrapLines(bubble.text, maxWidth - 16.dp.toPx(), paint).take(2)
    val textWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 72.dp.toPx()
    val bubbleWidth = min(maxWidth, max(72.dp.toPx(), textWidth) + 18.dp.toPx())
    val bubbleHeight = 18.dp.toPx() + lines.size * 16.dp.toPx()
    val left = (x - bubbleWidth / 2f).coerceIn(8.dp.toPx(), size.width - bubbleWidth - 8.dp.toPx())
    val top = y.coerceIn(8.dp.toPx(), size.height - bubbleHeight - 10.dp.toPx())

    drawContext.canvas.nativeCanvas.apply {
        val r = 12.dp.toPx()
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        drawRoundRect(left, top, left + bubbleWidth, top + bubbleHeight, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.dp.toPx()
        paint.color = 0xFF1C1917.toInt()
        drawRoundRect(left, top, left + bubbleWidth, top + bubbleHeight, r, r, paint)
        paint.style = Paint.Style.FILL
        lines.forEachIndexed { i, line ->
            drawText(line, left + 8.dp.toPx(), top + 17.dp.toPx() + i * 16.dp.toPx(), paint)
        }
    }
}

private fun wrapLines(value: String, maxWidth: Float, paint: Paint): List<String> {
    val words = value.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var line = ""
    words.forEach { word ->
        val candidate = if (line.isBlank()) word else "$line $word"
        if (paint.measureText(candidate) <= maxWidth || line.isBlank()) {
            line = candidate
        } else {
            lines.add(line)
            line = word
        }
    }
    if (line.isNotBlank()) lines.add(line)
    return lines
}
