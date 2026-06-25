package com.seoin.emojienglish.steps.passageread3

import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
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
import com.seoin.emojienglish.step.stringList
import com.seoin.emojienglish.step.timeOrderedActivities
import com.seoin.emojienglish.voice.AuthoringWebGateway
import com.seoin.emojienglish.voice.StepPromptKind
import com.seoin.emojienglish.voice.VoicePrompt
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------
// Data models
// ------------------------------------------------------------------

data class PassageRead3Spec(
    override val stepId: String,
    val data: PassageRead3Data,
) : StepSpec

data class PassageRead3Data(
    val passageId: String,
    val title: String,
    val reefDensity: Float,
    val sentences: List<PassageRead3Sentence>,
    val reefs: List<Reef>,
)

data class PassageRead3Sentence(
    val index: Int,
    val text: String,
    val maxDepth: Int,
    val reefIds: List<String>,
)

data class Reef(
    val id: String,
    val sentenceIndex: Int,
    val anchorStart: Int,
    val anchorEnd: Int,
    val type: String,
    val difficulty: Int,
    val payload: ReefPayload,
)

sealed interface ReefPayload
data class Nametag(val answer: String, val reassure: String) : ReefPayload
data class Detective(
    val blankSentence: String,
    val options: List<String>,
    val answerIndex: Int,
    val hint: String,
    val meaning: String,
) : ReefPayload
data class Skeleton(val chunks: List<SkChunk>, val questions: List<SkQuestion>) : ReefPayload
/** A word the child underlined themselves during reading (§3) — its problem is generated by GPT voice (§4). */
data class UserWord(val word: String, val sentence: String) : ReefPayload
data class SkChunk(
    val id: String,
    val text: String,
    val depth: Int,
    val role: String,
    val attachTo: String? = null,
    val containsReefs: List<String> = emptyList(),
)
data class SkQuestion(val q: String, val depth: Int, val answer: String, val attachTo: String? = null)

private data class ReefRunState(
    val attempted: Boolean = false,
    val firstChoiceLabel: String? = null,
    val correct: Boolean? = null,
)

private enum class Phase { Reading, Solving, Review }
private enum class ReadingMarkKind { BlueUnderline, OrangeWave }

private data class PassageOverlayInfo(
    val passageTopPx: Int = 0,
    val passageHeightPx: Int = 0,
    val currentSentenceCenterPx: Int = 0,
    val currentSentenceTopPx: Int = 0,
    val currentSentenceBottomPx: Int = 0,
    val currentSentenceLineHeightPx: Int = 0,
    val activeSentenceTopPx: Int = 0,
    val activeSentenceBottomPx: Int = 0,
)

private val PassagePaperColor = Color(0xFFFBF7EE)
private val PassageEdgeColor = Color(0xFFF0E9D8)
private val PassageRuleColor = Color(0xFFEDE4D2)
private val PassageMarginColor = Color(0xFFF2C9C2)
private val PassagePrintColor = Color(0xFF2A2620)
private val PassagePrintDimColor = Color(0xFFC3B9A2)
private val PassageChromeColor = Color(0xFF26514E)
private val PassagePencilColor = Color(0xFF7C7363)
private val PassageYellowMark = Color(0xFFFFE48F)
private val PassageBlueMark = Color(0xFFDBE8F8)
private val PassageWhoColor = Color(0xFF2F73C4)
private val PassageWhoBg = Color(0xFFDBE8F8)
private val PassageDidColor = Color(0xFFE07B2A)
private val PassageDidBg = Color(0xFFFBE7D0)
private val PassageWhatColor = Color(0xFF1F9E83)
private val PassageWhatBg = Color(0xFFD2EEE6)
private val PassageDepthColor = Color(0xFF8A6FB0)
private val PassageHighlightWashColor = Color(0xFFFFF3BF)

private data class ReadingMark(
    val range: IntRange,
    val kind: ReadingMarkKind,
)

/** Whole passage laid out as one flowing string + the global char range of each sentence. */
private data class RenderModel(val text: String, val sentenceRanges: List<IntRange>)

private fun buildRenderModel(sentences: List<PassageRead3Sentence>): RenderModel {
    val sb = StringBuilder()
    val ranges = ArrayList<IntRange>(sentences.size)
    sentences.forEachIndexed { i, s ->
        if (i > 0) sb.append(' ')
        val start = sb.length
        sb.append(s.text)
        ranges.add(start until sb.length)
    }
    return RenderModel(sb.toString(), ranges)
}

/** Map a reef's per-sentence anchor to a global char range in the flowing passage. */
private fun Reef.globalRange(model: RenderModel): IntRange {
    val base = model.sentenceRanges.getOrNull(sentenceIndex)?.first ?: 0
    val len = model.text.length
    val start = (base + anchorStart).coerceIn(0, len)
    val end = (base + anchorEnd).coerceIn(start, len)
    return start until end
}

/** Global [start, end) of the whitespace-delimited word at [offset], plus its sentence index. */
private fun wordSpanAt(model: RenderModel, offset: Int): Triple<Int, Int, Int>? {
    val text = model.text
    if (text.isEmpty()) return null
    val o = offset.coerceIn(0, text.length - 1)
    if (text[o].isWhitespace()) return null
    var s = o
    while (s > 0 && !text[s - 1].isWhitespace()) s--
    var e = o + 1
    while (e < text.length && !text[e].isWhitespace()) e++
    // trim surrounding punctuation/quotes so the underlined token is a clean word
    val trim = { c: Char -> !c.isLetterOrDigit() }
    while (s < e && trim(text[s])) s++
    while (e > s && trim(text[e - 1])) e--
    if (e <= s) return null
    val sIdx = model.sentenceRanges.indexOfFirst { s in it }
    if (sIdx < 0) return null
    return Triple(s, e, sIdx)
}

private fun wordRangeAt(model: RenderModel, offset: Int): IntRange? {
    val (start, end, _) = wordSpanAt(model, offset) ?: return null
    return start until end
}

private fun readingMarkRange(model: RenderModel, sentenceIndex: Int, startOffset: Int, endOffset: Int): IntRange? {
    val sentenceRange = model.sentenceRanges.getOrNull(sentenceIndex) ?: return null
    val rawStart = min(startOffset, endOffset).coerceIn(sentenceRange.first, sentenceRange.last)
    val rawEnd = max(startOffset, endOffset).coerceIn(sentenceRange.first, sentenceRange.last)
    val firstWord = nearestWordRangeInSentence(model, rawStart, sentenceRange, searchForward = true)
        ?: nearestWordRangeInSentence(model, rawStart, sentenceRange, searchForward = false)
        ?: return null
    val lastWord = nearestWordRangeInSentence(model, rawEnd, sentenceRange, searchForward = false)
        ?: nearestWordRangeInSentence(model, rawEnd, sentenceRange, searchForward = true)
        ?: return null
    val start = min(firstWord.first, lastWord.first)
    val endExclusive = max(firstWord.last + 1, lastWord.last + 1)
    return start until endExclusive
}

private fun sentenceWordRange(model: RenderModel, sentenceIndex: Int): IntRange? {
    val sentenceRange = model.sentenceRanges.getOrNull(sentenceIndex) ?: return null
    val firstWord = nearestWordRangeInSentence(model, sentenceRange.first, sentenceRange, searchForward = true)
        ?: return null
    val lastWord = nearestWordRangeInSentence(model, sentenceRange.last, sentenceRange, searchForward = false)
        ?: return null
    return firstWord.first until (lastWord.last + 1)
}

private fun isWholeSentenceMark(model: RenderModel, sentenceIndex: Int, range: IntRange): Boolean {
    val whole = sentenceWordRange(model, sentenceIndex) ?: return false
    return range.first <= whole.first && range.last >= whole.last
}

private fun nearestWordRangeInSentence(
    model: RenderModel,
    offset: Int,
    sentenceRange: IntRange,
    searchForward: Boolean,
): IntRange? {
    var probe = offset.coerceIn(sentenceRange.first, sentenceRange.last)
    while (probe in sentenceRange) {
        wordRangeAt(model, probe)?.let { range ->
            if (range.first in sentenceRange && range.last in sentenceRange) return range
        }
        probe += if (searchForward) 1 else -1
    }
    return null
}

private fun skeletonChunkRanges(reef: Reef, skeleton: Skeleton, model: RenderModel): Map<String, IntRange> {
    val sentenceRange = model.sentenceRanges.getOrNull(reef.sentenceIndex) ?: return emptyMap()
    val sentenceText = model.text.substring(sentenceRange.first, sentenceRange.last + 1)
    return skeleton.chunks.mapNotNull { chunk ->
        val local = sentenceText.indexOf(chunk.text)
            .takeIf { it >= 0 }
            ?: sentenceText.indexOf(chunk.text, ignoreCase = true).takeIf { it >= 0 }
            ?: return@mapNotNull null
        chunk.id to (sentenceRange.first + local until sentenceRange.first + local + chunk.text.length)
    }.toMap()
}

// ------------------------------------------------------------------
// Feature implementation
// ------------------------------------------------------------------

class PassageRead3Feature @Inject constructor(
    private val authoringGateway: AuthoringWebGateway?,
) : StepFeature {

    override val type: String = "passage_read3"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        PassageRead3Spec(stepId = stepJson.stepId(), data = parsePassageRead3(stepJson.params(), content))

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val data = (spec as PassageRead3Spec).data

        val savedResult by session.savedResult.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        var redo by remember { mutableStateOf(false) }
        if (savedResult is StepResult.Scored && !redo) {
            RestoreView(savedResult as StepResult.Scored, onReset = { redo = true })
            return
        }

        val model = remember(data) { buildRenderModel(data.sentences) }

        var phase by remember { mutableStateOf(Phase.Reading) }
        var currentSentence by remember { mutableIntStateOf(0) }
        var currentReef by remember { mutableIntStateOf(0) }
        val underlinedReefIds = remember { mutableStateListOf<String>() } // curated reefs the child underlined
        val userReefs = remember { mutableStateListOf<Reef>() }           // words the child underlined themselves
        val readingMarks = remember { mutableStateListOf<ReadingMark>() }
        val reefStates = remember { mutableStateMapOf<String, ReefRunState>() }
        var parentMode by remember { mutableStateOf<Boolean?>(null) }
        var firstGenerationRequested by remember { mutableStateOf(false) }
        var secondGenerationRequested by remember { mutableStateOf(false) }
        var reefGenerationStatus by remember { mutableStateOf<String?>(null) }
        var overlayInfo by remember { mutableStateOf(PassageOverlayInfo()) }
        var rootHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current

        // curated reefs + child-underlined reefs, ordered through the passage
        val solveReefs = remember(userReefs.toList()) {
            (data.reefs + userReefs).sortedWith(compareBy({ it.sentenceIndex }, { it.anchorStart }))
        }
        val reefGlobals = remember(userReefs.toList()) {
            (data.reefs + userReefs).associate { it.id to it.globalRange(model) }
        }
        val currentReadingMarks = remember(readingMarks.toList()) { readingMarks.toList() }
        val activeReef = solveReefs.getOrNull(currentReef)?.takeIf { phase != Phase.Reading }
        val skeletonReef = activeReef?.takeIf { it.type == "skeleton" }
        val skeleton = skeletonReef?.payload as? Skeleton
        val skeletonAnswers = remember(skeletonReef?.id) { mutableStateMapOf<String, String>() }
        var skeletonQuestionIndex by remember(skeletonReef?.id) { mutableIntStateOf(0) }
        val skeletonQuestion = skeleton?.questions?.getOrNull(skeletonQuestionIndex)

        fun answerReef(reef: Reef, label: String, correct: Boolean) {
            reefStates[reef.id] = ReefRunState(attempted = true, firstChoiceLabel = label, correct = correct)
            session.trace(
                "pr3_reef_attempt",
                mapOf(
                    "reef" to reef.id, "type" to reef.type, "choice" to label,
                    "correct" to correct.toString(), "answer" to reef.answerText(),
                ),
            )
            currentReef++
        }

        // Generate a problem for a child-underlined word via the GPT voice channel (§4).
        fun askVoice(reef: Reef) {
            val word = (reef.payload as? UserWord)?.word ?: model.text.substring(reef.globalRange(model).first, reef.globalRange(model).last + 1)
            session.requestVoice(
                VoicePrompt(
                    templateId = "pr3_word",
                    kind = StepPromptKind.QUIZ_VOCAB,
                    payload = word,
                    contextLabel = "내가 표시한 단어 · $word",
                ),
            )
            session.trace("pr3_generate_problem", mapOf("reef" to reef.id, "word" to word))
        }

        fun requestReefGeneration(stage: String, upToSentence: Int) {
            val gateway = authoringGateway
            val selectedReefs = underlinedReefIds.mapNotNull { id -> data.reefs.firstOrNull { it.id == id } }
            val payload = buildReefGenerationPromptPayload(
                data = data,
                selectedReefs = selectedReefs,
                readingMarks = readingMarks,
                upToSentence = upToSentence,
                stage = stage,
            )
            session.trace(
                if (stage == "first") "pr3_gen_first_request" else "pr3_gen_second_request",
                mapOf(
                    "upToSentence" to upToSentence.toString(),
                    "marks" to readingMarks.size.toString(),
                ),
            )
            if (gateway == null) {
                reefGenerationStatus = "암초 생성 WebView가 연결되지 않았어요."
                session.trace("pr3_gen_unavailable", mapOf("stage" to stage))
                return
            }
            coroutineScope.launch {
                reefGenerationStatus = if (stage == "first") "암초 JSON 요청 중..." else "마지막 암초 JSON 요청 중..."
                runCatching {
                    gateway.provideView()
                    gateway.selectInstantMode()
                    val before = gateway.assistantMarker()
                    if (!gateway.sendOnly(payload)) {
                        error(gateway.lastQueryFailureMessage("암초 JSON 요청"))
                    }
                    reefGenerationStatus = "암초 JSON 받는 중..."
                    val jsonText = awaitGeneratedReefJson(gateway, before)
                    val imported = parseGeneratedReefs(jsonText, data, stage)
                    val existingIds = (data.reefs + userReefs).map { it.id }.toMutableSet()
                    val existingKeys = (data.reefs + userReefs).map {
                        "${it.sentenceIndex}:${it.anchorStart}:${it.anchorEnd}:${it.type}"
                    }.toMutableSet()
                    val fresh = imported.filter { reef ->
                        val key = "${reef.sentenceIndex}:${reef.anchorStart}:${reef.anchorEnd}:${reef.type}"
                        reef.id !in existingIds && key !in existingKeys
                    }
                    userReefs.addAll(fresh)
                    reefGenerationStatus = if (fresh.isEmpty()) "새 암초 없음" else "암초 ${fresh.size}개 추가됨"
                    session.trace(
                        if (stage == "first") "pr3_gen_first_import" else "pr3_gen_second_import",
                        mapOf(
                            "received" to imported.size.toString(),
                            "imported" to fresh.size.toString(),
                        ),
                    )
                }.onFailure { e ->
                    reefGenerationStatus = "암초 생성 실패: ${e.message ?: "응답 확인 필요"}"
                    session.trace(
                        if (stage == "first") "pr3_gen_first_failed" else "pr3_gen_second_failed",
                        mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())),
                    )
                }
            }
        }

        fun addMergedBlueMark(range: IntRange) {
            var merged = range
            var changed: Boolean
            do {
                changed = false
                val index = readingMarks.indexOfFirst { mark ->
                    mark.kind == ReadingMarkKind.BlueUnderline &&
                        mark.range.first <= merged.last + 1 &&
                        merged.first <= mark.range.last + 1
                }
                if (index >= 0) {
                    val old = readingMarks.removeAt(index)
                    merged = min(old.range.first, merged.first)..max(old.range.last, merged.last)
                    changed = true
                }
            } while (changed)
            val mark = ReadingMark(merged, ReadingMarkKind.BlueUnderline)
            if (mark !in readingMarks) readingMarks.add(mark)
        }

        fun onReadingMark(range: IntRange) {
            if (phase != Phase.Reading) return
            val sIdx = model.sentenceRanges.indexOfFirst { range.first in it }
            if (sIdx != currentSentence || range.isEmpty()) return
            val kind = if (isWholeSentenceMark(model, sIdx, range)) ReadingMarkKind.OrangeWave else ReadingMarkKind.BlueUnderline
            val markRange = if (kind == ReadingMarkKind.OrangeWave) sentenceWordRange(model, sIdx) ?: range else range
            if (kind == ReadingMarkKind.OrangeWave) {
                val mark = ReadingMark(markRange, kind)
                if (mark !in readingMarks) readingMarks.add(mark)
            } else {
                addMergedBlueMark(markRange)
            }
            val pre = data.reefs.firstOrNull { r ->
                val g = r.globalRange(model)
                markRange.first < g.last + 1 && g.first < markRange.last + 1
            }
            if (pre != null && pre.id !in underlinedReefIds) {
                underlinedReefIds.add(pre.id)
            }
            session.trace(
                "pr3_mark_range",
                mapOf(
                    "kind" to kind.name,
                    "sentence" to sIdx.toString(),
                    "start" to markRange.first.toString(),
                    "end" to (markRange.last + 1).toString(),
                    "text" to model.text.substring(markRange.first, markRange.last + 1),
                ),
            )
        }

        fun finishReading() {
            if (!secondGenerationRequested) {
                secondGenerationRequested = true
                requestReefGeneration(stage = "second", upToSentence = data.sentences.lastIndex)
            }
            session.trace("pr3_read_done", mapOf("marked" to readingMarks.size.toString()))
            phase = Phase.Solving
        }

        fun goPreviousSentence() {
            if (phase == Phase.Reading && currentSentence > 0) currentSentence--
        }

        fun goNextSentence() {
            if (phase != Phase.Reading) return
            if (currentSentence < data.sentences.lastIndex) {
                currentSentence++
            } else {
                finishReading()
            }
        }

        fun completeReview() {
            val correctN = reefStates.count { it.value.correct == true }
            session.complete(
                StepResult.Scored(
                    selected = reefStates.entries.joinToString(",") { "${it.key}:${it.value.firstChoiceLabel ?: "-"}" },
                    answer = solveReefs.joinToString(",") { "${it.id}:${it.answerText()}" },
                    score = correctN,
                    maxScore = solveReefs.size,
                ),
            )
        }

        if (skeletonReef != null && skeleton != null && skeletonQuestion == null) {
            LaunchedEffect(skeletonReef.id, skeletonQuestionIndex) {
                val allCorrect = skeleton.questions.all { skeletonAnswers[it.q] == it.answer }
                answerReef(
                    skeletonReef,
                    skeleton.questions.joinToString(",") { "${it.q}:${skeletonAnswers[it.q] ?: ""}" },
                    allCorrect,
                )
            }
        }

        LaunchedEffect(phase, currentSentence, firstGenerationRequested) {
            val halfwayIndex = (data.sentences.size / 2).coerceAtLeast(1)
            if (phase == Phase.Reading && !firstGenerationRequested && currentSentence >= halfwayIndex) {
                firstGenerationRequested = true
                requestReefGeneration(stage = "first", upToSentence = currentSentence)
            }
        }

        val screenScrollState = rememberScrollState()

        Box(
            modifier
                .fillMaxSize()
                .onSizeChanged { rootHeightPx = it.height },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(screenScrollState)
                    .padding(top = 0.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // The passage is ALWAYS rendered, as flowing book-like text.
                PassageCanvas(
                    model = model,
                    reefGlobals = reefGlobals,
                    solveReefs = solveReefs,
                    phase = phase,
                    currentSentence = currentSentence,
                    currentReef = currentReef,
                    activeReef = activeReef,
                    skeletonQuestionIndex = skeletonQuestionIndex,
                    skeletonAnswers = skeletonAnswers,
                    readingMarks = currentReadingMarks,
                    reefStates = reefStates,
                    onReadingMark = { range -> onReadingMark(range) },
                    onSkeletonChunkChosen = { chunkId ->
                        val question = skeletonQuestion
                        if (question != null) {
                            skeletonAnswers[question.q] = chunkId
                            skeletonQuestionIndex++
                        }
                    },
                    onOverlayInfoChange = { overlayInfo = it },
                )
                authoringGateway?.let { gateway ->
                    HiddenAuthoringWebViewHost(gateway)
                }
            }
            if (
                phase == Phase.Reading &&
                rootHeightPx > 0 &&
                overlayInfo.currentSentenceBottomPx > overlayInfo.currentSentenceTopPx
            ) {
                ReadingNavigationOverlay(
                    rootHeightPx = rootHeightPx,
                    currentSentenceTopPx = overlayInfo.currentSentenceTopPx,
                    currentSentenceBottomPx = overlayInfo.currentSentenceBottomPx,
                    currentSentenceLineHeightPx = overlayInfo.currentSentenceLineHeightPx,
                    onPrevious = { goPreviousSentence() },
                    onNext = { goNextSentence() },
                )
            }
            PassageScrollDragBar(
                scrollState = screenScrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 0.dp)
                    .width(30.dp)
                    .height(238.dp),
            )
            val popupGapPx = with(density) { 68.dp.roundToPx() }
            val popupHeightPx = with(density) { 230.dp.roundToPx() }
            val popupTopPaddingPx = with(density) { 10.dp.roundToPx() }
            val popupBottomPaddingPx = with(density) { 12.dp.roundToPx() }
            val belowTop = overlayInfo.activeSentenceBottomPx + popupGapPx
            val aboveTop = overlayInfo.activeSentenceTopPx - popupGapPx - popupHeightPx
            val popupTopPx = when {
                rootHeightPx <= 0 -> popupTopPaddingPx
                belowTop + popupHeightPx <= rootHeightPx - popupBottomPaddingPx -> belowTop
                else -> aboveTop.coerceIn(popupTopPaddingPx, (rootHeightPx - popupHeightPx - popupBottomPaddingPx).coerceAtLeast(popupTopPaddingPx))
            }
            val popupModifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, popupTopPx) }
                .padding(horizontal = 12.dp)
            if (phase == Phase.Solving && activeReef != null) {
                QuizOverlay(
                    reef = activeReef,
                    skeleton = skeleton,
                    skeletonQuestionIndex = skeletonQuestionIndex,
                    skeletonAnswers = skeletonAnswers,
                    modifier = popupModifier,
                    onAnswer = { label, correct -> answerReef(activeReef, label, correct) },
                    onAskVoice = { askVoice(activeReef) },
                )
            }
            if (phase == Phase.Solving && currentReef >= solveReefs.size) {
                PopupCard(popupModifier) {
                    BranchSelection(
                        onParent = { parentMode = true; phase = Phase.Review; currentReef = 0 },
                        onSelf = { parentMode = false; phase = Phase.Review; currentReef = 0 },
                    )
                }
            }
            if (phase == Phase.Review) {
                PopupCard(popupModifier) {
                    ReviewControls(
                        reefs = solveReefs,
                        currentReef = currentReef,
                        reefStates = reefStates,
                        parentMode = parentMode == true,
                        onNext = { currentReef++ },
                        onFinish = { completeReview() },
                    )
                }
            }
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "암초독해 — 기록",
            resultLines = trace.resultSummaries(),
            activities = trace.timeOrderedActivities().map {
                MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail)
            },
            modifier = modifier,
        )
    }
}

private fun Reef.answerText(): String = when (val p = payload) {
    is Nametag -> p.answer
    is Detective -> p.options.getOrNull(p.answerIndex) ?: ""
    is Skeleton -> p.questions.joinToString(",") { "${it.q}:${it.answer}" }
    is UserWord -> p.word
}

private val PassageRead3Json = Json { ignoreUnknownKeys = true; isLenient = true }
private const val PR3_REEF_JSON_ATTEMPTS = 40
private const val PR3_REEF_JSON_FIRST_DELAY_MS = 4_000L
private const val PR3_REEF_JSON_RETRY_DELAY_MS = 3_000L

private suspend fun awaitGeneratedReefJson(
    gateway: AuthoringWebGateway,
    before: AuthoringWebGateway.AssistantMarker,
): String {
    val requiredCount = before.count + 1
    for (attempt in 1..PR3_REEF_JSON_ATTEMPTS) {
        delay(if (attempt == 1) PR3_REEF_JSON_FIRST_DELAY_MS else PR3_REEF_JSON_RETRY_DELAY_MS)
        val captured = gateway.captureLastAssistantJsonChunked(requiredCount, before) ?: continue
        val jsonText = extractBalancedJson(captured) ?: captured.trim()
        if (looksLikeGeneratedReefs(jsonText)) return jsonText
    }
    error("JSON 응답을 아직 찾지 못했어요.")
}

private fun looksLikeGeneratedReefs(jsonText: String): Boolean = runCatching {
    val root = PassageRead3Json.parseToJsonElement(jsonText)
    when (root) {
        is JsonArray -> root.isNotEmpty()
        is JsonObject -> (root["reefs"] as? JsonArray)?.isNotEmpty() == true
        else -> false
    }
}.getOrDefault(false)

private fun parseGeneratedReefs(jsonText: String, data: PassageRead3Data, stage: String): List<Reef> {
    val root = PassageRead3Json.parseToJsonElement(jsonText)
    val arr = when (root) {
        is JsonArray -> root
        is JsonObject -> root["reefs"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        runCatching {
            val type = obj.string("type") ?: return@runCatching null
            if (type !in setOf("nametag", "detective", "skeleton")) return@runCatching null
            val sentenceIndex = (obj["sentenceIndex"] as? JsonPrimitive)?.intOrNull ?: return@runCatching null
            val sentence = data.sentences.getOrNull(sentenceIndex)?.text ?: return@runCatching null
            val start = (obj["anchorStart"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, sentence.length)
                ?: return@runCatching null
            val end = (obj["anchorEnd"] as? JsonPrimitive)?.intOrNull?.coerceIn(start, sentence.length)
                ?: return@runCatching null
            if (end <= start) return@runCatching null
            val rawId = obj.string("id")?.takeIf { it.isNotBlank() } ?: "pr3_${stage}_${sentenceIndex}_$index"
            Reef(
                id = rawId,
                sentenceIndex = sentenceIndex,
                anchorStart = start,
                anchorEnd = end,
                type = type,
                difficulty = (obj["difficulty"] as? JsonPrimitive)?.intOrNull ?: 2,
                payload = parsePayload(type, obj),
            )
        }.getOrNull()
    }
}

private fun buildReefGenerationPromptPayload(
    data: PassageRead3Data,
    selectedReefs: List<Reef>,
    readingMarks: List<ReadingMark>,
    upToSentence: Int,
    stage: String,
): String {
    val model = buildRenderModel(data.sentences)
    val readSentences = data.sentences
        .filter { it.index <= upToSentence }
        .joinToString("\n") { "${it.index}. ${it.text}" }
    val selectedWords = selectedReefs
        .distinctBy { it.id }
        .joinToString(",\n") { reef ->
            val sentence = data.sentences.getOrNull(reef.sentenceIndex)?.text.orEmpty()
            val term = sentence.substring(
                reef.anchorStart.coerceIn(0, sentence.length),
                reef.anchorEnd.coerceIn(reef.anchorStart.coerceIn(0, sentence.length), sentence.length),
            )
            """    {"id":"${reef.id.jsonEscaped()}","sentenceIndex":${reef.sentenceIndex},"text":"${term.jsonEscaped()}","typeHint":"${reef.type.jsonEscaped()}"}"""
        }
        .ifBlank { "    " }
    val markedRanges = readingMarks
        .distinct()
        .joinToString(",\n") { mark ->
            val globalStart = mark.range.first
            val globalEnd = mark.range.last + 1
            val sentenceIndex = model.sentenceRanges.indexOfFirst { globalStart in it }
            val sentenceRange = model.sentenceRanges.getOrNull(sentenceIndex)
            val sentence = data.sentences.getOrNull(sentenceIndex)?.text.orEmpty()
            val anchorStart = sentenceRange?.let { (globalStart - it.first).coerceIn(0, sentence.length) } ?: 0
            val anchorEnd = sentenceRange?.let { (globalEnd - it.first).coerceIn(anchorStart, sentence.length) } ?: anchorStart
            val text = if (globalStart in 0 until globalEnd && globalEnd <= model.text.length) {
                model.text.substring(globalStart, globalEnd)
            } else {
                ""
            }
            """    {"kind":"${mark.kind.name.lowercase()}","sentenceIndex":$sentenceIndex,"anchorStart":$anchorStart,"anchorEnd":$anchorEnd,"text":"${text.jsonEscaped()}"}"""
        }
        .ifBlank { "    " }

    return """
        7세 한국 아동이 영어 지문을 읽다가 표시한 암초 후보를 바탕으로, 추가 암초 JSON만 만들어줘.
        설명, 머리말, 마크다운 없이 JSON 객체 하나만 출력해.
        정답은 payload 안에 넣되, 아이에게는 나중에만 보여줄 예정이야.
        문법 용어는 쓰지 말고, skeleton은 D0/D1 두 단계까지만 만들어.

        stage: "$stage"
        passageId: "${data.passageId.jsonEscaped()}"
        읽은 문장:
        $readSentences

        아이가 표시한 범위(색 종류는 겹친 범위를 구별하기 위한 것):
        [
        $markedRanges
        ]

        표시 범위가 기존 암초와 겹친 후보:
        [
        $selectedWords
        ]

        출력 스키마:
        {
          "passageId": "${data.passageId.jsonEscaped()}",
          "reefs": [
            {
              "id": "generated_unique_id",
              "sentenceIndex": 0,
              "anchorStart": 0,
              "anchorEnd": 0,
              "type": "nametag|detective|skeleton",
              "difficulty": 1,
              "nametag": {"answer": "person|place|name", "reassure": "짧은 안심 멘트"},
              "detective": {"blankSentence": "...", "options": ["...", "...", "..."], "answerIndex": 0, "hint": "한 문장 힌트", "meaning": "뜻"},
              "skeleton": {"chunks": [], "questions": []}
            }
          ]
        }
    """.trimIndent()
}

private fun String.jsonEscaped(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")

private fun extractBalancedJson(text: String): String? {
    val stripped = text.replace("```json", "```").let {
        val fenced = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)
        (fenced ?: it).trim()
    }
    val start = stripped.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    val stack = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    for (i in start until stripped.length) {
        val ch = stripped[i]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> stack.addLast('}')
            '[' -> stack.addLast(']')
            '}', ']' -> {
                if (stack.isEmpty() || stack.removeLast() != ch) return null
                if (stack.isEmpty()) return stripped.substring(start, i + 1)
            }
        }
    }
    return null
}

@Composable
private fun HiddenAuthoringWebViewHost(gateway: AuthoringWebGateway) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(1.dp),
        factory = {
            val webView = gateway.provideView()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
    )
}

@Composable
private fun ReadingNavigationOverlay(
    rootHeightPx: Int,
    currentSentenceTopPx: Int,
    currentSentenceBottomPx: Int,
    currentSentenceLineHeightPx: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val density = LocalDensity.current
    val drawSafeMarginPx = (currentSentenceLineHeightPx / 2).coerceAtLeast(with(density) { 8.dp.roundToPx() })
    val topHeightPx = (currentSentenceTopPx - drawSafeMarginPx).coerceIn(0, rootHeightPx)
    val bottomTopPx = (currentSentenceBottomPx + drawSafeMarginPx).coerceIn(0, rootHeightPx)
    val bottomHeightPx = (rootHeightPx - bottomTopPx).coerceAtLeast(0)
    Box(Modifier.fillMaxSize()) {
        if (topHeightPx > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { topHeightPx.toDp() })
                    .pointerInput(currentSentenceTopPx) {
                        detectTapGestures { onPrevious() }
                    },
            )
        }
        if (bottomHeightPx > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { bottomHeightPx.toDp() })
                    .offset { IntOffset(0, bottomTopPx) }
                    .pointerInput(currentSentenceBottomPx) {
                        detectTapGestures { onNext() }
                    },
            )
        }
    }
}

@Composable
private fun PassageScrollDragBar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    var trackHeight by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (isDragging) 0.86f else 0.20f,
        animationSpec = tween(durationMillis = 220),
        label = "passage-scroll-alpha",
    )
    val maxScroll = scrollState.maxValue
    Canvas(
        modifier
            .onSizeChanged { trackHeight = it.height }
            .pointerInput(maxScroll, trackHeight) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { _, dragAmount ->
                        if (maxScroll > 0 && trackHeight > 0) {
                            val ratio = maxScroll.toFloat() / trackHeight.toFloat()
                            scrollState.dispatchRawDelta(dragAmount.y * ratio)
                        }
                    },
                )
            },
    ) {
        if (maxScroll <= 0) return@Canvas
        val tabWidth = 24.dp.toPx()
        val tabLeft = size.width - tabWidth
        val radius = 11.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.96f * alpha),
            topLeft = Offset(tabLeft, 0f),
            size = Size(tabWidth, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = PassageEdgeColor.copy(alpha = alpha),
            topLeft = Offset(tabLeft, 0f),
            size = Size(tabWidth, size.height),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 1.dp.toPx()),
        )
        val trackWidth = 10.dp.toPx()
        val trackTop = 13.dp.toPx()
        val trackHeightPx = size.height - 26.dp.toPx()
        val trackLeft = tabLeft + (tabWidth - trackWidth) / 2f
        drawRoundRect(
            color = PassageChromeColor.copy(alpha = 0.12f * alpha),
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeightPx),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
        )
        val knobHeight = max(30.dp.toPx(), trackHeightPx * 0.22f)
        val knobWidth = 11.dp.toPx()
        val travel = max(1f, trackHeightPx - knobHeight)
        val knobTop = travel * (scrollState.value.toFloat() / maxScroll.toFloat())
        drawRoundRect(
            color = PassageChromeColor.copy(alpha = 0.72f * alpha),
            topLeft = Offset(tabLeft + (tabWidth - knobWidth) / 2f, trackTop + knobTop),
            size = Size(knobWidth, knobHeight),
            cornerRadius = CornerRadius(5.5.dp.toPx(), 5.5.dp.toPx()),
        )
    }
}

// ------------------------------------------------------------------
// Passage canvas — flowing text + inline reef overlay (stays on the passage)
// ------------------------------------------------------------------

@Composable
private fun PassageCanvas(
    model: RenderModel,
    reefGlobals: Map<String, IntRange>,
    solveReefs: List<Reef>,
    phase: Phase,
    currentSentence: Int,
    currentReef: Int,
    activeReef: Reef?,
    skeletonQuestionIndex: Int,
    skeletonAnswers: Map<String, String>,
    readingMarks: List<ReadingMark>,
    reefStates: Map<String, ReefRunState>,
    onReadingMark: (IntRange) -> Unit,
    onSkeletonChunkChosen: (String) -> Unit,
    onOverlayInfoChange: (PassageOverlayInfo) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var passageTopPx by remember { mutableIntStateOf(0) }
    var passageHeightPx by remember { mutableIntStateOf(0) }
    val liveStroke = remember { mutableStateListOf<Offset>() }
    var dragStartOffset by remember { mutableStateOf<Int?>(null) }
    var dragEndOffset by remember { mutableStateOf<Int?>(null) }
    val onSurface = PassagePrintColor
    val dim = PassagePrintDimColor
    val blueUnderlineColor = PassageWhoColor
    val orangeWaveColor = PassageDidColor
    val highlightBg = PassageHighlightWashColor.copy(alpha = 0.62f)
    val errorColor = MaterialTheme.colorScheme.error
    val okColor = Color(0xFF2EAD9E)
    val skeletonReef = activeReef?.takeIf { it.type == "skeleton" }
    val skeleton = skeletonReef?.payload as? Skeleton
    val skeletonQuestion = skeleton?.questions?.getOrNull(skeletonQuestionIndex)
    val skeletonChunksById = remember(skeletonReef?.id, skeleton) {
        skeleton?.chunks?.associateBy { it.id }.orEmpty()
    }
    val skeletonChunkRanges = remember(skeletonReef?.id, skeleton) {
        if (skeletonReef == null || skeleton == null) emptyMap() else skeletonChunkRanges(skeletonReef, skeleton, model)
    }
    val skeletonCandidateIds = remember(skeletonReef?.id, skeletonQuestionIndex, skeleton) {
        val depth = skeletonQuestion?.depth
        if (skeleton == null || depth == null) emptySet() else skeleton.chunks.filter { it.depth == depth }.map { it.id }.toSet()
    }
    val skeletonAnsweredIds = remember(skeletonAnswers.toMap()) { skeletonAnswers.values.toSet() }

    fun chooseSkeletonChunk(offset: Int) {
        skeletonReef ?: return
        skeleton ?: return
        val question = skeletonQuestion ?: return
        val chosen = skeletonCandidateIds.firstOrNull { id ->
            val range = skeletonChunkRanges[id] ?: return@firstOrNull false
            offset in range
        } ?: return
        onSkeletonChunkChosen(chosen)
    }

    fun sentenceMetricsFor(index: Int): Triple<Int, Int, Int>? {
        val l = layout ?: return null
        val range = model.sentenceRanges.getOrNull(index) ?: return null
        val rects = l.lineRectsForRange(range)
        if (rects.isEmpty()) return null
        val top = rects.minOf { it.top }.roundToInt()
        val bottom = rects.maxOf { it.bottom }.roundToInt()
        val lineHeight = rects
            .map { (it.bottom - it.top).roundToInt() }
            .filter { it > 0 }
            .minOrNull()
            ?: (bottom - top).coerceAtLeast(1)
        return Triple(passageTopPx + top, passageTopPx + bottom, lineHeight)
    }

    LaunchedEffect(layout, currentSentence, activeReef?.sentenceIndex, passageTopPx, passageHeightPx) {
        val current = sentenceMetricsFor(currentSentence)
        val activeIndex = activeReef?.sentenceIndex ?: currentSentence
        val active = sentenceMetricsFor(activeIndex)
        if (current != null) {
            onOverlayInfoChange(
                PassageOverlayInfo(
                    passageTopPx = passageTopPx,
                    passageHeightPx = passageHeightPx,
                    currentSentenceCenterPx = (current.first + current.second) / 2,
                    currentSentenceTopPx = current.first,
                    currentSentenceBottomPx = current.second,
                    currentSentenceLineHeightPx = current.third,
                    activeSentenceTopPx = active?.first ?: current.first,
                    activeSentenceBottomPx = active?.second ?: current.second,
                ),
            )
        }
    }

    val styled = remember(model, phase, currentSentence, currentReef, reefStates.toMap(), solveReefs) {
        buildStyledPassage(
            model = model,
            reefGlobals = reefGlobals,
            reefs = solveReefs,
            phase = phase,
            currentSentence = currentSentence,
            activeReefId = activeReef?.id,
            reefStates = reefStates,
            colorOnSurface = onSurface,
            colorDim = dim,
            colorHighlightBg = highlightBg,
            colorReefStateOk = okColor,
            colorReefStateBad = errorColor,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PassagePaperColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, PassageEdgeColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            passageTopPx = coordinates.positionInRoot().y.roundToInt()
                            passageHeightPx = coordinates.size.height
                        }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawPaperGuideLayer()
                        val l = layout ?: return@Canvas
                        drawCurrentSentenceGuide(
                            layout = l,
                            range = model.sentenceRanges.getOrNull(currentSentence),
                        )
                        drawReadingMarkLayer(
                            layout = l,
                            marks = readingMarks,
                            blueColor = blueUnderlineColor,
                            orangeColor = orangeWaveColor,
                        )
                        drawSkeletonChunkLayer(
                            layout = l,
                            chunksById = skeletonChunksById,
                            chunkRanges = skeletonChunkRanges,
                            candidateIds = skeletonCandidateIds,
                            answeredIds = skeletonAnsweredIds,
                        )
                    }
                    Text(
                        text = styled,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = 17.5.sp,
                            lineHeight = 34.sp,
                        ),
                        color = PassagePrintColor,
                        onTextLayout = { layout = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Canvas(
                        Modifier
                            .matchParentSize()
                            .pointerInput(phase, currentSentence, skeletonReef?.id, skeletonQuestionIndex) {
                                detectTapGestures { pos ->
                                    val l = layout ?: return@detectTapGestures
                                    when {
                                        phase == Phase.Reading -> {
                                            wordRangeAt(model, l.getOffsetForPosition(pos))?.let { range ->
                                                val sIdx = model.sentenceRanges.indexOfFirst { range.first in it }
                                                if (sIdx == currentSentence) onReadingMark(range)
                                            }
                                        }
                                        phase == Phase.Solving && skeletonReef != null -> {
                                            chooseSkeletonChunk(l.getOffsetForPosition(pos))
                                        }
                                    }
                                }
                            }
                            .pointerInput(phase, currentSentence) {
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        liveStroke.clear()
                                        liveStroke.add(pos)
                                        val l = layout
                                        if (l != null && phase == Phase.Reading) {
                                            val offset = l.getOffsetForPosition(pos)
                                            dragStartOffset = offset
                                            dragEndOffset = offset
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        liveStroke.add(change.position)
                                        val l = layout
                                        if (l != null && phase == Phase.Reading) {
                                            dragEndOffset = l.getOffsetForPosition(change.position)
                                        }
                                    },
                                    onDragEnd = {
                                        val start = dragStartOffset
                                        val end = dragEndOffset
                                        if (start != null && end != null && phase == Phase.Reading) {
                                            readingMarkRange(model, currentSentence, start, end)?.let { range ->
                                                onReadingMark(range)
                                            }
                                        }
                                        dragStartOffset = null
                                        dragEndOffset = null
                                        liveStroke.clear()
                                    },
                                    onDragCancel = {
                                        dragStartOffset = null
                                        dragEndOffset = null
                                        liveStroke.clear()
                                    },
                                )
                            },
                    ) {
                        drawLiveStroke(
                            points = liveStroke,
                            color = blueUnderlineColor,
                        )
                    }
                }
            }

        }
    }
}

private fun DrawScope.drawPaperGuideLayer() {
    drawRect(PassagePaperColor)
    val ruleGap = 34.dp.toPx()
    var y = 33.dp.toPx()
    while (y < size.height) {
        drawLine(
            color = PassageRuleColor.copy(alpha = 0.86f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
        )
        y += ruleGap
    }
    drawLine(
        color = PassageMarginColor.copy(alpha = 0.55f),
        start = Offset(1.dp.toPx(), 0f),
        end = Offset(1.dp.toPx(), size.height),
        strokeWidth = 1.dp.toPx(),
    )
}

private fun currentSentenceCenterY(layout: TextLayoutResult, range: IntRange?): Float {
    if (range == null) return layout.size.height / 2f
    val rects = layout.lineRectsForRange(range)
    if (rects.isEmpty()) return layout.size.height / 2f
    return (rects.minOf { it.top } + rects.maxOf { it.bottom }) / 2f
}

private fun DrawScope.drawCurrentSentenceGuide(layout: TextLayoutResult, range: IntRange?) {
    val y = currentSentenceCenterY(layout, range).coerceIn(0f, size.height)
    drawLine(
        color = PassageChromeColor.copy(alpha = 0.22f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.2.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawReadingMarkLayer(
    layout: TextLayoutResult,
    marks: List<ReadingMark>,
    blueColor: Color,
    orangeColor: Color,
) {
    marks.forEach { mark ->
        layout.lineRectsForRange(mark.range).forEach { rect ->
            val left = max(0f, rect.left - 1.5.dp.toPx())
            val right = min(size.width, rect.right + 1.5.dp.toPx())
            val y = (rect.bottom - 2.dp.toPx()).coerceIn(0f, size.height)
            if (right <= left) return@forEach
            when (mark.kind) {
                ReadingMarkKind.BlueUnderline -> {
                    drawLine(
                        color = blueColor.copy(alpha = 0.82f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                ReadingMarkKind.OrangeWave -> {
                    drawWavyUnderline(
                        left = left,
                        right = right,
                        y = y + 1.dp.toPx(),
                        color = orangeColor.copy(alpha = 0.90f),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawWavyUnderline(left: Float, right: Float, y: Float, color: Color) {
    val wave = 10.dp.toPx()
    val amplitude = 2.5.dp.toPx()
    val path = Path()
    path.moveTo(left, y)
    var x = left
    var sign = -1f
    while (x < right) {
        val next = min(right, x + wave / 2f)
        val controlX = (x + next) / 2f
        path.quadraticTo(controlX, y + amplitude * sign, next, y)
        sign *= -1f
        x = next
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawSkeletonChunkLayer(
    layout: TextLayoutResult,
    chunksById: Map<String, SkChunk>,
    chunkRanges: Map<String, IntRange>,
    candidateIds: Set<String>,
    answeredIds: Set<String>,
) {
    if (chunkRanges.isEmpty()) return
    chunkRanges.forEach { (id, range) ->
        val chunk = chunksById[id] ?: return@forEach
        val isCandidate = id in candidateIds
        val isAnswered = id in answeredIds
        val (base, fill) = skeletonRolePalette(chunk.role)
        val depth = chunk.depth.coerceAtLeast(0)
        layout.lineRectsForRange(range).forEach { rect ->
            val left = max(0f, rect.left - 1.dp.toPx())
            val right = min(size.width, rect.right + 1.dp.toPx())
            val baseline = (rect.bottom - 2.dp.toPx()).coerceIn(0f, size.height)
            if (right <= left) return@forEach
            if (depth == 0) {
                val top = (rect.top + rect.height * 0.24f).coerceIn(0f, size.height)
                val bottom = (rect.bottom - rect.height * 0.16f).coerceIn(top, size.height)
                drawRoundRect(
                    color = fill.copy(
                        alpha = when {
                            isCandidate -> 0.58f
                            isAnswered -> 0.42f
                            else -> 0.26f
                        },
                    ),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
                drawLine(
                    color = base.copy(alpha = if (isCandidate || isAnswered) 0.92f else 0.62f),
                    start = Offset(left, baseline),
                    end = Offset(right, baseline),
                    strokeWidth = if (isCandidate) 3.dp.toPx() else 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            } else {
                drawDashedUnderline(
                    left = left,
                    right = right,
                    y = baseline - min(depth, 2) * 3.dp.toPx(),
                    color = base.copy(alpha = if (isCandidate || isAnswered) 0.82f else 0.42f),
                    strokeWidth = if (isCandidate) 2.6.dp.toPx() else 1.8.dp.toPx(),
                )
            }
        }
    }
}

private fun DrawScope.drawDashedUnderline(
    left: Float,
    right: Float,
    y: Float,
    color: Color,
    strokeWidth: Float,
) {
    val dash = 7.dp.toPx()
    val gap = 4.dp.toPx()
    var x = left
    while (x < right) {
        val end = min(right, x + dash)
        drawLine(
            color = color,
            start = Offset(x, y),
            end = Offset(end, y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        x += dash + gap
    }
}

private fun skeletonRolePalette(role: String): Pair<Color, Color> {
    val r = role.lowercase()
    return when {
        "who" in r || "subject" in r || r == "s" -> PassageWhoColor to PassageWhoBg
        "did" in r || "verb" in r || "action" in r || r == "v" -> PassageDidColor to PassageDidBg
        "what" in r || "object" in r || "idea" in r || r == "o" -> PassageWhatColor to PassageWhatBg
        else -> PassageDepthColor to PassageDepthColor.copy(alpha = 0.18f)
    }
}

private fun DrawScope.drawLiveStroke(points: List<Offset>, color: Color) {
    if (points.size < 2) return
    points.zipWithNext().forEach { (a, b) ->
        drawLine(
            color = color.copy(alpha = 0.22f),
            start = a,
            end = b,
            strokeWidth = 14.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun TextLayoutResult.lineRectsForRange(range: IntRange): List<MarkLineRect> {
    val textLength = layoutInput.text.length
    val start = range.first.coerceIn(0, textLength)
    val endExclusive = (range.last + 1).coerceIn(start, textLength)
    if (start >= endExclusive) return emptyList()

    val rows = mutableListOf<MarkLineRect>()
    for (offset in start until endExclusive) {
        if (layoutInput.text[offset].isWhitespace()) continue
        val box = getBoundingBox(offset)
        if (box.width <= 0f || box.height <= 0f) continue
        val lineIndex = getLineForOffset(offset)
        val row = rows.firstOrNull { it.lineIndex == lineIndex }
        if (row == null) {
            rows += MarkLineRect(box.left, box.top, box.right, box.bottom, lineIndex)
        } else {
            row.left = min(row.left, box.left)
            row.top = min(row.top, box.top)
            row.right = max(row.right, box.right)
            row.bottom = max(row.bottom, box.bottom)
        }
    }
    return rows
}

private fun TextLayoutResult.wordRectsForRange(range: IntRange): List<MarkLineRect> {
    val text = layoutInput.text.text
    val textLength = text.length
    val start = range.first.coerceIn(0, textLength)
    val endExclusive = (range.last + 1).coerceIn(start, textLength)
    if (start >= endExclusive) return emptyList()

    val out = mutableListOf<MarkLineRect>()
    var i = start
    while (i < endExclusive) {
        while (i < endExclusive && text[i].isWhitespace()) i++
        val wordStart = i
        while (i < endExclusive && !text[i].isWhitespace()) i++
        val wordEnd = i
        if (wordEnd > wordStart) {
            out += lineRectsForRange(wordStart until wordEnd)
        }
    }
    return out
}

private data class MarkLineRect(
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
    val lineIndex: Int,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Positions [content] just above the word at [anchorStart] within the Text. */
@Composable
private fun AnchoredAbove(
    layout: TextLayoutResult,
    anchorStart: Int,
    paddingPx: Float,
    content: @Composable () -> Unit,
) {
    val safe = anchorStart.coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
    val box: Rect = remember(layout, safe) { runCatching { layout.getBoundingBox(safe) }.getOrDefault(Rect.Zero) }
    var popupHeight by remember { mutableStateOf(0) }
    val xPx = (box.left + paddingPx).roundToInt()
    // place above the word; if it would overflow the top, place below the line.
    val aboveY = box.top + paddingPx - popupHeight
    val yPx = (if (aboveY >= 0f) aboveY else box.bottom + paddingPx).roundToInt()

    Box(
        Modifier
            .offset { IntOffset(xPx, yPx) }
            .widthIn(max = 320.dp)
            .onSizeChanged { popupHeight = it.height },
    ) { content() }
}

private fun buildStyledPassage(
    model: RenderModel,
    reefGlobals: Map<String, IntRange>,
    reefs: List<Reef>,
    phase: Phase,
    currentSentence: Int,
    activeReefId: String?,
    reefStates: Map<String, ReefRunState>,
    colorOnSurface: Color,
    colorDim: Color,
    colorHighlightBg: Color,
    colorReefStateOk: Color,
    colorReefStateBad: Color,
): AnnotatedString {
    val b = AnnotatedString.Builder(model.text)
    val len = model.text.length
    fun span(style: SpanStyle, range: IntRange) {
        val s = range.first.coerceIn(0, len)
        val e = (range.last + 1).coerceIn(s, len)
        if (e > s) b.addStyle(style, s, e)
    }

    // Reading: dim every sentence except the current one, gently highlight the current.
    if (phase == Phase.Reading) {
        model.sentenceRanges.forEachIndexed { i, r ->
            if (i != currentSentence) span(SpanStyle(color = colorDim), r)
        }
        model.sentenceRanges.getOrNull(currentSentence)?.let { r ->
            span(SpanStyle(color = colorOnSurface, fontWeight = FontWeight.Medium), r)
        }
    }

    // Active reef being solved — highlight its word.
    if (activeReefId != null) {
        reefGlobals[activeReefId]?.let {
            span(SpanStyle(background = colorHighlightBg, fontWeight = FontWeight.Bold), it)
        }
    }

    // Review: tint each attempted reef word by correctness.
    if (phase == Phase.Review) {
        reefs.forEach { r ->
            val st = reefStates[r.id] ?: return@forEach
            val c = when (st.correct) {
                true -> colorReefStateOk
                false -> colorReefStateBad
                null -> null
            }
            if (c != null) reefGlobals[r.id]?.let { span(SpanStyle(color = c, fontWeight = FontWeight.Bold), it) }
        }
    }

    return b.toAnnotatedString()
}

// ------------------------------------------------------------------
// Reef popups (small, inline — no full-screen modal)
// ------------------------------------------------------------------

@Composable
private fun QuizOverlay(
    reef: Reef,
    skeleton: Skeleton?,
    skeletonQuestionIndex: Int,
    skeletonAnswers: Map<String, String>,
    modifier: Modifier = Modifier,
    onAnswer: (String, Boolean) -> Unit,
    onAskVoice: () -> Unit,
) {
    when (val p = reef.payload) {
        is Nametag -> NametagPopup(
            payload = p,
            modifier = modifier,
            onChoose = onAnswer,
        )
        is Detective -> DetectivePopup(
            payload = p,
            modifier = modifier,
            onChoose = onAnswer,
        )
        is UserWord -> UserWordPopup(
            payload = p,
            modifier = modifier,
            onAsk = onAskVoice,
            onResolve = { knew -> onAnswer(if (knew) "알겠어요" else "아직요", knew) },
        )
        is Skeleton -> SkeletonInlinePrompt(
            skeleton = skeleton ?: p,
            questionIndex = skeletonQuestionIndex,
            answers = skeletonAnswers,
            modifier = modifier,
        )
    }
}

@Composable
private fun PopupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .heightIn(max = 230.dp),
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PassageEdgeColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 7.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun NametagPopup(payload: Nametag, modifier: Modifier = Modifier, onChoose: (String, Boolean) -> Unit) {
    PopupCard(modifier) {
        Text(
            "이건 뭘까?",
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Cursive),
            color = PassageChromeColor,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("사람" to "person", "장소" to "place", "특별한 이름" to "name").forEach { (label, key) ->
                FilledTonalButton(onClick = { onChoose(label, key == payload.answer) }, contentPadding = PaddingValuesSmall) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DetectivePopup(payload: Detective, modifier: Modifier = Modifier, onChoose: (String, Boolean) -> Unit) {
    PopupCard(modifier) {
        Text(
            "앞뒤를 봐요",
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Cursive),
            color = PassageChromeColor,
        )
        Text(payload.blankSentence, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif), color = Color(0xFF5A6766))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            payload.options.forEachIndexed { i, opt ->
                FilledTonalButton(
                    onClick = { onChoose(opt, i == payload.answerIndex) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValuesSmall,
                ) { Text(opt, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun UserWordPopup(payload: UserWord, modifier: Modifier = Modifier, onAsk: () -> Unit, onResolve: (Boolean) -> Unit) {
    PopupCard(modifier) {
        Text(
            "내가 표시한 단어",
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Cursive),
            color = PassageChromeColor,
        )
        Text(payload.word, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), fontWeight = FontWeight.Bold)
        Button(onClick = onAsk, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValuesSmall) {
            Text("🎙️ 뜻 물어보기", style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onResolve(true) }, modifier = Modifier.weight(1f), contentPadding = PaddingValuesSmall) {
                Text("알겠어요", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = { onResolve(false) }, modifier = Modifier.weight(1f), contentPadding = PaddingValuesSmall) {
                Text("아직요", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private val PaddingValuesSmall = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)

@Composable
private fun SkeletonInlinePrompt(
    skeleton: Skeleton,
    questionIndex: Int,
    answers: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val chunkText = skeleton.chunks.associate { it.id to it.text }
    val question = skeleton.questions.getOrNull(questionIndex)
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, PassageEdgeColor),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .heightIn(max = 210.dp),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            skeleton.questions.take(questionIndex).forEach { done ->
                val chosenText = answers[done.q]?.let { chunkText[it] }.orEmpty()
                Text(
                    "${done.q} -> $chosenText",
                    style = MaterialTheme.typography.labelMedium,
                    color = PassagePencilColor,
                )
            }
            if (question != null) {
                Text(question.q, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Cursive), color = PassageChromeColor, fontWeight = FontWeight.Bold)
                Text(
                    "문장 안에 표시된 청크를 눌러요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PassagePencilColor,
                )
            }
        }
    }
}

@Composable
private fun SkeletonBoard(reef: Reef, skeleton: Skeleton, onComplete: (String, Boolean) -> Unit) {
    val answers = remember(reef.id) { mutableStateMapOf<String, String>() } // questionText -> chunkId
    var qIndex by remember(reef.id) { mutableIntStateOf(0) }
    val questions = skeleton.questions

    val q = questions.getOrNull(qIndex)
    if (q == null) {
        LaunchedEffect(reef.id) {
            val allCorrect = questions.all { answers[it.q] == it.answer }
            onComplete(questions.joinToString(",") { "${it.q}:${answers[it.q] ?: ""}" }, allCorrect)
        }
        return
    }

    // Candidates = ALL chunks of the same depth (mutual distractors, §5.3).
    val candidates = skeleton.chunks.filter { it.depth == q.depth }
    val chunkText = skeleton.chunks.associate { it.id to it.text }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // already-answered slots, indented by depth
            questions.take(qIndex).forEach { done ->
                val pad = if (done.depth >= 1) 16.dp else 0.dp
                Row(Modifier.padding(start = pad), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(done.q, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text("→ " + (chunkText[answers[done.q]] ?: ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Text(q.q, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { c ->
                    OutlinedButton(
                        onClick = { answers[q.q] = c.id; qIndex++ },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(c.text, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Popup controls
// ------------------------------------------------------------------

@Composable
private fun BranchSelection(onParent: () -> Unit, onSelf: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("이제 같이 살펴볼까요?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onParent, modifier = Modifier.weight(1f)) { Text("부모와 함께") }
            OutlinedButton(onClick = onSelf, modifier = Modifier.weight(1f)) { Text("혼자 보기") }
        }
    }
}

@Composable
private fun ReviewControls(
    reefs: List<Reef>,
    currentReef: Int,
    reefStates: Map<String, ReefRunState>,
    parentMode: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    val reef = reefs.getOrNull(currentReef)
    if (reef == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val correctN = reefStates.count { it.value.correct == true }
            Text("다 살펴봤어요!", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (!parentMode) Text("$correctN / ${reefs.size} 맞았어요", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("끝내기") }
        }
        return
    }
    val st = reefStates[reef.id]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parentMode) {
            // Child-facing: low pressure, no answer pushed (answers live in MasterView, §10).
            Text("이 부분을 같이 다시 골라볼까요?", style = MaterialTheme.typography.bodyMedium)
        } else {
            val correct = st?.correct
            val msg = when {
                correct == true -> "맞았어요! 🎉"
                correct == false -> when (val p = reef.payload) {
                    is Nametag -> p.reassure
                    is Detective -> "${p.hint} (${p.meaning})"
                    is Skeleton -> "어떤 게 누구/무엇을 꾸미는지 다시 볼까요?"
                    is UserWord -> "‘${p.word}’ — 앞뒤를 보고 무슨 뜻일지 한 번 더 떠올려볼까요?"
                }
                else -> "아직 안 풀었어요."
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (correct == true) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = { if (currentReef < reefs.size) onNext() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (currentReef >= reefs.lastIndex) "마무리" else "다음") }
    }
}

@Composable
private fun RestoreView(scored: StepResult.Scored, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("이전 기록이 있어요", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("${scored.score} / ${scored.maxScore} 맞았어요", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onReset) { Text("다시하기") }
    }
}

// ------------------------------------------------------------------
// Parsing
// ------------------------------------------------------------------

private fun parsePassageRead3(params: JsonObject, content: LessonContent): PassageRead3Data {
    val sentences = parseSentences(params, content.passage?.sentences)
    val reefs = parseReefs(params)
    return PassageRead3Data(
        passageId = params.string("passageId") ?: content.passage?.id ?: "unknown",
        title = params.string("title") ?: content.passage?.title ?: "암초독해",
        reefDensity = (params["reefDensity"] as? JsonPrimitive)?.floatOrNull ?: 1.0f,
        sentences = sentences,
        reefs = reefs,
    )
}

private fun parseSentences(params: JsonObject, fallback: List<String>?): List<PassageRead3Sentence> {
    val arr = params["sentences"] as? JsonArray
    if (arr == null || arr.isEmpty()) {
        return fallback?.mapIndexed { i, t -> PassageRead3Sentence(i, t, 0, emptyList()) }
            ?: throw StepSpecParseException("passage_read3 needs params.sentences")
    }
    return arr.mapIndexed { i, el ->
        val o = el as? JsonObject ?: throw StepSpecParseException("passage_read3 bad sentence $i")
        PassageRead3Sentence(
            index = i,
            text = o.string("text") ?: throw StepSpecParseException("passage_read3 sentence $i missing text"),
            maxDepth = (o["maxDepth"] as? JsonPrimitive)?.intOrNull ?: 0,
            reefIds = o.stringList("reefIds"),
        )
    }
}

private fun parseReefs(params: JsonObject): List<Reef> {
    val arr = params["reefs"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { it as? JsonObject }.map { o ->
        val type = o.string("type") ?: throw StepSpecParseException("passage_read3 reef missing type")
        Reef(
            id = o.string("id") ?: throw StepSpecParseException("passage_read3 reef missing id"),
            sentenceIndex = (o["sentenceIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
            anchorStart = (o["anchorStart"] as? JsonPrimitive)?.intOrNull ?: 0,
            anchorEnd = (o["anchorEnd"] as? JsonPrimitive)?.intOrNull ?: 0,
            type = type,
            difficulty = (o["difficulty"] as? JsonPrimitive)?.intOrNull ?: 1,
            payload = parsePayload(type, o),
        )
    }
}

private fun parsePayload(type: String, o: JsonObject): ReefPayload = when (type) {
    "nametag" -> (o.obj("nametag") ?: throw StepSpecParseException("nametag payload missing")).let {
        Nametag(it.string("answer") ?: "person", it.string("reassure") ?: "괜찮아, 계속 읽어.")
    }
    "detective" -> (o.obj("detective") ?: throw StepSpecParseException("detective payload missing")).let {
        Detective(
            blankSentence = it.string("blankSentence") ?: "",
            options = it.stringList("options"),
            answerIndex = (it["answerIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
            hint = it.string("hint") ?: "",
            meaning = it.string("meaning") ?: "",
        )
    }
    "skeleton" -> (o.obj("skeleton") ?: throw StepSpecParseException("skeleton payload missing")).let { s ->
        val chunks = (s["chunks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { c ->
            SkChunk(
                id = c.string("id") ?: throw StepSpecParseException("skeleton chunk missing id"),
                text = c.string("text") ?: "",
                depth = (c["depth"] as? JsonPrimitive)?.intOrNull ?: 0,
                role = c.string("role") ?: "",
                attachTo = c.string("attachTo"),
                containsReefs = (c["containsReefs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
            )
        }
        val questions = (s["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { q ->
            SkQuestion(
                q = q.string("q") ?: "",
                depth = (q["depth"] as? JsonPrimitive)?.intOrNull ?: 0,
                answer = q.string("answer") ?: "",
                attachTo = q.string("attachTo"),
            )
        }
        Skeleton(chunks, questions)
    }
    else -> throw StepSpecParseException("passage_read3 unknown reef type $type")
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

// ------------------------------------------------------------------
// Hilt binding
// ------------------------------------------------------------------

@Module
@InstallIn(SingletonComponent::class)
interface PassageRead3BindModule {
    @Binds
    @IntoMap
    @StringKey("passage_read3")
    fun bind(impl: PassageRead3Feature): StepFeature
}

// ------------------------------------------------------------------
// Preview
// ------------------------------------------------------------------

private fun previewData(): PassageRead3Data = PassageRead3Data(
    passageId = "unit55_preview",
    title = "UNIT 55",
    reefDensity = 1.4f,
    sentences = listOf(
        PassageRead3Sentence(0, "Mark Twain once wrote a funny story about a jumping frog.", 0, listOf("u55_mt")),
        PassageRead3Sentence(1, "The contest was held every year.", 0, listOf("u55_held")),
        PassageRead3Sentence(2, "It gave the people who really lived in Calaveras County a great idea.", 1, listOf("u55_skel")),
    ),
    reefs = listOf(
        Reef("u55_mt", 0, 0, 10, "nametag", 1, Nametag("person", "정확히 몰라도 괜찮아, 계속 읽어.")),
        Reef("u55_held", 1, 16, 20, "detective", 3, Detective(
            blankSentence = "The contest was ___ every year.",
            options = listOf("열렸다", "끝났다", "사라졌다"),
            answerIndex = 0,
            hint = "대회가 매년 있었다는 걸 보면 held는 열렸다는 뜻이야.",
            meaning = "열렸다",
        )),
        Reef("u55_skel", 2, 8, 18, "skeleton", 4, Skeleton(
            chunks = listOf(
                SkChunk("c1", "the people", 0, "who"),
                SkChunk("c2", "had a great idea", 0, "didWhat"),
                SkChunk("c3", "who really lived in Calaveras County", 1, "whichPeople", "c1"),
                SkChunk("c4", "a great idea", 1, "whatIdea", "c2"),
            ),
            questions = listOf(
                SkQuestion("who?", 0, "c1"),
                SkQuestion("did what?", 0, "c2"),
                SkQuestion("which people?", 1, "c3", "c1"),
            ),
        )),
    ),
)

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun PassageRead3Preview() {
    PassageRead3Feature(null).StudentScreen(
        spec = PassageRead3Spec("preview", previewData()),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
