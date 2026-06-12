package com.seoin.emojienglish.steps.storycomic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seoin.emojienglish.step.StepSession
import kotlinx.coroutines.delay

/**
 * 단어 찾기 퀴즈 — 만화를 다 본 뒤의 회상(retrieval) 연습.
 *
 * 전작의 comprehension-check 철학을 따른다: 선택지를 따로 만들지 않고
 * **방금 본 컷에서 직접 고른다**. 캡션은 숨긴 채 그림만 보여주므로,
 * 아이는 단어의 뜻과 장면 이미지를 직접 연결해야 한다 (시각-의미 연합).
 *
 * 채점은 "처음에 바로 맞힌 단어 수" — 틀려도 다시 고를 수 있어 좌절 없이
 * 끝까지 가지만, 마스터 기록에는 첫 시도 정확도가 남는다.
 */
@Composable
internal fun StoryFindQuiz(
    data: StoryComicData,
    session: StepSession,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 캡션에 단어가 실제로 등장하는 컷만 출제 대상.
    val quizItems = remember(data) {
        data.words.mapNotNull { word ->
            val idx = data.panels.indexOfFirst { wordRegex(word).containsMatchIn(it.caption) }
            if (idx >= 0) word to idx else null
        }.shuffled()
    }

    if (quizItems.isEmpty()) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    var qIndex by remember { mutableIntStateOf(0) }
    var wrongPicks by remember { mutableStateOf(setOf<Int>()) }
    var solved by remember { mutableStateOf(false) }
    var firstTryCount by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    val (word, answerIndex) = quizItems[qIndex]

    fun resetQuiz() {
        qIndex = 0
        wrongPicks = emptySet()
        solved = false
        firstTryCount = 0
        finished = false
    }

    // 문제 제시 = 단어 낭독 (귀로도 단어 형태 한 번 더).
    LaunchedEffect(qIndex, finished) {
        if (!finished) {
            session.trace("quiz_question", mapOf("word" to word))
            session.speak("Which panel shows. $word?")
        }
    }

    // 정답 후 잠깐 ✅를 보여주고 다음 문제로.
    LaunchedEffect(solved) {
        if (solved) {
            delay(1100)
            if (qIndex < quizItems.lastIndex) {
                qIndex++
                wrongPicks = emptySet()
                solved = false
            } else {
                session.trace(
                    "quiz_done",
                    mapOf("firstTry" to firstTryCount.toString(), "total" to quizItems.size.toString()),
                )
                finished = true
            }
        }
    }

    Column(
        modifier.fillMaxSize().padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (finished) {
            Spacer(Modifier.padding(top = 24.dp))
            Text("🎉", fontSize = 64.sp)
            Text("퀴즈 끝!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "처음에 바로 맞힌 단어: $firstTryCount / ${quizItems.size}",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { resetQuiz() }, modifier = Modifier.weight(1f)) {
                    Text("🔄 다시 풀기")
                }
                Button(onClick = onExit, modifier = Modifier.weight(1f)) {
                    Text("만화로 돌아가기")
                }
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔍 단어 찾기 퀴즈", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${qIndex + 1} / ${quizItems.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        LinearProgressIndicator(
            progress = { (qIndex + 1).toFloat() / quizItems.size },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            word,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFEA6A22),
            textAlign = TextAlign.Center,
        )
        Text(
            "이 단어가 나온 장면을 골라보세요!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )

        // 컷 썸네일 그리드 (캡션 없이 그림만 — 이미지로 기억해내기).
        data.panels.indices.chunked(3).forEach { rowIndices ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowIndices.forEach { i ->
                    val isWrong = i in wrongPicks
                    val isRight = solved && i == answerIndex
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                when {
                                    isRight -> Modifier.border(4.dp, Color(0xFF2E7D32), RoundedCornerShape(12.dp))
                                    isWrong -> Modifier.border(3.dp, Color(0xFFC62828), RoundedCornerShape(12.dp))
                                    else -> Modifier
                                },
                            )
                            .clickable(enabled = !solved && !isWrong) {
                                if (i == answerIndex) {
                                    if (wrongPicks.isEmpty()) firstTryCount++
                                    solved = true
                                    session.trace(
                                        "quiz_answer",
                                        mapOf("word" to word, "panel" to i.toString(), "correct" to "true",
                                              "firstTry" to wrongPicks.isEmpty().toString()),
                                    )
                                    session.speak("Great! $word!")
                                } else {
                                    wrongPicks = wrongPicks + i
                                    session.trace(
                                        "quiz_answer",
                                        mapOf("word" to word, "panel" to i.toString(), "correct" to "false"),
                                    )
                                    session.speak("Try again!")
                                }
                            },
                    ) {
                        StoryComicPanelView(
                            panel = data.panels[i],
                            animate = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (isWrong) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) { Text("❌", fontSize = 28.sp) }
                        }
                        if (isRight) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("✅", fontSize = 36.sp)
                            }
                        }
                    }
                }
                repeat(3 - rowIndices.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text("그만하고 만화로 돌아가기")
        }
    }
}
