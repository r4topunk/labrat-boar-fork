package team.sopa.boar.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import team.sopa.boar.R
import kotlin.math.roundToInt

/**
 * Brand type (port of src/ui-android/theme/fonts.ts and components/AppText.tsx): Baloo 2 for titles and weight 800+,
 * Lexend at the nearest weight for everything read, the platform monospace only for code. Android doesn't synthesize
 * weights for custom fonts, so each weight is its own file.
 */
object BrandType {
    private val baloo700 = FontFamily(Font(R.font.baloo2_700bold, FontWeight.Bold))
    private val baloo800 = FontFamily(Font(R.font.baloo2_800extrabold, FontWeight.ExtraBold))
    private val lexend400 = FontFamily(Font(R.font.lexend_400regular, FontWeight.Normal))
    private val lexend500 = FontFamily(Font(R.font.lexend_500medium, FontWeight.Medium))
    private val lexend600 = FontFamily(Font(R.font.lexend_600semibold, FontWeight.SemiBold))
    private val lexend700 = FontFamily(Font(R.font.lexend_700bold, FontWeight.Bold))

    /** Body size for text that sets none. */
    const val BODY = 16

    /** familyFor: the bundled face for a style's family and weight, and whether digits are fixed-width. */
    fun familyFor(family: String?, weight: Int): Pair<FontFamily, Boolean> {
        if (family == "code") return FontFamily.Monospace to false
        val mono = family == "monospace"
        if (family == "display" || weight >= 800) return (if (weight >= 800) baloo800 else baloo700) to false
        val f = when {
            weight >= 700 -> lexend700
            weight >= 600 -> lexend600
            weight >= 500 -> lexend500
            else -> lexend400
        }
        return f to mono
    }

    /** scaledSize: the new UI's scale (body 16, nothing under 12); titles keep their size. */
    fun scaledSize(size: Float): Float = when {
        size <= 11 -> 12f
        size == 12f -> 13f
        size == 13f -> 14f
        size <= 15 -> 16f
        else -> size
    }

    /** fontScaleFactor: the Settings text size. */
    fun factor(scale: String?) = when (scale) { "compact" -> 0.9f; "large" -> 1.2f; else -> 1f }

    /** brandStyle for a generated TextSpec at a text-size setting. */
    fun style(spec: TextSpec, scale: String?): TextStyle {
        val (family, tabular) = familyFor(spec.family, spec.weight)
        val size = (scaledSize(spec.size) * factor(scale)).roundToInt()
        val line = spec.lineHeight?.let { if (spec.size > 0) (it * size / spec.size).roundToInt() else it.roundToInt() }
        return TextStyle(
            fontFamily = family,
            fontSize = size.sp,
            lineHeight = line?.sp ?: TextStyle.Default.lineHeight,
            letterSpacing = spec.letterSpacing?.let { (it / size).em } ?: TextStyle.Default.letterSpacing,
            fontFeatureSettings = if (tabular) "tnum" else null,
        )
    }
}

/** The text styles of one text-size setting, by the TS names ("ui.body", "mono.sm"). */
class BoarType(val scale: String) {
    private val specs = when (scale) { "compact" -> TypeTokens.compact; "large" -> TypeTokens.large; else -> TypeTokens.standard }
    private val cache = HashMap<String, TextStyle>()
    operator fun get(name: String): TextStyle = cache.getOrPut(name) { BrandType.style(specs.getValue(name), scale) }
    val body get() = this["ui.body"]
    val bodyLg get() = this["ui.bodyLg"]
    val caption get() = this["ui.caption"]
    val subtext get() = this["ui.subtext"]
    val micro get() = this["ui.micro"]
    val titleSm get() = this["ui.titleSm"]
    val title get() = this["ui.title"]
    val titleLg get() = this["ui.titleLg"]
    val headline get() = this["ui.headline"]
}

private val LocalColors = staticCompositionLocalOf { ThemeTokens.byId(null) }
private val LocalType = staticCompositionLocalOf { BoarType("standard") }

/**
 * Navigation chrome (the chat header) at most at the Standard text size whatever Settings → Text size says, and Android's own
 * font size capped at 1.3×: its buttons are a fixed size, and larger text broke it (the status pill, the model name,
 * the Labs badges). Reading text keeps the user's size.
 */
@Composable
fun FixedSizeChrome(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(
        // Capped at Standard: Compact stays Compact, Large reads as Standard.
        LocalType provides if (LocalType.current.scale == "large") BoarType("standard") else LocalType.current,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, minOf(d.fontScale, 1.3f)),
        content = content,
    )
}

object Boar {
    val colors: BoarColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: BoarType @Composable @ReadOnlyComposable get() = LocalType.current
}

/**
 * The app theme: Material You colors (the wallpaper's on Android 12+, the brand's before) in System, Light or Dark, the
 * Boar.colors tokens derived from them, and the brand type at the user's text size. Old ids (campfire, moonlight) read
 * as System.
 */
@Composable
fun BoarTheme(themeId: String? = null, fontScale: String = "standard", content: @Composable () -> Unit) {
    val dark = when (ThemeMode.normalize(themeId)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = remember(dark, context) { boarColorScheme(context, dark) }
    val c = remember(scheme, dark) { boarColorsOf(scheme, dark) }
    // enableEdgeToEdge picks the bar icons from the phone's mode; a Light or Dark chosen here can differ from it.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.findActivity()?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalColors provides c, LocalType provides BoarType(fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
