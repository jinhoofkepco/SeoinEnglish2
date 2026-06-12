package com.seoin.emojienglish.steps.storycomic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seoin.emojienglish.designsystem.DummyMasterScaffold
import com.seoin.emojienglish.designsystem.MasterActivityRow
import com.seoin.emojienglish.designsystem.formatClock
import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.model.StepTraceSnapshot
import com.seoin.emojienglish.step.FakeStepSession
import com.seoin.emojienglish.step.StepFeature
import com.seoin.emojienglish.step.StepSession
import com.seoin.emojienglish.step.StepSpec
import com.seoin.emojienglish.step.params
import com.seoin.emojienglish.step.resultSummaries
import com.seoin.emojienglish.step.stepId
import com.seoin.emojienglish.step.timeOrderedActivities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * story_comic (전체만화) — the lesson's words woven into ONE continuous story
 * comic, ported from the previous app's word-comic study stage.
 *
 * Design decisions carried over (전작에서 검증된 고민들):
 *  1. **한 컷씩 크게**: a single big panel per screen, not a grid — a child
 *     focuses on one scene at a time.
 *  2. **캡션 자동 낭독**: when a panel appears its caption is spoken (TTS),
 *     pairing listening with reading. Replay re-reads any time.
 *  3. **오늘의 단어 칩**: today's words as chips above the panel; tapping one
 *     speaks the word + a kid-friendly English explanation.
 *  4. **캡션 속 단어 탭**: today's words are highlighted inside the caption and
 *     individually tappable — meaning arrives at the exact moment of curiosity.
 *  5. **풍부한 연출**: camera moves, mood grading, fx, sfx, sprite anims — the
 *     panel is always subtly alive (StoryComicPanelView).
 *  6. **흐름 인지 버튼**: the next button tells the child where they are
 *     ("다음 컷" → "처음부터" at the end), and the step auto-completes on the
 *     last panel so the Player's CTA appears without an extra tap.
 */
class StoryComicFeature @Inject constructor() : StepFeature {
    override val type: String = "story_comic"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        StoryComicSpec(
            stepId = stepJson.stepId(),
            data = parseStoryComic(stepJson.params()),
        )

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as StoryComicSpec
        val data = s.data
        var panelIndex by remember { mutableIntStateOf(0) }
        val panel = data.panels[panelIndex]
        val isLast = panelIndex == data.panels.lastIndex

        fun speakWord(word: String) {
            val explanation = data.wordExplanations[word.lowercase().trim()]
                ?: session.content.word(word)?.let { "${it.meaningKo}. ${it.example}" }
                ?: return
            session.trace("word_tap", mapOf("word" to word))
            session.speak("$word. $explanation")
        }

        // 컷 등장 = 캡션 낭독 + 기록. 마지막 컷 도달 = 완료(Player CTA 활성).
        LaunchedEffect(panelIndex) {
            session.trace("panel_view", mapOf("panel" to panelIndex.toString()))
            if (panel.caption.isNotBlank()) session.speak(panel.caption)
            if (isLast) session.complete(StepResult.Completed())
        }

        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header: story title + meaning + progress
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        data.title.ifBlank { "이야기 만화" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEA6A22),
                    )
                    Text(
                        "${panelIndex + 1} / ${data.panels.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (data.meaning.isNotBlank()) {
                    Text(
                        data.meaning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 오늘의 단어 칩 — 탭하면 단어 + 쉬운 영어 설명을 읽어줌
                if (data.words.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        data.words.forEach { word ->
                            AssistChip(
                                onClick = { speakWord(word) },
                                label = { Text(word) },
                            )
                        }
                    }
                }

                // The panel — one per screen, big.
                StoryComicPanelView(
                    panel = panel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )

                // Caption with highlighted, tappable today-words.
                if (panel.caption.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFE0BE)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = captionWithTappableWords(panel.caption, data.words, ::speakWord),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }

                // Panel dots
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.panels.indices.forEach { i ->
                        Surface(
                            color = if (i == panelIndex) Color(0xFFEA6A22)
                                    else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                            modifier = Modifier.size(if (i == panelIndex) 10.dp else 8.dp),
                            content = {},
                        )
                    }
                }

                // Replay + flow-aware next
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            session.trace("replay", mapOf("panel" to panelIndex.toString()))
                            if (panel.caption.isNotBlank()) session.speak(panel.caption)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("🔁 다시 듣기") }

                    if (!isLast) {
                        Button(
                            onClick = { panelIndex++ },
                            modifier = Modifier.weight(1f),
                        ) { Text("다음 컷 →") }
                    } else {
                        OutlinedButton(
                            onClick = { panelIndex = 0 },
                            modifier = Modifier.weight(1f),
                        ) { Text("처음부터 🔄") }
                    }
                }
            }
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "전체 만화 — 기록",
            resultLines = trace.resultSummaries(),
            activities = trace.timeOrderedActivities().map {
                MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail)
            },
            modifier = modifier,
        )
    }
}

/**
 * Caption as AnnotatedString: today's words get the 전작 highlight treatment
 * (bold + underline + warm color + soft background) and are tappable to hear
 * the word's explanation right where the question arises.
 */
private fun captionWithTappableWords(
    caption: String,
    words: List<String>,
    onWordTap: (String) -> Unit,
) = buildAnnotatedString {
    val highlightStyle = SpanStyle(
        color = Color(0xFFB45309),
        background = Color(0x33FBBF24),
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline,
    )
    // Find each today-word occurrence (case-insensitive, word boundary).
    data class Hit(val start: Int, val end: Int, val word: String)
    val hits = mutableListOf<Hit>()
    words.forEach { word ->
        if (word.isBlank()) return@forEach
        Regex("\\b${Regex.escape(word)}\\w*", RegexOption.IGNORE_CASE)
            .findAll(caption)
            .forEach { m -> hits += Hit(m.range.first, m.range.last + 1, word) }
    }
    hits.sortBy { it.start }

    var cursor = 0
    hits.forEach { hit ->
        if (hit.start < cursor) return@forEach // overlapping match — skip
        append(caption.substring(cursor, hit.start))
        withLink(
            LinkAnnotation.Clickable(
                tag = hit.word,
                styles = TextLinkStyles(style = highlightStyle),
                linkInteractionListener = { onWordTap(hit.word) },
            ),
        ) {
            append(caption.substring(hit.start, hit.end))
        }
        cursor = hit.end
    }
    append(caption.substring(cursor))
}

@Module
@InstallIn(SingletonComponent::class)
interface StoryComicBindModule {
    @Binds
    @IntoMap
    @StringKey("story_comic")
    fun bind(impl: StoryComicFeature): StepFeature
}

// ------------------------------------------------------------------ preview

/** Standalone preview data — mirrors the restaurant unit's story comic. */
internal val PreviewStory = StoryComicData(
    title = "Mina's Lunch Story",
    meaning = "오늘 배운 단어로 만든 이야기 만화예요",
    words = listOf("order", "delicious"),
    wordExplanations = mapOf(
        "order" to "Order means to ask for food in a restaurant.",
        "delicious" to "Delicious means the food tastes very, very good.",
    ),
    panels = listOf(
        StoryPanel(
            bg = "room", mood = "warm", climax = false, fx = "",
            zoom = StoryZoom("pan", 1.35f, 50f, 55f),
            sfx = emptyList(),
            caption = "Mina goes to a restaurant.",
            sprites = listOf(
                StorySprite("👧", 30f, 72f, 1.0f, 0f, false, "sway"),
                StorySprite("🍽️", 65f, 70f, 0.9f, 0f, false, "none"),
            ),
            bubble = StoryBubble(0, -1f, -1f, "Hungry!"),
        ),
        StoryPanel(
            bg = "room", mood = "", climax = true, fx = "focus",
            zoom = StoryZoom("pushin", 1.3f, 50f, 55f),
            sfx = listOf(StorySfx("Yum!", 72f, 28f, 10f, -8f, 0xFFFDE047.toInt())),
            caption = "The pasta is delicious!",
            sprites = listOf(
                StorySprite("😋", 40f, 68f, 1.2f, 0f, false, "jump"),
                StorySprite("🍝", 68f, 70f, 1.0f, 0f, false, "bounce"),
            ),
            bubble = StoryBubble(0, -1f, -1f, "So good!"),
        ),
    ),
)

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun StoryComicPreview() {
    StoryComicFeature().StudentScreen(
        spec = StoryComicSpec("s_story", PreviewStory),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
