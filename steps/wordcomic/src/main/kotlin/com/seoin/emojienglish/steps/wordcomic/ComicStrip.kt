package com.seoin.emojienglish.steps.wordcomic

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seoin.emojienglish.model.ComicScene
import com.seoin.emojienglish.model.ComicScript

/**
 * Renders a [ComicScript] as a 2×2 strip of panels. The whole drawing is data —
 * each panel assembles fixed primitives (bg gradient, emoji sprites positioned by
 * percentage, caption, speech bubble, highlighted word). Phase 1 is static; the
 * `anim` field is parsed but not yet animated (Phase 2).
 */
@Composable
fun ComicStrip(
    script: ComicScript,
    modifier: Modifier = Modifier,
    onPanelClick: (ComicScene) -> Unit = {},
) {
    // Phone (compact) stacks panels in ONE vertical column; tablet shows 2×2 (피드백).
    // smallestScreenWidthDp is orientation-independent, so rotation won't flip it.
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val cols = if (tablet) 2 else 1

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        script.panels.chunked(cols).forEach { rowPanels ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowPanels.forEach { scene ->
                    ComicPanelView(
                        scene = scene,
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                        onClick = { onPanelClick(scene) },
                    )
                }
                repeat(cols - rowPanels.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ComicPanelView(
    scene: ComicScene,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush(scene.bg))
            .clickable(onClick = onClick),
    ) {
        val w = maxWidth
        val h = maxHeight
        val unit = if (w < h) w else h

        // Sprites positioned by percentage, clamped to a safe range so nothing is
        // clipped at the edges (공유 설계: x 13~87, y 18~82). Bigger base size than
        // before (피드백: 이전엔 너무 작게 렌더링).
        scene.sprites.forEach { sprite ->
            val scale = sprite.scale.coerceIn(0.4f, 1.6f)
            val fs = unit.value * 0.32f * scale
            val px = w * (sprite.x.coerceIn(13f, 87f) / 100f)
            val py = h * (sprite.y.coerceIn(18f, 82f) / 100f)
            SpriteView(
                emoji = sprite.char ?: "⬜",
                fontSp = fs,
                anim = sprite.anim,
                baseX = px - (fs / 2).dp,
                baseY = py - (fs / 2).dp,
            )
        }

        // Speech bubble anchored above one sprite.
        scene.bubble?.let { bubble ->
            scene.sprites.getOrNull(bubble.anchor)?.let { anchor ->
                val px = w * (anchor.x.coerceIn(13f, 87f) / 100f)
                val py = h * (anchor.y.coerceIn(18f, 82f) / 100f)
                Surface(
                    color = Color.White.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .offset(x = (px.value - 30).dp, y = (py.value - unit.value * 0.30f).coerceAtLeast(2f).dp)
                        .widthIn(max = w * 0.7f),
                ) {
                    Text(
                        bubble.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF202020),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Caption bar at the bottom, with the target word emphasized.
        if (scene.caption.isNotBlank()) {
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Text(
                    text = captionWithHighlight(scene.caption, scene.highlight),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * A single emoji sprite with an optional looping motion (Phase 2). The motion is
 * a small vertical offset added on top of the static position so it reads as
 * "bouncing / floating / jumping" without leaving the panel.
 */
@Composable
private fun SpriteView(emoji: String, fontSp: Float, anim: String?, baseX: Dp, baseY: Dp) {
    val (ampFraction, durationMs) = when (anim?.lowercase()) {
        "jump" -> 0.35f to 700
        "bounce" -> 0.14f to 420
        "float" -> 0.10f to 1800
        else -> 0f to 1000
    }
    val transition = rememberInfiniteTransition(label = "sprite")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "y",
    )
    val dy = -(fontSp * ampFraction) * phase // upward offset in dp units
    Box(Modifier.offset(x = baseX, y = baseY + dy.dp)) {
        Text(emoji, fontSize = fontSp.sp)
    }
}

private fun captionWithHighlight(caption: String, highlight: String?): AnnotatedString {
    if (highlight.isNullOrBlank()) return AnnotatedString(caption)
    val idx = caption.indexOf(highlight, ignoreCase = true)
    if (idx < 0) return AnnotatedString(caption)
    return buildAnnotatedString {
        append(caption.substring(0, idx))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F))) {
            append(caption.substring(idx, idx + highlight.length))
        }
        append(caption.substring(idx + highlight.length))
    }
}

/** Fixed background presets → vertical gradients (LLM picks by name, so stable). */
private fun bgBrush(name: String): Brush {
    val colors = when (name.lowercase()) {
        "swamp" -> listOf(Color(0xFF4B5320), Color(0xFF2E3B1F))
        "pond", "water" -> listOf(Color(0xFF4FC3F7), Color(0xFF0277BD))
        "forest" -> listOf(Color(0xFF81C784), Color(0xFF2E7D32))
        "sky" -> listOf(Color(0xFF90CAF9), Color(0xFFE3F2FD))
        "snow" -> listOf(Color(0xFFE1F5FE), Color(0xFFB3E5FC))
        "lava" -> listOf(Color(0xFFFF7043), Color(0xFFBF360C))
        "night" -> listOf(Color(0xFF303F9F), Color(0xFF1A237E))
        "sunset" -> listOf(Color(0xFFFFB74D), Color(0xFFE57373))
        else -> listOf(Color(0xFFCFD8DC), Color(0xFF90A4AE))
    }
    return Brush.verticalGradient(colors)
}
