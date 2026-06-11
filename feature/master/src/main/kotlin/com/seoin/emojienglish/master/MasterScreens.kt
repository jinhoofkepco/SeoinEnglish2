package com.seoin.emojienglish.master

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seoin.emojienglish.designsystem.Pill

/**
 * Central master dashboard (요구사항 ⑦, log-only for now). Reached from the
 * navigator bar's "마스터" chip. Each log line links into that step's master view.
 */
@Composable
fun MasterScreen(
    onOpenStep: (bookId: String, unitId: String, stepIndex: Int) -> Unit,
    vm: MasterViewModel = hiltViewModel(),
) {
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill("👩‍🏫 마스터 대시보드")
        Text("학습 로그", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (!unlocked) {
            Text(
                "마스터 모드가 꺼져 있습니다. 하단바를 펼쳐 🔒 마스터 버튼으로 잠금 해제하세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            return@Column
        }

        if (log.isEmpty()) {
            Text("아직 학습 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Text(
            "한 줄을 누르면 해당 스텝의 활동 화면으로 이동합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(log) { row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenStep(row.bookId, row.unitId, row.stepIndex) }
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("${row.time}  ·  ${row.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(row.subtitle, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
