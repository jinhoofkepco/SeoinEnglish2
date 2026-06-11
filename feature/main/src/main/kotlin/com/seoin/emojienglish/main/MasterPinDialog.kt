package com.seoin.emojienglish.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Master PIN dialog (요구사항 ⑥⑦). The "마스터 모드 유지" checkbox sets the
 * ephemeral keep-flag so master↔student can be flipped with no PIN for the rest
 * of the run (resets on app restart) — for observing side-by-side.
 */
@Composable
fun MasterPinDialog(
    onDismiss: () -> Unit,
    onSubmit: (pin: String, keep: Boolean) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("마스터 모드") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PIN을 입력하세요. (기본값 0000)", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; error = false },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error,
                )
                if (error) Text("PIN이 올바르지 않습니다.", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keep, onCheckedChange = { keep = it })
                    Text("마스터 모드 유지 (PIN 없이 전환 · 재시작 시 해제)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSubmit(pin, keep)) onDismiss() else error = true }) {
                Text("확인")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
