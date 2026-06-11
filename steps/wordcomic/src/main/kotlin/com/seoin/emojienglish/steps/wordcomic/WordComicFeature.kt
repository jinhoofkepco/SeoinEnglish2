package com.seoin.emojienglish.steps.wordcomic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.seoin.emojienglish.designsystem.DummyMasterScaffold
import com.seoin.emojienglish.designsystem.MasterActivityRow
import com.seoin.emojienglish.designsystem.formatClock
import com.seoin.emojienglish.designsystem.DummyStudentScaffold
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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * DUMMY word_comic step (§5.2 params: `panelIds`).
 *
 * Renders through [DummyStudentScaffold] so the whole app outline runs now; the
 * real comic UI is written in its own session later. Replacing it means swapping
 * the scaffold call for the real screen — nothing else changes (§0.2).
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
        val s = spec as WordComicSpec
        val saved by session.savedResult.collectAsState()
        DummyStudentScaffold(
            emoji = "🗯️",
            title = "단어 만화",
            typeLabel = type,
            summaryLines = listOf("패널: ${s.panelIds.joinToString().ifEmpty { "(없음)" }}") +
                if (saved != null) listOf("이미 완료됨") else emptyList(),
            modifier = modifier,
        ) {
            session.trace("step_visit", mapOf("panels" to s.panelIds.size.toString()))
            session.complete(StepResult.Completed())
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
