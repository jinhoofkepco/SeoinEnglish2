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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 마스터 저작 화면 — 단어만화(story_comic) 단원을 GPT로 만든다.
 * 하단에 저작 전용 ChatGPT 웹뷰를 띄워 **로그인·생성 과정 확인·이미지 수동 다운로드**를
 * 할 수 있게 한다.
 */
@Composable
fun AuthoringScreen(
    onBack: () -> Unit,
    vm: AuthoringViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val reload by vm.webReloads.collectAsStateWithLifecycle()

    val passagePhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> vm.addPassagePhotos(uris) }
    val wordPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> vm.addWordPhotos(uris) }
    val smallButtonPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("← 뒤로") }
            Text("콘텐츠 만들기", style = MaterialTheme.typography.titleMedium)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = s.bookTitle, onValueChange = vm::setBookTitle,
                label = { Text("책 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("책 ID: ${s.bookId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            OutlinedTextField(
                value = s.unitTitle, onValueChange = vm::setUnitTitle,
                label = { Text("단원 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.unitId, onValueChange = vm::setUnitId,
                label = { Text("단원 ID (영문/숫자)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.source, onValueChange = vm::setSource,
                label = { Text("소스 텍스트 (지문 문장 또는 단어만화 주제)") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { passagePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !s.running,
                        modifier = Modifier.weight(1.15f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) {
                        Text(
                            if (s.passagePhotoCount > 0) "지문${s.passagePhotoCount}" else "지문사진",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { wordPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !s.running,
                        modifier = Modifier.weight(1.15f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) {
                        Text(
                            if (s.wordPhotoCount > 0) "단어${s.wordPhotoCount}" else "단어사진",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    Button(
                        onClick = vm::runAllAuto,
                        enabled = !s.running && s.unitId.isNotBlank() &&
                            (s.passagePhotoCount > 0 || s.wordPhotoCount > 0 || s.source.isNotBlank()),
                        modifier = Modifier.weight(1.35f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) { Text("전체자동", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = vm::extractSentencesAuto,
                        enabled = !s.running && (s.passagePhotoCount > 0 || s.source.isNotBlank()),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) { Text("문장", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    OutlinedButton(
                        onClick = vm::buildPassageAndSave,
                        enabled = !s.running && s.sentences.isNotEmpty(),
                        modifier = Modifier.weight(1.15f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) { Text("지문저장", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    OutlinedButton(
                        onClick = vm::extractWordsAuto,
                        enabled = !s.running && (s.wordPhotoCount > 0 || s.source.isNotBlank()),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) { Text("단어", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    OutlinedButton(
                        onClick = vm::buildAndSave,
                        enabled = !s.running && s.words.isNotEmpty(),
                        modifier = Modifier.weight(1.15f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) { Text("만화JSON", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = vm::requestGridImage,
                        enabled = !s.running && s.words.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) { Text("그림", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    OutlinedButton(
                        onClick = vm::captureGridImage,
                        enabled = !s.running && s.unitId.isNotBlank(),
                        modifier = Modifier.weight(1.1f).height(38.dp),
                        contentPadding = smallButtonPadding,
                    ) { Text("그림저장", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
            }

            if (s.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Text(s.status, style = MaterialTheme.typography.bodySmall)
                }
            } else if (s.status.isNotBlank()) {
                Text(s.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            s.error?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            if (s.words.isNotEmpty()) {
                Text("추출 단어: ${s.words.joinToString(", ")}", style = MaterialTheme.typography.labelMedium)
            }
            if (s.sentences.isNotEmpty()) {
                Text(
                    "추출 문장: ${s.sentences.take(4).joinToString(" / ")}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (s.unitJsonPreview.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        s.unitJsonPreview.take(2000),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        // GPT 웹뷰 — 로그인·생성 확인·이미지 수동 다운로드용.
        Text(
            "ChatGPT 창: 지문사진과 단어사진은 따로 첨부 / 여러 번 선택하면 누적 / 전체자동은 들어온 묶음만 생성.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Box(Modifier.fillMaxWidth().height(520.dp)) {
            // 렌더러가 죽으면 webReloads 가 증가 → key 가 바뀌어 새 웹뷰를 다시 붙인다.
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
}
