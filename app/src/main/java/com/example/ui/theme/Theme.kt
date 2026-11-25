package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val VaultDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimaryLight,
    onPrimary = OnEmeraldDark,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = AmberSecondary,
    onSecondary = Color(0xFF2B1B00),
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = Color(0xFFFDE68A),
    tertiary = CyanTertiary,
    onTertiary = Color(0xFF00242C),
    tertiaryContainer = CyanContainerDark,
    onTertiaryContainer = Color(0xFFA5F3FC),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = ObsidianSurfaceElevated,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1F2A38),
    error = VaultErrorDark,
    onError = Color(0xFF4C0519),
    errorContainer = VaultErrorContainerDark,
    onErrorContainer = Color(0xFFFFCCD5)
)

private val VaultLightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryDark,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = OnEmeraldContainerLight,
    secondary = AmberSecondaryDark,
    onSecondary = Color.White,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = Color(0xFF451A03),
    tertiary = CyanTertiaryDark,
    onTertiary = Color.White,
    tertiaryContainer = CyanContainerLight,
    onTertiaryContainer = Color(0xFF0E3A4A),
    background = AlabasterBg,
    onBackground = TextPrimaryLight,
    surface = AlabasterSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = AlabasterOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = VaultErrorLight,
    onError = Color.White,
    errorContainer = VaultErrorContainerLight,
    onErrorContainer = Color(0xFF881337)
)

val VaultShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class VaultThemePreference {
    DARK, LIGHT, SYSTEM
}

@Composable
fun BrankasKuTheme(
    themePreference: VaultThemePreference = VaultThemePreference.DARK,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themePreference) {
        VaultThemePreference.DARK -> true
        VaultThemePreference.LIGHT -> false
        VaultThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) VaultDarkColorScheme else VaultLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !useDarkTheme
                    isAppearanceLightNavigationBars = !useDarkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BrankasTypography,
        shapes = VaultShapes,
        content = content
    )
}
