@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package team.sopa.boar.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
            // Opens the same Hugging Face browser as the Models screen: a model added there comes back here, selected.
            DiscoverRow(onBrowse)
        }
    }
}

/** This phone's memory and free space, measured; a value Android doesn't give is left out. */
@Composable
private fun DeviceChips(state: SetupState) {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.totalRamBytes > 0) Tag(t("clean.onboarding.device.memory", "size" to ramText(state.totalRamBytes)), cs.surfaceContainerHigh, cs.onSurfaceVariant, BoarIcons.Memory)
        if (state.freeDiskBytes > 0) Tag(t("clean.onboarding.device.free", "size" to sizeText(state.freeDiskBytes)), cs.surfaceContainerHigh, cs.onSurfaceVariant, BoarIcons.Download)
    }
}

@Composable
private fun ModelCard(m: ModelOption, selected: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SelectCard(selected, Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })) {
        Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, color = LocalContentColor.current, modifier = Modifier.weight(1f))
                Text(if (m.installed) t("clean.onboarding.onPhone") else sizeText(m.sizeBytes),
                    style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.78f))
            }
            // Speed tier and how it runs here as one neutral supporting line (advice, not an error); the licence lives in
            // the model's details. Only the recommended pick carries a chip.
            val support = listOfNotNull(t(m.tier.labelKey), if (m.fit == RamFit.HEAVY) t("clean.onboarding.tag.heavy") else null,
                if (m.discovered) t("clean.onboarding.tag.discovered") else null)
            Text(support.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.78f))
            if (m.recommended) Box(Modifier.padding(top = 6.dp, bottom = 2.dp)) { Tag(t("clean.onboarding.tag.recommended"), cs.primary, cs.onPrimary, Icons.Filled.Star) }
        }
    }
}

/** How the model fits this phone: an icon and a short line in one role colour (no chip). */
@Composable
private fun FitLine(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** "Discover GGUF models": a quiet list row in the cards' own tonal style, after the catalog. */
@Composable
private fun DiscoverRow(onBrowse: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onBrowse, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = CircleShape, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t("native.browse.discoverTitle"), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(t("native.browse.discoverSubtitle"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
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
                    SelectCard(selected, Modifier.selectable(selected, role = Role.RadioButton) { vm.setDefaultModel(m.asset.id) }) {
                        RadioButton(selected, onClick = null, modifier = Modifier.padding(12.dp).align(Alignment.CenterVertically))
                        Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium, color = LocalContentColor.current)
                            Text(t(m.tier.labelKey), style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.78f))
                        }
                        if (m.recommended) Box(Modifier.align(Alignment.CenterVertically).padding(start = 8.dp)) {
                            Tag(t("clean.onboarding.tag.recommended"), cs.primary, cs.onPrimary, Icons.Filled.Star)
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
