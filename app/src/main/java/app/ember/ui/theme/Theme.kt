package app.ember.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils

/** One full set of Ember colors. */
class EmberPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val defaultAccent: Color,
    val success: Color,
    val glassText: Color,
    val glassMuted: Color,
    val glassFill: Color,
    val glassBorder: Color,
    /** Overlay ink: white on dark, black on light (used at low alpha for tracks and highlights). */
    val wash: Color,
)

val DarkPalette = EmberPalette(
    isDark = true,
    background = Color(0xFF0E0D10),
    surface = Color(0xFF17151A),
    surfaceHigh = Color(0xFF211E25),
    surfaceHighest = Color(0xFF2B2730),
    outline = Color(0xFF3A353F),
    text = Color(0xFFF3EEEA),
    textMuted = Color(0xFFA39C97),
    textFaint = Color(0xFF6F6863),
    defaultAccent = Color(0xFFF2A65A),
    success = Color(0xFF7FCB9A),
    glassText = Color(0xFFFFF4EA),
    glassMuted = Color(0xCCF3DCCB),
    glassFill = Color.White.copy(alpha = 0.10f),
    glassBorder = Color.White.copy(alpha = 0.18f),
    wash = Color.White,
)

/** Warm paper tones that stay readable in bright sunlight. */
val LightPalette = EmberPalette(
    isDark = false,
    background = Color(0xFFFBF8F4),
    surface = Color(0xFFF2EDE6),
    surfaceHigh = Color(0xFFE9E2D9),
    surfaceHighest = Color(0xFFDFD6CB),
    outline = Color(0xFFCBBFB2),
    text = Color(0xFF1D1916),
    textMuted = Color(0xFF5E5650),
    textFaint = Color(0xFF8F8680),
    defaultAccent = Color(0xFFB35C14),
    success = Color(0xFF2E7D4F),
    glassText = Color(0xFF1D1916),
    glassMuted = Color(0xFF4F4741),
    glassFill = Color.White.copy(alpha = 0.62f),
    glassBorder = Color.Black.copy(alpha = 0.08f),
    wash = Color.Black,
)

/**
 * Current colors. Backed by Compose state, so everything redraws when the phone
 * switches between light and dark.
 */
object EmberColors {
    var palette by mutableStateOf(DarkPalette)
    val isDark: Boolean get() = palette.isDark
    val Background: Color get() = palette.background
    val Surface: Color get() = palette.surface
    val SurfaceHigh: Color get() = palette.surfaceHigh
    val SurfaceHighest: Color get() = palette.surfaceHighest
    val Outline: Color get() = palette.outline
    val Text: Color get() = palette.text
    val TextMuted: Color get() = palette.textMuted
    val TextFaint: Color get() = palette.textFaint
    val DefaultAccent: Color get() = palette.defaultAccent
    val Success: Color get() = palette.success
    val GlassText: Color get() = palette.glassText
    val GlassMuted: Color get() = palette.glassMuted
    val GlassFill: Color get() = palette.glassFill
    val GlassBorder: Color get() = palette.glassBorder
    val Wash: Color get() = palette.wash
}

/** Cover colors are tuned bright for dark mode; in light mode darken them so text stays readable. */
fun readableAccent(color: Color): Color {
    if (EmberColors.isDark) return color
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(0.35f, 0.8f)
    hsl[2] = hsl[2].coerceIn(0.30f, 0.44f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** A pale version of a cover color for light-mode backdrops. */
fun paleTint(color: Color, lightness: Float = 0.90f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * 0.7f).coerceAtMost(0.55f)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Dark text or light text, whichever reads better on [color]. */
fun onColorFor(color: Color): Color =
    if (ColorUtils.calculateLuminance(color.toArgb()) > 0.45) Color(0xFF1A1210) else Color(0xFFFFF7F0)

/** A deep, dark version of a cover color for immersive backgrounds. */
fun deepTint(color: Color, lightness: Float = 0.16f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * 0.85f).coerceAtMost(0.7f)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

val SerifTitle = FontFamily.Serif

/**
 * Type sizes one step above Material's defaults, for easier reading at arm's length.
 * Main reading text is 15-17sp and nothing drops below 12sp (Android's accessibility guidance).
 */
private val EmberTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

@Composable
fun EmberTheme(accent: Color, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    if (EmberColors.palette !== palette) EmberColors.palette = palette
    val animated by animateColorAsState(accent, animationSpec = tween(700), label = "accent")
    val scheme = if (dark) {
        darkColorScheme(
            primary = animated,
            onPrimary = onColorFor(animated),
            primaryContainer = deepTint(animated, 0.24f),
            onPrimaryContainer = palette.text,
            secondary = animated,
            onSecondary = onColorFor(animated),
            secondaryContainer = palette.surfaceHighest,
            onSecondaryContainer = palette.text,
            tertiary = animated,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.background,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.textMuted,
            surfaceContainerLowest = palette.background,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surfaceHigh,
            surfaceContainerHighest = palette.surfaceHighest,
            outline = palette.outline,
            outlineVariant = palette.surfaceHighest,
        )
    } else {
        lightColorScheme(
            primary = animated,
            onPrimary = onColorFor(animated),
            primaryContainer = paleTint(animated, 0.88f),
            onPrimaryContainer = palette.text,
            secondary = animated,
            onSecondary = onColorFor(animated),
            secondaryContainer = palette.surfaceHighest,
            onSecondaryContainer = palette.text,
            tertiary = animated,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.background,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.textMuted,
            surfaceContainerLowest = palette.background,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surfaceHigh,
            surfaceContainerHighest = palette.surfaceHighest,
            outline = palette.outline,
            outlineVariant = palette.surfaceHighest,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = EmberTypography, content = content)
}
