package team.sopa.boar.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import team.sopa.boar.chat.ChatState
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/** The icon of an answer style (ids from personalities.json; the JSON's emoji are not used). */
internal fun toneVector(id: String): ImageVector = when (id) {
    "succinct" -> BoarIcons.Bolt
    "detailed" -> BoarIcons.MenuBook
    "summary" -> BoarIcons.Description
    else -> Icons.Filled.Edit
}

/**
 * The input bar's style chip opens this: every answer style with what it does, the current one marked, and Deep Research
 * below (it runs in any style). Replaces cycling through the styles blind. Engine presets stay in Advanced tweaks.
 * A radio group and a switch row, so TalkBack gets the roles and the whole row is the target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToneSheet(state: ChatState, onSetTone: (String) -> Unit, onSetDeepResearch: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    // In dark the default 32 % scrim barely dims the chat: a deeper scrim and a container a step up keep the sheet's edge.
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLow,
        scrimColor = scheme.scrim.copy(alpha = if (dark) 0.6f else 0.32f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(t("modelSetupScreen.sections.tone"), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 16.dp))
            ToneChoices(state.tones.map { it.first }, state.personalityId, state.deepResearch, onSetTone = { onSetTone(it); onDismiss() }, onSetDeepResearch = onSetDeepResearch)
        }
    }
}

/**
 * The answer styles as one grouped tonal list (the picked one in secondaryContainer with its radio on), and Deep Research
 * as its own block under it. Shared by the chat's sheet and Settings, so the same choice looks and reads the same.
 */
@Composable
fun ToneChoices(tones: List<String>, selectedId: String?, deepResearch: Boolean, onSetTone: (String) -> Unit, onSetDeepResearch: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tones.forEachIndexed { i, id ->
                val selected = id == selectedId
                ListItem(
                    headlineContent = { Text(t("personalities.$id.label")) },
                    supportingContent = { Text(t("personalities.$id.description")) },
                    leadingContent = { Icon(toneVector(id), contentDescription = null) },
                    trailingContent = { RadioButton(selected, onClick = null) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                        headlineColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                        supportingColor = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                        leadingIconColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant),
                    modifier = Modifier.clip(groupedShape(i, tones.size, outer = 20.dp))
                        .selectable(selected, role = Role.RadioButton) { onSetTone(id) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // Deep Research is not a style (it runs with any of them): its own block under the list.
        DeepResearchRow(deepResearch, onSetDeepResearch)
    }
}

/** Deep Research as one switch block: in Settings, and in the chat's Answer options sheet. */
@Composable
fun DeepResearchRow(deepResearch: Boolean, onSetDeepResearch: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(t("personalitySettings.deepResearchTitle")) },
        supportingContent = { Text(t("design.tone.deepResearchNote")) },
        leadingContent = { Icon(if (deepResearch) BoarIcons.ScienceFilled else BoarIcons.Science, contentDescription = null,
            tint = if (deepResearch) scheme.tertiary else scheme.onSurfaceVariant) },
        trailingContent = { Switch(deepResearch, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainerHigh),
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .toggleable(deepResearch, role = Role.Switch, onValueChange = onSetDeepResearch),
    )
}
