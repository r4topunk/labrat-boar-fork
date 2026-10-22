@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package team.sopa.boar.ui.setup

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import team.sopa.boar.setup.KnowledgeTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.LocalContentColor
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
            // Libraries already on the phone count as chosen: setup never removes them.
            val libraries = t("clean.onboarding.knowledge.libraries", "count" to allItems.count { it.installed || state.isSelected(it) })
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
            // The clinical caveat once, right under the Health header (and for any other section where every library carries it).
            val sectionCaveat = section.theme == KnowledgeTheme.HEALTH || section.items.all { it.clinicalCaveat }
            if (sectionCaveat) item(key = "caveat-${section.theme}") { CaveatNote() }
            items(section.items, key = { it.id }) { item ->
                KnowledgeCard(item, state.isSelected(item), sectionCaveat = sectionCaveat, showStar = state.knowledgePreset != KnowledgePreset.RECOMMENDED,
                    onToggle = { vm.toggleKnowledge(item) }, onPreview = { previewId = item.id })
            }
        }
    }

    allItems.firstOrNull { it.id == previewId }?.let { item ->
        PreviewSheet(item, state.isSelected(item), health = item.theme == KnowledgeTheme.HEALTH, onToggle = { vm.toggleKnowledge(item) }, onDismiss = { previewId = null })
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
            // Tonal chips for all three (no outline on the unselected ones): one visual style in the row.
            FilterChip(
                selected = selected,
                onClick = { onPick(p) },
                label = { Text(t(key), style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)) },
                leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = null,
            )
        }
    }
}

@Composable
private fun itemName(item: KnowledgeItem) = item.nameKey?.let { t(it) } ?: item.fallbackName

@Composable
private fun itemDescription(item: KnowledgeItem) = item.descriptionKey?.let { t(it) } ?: item.catalogDescription

@Composable
private fun KnowledgeCard(item: KnowledgeItem, selected: Boolean, sectionCaveat: Boolean, showStar: Boolean, onToggle: () -> Unit, onPreview: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // A library already on the phone stays whatever is picked (setup never deletes): no checkbox, a check, and the card opens its preview.
    val on = selected || item.installed
    val selection = if (item.installed) Modifier.clickable(onClick = onPreview) else Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })
    SelectCard(on, selection, bottom = 6.dp) {
        if (item.installed) Box(Modifier.padding(12.dp).size(24.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CheckCircle, contentDescription = t("clean.onboarding.onPhone"), tint = cs.primary)
        } else Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val muted = LocalContentColor.current.copy(alpha = 0.78f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(itemName(item), style = MaterialTheme.typography.titleMedium, color = LocalContentColor.current, modifier = Modifier.weight(1f))
                // Recommended as a small star, and only when the Recommended preset isn't the one active (then it says it already).
                if (item.recommended && showStar) Icon(Icons.Filled.Star, contentDescription = t("clean.onboarding.tag.recommended"), Modifier.size(18.dp), tint = cs.primary)
            }
            Text(itemDescription(item), style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.clinicalCaveat && !sectionCaveat) Caveat()
            // Facts in one quiet line (articles, then size or "on this phone"), Preview at its end.
            val facts = listOfNotNull(
                item.preview?.takeIf { it.articles > 0 }?.let { t("clean.onboarding.articles", "count" to it.articles, "value" to countText(it.articles)) },
                if (item.installed) t("clean.onboarding.onPhone") else sizeText(item.sizeBytes),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onPreview, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(40.dp)) {
                    Text(t("clean.onboarding.preview"))
                }
            }
        }
    }
}

@Composable
private fun CaveatNote() {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHighest, contentColor = cs.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(20.dp))
            Text(t("clean.onboarding.caveat"), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Caveat() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        Text(t("clean.onboarding.caveat"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

/** The catalog text without the caveat sentence when the sheet already shows the caveat once. */
private fun withoutCaveat(text: String, caveat: String): String =
    text.split(Regex("(?<=[.!?])\\s+")).filterNot { it.contains("clinically reviewed", ignoreCase = true) || it.trim() == caveat.trim() }
        .joinToString(" ").trim()

/** What a library holds: articles, sources with counts, license, the catalog text and a few of its titles. */
@Composable
private fun PreviewSheet(item: KnowledgeItem, selected: Boolean, health: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val preview = item.preview
    val dark = cs.surface.luminance() < 0.5f
    // In dark the default scrim barely dims the page: a deeper scrim and a container a step up keep the sheet's edge.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = if (dark) cs.surfaceContainer else cs.surfaceContainerLow,
        scrimColor = cs.scrim.copy(alpha = if (dark) 0.6f else 0.32f)) {
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
            if (item.clinicalCaveat || health) {
                // Calm, not alarming: a high tonal surface with the note in onSurfaceVariant.
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHighest, contentColor = cs.onSurfaceVariant) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp))
                        Text(t("clean.onboarding.caveat"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val caveat = t("clean.onboarding.caveat")
            val description = if (item.clinicalCaveat) withoutCaveat(item.catalogDescription, caveat) else item.catalogDescription
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)

            // One source that is the library itself says nothing new: the list shows only for mixed libraries.
            if (preview != null && preview.sources.size > 1) {
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
                    preview.samples.take(6).forEach { title ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(BoarIcons.Description, null, Modifier.size(18.dp).padding(top = 2.dp), tint = cs.primary)
                            Text(title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                        }
                    }
                }
            }

            SheetLabel(t("clean.onboarding.sheet.license"))
            Text(item.license, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)

            // On the phone already: nothing to add or remove here, say where it's managed. Selected: a quiet outlined way
            // out; not selected: the one filled action of the sheet.
            if (item.installed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = cs.primary)
                Text(t("design.onboarding.installedNote"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            else if (selected) OutlinedButton(onToggle, Modifier.fillMaxWidth().height(52.dp)) { Text(t("clean.onboarding.sheet.remove")) }
            else Button(onToggle, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(t("clean.onboarding.sheet.add"))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetLabel(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
