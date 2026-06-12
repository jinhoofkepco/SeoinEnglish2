package com.seoin.emojienglish.steps.question

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seoin.emojienglish.designsystem.DummyMasterScaffold
import com.seoin.emojienglish.designsystem.MasterActivityRow
import com.seoin.emojienglish.designsystem.formatClock
import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.Question
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
        val questions = s.questionIds.mapNotNull { session.content.question(it) }

        if (questions.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("문제를 불러올 수 없습니다.", style = MaterialTheme.typography.bodyLarge)
            }
            return
        }

        // answers[qIndex] = selected choice for that question
        var answers by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
        var currentIndex by remember { mutableIntStateOf(0) }

        val currentQuestion = questions.getOrNull(currentIndex)
        val selectedForCurrent = answers[currentIndex]
        val isAnswered = selectedForCurrent != null
        val isCorrect = isAnswered && selectedForCurrent == currentQuestion?.answer
        val isLast = currentIndex == questions.lastIndex

        fun advance() {
            val q = questions[currentIndex]
            val sel = answers[currentIndex] ?: return
            session.trace("answer_submit", mapOf(
                "q" to q.id,
                "selected" to sel,
                "correct" to (sel == q.answer).toString(),
            ))
            if (!isLast) {
                currentIndex++
            } else {
                val score = answers.values.zip(questions.map { it.answer }).count { (a, b) -> a == b }
                session.complete(
                    StepResult.Scored(
                        selected = sel,
                        answer = q.answer,
                        score = score,
                        maxScore = questions.size,
                    ),
                )
            }
        }

        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("퀴즈", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${currentIndex + 1} / ${questions.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                LinearProgressIndicator(
                    progress = { (currentIndex + 1).toFloat() / questions.size },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (currentQuestion != null) {
                    QuestionCard(
                        question = currentQuestion,
                        selected = selectedForCurrent,
                        onSelect = { choice ->
                            if (!isAnswered) answers = answers + (currentIndex to choice)
                        },
                    )
                }

                if (isAnswered) {
                    Text(
                        if (isCorrect) "✅ 맞아요!" else "❌ 정답은 「${currentQuestion?.answer}」예요",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isCorrect) Color(0xFF2E7D32) else Color(0xFFC62828),
                    )
                    Button(onClick = { advance() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isLast) "완료" else "다음 질문 →")
                    }
                }
            }
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

@Composable
private fun QuestionCard(
    question: Question,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                question.question,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()
            question.choices.forEach { choice ->
                val isCorrect = choice == question.answer
                val isSelected = selected == choice
                val bgColor: Color = when {
                    selected == null -> MaterialTheme.colorScheme.surface
                    isSelected && isCorrect -> Color(0xFF81C784)
                    isSelected && !isCorrect -> Color(0xFFEF9A9A)
                    isCorrect -> Color(0xFF81C784)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val textColor: Color = when {
                    selected == null -> MaterialTheme.colorScheme.onSurface
                    isSelected || isCorrect -> Color.White
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                }
                val animatedBg by animateColorAsState(bgColor, tween(300), label = "qBg")
                val animatedText by animateColorAsState(textColor, tween(300), label = "qText")

                Button(
                    onClick = { onSelect(choice) },
                    enabled = selected == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = animatedBg,
                        contentColor = animatedText,
                        disabledContainerColor = animatedBg,
                        disabledContentColor = animatedText,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(choice, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
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
