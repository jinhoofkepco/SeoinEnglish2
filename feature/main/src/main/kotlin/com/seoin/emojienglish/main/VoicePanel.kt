package com.seoin.emojienglish.main

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.seoin.emojienglish.voice.VoicePanelMode
import com.seoin.emojienglish.voice.VoiceSessionMode
import com.seoin.emojienglish.voice.VoiceSessionState
import com.seoin.emojienglish.voice.VoiceShellState

/**
 * 통합 하단 허브 — 보이스 상태/마이크 + 자유대화 + 마스터 + 웹뷰를 한 곳에 모은다.
 *
 *  - **접힘**: 좌하단 동그라미 하나(보이스 상태색, 마스터 ON이면 금색 링). 배경 투명
 *    이라 콘텐츠가 양옆·위로 그대로 보인다. 탭하면 펼침.
 *  - **펼침**: 한 줄 허브 바 — 접기 동그라미 · 상태 · (수동/AUTO) · 🎙마이크 ·
 *    자유대화 · 마스터 · 웹뷰 토글.
 *  - **웹뷰**: 보이스 세션 활성 시 항상 attach 유지(MINIMIZED=1dp 슬리버, 거의
 *    안 보임 / OPEN=460dp). attach돼 있어야 ChatGPT voice JS가 동작(한 세트 한 보이스).
 */
@Composable
fun BottomHub(
    voice: VoiceSessionState,
    masterOn: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleMic: () -> Unit,
    onVoice: () -> Unit,
    onMaster: () -> Unit,
    onToggleWebView: () -> Unit,
    provideView: () -> WebView?,
) {
    val hasVoice = voice.mode != VoiceSessionMode.NONE

    Column(Modifier.fillMaxWidth()) {
        // ── WebView (보이스 활성 + attach) ───────────────────────────────────
        if (hasVoice && voice.panelMode != VoicePanelMode.CLOSED) {
            val visibleHeight = if (voice.panelMode == VoicePanelMode.OPEN) WebViewContentHeight else MinimizedSliver
            if (voice.panelMode == VoicePanelMode.OPEN && voice.conversationName.isNotBlank()) {
                Text(
                    "대화: ${voice.conversationName}",
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
                        modifier = Modifier.fillMaxWidth().requiredHeight(WebViewContentHeight),
                        factory = { _ ->
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            webView
                        },
                    )
                }
                if (voice.panelMode == VoicePanelMode.OPEN && voice.shell == VoiceShellState.NOT_LOGGED_IN) {
                    Text(
                        "chatgpt.com에 로그인하세요 (최초 1회 수동).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.TopCenter).padding(4.dp),
                    )
                }
            }
        }

        // ── 허브 바 ─────────────────────────────────────────────────────────
        if (!expanded) {
            CollapsedDot(voice = voice, masterOn = masterOn, hasVoice = hasVoice, onExpand = onToggleExpand)
        } else {
            ExpandedHubBar(
                voice = voice,
                masterOn = masterOn,
                hasVoice = hasVoice,
                onCollapse = onToggleExpand,
                onToggleMic = onToggleMic,
                onVoice = onVoice,
                onMaster = onMaster,
                onToggleWebView = onToggleWebView,
            )
        }
    }
}

/** 접힘: 좌하단 동그라미 하나. 배경 투명 → 콘텐츠가 그대로 보인다. */
@Composable
private fun CollapsedDot(
    voice: VoiceSessionState,
    masterOn: Boolean,
    hasVoice: Boolean,
    onExpand: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(voiceDotColor(voice))
                .then(
                    if (masterOn) Modifier.border(2.5.dp, Color(0xFFFFC107), CircleShape) else Modifier,
                )
                .clickable(onClick = onExpand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "메뉴 펴기",
                tint = Color.White,
            )
        }
        if (hasVoice && voice.speaking) {
            Text("🔊", style = MaterialTheme.typography.labelMedium)
        }
        if (hasVoice && voice.micOpen) {
            Icon(Icons.Filled.Mic, contentDescription = "마이크 켜짐", tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
        }
    }
}

/** 펼침: 한 줄 허브 바. */
@Composable
private fun ExpandedHubBar(
    voice: VoiceSessionState,
    masterOn: Boolean,
    hasVoice: Boolean,
    onCollapse: () -> Unit,
    onToggleMic: () -> Unit,
    onVoice: () -> Unit,
    onMaster: () -> Unit,
    onToggleWebView: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        color = if (masterOn) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 접기 동그라미
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(voiceDotColor(voice))
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "접기", tint = Color.White)
            }

            if (hasVoice) {
                Text(shellLabel(voice), style = MaterialTheme.typography.labelSmall)
                if (voice.speaking) {
                    Text("🔊", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            } else if (masterOn) {
                Text("마스터 ON", style = MaterialTheme.typography.labelSmall)
            }

            Box(Modifier.weight(1f))

            if (hasVoice) {
                // 수동/AUTO 배지 + 마이크 토글
                Text(
                    if (voice.manualOverride) "수동" else if (voice.micAutoGate) "AUTO" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                IconButton(onClick = onToggleMic) {
                    Icon(
                        if (voice.micOpen) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = if (voice.micOpen) "마이크 끄기" else "마이크 켜기",
                        tint = if (voice.micOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TextButton(onClick = onVoice) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Text(if (hasVoice) " 대화" else " Voice")
            }
            TextButton(onClick = onMaster) {
                Icon(
                    if (masterOn) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = null,
                )
                Text(if (masterOn) " ON" else " 마스터")
            }
            if (hasVoice) {
                IconButton(onClick = onToggleWebView) {
                    Icon(
                        if (voice.panelMode == VoicePanelMode.OPEN) Icons.Filled.CloseFullscreen
                        else Icons.Filled.OpenInFull,
                        contentDescription = if (voice.panelMode == VoicePanelMode.OPEN) "웹뷰 접기" else "웹뷰 열기",
                    )
                }
            }
        }
    }
}

private val WebViewContentHeight = 460.dp
private val MinimizedSliver = 1.dp

private fun voiceDotColor(voice: VoiceSessionState): Color = when {
    voice.mode == VoiceSessionMode.NONE -> Color(0xFF9AA0A6)
    voice.shell in setOf(
        VoiceShellState.PRIMED, VoiceShellState.COACHING,
        VoiceShellState.CHILD_TURN, VoiceShellState.READY,
    ) -> Color(0xFF2E7D32)
    voice.shell in setOf(VoiceShellState.CONNECTING, VoiceShellState.RECONNECTING) -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
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
    return "$mode · $shell$busy"
}
