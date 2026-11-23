package com.ghostporttechnologies.chamber.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Chamber's own palette (public/index.html :root), so the native app looks like Chamber. */
object ChamberColors {
    val Bg = Color(0xFF0A0C0B)
    val Surface = Color(0xFF101412)
    val Surface2 = Color(0xFF161B18)
    val Line = Color(0xFF232A26)
    val Text = Color(0xFFD8DED9)
    val TextDim = Color(0xFF8D978F)
    val TextFaint = Color(0xFF5F6964)
    val Accent = Color(0xFF00FF88)
    val Danger = Color(0xFFFF6B6B)
}

private val Dark = darkColorScheme(
    primary = ChamberColors.Accent,
    onPrimary = ChamberColors.Bg,
    background = ChamberColors.Bg,
    onBackground = ChamberColors.Text,
    surface = ChamberColors.Surface,
    onSurface = ChamberColors.Text,
    surfaceVariant = ChamberColors.Surface2,
    onSurfaceVariant = ChamberColors.TextDim,
    outline = ChamberColors.Line,
    error = ChamberColors.Danger,
)

private val Light = lightColorScheme(
    primary = Color(0xFF00804A),
    background = Color(0xFFF4F7F5),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFC62828),
)

@Composable
fun ChamberTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
