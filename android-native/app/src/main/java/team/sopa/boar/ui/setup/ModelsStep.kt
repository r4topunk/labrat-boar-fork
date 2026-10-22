@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package team.sopa.boar.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import team.sopa.boar.setup.ModelOption
import team.sopa.boar.setup.RamFit
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

internal const val PROGRESS_MODELS = 0.25f
internal const val PROGRESS_DEFAULT = 0.4f
internal const val PROGRESS_KNOWLEDGE = 0.55f

/** Step 2: every answer model as a checkable card with tags; Discover (Hugging Face) at the bottom. */
@Composable
internal fun ModelsStep(state: SetupState, vm: SetupViewModel, onBrowse: () -> Unit, onBack: () -> Unit) {
    val n = state.selectedModelIds.size
    StepScaffold(
        progress = PROGRESS_MODELS,
        onBack = onBack,
        bottomBar = {
            val download = state.plan.modelsDownloadBytes
            ActionBar(
                summary = if (n == 0) t("clean.onboarding.models.none") else t("clean.onboarding.models.selected", "count" to n),
                detail = when {
                    n == 0 -> t("clean.onboarding.models.pickOne")
                    download > 0 -> t("clean.onboarding.models.toDownload", "size" to sizeText(download))
                    else -> t("clean.onboarding.models.nothingToDownload")
                },
            ) {
                Button({ vm.next() }, enabled = state.canContinueModels) {
                    Text(t("clean.onboarding.continue"))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(ButtonDefaults.IconSize))
                }
            }
        },
    ) {
        item(key = "header") { StepHeader(t("clean.onboarding.models.title"), t("clean.onboarding.models.subtitle")) }
        item(key = "device") { DeviceChips(state) }
        items(state.models, key = { it.asset.id }) { m ->
            ModelCard(m, m.asset.id in state.selectedModelIds) { vm.toggleModel(m.asset.id) }
        }
        item(key = "discover") {
            Column(Modifier.padding(top = 8.dp)) {
                // The same card as the Models screen: a model added there comes back here, listed and selected.
                team.sopa.boar.ui.models.DiscoverGgufCard(onBrowse)
            }
        }
    }
}

/** This phone's memory and free space, measured; a value Android doesn't give is left out. */
@Composable
private fun DeviceChips(state: SetupState) {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.totalRamBytes > 0) Tag(t("clean.onboarding.device.memory", "size" to ramText(state.totalRamBytes)), cs.surfaceContainerHigh, cs.onSurfaceVariant, BoarIcons.Memory, outlined = true)
        if (state.freeDiskBytes > 0) Tag(t("clean.onboarding.device.free", "size" to sizeText(state.freeDiskBytes)), cs.surfaceContainerHigh, cs.onSurfaceVariant, BoarIcons.Download, outlined = true)
    }
}

/** Selected: a tinted container and a 2 dp primary edge; the whole card is the checkbox's target. */
@Composable
private fun selectableCardColors(selected: Boolean) = CardDefaults.outlinedCardColors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
)

@Composable
internal fun selectableBorder(selected: Boolean) = BorderStroke(
    if (selected) 2.dp else 1.dp,
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
)

@Composable
private fun ModelCard(m: ModelOption, selected: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    OutlinedCard(
        Modifier.fillMaxWidth().clip(shape).toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        shape = shape, colors = selectableCardColors(selected), border = selectableBorder(selected),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text(if (m.installed) t("clean.onboarding.onPhone") else sizeText(m.sizeBytes),
                        style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.recommended) Tag(t("clean.onboarding.tag.recommended"), cs.primary, cs.onPrimary, Icons.Filled.Star)
                    Tag(t(m.tier.labelKey), cs.secondaryContainer, cs.onSecondaryContainer)
                    when (m.fit) {
                        RamFit.FITS -> Tag(t("clean.onboarding.tag.fits"), cs.tertiaryContainer, cs.onTertiaryContainer, Icons.Filled.CheckCircle)
                        RamFit.HEAVY -> Tag(t("clean.onboarding.tag.heavy"), cs.errorContainer, cs.onErrorContainer, Icons.Outlined.Warning)
                        RamFit.UNKNOWN -> Unit
                    }
                    m.license?.let { Tag(it, cs.surface, cs.onSurfaceVariant, outlined = true) }
                    if (m.discovered) Tag(t("clean.onboarding.tag.discovered"), cs.surface, cs.onSurfaceVariant, outlined = true)
                }
                if (m.fit == RamFit.HEAVY) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = cs.error)
                        Text(t("clean.onboarding.models.heavyWarning"), style = MaterialTheme.typography.bodySmall, color = cs.error)
                    }
                }
            }
        }
    }
}

/** Step 3 (only with two or more models): which one answers after setup. */
@Composable
internal fun DefaultModelStep(state: SetupState, vm: SetupViewModel, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val options = state.selectedModels.sortedByDescending { it.recommended }
    StepScaffold(
        progress = PROGRESS_DEFAULT,
        onBack = onBack,
        bottomBar = {
            val chosen = options.firstOrNull { it.asset.id == state.defaultModelId }
            ActionBar(summary = chosen?.name ?: t("clean.onboarding.default.none"), detail = null) {
                Button({ vm.next() }, enabled = chosen != null) {
                    Text(t("clean.onboarding.continue"))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(ButtonDefaults.IconSize))
                }
            }
        },
    ) {
        item(key = "header") { StepHeader(t("clean.onboarding.default.title"), t("clean.onboarding.default.subtitle")) }
        item(key = "radios") {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                options.forEach { m ->
                    val selected = m.asset.id == state.defaultModelId
                    val shape = MaterialTheme.shapes.large
                    OutlinedCard(
                        Modifier.fillMaxWidth().clip(shape).selectable(selected, role = Role.RadioButton) { vm.setDefaultModel(m.asset.id) },
                        shape = shape, colors = selectableCardColors(selected), border = selectableBorder(selected),
                    ) {
                        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, onClick = null, modifier = Modifier.padding(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(m.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (m.recommended) Tag(t("clean.onboarding.tag.recommended"), cs.primary, cs.onPrimary, Icons.Filled.Star)
                                    Tag(t(m.tier.labelKey), cs.secondaryContainer, cs.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "note") {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Text(t("clean.onboarding.default.switchLater"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}
