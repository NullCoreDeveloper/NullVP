package nullvp.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NeonCyan = Color(0xFF00E5FF)
val DeepCyan = Color(0xFF00B0FF)
val CyberGreen = Color(0xFF00E676)
val CyberRed = Color(0xFFFF1744)
val CyberAmber = Color(0xFFFFAB00)

val DarkSurface = Color(0xFF131316)
val DarkSurfaceVariant = Color(0xFF1E1E24)
val DarkBackground = Color(0xFF0A0A0C)
val OnDarkSurface = Color(0xFFEDEDED)
val OnDarkSurfaceVariant = Color(0xFFA0A0AB)

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = NeonCyan,
    secondary = DeepCyan,
    onSecondary = Color.Black,
    background = DarkBackground,
    onBackground = OnDarkSurface,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurfaceVariant,
    error = CyberRed,
    onError = Color.White
)

@Composable
fun NullVpTheme(
    darkTheme: Boolean = true, // Default to sleek dark mode
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

