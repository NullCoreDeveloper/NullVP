package nullvp.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import nullvp.domain.model.AppTheme

val NeonCyan = Color(0xFF00E5FF)
val DeepCyan = Color(0xFF00B0FF)
val CyberGreen = Color(0xFF00E676)
val CyberRed = Color(0xFFFF1744)
val CyberAmber = Color(0xFFFFAB00)

// Raw Dark Palette
private val RawDarkSurface = Color(0xFF131316)
private val RawDarkSurfaceVariant = Color(0xFF1E1E24)
private val RawDarkBackground = Color(0xFF0A0A0C)
private val RawOnDarkSurface = Color(0xFFEDEDED)
private val RawOnDarkSurfaceVariant = Color(0xFFA0A0AB)

// Raw Light Palette (Modern Clean Slate / Cyan)
private val RawLightSurface = Color(0xFFFFFFFF)
private val RawLightSurfaceVariant = Color(0xFFF1F5F9)
private val RawLightBackground = Color(0xFFF8FAFC)
private val RawOnLightSurface = Color(0xFF0F172A)
private val RawOnLightSurfaceVariant = Color(0xFF475569)
private val RawLightOutline = Color(0xFFCBD5E1)
private val RawLightPrimary = Color(0xFF0284C7)
private val RawLightPrimaryContainer = Color(0xFFE0F2FE)
private val RawLightSecondary = Color(0xFF0EA5E9)

// Adaptive Dynamic Getters for seamless light/dark theme switching
val DarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val DarkSurfaceVariant: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

val DarkBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val OnDarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface

val OnDarkSurfaceVariant: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val AppPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = NeonCyan,
    secondary = DeepCyan,
    onSecondary = Color.Black,
    background = RawDarkBackground,
    onBackground = RawOnDarkSurface,
    surface = RawDarkSurface,
    onSurface = RawOnDarkSurface,
    surfaceVariant = RawDarkSurfaceVariant,
    onSurfaceVariant = RawOnDarkSurfaceVariant,
    outline = Color(0xFF2E2E38),
    error = CyberRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = RawLightPrimary,
    onPrimary = Color.White,
    primaryContainer = RawLightPrimaryContainer,
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = RawLightSecondary,
    onSecondary = Color.White,
    background = RawLightBackground,
    onBackground = RawOnLightSurface,
    surface = RawLightSurface,
    onSurface = RawOnLightSurface,
    surfaceVariant = RawLightSurfaceVariant,
    onSurfaceVariant = RawOnLightSurfaceVariant,
    outline = RawLightOutline,
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun NullVpTheme(
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (appTheme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
