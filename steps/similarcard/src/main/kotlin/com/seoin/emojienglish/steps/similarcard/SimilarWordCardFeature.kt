package com.seoin.emojienglish.steps.similarcard

import androidx.compose.runtime.Composable
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
import com.seoin.emojienglish.step.requireString
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
 * DUMMY similar_word_card step (§5.2 params: `wordId`, `answer`, `choices`).
 * The real step is a choice quiz producing [StepResult.Scored]; the dummy just
 * "answers" with the correct choice so the flow can be exercised.
 */
data class SimilarCardSpec(
    override val stepId: String,
    val wordId: String,
    val answer: String,
    val choices: List<String>,
) : StepSpec

class SimilarWordCardFeature @Inject constructor() : StepFeature {
    override val type: String = "similar_word_card"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec {
        val p = stepJson.params()
        return SimilarCardSpec(
            stepId = stepJson.stepId(),
            wordId = p.requireString("wordId"),
            answer = p.requireString("answer"),
            choices = p.stringList("choices"),
        )
    }

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as SimilarCardSpec
        DummyStudentScaffold(
            emoji = "🃏",
            title = "비슷한 말 카드",
            typeLabel = type,
            summaryLines = listOf(
                "단어: ${s.wordId}",
                "정답: ${s.answer}",
                "선택지: ${s.choices.joinToString()}",
            ),
            completeButtonText = "정답 선택 → 완료",
            modifier = modifier,
        ) {
            session.trace("answer_submit", mapOf("selected" to s.answer))
            session.complete(
                StepResult.Scored(selected = s.answer, answer = s.answer, score = 1, maxScore = 1),
            )
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "비슷한 말 카드 — 기록",
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
interface SimilarCardBindModule {
    @Binds
    @IntoMap
    @StringKey("similar_word_card")
    fun bind(impl: SimilarWordCardFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun SimilarCardPreview() {
    SimilarWordCardFeature().StudentScreen(
        spec = SimilarCardSpec("s3", "w_delicious", "tasty", listOf("noisy", "tasty", "empty", "late")),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
