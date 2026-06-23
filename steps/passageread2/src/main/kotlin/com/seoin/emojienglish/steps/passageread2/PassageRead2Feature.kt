package com.seoin.emojienglish.steps.passageread2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.seoin.emojienglish.step.StepSpecParseException
import com.seoin.emojienglish.step.params
import com.seoin.emojienglish.step.resultSummaries
import com.seoin.emojienglish.step.stepId
import com.seoin.emojienglish.step.string
import com.seoin.emojienglish.step.timeOrderedActivities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.max
import kotlin.math.min

data class PassageRead2Spec(
    override val stepId: String,
    val data: PassageRead2Data,
) : StepSpec

data class PassageRead2Data(
    val title: String,
    val subtitle: String,
    val renderConfig: PassageRenderConfig,
    val sentences: List<PassageRead2Sentence>,
)

data class PassageRead2Sentence(
    val id: String,
    val text: String,
    val clauses: List<PassageClause>,
    val chunks: List<PassageChunk2>,
    val segments: List<PassageRenderSegment>,
)

data class PassageClause(
    val id: String,
    val parent: String?,
    val depth: Int,
    val role: String,
    val hostChunkId: String?,
)

data class PassageChunk2(
    val id: String,
    val text: String,
    val role: String,
    val clauseId: String,
    val opensClause: String?,
    val attachesTo: String?,
    val startChar: Int,
    val endChar: Int,
)

data class PassageRenderSegment(
    val id: String,
    val text: String,
    val role: String,
    val displayRole: String,
    val depth: Int,
    val startChar: Int,
    val endChar: Int,
    val sourceChunkIds: List<String>,
    val internalBreaks: List<Int>,
    val merged: Boolean,
)

data class PassageRenderConfig(
    val roleHue: Map<String, String>,
    val depthSaturation: Map<Int, Float>,
    val depthCap: Int,
) {
    fun saturation(depth: Int): Float = depthSaturation[depth.coerceAtMost(depthCap)] ?: 0.35f
}

private data class PassageProgressState(
    val currentChunkIndex: Int,
    val activeDepth: Int,
    val currentChunk: PassageChunk2?,
    val displaySegments: List<PassageProgressSegment>,
)

private data class PassageProgressSegment(
    val id: String,
    val text: String,
    val displayRole: String,
    val startChar: Int,
    val endChar: Int,
    val sourceChunkIds: List<String>,
    val sourceChunkIndexes: List<Int>,
    val internalBreaks: List<Int>,
    val layers: List<PassageLayer>,
    val phase: ChunkPhase,
    val opensTrack: Boolean,
)

private data class PassageLayer(
    val role: String,
    val depth: Int,
)

private enum class ChunkPhase {
    Painting,
    Active,
    Dimmed,
    Merged,
}

/**
 * passage_read2 sample.
 *
 * Rendering rule: the visible sentence is always [PassageRead2Sentence.text].
 * Chunk data only resolves character ranges; it never reconstructs the string or
 * inserts separators, so chunking cannot change text measurement or wrapping.
 */
class PassageRead2Feature @Inject constructor() : StepFeature {
    override val type: String = "passage_read2"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        PassageRead2Spec(
            stepId = stepJson.stepId(),
            data = parsePassageRead2(stepJson.params(), content),
        )

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as PassageRead2Spec
        val data = s.data

        var sentenceIndex by remember { mutableIntStateOf(0) }
        val sentence = data.sentences[sentenceIndex]
        var currentChunkIndex by remember(sentence.id) {
            mutableIntStateOf(-1)
        }
        val progress = remember(sentence, currentChunkIndex, data.renderConfig) {
            buildProgressState(sentence, data.renderConfig, currentChunkIndex)
        }

        LaunchedEffect(sentence.id) {
            session.trace(
                "passage_read2_sentence_view",
                mapOf("sentence" to sentence.id, "chunkCount" to sentence.chunks.size.toString()),
            )
        }

        fun moveChunk(delta: Int) {
            val next = (currentChunkIndex + delta).coerceIn(-1, sentence.chunks.lastIndex)
            if (next == currentChunkIndex) return
            val nextProgress = buildProgressState(sentence, data.renderConfig, next)
            currentChunkIndex = next
            val chunk = sentence.chunks.getOrNull(next)
            session.trace(
                "passage_read2_chunk_cursor",
                mapOf(
                    "sentence" to sentence.id,
                    "index" to next.toString(),
                    "chunk" to (chunk?.id ?: "before_start"),
                    "depth" to nextProgress.activeDepth.toString(),
                ),
            )
        }

        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PassageHeader(
                title = data.title,
                subtitle = data.subtitle,
                index = sentenceIndex,
                total = data.sentences.size,
            )

            ChunkProgressStrip(
                sentence = sentence,
                currentChunkIndex = currentChunkIndex,
                activeDepth = progress.activeDepth,
            )

            ChunkedSentenceText(
                sentence = sentence,
                config = data.renderConfig,
                progress = progress,
                onAdvance = { moveChunk(1) },
            )

            ChunkProgressInspector(progress = progress, totalChunks = sentence.chunks.size)
            RoleLegend(data.renderConfig)

            NavigationRow(
                canGoBack = currentChunkIndex > -1 || sentenceIndex > 0,
                nextLabel = when {
                    currentChunkIndex < sentence.chunks.lastIndex -> "다음 청크"
                    sentenceIndex < data.sentences.lastIndex -> "다음 문장"
                    else -> "완료"
                },
                onBack = {
                    if (currentChunkIndex > -1) {
                        moveChunk(-1)
                    } else if (sentenceIndex > 0) {
                        sentenceIndex--
                    }
                },
                onNext = {
                    when {
                        currentChunkIndex < sentence.chunks.lastIndex -> moveChunk(1)
                        sentenceIndex < data.sentences.lastIndex -> sentenceIndex++
                        else -> {
                            session.trace("passage_read2_complete", mapOf("sentenceCount" to data.sentences.size.toString()))
                            session.complete(StepResult.Completed())
                        }
                    }
                },
                onComplete = {
                    session.trace("passage_read2_complete", mapOf("sentenceCount" to data.sentences.size.toString()))
                    session.complete(StepResult.Completed())
                },
            )
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "지문독해2 — 기록",
            resultLines = trace.resultSummaries(),
            activities = trace.timeOrderedActivities().map {
                MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail)
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun PassageHeader(title: String, subtitle: String, index: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(54.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "${index + 1}/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ChunkProgressStrip(
    sentence: PassageRead2Sentence,
    currentChunkIndex: Int,
    activeDepth: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (currentChunkIndex < 0) "청크 시작 전" else "청크 ${currentChunkIndex + 1} / ${sentence.chunks.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            Text(
                "activeDepth $activeDepth",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            sentence.chunks.forEachIndexed { index, chunk ->
                val reached = index <= currentChunkIndex
                val color = if (reached) colorForRole(chunk.roleForProgress(sentence)) else MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(color.copy(alpha = if (reached) 0.82f else 0.38f), RoundedCornerShape(50)),
                )
            }
        }
    }
}

@Composable
private fun ChunkedSentenceText(
    sentence: PassageRead2Sentence,
    config: PassageRenderConfig,
    progress: PassageProgressState,
    onAdvance: () -> Unit,
) {
    var layout by remember(sentence.id) { mutableStateOf<TextLayoutResult?>(null) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surface
    val markedText = remember(sentence, progress.currentChunkIndex, onSurface) {
        sentence.text.withTrackTextStyling(
            sentence = sentence,
            currentChunkIndex = progress.currentChunkIndex,
            subduedColor = onSurface.copy(alpha = 0.42f),
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdvance),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                markedText,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 13.5.sp,
                    lineHeight = 36.sp,
                ),
                color = onSurface,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val textLayout = layout ?: return@drawBehind
                        drawProgressTracks(
                            layout = textLayout,
                            segments = progress.displaySegments,
                            config = config,
                            activeDepth = progress.activeDepth,
                            surfaceColor = surfaceColor,
                        )
                    },
            )
            Text(
                "문장 영역을 탭하면 가장 작은 청크 단위로 왼쪽에서 오른쪽으로 진행됩니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun DrawScope.drawProgressTracks(
    layout: TextLayoutResult,
    segments: List<PassageProgressSegment>,
    config: PassageRenderConfig,
    activeDepth: Int,
    surfaceColor: Color,
) {
    segments.sortedWith(compareBy<PassageProgressSegment> { it.layers.lastOrNull()?.depth ?: 0 }.thenBy { it.startChar })
        .forEach { segment ->
            val layer = segment.layers.lastOrNull() ?: PassageLayer(segment.displayRole, 0)
            val depth = layer.depth.coerceAtMost(2)
            val rects = layout.trackRectsForRange(segment.startChar, segment.endChar)
            if (rects.isEmpty()) return@forEach

            if (isConnectiveTrack(layer.role, segment.displayRole)) {
                return@forEach
            }

            if (segment.opensTrack && depth in 1..2) {
                drawOpenTrackBracket(
                    rect = rects.first(),
                    depth = depth,
                    color = colorForRole(layer.role).copy(
                        alpha = bracketAlpha(
                            depth = depth,
                            phase = segment.phase,
                            activeDepth = activeDepth,
                        ),
                    ),
                )
            }

            rects.forEach { rect ->
                val roleColor = colorForRole(layer.role)
                if (depth == 0) {
                    drawD0Bubble(
                        rect = rect,
                        color = roleColor.copy(
                            alpha = d0BubbleAlpha(
                                phase = segment.phase,
                                activeDepth = activeDepth,
                            ),
                        ),
                    )
                } else {
                    drawNestedDepthBand(
                        rect = rect,
                        depth = depth,
                        color = roleColor.copy(
                            alpha = nestedBandAlpha(
                                depth = depth,
                                phase = segment.phase,
                                activeDepth = activeDepth,
                                config = config,
                            ),
                        ),
                    )
                }
            }

            if (segment.internalBreaks.isNotEmpty()) {
                drawTrackBreaks(
                    layout = layout,
                    segment = segment,
                    layer = layer,
                    color = colorForRole(layer.role).copy(alpha = 0.44f),
                    surfaceColor = surfaceColor,
                )
            }
        }
}

private fun DrawScope.drawD0Bubble(
    rect: TrackRect,
    color: Color,
) {
    val height = rect.height * 0.58f
    val horizontalInset = min(rect.width * 0.1f, 3.2.dp.toPx())
    val width = max(1f, rect.width - horizontalInset * 2)
    val top = rect.top + (rect.height - height) / 2f + 0.4.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left + horizontalInset, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f, height / 2f),
    )
}

private fun DrawScope.drawNestedDepthBand(
    rect: TrackRect,
    depth: Int,
    color: Color,
) {
    val horizontalInset = min(rect.width * 0.08f, 2.4.dp.toPx())
    val y = rect.centerY
    drawLine(
        color = color,
        start = Offset(max(0f, rect.left + horizontalInset), y),
        end = Offset(min(this.size.width, rect.right - horizontalInset), y),
        strokeWidth = nestedStrokeWidth(rect, depth),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawOpenTrackBracket(
    rect: TrackRect,
    depth: Int,
    color: Color,
) {
    val strokeWidth = nestedStrokeWidth(rect, depth)
    val bracketWidth = if (depth == 1) 4.8.dp.toPx() else 3.8.dp.toPx()
    val x = max(0f, rect.left - bracketWidth * 0.65f)
    val top = rect.top + rect.height * 0.16f
    val bottom = rect.bottom - rect.height * 0.16f
    val middle = (top + bottom) / 2f
    val path = Path().apply {
        moveTo(x + bracketWidth, top)
        cubicTo(x, top, x, middle, x + bracketWidth * 0.45f, middle)
        cubicTo(x, middle, x, bottom, x + bracketWidth, bottom)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawTrackBreaks(
    layout: TextLayoutResult,
    segment: PassageProgressSegment,
    layer: PassageLayer,
    color: Color,
    surfaceColor: Color,
) {
    val textLength = layout.layoutInput.text.length
    segment.internalBreaks
        .filter { it > segment.startChar && it < segment.endChar && it < textLength }
        .forEach { breakOffset ->
            val rect = layout.getBoundingBox(breakOffset)
            if (rect.width <= 0f || rect.height <= 0f) return@forEach
            val depth = layer.depth.coerceAtMost(2)
            val x = rect.left
            val lineIndex = layout.getLineForOffset(breakOffset)
            val lineRect = TrackRect(rect.left, rect.top, rect.right, rect.bottom, lineIndex)
            val y = lineRect.centerY
            val clearHalfHeight = if (depth == 0) {
                lineRect.height * 0.36f
            } else {
                nestedStrokeWidth(lineRect, depth) * 0.68f
            }
            val markHalfHeight = min(clearHalfHeight * 0.62f, 2.4.dp.toPx())
            drawLine(
                color = surfaceColor,
                start = Offset(x, y - clearHalfHeight),
                end = Offset(x, y + clearHalfHeight),
                strokeWidth = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(x, y - markHalfHeight),
                end = Offset(x, y + markHalfHeight),
                strokeWidth = 0.9.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
}

private fun DrawScope.nestedStrokeWidth(
    rect: TrackRect,
    depth: Int,
): Float {
    val ratio = if (depth.coerceAtMost(2) == 1) 0.5f else 0.25f
    val minWidth = if (depth.coerceAtMost(2) == 1) 4.dp.toPx() else 2.dp.toPx()
    return max(minWidth, rect.height * ratio)
}

private fun d0BubbleAlpha(
    phase: ChunkPhase,
    activeDepth: Int,
): Float {
    val base = when (phase) {
        ChunkPhase.Painting -> 0.5f
        ChunkPhase.Active -> 0.42f
        ChunkPhase.Merged -> 0.3f
        ChunkPhase.Dimmed -> 0.24f
    }
    val depthFocus = if (activeDepth == 0) 1f else 0.56f
    return (base * depthFocus).coerceIn(0.1f, 0.5f)
}

private fun nestedBandAlpha(
    depth: Int,
    phase: ChunkPhase,
    activeDepth: Int,
    config: PassageRenderConfig,
): Float {
    val base = if (depth.coerceAtMost(2) == 1) 0.3f else 0.18f
    return (base * nestedVisibility(depth, phase, activeDepth) * (0.78f + config.saturation(depth) * 0.22f))
        .coerceIn(0.07f, 0.32f)
}

private fun bracketAlpha(
    depth: Int,
    phase: ChunkPhase,
    activeDepth: Int,
): Float {
    val base = if (depth.coerceAtMost(2) == 1) 0.5f else 0.3f
    return (base * nestedVisibility(depth, phase, activeDepth)).coerceIn(0.1f, 0.5f)
}

private fun nestedVisibility(depth: Int, phase: ChunkPhase, activeDepth: Int): Float {
    val focused = depth.coerceAtMost(2) == activeDepth.coerceAtMost(2)
    return when {
        phase == ChunkPhase.Painting -> 1f
        focused -> 0.95f
        phase == ChunkPhase.Merged -> 0.72f
        else -> 0.56f
    }
}

private fun isConnectiveTrack(role: String, displayRole: String): Boolean =
    role == "connective" || displayRole == "connective"

private fun String.withTrackTextStyling(
    sentence: PassageRead2Sentence,
    currentChunkIndex: Int,
    subduedColor: Color,
) = buildAnnotatedString {
    val subduedOffsets = sentence.chunks
        .asSequence()
        .filterIndexed { index, chunk -> index <= currentChunkIndex && chunk.role == "connective" }
        .flatMap { chunk -> (chunk.startChar until chunk.endChar).asSequence() }
        .toSet()

    this@withTrackTextStyling.forEachIndexed { index, char ->
        val style = when {
            index in subduedOffsets && char == ' ' -> SpanStyle(color = subduedColor, letterSpacing = 1.4.sp)
            index in subduedOffsets -> SpanStyle(color = subduedColor)
            char == ' ' -> SpanStyle(letterSpacing = 1.4.sp)
            else -> null
        }
        if (style == null) {
            append(char)
        } else {
            withStyle(style) {
                append(char)
            }
        }
    }
}

private fun TextLayoutResult.trackRectsForRange(start: Int, endExclusive: Int): List<TrackRect> {
    val textLength = layoutInput.text.length
    val safeStart = start.coerceIn(0, textLength)
    val safeEnd = endExclusive.coerceIn(0, textLength)
    if (safeStart >= safeEnd) return emptyList()

    val rows = mutableListOf<TrackRect>()
    for (offset in safeStart until safeEnd) {
        val rect = getBoundingBox(offset)
        if (rect.width <= 0f || rect.height <= 0f) continue
        val lineIndex = getLineForOffset(offset)
        val row = rows.firstOrNull {
            it.lineIndex == lineIndex
        }
        if (row == null) {
            rows += TrackRect(rect.left, rect.top, rect.right, rect.bottom, lineIndex)
        } else {
            row.left = min(row.left, rect.left)
            row.top = min(row.top, rect.top)
            row.right = max(row.right, rect.right)
            row.bottom = max(row.bottom, rect.bottom)
        }
    }
    return rows
}

private data class TrackRect(
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
    val lineIndex: Int,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
}

private data class ClauseSpan(
    val clause: PassageClause,
    val chunks: List<PassageChunk2>,
    val indexes: List<Int>,
    val firstIndex: Int,
    val lastIndex: Int,
    val startChar: Int,
    val endChar: Int,
    val internalBreaks: List<Int>,
)

private fun buildProgressState(
    sentence: PassageRead2Sentence,
    config: PassageRenderConfig,
    currentChunkIndex: Int,
): PassageProgressState {
    val safeIndex = currentChunkIndex.coerceIn(-1, sentence.chunks.lastIndex)
    val currentChunk = sentence.chunks.getOrNull(safeIndex)
    val clauseById = sentence.clauses.associateBy { it.id }
    val chunkById = sentence.chunks.associateBy { it.id }
    val chunkIndexById = sentence.chunks.mapIndexed { index, chunk -> chunk.id to index }.toMap()
    val spans = sentence.clauses.mapNotNull { clauseSpan(sentence, it, clauseById, chunkIndexById) }
    val spanByClauseId = spans.associateBy { it.clause.id }
    val currentDepth = currentChunk?.let { clauseById[it.clauseId]?.depth ?: 0 } ?: 0
    val activeDepth = when {
        safeIndex < 0 -> 0
        safeIndex >= sentence.chunks.lastIndex -> 0
        currentDepth > config.depthCap -> config.depthCap
        else -> spans
            .filter { it.clause.depth in 1..config.depthCap && it.firstIndex <= safeIndex && safeIndex < it.lastIndex }
            .maxOfOrNull { it.clause.depth }
            ?: 0
    }

    if (safeIndex < 0) {
        return PassageProgressState(
            currentChunkIndex = safeIndex,
            activeDepth = activeDepth,
            currentChunk = null,
            displaySegments = emptyList(),
        )
    }

    val hiddenChunkIds = mutableSetOf<String>()
    val display = mutableListOf<PassageProgressSegment>()

    sentence.clauses
        .filter { it.depth > config.depthCap }
        .mapNotNull { capMergeRoot(it, clauseById, config.depthCap) }
        .distinctBy { it.id }
        .forEach { root ->
            val rootSpan = spanByClauseId[root.id] ?: return@forEach
            if (safeIndex < rootSpan.firstIndex) return@forEach
            val host = root.hostChunkId?.let(chunkById::get) ?: return@forEach
            val rangeChunks = (listOf(host) + rootSpan.chunks).distinctBy { it.id }.sortedBy { it.startChar }
            val sourceIndexes = rangeChunks.mapNotNull { chunkIndexById[it.id] }
            hiddenChunkIds += rangeChunks.map { it.id }
            display += PassageProgressSegment(
                id = "${host.id}_cap_merged",
                text = sentence.text.substring(rangeChunks.minOf { it.startChar }, rangeChunks.maxOf { it.endChar }),
                displayRole = host.role,
                startChar = rangeChunks.minOf { it.startChar },
                endChar = rangeChunks.maxOf { it.endChar },
                sourceChunkIds = rangeChunks.map { it.id },
                sourceChunkIndexes = sourceIndexes,
                internalBreaks = rangeChunks.drop(1).map { it.startChar },
                layers = layersForChunk(host, sentence, config),
                phase = if (safeIndex in sourceIndexes) ChunkPhase.Painting else ChunkPhase.Merged,
                opensTrack = false,
            )
        }

    spans
        .filter { it.clause.depth in 1..config.depthCap && it.lastIndex <= safeIndex }
        .sortedBy { it.clause.depth }
        .forEach { span ->
            val role = roleForClause(span.clause, sentence)
            val trackChunks = span.chunks.filterNot { it.role == "connective" }.ifEmpty { span.chunks }
            val startChar = trackChunks.minOf { it.startChar }
            val endChar = trackChunks.maxOf { it.endChar }
            display += PassageProgressSegment(
                id = "${span.clause.id}_surfaced",
                text = sentence.text.substring(startChar, endChar),
                displayRole = span.clause.role,
                startChar = startChar,
                endChar = endChar,
                sourceChunkIds = span.chunks.map { it.id },
                sourceChunkIndexes = span.indexes,
                internalBreaks = trackChunks.drop(1).map { it.startChar },
                layers = layersForClause(span.clause, sentence, config, role),
                phase = ChunkPhase.Merged,
                opensTrack = span.clause.depth in 1..config.depthCap,
            )
            hiddenChunkIds += span.chunks.map { it.id }
        }

    sentence.chunks.forEachIndexed { modifierIndex, modifier ->
        if (modifier.role != "modifier" || modifier.attachesTo == null || safeIndex <= modifierIndex) {
            return@forEachIndexed
        }
        val head = chunkById[modifier.attachesTo] ?: return@forEachIndexed
        if (head.id in hiddenChunkIds || modifier.id in hiddenChunkIds) return@forEachIndexed
        val headIndex = chunkIndexById[head.id] ?: return@forEachIndexed
        val depth = clauseById[modifier.clauseId]?.depth ?: 0
        if (depth > config.depthCap) return@forEachIndexed
        val rangeChunks = listOf(head, modifier).sortedBy { it.startChar }
        hiddenChunkIds += rangeChunks.map { it.id }
        display += PassageProgressSegment(
            id = "${head.id}_${modifier.id}_modifier_merged",
            text = sentence.text.substring(rangeChunks.minOf { it.startChar }, rangeChunks.maxOf { it.endChar }),
            displayRole = "modifier",
            startChar = rangeChunks.minOf { it.startChar },
            endChar = rangeChunks.maxOf { it.endChar },
            sourceChunkIds = rangeChunks.map { it.id },
            sourceChunkIndexes = listOf(headIndex, modifierIndex),
            internalBreaks = listOf(modifier.startChar),
            layers = layersForChunk(head, sentence, config),
            phase = ChunkPhase.Merged,
            opensTrack = false,
        )
    }

    sentence.chunks.forEachIndexed { index, chunk ->
        if (index > safeIndex) return@forEachIndexed
        if (chunk.id in hiddenChunkIds) return@forEachIndexed
        val depth = (clauseById[chunk.clauseId]?.depth ?: 0)
        if (depth > config.depthCap) return@forEachIndexed
        display += PassageProgressSegment(
            id = chunk.id,
            text = chunk.text,
            displayRole = chunk.role,
            startChar = chunk.startChar,
            endChar = chunk.endChar,
            sourceChunkIds = listOf(chunk.id),
            sourceChunkIndexes = listOf(index),
            internalBreaks = emptyList(),
            layers = layersForChunk(
                chunk = chunk,
                sentence = sentence,
                config = config,
                useModifierAccent = chunk.role == "modifier" && index == safeIndex,
            ),
            phase = when {
                index == safeIndex -> ChunkPhase.Painting
                depth == activeDepth -> ChunkPhase.Active
                else -> ChunkPhase.Dimmed
            },
            opensTrack = opensVisibleClauseTrack(
                chunk = chunk,
                sentence = sentence,
                clauseById = clauseById,
                config = config,
            ),
        )
    }

    return PassageProgressState(
        currentChunkIndex = safeIndex,
        activeDepth = activeDepth,
        currentChunk = currentChunk,
        displaySegments = display,
    )
}

private fun clauseSpan(
    sentence: PassageRead2Sentence,
    clause: PassageClause,
    clauseById: Map<String, PassageClause>,
    chunkIndexById: Map<String, Int>,
): ClauseSpan? {
    val chunks = sentence.chunks
        .filter { isDescendantClause(it.clauseId, clause.id, clauseById) }
        .sortedBy { it.startChar }
    if (chunks.isEmpty()) return null
    val indexes = chunks.mapNotNull { chunkIndexById[it.id] }
    if (indexes.isEmpty()) return null
    return ClauseSpan(
        clause = clause,
        chunks = chunks,
        indexes = indexes,
        firstIndex = indexes.min(),
        lastIndex = indexes.max(),
        startChar = chunks.minOf { it.startChar },
        endChar = chunks.maxOf { it.endChar },
        internalBreaks = chunks.drop(1).map { it.startChar },
    )
}

private fun opensVisibleClauseTrack(
    chunk: PassageChunk2,
    sentence: PassageRead2Sentence,
    clauseById: Map<String, PassageClause>,
    config: PassageRenderConfig,
): Boolean {
    val clause = clauseById[chunk.clauseId] ?: return false
    if (clause.depth !in 1..config.depthCap) return false
    if (chunk.role == "connective") return false
    val firstContentChunk = sentence.chunks
        .firstOrNull { it.clauseId == clause.id && it.role != "connective" }
        ?: sentence.chunks.firstOrNull { it.clauseId == clause.id }
    return firstContentChunk?.id == chunk.id
}

private fun layersForChunk(
    chunk: PassageChunk2,
    sentence: PassageRead2Sentence,
    config: PassageRenderConfig,
    useModifierAccent: Boolean = false,
): List<PassageLayer> {
    val clauseById = sentence.clauses.associateBy { it.id }
    val chunkById = sentence.chunks.associateBy { it.id }
    val ancestors = ancestorsForClause(chunk.clauseId, clauseById)
        .filter { it.depth in 1..config.depthCap }
        .map { PassageLayer(roleForClause(it, sentence), it.depth) }
    val chunkDepth = (clauseById[chunk.clauseId]?.depth ?: 0).coerceAtMost(config.depthCap)
    val role = if (useModifierAccent && chunk.role == "modifier") {
        "modifier"
    } else {
        effectiveColorRole(chunk, chunkById)
    }
    return (ancestors + PassageLayer(role, chunkDepth))
        .dedupeAdjacentLayers()
}

private fun layersForClause(
    clause: PassageClause,
    sentence: PassageRead2Sentence,
    config: PassageRenderConfig,
    role: String,
): List<PassageLayer> {
    val clauseById = sentence.clauses.associateBy { it.id }
    val ancestors = ancestorsForClause(clause.id, clauseById)
        .filter { it.depth in 1 until clause.depth && it.depth <= config.depthCap }
        .map { PassageLayer(roleForClause(it, sentence), it.depth) }
    return (ancestors + PassageLayer(role, clause.depth.coerceAtMost(config.depthCap)))
        .dedupeAdjacentLayers()
}

private fun List<PassageLayer>.dedupeAdjacentLayers(): List<PassageLayer> =
    fold(emptyList()) { acc, layer ->
        if (acc.lastOrNull() == layer) acc else acc + layer
    }

private fun ancestorsForClause(clauseId: String, clauseById: Map<String, PassageClause>): List<PassageClause> {
    val result = mutableListOf<PassageClause>()
    var current = clauseById[clauseId]
    while (current != null) {
        result += current
        current = current.parent?.let(clauseById::get)
    }
    return result.asReversed()
}

private fun roleForClause(clause: PassageClause, sentence: PassageRead2Sentence): String {
    if (clause.role != "modifier") return clause.role
    val host = clause.hostChunkId?.let { hostId -> sentence.chunks.firstOrNull { it.id == hostId } }
    return host?.let { effectiveColorRole(it, sentence.chunks.associateBy { chunk -> chunk.id }) } ?: "modifier"
}

private fun PassageChunk2.roleForProgress(sentence: PassageRead2Sentence): String =
    effectiveColorRole(this, sentence.chunks.associateBy { it.id })

private fun PassageChunk2.roleForProgress(progress: PassageProgressState): String =
    progress.displaySegments
        .lastOrNull { id in it.sourceChunkIds }
        ?.layers
        ?.lastOrNull()
        ?.role
        ?: role

@Composable
private fun ChunkProgressInspector(progress: PassageProgressState, totalChunks: Int) {
    val chunk = progress.currentChunk
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (chunk == null) {
            Text(
                "문장을 탭하면 첫 청크부터 왼쪽에서 오른쪽으로 칠해집니다.",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            return@Card
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(colorForRole(chunk.roleForProgress(progress)), CircleShape),
                )
                Text(chunk.role, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("chunk ${progress.currentChunkIndex + 1} / $totalChunks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Text("activeDepth ${progress.activeDepth}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (progress.displaySegments.any { chunk.id in it.sourceChunkIds && it.phase == ChunkPhase.Merged }) {
                    Text("merged", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(chunk.text, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
            Text(
                "range ${chunk.startChar}..${chunk.endChar} · clause ${chunk.clauseId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun RoleLegend(config: PassageRenderConfig) {
    val roles = listOf(
        "subject" to "Subject",
        "verb" to "Verb",
        "object" to "Object",
        "complement" to "Complement",
        "adjunct_time" to "Adjunct",
        "connective" to "Link",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        roles.forEach { (role, label) ->
            Surface(
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(9.dp).background(colorForRole(colorRoleForLegend(role, config)), CircleShape))
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun NavigationRow(
    canGoBack: Boolean,
    nextLabel: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onComplete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onBack,
                enabled = canGoBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("이전")
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
            ) {
                Text(nextLabel)
            }
        }
        TextButton(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
            Text("샘플 확인 완료")
        }
    }
}

private fun parsePassageRead2(params: JsonObject, content: LessonContent): PassageRead2Data {
    val config = parseRenderConfig(params.obj("renderConfig"))
    val sentenceObjects = params.objects("sentences")
    val sentences = if (sentenceObjects.isNotEmpty()) {
        sentenceObjects.mapIndexed { index, obj -> parseSentence(obj, index, config) }
    } else {
        content.passage?.sentences.orEmpty().mapIndexed { index, text ->
            parseSentence(
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("sentence_${index + 1}"),
                        "text" to JsonPrimitive(text),
                        "chunks" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("sentence_${index + 1}_whole"),
                                        "text" to JsonPrimitive(text),
                                        "role" to JsonPrimitive("object"),
                                        "clauseId" to JsonPrimitive("sentence_${index + 1}_cl0"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                index,
                config,
            )
        }
    }

    if (sentences.isEmpty()) {
        throw StepSpecParseException("passage_read2 needs params.sentences or content.passage.sentences")
    }

    return PassageRead2Data(
        title = params.string("title") ?: content.passage?.title ?: "지문독해2",
        subtitle = params.string("subtitle") ?: "chunk role / clause depth sample",
        renderConfig = config,
        sentences = sentences,
    )
}

private fun parseSentence(obj: JsonObject, index: Int, config: PassageRenderConfig): PassageRead2Sentence {
    val id = obj.string("id") ?: "s${index + 1}"
    val text = obj.string("text") ?: throw StepSpecParseException("passage_read2 sentence $id is missing text")
    val clauses = obj.objects("clauses").mapIndexed { clauseIndex, clauseObj ->
        PassageClause(
            id = clauseObj.string("id") ?: "${id}_cl$clauseIndex",
            parent = clauseObj.string("parent"),
            depth = clauseObj.int("depth") ?: 0,
            role = clauseObj.string("role") ?: "main",
            hostChunkId = clauseObj.string("hostChunkId"),
        )
    }.ifEmpty {
        listOf(PassageClause("${id}_cl0", parent = null, depth = 0, role = "main", hostChunkId = null))
    }
    val defaultClauseId = clauses.first().id
    val rawChunks = obj.objects("chunks").mapIndexed { chunkIndex, chunkObj ->
        RawChunk2(
            id = chunkObj.string("id") ?: "${id}_c${chunkIndex + 1}",
            text = chunkObj.string("text")
                ?: throw StepSpecParseException("passage_read2 chunk in $id is missing text"),
            role = chunkObj.string("role") ?: "object",
            clauseId = chunkObj.string("clauseId") ?: defaultClauseId,
            opensClause = chunkObj.string("opensClause"),
            attachesTo = chunkObj.string("attachesTo"),
        )
    }.ifEmpty {
        listOf(RawChunk2("${id}_whole", text, "object", defaultClauseId, null, null))
    }
    val chunks = resolveChunkOffsets(sentenceId = id, sentenceText = text, rawChunks = rawChunks)
    val initial = PassageRead2Sentence(id = id, text = text, clauses = clauses, chunks = chunks, segments = emptyList())
    return initial.copy(segments = buildRenderSegments(initial, config))
}

private data class RawChunk2(
    val id: String,
    val text: String,
    val role: String,
    val clauseId: String,
    val opensClause: String?,
    val attachesTo: String?,
)

private fun resolveChunkOffsets(
    sentenceId: String,
    sentenceText: String,
    rawChunks: List<RawChunk2>,
): List<PassageChunk2> {
    var cursor = 0
    return rawChunks.map { raw ->
        val start = sentenceText.indexOf(raw.text, startIndex = cursor)
        if (start < 0) {
            throw StepSpecParseException(
                "passage_read2 chunk ${raw.id} in $sentenceId does not occur after char $cursor",
            )
        }
        val end = start + raw.text.length
        cursor = end
        PassageChunk2(
            id = raw.id,
            text = raw.text,
            role = raw.role,
            clauseId = raw.clauseId,
            opensClause = raw.opensClause,
            attachesTo = raw.attachesTo,
            startChar = start,
            endChar = end,
        )
    }
}

private fun buildRenderSegments(
    sentence: PassageRead2Sentence,
    config: PassageRenderConfig,
): List<PassageRenderSegment> {
    val clauseById = sentence.clauses.associateBy { it.id }
    val chunkById = sentence.chunks.associateBy { it.id }
    val hiddenChunkIds = mutableSetOf<String>()
    val mergedSegments = mutableListOf<PassageRenderSegment>()

    val capRoots = sentence.clauses
        .filter { it.depth > config.depthCap }
        .mapNotNull { capMergeRoot(it, clauseById, config.depthCap) }
        .distinctBy { it.id }

    capRoots.forEach { root ->
        val host = root.hostChunkId?.let(chunkById::get) ?: return@forEach
        val descendants = sentence.chunks.filter { isDescendantClause(it.clauseId, root.id, clauseById) }
        if (descendants.isEmpty()) return@forEach
        val rangeChunks = (listOf(host) + descendants).distinctBy { it.id }.sortedBy { it.startChar }
        val start = rangeChunks.minOf { it.startChar }
        val end = rangeChunks.maxOf { it.endChar }
        hiddenChunkIds += rangeChunks.map { it.id }
        mergedSegments += PassageRenderSegment(
            id = "${host.id}_merged",
            text = sentence.text.substring(start, end),
            role = effectiveColorRole(host, chunkById),
            displayRole = host.role,
            depth = config.depthCap,
            startChar = start,
            endChar = end,
            sourceChunkIds = rangeChunks.map { it.id },
            internalBreaks = rangeChunks.drop(1).map { it.startChar },
            merged = true,
        )
    }

    val normalSegments = sentence.chunks
        .filterNot { it.id in hiddenChunkIds }
        .map { chunk ->
            val depth = (clauseById[chunk.clauseId]?.depth ?: 0).coerceAtMost(config.depthCap)
            PassageRenderSegment(
                id = chunk.id,
                text = chunk.text,
                role = effectiveColorRole(chunk, chunkById),
                displayRole = chunk.role,
                depth = depth,
                startChar = chunk.startChar,
                endChar = chunk.endChar,
                sourceChunkIds = listOf(chunk.id),
                internalBreaks = emptyList(),
                merged = false,
            )
        }

    return (normalSegments + mergedSegments).sortedWith(compareBy({ it.startChar }, { it.endChar }))
}

private fun capMergeRoot(
    clause: PassageClause,
    clauseById: Map<String, PassageClause>,
    depthCap: Int,
): PassageClause? {
    var current: PassageClause? = clause
    while (current != null && current.depth > depthCap + 1) {
        current = current.parent?.let(clauseById::get)
    }
    return current?.takeIf { it.depth == depthCap + 1 }
}

private fun isDescendantClause(
    clauseId: String,
    rootClauseId: String,
    clauseById: Map<String, PassageClause>,
): Boolean {
    var currentId: String? = clauseId
    while (currentId != null) {
        if (currentId == rootClauseId) return true
        currentId = clauseById[currentId]?.parent
    }
    return false
}

private fun effectiveColorRole(chunk: PassageChunk2, chunkById: Map<String, PassageChunk2>): String {
    if (chunk.role != "modifier") return chunk.role
    val attached = chunk.attachesTo?.let(chunkById::get) ?: return chunk.role
    return effectiveColorRole(attached, chunkById)
}

private fun parseRenderConfig(obj: JsonObject?): PassageRenderConfig {
    val roleHue = obj?.obj("roleHue")?.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.contentOrNull ?: "gray"
    } ?: DefaultRoleHue
    val saturation = obj?.obj("depthSaturation")?.mapNotNull { (key, value) ->
        val depth = key.toIntOrNull() ?: return@mapNotNull null
        val amount = (value as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: return@mapNotNull null
        depth to amount
    }?.toMap() ?: DefaultDepthSaturation
    return PassageRenderConfig(
        roleHue = roleHue,
        depthSaturation = saturation,
        depthCap = obj?.obj("depthSaturation")?.let { obj.int("depthCap") } ?: obj?.int("depthCap") ?: 2,
    )
}

private fun colorRoleForLegend(role: String, config: PassageRenderConfig): String {
    val hue = config.roleHue[role] ?: when {
        role.startsWith("adjunct") -> "green"
        else -> role
    }
    return if (hue == "inherit") role else hue
}

private fun colorForRole(role: String): Color {
    val hue = when {
        role.startsWith("adjunct") -> "green"
        role == "subject" -> "red"
        role == "verb" -> "orange"
        role == "object" -> "yellow"
        role == "complement" -> "teal"
        role == "connective" -> "gray"
        role == "modifier" -> "blue"
        else -> role
    }
    return when (hue) {
        "red" -> Color(0xFFE65F6F)
        "orange" -> Color(0xFFF28C38)
        "yellow" -> Color(0xFFE2B63D)
        "teal" -> Color(0xFF2EAD9E)
        "green" -> Color(0xFF4FA66A)
        "gray" -> Color(0xFF8A929D)
        "blue" -> Color(0xFF5F7FE6)
        else -> Color(0xFF5F7FE6)
    }
}

private val DefaultRoleHue = mapOf(
    "subject" to "red",
    "verb" to "orange",
    "object" to "yellow",
    "complement" to "teal",
    "adjunct_time" to "green",
    "adjunct_place" to "green",
    "adjunct_reason" to "green",
    "adjunct_purpose" to "green",
    "adjunct_manner" to "green",
    "adjunct_topic" to "green",
    "modifier" to "inherit",
    "connective" to "gray",
)

private val DefaultDepthSaturation = mapOf(0 to 1.0f, 1 to 0.6f, 2 to 0.35f)

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

@Module
@InstallIn(SingletonComponent::class)
interface PassageRead2BindModule {
    @Binds
    @IntoMap
    @StringKey("passage_read2")
    fun bind(impl: PassageRead2Feature): StepFeature
}

private fun previewData(): PassageRead2Data {
    val config = PassageRenderConfig(DefaultRoleHue, DefaultDepthSaturation, depthCap = 2)
    val sentence = parseSentence(
        JsonObject(
            mapOf(
                "id" to JsonPrimitive("preview"),
                "text" to JsonPrimitive("Ancient writers recorded that the king announced that the dyers had finally found a snail that produced a dye which glowed in the dark."),
                "clauses" to JsonArray(
                    listOf(
                        JsonObject(mapOf("id" to JsonPrimitive("cl0"), "depth" to JsonPrimitive(0))),
                        JsonObject(mapOf("id" to JsonPrimitive("cl1"), "parent" to JsonPrimitive("cl0"), "depth" to JsonPrimitive(1), "hostChunkId" to JsonPrimitive("c2"))),
                        JsonObject(mapOf("id" to JsonPrimitive("cl2"), "parent" to JsonPrimitive("cl1"), "depth" to JsonPrimitive(2), "hostChunkId" to JsonPrimitive("c5"))),
                        JsonObject(mapOf("id" to JsonPrimitive("cl3"), "parent" to JsonPrimitive("cl2"), "depth" to JsonPrimitive(3), "hostChunkId" to JsonPrimitive("c9"))),
                    ),
                ),
                "chunks" to JsonArray(
                    listOf(
                        chunkObj("c1", "Ancient writers", "subject", "cl0"),
                        chunkObj("c2", "recorded", "verb", "cl0"),
                        chunkObj("c3", "that", "connective", "cl1"),
                        chunkObj("c4", "the king", "subject", "cl1"),
                        chunkObj("c5", "announced", "verb", "cl1"),
                        chunkObj("c6", "that", "connective", "cl2"),
                        chunkObj("c7", "the dyers", "subject", "cl2"),
                        chunkObj("c8", "had finally found", "verb", "cl2"),
                        chunkObj("c9", "a snail", "object", "cl2"),
                        chunkObj("c10", "that", "connective", "cl3"),
                        chunkObj("c11", "produced", "verb", "cl3"),
                        chunkObj("c12", "a dye", "object", "cl3"),
                        chunkObj("c13", "which", "connective", "cl3"),
                        chunkObj("c14", "glowed", "verb", "cl3"),
                        chunkObj("c15", "in the dark.", "adjunct_place", "cl3"),
                    ),
                ),
            ),
        ),
        index = 0,
        config = config,
    )
    return PassageRead2Data(
        title = "Tyrian Purple",
        subtitle = "preview",
        renderConfig = config,
        sentences = listOf(sentence),
    )
}

private fun chunkObj(id: String, text: String, role: String, clauseId: String): JsonObject =
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "text" to JsonPrimitive(text),
            "role" to JsonPrimitive(role),
            "clauseId" to JsonPrimitive(clauseId),
        ),
    )

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun PassageRead2Preview() {
    PassageRead2Feature().StudentScreen(
        spec = PassageRead2Spec("preview", previewData()),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
