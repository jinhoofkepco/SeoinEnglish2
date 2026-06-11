package com.seoin.emojienglish.steps.voiceexplain

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
import com.seoin.emojienglish.step.string
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
 * DUMMY voice_explain step (§5.2 params: `targetType`, `targetId`, `promptTemplateId`).
 *
 * The real step will drive a coaching turn via [com.seoin.emojienglish.voice.VoiceGateway]
 * (`runTurn`, Appendix A §B-1). For now it just opens the global voice sheet and
 * completes, so the wiring is exercised end-to-end.
 */
data class VoiceExplainSpec(
    override val stepId: String,
    val targetType: String,
    val targetId: String,
    val promptTemplateId: String,
) : StepSpec

class VoiceExplainFeature @Inject constructor() : StepFeature {
    override val type: String = "voice_explain"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec {
        val p = stepJson.params()
        return VoiceExplainSpec(
            stepId = stepJson.stepId(),
            targetType = p.requireString("targetType"),
            targetId = p.requireString("targetId"),
            promptTemplateId = p.string("promptTemplateId") ?: "word_basic",
        )
    }

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as VoiceExplainSpec
        val word = session.content.word(s.targetId)
        DummyStudentScaffold(
            emoji = "🎙️",
            title = "음성 설명",
            typeLabel = type,
            summaryLines = listOfNotNull(
                "대상: ${s.targetType} / ${s.targetId}",
                "템플릿: ${s.promptTemplateId}",
                word?.let { "단어: ${it.text} (${it.meaningKo})" },
            ),
            completeButtonText = "코칭 듣기 → 완료",
            modifier = modifier,
        ) {
            session.requestVoice(
                VoicePrompt(
                    templateId = s.promptTemplateId,
                    variables = mapOf(
                        "word" to (word?.text ?: s.targetId),
                        "example" to (word?.example ?: ""),
                    ),
                    contextLabel = "음성 설명 · ${word?.text ?: s.targetId}",
                ),
            )
            session.complete(StepResult.VoiceCompleted(s.targetType, s.targetId))
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "음성 설명 — 기록",
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
interface VoiceExplainBindModule {
    @Binds
    @IntoMap
    @StringKey("voice_explain")
    fun bind(impl: VoiceExplainFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun VoiceExplainPreview() {
    VoiceExplainFeature().StudentScreen(
        spec = VoiceExplainSpec("s2", "word", "w_order", "word_basic"),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
