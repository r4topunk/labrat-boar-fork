package team.sopa.boar.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
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
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp).padding(bottom = 16.dp)) {
            Text(t("modelSetupScreen.sections.tone"), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Column(Modifier.selectableGroup()) {
                for ((id, _) in state.tones) {
                    val selected = id == state.personalityId
                    ListItem(
                        headlineContent = { Text(t("personalities.$id.label")) },
                        supportingContent = { Text(t("personalities.$id.description")) },
                        leadingContent = { Icon(toneVector(id), contentDescription = null) },
                        trailingContent = { RadioButton(selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = if (selected) scheme.secondaryContainer else Color.Transparent),
                        modifier = Modifier.clip(MaterialTheme.shapes.large)
                            .selectable(selected, role = Role.RadioButton) { onSetTone(id); onDismiss() },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp), color = scheme.outlineVariant)
            ListItem(
                headlineContent = { Text(t("personalitySettings.deepResearchTitle")) },
                supportingContent = { Text(t("personalitySettings.deepResearchNote")) },
                leadingContent = { Icon(if (state.deepResearch) BoarIcons.ScienceFilled else BoarIcons.Science, contentDescription = null) },
                trailingContent = { Switch(state.deepResearch, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(MaterialTheme.shapes.large)
                    .toggleable(state.deepResearch, role = Role.Switch, onValueChange = onSetDeepResearch),
            )
        }
    }
}
