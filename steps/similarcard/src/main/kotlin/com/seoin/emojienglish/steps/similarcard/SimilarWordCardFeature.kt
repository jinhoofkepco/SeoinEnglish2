package com.seoin.emojienglish.steps.similarcard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.seoin.emojienglish.step.resultSummaries
import com.seoin.emojienglish.step.timeOrderedActivities
import com.seoin.emojienglish.step.params
import com.seoin.emojienglish.step.requireString
import com.seoin.emojienglish.step.stepId
import com.seoin.emojienglish.step.stringList
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class SimilarCardSpec(
    override val stepId: String,
    val wordId: String,
    val answer: String,
    val choices: List<String>,
) : StepSpec

class SimilarWordCardFeature @Inject constructor() : StepFeature {
    override val type: String = "similar_word_card"

    override fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec {
        val p = stepJson.params()
        return SimilarCardSpec(
            stepId = stepJson.stepId(),
            wordId = p.requireString("wordId"),
            answer = p.requireString("answer"),
            choices = p.stringList("choices"),
        )
    }

    @Composable
    override fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier) {
        val s = spec as SimilarCardSpec
        val word = session.content.word(s.wordId)
        val shuffledChoices = remember { s.choices.shuffled() }

        var selected by remember { mutableStateOf<String?>(null) }

        val isCorrect = selected != null && selected == s.answer

        // Auto-complete after showing feedback.
        LaunchedEffect(selected) {
            if (selected != null) {
                delay(1200)
                session.trace("answer_submit", mapOf("selected" to (selected ?: ""), "correct" to isCorrect.toString()))
                session.complete(
                    StepResult.Scored(
                        selected = selected ?: "",
                        answer = s.answer,
                        score = if (isCorrect) 1 else 0,
                        maxScore = 1,
                    ),
                )
            }
        }

        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "비슷한 뜻 고르기",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                // Word card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (word?.emoji?.isNotBlank() == true) {
                            Text(word.emoji, fontSize = 56.sp)
                        }
                        Text(
                            word?.text ?: s.wordId,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (word?.meaningKo?.isNotBlank() == true) {
                            Text(
                                word.meaningKo,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                        if (word?.example?.isNotBlank() == true) {
                            Text(
                                "\"${word.example}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Text(
                    "비슷한 뜻의 단어는?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline,
                )

                // Choice buttons in a 2×2 grid
                val half = shuffledChoices.size / 2
                listOf(
                    shuffledChoices.take(half),
                    shuffledChoices.drop(half),
                ).forEach { rowChoices ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowChoices.forEach { choice ->
                            ChoiceButton(
                                text = choice,
                                isCorrect = choice == s.answer,
                                selected = selected,
                                onClick = { if (selected == null) selected = choice },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (selected != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isCorrect) "✅ 정답이에요!" else "❌ 정답은 「${s.answer}」예요",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isCorrect) Color(0xFF2E7D32) else Color(0xFFC62828),
                    )
                }
            }
        }
    }

    @Composable
    override fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier) {
        DummyMasterScaffold(
            title = "비슷한 말 카드 — 기록",
            resultLines = trace.resultSummaries(),
            activities = trace.timeOrderedActivities().map {
                MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail)
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    isCorrect: Boolean,
    selected: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor: Color = when {
        selected == null -> MaterialTheme.colorScheme.secondaryContainer
        selected == text && isCorrect -> Color(0xFF81C784) // selected correct → green
        selected == text && !isCorrect -> Color(0xFFEF9A9A) // selected wrong → red
        isCorrect && selected != null -> Color(0xFF81C784) // reveal correct
        else -> MaterialTheme.colorScheme.surfaceVariant // other, dim
    }
    val textColor: Color = when {
        selected == null -> MaterialTheme.colorScheme.onSecondaryContainer
        selected == text -> Color.White
        isCorrect && selected != null -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val animatedBg by animateColorAsState(bgColor, tween(300), label = "choiceBg")
    val animatedText by animateColorAsState(textColor, tween(300), label = "choiceText")

    Button(
        onClick = onClick,
        enabled = selected == null,
        colors = ButtonDefaults.buttonColors(
            containerColor = animatedBg,
            contentColor = animatedText,
            disabledContainerColor = animatedBg,
            disabledContentColor = animatedText,
        ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp),
        modifier = modifier.height(64.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface SimilarCardBindModule {
    @Binds
    @IntoMap
    @StringKey("similar_word_card")
    fun bind(impl: SimilarWordCardFeature): StepFeature
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun SimilarCardPreview() {
    SimilarWordCardFeature().StudentScreen(
        spec = SimilarCardSpec("s3", "w_delicious", "tasty", listOf("noisy", "tasty", "empty", "late")),
        session = FakeStepSession(),
        modifier = Modifier,
    )
}
