package com.aistudio.oggresource.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private const val THEME_PREFS = "ogg_theme_prefs"
private const val KEY_DARK_THEME = "dark_theme"

fun readSavedDarkTheme(context: Context, default: Boolean): Boolean {
    return context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_DARK_THEME, default)
}

fun persistDarkTheme(context: Context, darkTheme: Boolean) {
    context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_DARK_THEME, darkTheme)
        .apply()
}

private val DarkColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = AmberOnPrimary,
    primaryContainer = Color(0xFF382A08),
    onPrimaryContainer = Color(0xFFFDE68A),
    secondary = CyanAccent,
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF004F5C),
    onSecondaryContainer = Color(0xFFBCE9FF),
    tertiary = EmeraldSuccess,
    onTertiary = Color(0xFF003822),
    tertiaryContainer = Color(0xFF005234),
    onTertiaryContainer = Color(0xFF88F8BD),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = RoseError,
    onError = Color(0xFF600010),
    errorContainer = Color(0xFF8C001C),
    onErrorContainer = Color(0xFFFFD9DD),
    outline = Color(0xFF374151),
    outlineVariant = Color(0xFF2A303C)
)

// Original / logo theme - 2K BEATS red, white badge, athletic charcoal
private val LogoColorScheme = lightColorScheme(
    primary = BeatsRedPrimary,
    onPrimary = Color.White,
    primaryContainer = BeatsRedContainer,
    onPrimaryContainer = BeatsOnRedContainer,
    secondary = BeatsAthleticDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EDF2),
    onSecondaryContainer = BeatsAthleticDark,
    tertiary = BeatsRedDark,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD5D7),
    onTertiaryContainer = BeatsOnRedContainer,
    background = BeatsOffWhite,
    onBackground = BeatsCharcoal,
    surface = Color.White,
    onSurface = BeatsCharcoal,
    surfaceVariant = BeatsSurfaceVariant,
    onSurfaceVariant = Color(0xFF4A5560),
    error = BeatsRedDark,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

@Composable
fun OggResourceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LogoColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
