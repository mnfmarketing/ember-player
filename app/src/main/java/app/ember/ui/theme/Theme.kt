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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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

// ---------------------------------------------------------------------------------------------
// Themes: each has a light and a dark version
// ---------------------------------------------------------------------------------------------

private fun hsl(h: Float, s: Float, l: Float): Color = Color(ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** A dark palette tinted with [hue] (at saturation [sat]): near-black backgrounds, soft light text. */
private fun tintedDark(hue: Float, sat: Float, accent: Color, success: Color = Color(0xFF7FCB9A)) = EmberPalette(
    isDark = true,
    background = hsl(hue, sat, 0.065f),
    surface = hsl(hue, sat, 0.095f),
    surfaceHigh = hsl(hue, sat, 0.13f),
    surfaceHighest = hsl(hue, sat * 0.9f, 0.165f),
    outline = hsl(hue, sat * 0.7f, 0.23f),
    text = hsl(hue, 0.18f, 0.93f),
    textMuted = hsl(hue, 0.08f, 0.64f),
    textFaint = hsl(hue, 0.06f, 0.43f),
    defaultAccent = accent,
    success = success,
    glassText = Color(0xFFF7F4F0),
    glassMuted = hsl(hue, 0.20f, 0.84f).copy(alpha = 0.85f),
    glassFill = Color.White.copy(alpha = 0.10f),
    glassBorder = Color.White.copy(alpha = 0.18f),
    wash = Color.White,
)

/** A light palette tinted with [hue]: pale paper backgrounds, dark text. */
private fun tintedLight(hue: Float, sat: Float, accent: Color, success: Color = Color(0xFF2E7D4F)) = EmberPalette(
    isDark = false,
    background = hsl(hue, sat, 0.975f),
    surface = hsl(hue, sat, 0.935f),
    surfaceHigh = hsl(hue, sat * 0.95f, 0.895f),
    surfaceHighest = hsl(hue, sat * 0.9f, 0.85f),
    outline = hsl(hue, sat * 0.7f, 0.76f),
    text = hsl(hue, 0.20f, 0.10f),
    textMuted = hsl(hue, 0.08f, 0.35f),
    textFaint = hsl(hue, 0.06f, 0.54f),
    defaultAccent = accent,
    success = success,
    glassText = hsl(hue, 0.20f, 0.10f),
    glassMuted = hsl(hue, 0.08f, 0.29f),
    glassFill = Color.White.copy(alpha = 0.62f),
    glassBorder = Color.Black.copy(alpha = 0.08f),
    wash = Color.Black,
)

/** A theme: its name, and the colors it uses in dark mode and in light mode. */
class EmberThemeDef(val id: String, val name: String, val dark: EmberPalette, val light: EmberPalette)

val EmberThemes: List<EmberThemeDef> = listOf(
    EmberThemeDef("ember", "Ember", DarkPalette, LightPalette),
    EmberThemeDef("paper", "Paper", tintedDark(32f, 0.22f, Color(0xFFE0B072)), tintedLight(40f, 0.48f, Color(0xFF8C5A26))),
    EmberThemeDef("midnight", "Midnight", tintedDark(224f, 0.38f, Color(0xFF86A9FF)), tintedLight(220f, 0.40f, Color(0xFF2D59B5))),
    EmberThemeDef("slate", "Slate", tintedDark(210f, 0.16f, Color(0xFF8DB4DA)), tintedLight(210f, 0.20f, Color(0xFF3D6890))),
    EmberThemeDef("graphite", "Graphite", tintedDark(0f, 0f, Color(0xFFF0A04B)), tintedLight(0f, 0f, Color(0xFFB0560F))),
    EmberThemeDef("forest", "Forest", tintedDark(150f, 0.26f, Color(0xFF7FCB9A), Color(0xFFA6D86E)), tintedLight(140f, 0.24f, Color(0xFF2E7D4F), Color(0xFF557A1F))),
    EmberThemeDef("ocean", "Ocean", tintedDark(188f, 0.36f, Color(0xFF5CCBC9)), tintedLight(186f, 0.34f, Color(0xFF137C80))),
)

fun themeById(id: String): EmberThemeDef = EmberThemes.firstOrNull { it.id == id } ?: EmberThemes.first()

/** Dark mode on pure black (saves battery on OLED screens); cards stay just visible. */
fun trueBlack(p: EmberPalette): EmberPalette {
    val tint = FloatArray(3)
    ColorUtils.colorToHSL(p.surface.toArgb(), tint)
    return EmberPalette(
        isDark = true,
        background = Color.Black,
        surface = hsl(tint[0], tint[1], 0.06f),
        surfaceHigh = hsl(tint[0], tint[1], 0.095f),
        surfaceHighest = hsl(tint[0], tint[1], 0.13f),
        outline = hsl(tint[0], tint[1] * 0.7f, 0.20f),
        text = p.text,
        textMuted = p.textMuted,
        textFaint = p.textFaint,
        defaultAccent = p.defaultAccent,
        success = p.success,
        glassText = p.glassText,
        glassMuted = p.glassMuted,
        glassFill = p.glassFill,
        glassBorder = p.glassBorder,
        wash = p.wash,
    )
}

/** Fixed accent choices (bright; light mode darkens them so text on them stays readable). */
val AccentSwatches: List<Pair<String, Color>> = listOf(
    "Orange" to Color(0xFFF2A65A),
    "Red" to Color(0xFFE5574C),
    "Amber" to Color(0xFFF5B841),
    "Gold" to Color(0xFFD4B04A),
    "Green" to Color(0xFF6CC48A),
    "Teal" to Color(0xFF3FBDB3),
    "Blue" to Color(0xFF5B9BF0),
    "Steel" to Color(0xFF93A6BB),
)

/** A custom accent from the hue slider (0-360). */
fun accentFromHue(hue: Float): Color = hsl(hue, 0.72f, 0.62f)

fun hueOf(color: Color): Float {
    val out = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), out)
    return out[0]
}

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

/**
 * Ember's look. [mode] is "system" (follow the phone's dark mode, including a night schedule),
 * "light" or "dark". [accentMode] is "book" (the playing book's cover color), "theme" (the
 * theme's own color) or "fixed" ([accentColor]).
 */
@Composable
fun EmberTheme(
    themeId: String,
    mode: String,
    trueBlackOn: Boolean,
    accentMode: String,
    accentColor: Int,
    bookColor: Int?,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val def = themeById(themeId)
    val palette = remember(def, dark, trueBlackOn) { if (dark) (if (trueBlackOn) trueBlack(def.dark) else def.dark) else def.light }
    if (EmberColors.palette !== palette) EmberColors.palette = palette
    val accent = when (accentMode) {
        "fixed" -> readableAccent(Color(accentColor))
        "theme" -> palette.defaultAccent
        else -> bookColor?.let { readableAccent(Color(it)) } ?: palette.defaultAccent
    }
    // Status and navigation bar icons follow Ember's light or dark, not the phone's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
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
