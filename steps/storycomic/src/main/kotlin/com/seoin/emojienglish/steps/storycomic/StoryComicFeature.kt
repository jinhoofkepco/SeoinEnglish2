package com.seoin.emojienglish.steps.storycomic

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.window.Dialog
import com.seoin.emojienglish.designsystem.DummyMasterScaffold
import com.seoin.emojienglish.designsystem.MasterActivityRow
import com.seoin.emojienglish.designsystem.formatClock
import com.seoin.emojienglish.designsystem.rememberContentImage
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
import com.seoin.emojienglish.voice.PictureController
import com.seoin.emojienglish.voice.PictureWord
import com.seoin.emojienglish.voice.StepPromptKind
import com.seoin.emojienglish.voice.VoicePrompt
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
class StoryComicFeature @Inject constructor(
    private val pictures: PictureController,
) : StepFeature {
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
        val voiceActive by session.voiceActive.collectAsState()
        var panelIndex by remember { mutableIntStateOf(0) }
        var selectedWord by remember { mutableStateOf<String?>(null) }
        var heardWords by remember { mutableStateOf(setOf<String>()) }
        var inQuiz by remember { mutableStateOf(false) }
        // 빠른 연타·재구성 사이의 stale 인덱스로 인한 OOB 크래시 방어 (피드백).
        if (panelIndex > data.panels.lastIndex) panelIndex = data.panels.lastIndex
        val panel = data.panels[panelIndex.coerceIn(0, data.panels.lastIndex)]
        val isLast = panelIndex >= data.panels.lastIndex

        fun explanationOf(word: String): String? =
            data.wordExplanations[word.lowercase().trim()]
                ?: session.content.word(word)?.let { "${it.meaningKo}. ${it.example}" }

        fun popupOf(word: String): StoryWordPopup? =
            data.wordPopups[word.lowercase().trim()]

        fun popupDefinitionOf(word: String): String? =
            popupOf(word)?.definitionEn ?: explanationOf(word)

        // 단어 탭 = 팝업 열기 + 영어 정의 즉시 낭독. 그림/대화는 팝업에서 이어진다.
        fun selectWord(word: String) {
            selectedWord = word
            heardWords = heardWords + word
            session.trace("word_tap", mapOf("word" to word))
            if (!voiceActive) {
                popupDefinitionOf(word)?.let { definition ->
                    session.speak("$word. $definition")
                }
            } else {
                session.trace("word_tts_skipped_voice_active", mapOf("word" to word))
            }
        }

        // 단어 수집 완료 — 한 번만 기록 (다 만나면 작은 축하 한 줄).
        val allHeard = data.words.isNotEmpty() && heardWords.containsAll(data.words)
        var celebrated by remember { mutableStateOf(false) }
        LaunchedEffect(allHeard) {
            if (allHeard && !celebrated) {
                celebrated = true
                session.trace("words_all_heard", mapOf("count" to data.words.size.toString()))
            }
        }

        // 찾기 퀴즈 모드 — 만화 화면 전체를 퀴즈로 전환.
        if (inQuiz) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    StoryFindQuiz(
                        data = data,
                        session = session,
                        onExit = { inQuiz = false },
                    )
                }
            }
            return
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
                // 제목/설명은 공간 절약을 위해 제거 — 진행 표시만 우측에 작게 (피드백).
                Text(
                    "${panelIndex + 1} / ${data.panels.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.End),
                )

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
                                onClick = { selectWord(word) },
                                label = {
                                    Text(
                                        when {
                                            selectedWord == word -> "⭐ $word"
                                            word in heardWords -> "✓ $word"
                                            else -> word
                                        },
                                    )
                                },
                            )
                        }
                    }
                }

                if (allHeard) {
                    Text(
                        "🎉 오늘의 단어를 다 만났어요!",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32),
                    )
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
                            text = captionWithTappableWords(panel.caption, data.words, ::selectWord),
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

                // Replay + read-along + flow-aware next
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            session.trace("replay", mapOf("panel" to panelIndex.toString()))
                            if (panel.caption.isNotBlank()) session.speak(panel.caption)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("🔁 듣기") }

                    // 따라 읽기 — GPT가 천천히 읽어주고 아이가 따라 말함 (READ_ALONG).
                    OutlinedButton(
                        onClick = {
                            session.trace("read_along", mapOf("panel" to panelIndex.toString()))
                            session.requestVoice(
                                VoicePrompt(
                                    templateId = "story_read_along",
                                    kind = StepPromptKind.READ_ALONG,
                                    payload = panel.caption,
                                    contextLabel = "따라 읽기 · 컷 ${panelIndex + 1}",
                                ),
                            )
                        },
                        enabled = panel.caption.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("🗣 따라 읽기") }

                    if (!isLast) {
                        Button(
                            onClick = { if (panelIndex < data.panels.lastIndex) panelIndex++ },
                            modifier = Modifier.weight(1f),
                        ) { Text("다음 컷 →") }
                    } else {
                        OutlinedButton(
                            onClick = { panelIndex = 0 },
                            modifier = Modifier.weight(1f),
                        ) { Text("🔄 처음부터") }
                    }
                }

                // 마지막 컷 = 회상 퀴즈로 마무리 (방금 본 컷에서 단어 장면 찾기).
                if (isLast) {
                    Button(
                        onClick = {
                            session.trace("quiz_start", emptyMap())
                            inQuiz = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🔍 단어 찾기 퀴즈") }
                }
            }

            selectedWord?.let { word ->
                val key = word.lowercase().trim()
                val popup = popupOf(word)
                val explanation = explanationOf(word)
                StoryWordPopupDialog(
                    word = word,
                    popup = popup,
                    fallbackDefinition = explanation,
                    voiceActive = voiceActive,
                    onDismiss = { selectedWord = null },
                    onPictureClick = {
                        // 그림창에 단어 칩 추가(요청은 그림창에서 칩 탭 시). 팝업은 닫아
                        // 그림창이 가려지지 않게 한다.
                        session.trace("story_word_picture", mapOf("word" to word))
                        pictures.addWord(
                            PictureWord(
                                id = key,
                                label = word,
                                prompt = storyPicturePayload(word, explanation),
                            ),
                        )
                        selectedWord = null
                    },
                    onTalkClick = {
                        session.trace("word_talk", mapOf("word" to word))
                        session.requestVoice(
                            VoicePrompt(
                                templateId = "story_word_talk",
                                kind = StepPromptKind.QUIZ_VOCAB,
                                payload = storyWordTalkPayload(
                                    word = word,
                                    popup = popup,
                                    extraBrief = data.voiceTalks[key] ?: explanation,
                                    caption = panel.caption,
                                ),
                                contextLabel = "단어 이야기 · $word",
                            ),
                        )
                    },
                )
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

@Composable
private fun StoryWordPopupDialog(
    word: String,
    popup: StoryWordPopup?,
    fallbackDefinition: String?,
    voiceActive: Boolean,
    onDismiss: () -> Unit,
    onPictureClick: () -> Unit,
    onTalkClick: () -> Unit,
) {
    val definition = popup?.definitionEn ?: fallbackDefinition.orEmpty()
    val imageAsset = popup?.imageAsset.orEmpty()
    val imageAlt = popup?.imageAlt.orEmpty()
    var talkRequested by remember(word) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .widthIn(max = 430.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (imageAsset.isNotBlank()) {
                    StoryWordAssetImage(
                        assetPath = imageAsset,
                        imageAlt = imageAlt,
                        cols = popup?.gridCols ?: 0,
                        rows = popup?.gridRows ?: 0,
                        cell = popup?.cell ?: -1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp)),
                    )
                }
                Text(
                    word,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (definition.isNotBlank()) {
                    Text(
                        definition,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = onPictureClick,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🖼 그림 보기") }
                Button(
                    onClick = {
                        if (talkRequested || voiceActive) return@Button
                        talkRequested = true
                        onTalkClick()
                    },
                    enabled = !talkRequested && !voiceActive,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            talkRequested -> "대화 요청됨"
                            voiceActive -> "선생님 말하는 중"
                            else -> "선생님이랑 대화하기"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryWordAssetImage(
    assetPath: String,
    imageAlt: String,
    cols: Int = 0,
    rows: Int = 0,
    cell: Int = -1,
    modifier: Modifier = Modifier,
) {
    // filesDir(런타임 콘텐츠) → assets 순으로 찾는다. 그리드 한 장이면 해당 칸만 잘라 표시.
    val bitmap = rememberContentImage(assetPath, cols, rows, cell)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = imageAlt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No picture",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    // Each token of a multi-word entry may inflect ("break down" → "breaks down",
    // "churn" → "churning"), so every token gets a \w* suffix.
    data class Hit(val start: Int, val end: Int, val word: String)
    val hits = mutableListOf<Hit>()
    words.forEach { word ->
        if (word.isBlank()) return@forEach
        val pattern = word.trim().split(Regex("\\s+"))
            .joinToString(separator = "\\w*\\s+", prefix = "\\b", postfix = "\\w*") { Regex.escape(it) }
        Regex(pattern, RegexOption.IGNORE_CASE)
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

// 그림창에 주입할 요청문 — 생성 요청 없이 사진 검색만 짧게.
private fun storyPicturePayload(word: String, explanation: String?): String {
    val target = explanation
        ?.takeIf { it.isNotBlank() }
        ?.let { "\"$word\"($it)" }
        ?: "\"$word\""
    return "$target 을 설명할 수 있는 사진 찾아줘."
}

private fun storyWordTalkPayload(
    word: String,
    popup: StoryWordPopup?,
    extraBrief: String?,
    caption: String,
): String = buildString {
    appendLine("Word: $word")
    popup?.definitionEn?.takeIf { it.isNotBlank() }?.let {
        appendLine("English definition: $it")
    }
    popup?.imageAlt?.takeIf { it.isNotBlank() }?.let {
        appendLine("Picture: $it")
    }
    caption.takeIf { it.isNotBlank() }?.let {
        appendLine("Comic sentence: $it")
    }
    extraBrief?.takeIf { it.isNotBlank() }?.let {
        appendLine("Extra teaching note: $it")
    }
    appendLine("Goal: Talk about the picture in very easy English, then ask the child one short question that helps them use the word.")
}.trim()

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
    wordPopups = emptyMap(),
    voiceTalks = emptyMap(),
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
    StoryComicFeature(com.seoin.emojienglish.voice.NoopPictureController).StudentScreen(
        spec = StoryComicSpec("s_story", PreviewStory),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
