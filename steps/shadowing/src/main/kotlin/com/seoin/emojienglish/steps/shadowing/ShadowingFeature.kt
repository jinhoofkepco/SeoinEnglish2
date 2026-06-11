package com.seoin.emojienglish.steps.shadowing

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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * DUMMY shadowing step (§5.2 params: `passageId`). Real step records per-sentence
 * practice → [StepResult.ShadowingRecorded]; real audio comes later without any
 * contract change (§14). The dummy reports "all sentences practiced".
 */
data class ShadowingSpec(
    override val stepId: String,
    val passageId: String,
) : StepSpec

class ShadowingFeature @Inject constructor() : StepFeature {
    override val type: String = "shadowing"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        ShadowingSpec(
            stepId = stepJson.stepId(),
            passageId = stepJson.params().requireString("passageId"),
        )

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as ShadowingSpec
        val passage = session.content.passage
        val total = passage?.sentences?.size ?: 0
        DummyStudentScaffold(
            emoji = "🗣️",
            title = "쉐도잉",
            typeLabel = type,
            summaryLines = listOfNotNull(
                "지문: ${s.passageId}",
                passage?.let { "문장 수: $total" },
            ),
            completeButtonText = "모두 따라 읽기 → 완료",
            modifier = modifier,
        ) {
            session.trace("sentence_play", mapOf("count" to total.toString()))
            session.complete(
                StepResult.ShadowingRecorded(practicedSentences = total, totalSentences = total),
            )
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "쉐도잉 — 기록",
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
interface ShadowingBindModule {
    @Binds
    @IntoMap
    @StringKey("shadowing")
    fun bind(impl: ShadowingFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun ShadowingPreview() {
    ShadowingFeature().StudentScreen(
        spec = ShadowingSpec("s4", "passage_01"),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
