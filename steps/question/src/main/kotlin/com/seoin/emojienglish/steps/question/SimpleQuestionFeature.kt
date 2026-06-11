package com.seoin.emojienglish.steps.question

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
 * DUMMY simple_question step (§5.2 params: `questionIds`). Real step is a
 * multiple-choice quiz producing [StepResult.Scored] per question. The dummy
 * resolves each referenced question and "answers" correctly.
 */
data class QuestionSpec(
    override val stepId: String,
    val questionIds: List<String>,
) : StepSpec

class SimpleQuestionFeature @Inject constructor() : StepFeature {
    override val type: String = "simple_question"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        QuestionSpec(
            stepId = stepJson.stepId(),
            questionIds = stepJson.params().stringList("questionIds"),
        )

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as QuestionSpec
        val first = s.questionIds.firstOrNull()?.let { session.content.question(it) }
        DummyStudentScaffold(
            emoji = "❓",
            title = "간단 질문",
            typeLabel = type,
            summaryLines = listOfNotNull(
                "질문 수: ${s.questionIds.size}",
                first?.let { "예: ${it.question}" },
            ),
            completeButtonText = "정답 선택 → 완료",
            modifier = modifier,
        ) {
            val answer = first?.answer ?: ""
            session.trace("answer_submit", mapOf("selected" to answer))
            session.complete(
                StepResult.Scored(
                    selected = answer, answer = answer,
                    score = s.questionIds.size, maxScore = s.questionIds.size,
                ),
            )
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "간단 질문 — 기록",
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
interface SimpleQuestionBindModule {
    @Binds
    @IntoMap
    @StringKey("simple_question")
    fun bind(impl: SimpleQuestionFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun SimpleQuestionPreview() {
    SimpleQuestionFeature().StudentScreen(
        spec = QuestionSpec("s5", listOf("q1")),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
