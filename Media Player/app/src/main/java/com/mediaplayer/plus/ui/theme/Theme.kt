package com.mediaplayer.plus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PowerampDark = Color(0xFF121212)
val PowerampAccent = Color(0xFFBB86FC)
val PowerampSurface = Color(0xFF1E1E1E)

private val DarkColorScheme = darkColorScheme(
    primary = PowerampAccent,
    background = PowerampDark,
    surface = PowerampSurface,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun PowerampTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
