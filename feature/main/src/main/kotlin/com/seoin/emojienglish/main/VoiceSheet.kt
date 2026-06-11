package com.seoin.emojienglish.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Global GPT-Voice bottom sheet (§3, §9.3). Appears whenever a [VoicePrompt] is
 * pushed to the [com.seoin.emojienglish.voice.VoiceController] — from the chrome
 * 🎙 button or from a step's `session.requestVoice(...)`.
 *
 * Skeleton scope: shows the resolved context + variables. The real coaching turn
 * loop (Appendix A) is wired in M2′/M8; the persona/system text comes from
 * `templates.json` once the `서인영어_brain2` doc lands (§14) — code unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheetHost(vm: MainViewModel) {
    val prompt by vm.activeVoicePrompt.collectAsStateWithLifecycle()
    val current = prompt ?: return

    ModalBottomSheet(onDismissRequest = vm::closeVoice) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🎙 GPT Voice 코칭", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (current.contextLabel.isNotEmpty()) {
                Text(current.contextLabel, style = MaterialTheme.typography.titleSmall)
            }
            Text("템플릿: ${current.templateId}", style = MaterialTheme.typography.bodyMedium)
            if (current.variables.isNotEmpty()) {
                current.variables.forEach { (k, v) ->
                    Text("• $k = $v", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "실제 음성 코칭(WebView 자동화)은 M2′에서 연결됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
