package team.sopa.boar.ui.theme

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon sizes as named tokens (Material 3): 24 dp in a 48 dp touch target (IconButton, list rows, nav items), 18 dp inside a
 * button or chip (ButtonDefaults.IconSize), 16 dp beside 12-14 sp meta text, and 20 dp for the quiet inline actions under
 * an answer (copy, reasoning), which sit next to 12 sp text and must not outweigh it.
 */
object IconSize {
    val Standard: Dp = 24.dp
    val Meta: Dp = 16.dp
    val InlineAction: Dp = 20.dp
    /** The status check before "Works offline": a filled circle, so 14 dp reads as heavy as a 16 dp outline. */
    val Status: Dp = 14.dp
}

/**
 * How far a single text line's x-height centre sits below its line-box centre. Measured on the Pixel (Roboto Flex, the
 * M3 type scale): 1.1-1.7 dp for the compact 12-14 sp styles (labelSmall/Medium/Large, bodySmall/Medium), so 0.11 x
 * the font size. Calibrated for those styles only: a bodyLarge (16/24 sp) line in a list row is already centred within
 * 0.5 dp, so rows use no shift; the composer's field uses it and was re-measured (0.6 dp). A row centring an icon and a line by their boxes leaves
 * the icon that much high; offsetting the icon down by it (or the text up) puts both on one optical centreline.
 */
@Composable
fun xHeightShift(style: TextStyle): Dp = with(LocalDensity.current) { (style.fontSize.toPx() * 0.11f).toDp() }

/** An icon beside a single line of [style] text, centred on that line's x-height instead of its line box. */
@Composable
fun Modifier.onTextLine(style: TextStyle): Modifier = this.offset(y = xHeightShift(style))

/** A single line of [style] text whose x-height centre should land on its row's centre (beside a centred icon or badge). */
@Composable
fun Modifier.textOnRowCenter(style: TextStyle): Modifier = this.offset(y = -xHeightShift(style))
