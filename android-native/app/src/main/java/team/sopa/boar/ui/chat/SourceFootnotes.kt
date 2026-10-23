package team.sopa.boar.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.sopa.boar.chat.Source
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.theme.IconSize
import team.sopa.boar.ui.theme.textOnRowCenter
import team.sopa.boar.ui.t

private val URL = Regex("""\s*[—–:-]*\s*https?://\S+""")
private val EMPTY_PARENS = Regex("""\(\s*\)""")
private val SPACES = Regex("""[ \t]{2,}""")

/** Where the passage comes from, as a name: "Wikipedia — https://…" shows "Wikipedia" (the URL only with technical details). */
private fun sourceName(s: Source, yourDocuments: String, technicalDetails: Boolean): String {
    val raw = s.source ?: if (s.docId?.startsWith("custom-") == true) yourDocuments else s.title
    return if (technicalDetails) raw else raw.replace(URL, "").trim().ifEmpty { s.title }
}

private val REDIRECT = Regex("""(?m)^[^.\n]{1,80}\b(redirects|redirect) here\.?[^\n]*$""")
private val LONE_GAP = Regex("""\s*\n\s*(…|\.\.\.)\s*\n\s*""")

/**
 * The passage as prose: no empty brackets where the pronunciation was ("Mount Kilimanjaro () is…"), no wiki
 * "X redirects here." lead-ins, a gap "…" kept inline instead of on its own line, and cut at the last full sentence
 * within [max] characters, so it never ends mid-word.
 */
internal fun cleanBody(body: String, max: Int = 520): String {
    val s = body.replace(REDIRECT, "").replace(LONE_GAP, " … ").replace(EMPTY_PARENS, "").replace(SPACES, " ").trim()
    if (s.length <= max) return s
    val cut = s.take(max)
    val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
    return if (end > max / 3) cut.take(end + 1) else cut.substringBeforeLast(' ') + " …"
}

/** The source's name is worth a line only when the title doesn't already start with it ("Wikivoyage — Canberra"). */
private fun showName(name: String, title: String) = name.isNotBlank() && !title.startsWith(name, ignoreCase = true) && name != title

/** The corners of a row in a grouped list: the group is one rounded block, the rows inside meet at small corners. */
internal fun groupedShape(index: Int, count: Int, outer: Dp = 16.dp, inner: Dp = 4.dp) = RoundedCornerShape(
    topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
    bottomStart = if (index == count - 1) outer else inner, bottomEnd = if (index == count - 1) outer else inner,
)

/**
 * components/SourceFootnotes.tsx: the numbered sources under an answer; tapping one shows the passage the model read.
 * A grouped tonal list (surfaceContainer rows 2 dp apart, one rounded block), no dividers or borders: a 48 dp row with
 * the number, the title and a chevron; the passage opens inside the same row. The retrieval similarity is jargon that
 * reads as "BOAR is 79 % sure", so it shows only with technical details.
 */
@Composable
fun SourceFootnotes(sources: List<Source>, title: String? = null, technicalDetails: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    var expanded by remember { mutableStateOf<Int?>(null) }
    val yourDocuments = t("native.sourceFootnotes.yourDocuments")
    val expandedState = t("clean.chat.expanded")
    val collapsedState = t("clean.chat.collapsed")
    val showPassage = t("clean.chat.showPassage")
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title ?: t("sourceFootnotes.title"), style = type.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        sources.forEachIndexed { i, s ->
            val open = expanded == i
            val rotation by animateFloatAsState(if (open) 180f else 0f, label = "sourceCaret")
            Column(Modifier.fillMaxWidth().clip(groupedShape(i, sources.size)).background(scheme.surfaceContainer)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { stateDescription = if (open) expandedState else collapsedState }
                        .clickable(onClickLabel = showPassage, role = Role.Button) { expanded = if (open) null else i }
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(24.dp).background(scheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = type.labelMedium, color = scheme.onSecondaryContainer)
                    }
                    // The title's x-height on the badge's and the chevron's centre (its line box alone sits it ~1.5 dp low).
                    Text(s.title, style = type.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).textOnRowCenter(type.bodyMedium))
                    if (technicalDetails && s.similarity != null)
                        Text("%.0f%%".format(s.similarity * 100), style = type.labelSmall, color = scheme.onSurfaceVariant)
                    Icon(BoarIcons.ExpandMore, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(IconSize.Standard).rotate(rotation))
                }
                AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    // The passage in an inset block a step up in tone, a size under the answer, so it reads as a quote, not as more answer.
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .clip(MaterialTheme.shapes.medium).background(scheme.surfaceContainerHighest).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val name = sourceName(s, yourDocuments, technicalDetails)
                        if (showName(name, s.title)) Text(name, style = type.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cleanBody(s.body), style = type.bodySmall.copy(lineHeight = type.bodySmall.fontSize * 1.45f), color = scheme.onSurface)
                    }
                }
            }
        }
    }
}
