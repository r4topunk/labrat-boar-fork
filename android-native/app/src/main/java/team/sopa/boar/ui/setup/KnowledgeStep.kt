@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package team.sopa.boar.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.sopa.boar.setup.KnowledgeItem
import team.sopa.boar.setup.KnowledgePreset
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/** Step 4: every knowledge library by theme, quick-select chips, a preview sheet per library. */
@Composable
internal fun KnowledgeStep(state: SetupState, vm: SetupViewModel, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val allItems = state.knowledge.flatMap { it.items }

    StepScaffold(
        progress = PROGRESS_KNOWLEDGE,
        onBack = onBack,
        bottomBar = {
            val plan = state.plan
            val libraries = t("clean.onboarding.knowledge.libraries", "count" to state.selectedKnowledgeCount)
            ActionBar(
                summary = if (plan.downloadBytes > 0) t("clean.onboarding.knowledge.summary", "size" to sizeText(plan.downloadBytes), "count" to plan.estimatedMinutes)
                    else t("clean.onboarding.knowledge.nothingToDownload"),
                detail = if (plan.shortfallBytes > 0) t("flows.onboarding.noSpace", "size" to sizeText(plan.shortfallBytes)) else libraries,
                detailColor = if (plan.shortfallBytes > 0) cs.error else cs.onSurfaceVariant,
            ) {
                // Any selection goes, none included (models only); only a selection that can't fit is held back.
                Button({ vm.install() }, enabled = plan.shortfallBytes == 0L) {
                    Icon(BoarIcons.Download, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(t(if (plan.downloadBytes > 0) "clean.onboarding.knowledge.download" else "clean.onboarding.knowledge.finish"))
                }
            }
        },
    ) {
        item(key = "header") { StepHeader(t("clean.onboarding.knowledge.title"), t("clean.onboarding.knowledge.subtitle")) }
        item(key = "presets") { PresetChips(state.knowledgePreset, vm::applyPreset) }
        state.knowledge.forEach { section ->
            stickyHeader(key = "section-${section.theme}") { _ -> SectionTitle(t(section.titleKey)) }
            items(section.items, key = { it.id }) { item ->
                KnowledgeCard(item, state.isSelected(item), onToggle = { vm.toggleKnowledge(item) }, onPreview = { previewId = item.id })
            }
        }
    }

    allItems.firstOrNull { it.id == previewId }?.let { item ->
        PreviewSheet(item, state.isSelected(item), onToggle = { vm.toggleKnowledge(item) }, onDismiss = { previewId = null })
    }
}

@Composable
private fun PresetChips(current: KnowledgePreset?, onPick: (KnowledgePreset) -> Unit) {
    val presets = listOf(
        KnowledgePreset.RECOMMENDED to "clean.onboarding.preset.recommended",
        KnowledgePreset.EVERYTHING to "clean.onboarding.preset.everything",
        KnowledgePreset.MINIMAL to "clean.onboarding.preset.minimal",
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.forEach { (p, key) ->
            val selected = current == p
            FilterChip(
                selected = selected,
                onClick = { onPick(p) },
                label = { Text(t(key)) },
                leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            )
        }
    }
}

@Composable
private fun itemName(item: KnowledgeItem) = item.nameKey?.let { t(it) } ?: item.fallbackName

@Composable
private fun itemDescription(item: KnowledgeItem) = item.descriptionKey?.let { t(it) } ?: item.catalogDescription

@Composable
private fun KnowledgeCard(item: KnowledgeItem, selected: Boolean, onToggle: () -> Unit, onPreview: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    OutlinedCard(
        Modifier.fillMaxWidth(), shape = shape,
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) cs.secondaryContainer.copy(alpha = 0.55f) else cs.surface),
        border = selectableBorder(selected),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(shape).toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(start = 4.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(itemName(item), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(itemDescription(item), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.recommended) Tag(t("clean.onboarding.tag.recommended"), cs.primary, cs.onPrimary, Icons.Filled.Star)
                    Tag(if (item.installed) t("clean.onboarding.onPhone") else sizeText(item.sizeBytes), cs.secondaryContainer, cs.onSecondaryContainer)
                    item.preview?.takeIf { it.articles > 0 }?.let {
                        Tag(t("clean.onboarding.articles", "count" to it.articles, "value" to countText(it.articles)), cs.surface, cs.onSurfaceVariant, outlined = true)
                    }
                }
                if (item.clinicalCaveat) Caveat()
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, bottom = 4.dp)) {
            TextButton(onPreview) { Text(t("clean.onboarding.preview")) }
        }
    }
}

@Composable
private fun Caveat() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = cs.tertiary)
        Text(t("clean.onboarding.caveat"), style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
    }
}

/** What a library holds: articles, sources with counts, license, the catalog text and a few of its titles. */
@Composable
private fun PreviewSheet(item: KnowledgeItem, selected: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val preview = item.preview
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = cs.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Gap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(itemName(item), style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, modifier = Modifier.semantics { heading() })
                val meta = listOfNotNull(
                    preview?.takeIf { it.articles > 0 }?.let { t("clean.onboarding.articles", "count" to it.articles, "value" to countText(it.articles)) },
                    if (item.installed) t("clean.onboarding.onPhone") else sizeText(item.sizeBytes),
                )
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (item.clinicalCaveat) {
                Card(colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp))
                        Text(t("clean.onboarding.caveat"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(item.catalogDescription, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)

            if (preview != null && preview.sources.isNotEmpty()) {
                SheetLabel(t("clean.onboarding.sheet.sources"))
                Column {
                    preview.sources.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
                            Text(countText(s.documents), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            if (preview != null && preview.samples.isNotEmpty()) {
                SheetLabel(t("clean.onboarding.sheet.samples", "count" to preview.articles, "value" to countText(preview.articles)))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    preview.samples.forEach { title ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(BoarIcons.Description, null, Modifier.size(18.dp).padding(top = 2.dp), tint = cs.primary)
                            Text(title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                        }
                    }
                }
            }

            SheetLabel(t("clean.onboarding.sheet.license"))
            Text(item.license, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)

            FilledTonalButton(onToggle, Modifier.fillMaxWidth().height(52.dp)) {
                if (selected) {
                    Icon(Icons.Filled.Check, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(t(if (selected) "clean.onboarding.sheet.remove" else "clean.onboarding.sheet.add"))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetLabel(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
