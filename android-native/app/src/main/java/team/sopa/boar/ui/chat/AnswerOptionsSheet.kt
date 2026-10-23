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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import team.sopa.boar.chat.ChatState
import team.sopa.boar.engine.models.Units
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/** The callbacks of the Answer options sheet (the chat's ViewModel). */
class AnswerOptionsActions(
    val onSetTone: (String) -> Unit,
    val onSetModel: (String) -> Unit,
    val onSetSourcesOwn: (Boolean) -> Unit,
    val onSetDeepResearch: (Boolean) -> Unit,
    val onReset: () -> Unit,
)

private enum class OptionsPage { LIST, STYLE, MODEL, SOURCES }

/**
 * The composer's options button opens this one sheet: Style, Model and Sources as a grouped list (each with its current
 * value, in the primary colour when it isn't the default, and a chevron into its own radio list on the same sheet), Deep
 * Research as its switch block, and Reset to defaults. Canvas F1–F5 ("V4 field without chips + V5 sheet", r4to, Oct 22).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerOptionsSheet(state: ChatState, actions: AnswerOptionsActions, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    var page by rememberSaveable { mutableStateOf(OptionsPage.LIST) }
    BackHandler(page != OptionsPage.LIST) { page = OptionsPage.LIST }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLow,
        scrimColor = scheme.scrim.copy(alpha = if (dark) 0.6f else 0.32f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "answerOptions") { p ->
                when (p) {
                    OptionsPage.LIST -> OptionsList(state, actions) { page = it }
                    else -> OptionsDetail(p, state, actions) { page = OptionsPage.LIST }
                }
            }
        }
    }
}

@Composable
private fun OptionsList(state: ChatState, actions: AnswerOptionsActions, open: (OptionsPage) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val model = state.models.firstOrNull { it.id == state.activeModelId }
    Column(Modifier.fillMaxWidth()) {
        Text(t("design.options.title"), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 16.dp))
        val rows = listOf(
            Triple(OptionsPage.STYLE, toneVector(state.personalityId), Pair(t("design.options.style"),
                t("personalities.${state.personalityId}.label") to (state.personalityId != state.defaultPersonalityId))),
            Triple(OptionsPage.MODEL, BoarIcons.Memory, Pair(t("design.options.model"),
                (model?.name ?: t("design.options.noModel")) to (state.defaultModelId != null && state.activeModelId != state.defaultModelId))),
            Triple(OptionsPage.SOURCES, BoarIcons.MenuBook, Pair(t("design.options.sources"),
                t(if (state.sourcesOwn) "design.options.sourcesOwn" else "design.options.sourcesAll") to state.sourcesOwn)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { i, (target, icon, text) ->
                val (title, value) = text
                val (current, changed) = value
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(current, color = if (changed) scheme.primary else scheme.onSurfaceVariant) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainerHigh,
                        leadingIconColor = scheme.onSurfaceVariant, trailingIconColor = scheme.onSurfaceVariant),
                    modifier = Modifier.clip(groupedShape(i, rows.size, outer = 20.dp))
                        .selectable(false, role = Role.Button) { open(target) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        DeepResearchRow(state.deepResearch, actions.onSetDeepResearch,
            disabledNote = if (state.sourcesOwn) t("design.options.deepResearchOff") else null)
        TextButton(actions.onReset, enabled = state.optionsChanged, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
            Text(t("design.options.reset"))
        }
    }
}

@Composable
private fun OptionsDetail(page: OptionsPage, state: ChatState, actions: AnswerOptionsActions, back: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    data class Choice(val id: String, val label: String, val note: String, val icon: ImageVector?, val selected: Boolean, val pick: () -> Unit)
    val (title, choices, foot) = when (page) {
        OptionsPage.STYLE -> Triple(t("design.options.style"), state.tones.map { (id, _) ->
            Choice(id, t("personalities.$id.label"), t("personalities.$id.description"), toneVector(id), id == state.personalityId) { actions.onSetTone(id) }
        }, null)
        OptionsPage.MODEL -> Triple(t("design.options.model"), state.models.map { m ->
            val note = listOfNotNull(Units.formatBytes(m.sizeBytes), if (m.recommended) t("design.options.recommended") else null).joinToString(" · ")
            Choice(m.id, m.name, note, null, m.id == state.activeModelId) { actions.onSetModel(m.id) }
        }, t("design.options.modelFoot"))
        else -> Triple(t("design.options.sources"), listOf(
            Choice("all", t("design.options.sourcesAll"), t("design.options.sourcesAllNote"), BoarIcons.MenuBook, !state.sourcesOwn) { actions.onSetSourcesOwn(false) },
            Choice("own", t("design.options.sourcesOwn"), t("design.options.sourcesOwnNote"), BoarIcons.Description, state.sourcesOwn) { actions.onSetSourcesOwn(true) },
        ), null)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("design.options.back")) }
            Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            choices.forEachIndexed { i, c ->
                ListItem(
                    headlineContent = { Text(c.label) },
                    supportingContent = { Text(c.note) },
                    leadingContent = c.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                    trailingContent = { RadioButton(c.selected, onClick = null) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (c.selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                        headlineColor = if (c.selected) scheme.onSecondaryContainer else scheme.onSurface,
                        supportingColor = if (c.selected) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                        leadingIconColor = if (c.selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant),
                    // Picking goes back to the list, where the row now shows the new value.
                    modifier = Modifier.clip(groupedShape(i, choices.size, outer = 20.dp))
                        .selectable(c.selected, role = Role.RadioButton) { c.pick(); back() },
                )
            }
        }
        foot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp)) }
    }
}
