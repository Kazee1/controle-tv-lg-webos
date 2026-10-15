package com.example.tvremote.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC2FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF174A7E),
    secondary = Color(0xFFAFC8E8),
    tertiary = Color(0xFFBFC6FF),
    background = Color(0xFF090B10),
    surface = Color(0xFF10131A),
    surfaceVariant = Color(0xFF1B202A),
    onSurface = Color(0xFFE4E8F1),
    onSurfaceVariant = Color(0xFFBBC3D0),
    error = Color(0xFFFFB4AB),
)

@Composable
fun TvRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
