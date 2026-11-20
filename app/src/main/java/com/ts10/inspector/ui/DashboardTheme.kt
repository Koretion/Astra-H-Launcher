package com.ts10.inspector.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.settings.DashboardPreferences
import java.util.Calendar

enum class DashboardThemeMode {
    AUTOMATIC,
    LIGHT,
    DARK,
}

@Immutable
data class DashboardColors(
    val dark: Boolean,
    val background: Color,
    val modalBackground: Color,
    val primary: Color,
    val muted: Color,
    val actionBackground: Color,
    val actionForeground: Color,
    val divider: Color,
)

private val LightDashboardColors = DashboardColors(
    dark = false,
    background = Color(0xFFF4F2EF),
    modalBackground = Color(0xFFF4F3F0),
    primary = Color(0xFF202020),
    muted = Color(0xFF686868),
    actionBackground = Color(0xFF6C737A),
    actionForeground = Color.White,
    divider = Color(0xFF2B2B2B),
)

private val DarkDashboardColors = DashboardColors(
    dark = true,
    background = Color(0xFF050607),
    modalBackground = Color(0xFF121416),
    primary = Color(0xFFF5F5F5),
    muted = Color(0xFF73777B),
    actionBackground = Color(0xFF1D2227),
    actionForeground = Color.White,
    divider = Color(0xFF7B7F83),
)

val LocalDashboardColors = staticCompositionLocalOf { LightDashboardColors }

@Composable
fun AstraDashboardTheme(
    onEffectiveDarkChanged: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = DashboardPreferences.get(context).state.collectAsStateWithLifecycle().value
    val dark = when (settings.themeMode) {
        DashboardThemeMode.AUTOMATIC -> automaticDashboardDark(
            systemDark = isSystemInDarkTheme(),
            hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        )
        DashboardThemeMode.LIGHT -> false
        DashboardThemeMode.DARK -> true
    }
    val colors = if (dark) DarkDashboardColors else LightDashboardColors
    SideEffect { onEffectiveDarkChanged?.invoke(dark) }
    val materialColors = if (dark) {
        darkColorScheme(
            background = colors.background,
            surface = colors.modalBackground,
            onBackground = colors.primary,
            onSurface = colors.primary,
        )
    } else {
        lightColorScheme(
            background = colors.background,
            surface = colors.modalBackground,
            onBackground = colors.primary,
            onSurface = colors.primary,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalDashboardColors provides colors) {
        MaterialTheme(colorScheme = materialColors) { LocalizedContent(content) }
    }
}

/** TS10 can update its QF chrome before (or without) publishing UI_MODE_NIGHT to overlays. */
internal fun automaticDashboardDark(systemDark: Boolean, hourOfDay: Int): Boolean =
    systemDark || hourOfDay >= 18 || hourOfDay < 7
