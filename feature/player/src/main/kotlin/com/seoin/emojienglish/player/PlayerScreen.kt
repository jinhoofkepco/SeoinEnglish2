package com.seoin.emojienglish.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seoin.emojienglish.designsystem.ContentColumn
import com.seoin.emojienglish.designsystem.StepNavigatorBar
import com.seoin.emojienglish.designsystem.UnsupportedStepCard

/**
 * The study screen (§7, 요구사항 ①④⑦).
 *
 * Top = navigator bar (title 1/3 + step chips + master chip). No bottom CTA bar:
 * the "다음 단계" button floats above the global thin bar and only appears once
 * the step is complete (요구사항 ④). With master mode on, each step renders its
 * own activity view instead of the student screen.
 */
@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    onHome: () -> Unit,
    onOpenMaster: () -> Unit,
    vm: LessonPlayerViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()

    // 오늘 학습을 다 끝내도 화면을 닫지 않고 그대로 두고 "다 했어요"만 표시(피드백 #1).
    // 다른 곳으로 가려면 상단 제목(→홈)이나 칩을 쓰면 된다.

    Column(Modifier.fillMaxSize()) {
        StepNavigatorBar(
            title = state.sourceLabel,
            chips = state.chips,
            currentIndex = state.index,
            onTitleClick = onHome,
            onChipClick = vm::goTo,
            chipEnabled = vm::chipEnabled,
            showMasterChip = true,
            onMasterClick = onOpenMaster,
        )

        Box(Modifier.fillMaxSize()) {
            ContentColumn {
                val feature = state.stepFeature
                val item = state.currentItem
                when {
                    item == null ->
                        Text("학습할 단계가 없습니다.", style = MaterialTheme.typography.bodyLarge)

                    state.masterMode && feature != null && state.spec != null ->
                        feature.MasterView(
                            spec = state.spec!!,
                            trace = state.masterSnapshot ?: com.seoin.emojienglish.model.StepTraceSnapshot(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                    feature != null && state.spec != null ->
                        feature.StudentScreen(
                            spec = state.spec!!,
                            session = vm.sessionFor(item, state.index),
                            modifier = Modifier.fillMaxWidth(),
                        )

                    state.parseError != null -> {
                        UnsupportedStepCard(state.rawType)
                        Text(
                            "파싱 오류: ${state.parseError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> UnsupportedStepCard(state.rawType)
                }
            }

            // Floating "다음 단계": hidden until the step is complete, student mode only.
            // Bottom padding clears the global thin bar + the mini voice panel so it
            // is never hidden behind them (요구사항 ④). Hidden once everything is done.
            if (!state.masterMode && state.isCurrentComplete && !finished) {
                Button(
                    onClick = vm::goNext,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 84.dp),
                ) {
                    Text(if (state.isLast) "학습 종료" else "다음 단계")
                }
            }
            // On finishing, the screen stays put (피드백 #1) — the "다 했어요"
            // indicator now lives on the home "오늘의 할일" list instead.
        }
    }
}
