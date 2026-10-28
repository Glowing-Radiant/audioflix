package app.audioflix.ui.theme

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

private val DarkColors = darkColorScheme(
  primary = Color(0xFFFFB4A8),
  primaryContainer = Color(0xFF8C1D13),
  secondary = Color(0xFFE7BDB6),
  tertiary = Color(0xFFDEC48C),
)

private val LightColors = lightColorScheme(
  primary = Color(0xFFA4281C),
  primaryContainer = Color(0xFFFFDAD4),
  secondary = Color(0xFF775651),
  tertiary = Color(0xFF6E5C2E),
)

@Composable
fun AudioFlixTheme(
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
  MaterialTheme(colorScheme = colorScheme, content = content)
}
