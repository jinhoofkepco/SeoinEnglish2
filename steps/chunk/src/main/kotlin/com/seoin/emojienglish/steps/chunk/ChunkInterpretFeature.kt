package com.seoin.emojienglish.steps.chunk

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
 * DUMMY chunk_interpret step (§5.2 params: `chunkIds`). Real step walks each
 * chunk with its Korean meaning; the dummy lists the resolved chunks and
 * completes.
 */
data class ChunkSpec(
    override val stepId: String,
    val chunkIds: List<String>,
) : StepSpec

class ChunkInterpretFeature @Inject constructor() : StepFeature {
    override val type: String = "chunk_interpret"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        ChunkSpec(
            stepId = stepJson.stepId(),
            chunkIds = stepJson.params().stringList("chunkIds"),
        )

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as ChunkSpec
        val resolved = s.chunkIds.mapNotNull { session.content.chunk(it) }
        DummyStudentScaffold(
            emoji = "🧩",
            title = "청크 해석",
            typeLabel = type,
            summaryLines = listOf("청크 수: ${s.chunkIds.size}") +
                resolved.take(2).map { "• ${it.text} — ${it.meaningKo}" },
            completeButtonText = "해석 확인 → 완료",
            modifier = modifier,
        ) {
            session.trace("chunk_view", mapOf("count" to s.chunkIds.size.toString()))
            session.complete(StepResult.Completed())
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "청크 해석 — 기록",
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
interface ChunkInterpretBindModule {
    @Binds
    @IntoMap
    @StringKey("chunk_interpret")
    fun bind(impl: ChunkInterpretFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun ChunkInterpretPreview() {
    ChunkInterpretFeature().StudentScreen(
        spec = ChunkSpec("s6", listOf("c1", "c2", "c3")),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
