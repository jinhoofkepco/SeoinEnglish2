package com.seoin.emojienglish.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** epoch-millis → "HH:mm:ss" for master activity timestamps. */
fun formatClock(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

/**
 * Shared placeholder UI for **dummy step modules** (학습 화면).
 *
 * Lets `main + dummy steps` complete the whole app outline now; each real
 * StudentScreen is written in its own session later by swapping this call for
 * the real UI — nothing else changes (§0.2, §0.3).
 *
 * Note: the "다음 단계" CTA is owned by the Player (요구사항 ④), not here, so this
 * scaffold's button only signals completion.
 */
@Composable
fun DummyStudentScaffold(
    emoji: String,
    title: String,
    typeLabel: String,
    summaryLines: List<String>,
    completeButtonText: String = "완료",
    modifier: Modifier = Modifier,
    onComplete: () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("$emoji  $title", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Pill("type · $typeLabel")
        StepRuleBox(
            title = "준비 중인 스텝 (더미)",
            body = "이 스텝의 학습 화면은 다른 세션에서 구현됩니다. 아래는 JSON에서 파싱된 파라미터 요약입니다.",
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (summaryLines.isEmpty()) {
                    Text("(파라미터 없음)", style = MaterialTheme.typography.bodyMedium)
                } else {
                    summaryLines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
            Text(completeButtonText)
        }
    }
}

/** One time-ordered activity entry for a step's master view (요구사항 ⑦). */
data class MasterActivityRow(
    val time: String,
    val title: String,
    val subtitle: String,
    val detail: String,
)

/**
 * Shared placeholder for a dummy step's MasterView (§0.4, §11.2, 요구사항 ⑦).
 *
 * Draws only from the trace snapshot it is handed — never live student state.
 * Shows per-occurrence results plus a **time-ordered activity list**; tapping a
 * row reveals its detail inline (real steps replace this with the step-specific
 * view, e.g. a quiz showing the student's answer vs. the correct answer).
 */
@Composable
fun DummyMasterScaffold(
    title: String,
    resultLines: List<String>,
    activities: List<MasterActivityRow>,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableIntStateOf(-1) }
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Pill("👩‍🏫 마스터 보기 (읽기 전용)")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("회차별 결과", style = MaterialTheme.typography.titleSmall)
                if (resultLines.isEmpty()) {
                    Text("아직 기록 없음", style = MaterialTheme.typography.bodySmall)
                } else {
                    resultLines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Text("활동 (시간순)", style = MaterialTheme.typography.titleSmall)
        if (activities.isEmpty()) {
            Text("활동 기록이 없습니다.", style = MaterialTheme.typography.bodySmall)
        } else {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    activities.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (selected == i) -1 else i }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "${row.time}  ·  ${row.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (row.subtitle.isNotEmpty()) {
                                Text(row.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                            if (selected == i && row.detail.isNotEmpty()) {
                                Text(
                                    row.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
