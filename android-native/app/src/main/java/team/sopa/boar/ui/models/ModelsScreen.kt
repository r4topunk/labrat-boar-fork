package team.sopa.boar.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import team.sopa.boar.app.DevOptions
import team.sopa.boar.engine.models.AnswerTier
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.Compatibility
import team.sopa.boar.engine.models.ModelOrder
import team.sopa.boar.engine.models.SetupModel
import team.sopa.boar.models.ModelsState
import team.sopa.boar.models.ModelsViewModel
import team.sopa.boar.models.Row as AssetRow
import team.sopa.boar.ui.components.BoarTopBar
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The models catalog (ModelCatalogScreen.tsx's content in Material 3 clothes): BoarTopBar like the other secondary
 * screens, one filter row, the sort in the bar's menu, and one outlined card per model. Knowledge packs live on the
 * Knowledge screen (KnowledgePacks below); the embedding model is BOAR's own search engine, not a choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(state: ModelsState, vm: ModelsViewModel, onBack: () -> Unit) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                BoarTopBar(
                    title = t("modelCatalogScreen.title"), onBack = onBack, scrollBehavior = scroll,
                    actions = { SortMenu(state.sort, vm::setSort) },
                )
            },
        ) { inner ->
            // The user's sort and filter (ModelOrder): Smart puts the active model first, then downloads, then the
            // phone's models by last use.
            val all = state.items.filter { it.kind != AssetKind.CORPUS && it.kind != AssetKind.EMBEDDING }
            val facts = ModelOrder.Facts(
                active = state.activeLlm, onPhone = state.rows.filterValues { it.present }.keys,
                downloading = state.rows.filterValues { it.downloading }.keys, mine = state.mine, lastUsed = state.lastUsed, added = state.added,
            )
            val models = ModelOrder.apply(all, state.sort, state.filter, facts)
            // The model setup recommends for this phone's memory, the same rule (SetupModel), so the badge and setup agree.
            val recommendedId = remember(state.items, state.totalRamBytes) {
                SetupModel.recommended(
                    state.items.filter { it.kind == AssetKind.LLM && it.answerTier != null }.sortedBy { if (it.answerTier == AnswerTier.DEFAULT) 0 else 1 },
                    state.totalRamBytes,
                )?.id
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    // Two filters are always there; "Downloading" and "Added by you" only when they'd show something
                    // (or when one is the saved choice, so the active filter is never invisible).
                    val filters = buildList<Pair<ModelOrder.Filter, String>> {
                        add(ModelOrder.Filter.ALL to t("native.models.filterAll"))
                        add(ModelOrder.Filter.ON_PHONE to t("native.models.filterOnPhone"))
                        if (state.filter == ModelOrder.Filter.DOWNLOADING || state.rows.values.any { it.downloading })
                            add(ModelOrder.Filter.DOWNLOADING to t("native.models.filterDownloading"))
                        if (state.filter == ModelOrder.Filter.MINE || state.mine.isNotEmpty())
                            add(ModelOrder.Filter.MINE to t("native.models.filterMine"))
                    }
                    ChipRow(null, filters, state.filter, vm::setFilter)
                }
                if (models.isEmpty()) item {
                    Text(t("native.models.emptyFilter"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(models, key = { it.id }) { a ->
                    val active = if (a.kind == AssetKind.LLM) a.id == state.activeLlm else state.rows[a.id]?.present == true
                    CatalogCard(a, state.rows[a.id], active, a.id == recommendedId, state.totalRamBytes, state.activating == a.id, state.activating != null, vm)
                }
            }
        }
        state.toast?.let { team.sopa.boar.ui.chat.Toast(it, vm::toastShown) }
    }
}

/** Sort, in the top bar's overflow: a menu with the current choice checked (Smart, Last used, Recently added). */
@Composable
private fun SortMenu(sort: ModelOrder.Sort, onSort: (ModelOrder.Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(
        ModelOrder.Sort.SMART to t("native.models.sortSmart"), ModelOrder.Sort.LAST_USED to t("native.models.sortLastUsed"),
        ModelOrder.Sort.ADDED to t("native.models.sortAdded"),
    )
    Box {
        IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = t("native.models.sort")) }
        DropdownMenu(open, { open = false }) {
            Text(t("native.models.sort"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            for ((value, label) in options) DropdownMenuItem(
                text = { Text(label) },
                onClick = { open = false; onSort(value) },
                leadingIcon = { if (value == sort) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
            )
        }
    }
}

/** The knowledge packs (KnowledgeScreen.tsx "Topic packs"): download, verify and remove, first thing on the Knowledge screen. */
@Composable
fun KnowledgePacks(state: ModelsState, vm: ModelsViewModel) {
    val packs = state.items.filter { it.kind == AssetKind.CORPUS }
    if (packs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t("clean.library.builtInTitle"), style = MaterialTheme.typography.titleMedium)
            Text(t("clean.library.builtInSummary"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (a in packs) CatalogCard(a, state.rows[a.id], false, false, state.totalRamBytes, state.activating == a.id, state.activating != null, vm)
    }
}

internal fun formatMB(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "%.0f MB".format(bytes / (1024.0 * 1024))
}

private fun formatSpeed(b: Double?, calculating: String) = when {
    b == null || b <= 0 -> calculating
    b >= 1024 * 1024 -> "%.1f MB/s".format(b / (1024 * 1024))
    else -> "%.0f KB/s".format(b / 1024)
}

private fun formatEta(s: Double?, calculating: String) = when {
    s == null || s <= 0 || s.isInfinite() || s.isNaN() -> calculating
    s < 60 -> "${ceil(s).toInt()}s"
    else -> "${floor(s / 60).toInt()}m ${ceil(s % 60).toInt()}s"
}

/**
 * One model or pack as an outlined card: the active model has a 2 dp primary outline (not a tint, which the compat
 * chip shares), the plain name from the catalog (shortName), and size only. Kind, license and the technical label
 * show with developer options on. Removing is in the card's overflow menu, so the footer carries only the action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCard(item: CatalogAsset, row: AssetRow?, active: Boolean, recommended: Boolean, ramBytes: Long, activating: Boolean, busy: Boolean, vm: ModelsViewModel) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val dev = DevOptions.enabled(LocalContext.current)
    val present = row?.present == true
    val downloading = row?.downloading == true
    val corpus = item.kind == AssetKind.CORPUS
    val highlighted = active && !corpus && present
    var confirm by remember { mutableStateOf(false) }
    var confirmUnlist by remember { mutableStateOf(false) }
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) scheme.primary else scheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.shortName, style = type.titleMedium)
                    val meta = buildList<String> {
                        add(formatMB(item.sizeBytes))
                        if (dev) {
                            add(t("catalogItemCard.kind.${item.kind.name.lowercase()}")); add(item.license)
                            if (item.required) add(t("catalogItemCard.default"))
                        }
                    }.joinToString(" · ")
                    Text(meta, style = type.labelMedium, color = scheme.onSurfaceVariant)
                    if (dev) Text(item.label, style = type.labelSmall, color = scheme.onSurfaceVariant)
                }
                StatusBadge(present, highlighted, downloading, corpus)
                // Remove lives in the overflow menu: a downloaded file (not a required one), or a listed one never downloaded.
                if (present && !item.required) CardMenu(enabled = !busy) { confirm = true }
                else if (!present && vm.isDiscovered(item)) CardMenu(enabled = true) { confirmUnlist = true }
            }
            val comp = if (item.kind == AssetKind.LLM && ramBytes > 0) Compatibility.compute(item.sizeBytes, ramBytes) else Compatibility.UNKNOWN
            if (recommended || comp != Compatibility.UNKNOWN) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recommended) Pill(t("flows.onboarding.recommended"), scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Filled.Check)
                // The chip's role carries the meaning (the label says it too): fits = secondary, tight = tertiary, too big = error.
                when (comp) {
                    Compatibility.GREEN -> Pill(t("catalogItemCard.compat.green"), scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Filled.CheckCircle)
                    Compatibility.YELLOW -> Pill(t("catalogItemCard.compat.yellow"), scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Outlined.Warning)
                    Compatibility.RED -> Pill(t("catalogItemCard.compat.red"), scheme.errorContainer, scheme.onErrorContainer, BoarIcons.ErrorOutline)
                    Compatibility.UNKNOWN -> {}
                }
            }
            // Models added before the rename keep the old name in their saved description.
            Text(item.description.replace("not vetted by aoair", "not vetted by BOAR"), style = type.bodyMedium, color = scheme.onSurfaceVariant)
            row?.error?.let { e ->
                Surface(shape = MaterialTheme.shapes.small, color = scheme.errorContainer, contentColor = scheme.onErrorContainer) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(BoarIcons.ErrorOutline, contentDescription = null, Modifier.size(18.dp))
                        Text(e, style = type.bodySmall)
                    }
                }
            }
            val calculating = t("catalogItemCard.calculating")
            if (downloading) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("catalogItemCard.downloadingAsset"), style = type.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text("%.0f%%".format(row!!.progress * 100), style = type.labelLarge)
                }
                // The stock indicator on the scheme's own track.
                LinearProgressIndicator(progress = { maxOf(row.progress, 0.02).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (row.bytesWritten > 0) formatMB(row.bytesWritten) else "0 MB"} / ${formatMB(item.sizeBytes)}", style = type.labelMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(formatSpeed(row.speedBytesPerSec, calculating) + " · " + t("catalogItemCard.eta", "eta" to formatEta(row.etaSeconds, calculating)),
                        style = type.labelMedium, color = scheme.onSurfaceVariant)
                }
                TextButton({ vm.cancelDownload(item) }, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) {
                    Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(t("native.models.cancelDownload"))
                }
            } else when {
                !present -> SmallButton(if (row?.error != null) t("catalogItemCard.retryDownload") else t("catalogItemCard.downloadAsset")) { vm.download(item) }
                row?.indexing == true -> Busy(t("setupWizard.step3.buildingIndex"))
                corpus -> Check(t("catalogItemCard.indexedNote"))
                // The active model says so in its badge and outline; nothing more to offer here.
                active -> {}
                activating -> Busy(t("catalogItemCard.loadingModel"))
                else -> SmallButton(t("catalogItemCard.selectUse"), enabled = !busy) { vm.use(item) }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(t(if (corpus) "catalogItemCard.removeCorpusTitle" else "catalogItemCard.removeModelTitle")) },
        text = { Text(t(if (corpus) "catalogItemCard.removeCorpusMessage" else "catalogItemCard.removeModelMessage", "size" to formatMB(item.sizeBytes))) },
        confirmButton = { DestructiveTextButton({ confirm = false; vm.remove(item) }) { Text(t("catalogItemCard.removeAssetButton")) } },
        dismissButton = { TextButton({ confirm = false }) { Text(t("common.cancel")) } },
    )
    if (confirmUnlist) AlertDialog(
        onDismissRequest = { confirmUnlist = false },
        title = { Text(t("catalogItemCard.removeFromListTitle")) },
        text = { Text(t("catalogItemCard.removeFromListMessage")) },
        confirmButton = { DestructiveTextButton({ confirmUnlist = false; vm.removeFromList(item) }) { Text(t("catalogItemCard.removeAssetButton")) } },
        dismissButton = { TextButton({ confirmUnlist = false }) { Text(t("common.cancel")) } },
    )
}

/** Remove, behind the card's overflow button: a named 48 dp target instead of a trash can in the footer. */
@Composable
private fun CardMenu(enabled: Boolean, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, contentDescription = t("clean.library.more")) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(t("common.remove")) }, onClick = { open = false; onRemove() })
        }
    }
}

/** A dialog's confirm for a destructive action: the error role on the text button, the dialog itself keeps the M3 defaults. */
@Composable
private fun DestructiveTextButton(onClick: () -> Unit, label: @Composable () -> Unit) =
    TextButton(onClick, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { label() }

/**
 * "Discover GGUF models · Browse Hugging Face models", in setup's model step under More options. Not a model, so it
 * doesn't look like one: a dashed tertiary outline and a tint, like an open slot.
 */
@Composable
fun DiscoverGgufCard(onBrowse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.tertiary
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(scheme.tertiary.copy(alpha = 0.12f))
            .drawBehind {
                drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
            }
            .clickable(role = Role.Button, onClick = onBrowse).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t("native.browse.discoverTitle"), style = MaterialTheme.typography.titleSmall, color = scheme.tertiary)
            Text(t("native.browse.discoverSubtitle"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.tertiary)
    }
}

/** A small non-interactive label: container and content from one role pair, an optional 16 dp icon. */
@Composable
private fun Pill(label: String, container: Color, content: Color, icon: ImageVector? = null, border: BorderStroke? = null) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content, border = border) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Where the file is, in sentence case: Downloading, Not downloaded, In use (the active model), Downloaded. A pack that is
 * ready has no badge: its footer says "Ready to search offline", once.
 */
@Composable
private fun StatusBadge(present: Boolean, active: Boolean, downloading: Boolean, corpus: Boolean) {
    val scheme = MaterialTheme.colorScheme
    when {
        downloading -> Pill(t("catalogItemCard.badge.downloading"), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        !present -> Pill(t("catalogItemCard.badge.notOnDisk"), Color.Transparent, scheme.onSurfaceVariant, border = BorderStroke(1.dp, scheme.outlineVariant))
        corpus -> {}
        active -> Pill(t("catalogItemCard.badge.active"), scheme.primaryContainer, scheme.onPrimaryContainer)
        else -> Pill(t("catalogItemCard.badge.cached"), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
}

@Composable
internal fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    // A real tonal button (secondaryContainer, labelLarge): its text follows Settings → Text size with the theme roles.
    FilledTonalButton(onClick, enabled = enabled) { Text(label) }
}

@Composable
internal fun Check(label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Busy(label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
