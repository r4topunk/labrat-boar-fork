package team.sopa.boar.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import team.sopa.boar.ui.theme.IconSize

/**
 * A list row with M3 ListItem's metrics (16 dp insets, 16 dp gaps, 72 dp with a supporting line, 12 dp vertical padding)
 * but one alignment rule at any line count: the leading icon sits on the headline's first line (its x-height centre), the
 * trailing control (radio, switch, chevron) centres on the whole row. ListItem top-aligns both from three lines on and
 * centres both below that, so sibling rows with 2- and 3-line descriptions disagreed.
 */
@Composable
internal fun OptionRow(
    headline: String,
    supporting: String?,
    leading: ImageVector?,
    container: Color,
    headlineColor: Color,
    supportingColor: Color,
    leadingColor: Color,
    modifier: Modifier = Modifier,
    trailingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
) {
    val type = MaterialTheme.typography
    val lineHeight = with(LocalDensity.current) { type.bodyLarge.lineHeight.toDp() }
    Row(
        modifier.fillMaxWidth().background(container).heightIn(min = if (supporting != null) 72.dp else 56.dp)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // The icon in a box one headline line tall, so it centres on that line and not on the whole text block. For
            // bodyLarge (16/24 sp) the line box centre already sits on the x-height centre (measured 0.5 dp), so no shift.
            if (leading != null) Box(Modifier.heightIn(min = lineHeight), contentAlignment = Alignment.Center) {
                Icon(leading, contentDescription = null, tint = leadingColor, modifier = Modifier.size(IconSize.Standard))
            }
            Column(Modifier.weight(1f)) {
                Text(headline, style = type.bodyLarge, color = headlineColor)
                if (supporting != null) Text(supporting, style = type.bodyMedium, color = supportingColor)
            }
        }
        if (trailing != null) CompositionLocalProvider(LocalContentColor provides trailingColor) { trailing() }
    }
}
