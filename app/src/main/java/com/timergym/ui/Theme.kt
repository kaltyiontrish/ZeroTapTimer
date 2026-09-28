package com.timergym.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val WorkColor = Color(0xFFFF5A5F)
val RestColor = Color(0xFF2EE6A8)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2EE6A8),
    onPrimary = Color(0xFF00281A),
    secondary = Color(0xFFFFC542),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF12161C),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2028),
    onSurfaceVariant = Color(0xFF9BA7B4),
    outline = Color(0xFF2C343E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00875A),
    onPrimary = Color.White,
    secondary = Color(0xFF8A6300),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF10151B),
    surface = Color.White,
    onSurface = Color(0xFF10151B),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF4A5560),
    outline = Color(0xFFCBD4DD),
)

/** Tabular-ish digits: the countdown must not jitter horizontally as numbers change. */
val BigDigits = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 76.sp,
    letterSpacing = (-2).sp,
)

@Composable
fun TimerGymTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** mm:ss, rounding up so the display shows 0:01 for the last second rather than 0:00. */
fun formatMs(ms: Long): String {
    val total = ((ms.coerceAtLeast(0) + 999) / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
