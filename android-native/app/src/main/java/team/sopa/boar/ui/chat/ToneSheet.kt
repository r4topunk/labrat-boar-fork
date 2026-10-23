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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
    "detailed" -> BoarIcons.Book
    "summary" -> BoarIcons.Description
    else -> Icons.Outlined.Edit
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
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 16.dp))
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
                OptionRow(t("personalities.$id.label"), t("personalities.$id.description"), toneVector(id),
                    container = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                    headlineColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                    supportingColor = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    leadingColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    modifier = Modifier.clip(groupedShape(i, tones.size, outer = 20.dp))
                        .selectable(selected, role = Role.RadioButton) { onSetTone(id) },
                ) { RadioButton(selected, onClick = null) }
            }
        }
        Spacer(Modifier.height(16.dp))
        // Deep Research is not a style (it runs with any of them): its own block under the list.
        DeepResearchRow(deepResearch, onSetDeepResearch)
    }
}

/**
 * Deep Research as one switch block: in Settings, and in the chat's Answer options sheet. [disabledNote]: why it can't be
 * turned on now (Sources = My files); the row dims, the switch is off and disabled, and the note replaces the description.
 */
@Composable
fun DeepResearchRow(deepResearch: Boolean, onSetDeepResearch: (Boolean) -> Unit, disabledNote: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val enabled = disabledNote == null
    val on = deepResearch && enabled
    val dim = scheme.onSurface.copy(alpha = 0.38f)
    // Filled flask only while it is on (the selected state); outlined otherwise, like its sibling rows.
    OptionRow(t("personalitySettings.deepResearchTitle"), disabledNote ?: t("design.tone.deepResearchNote"),
        if (on) BoarIcons.ScienceFilled else BoarIcons.Science,
        container = scheme.surfaceContainerHigh, headlineColor = if (enabled) scheme.onSurface else dim,
        supportingColor = scheme.onSurfaceVariant, leadingColor = if (on) scheme.tertiary else if (enabled) scheme.onSurfaceVariant else dim,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onSetDeepResearch),
    ) { Switch(on, onCheckedChange = null, enabled = enabled) }
}
