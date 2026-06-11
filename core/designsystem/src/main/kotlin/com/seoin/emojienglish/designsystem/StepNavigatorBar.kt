package com.seoin.emojienglish.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ChipState { DONE, CURRENT, UPCOMING }

data class NavStepChip(val label: String, val emoji: String, val state: ChipState)

/**
 * The top **navigator bar** (요구사항 ①). One row:
 *  - left ~1/3: the source title (book/unit or "오늘의 할일") — tap → home (②).
 *  - right ~2/3: step chips, the current one scrolled to the front, horizontally
 *    scrollable. A trailing "마스터" chip (⑦) opens the central dashboard.
 *
 * Chip tappability is decided by the caller via [chipEnabled] (학생=완료한 것만,
 * 마스터=전부).
 */
@Composable
fun StepNavigatorBar(
    title: String,
    chips: List<NavStepChip>,
    currentIndex: Int,
    onTitleClick: () -> Unit,
    onChipClick: (Int) -> Unit,
    chipEnabled: (Int) -> Boolean,
    showMasterChip: Boolean,
    onMasterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onTitleClick,
                modifier = Modifier.weight(0.34f),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val listState = rememberLazyListState()
            LaunchedEffect(currentIndex) {
                if (currentIndex in chips.indices) listState.animateScrollToItem(currentIndex)
            }

            LazyRow(
                state = listState,
                modifier = Modifier
                    .weight(0.66f)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(chips.size) { i ->
                    val chip = chips[i]
                    val selected = chip.state == ChipState.CURRENT
                    AssistChip(
                        onClick = { if (chipEnabled(i)) onChipClick(i) },
                        enabled = chipEnabled(i),
                        label = { Text("${chip.emoji} ${chip.label}", maxLines = 1) },
                        colors = if (selected) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                labelColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                    )
                }
                if (showMasterChip) {
                    item {
                        AssistChip(
                            onClick = onMasterClick,
                            label = { Text("마스터", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}
