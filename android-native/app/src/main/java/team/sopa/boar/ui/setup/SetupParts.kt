package team.sopa.boar.ui.setup

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import team.sopa.boar.engine.models.Units
import team.sopa.boar.ui.t
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

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

/** Fades and lifts its content in, [index] × 60 ms after the screen appears; shown at once when [motion] is off. */
@Composable
internal fun Staggered(index: Int, motion: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion) {
            delay(index * 60L)
            p.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        } else p.snapTo(1f)
    }
    Box(modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 16.dp.toPx() }) { content() }
}

/** The brand headline face, larger: the type scale maps every display role onto the brand headline size. */
@Composable
internal fun displayStyle(scale: Float = 1.45f): TextStyle {
    val base = MaterialTheme.typography.displaySmall
    fun TextUnit.scaled(f: Float) = if (isSpecified) this * f else this
    return base.copy(fontSize = base.fontSize.scaled(scale), lineHeight = base.lineHeight.scaled(scale * 0.92f))
}

/**
 * A setup step after the welcome: a slim top row (Back + where we are in the flow), a list that scrolls under the
 * system bars, and a bottom action bar. [progress] is 0..1 through the flow; [onBack] null hides the arrow.
 */
@Composable
internal fun StepScaffold(
    progress: Float,
    onBack: (() -> Unit)?,
    listState: LazyListState = rememberLazyListState(),
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = Gutter).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("clean.onboarding.back")) }
                } else Spacer(Modifier.width(48.dp))
                Spacer(Modifier.width(8.dp))
                val label = t("clean.onboarding.progressA11y", "pct" to (progress * 100).toInt())
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(6.dp).semantics { contentDescription = label },
                    trackColor = cs.surfaceContainerHighest,
                )
            }
        },
        bottomBar = { bottomBar?.invoke() },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = Gutter, end = Gutter,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + Gutter,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** One headline per screen, supporting text in onSurfaceVariant. */
@Composable
internal fun StepHeader(title: String, subtitle: String?) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = cs.onSurface, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant) }
    }
}

/** A list section title (sticky in the knowledge step: it carries its own surface). */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Text(
            text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

/** A small read-only label (model and pack tags): one container/content role pair, an optional 16 dp icon. */
@Composable
internal fun Tag(label: String, container: Color, content: Color, icon: ImageVector? = null, outlined: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (outlined) Color.Transparent else container,
        contentColor = content,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon?.let { Icon(it, null, Modifier.size(14.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The bottom bar of the choosing steps: a summary on the start side, the step's action on the end. */
@Composable
internal fun ActionBar(summary: String, detail: String?, detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, action: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer) {
        Column {
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Gutter, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gap),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(summary, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = detailColor, maxLines = 2) }
                }
                action()
            }
        }
    }
}

// --- Formatting ------------------------------------------------------------------------------------------------------

@Composable
internal fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** File sizes in decimal units, in the app's language ("1.6 GB", "1,6 GB"). */
@Composable
internal fun sizeText(bytes: Long): String = Units.formatBytes(bytes, appLocale())

/** RAM in binary units, as Android's own "Memory" line: "7.2 GB". */
@Composable
internal fun ramText(bytes: Long): String {
    val l = appLocale()
    return if (bytes >= 999.5 * 1024 * 1024) String.format(l, "%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"
}

/** 5300 -> "5,300" (the language's grouping). */
@Composable
internal fun countText(n: Int): String = NumberFormat.getIntegerInstance(appLocale()).format(n)

internal fun minutesLeft(seconds: Long) = maxOf(1, ceil(seconds / 60.0).toInt())
