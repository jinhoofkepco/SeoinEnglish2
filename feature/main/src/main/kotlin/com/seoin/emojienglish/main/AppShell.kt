package com.seoin.emojienglish.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seoin.emojienglish.voice.VoicePanelMode

/**
 * The app shell — provides the **single bottom hub** (보이스 + 마스터 통합) over
 * every screen, plus the master PIN dialog.
 *
 * 핵심 수정 (가림 버그):
 *  - 하단 허브의 실제 높이를 측정해(`onSizeChanged`) 콘텐츠의 bottom padding으로
 *    그대로 돌려준다. 접힘(동그라미만)·펼침(허브바)·웹뷰 열림 어느 상태든 스텝의
 *    마지막 버튼이 항상 스크롤로 닿는다.
 *  - 보이스 MiniStrip(별도 줄)을 없애고 그 상태/마이크 정보를 허브 바·동그라미로
 *    흡수 — 두 줄 → 한 줄.
 */
@Composable
fun AppShell(
    vm: MainViewModel,
    content: @Composable (Modifier) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    val masterOn by vm.masterUnlocked.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val picture by vm.pictureState.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    var hubHeightPx by remember { mutableIntStateOf(0) }
    val hubHeightDp = with(density) { hubHeightPx.toDp() }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Content reserves exactly the measured hub height — never more, never less.
        content(Modifier.fillMaxSize().padding(bottom = hubHeightDp))

        // 그림창 오버레이 — 콘텐츠 위, 단 하단 허브 **아래**에 그려서 "그림" 버튼을
        // 다시 눌러 닫을 수 있게 한다(허브가 패널 위로 보임). 열렸을 때만 렌더.
        PicturePanel(
            state = picture,
            onClose = vm::closePicture,
            onWord = vm::requestPicture,
            provideView = vm::providePictureView,
            bottomInset = hubHeightDp,
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { hubHeightPx = it.height },
        ) {
            BottomHub(
                voice = voice,
                masterOn = masterOn,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded },
                onToggleMic = { vm.setMicManual(!voice.micOpen) },
                onPicture = vm::togglePicture,
                onMaster = { if (!vm.toggleMaster()) showPin = true },
                onToggleWebView = {
                    vm.setPanelMode(
                        if (voice.panelMode == VoicePanelMode.OPEN) VoicePanelMode.MINIMIZED
                        else VoicePanelMode.OPEN,
                    )
                },
                provideView = vm::provideVoiceView,
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