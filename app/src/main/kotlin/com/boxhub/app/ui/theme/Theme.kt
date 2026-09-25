package com.boxhub.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = SurfaceLight,
    background = LavenderGray,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outlineVariant = OutlineLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerLow = Color(0xFFF8F8FB),
    secondaryContainer = UnreadPillLight,
    tertiary = FabPeriwinkle,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = BackgroundDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outlineVariant = OutlineDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerLow = Color(0xFF171920),
    secondaryContainer = UnreadPillDark,
    tertiary = PrimaryBlueDark,
)

/**
 * BoxHub 主题：默认跟随系统深浅色；Android 12+ 可用动态取色（M1 恒开，设置项 M6 提供）。
 * 配色基调复刻 Re:Source 的低饱和柔和风。
 */
@Composable
fun BoxHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = BoxHubTypography,
        content = content,
    )
}
