package cn.shiyi.reminder.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Light = lightColorScheme(
    primary = Color(0xFF98536E), onPrimary = Color(0xFFFFFBFC),
    primaryContainer = Color(0xFFF6E1E8), onPrimaryContainer = Color(0xFF643248),
    secondary = Color(0xFF72618F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEE7F6), onSecondaryContainer = Color(0xFF504064),
    tertiary = Color(0xFF8B7055), tertiaryContainer = Color(0xFFF3E8D8),
    background = Color(0xFFFCF8F5), surface = Color(0xFFFFFDFC),
    surfaceVariant = Color(0xFFF5EEEF), onSurface = Color(0xFF3F3339), onSurfaceVariant = Color(0xFF796870),
    outline = Color(0xFFBCA8B1), outlineVariant = Color(0xFFEADDE2),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFE4ACBF), onPrimary = Color(0xFF472335),
    primaryContainer = Color(0xFF533444), onPrimaryContainer = Color(0xFFF9D9E5),
    secondary = Color(0xFFCEC0E6), onSecondary = Color(0xFF352B43),
    secondaryContainer = Color(0xFF40364F), onSecondaryContainer = Color(0xFFEDE2FC),
    tertiary = Color(0xFFE4C8A7), tertiaryContainer = Color(0xFF514131),
    background = Color(0xFF211B20), surface = Color(0xFF2B242A),
    surfaceVariant = Color(0xFF382E36), onSurface = Color(0xFFF1E6EC), onSurfaceVariant = Color(0xFFCDBBC5),
    outline = Color(0xFF9E8794), outlineVariant = Color(0xFF51424D),
)

private val ShiyiTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun ShiyiTheme(mode: String = "light", content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = ShiyiTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp)), content = content)
}
