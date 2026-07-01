package com.seoin.emojienglish.main

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.seoin.emojienglish.voice.PictureState
import com.seoin.emojienglish.voice.PictureWord

/**
 * 그림창 — 화면 가운데를 거의 다 덮는 오버레이. 2번째 ChatGPT WebView를 host 하고,
 * 현재 포커스 문장/컷의 그림 단어는 패널이 열릴 때 곧바로 ChatGPT에 주입된다.
 *
 * 닫기: **바깥(스크림) 탭** 또는 하단 허브의 "그림" 버튼 재탭(AppShell이 허브를 이 패널
 * 위에 그린다). 스크림은 카드 **뒤 형제 레이어**라 카드 안 웹뷰 터치와 충돌하지 않는다.
 */
@Composable
fun PicturePanel(
    state: PictureState,
    onClose: () -> Unit,
    onWord: (PictureWord) -> Unit,
    provideView: () -> WebView?,
    bottomInset: Dp = 0.dp,
) {
    if (!state.visible) return

    Box(Modifier.fillMaxSize()) {
        // 스크림(카드 뒤) — 바깥 영역을 탭하면 닫힌다. ripple 없는 no-op 인디케이션.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClose() },
        )

        // 카드(가운데 거의 전체) — 웹뷰는 자체 터치 처리, 카드 안은 스크림으로 안 샌다.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                // 하단 허브 바에 가리지 않게 그만큼 띄운다.
                .padding(bottom = bottomInset),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 헤더 — 제목만(닫기는 바깥 탭 / 그림 버튼).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🖼 그림 찾기",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "바깥을 누르거나 ‘그림’을 다시 누르면 닫혀요",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                // 웹뷰: 찾을 대상은 패널 진입 전에 확정되어 곧바로 주입된다.
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val webView = provideView()
                    if (webView != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { _ ->
                                (webView.parent as? ViewGroup)?.removeView(webView)
                                webView
                            },
                        )
                    }
                }
            }
        }
    }
}
