package com.seoin.emojienglish.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6FE0),
    secondary = Color(0xFF7A5AF8),
    tertiary = Color(0xFFE0913B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CB6FF),
    secondary = Color(0xFFC2B0FF),
    tertiary = Color(0xFFFFC98A),
)

/**
 * App theme. Dark mode is wired through here so it stays a token-only change
 * later (§14 "다크 모드 미정"): no screen needs to know about it.
 */
@Composable
fun EmojiEnglishTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
