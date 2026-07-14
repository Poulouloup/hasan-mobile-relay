package com.hasan.v1.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Le mockup (docs/design/hasan-mockup-v2.html) n'a qu'un seul thème, sombre —
 * pas de variante claire. isSystemInDarkTheme() n'est pas utilisé pour basculer
 * de palette : Hasan reste visuellement identique quel que soit le thème système.
 */
private val HasanColorScheme = darkColorScheme(
    primary = HasanColors.Accent,
    onPrimary = HasanColors.TextPrimary,
    background = HasanColors.BgBase,
    onBackground = HasanColors.TextPrimary,
    surface = HasanColors.BgSurface,
    onSurface = HasanColors.TextPrimary,
    surfaceVariant = HasanColors.BgSurface2,
    onSurfaceVariant = HasanColors.TextSecondary,
    outline = HasanColors.Border,
    error = HasanColors.Accent
)

@Composable
fun HasanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HasanColorScheme,
        typography = HasanTypography,
        content = content
    )
}
