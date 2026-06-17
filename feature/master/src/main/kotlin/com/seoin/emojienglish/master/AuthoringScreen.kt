package com.seoin.emojienglish.master

import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthoringScreen(
    onBack: () -> Unit,
    vm: AuthoringViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val reload by vm.webReloads.collectAsStateWithLifecycle()
    val http431 by vm.http431State.collectAsStateWithLifecycle()
    var showAdvanced by remember { mutableStateOf(false) }
    var confirmClearAllCookies by remember { mutableStateOf(false) }

    val passagePhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> vm.addPassagePhotos(uris) }
    val wordPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> vm.addWordPhotos(uris) }

    val buttonPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
    val canRun = !s.running && (s.passagePhotoCount > 0 || s.wordPhotoCount > 0 || s.source.isNotBlank())

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack, contentPadding = buttonPadding) { Text("뒤로") }
            Text("콘텐츠 만들기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                if (s.unitId.isBlank()) "ID 자동" else s.unitId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                contentPadding = buttonPadding,
            ) { Text(if (showAdvanced) "접기" else "고급") }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = s.unitTitle,
                onValueChange = vm::setUnitTitle,
                label = { Text("단원명") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.source,
                onValueChange = vm::setSource,
                label = { Text("텍스트") },
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )

            if (showAdvanced) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = s.bookTitle,
                        onValueChange = vm::setBookTitle,
                        label = { Text("책") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = s.unitId,
                        onValueChange = vm::setUnitId,
                        label = { Text("단원 ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(
                    onClick = vm::runResponseMenuProbe,
                    enabled = !s.running,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = buttonPadding,
                ) { Text("대기테스트", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { passagePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !s.running,
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = buttonPadding,
                ) { Text(if (s.passagePhotoCount > 0) "지문 ${s.passagePhotoCount}" else "지문사진", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                OutlinedButton(
                    onClick = { wordPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !s.running,
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = buttonPadding,
                ) { Text(if (s.wordPhotoCount > 0) "단어 ${s.wordPhotoCount}" else "단어사진", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                Button(
                    onClick = vm::runAllAuto,
                    enabled = canRun,
                    modifier = Modifier.weight(1.05f).height(34.dp),
                    contentPadding = buttonPadding,
                ) { Text("전체자동", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = vm::extractSentencesAuto,
                    enabled = !s.running && (s.passagePhotoCount > 0 || s.source.isNotBlank()),
                    modifier = Modifier.weight(1f).height(32.dp),
                    contentPadding = buttonPadding,
                ) { Text("문장", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                OutlinedButton(
                    onClick = vm::buildPassageAndSave,
                    enabled = !s.running && s.sentences.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(32.dp),
                    contentPadding = buttonPadding,
                ) { Text("지문저장", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                OutlinedButton(
                    onClick = vm::extractWordsAuto,
                    enabled = !s.running && (s.wordPhotoCount > 0 || s.source.isNotBlank()),
                    modifier = Modifier.weight(1f).height(32.dp),
                    contentPadding = buttonPadding,
                ) { Text("단어", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                OutlinedButton(
                    onClick = vm::buildAndSave,
                    enabled = !s.running && s.words.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(32.dp),
                    contentPadding = buttonPadding,
                ) { Text("만화저장", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = vm::requestGridImage,
                    enabled = !s.running && s.words.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(32.dp),
                    contentPadding = buttonPadding,
                ) { Text("그림요청", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                OutlinedButton(
                    onClick = vm::captureGridImage,
                    enabled = !s.running,
                    modifier = Modifier.weight(1f).height(32.dp),
                    contentPadding = buttonPadding,
                ) { Text("그림저장", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                Text(
                    "문장 ${s.sentences.size} · 단어 ${s.words.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1.25f).align(Alignment.CenterVertically),
                    maxLines = 1,
                )
            }

            if (s.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Text(s.status, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            } else {
                if (s.status.isNotBlank()) {
                    Text(s.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                }
                s.error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
            }

            if (http431.active) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "ChatGPT 로드 오류 431: 쿠키/헤더가 너무 커졌습니다. 자동 쿠키 삭제는 하지 않았습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                    Text(
                        "host=${http431.host} · url=${http431.requestUrlLength} · q=${http431.qQueryLength} · cookies=${http431.cookieCount} · conv_key=${http431.convKeyCookieCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = vm::retryChatGptAfter431,
                            modifier = Modifier.weight(1f).height(32.dp),
                            contentPadding = buttonPadding,
                        ) { Text("다시 시도", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                        OutlinedButton(
                            onClick = vm::openChatGptInBrowser,
                            modifier = Modifier.weight(1f).height(32.dp),
                            contentPadding = buttonPadding,
                        ) { Text("브라우저", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        if (http431.convKeyCookieCount > 0) {
                            OutlinedButton(
                                onClick = vm::clearTemporaryChatCookiesAfter431,
                                modifier = Modifier.weight(1f).height(32.dp),
                                contentPadding = buttonPadding,
                            ) { Text("임시 채팅 쿠키 정리", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                        }
                        OutlinedButton(
                            onClick = { confirmClearAllCookies = true },
                            modifier = Modifier.weight(1f).height(32.dp),
                            contentPadding = buttonPadding,
                        ) { Text("앱 내 WebView 쿠키 전체 초기화", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
            }
        }

        Text(
            "ChatGPT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            key(reload) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { _ ->
                        val wv = vm.provideView()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv
                    },
                )
            }
        }
    }

    if (confirmClearAllCookies) {
        AlertDialog(
            onDismissRequest = { confirmClearAllCookies = false },
            title = { Text("앱 내 WebView 쿠키 전체 초기화") },
            text = {
                Text("ChatGPT 로그인 쿠키를 포함해 앱 안의 모든 WebView 쿠키가 삭제됩니다. 음성/그림/저작 WebView에서 다시 로그인이 필요할 수 있습니다.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearAllCookies = false
                        vm.clearAllInAppWebViewCookiesAfterConfirmation()
                    },
                ) { Text("초기화") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAllCookies = false }) { Text("취소") }
            },
        )
    }
}
