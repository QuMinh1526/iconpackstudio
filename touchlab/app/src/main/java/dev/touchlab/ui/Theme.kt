package dev.touchlab.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00121C),
    secondary = Color(0xFF81C784),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F2630),
    error = Color(0xFFE57373),
)

/** Theme dùng chung cho Activity và overlay (overlay không có Activity nên tự bọc theme). */
@Composable
fun TouchLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
