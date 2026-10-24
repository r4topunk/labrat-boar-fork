package team.sopa.boar.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import team.sopa.boar.chat.ChatState
import team.sopa.boar.chat.SourceRow
import team.sopa.boar.ui.setup.countText
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/** The callbacks of the Answer options sheet (the chat's ViewModel). */
class AnswerOptionsActions(
    val onSetSourcesOwn: (Boolean) -> Unit,
    val onReset: () -> Unit,
    val onToggleSource: (String) -> Unit = {},
    val onRefreshSources: () -> Unit = {},
)

private enum class OptionsPage { LIST, SOURCES }

/**
 * The composer's options button opens this one sheet: Sources as a grouped list (with its current value, in the primary
 * colour when it isn't the default, and a chevron into its own list on the same sheet), and Reset to defaults. Canvas
 * F1–F5 ("V4 field without chips + V5 sheet", r4to, Oct 22). The model (always Qwen3 1.7B), the answer style (always
 * Succinct) and Deep Research are not offered in this app (r4to, Oct 24), so none has a row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerOptionsSheet(state: ChatState, actions: AnswerOptionsActions, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    var page by rememberSaveable { mutableStateOf(OptionsPage.LIST) }
    BackHandler(page != OptionsPage.LIST) { page = OptionsPage.LIST }
    // The Sources checklist lists what is installed now (a pack installed since the last look is on).
    LaunchedEffect(Unit) { actions.onRefreshSources() }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLow,
        scrimColor = scheme.scrim.copy(alpha = if (dark) 0.6f else 0.32f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "answerOptions") { p ->
                when (p) {
                    OptionsPage.LIST -> OptionsList(state, actions) { page = it }
                    OptionsPage.SOURCES -> SourcesDetail(state, actions) { page = OptionsPage.LIST }
                }
            }
        }
    }
}

@Composable
private fun OptionsList(state: ChatState, actions: AnswerOptionsActions, open: (OptionsPage) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(t("design.options.title"), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 16.dp))
        val rows = listOf(
            Triple(OptionsPage.SOURCES, BoarIcons.LibraryBooks, Pair(t("design.options.sources"),
                sourcesSummary(state) to !state.sourcesEverything)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { i, (target, icon, text) ->
                val (title, value) = text
                val (current, changed) = value
                OptionRow(title, current, icon,
                    container = scheme.surfaceContainerHigh, headlineColor = scheme.onSurface,
                    supportingColor = if (changed) scheme.primary else scheme.onSurfaceVariant, leadingColor = scheme.onSurfaceVariant,
                    modifier = Modifier.clip(groupedShape(i, rows.size, outer = 20.dp))
                        .selectable(false, role = Role.Button) { open(target) },
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
            }
        }
        TextButton(actions.onReset, enabled = state.optionsChanged, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)) {
            Text(t("design.options.reset"))
        }
    }
}

/** The Sources row's value: Everything, Only my files, or "4 of 7 sources". */
@Composable
private fun sourcesSummary(state: ChatState): String = when {
    state.sourcesOwn -> t("design.options.sourcesOwn")
    state.sourcesEverything -> t("design.options.sourcesAll")
    else -> t("design.options.sourcesSome", "on" to state.sourcesChecked.size, "total" to state.sourceRows.size)
}

/**
 * Sources: two quick choices (Everything, Only my files; picking one goes back to the list, as the radio pages do), then
 * the checklist of what is searched: Your files, Wikipedia essentials (the built-in library) and each installed pack with
 * its article count. The last ticked row is disabled, so at least one source stays on (the note under the list says so).
 * Only Your files ticked is Only my files; any other partial selection only filters the search.
 */
@Composable
private fun SourcesDetail(state: ChatState, actions: AnswerOptionsActions, back: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val checked = state.sourcesChecked
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("design.options.back")) }
            Spacer(Modifier.width(8.dp))
            Text(t("design.options.sources"), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        }
        val quick = listOf(
            Triple(t("design.options.sourcesAll"), t("design.options.sourcesAllNote"), BoarIcons.LibraryBooks) to (state.sourcesEverything to false),
            Triple(t("design.options.sourcesOwn"), t("design.options.sourcesOwnNote"), BoarIcons.Description) to (state.sourcesOwn to true),
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            quick.forEachIndexed { i, (text, pick) ->
                val (label, note, icon) = text
                val (selected, own) = pick
                OptionRow(label, note, icon,
                    container = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                    headlineColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                    supportingColor = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    leadingColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    modifier = Modifier.clip(groupedShape(i, quick.size, outer = 20.dp))
                        .selectable(selected, role = Role.RadioButton) { actions.onSetSourcesOwn(own); back() },
                ) { RadioButton(selected, onClick = null) }
            }
        }
        Text(t("design.options.sourcesSearchIn"), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
        val rows = state.sourceRows
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { i, row ->
                val on = row.id in checked
                // The only ticked row can't be unticked.
                val enabled = !(on && checked.size == 1)
                val name = row.nameKey?.let { t(it) } ?: row.name
                val note = when (row.id) {
                    SourceRow.FILES -> t("design.options.sourcesFilesNote")
                    else -> row.articles?.takeIf { it > 0 }?.let { t("clean.onboarding.articles", "count" to it, "value" to countText(it)) }
                }
                val icon = when (row.id) {
                    SourceRow.FILES -> BoarIcons.Description
                    else -> BoarIcons.LibraryBooks
                }
                OptionRow(name, note, icon,
                    container = scheme.surfaceContainerHigh, headlineColor = scheme.onSurface,
                    supportingColor = scheme.onSurfaceVariant, leadingColor = scheme.onSurfaceVariant,
                    modifier = Modifier.clip(groupedShape(i, rows.size, outer = 20.dp))
                        .toggleable(on, enabled = enabled, role = Role.Checkbox) { actions.onToggleSource(row.id) },
                ) { Checkbox(on, onCheckedChange = null, enabled = enabled) }
            }
        }
        Text(t("design.options.sourcesFoot"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
    }
}
