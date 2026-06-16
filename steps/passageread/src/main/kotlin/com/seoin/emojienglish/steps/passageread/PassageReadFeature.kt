package com.seoin.emojienglish.steps.passageread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlin.math.roundToInt
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
import com.seoin.emojienglish.voice.PictureController
import com.seoin.emojienglish.voice.PictureWord
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class PassageReadSpec(
    override val stepId: String,
    val data: PassageReadData,
) : StepSpec

data class PassageReadData(
    val title: String,
    val trackLabel: String,
    val curiosityQuestion: String,
    val coverVisual: String,
    val defaultChunkSetId: String,
    val paragraphs: List<PassageParagraph>,
    val processSteps: List<PassageProcessStep>,
    val exploreItems: List<PassageExploreItem>,
) {
    /** Flat sentence list across all paragraphs (sequential reading order). */
    val sentences: List<PassageSentence> = paragraphs.flatMap { it.sentences }

    fun paragraphOf(sentenceId: String): PassageParagraph? =
        paragraphs.firstOrNull { p -> p.sentences.any { it.id == sentenceId } }
}

data class PassageParagraph(
    val id: String,
    val title: String,
    /** 문단 끝 조망 질문 (없으면 빈 문자열) — 문단 마지막 문장 뒤에 카드로. */
    val overlookQuestion: String,
    val overlookHintKo: String,
    val sentences: List<PassageSentence>,
)

data class PassageSentence(
    val id: String,
    val text: String,
    val chunks: List<PassageChunk>,
)

data class PassageChunk(
    val id: String,
    val text: String,
    val startChar: Int,
    val endChar: Int,
    val role: String,
    val decodeHint: String,
    /** 한글 뜻 — 청크 단위 영어→한글 번갈아 설명에 사용. */
    val meaningKo: String,
    val exploreIds: List<String>,
)

data class PassageProcessStep(
    val id: String,
    val label: String,
    val caption: String,
    val visual: String,
)

data class PassageExploreItem(
    val id: String,
    val label: String,
    val sentenceIds: List<String>,
    val searchContext: String,
    val parentPrompt: String,
    val visual: String,
)

/**
 * passage_read (지문 탐험) — 배치 원칙:
 *
 *  - 표지(제목·호기심 질문·공정 지도)는 진입 시 1회만.
 *  - 읽기 화면 상단 **~60% = 스크롤되는 지문**(전체 문단을 책처럼). 현재 문장은
 *    항상 **화면 중앙**으로 자동 스크롤되고 강조, 나머지는 흐린 본문으로 맥락 유지.
 *    문단 사이에는 여백·제목, 문단 끝에는 조망 질문 카드.
 *  - 하단 = 청크 힌트 + **작은 원형 버튼**(따라 읽기·영어설명·한글설명) + 이전/다음.
 *  - 탐험 단어는 본문 인라인(점선 밑줄+🔎), 길게 누르면 영상/질문/실험 메뉴.
 */
class PassageReadFeature @Inject constructor(
    private val pictures: PictureController,
) : StepFeature {
    override val type: String = "passage_read"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec =
        PassageReadSpec(
            stepId = stepJson.stepId(),
            data = parsePassageRead(stepJson.params(), content),
        )

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as PassageReadSpec
        val data = s.data
        var started by remember { mutableStateOf(false) }
        var currentIndex by remember { mutableIntStateOf(0) }

        if (!started) {
            CoverPage(
                data = data,
                modifier = modifier,
                onStart = {
                    session.trace("passage_start", mapOf("sentenceCount" to data.sentences.size.toString()))
                    started = true
                },
            )
            return
        }

        val sentences = data.sentences
        val current = sentences[currentIndex]
        var selectedChunkId by remember(current.id) { mutableStateOf<String?>(null) }
        val selectedChunk = current.chunks.firstOrNull { it.id == selectedChunkId }
        val paragraph = data.paragraphOf(current.id)
        // 문단 끝 조망: 본문 흐름을 안 해치게 아이콘만 두고, 누르면 하단에 펼침.
        var overlookParaId by remember { mutableStateOf<String?>(null) }
        val overlookPara = data.paragraphs.firstOrNull { it.id == overlookParaId }
        // 상시 안내 대신 작은 ! — 누르면 위에서 잠깐 떴다 사라진다.
        var showHint by remember { mutableStateOf(false) }

        LaunchedEffect(current.id) {
            session.trace("passage_sentence_view", mapOf("sentence" to current.id))
            session.speak(current.text)
        }
        LaunchedEffect(showHint) {
            if (showHint) {
                delay(1800)
                showHint = false
            }
        }

        Box(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // 상단 한 줄: 문단 제목 · ! 안내 · 진행
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        paragraph?.title.orEmpty().ifBlank { data.title },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    InfoDot(onClick = { showHint = true })
                    Text(
                        "${currentIndex + 1} / ${sentences.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                // 지문 영역 — 남는 공간 전부. 버튼을 맨 아래로 내리고 지문을 키운다.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    PassageScroll(
                        data = data,
                        currentId = current.id,
                        onSentenceClick = { id ->
                            currentIndex = sentences.indexOfFirst { it.id == id }.coerceAtLeast(0)
                        },
                        onChunkClick = { chunk ->
                            selectedChunkId = chunk.id
                            session.trace(
                                "passage_chunk_tap",
                                mapOf("sentence" to current.id, "chunk" to chunk.id, "text" to chunk.text),
                            )
                            session.speak(chunk.text)
                        },
                        onPicture = { label, prompt ->
                            session.trace("passage_picture", mapOf("sentence" to current.id, "text" to label))
                            pictures.addWord(
                                PictureWord(id = "${current.id}_${label.hashCode()}", label = label, prompt = prompt),
                            )
                        },
                        overlookOpenId = overlookParaId,
                        onOverlookToggle = { pid ->
                            overlookParaId = if (overlookParaId == pid) null else pid
                            if (overlookParaId != null) session.trace("passage_overlook_open", mapOf("paragraph" to pid))
                        },
                    )
                }

                // ── 하단 컨트롤 — 맨 아래 고정. 가는 구분선으로만 구획 ──────────────
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 맥락 힌트(있을 때만): 조망 펼침 우선, 아니면 선택한 청크 한 줄.
                    if (overlookPara != null) {
                        OverlookInline(
                            para = overlookPara,
                            onAsk = {
                                session.trace("passage_overlook_ask", mapOf("paragraph" to overlookPara.id))
                                session.requestVoice(
                                    VoicePrompt(
                                        templateId = "passage_overlook",
                                        kind = StepPromptKind.QUIZ_CONTEXT,
                                        payload = overlookPayload(data, overlookPara),
                                        contextLabel = "${data.title} · 돌아보기",
                                    ),
                                )
                            },
                            onClose = { overlookParaId = null },
                        )
                    } else if (selectedChunk != null) {
                        ChunkHintInline(
                            chunk = selectedChunk,
                            onReplay = { session.speak(selectedChunk.text) },
                        )
                    }

                    // 버튼: 이전 = 맨 왼쪽, 나머지(따라읽기·EN·한·다음)는 오른쪽으로.
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleNav(
                            emoji = "‹",
                            label = "이전",
                            enabled = currentIndex > 0,
                            filled = false,
                        ) { if (currentIndex > 0) currentIndex-- }

                        Spacer(Modifier.weight(1f))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircleAction(emoji = "🗣", label = "따라 읽기") {
                                session.trace("passage_read_along", mapOf("sentence" to current.id))
                                session.requestVoice(
                                    VoicePrompt(
                                        templateId = "passage_read_along",
                                        kind = StepPromptKind.READ_ALONG,
                                        payload = current.text,
                                        contextLabel = "${data.title} · 문장 ${currentIndex + 1}",
                                    ),
                                )
                            }
                            CircleAction(text = "EN", label = "영어 설명") {
                                session.trace("passage_sentence_decode", mapOf("sentence" to current.id, "lang" to "en"))
                                session.requestVoice(
                                    VoicePrompt(
                                        templateId = "passage_sentence_decode",
                                        kind = StepPromptKind.EXPLAIN,
                                        payload = sentenceDecodePayload(data, current),
                                        contextLabel = "${data.title} · 문장 구조",
                                    ),
                                )
                            }
                            CircleAction(text = "한", label = "한글 뜻") {
                                session.trace("passage_sentence_ko", mapOf("sentence" to current.id, "lang" to "ko"))
                                // 한글 뜻도 보이스(GPT)에게: 청크 단위 영어→한글 번갈아 설명.
                                session.requestVoice(
                                    VoicePrompt(
                                        templateId = "passage_sentence_ko",
                                        kind = StepPromptKind.EXPLAIN,
                                        payload = sentenceKoPayload(data, current),
                                        contextLabel = "${data.title} · 한글 뜻",
                                    ),
                                )
                            }
                            CircleNav(
                                emoji = if (currentIndex == sentences.lastIndex) "✓" else "›",
                                label = if (currentIndex == sentences.lastIndex) "완료" else "다음",
                                enabled = true,
                                filled = true,
                            ) {
                                if (currentIndex < sentences.lastIndex) {
                                    currentIndex++
                                } else {
                                    session.trace("passage_complete", mapOf("sentenceCount" to sentences.size.toString()))
                                    session.complete(StepResult.Completed())
                                }
                            }
                        }
                    }
                }
            }

            // ! 안내 토스트 — 위에서 1~2초만 떴다 사라진다.
            if (showHint) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp, start = 24.dp, end = 24.dp),
                ) {
                    Text(
                        "문장에서 단어 조각을 눌러보세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "지문 탐험 — 기록",
            resultLines = trace.resultSummaries(),
            activities = trace.timeOrderedActivities().map {
                MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail)
            },
            modifier = modifier,
        )
    }

    private fun exploreVoice(
        session: StepSession,
        data: PassageReadData,
        current: PassageSentence,
        item: PassageExploreItem,
        mode: String,
    ) {
        val action = when (mode) {
            "video" -> "passage_parent_video"
            "questions" -> "passage_parent_questions"
            else -> "passage_parent_home_experiment"
        }
        session.trace(action, mapOf("item" to item.id, "sentence" to current.id))
        session.requestVoice(
            VoicePrompt(
                templateId = "parent_explore_$mode",
                kind = StepPromptKind.FREE_TALK,
                payload = parentExplorePayload(data, current, item, mode),
                contextLabel = "탐색 · ${item.label}",
            ),
        )
    }
}

// ─────────────────────────────────────────────── 스크롤 지문 + 중앙 고정

/**
 * 전체 문단을 책처럼 흐르게 쌓되, [currentId] 문장이 바뀌면 그 문장을 **화면 중앙**으로
 * 자동 스크롤한다. 안정감 원칙: 현재 문장도 **폰트 크기·줄간격 동일**, 강조는 왼쪽
 * 컬러 바 + 진한 글자색으로만(레이아웃 출렁임 제거). 문단 끝엔 작은 🔭 아이콘만 두고,
 * 누르면 하단에서 펼친다(본문 흐름을 안 끊음).
 */
@Composable
private fun PassageScroll(
    data: PassageReadData,
    currentId: String,
    onSentenceClick: (String) -> Unit,
    onChunkClick: (PassageChunk) -> Unit,
    onPicture: (String, String) -> Unit,
    overlookOpenId: String?,
    onOverlookToggle: (String) -> Unit,
) {
    val rows = remember(data, currentId, overlookOpenId) {
        val list = mutableListOf<ScrollRow>()
        data.paragraphs.forEachIndexed { pIndex, para ->
            if (para.title.isNotBlank()) {
                list += ScrollRow("h_${para.id}", null) { ParagraphHeader(para.title) }
            }
            para.sentences.forEach { sentence ->
                list += ScrollRow("s_${sentence.id}", sentence.id) {
                    SentenceLine(
                        sentence = sentence,
                        isCurrent = sentence.id == currentId,
                        onClick = { onSentenceClick(sentence.id) },
                        onChunkClick = onChunkClick,
                        onPicture = onPicture,
                    )
                }
            }
            if (para.overlookQuestion.isNotBlank()) {
                list += ScrollRow("o_${para.id}", null) {
                    OverlookMarker(
                        opened = overlookOpenId == para.id,
                        onClick = { onOverlookToggle(para.id) },
                        lastParagraph = pIndex == data.paragraphs.lastIndex,
                    )
                }
            }
        }
        list
    }

    val currentRowIndex = rows.indexOfFirst { it.sentenceId == currentId }.coerceAtLeast(0)
    val listState = rememberLazyListState()

    LaunchedEffect(currentId) {
        val viewport = listState.layoutInfo.viewportSize.height
        val itemH = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentRowIndex }?.size ?: 0
        val offset = -(viewport / 2 - itemH / 2).coerceAtLeast(0)
        listState.animateScrollToItem(currentRowIndex, offset)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(rows, key = { it.key }) { row -> row.content() }
    }
}

private data class ScrollRow(
    val key: String,
    val sentenceId: String?,
    val content: @Composable () -> Unit,
)

@Composable
private fun ParagraphHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/**
 * 한 문장 한 줄 — 현재 문장만 왼쪽 컬러 바 + 진한 글자색. 현재 문장은 **원문 그대로**
 * 그려서(청크 구분자 없음) 좌표↔글자가 1:1. 상호작용:
 *  - **짧은 탭 = 청크 낭독**(기존 동작 유지).
 *  - **길게 누르면 그 단어가 선택**되고, 누른 채 **드래그하면 단어 단위로 범위 확장**,
 *    손을 떼면 선택이 남고 그 자리에 작은 **메뉴판**(그림 등)이 뜬다.
 *  - 줄 끝(마지막 단어 뒤 여백)을 길게 누르면 **문장 전체 선택**.
 */
@Composable
private fun SentenceLine(
    sentence: PassageSentence,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onChunkClick: (PassageChunk) -> Unit,
    onPicture: (String, String) -> Unit,
) {
    val baseColor = if (isCurrent) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val chunkColorA = MaterialTheme.colorScheme.primary
    val chunkColorB = MaterialTheme.colorScheme.tertiary

    var layout by remember(sentence) { mutableStateOf<TextLayoutResult?>(null) }
    var selection by remember(sentence, isCurrent) { mutableStateOf<TextRange?>(null) }
    var menuAnchor by remember(sentence, isCurrent) { mutableStateOf<Offset?>(null) }

    val annotated = remember(sentence, isCurrent, selection, highlight, chunkColorA, chunkColorB) {
        buildAnnotatedString {
            append(sentence.text)
            if (isCurrent) {
                // 청크 구분 — 색 교대 + 밑줄. 구분자(글자) 없이 색으로만 입혀 좌표↔글자 1:1 유지.
                sentence.chunks.sortedBy { it.startChar }.forEachIndexed { i, chunk ->
                    val color = if (chunk.exploreIds.isNotEmpty() || i % 2 == 0) chunkColorA else chunkColorB
                    val start = chunk.startChar.coerceIn(0, length)
                    val end = chunk.endChar.coerceIn(0, length)
                    if (start < end) {
                        addStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline), start, end)
                    }
                }
            }
            val sel = selection
            if (isCurrent && sel != null && sel.start < sel.end &&
                sel.start >= 0 && sel.end <= length
            ) {
                addStyle(SpanStyle(background = highlight), sel.start, sel.end)
            }
        }
    }

    val gestures = if (isCurrent) {
        Modifier
            .pointerInput(sentence) {
                detectTapGestures(onTap = { pos ->
                    // 길게 누르기-드래그가 방금 메뉴를 열었으면 그 뒤 따라오는 탭은 무시.
                    if (menuAnchor != null) return@detectTapGestures
                    selection = null
                    val l = layout ?: return@detectTapGestures
                    chunkAtOffset(sentence, l.getOffsetForPosition(pos))?.let(onChunkClick)
                })
            }
            .pointerInput(sentence) {
                var anchor: TextRange? = null
                var lastPos = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        menuAnchor = null
                        lastPos = pos
                        val l = layout ?: return@detectDragGesturesAfterLongPress
                        val line = l.getLineForVerticalPosition(pos.y)
                        val sel = if (pos.x > l.getLineRight(line)) {
                            TextRange(0, sentence.text.length)        // 줄 끝 여백 → 문장 전체
                        } else {
                            l.getWordBoundary(l.getOffsetForPosition(pos))
                        }
                        anchor = sel
                        selection = sel
                    },
                    onDrag = { change, _ ->
                        lastPos = change.position
                        val l = layout ?: return@detectDragGesturesAfterLongPress
                        val a = anchor ?: return@detectDragGesturesAfterLongPress
                        val w = l.getWordBoundary(l.getOffsetForPosition(change.position))
                        selection = TextRange(minOf(a.start, w.start), maxOf(a.end, w.end))
                    },
                    onDragEnd = { menuAnchor = lastPos },
                    onDragCancel = { menuAnchor = null },
                )
            }
    } else {
        Modifier
    }

    val barColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isCurrent, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .width(3.dp)
                    .heightIn(min = 20.dp)
                    .background(barColor, RoundedCornerShape(2.dp)),
            )
            Text(
                annotated,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
                color = baseColor,
                lineHeight = 28.sp,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                    .then(gestures),
            )
        }

        // 선택 후 손 뗀 자리에 뜨는 작은 메뉴판 (펜 사이드버튼 메뉴 느낌).
        val anchor = menuAnchor
        val sel = selection
        if (isCurrent && anchor != null && sel != null && sel.start < sel.end) {
            val selText = sentence.text
                .substring(sel.start.coerceIn(0, sentence.text.length), sel.end.coerceIn(0, sentence.text.length))
                .trim()
            SelectionActionMenu(
                anchor = anchor,
                selectedText = selText,
                onPicture = {
                    onPicture(selText, passagePicturePayload(selText, sentence))
                    menuAnchor = null
                },
                onDismiss = { menuAnchor = null; selection = null },
            )
        }
    }
}

private fun chunkAtOffset(sentence: PassageSentence, offset: Int): PassageChunk? =
    sentence.chunks.firstOrNull { offset >= it.startChar && offset < it.endChar }

/** 클릭 근처 작은 메뉴판 — 선택 텍스트 + 액션 줄(지금은 그림, 추후 줄만 추가). */
@Composable
private fun SelectionActionMenu(
    anchor: Offset,
    selectedText: String,
    onPicture: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        offset = IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 240.dp),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    selectedText.ifBlank { "선택" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "🖼 그림",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPicture)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 문단 끝 — 본문 흐름 안에 작은 🔭 한 점. 누르면 하단에서 펼친다. */
@Composable
private fun OverlookMarker(opened: Boolean, onClick: () -> Unit, lastParagraph: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "🔭",
            fontSize = 13.sp,
            color = if (opened) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ──────────────────────────────────────────────── 하단 인라인 (조망/힌트)

/** 문단 돌아보기 펼침 — 하단에 한 덩어리로. 본문 톤, 가벼운 배경. */
@Composable
private fun OverlookInline(para: PassageParagraph, onAsk: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "🔭 ${para.overlookQuestion}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (para.overlookHintKo.isNotBlank()) {
                    Text(
                        para.overlookHintKo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            InlineCircle(emoji = "🎙", onClick = onAsk)
            InlineCircle(emoji = "✕", onClick = onClose, subtle = true)
        }
    }
}

/** 선택한 청크 한 줄 — 영어 · 한글뜻 · 🔊. 박스 무게를 최소화. */
@Composable
private fun ChunkHintInline(chunk: PassageChunk, onReplay: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                chunk.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (chunk.meaningKo.isNotBlank()) {
                Text(
                    "· ${chunk.meaningKo}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            InlineCircle(emoji = "🔊", onClick = onReplay, subtle = true)
        }
    }
}

// ──────────────────────────────────────────────────────── 작은 원형 버튼

/** 학습 원형 버튼 — 이모지 또는 짧은 텍스트(EN/한). */
@Composable
private fun CircleAction(
    emoji: String? = null,
    text: String? = null,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(46.dp).clickable(onClick = onClick),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (emoji != null) {
                    Text(emoji, fontSize = 20.sp)
                } else {
                    Text(
                        text.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** 이전/다음 — 학습 버튼과 같은 원형 계열. 다음만 채움 + 약간 크게. */
@Composable
private fun CircleNav(
    emoji: String,
    label: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .size(if (filled) 54.dp else 46.dp)
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    emoji,
                    fontSize = if (filled) 24.sp else 22.sp,
                    color = when {
                        filled -> MaterialTheme.colorScheme.onPrimary
                        enabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 아주 작은 ! 안내 점 — 누르면 상단에 토스트가 잠깐 뜬다(상시 문구 대체). */
@Composable
private fun InfoDot(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(20.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "!",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 인라인용 작은 원형(36dp) — 🎙 / 🔊 / ✕. */
@Composable
private fun InlineCircle(emoji: String, onClick: () -> Unit, subtle: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (subtle) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
        border = if (subtle) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = Modifier.size(36.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emoji,
                fontSize = 15.sp,
                color = if (subtle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────── 표지

@Composable
private fun CoverPage(data: PassageReadData, modifier: Modifier, onStart: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    data.trackLabel.ifBlank { "Reading" },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    data.title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(data.coverVisual, fontSize = 56.sp)
            }
            if (data.curiosityQuestion.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(data.curiosityQuestion, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (data.processSteps.isNotEmpty()) {
                Text("한눈에 보는 과정", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    data.processSteps.forEachIndexed { index, step ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(120.dp).heightIn(min = 104.dp),
                        ) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(step.visual, fontSize = 30.sp)
                                Text("${index + 1}. ${step.label}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                if (step.caption.isNotBlank()) {
                                    Text(step.caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("📖 읽기 시작") }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ───────────────────────────────────────────────────────────── 파싱

private fun parsePassageRead(params: JsonObject, content: LessonContent): PassageReadData {
    val defaultChunkSetId = params.string("defaultChunkSetId") ?: "short"
    val paragraphs = parseParagraphs(params, defaultChunkSetId, content)
    if (paragraphs.all { it.sentences.isEmpty() }) {
        throw StepSpecParseException("passage_read needs params.paragraphs/sentences or content.passage")
    }

    return PassageReadData(
        title = params.string("title") ?: content.passage?.title ?: "지문 탐험",
        trackLabel = params.string("trackLabel") ?: "Passage",
        curiosityQuestion = params.string("curiosityQuestion") ?: "",
        coverVisual = params.string("coverVisual") ?: "📖",
        defaultChunkSetId = defaultChunkSetId,
        paragraphs = paragraphs,
        processSteps = params.objects("processSteps").mapIndexed { index, obj ->
            PassageProcessStep(
                id = obj.string("id") ?: "process_${index + 1}",
                label = obj.string("label") ?: "Step ${index + 1}",
                caption = obj.string("caption") ?: "",
                visual = obj.string("visual") ?: "□",
            )
        },
        exploreItems = params.objects("exploreItems").mapIndexed { index, obj ->
            PassageExploreItem(
                id = obj.string("id") ?: "explore_${index + 1}",
                label = obj.string("label") ?: obj.string("id") ?: "explore",
                sentenceIds = obj.stringArray("sentenceIds"),
                searchContext = obj.string("searchContext") ?: "",
                parentPrompt = obj.string("parentPrompt") ?: "",
                visual = obj.string("visual") ?: "🔎",
            )
        },
    )
}

private fun parseParagraphs(
    params: JsonObject,
    defaultChunkSetId: String,
    content: LessonContent,
): List<PassageParagraph> {
    val paraObjects = params.objects("paragraphs")
    if (paraObjects.isNotEmpty()) {
        return paraObjects.mapIndexed { pIndex, pObj ->
            PassageParagraph(
                id = pObj.string("id") ?: "p${pIndex + 1}",
                title = pObj.string("title") ?: "",
                overlookQuestion = pObj.string("overlookQuestion") ?: "",
                overlookHintKo = pObj.string("overlookHintKo") ?: "",
                sentences = pObj.objects("sentences").mapIndexed { sIndex, sObj ->
                    parseSentence(sObj, sIndex, defaultChunkSetId)
                },
            )
        }
    }
    // flat sentences → 단일 문단
    val flat = params.objects("sentences").mapIndexed { sIndex, sObj ->
        parseSentence(sObj, sIndex, defaultChunkSetId)
    }.ifEmpty {
        content.passage?.sentences.orEmpty().mapIndexed { index, text ->
            PassageSentence(
                id = "sentence_${index + 1}",
                text = text,
                chunks = listOf(wholeChunk("sentence_${index + 1}", text)),
            )
        }
    }
    return if (flat.isEmpty()) emptyList()
    else listOf(PassageParagraph("p1", "", "", "", flat))
}

private fun parseSentence(obj: JsonObject, index: Int, defaultChunkSetId: String): PassageSentence {
    val text = obj.string("text") ?: throw StepSpecParseException("passage_read sentence is missing text")
    val sentenceId = obj.string("id") ?: "s${index + 1}"
    val chunkObjects = obj.objects("chunks").ifEmpty {
        obj.objects("chunkSets")
            .firstOrNull { (it.string("id") ?: "") == defaultChunkSetId }
            ?.objects("chunks")
            ?: obj.objects("chunkSets").firstOrNull()?.objects("chunks").orEmpty()
    }
    val chunks = chunkObjects.mapIndexed { chunkIndex, chunkObj ->
        parseChunk(sentenceId, text, chunkIndex, chunkObj)
    }.ifEmpty { listOf(wholeChunk(sentenceId, text)) }
    return PassageSentence(id = sentenceId, text = text, chunks = chunks)
}

private fun wholeChunk(sentenceId: String, text: String) = PassageChunk(
    id = "${sentenceId}_whole",
    text = text,
    startChar = 0,
    endChar = text.length,
    role = "whole sentence",
    decodeHint = "문장 전체를 한 번에 읽어봅니다.",
    meaningKo = "",
    exploreIds = emptyList(),
)

private fun parseChunk(sentenceId: String, sentenceText: String, index: Int, obj: JsonObject): PassageChunk {
    val text = obj.string("text") ?: throw StepSpecParseException("chunk in $sentenceId is missing text")
    val inferredStart = sentenceText.indexOf(text).takeIf { it >= 0 } ?: 0
    val start = obj.int("startChar") ?: inferredStart
    val end = obj.int("endChar") ?: (start + text.length).coerceAtMost(sentenceText.length)
    if (start !in 0..sentenceText.length || end !in 0..sentenceText.length || start > end) {
        throw StepSpecParseException("chunk ${obj.string("id") ?: index} has invalid char range")
    }
    if (end - start == text.length && sentenceText.substring(start, end) != text) {
        throw StepSpecParseException("chunk ${obj.string("id") ?: index} text does not match sentence substring")
    }
    return PassageChunk(
        id = obj.string("id") ?: "${sentenceId}_c${index + 1}",
        text = text,
        startChar = start,
        endChar = end,
        role = obj.string("role") ?: "",
        decodeHint = obj.string("decodeHint") ?: "",
        meaningKo = obj.string("meaningKo") ?: "",
        exploreIds = obj.stringArray("exploreIds"),
    )
}

// 보이스 프롬프트는 항상 간결하게 — 예시만 주고 판단은 보이스에게 맡긴다.

private fun sentenceKoPayload(data: PassageReadData, sentence: PassageSentence): String =
    "이 문장을 끊어 읽기로 한국어 해석해줘 (예: \"the cream, 그 크림은, is beaten, 쳐져요\"): ${sentence.text}"

private fun overlookPayload(data: PassageReadData, para: PassageParagraph): String {
    val body = para.sentences.joinToString(" ") { it.text }
    return "방금 읽은 문단 내용을 아이에게 물어봐줘 (예: ${para.overlookQuestion}). 문단: $body"
}

private fun sentenceDecodePayload(data: PassageReadData, sentence: PassageSentence): String {
    val chunks = sentence.chunks.sortedBy { it.startChar }.joinToString(" / ") { it.text }
    return "이 문장을 청크 단위로 \"영어 청크 → 그 청크를 아주 쉬운 영어 단어로 자세히 설명\" " +
        "순서로 번갈아 말해줘 (청크: $chunks): ${sentence.text}"
}

private fun chunkDecodePayload(data: PassageReadData, sentence: PassageSentence, chunk: PassageChunk): String =
    "\"${chunk.text}\"가 이 문장에서 어떤 뜻·역할인지 쉽게 설명해줘: ${sentence.text}"

// 그림창에 주입할 요청문 — 사진 우선, 없으면 간단히 그려달라고. 짧게 + 문맥 한 줄.
private fun passagePicturePayload(selectedText: String, sentence: PassageSentence): String =
    "\"${selectedText.trim()}\" 의 사진이나 그림을 보여줘. 못 찾으면 아이가 알아볼 수 있게 간단히 그려줘. 문맥: ${sentence.text}"

private fun parentExplorePayload(
    data: PassageReadData,
    sentence: PassageSentence,
    item: PassageExploreItem,
    mode: String,
): String = when (mode) {
    "video" -> "\"${item.label}\"를 더 알아볼 어린이 영상 검색어를 한국어로 추천해줘"
    "questions" -> "\"${item.label}\"에 대해 부모와 아이가 나눌 질문 몇 개를 한국어로 줘"
    else -> "\"${item.label}\"와 관련해 집에서 해볼 간단한 활동을 한국어로 알려줘"
}

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

@Module
@InstallIn(SingletonComponent::class)
interface PassageReadBindModule {
    @Binds
    @IntoMap
    @StringKey("passage_read")
    fun bind(impl: PassageReadFeature): StepFeature
}

private val PreviewPassage = PassageReadData(
    title = "How Butter Is Made",
    trackLabel = "Track 11",
    curiosityQuestion = "How does cream become butter?",
    coverVisual = "🧈",
    defaultChunkSetId = "short",
    processSteps = listOf(
        PassageProcessStep("fresh_milk", "Fresh milk", "From the farm", "🥛"),
        PassageProcessStep("churner", "Churner", "Cream is beaten", "🌀"),
    ),
    exploreItems = listOf(
        PassageExploreItem("churner", "churner", listOf("s2"), "A machine that beats cream.", "Find videos.", "🌀"),
    ),
    paragraphs = listOf(
        PassageParagraph(
            id = "p1", title = "도입",
            overlookQuestion = "What is butter made from?",
            overlookHintKo = "앞 문단의 핵심을 한 문장으로 말해보세요.",
            sentences = listOf(
                PassageSentence(
                    id = "s1",
                    text = "Butter starts as raw cow's milk.",
                    chunks = listOf(
                        PassageChunk("s1_c1", "Butter", 0, 6, "what", "오늘의 주인공입니다.", "버터", emptyList()),
                        PassageChunk("s1_c2", "starts as", 7, 16, "change", "무엇에서 시작하는지.", "~에서 시작해요", emptyList()),
                        PassageChunk("s1_c3", "raw cow's milk.", 17, 32, "source", "출발 재료입니다.", "날것의 소 우유", emptyList()),
                    ),
                ),
            ),
        ),
        PassageParagraph(
            id = "p2", title = "공정",
            overlookQuestion = "How does cream become butter?",
            overlookHintKo = "크림이 버터가 되는 과정을 떠올려보세요.",
            sentences = listOf(
                PassageSentence(
                    id = "s2",
                    text = "Later, the cream is beaten in a machine called a churner.",
                    chunks = listOf(
                        PassageChunk("s2_c1", "Later,", 0, 6, "time", "언제인지.", "나중에", emptyList()),
                        PassageChunk("s2_c2", "the cream", 7, 16, "subject", "무엇이 움직이는지.", "그 크림이", emptyList()),
                        PassageChunk("s2_c3", "is beaten", 17, 26, "action", "당하는 동작.", "쳐집니다", emptyList()),
                        PassageChunk("s2_c4", "in a machine called a churner.", 27, 57, "tool", "어떤 기계 안에서.", "처너라는 기계 안에서", listOf("churner")),
                    ),
                ),
            ),
        ),
    ),
)

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun PassageReadPreview() {
    PassageReadFeature(com.seoin.emojienglish.voice.NoopPictureController).StudentScreen(
        spec = PassageReadSpec("s_passage", PreviewPassage),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
