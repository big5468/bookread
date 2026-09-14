package com.guanjian.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 浅色配色。
 *
 * 取色思路：整体偏纸张与木质色调，低饱和、低对比压力，适合长时间阅读场景。
 * 主色是偏橄榄的绿（0xFF596743），背景接近米白（0xFFFAF8F2）而非纯白，
 * 减少在图书馆灯光下的刺眼感。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4CAF50), onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE7CD), onPrimaryContainer = Color(0xFF243018),
    secondary = Color(0xFF6D8042), secondaryContainer = Color(0xFFBAE7C2),
    background = Color(0xFFF6F5F0), surface = Color(0xFFFFFDF7),
    surfaceVariant = Color(0xFFE5E9DF), onSurface = Color(0xFF1B1C19),
    onSurfaceVariant = Color(0xFF454A42), outline = Color(0xFF737A6E),
    outlineVariant = Color(0xFFC4C9BD), surfaceContainer = Color(0xFFF0F1EA)
)

/**
 * 深色配色。
 *
 * 背景用偏深的暖灰（0xFF191C17）而不是纯黑，避免深色模式下的「光晕」与视觉疲劳。
 * 主色相应提亮为浅绿（0xFFBECEA6）以保证在深底上的可读对比度。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBECEA6), onPrimary = Color(0xFF29371B),
    primaryContainer = Color(0xFF3E4B31), onPrimaryContainer = Color(0xFFDEE7CD),
    secondary = Color(0xFFE7BE9B), secondaryContainer = Color(0xFF5B402D),
    background = Color(0xFF191C17), surface = Color(0xFF191C17),
    surfaceVariant = Color(0xFF40483C), onSurface = Color(0xFFE5E5DB),
    onSurfaceVariant = Color(0xFFC3C8BA), outline = Color(0xFF8D9587),
    outlineVariant = Color(0xFF40483C), surfaceContainer = Color(0xFF22261F)
)

/** 统一圆角尺寸，让卡片、输入框和底部选择页保持同一视觉节奏。 */
private val GuanJianShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
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
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = GuanJianShapes, content = content)
}
