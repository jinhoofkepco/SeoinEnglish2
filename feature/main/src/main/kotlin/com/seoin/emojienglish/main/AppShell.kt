package com.seoin.emojienglish.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val CollapsedBarHeight = 32.dp

/**
 * The app shell. Provides the **global thin bottom bar** (요구사항 ⑤⑥) over every
 * screen, plus the global voice sheet and the master PIN dialog. Each screen
 * owns its own top region (home layout vs. the study navigator bar), so the
 * shell no longer switches route-based chrome.
 */
@Composable
fun AppShell(
    vm: MainViewModel,
    content: @Composable (Modifier) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    val masterOn by vm.masterUnlocked.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Content reserves space for the thin bottom bar + Android nav bar.
        content(
            Modifier
                .fillMaxSize()
                .padding(bottom = CollapsedBarHeight)
                .windowInsetsPadding(WindowInsets.navigationBars),
        )

        // The persistent voice panel floats directly above the thin bottom bar and
        // survives navigation (한 세트 한 보이스). Empty when no session is active.
        Column(Modifier.align(Alignment.BottomCenter)) {
            VoicePanel(vm)
            ThinBottomBar(
                expanded = expanded,
                masterOn = masterOn,
                onToggleExpand = { expanded = !expanded },
                onVoice = vm::openFreeTalkVoice,
                onMaster = { if (!vm.toggleMaster()) showPin = true },
            )
        }
    }

    if (showPin) {
        MasterPinDialog(
            onDismiss = { showPin = false },
            onSubmit = { pin, keep -> vm.submitPin(pin, keep) },
        )
    }
}

@Composable
private fun ThinBottomBar(
    expanded: Boolean,
    masterOn: Boolean,
    onToggleExpand: () -> Unit,
    onVoice: () -> Unit,
    onMaster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        tonalElevation = 3.dp,
        color = if (masterOn) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (expanded) 56.dp else CollapsedBarHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bottom-left collapse/expand toggle (요구사항 ⑤).
            IconButton(onClick = onToggleExpand) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (expanded) "접기" else "펴기",
                )
            }

            if (!expanded && masterOn) {
                Text("마스터 ON", style = MaterialTheme.typography.labelSmall)
            }

            Box(Modifier.weight(1f))

            AnimatedVisibility(visible = expanded) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onVoice) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Text(" Voice")
                    }
                    TextButton(onClick = onMaster) {
                        Icon(
                            if (masterOn) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = null,
                        )
                        Text(if (masterOn) " 마스터 ON" else " 마스터")
                    }
                }
            }
        }
    }
}
