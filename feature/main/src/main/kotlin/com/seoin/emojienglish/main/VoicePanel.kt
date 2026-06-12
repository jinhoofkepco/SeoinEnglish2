package com.seoin.emojienglish.main

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seoin.emojienglish.voice.VoicePanelMode
import com.seoin.emojienglish.voice.VoiceSessionMode
import com.seoin.emojienglish.voice.VoiceSessionState
import com.seoin.emojienglish.voice.VoiceShellState

/**
 * The persistent, collapsible voice panel (요구사항 ⑤⑥ + 공통기능 계층). It lives in
 * the AppShell above the NavHost, so the **same** ChatGPT WebView session survives
 * step navigation (한 세트 한 보이스). Collapsed it is a thin status strip with our
 * mic button; expanded it reveals the live WebView (so the teacher can use GPT's
 * own left menu for manual log/folder management) plus the mic + state controls.
 */
@Composable
fun VoicePanel(vm: MainViewModel) {
    val state by vm.voiceState.collectAsStateWithLifecycle()
    if (state.mode == VoiceSessionMode.NONE) return

    Surface(tonalElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth()) {
            MiniStrip(
                state = state,
                onToggleMic = { vm.setMicManual(!state.micOpen) },
                onCyclePanel = vm::cyclePanel,
            )
            // WebView is hosted whenever the panel is MINIMIZED or OPEN (it must be
            // attached for voice to work). The WebView is always laid out full-size
            // internally; MINIMIZED just clips it to a thin sliver ("열린 흉내").
            AnimatedVisibility(visible = state.panelMode != VoicePanelMode.CLOSED) {
                ExpandedBody(
                    state = state,
                    open = state.panelMode == VoicePanelMode.OPEN,
                    provideView = vm::provideVoiceView,
                )
            }
        }
    }
}

@Composable
private fun MiniStrip(
    state: VoiceSessionState,
    onToggleMic: () -> Unit,
    onCyclePanel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StateDot(state)
        Text(shellLabel(state), style = MaterialTheme.typography.labelMedium)
        if (state.speaking) {
            Text("🔊 설명 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.weight(1f))
        // Auto-gate vs manual-override indicator.
        Text(
            if (state.manualOverride) "수동" else if (state.micAutoGate) "AUTO" else "—",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        // Our mic on/off button (disables auto-gate).
        IconButton(onClick = onToggleMic) {
            Icon(
                if (state.micOpen) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = if (state.micOpen) "마이크 끄기" else "마이크 켜기",
                tint = if (state.micOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Cycle: 최소화 열림 → 열림 → 닫힘. Icon shows what the next tap does.
        IconButton(onClick = onCyclePanel) {
            val (icon, desc) = when (state.panelMode) {
                VoicePanelMode.MINIMIZED -> Icons.Filled.OpenInFull to "열기"
                VoicePanelMode.OPEN -> Icons.Filled.CloseFullscreen to "닫기"
                VoicePanelMode.CLOSED -> Icons.Filled.UnfoldMore to "최소화 열기"
            }
            Icon(icon, contentDescription = desc)
        }
    }
}

/**
 * The WebView is always laid out at [WebViewContentHeight] internally so ChatGPT's
 * voice DOM stays functional, but the visible window is clipped: [open] shows it
 * full, otherwise a thin sliver (MINIMIZED = "열린 흉내만"). This keeps audio/voice
 * running without a big window covering the lesson.
 */
@Composable
private fun ExpandedBody(state: VoiceSessionState, open: Boolean, provideView: () -> WebView?) {
    val visibleHeight = if (open) WebViewContentHeight else MinimizedSliver
    Column(Modifier.fillMaxWidth()) {
        if (open && state.conversationName.isNotBlank()) {
            Text(
                "대화: ${state.conversationName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(visibleHeight)
                .clipToBounds(),
        ) {
            val webView = provideView()
            if (webView != null) {
                AndroidView(
                    // requiredHeight forces the full content viewport regardless of
                    // the clipped visible height, so the voice UI never reflows away.
                    modifier = Modifier.fillMaxWidth().requiredHeight(WebViewContentHeight),
                    factory = { _ ->
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                )
            } else {
                Text(
                    "음성 엔진을 사용할 수 없습니다 (프리뷰/테스트 모드).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (open && state.shell == VoiceShellState.NOT_LOGGED_IN) {
                Text(
                    "chatgpt.com에 로그인하세요 (최초 1회 수동).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopCenter).padding(4.dp),
                )
            }
        }
    }
}

private val WebViewContentHeight = 460.dp
private val MinimizedSliver = 10.dp

@Composable
private fun StateDot(state: VoiceSessionState) {
    val color = when (state.shell) {
        VoiceShellState.PRIMED, VoiceShellState.COACHING, VoiceShellState.CHILD_TURN, VoiceShellState.READY ->
            Color(0xFF2E7D32) // green
        VoiceShellState.CONNECTING, VoiceShellState.RECONNECTING -> Color(0xFFF9A825) // amber
        else -> Color(0xFFC62828) // red
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

private fun shellLabel(state: VoiceSessionState): String {
    val mode = if (state.mode == VoiceSessionMode.FREE_TALK) "자유대화" else "코칭"
    val shell = when (state.shell) {
        VoiceShellState.NOT_LOGGED_IN -> "로그인 필요"
        VoiceShellState.CONNECTING -> "연결 중"
        VoiceShellState.READY -> "준비됨"
        VoiceShellState.PRIMED -> "대기"
        VoiceShellState.COACHING -> "진행 중"
        VoiceShellState.CHILD_TURN -> "학생 차례"
        VoiceShellState.RECONNECTING -> "재연결"
        VoiceShellState.FALLBACK_TTS -> "TTS"
    }
    val busy = if (state.busy) " · …" else ""
    return "🎙 $mode · $shell$busy"
}
