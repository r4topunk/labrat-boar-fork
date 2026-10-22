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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.sopa.boar.chat.Source
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

private val URL = Regex("""\s*[—–:-]*\s*https?://\S+""")
private val EMPTY_PARENS = Regex("""\(\s*\)""")
private val SPACES = Regex("""[ \t]{2,}""")

/** Where the passage comes from, as a name: "Wikipedia — https://…" shows "Wikipedia" (the URL only with technical details). */
private fun sourceName(s: Source, yourDocuments: String, technicalDetails: Boolean): String {
    val raw = s.source ?: if (s.docId?.startsWith("custom-") == true) yourDocuments else s.title
    return if (technicalDetails) raw else raw.replace(URL, "").trim().ifEmpty { s.title }
}

/** The extraction leaves "Mount Kilimanjaro () is…" where the pronunciation was; drop the empty brackets. */
private fun cleanBody(body: String) = body.replace(EMPTY_PARENS, "").replace(SPACES, " ").trim()

/**
 * components/SourceFootnotes.tsx: the numbered sources under an answer; tapping one shows the passage the model read.
 * Each row is a stock ListItem (56 dp, button role, expanded/collapsed state for TalkBack). The retrieval similarity is
 * jargon that reads as "BOAR is 79 % sure", so it shows only with technical details.
 */
@Composable
fun SourceFootnotes(sources: List<Source>, title: String? = null, technicalDetails: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf<Int?>(null) }
    val yourDocuments = t("native.sourceFootnotes.yourDocuments")
    val expandedState = t("clean.chat.expanded")
    val collapsedState = t("clean.chat.collapsed")
    val showPassage = t("clean.chat.showPassage")
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = scheme.outlineVariant)
        Text(title ?: t("sourceFootnotes.title"), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.padding(top = 4.dp))
        sources.forEachIndexed { i, s ->
            val open = expanded == i
            val rotation by animateFloatAsState(if (open) 180f else 0f, label = "sourceCaret")
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(if (open) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)) {
                ListItem(
                    headlineContent = { Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (technicalDetails && s.similarity != null) {
                        @Composable { Text("%.0f%%".format(s.similarity * 100), style = MaterialTheme.typography.labelSmall) }
                    } else null,
                    leadingContent = {
                        Badge(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) { Text("${i + 1}") }
                    },
                    trailingContent = { Icon(BoarIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.semantics { stateDescription = if (open) expandedState else collapsedState }
                        .clickable(onClickLabel = showPassage, role = Role.Button) { expanded = if (open) null else i },
                )
                AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sourceName(s, yourDocuments, technicalDetails), style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(cleanBody(s.body), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
