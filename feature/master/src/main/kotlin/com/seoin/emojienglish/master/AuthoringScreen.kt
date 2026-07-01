package com.seoin.emojienglish.master

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class PhotoRangeTarget { Passage, Word }

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
    var showSourceDialog by remember { mutableStateOf(false) }
    var showRepairDialog by remember { mutableStateOf(false) }
    var showWordCardDialog by remember { mutableStateOf(false) }
    var sourceDraft by remember { mutableStateOf("") }
    val sourceFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val rootView = LocalView.current
    val repairChoice = s.repairChoices.getOrNull(s.repairChoiceIndex)
    var pendingRangeTarget by remember { mutableStateOf<PhotoRangeTarget?>(null) }

    DisposableEffect(rootView) {
        onDispose { rootView.keepScreenOn = false }
    }
    LaunchedEffect(Unit) {
        vm.clearAuthoringDraftOnEntry()
    }
    LaunchedEffect(rootView, s.running) {
        rootView.keepScreenOn = s.running
    }
    LaunchedEffect(s.wordCardCandidateVersion) {
        if (s.wordCardCandidateVersion > 0 && s.wordCardCandidates.isNotEmpty()) {
            showWordCardDialog = true
        }
    }

    val passagePhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> vm.addPassagePhotos(uris) }
    val wordPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris -> vm.addWordPhotos(uris) }
    val passageRangePhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2),
    ) { uris -> vm.addPassagePhotoRange(uris) }
    val wordRangePhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2),
    ) { uris -> vm.addWordPhotoRange(uris) }
    val rangePermission = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
        else -> null
    }
    val rangePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingRangeTarget
        pendingRangeTarget = null
        if (!granted || target == null) {
            vm.showPhotoRangePermissionDenied()
        } else {
            when (target) {
                PhotoRangeTarget.Passage -> passageRangePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                PhotoRangeTarget.Word -> wordRangePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }
    fun launchRangePicker(target: PhotoRangeTarget) {
        val permission = rangePermission
        if (permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            when (target) {
                PhotoRangeTarget.Passage -> passageRangePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                PhotoRangeTarget.Word -> wordRangePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        } else {
            pendingRangeTarget = target
            rangePermissionLauncher.launch(permission)
        }
    }

    val buttonPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
    val canRun = s.running || s.passagePhotoCount > 0 || s.wordPhotoCount > 0 || s.source.isNotBlank()

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
                .heightIn(max = 210.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = s.unitTitle,
                    onValueChange = vm::setUnitTitle,
                    label = { Text("단원명") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                PhotoTokenButton(
                    label = "지",
                    count = s.passagePhotoCount,
                    enabled = !s.running,
                    onClick = { passagePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                PhotoTokenButton(
                    label = "단",
                    count = s.wordPhotoCount,
                    enabled = !s.running,
                    onClick = { wordPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                Button(
                    onClick = {
                        if (s.running) vm.cancelCurrentWork() else vm.runAllAuto()
                    },
                    enabled = canRun,
                    modifier = Modifier.height(36.dp).widthIn(min = 54.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text(if (s.running) "중지" else "생성", fontSize = 11.sp, maxLines = 1) }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                TinyActionButton(
                    label = if (s.source.isBlank()) "텍" else "텍✓",
                    active = s.source.isNotBlank(),
                    enabled = !s.running,
                    onClick = {
                        sourceDraft = s.source
                        showSourceDialog = true
                    },
                )
                TinyActionButton(
                    label = "지범",
                    enabled = !s.running,
                    onClick = { launchRangePicker(PhotoRangeTarget.Passage) },
                )
                TinyActionButton(
                    label = "단범",
                    enabled = !s.running,
                    onClick = { launchRangePicker(PhotoRangeTarget.Word) },
                )
                TinyActionButton(
                    label = "문",
                    enabled = !s.running && (s.passagePhotoCount > 0 || s.source.isNotBlank()),
                    onClick = vm::extractSentencesAuto,
                )
                TinyActionButton(
                    label = "지저",
                    enabled = !s.running && s.sentences.isNotEmpty(),
                    onClick = vm::buildPassageAndSave,
                )
                TinyActionButton(
                    label = "단카",
                    enabled = !s.running && (s.passagePhotoCount > 0 || s.source.isNotBlank()),
                    active = s.wordCardCandidates.isNotEmpty(),
                    onClick = {
                        if (s.wordCardCandidates.isNotEmpty()) showWordCardDialog = true
                        else vm.prepareWordCardCandidates()
                    },
                )
                TinyActionButton(
                    label = "단카2",
                    enabled = !s.running && s.wordPhotoCount > 0,
                    onClick = vm::buildWordCardFromDefinitionPhotos,
                )
                TinyActionButton(
                    label = "단",
                    enabled = !s.running && (s.wordPhotoCount > 0 || s.source.isNotBlank()),
                    onClick = vm::extractWordsAuto,
                )
                TinyActionButton(
                    label = "만저",
                    enabled = !s.running && s.words.isNotEmpty(),
                    onClick = vm::buildAndSave,
                )
                TinyActionButton(
                    label = "그요",
                    enabled = !s.running && s.words.isNotEmpty(),
                    onClick = vm::requestGridImage,
                )
                TinyActionButton(
                    label = "그저",
                    enabled = !s.running,
                    onClick = vm::captureGridImage,
                )
                TinyActionButton(
                    label = "그림다시",
                    enabled = !s.running,
                    onClick = {
                        vm.refreshRepairChoices()
                        showRepairDialog = true
                    },
                )
                TinyActionButton(
                    label = "보정",
                    enabled = !s.running && repairChoice != null,
                    onClick = vm::repairSelectedUnitImages,
                )
                if (showAdvanced) {
                    TinyActionButton(
                        label = "대기",
                        enabled = !s.running,
                        onClick = vm::runResponseMenuProbe,
                    )
                }
                Text(
                    repairChoice?.let {
                        "그림 ${s.repairChoiceIndex + 1}/${s.repairChoices.size}"
                    } ?: "문${s.sentences.size} 단${s.words.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

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

    if (showSourceDialog) {
        LaunchedEffect(Unit) {
            sourceFocusRequester.requestFocus()
            keyboard?.show()
        }
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("텍스트") },
            text = {
                OutlinedTextField(
                    value = sourceDraft,
                    onValueChange = { sourceDraft = it },
                    modifier = Modifier.fillMaxWidth().height(180.dp).focusRequester(sourceFocusRequester),
                    label = { Text("직접 입력") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.setSource(sourceDraft)
                        showSourceDialog = false
                    },
                ) { Text("완료") }
            },
            dismissButton = {
                TextButton(onClick = { showSourceDialog = false }) { Text("취소") }
            },
        )
    }

    if (showWordCardDialog) {
        AlertDialog(
            onDismissRequest = { showWordCardDialog = false },
            title = { Text("단어카드 단어 선택") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (s.wordCardBracketedText.isNotBlank()) {
                        Text(
                            s.wordCardBracketedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinyActionButton(label = "전체", enabled = !s.running, onClick = { vm.setAllWordCardCandidates(true) })
                        TinyActionButton(label = "해제", enabled = !s.running, onClick = { vm.setAllWordCardCandidates(false) })
                        TinyActionButton(label = "새로", enabled = !s.running, onClick = {
                            showWordCardDialog = false
                            vm.clearWordCardCandidates()
                            vm.prepareWordCardCandidates()
                        })
                    }
                    s.wordCardCandidates.withIndex().chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { indexedCandidate ->
                                val candidate = indexedCandidate.value
                                OutlinedButton(
                                    onClick = { vm.toggleWordCardCandidate(indexedCandidate.index) },
                                    enabled = !s.running,
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = if (candidate.selected) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    },
                                ) {
                                    Text(candidate.word, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    OutlinedTextField(
                        value = s.wordCardPrompt,
                        onValueChange = vm::setWordCardPrompt,
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        label = { Text("설명 생성 프롬프트") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !s.running && s.wordCardCandidates.any { it.selected },
                    onClick = {
                        showWordCardDialog = false
                        vm.buildWordCardFromSelected()
                    },
                ) { Text("보내기") }
            },
            dismissButton = {
                TextButton(onClick = { showWordCardDialog = false }) { Text("닫기") }
            },
        )
    }

    if (showRepairDialog) {
        AlertDialog(
            onDismissRequest = { showRepairDialog = false },
            title = { Text("그림 다시 만들 단원") },
            text = {
                if (s.repairChoices.isEmpty()) {
                    Text("story_comic 단원을 찾지 못했습니다.")
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        s.repairChoices.forEachIndexed { index, choice ->
                            OutlinedButton(
                                onClick = {
                                    vm.selectRepairChoice(index)
                                    showRepairDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "${index + 1}. ${choice.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRepairDialog = false }) { Text("닫기") }
            },
        )
    }
}

@Composable
private fun PhotoTokenButton(
    label: String,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.TopStart) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.size(36.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                if (count > 0) count.toString() else label,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        if (count > 0) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color.White, fontSize = 7.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TinyActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val colors = if (active) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        ButtonDefaults.outlinedButtonColors()
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(28.dp).widthIn(min = 42.dp),
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
        colors = colors,
    ) {
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}
