package com.seoin.emojienglish.steps.chunk

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seoin.emojienglish.designsystem.DummyMasterScaffold
import com.seoin.emojienglish.designsystem.MasterActivityRow
import com.seoin.emojienglish.designsystem.formatClock
import com.seoin.emojienglish.model.Chunk
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
        val chunks = s.chunkIds.mapNotNull { session.content.chunk(it) }

        if (chunks.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("청크 내용을 불러올 수 없습니다.", style = MaterialTheme.typography.bodyLarge)
            }
            return
        }

        var currentIndex by remember { mutableIntStateOf(0) }
        val isLast = currentIndex == chunks.lastIndex
        val current = chunks[currentIndex]

        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Header + progress
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("청크 해석", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${currentIndex + 1} / ${chunks.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                LinearProgressIndicator(
                    progress = { (currentIndex + 1).toFloat() / chunks.size },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Animated chunk card
                AnimatedContent(
                    targetState = currentIndex,
                    transitionSpec = {
                        (slideInHorizontally { it / 2 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 2 } + fadeOut())
                    },
                    label = "chunk",
                ) { idx ->
                    val chunk = chunks.getOrNull(idx) ?: chunks.first()
                    ChunkCard(chunk = chunk, index = idx + 1, total = chunks.size)
                }

                // Dot indicators
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunks.indices.forEach { i ->
                        val isActive = i == currentIndex
                        Surface(
                            color = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                            modifier = Modifier.size(if (isActive) 10.dp else 8.dp),
                            content = {},
                        )
                    }
                }

                // Navigation buttons
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (currentIndex > 0) {
                        TextButton(
                            onClick = { currentIndex-- },
                            modifier = Modifier.weight(1f),
                        ) { Text("← 이전") }
                    }

                    if (isLast) {
                        Button(
                            onClick = {
                                session.trace("chunk_view", mapOf("count" to chunks.size.toString()))
                                session.complete(StepResult.Completed())
                            },
                            modifier = Modifier.weight(if (currentIndex > 0) 1f else 2f),
                        ) { Text("✅ 완료") }
                    } else {
                        Button(
                            onClick = {
                                session.trace("chunk_seen", mapOf("index" to currentIndex.toString()))
                                currentIndex++
                            },
                            modifier = Modifier.weight(if (currentIndex > 0) 1f else 2f),
                        ) { Text("다음 →") }
                    }
                }
            }
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

@Composable
private fun ChunkCard(chunk: Chunk, index: Int, total: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                chunk.text,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                lineHeight = 40.sp,
            )

            if (chunk.meaningKo.isNotBlank()) {
                Text(
                    "▼",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Text(
                    chunk.meaningKo,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }
        }
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
