package com.seoin.emojienglish.steps.wordcard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.seoin.emojienglish.step.timeOrderedActivities
import com.seoin.emojienglish.voice.StepPromptKind
import com.seoin.emojienglish.voice.VoicePrompt
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

data class WordCardSpec(
    override val stepId: String,
    val title: String,
    val passage: String,
    val cards: List<WordCardItem>,
) : StepSpec

data class WordCardItem(
    val id: String,
    val word: String,
    val meaningKo: List<String>,
    val structureKo: List<String>,
    val examples: List<WordCardExample>,
    val defaultChecked: Boolean = false,
)

data class WordCardExample(
    val en: String,
    val ko: String = "",
    val noteKo: String = "",
)

class WordCardFeature @Inject constructor() : StepFeature {
    override val type: String = "word_card"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec {
        val p = stepJson.params()
        val cards = (p["cards"] as? JsonArray)
            ?.mapIndexedNotNull { index, element -> parseCard(index, element as? JsonObject) }
            .orEmpty()
        if (cards.isEmpty()) throw StepSpecParseException("word_card: params.cards 가 비었습니다")
        return WordCardSpec(
            stepId = stepJson.stepId(),
            title = p.stringValue("title") ?: "Word Cards",
            passage = p.stringValue("passage").orEmpty(),
            cards = cards,
        )
    }

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as WordCardSpec
        var selectedId by remember(s.stepId) { mutableStateOf<String?>(null) }
        var completed by remember(s.stepId) { mutableStateOf(false) }
        val seen = remember(s.stepId) { mutableStateMapOf<String, Boolean>() }
        val checked = remember(s.stepId) {
            mutableStateMapOf<String, Boolean>().also { map ->
                s.cards.forEach { map[it.id] = it.defaultChecked }
            }
        }

        fun markSeen(card: WordCardItem) {
            if (seen[card.id] != true) {
                seen[card.id] = true
                session.trace("word_card_seen", mapOf("word" to card.word, "id" to card.id))
            }
        }

        LaunchedEffect(seen.size) {
            if (!completed && seen.size >= s.cards.size) {
                completed = true
                session.complete(StepResult.Completed())
            }
        }

        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WordButtonGrid(
                    cards = s.cards,
                    selectedId = selectedId,
                    seen = seen,
                    checked = checked,
                    onClick = { card ->
                        val current = selectedId?.let { id -> s.cards.firstOrNull { it.id == id } }
                        if (selectedId == card.id) {
                            markSeen(card)
                            selectedId = null
                        } else {
                            current?.let(::markSeen)
                            selectedId = card.id
                            session.trace("word_card_open", mapOf("word" to card.word, "id" to card.id))
                        }
                    },
                )

                val selected = s.cards.firstOrNull { it.id == selectedId }
                if (selected != null) {
                    Dialog(
                        onDismissRequest = {
                            markSeen(selected)
                            selectedId = null
                        },
                    ) {
                        WordDetailCard(
                            card = selected,
                            checked = checked[selected.id] == true,
                            onCheckedChange = { value ->
                                checked[selected.id] = value
                                session.trace(
                                    "word_card_check",
                                    mapOf("word" to selected.word, "checked" to value.toString()),
                                )
                            },
                            onRead = { session.speak(readText(selected), "ko-KR") },
                            onVoice = {
                                session.requestVoice(
                                    VoicePrompt(
                                        templateId = "word_card",
                                        kind = StepPromptKind.EXPLAIN,
                                        payload = teacherPayload(selected),
                                        contextLabel = "단어카드 · ${selected.word}",
                                        variables = mapOf("word" to selected.word),
                                    ),
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        selectedId?.let { id -> s.cards.firstOrNull { it.id == id }?.let(::markSeen) }
                        completed = true
                        session.complete(StepResult.Completed())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (seen.size >= s.cards.size) "다 봤어요" else "여기까지 저장")
                }
            }
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "단어카드 학습 기록",
            resultLines = trace.resultSummaries(),
            activities = trace.timeOrderedActivities().map {
                MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail)
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun WordButtonGrid(
    cards: List<WordCardItem>,
    selectedId: String?,
    seen: Map<String, Boolean>,
    checked: Map<String, Boolean>,
    onClick: (WordCardItem) -> Unit,
) {
    BoxWithConstraints {
        val columnCount = if (maxWidth >= 600.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.chunked(columnCount).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { card ->
                        WordButton(
                            card = card,
                            selected = selectedId == card.id,
                            seen = seen[card.id] == true,
                            checked = checked[card.id] == true,
                            onClick = { onClick(card) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columnCount - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WordButton(
    card: WordCardItem,
    selected: Boolean,
    seen: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        checked -> MaterialTheme.colorScheme.tertiaryContainer
        seen -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        checked -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = container),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(card.word, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            val stateLabel = when {
                checked -> "체크"
                seen -> "봤음"
                else -> ""
            }
            if (stateLabel.isNotBlank()) {
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun WordDetailCard(
    card: WordCardItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRead: () -> Unit,
    onVoice: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(card.word, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onRead, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("읽기")
                }
                Button(onClick = onVoice, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("보이스")
                }
            }

            SectionText("뜻", card.meaningKo)
            SectionText("문장 구조", card.structureKo.ifEmpty { listOf("이 단어는 지문 속 문장에서 어떤 일을 하는지 살펴보면 됩니다.") })
            if (card.examples.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("예시문", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    card.examples.forEach { example ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(example.en, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (example.ko.isNotBlank()) Text(example.ko, style = MaterialTheme.typography.bodySmall)
                            if (example.noteKo.isNotBlank()) {
                                Text(example.noteKo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Text("나중에 활동에서 다시 볼 단어로 표시")
            }
        }
    }
}

@Composable
private fun SectionText(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        lines.filter { it.isNotBlank() }.forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun parseCard(index: Int, obj: JsonObject?): WordCardItem? {
    if (obj == null) return null
    val word = obj.stringValue("word")?.trim().orEmpty()
    if (word.isBlank()) return null
    return WordCardItem(
        id = obj.stringValue("id")?.trim().takeUnless { it.isNullOrBlank() } ?: "w${index + 1}_${word.slug()}",
        word = word,
        meaningKo = obj.stringListValue("meaningKo").takeIf { it.isNotEmpty() }
            ?: listOf(obj.stringValue("meaningKo").orEmpty()).filter { it.isNotBlank() },
        structureKo = obj.stringListValue("structureKo").takeIf { it.isNotEmpty() }
            ?: listOf(obj.stringValue("structureKo").orEmpty()).filter { it.isNotBlank() },
        examples = parseExamples(obj["examples"] as? JsonArray),
        defaultChecked = (obj["checked"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private fun parseExamples(array: JsonArray?): List<WordCardExample> =
    array?.mapNotNull { element ->
        when (element) {
            is JsonObject -> WordCardExample(
                en = element.stringValue("en").orEmpty(),
                ko = element.stringValue("ko").orEmpty(),
                noteKo = element.stringValue("noteKo").orEmpty(),
            ).takeIf { it.en.isNotBlank() }
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { WordCardExample(en = it) }
            else -> null
        }
    }.orEmpty()

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringListValue(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

private fun String.slug(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "word" }

private fun readText(card: WordCardItem): String =
    buildString {
        append(card.word).append(". ")
        card.meaningKo.forEach { append(it).append(" ") }
        card.structureKo.forEach { append(it).append(" ") }
        card.examples.firstOrNull()?.let { append("예시. ").append(it.en).append(" ") }
    }.trim()

private fun teacherPayload(card: WordCardItem): String =
    buildString {
        append("단어: ${card.word}\n")
        append("뜻 설명:\n")
        card.meaningKo.forEach { append("- $it\n") }
        append("문장 구조 설명:\n")
        card.structureKo.forEach { append("- $it\n") }
        append("예시문:\n")
        card.examples.forEach { append("- ${it.en} ${it.ko}\n") }
        append("아이에게 이 단어를 아주 쉬운 말로 설명하고, 마지막에 짧은 확인 질문 하나만 해줘.")
    }

@Module
@InstallIn(SingletonComponent::class)
interface WordCardBindModule {
    @Binds
    @IntoMap
    @StringKey("word_card")
    fun bind(impl: WordCardFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun WordCardPreview() {
    WordCardFeature().StudentScreen(
        spec = WordCardSpec(
            stepId = "s_word_card",
            title = "Fizzy Treat Words",
            passage = "Bubbles formed when baking soda and citric acid mix together.",
            cards = listOf(
                WordCardItem(
                    id = "w_fizzy",
                    word = "fizzy",
                    meaningKo = listOf("작은 거품이 많이 있는 느낌이에요.", "탄산음료처럼 톡톡 올라와요.", "입 안에서 살짝 간질간질할 수 있어요."),
                    structureKo = listOf("fizzy는 보통 명사 앞에서 어떤 느낌인지 꾸며줘요."),
                    examples = listOf(WordCardExample("a fizzy drink", "톡톡 거품이 나는 음료", "fizzy가 drink를 꾸며요.")),
                ),
                WordCardItem(
                    id = "w_reaction",
                    word = "reaction",
                    meaningKo = listOf("두 가지가 만나서 새 일이 생기는 거예요.", "실험에서 변화를 말할 때 써요.", "거품이나 열처럼 보이는 변화가 나올 수 있어요."),
                    structureKo = listOf("reaction은 명사라서 a reaction, the reaction처럼 써요."),
                    examples = listOf(WordCardExample("It causes an instant reaction.", "그것은 바로 반응을 일으켜요.", "cause가 reaction을 목적어로 데려와요.")),
                ),
            ),
        ),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
