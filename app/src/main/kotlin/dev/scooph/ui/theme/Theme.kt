package dev.scooph.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 全局唯一的强调色（spec：整个应用只有一个强调色）。
private val Accent = Color(0xFF2F6BFF)

private val LightColors = lightColorScheme(primary = Accent)
private val DarkColors = darkColorScheme(primary = Accent)

@Composable
fun ScoophTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
