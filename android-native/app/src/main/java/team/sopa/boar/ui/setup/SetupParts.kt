package team.sopa.boar.ui.setup

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import team.sopa.boar.engine.models.Units
import java.text.NumberFormat
import java.util.Locale

/** Screen gutter and the vertical rhythm of every step. */
internal val Gutter = 24.dp
internal val Gap = 16.dp

/** False when the phone has animations turned off (Developer options / Accessibility → Remove animations). */
@Composable
internal fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
}

/** The brand headline face, larger: the type scale maps every display role onto the brand headline size. */
@Composable
internal fun displayStyle(scale: Float = 1.45f): TextStyle {
    val base = MaterialTheme.typography.displaySmall
    fun TextUnit.scaled(f: Float) = if (isSpecified) this * f else this
    return base.copy(fontSize = base.fontSize.scaled(scale), lineHeight = base.lineHeight.scaled(scale * 0.92f))
}

// --- Formatting ------------------------------------------------------------------------------------------------------

@Composable
internal fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** File sizes in decimal units, in the app's language ("1.6 GB", "1,6 GB"). */
@Composable
internal fun sizeText(bytes: Long): String = Units.formatBytes(bytes, appLocale())

/** 5300 -> "5,300" (the language's grouping). */
@Composable
internal fun countText(n: Int): String = NumberFormat.getIntegerInstance(appLocale()).format(n)
