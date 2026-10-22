package team.sopa.boar.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The three theme choices in Settings. Campfire and Moonlight are gone: the app follows the wallpaper (Material You),
 * so a saved "campfire"/"moonlight" (or nothing) reads as System.
 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val all = listOf(SYSTEM, LIGHT, DARK)
    const val DEFAULT_ID = SYSTEM
    fun normalize(id: String?): String = id?.takeIf { it in all } ?: SYSTEM
}

/**
 * The Material color scheme: the wallpaper's on Android 12+, otherwise one seeded from the brand (Moonlight's navy
 * and amber at night, a warm cream with the same amber by day), so phones before 12 still look like BOAR.
 */
fun boarColorScheme(context: Context, dark: Boolean): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) brandDark else brandLight

private val brandDark = ThemeTokens.moonlight.let { m ->
    darkColorScheme(
        primary = Color(0xFFFFB95C), onPrimary = Color(0xFF462A00), primaryContainer = Color(0xFF643F00), onPrimaryContainer = Color(0xFFFFDDB7),
        secondary = Color(0xFFDDC2A1), onSecondary = Color(0xFF3E2D16), secondaryContainer = Color(0xFF56432B), onSecondaryContainer = Color(0xFFFADEBC),
        tertiary = Color(0xFFBAC3FF), onTertiary = Color(0xFF222C61), tertiaryContainer = Color(0xFF394379), onTertiaryContainer = Color(0xFFDEE0FF),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        background = m.bgTerminal, onBackground = m.textPrimary, surface = m.bgTerminal, onSurface = m.textPrimary,
        surfaceVariant = m.bgCardElevated, onSurfaceVariant = m.textSecondary, outline = m.textDim, outlineVariant = m.borderDefault,
        surfaceContainerLowest = m.bgBlack, surfaceContainerLow = m.bgSurface, surfaceContainer = m.bgCard,
        surfaceContainerHigh = m.bgCardElevated, surfaceContainerHighest = m.bgCardHover,
        inverseSurface = m.textPrimary, inverseOnSurface = m.bgTerminal, inversePrimary = Color(0xFF835400),
    )
}

private val brandLight = lightColorScheme(
    primary = Color(0xFF835400), onPrimary = Color.White, primaryContainer = Color(0xFFFFDDB7), onPrimaryContainer = Color(0xFF2A1700),
    secondary = Color(0xFF6F5B40), onSecondary = Color.White, secondaryContainer = Color(0xFFFADEBC), onSecondaryContainer = Color(0xFF271904),
    tertiary = Color(0xFF515B92), onTertiary = Color.White, tertiaryContainer = Color(0xFFDEE0FF), onTertiaryContainer = Color(0xFF0C164B),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F4), onBackground = Color(0xFF201B15), surface = Color(0xFFFFF8F4), onSurface = Color(0xFF201B15),
    surfaceVariant = Color(0xFFF0E0D0), onSurfaceVariant = Color(0xFF50453A), outline = Color(0xFF827568), outlineVariant = Color(0xFFD4C4B5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBF2EA), surfaceContainer = Color(0xFFF5ECE4),
    surfaceContainerHigh = Color(0xFFEFE6DE), surfaceContainerHighest = Color(0xFFEAE1D9),
)

/**
 * The app's color tokens taken from the Material scheme, so every screen that reads Boar.colors follows the wallpaper
 * and the light/dark choice without changes of its own. Each token keeps its old role: page and card backgrounds ->
 * surface and the surface containers (one step up per level), text -> onSurface / onSurfaceVariant / outline, the two
 * accents (emerald, cyan) -> primary and tertiary, danger (crimson) -> error. Amber ("may be slow", "keep BOAR open",
 * the brand subtitle) -> tertiary too: a fixed gold next to a wallpaper-blue primary read as a second, unrelated accent
 * (review setup-5). Material has no warning role, so real failures use error and caution uses tertiary; the pair of
 * a filled amber (amber400) is onTertiary.
 */
fun boarColorsOf(s: ColorScheme, dark: Boolean): BoarColors {
    val amber = s.tertiary
    return BoarColors(
        id = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, name = if (dark) "Dark" else "Light", icon = "",
        bgBlack = s.surfaceContainerLowest,
        bgTerminal = s.surface,
        bgSurface = s.surfaceContainerLow,
        bgCard = s.surfaceContainer,
        bgCardElevated = s.surfaceContainerHigh,
        bgCardHover = s.surfaceContainerHighest,
        // Fields sit on cards: the highest container, as Material's filled fields.
        bgInput = s.surfaceContainerHighest,
        bgSubtle = s.onSurface.copy(alpha = 0.05f),
        bgOverlay = s.surface.copy(alpha = 0.78f),
        bgModalOverlay = s.scrim.copy(alpha = if (dark) 0.85f else 0.5f),
        borderSubtle = s.outlineVariant.copy(alpha = 0.5f),
        borderDefault = s.outlineVariant,
        borderElevated = s.outline,
        borderFocus = s.primary,
        borderEmerald = s.primary.copy(alpha = 0.35f),
        borderCyan = s.tertiary.copy(alpha = 0.35f),
        borderAmber = amber.copy(alpha = 0.4f),
        borderDanger = s.error.copy(alpha = 0.4f),
        borderFrontier = s.tertiary.copy(alpha = 0.45f),
        textPrimary = s.onSurface,
        textHeading = s.onSurface,
        textSecondary = s.onSurfaceVariant,
        textMuted = s.onSurfaceVariant,
        textDim = s.outline,
        // Text and thumbs on a filled accent (emerald500/600 backgrounds).
        textInverse = s.onPrimary,
        textAccentEmerald = s.primary,
        textAccentCyan = s.tertiary,
        textAccentAmber = amber,
        textAccentViolet = s.tertiary,
        emerald50 = s.onPrimaryContainer,
        emerald400 = s.primary,
        emerald500 = s.primary,
        emerald600 = s.primary,
        emerald900 = s.primaryContainer,
        emeraldBgSubtle = s.primary.copy(alpha = 0.12f),
        emeraldBorder = s.primary.copy(alpha = 0.28f),
        cyan400 = s.tertiary,
        cyan500 = s.tertiary,
        cyan600 = s.tertiary,
        cyanBgSubtle = s.tertiary.copy(alpha = 0.12f),
        cyanBorder = s.tertiary.copy(alpha = 0.3f),
        frontierGlow = s.primary,
        frontierGlowCyan = s.tertiary,
        frontierBadgeBg = s.tertiary.copy(alpha = 0.18f),
        frontierBadgeBorder = s.tertiary.copy(alpha = 0.45f),
        frontierText = s.tertiary,
        frontierGradientStart = s.surfaceContainerHigh,
        frontierGradientEnd = s.surface,
        amber400 = amber,
        amber500 = amber,
        amber600 = amber,
        amberBgSubtle = amber.copy(alpha = 0.12f),
        amberBorder = amber.copy(alpha = 0.35f),
        crimson400 = s.error,
        crimson500 = s.error,
        crimson600 = s.error,
        crimson900 = s.errorContainer,
        crimsonBgSubtle = s.error.copy(alpha = 0.12f),
        crimsonBorder = s.error.copy(alpha = 0.35f),
    )
}
