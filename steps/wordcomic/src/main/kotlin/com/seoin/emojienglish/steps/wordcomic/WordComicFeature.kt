package com.seoin.emojienglish.steps.wordcomic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seoin.emojienglish.designsystem.DummyMasterScaffold
import com.seoin.emojienglish.designsystem.MasterActivityRow
import com.seoin.emojienglish.designsystem.formatClock
import com.seoin.emojienglish.model.ComicBubble
import com.seoin.emojienglish.model.ComicScene
import com.seoin.emojienglish.model.ComicScript
import com.seoin.emojienglish.model.ComicSprite
import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.model.StepTraceSnapshot
import com.seoin.emojienglish.step.FakeStepSession
import com.seoin.emojienglish.step.StepFeature
import com.seoin.emojienglish.step.StepSession
import com.seoin.emojienglish.step.StepSpec
import com.seoin.emojienglish.step.resultSummaries
import com.seoin.emojienglish.step.timeOrderedActivities
import com.seoin.emojienglish.step.params
import com.seoin.emojienglish.step.stepId
import com.seoin.emojienglish.step.stringList
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
 * word_comic step (Phase 1: declarative 4-panel comic renderer).
 *
 * Renders a [ComicScript] via [ComicStrip] — the whole comic is data. For now the
 * script is a static sample (swamp/diet/pale/algae); Phase 2 adds animations and
 * Phase 3 has an LLM generate the script JSON from keywords. Tapping a panel asks
 * the voice tutor to explain that panel's highlighted word.
 */
data class WordComicSpec(
    override val stepId: String,
    val panelIds: List<String>,
) : StepSpec

class WordComicFeature @Inject constructor() : StepFeature {
    override val type: String = "word_comic"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        WordComicSpec(
            stepId = stepJson.stepId(),
            panelIds = stepJson.params().stringList("panelIds"),
        )

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val saved by session.savedResult.collectAsState()

        // Viewing the comic IS the activity → record + complete on entry so the
        // "다음 단계" button is available.
        LaunchedEffect(Unit) {
            session.trace("step_visit", mapOf("comic" to "swamp_diet_pale_algae"))
            session.complete(StepResult.Completed())
        }

        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("단어 만화", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "칸을 누르면 그 단어를 음성으로 설명해줘요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                ComicStrip(
                    script = SwampDietPaleAlgae,
                    onPanelClick = { scene ->
                        val word = scene.highlight ?: return@ComicStrip
                        session.trace("ask_voice", mapOf("word" to word))
                        session.requestVoice(
                            VoicePrompt(
                                templateId = "word_explain",
                                kind = StepPromptKind.EXPLAIN,
                                payload = word,
                                contextLabel = "단어 설명 · $word",
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
            title = "단어 만화 — 기록",
            resultLines = trace.resultSummaries(),
            activities = trace.timeOrderedActivities().map {
                MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail)
            },
            modifier = modifier,
        )
    }
}

/** Phase-1 static comic teaching: swamp → diet → pale → algae (a frog's story). */
private val SwampDietPaleAlgae = ComicScript(
    panels = listOf(
        ComicScene(
            bg = "swamp",
            caption = "개구리가 swamp(늪)에 살아요",
            highlight = "swamp",
            sprites = listOf(
                ComicSprite(char = "🌳", x = 20f, y = 55f, scale = 1.2f),
                ComicSprite(char = "🐸", x = 48f, y = 70f, scale = 1.3f),
                ComicSprite(char = "🪨", x = 78f, y = 74f),
            ),
            bubble = ComicBubble(anchor = 1, text = "여기가 내 집!"),
        ),
        ComicScene(
            bg = "pond",
            caption = "오늘부터 diet(식단 조절)를 해요",
            highlight = "diet",
            sprites = listOf(
                ComicSprite(char = "🐸", x = 42f, y = 70f, scale = 1.3f),
                ComicSprite(char = "🥗", x = 74f, y = 68f, scale = 1.1f),
            ),
            bubble = ComicBubble(anchor = 0, text = "건강하게 먹자!"),
        ),
        ComicScene(
            bg = "night",
            caption = "너무 적게 먹어 pale(창백)해졌어요",
            highlight = "pale",
            sprites = listOf(
                ComicSprite(char = "🐸", x = 46f, y = 70f, scale = 1.3f),
                ComicSprite(char = "💧", x = 72f, y = 38f, scale = 0.9f, anim = "float"),
                ComicSprite(char = "😵‍💫", x = 64f, y = 60f, scale = 0.9f),
            ),
            bubble = ComicBubble(anchor = 0, text = "기운이 없어…"),
        ),
        ComicScene(
            bg = "pond",
            caption = "algae(물이끼)를 먹고 기운이 났어요!",
            highlight = "algae",
            sprites = listOf(
                ComicSprite(char = "🐸", x = 44f, y = 70f, scale = 1.3f, anim = "jump"),
                ComicSprite(char = "🌿", x = 74f, y = 72f, scale = 1.1f),
            ),
            bubble = ComicBubble(anchor = 0, text = "맛있다!"),
        ),
    ),
)

@Module
@InstallIn(SingletonComponent::class)
interface WordComicBindModule {
    @Binds
    @IntoMap
    @StringKey("word_comic")
    fun bind(impl: WordComicFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun WordComicPreview() {
    WordComicFeature().StudentScreen(
        spec = WordComicSpec("s1", listOf("p1", "p2", "p3")),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
