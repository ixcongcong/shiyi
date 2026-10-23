package cn.shiyi.reminder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF356B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EDE5), onPrimaryContainer = Color(0xFF1C493C),
    secondary = Color(0xFF8E6B36), secondaryContainer = Color(0xFFF4E4C6),
    background = Color(0xFFF7F6F0), surface = Color(0xFFFFFEF9),
    surfaceVariant = Color(0xFFEEEFE6), onSurface = Color(0xFF263C34), onSurfaceVariant = Color(0xFF63726A),
    outlineVariant = Color(0xFFDCE1D7),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA1D2BB), onPrimary = Color(0xFF103B2D),
    primaryContainer = Color(0xFF2C5043), onPrimaryContainer = Color(0xFFD0EDDE),
    secondary = Color(0xFFE4C28C), secondaryContainer = Color(0xFF54432B),
    background = Color(0xFF141D18), surface = Color(0xFF1A251F),
    surfaceVariant = Color(0xFF2B3730), onSurface = Color(0xFFE2EADF), onSurfaceVariant = Color(0xFFBBC9BE),
)

@Composable
fun ShiyiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
