package com.guanjian.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 浅色配色。
 *
 * 浅灰紫背景与白色卡片；明亮的珊瑚橙强调主要操作，天蓝色用于统计。
 * 沿用参考图的亮色调，绿色用于本地回顾和历史记录。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFFF725B), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4DF), onPrimaryContainer = Color(0xFF632014),
    secondary = Color(0xFF24AFE3), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF4FC), onSecondaryContainer = Color(0xFF073544),
    tertiary = Color(0xFF6AAE7B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1F2E4), onTertiaryContainer = Color(0xFF163C22),
    background = Color(0xFFF7F6FC), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F0F7), onSurface = Color(0xFF202128),
    onSurfaceVariant = Color(0xFF858793), outline = Color(0xFFB9BBC5),
    outlineVariant = Color(0xFFE9E8F0), surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAFF), surfaceContainerHigh = Color(0xFFF0EFF6)
)

/**
 * 深色配色。
 *
 * 炭灰色背景配合提亮的珊瑚色、蓝色和绿色，保留浅色模式的功能区分。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A7), onPrimary = Color(0xFF5B190E),
    primaryContainer = Color(0xFF7D2D20), onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFF83D3F3), onSecondary = Color(0xFF003544),
    secondaryContainer = Color(0xFF164D60), onSecondaryContainer = Color(0xFFC5EEFF),
    tertiary = Color(0xFF9FD5AA), onTertiary = Color(0xFF073917),
    tertiaryContainer = Color(0xFF315C3C), onTertiaryContainer = Color(0xFFBDEFC6),
    background = Color(0xFF17171C), surface = Color(0xFF222228),
    surfaceVariant = Color(0xFF2B2B33), onSurface = Color(0xFFE7E1E5),
    onSurfaceVariant = Color(0xFFC9C3CB), outline = Color(0xFF938E98),
    outlineVariant = Color(0xFF3D3B44), surfaceContainer = Color(0xFF222228),
    surfaceContainerLow = Color(0xFF1D1D23), surfaceContainerHigh = Color(0xFF2C2C34)
)

/** 统一圆角尺寸，让卡片、输入框和底部选择页保持同一视觉节奏。 */
private val GuanJianShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val GuanJianTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp)
)

/**
 * 应用主题。
 *
 * @param mode 主题模式名，取自 [com.guanjian.app.data.ThemeMode] 的枚举名。
 *   字符串类型是因为它来自 DataStore 的反序列化结果；无法识别的值
 *   （包括历史遗留值）一律按 SYSTEM 处理，不会抛异常。
 */
@Composable
fun GuanJianTheme(mode: String = "SYSTEM", content: @Composable () -> Unit) {
    val dark = when (mode) { "LIGHT" -> false; "DARK" -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = GuanJianShapes,
        typography = GuanJianTypography,
        content = content
    )
}
