package team.sopa.boar.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import team.sopa.boar.engine.models.HfQuery
import team.sopa.boar.models.HfBrowser
import team.sopa.boar.models.ModelsState
import team.sopa.boar.models.ModelsViewModel
import team.sopa.boar.ui.components.BoarTopBar
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/**
 * Hugging Face models on a page of their own, opened from the Models screen: a search with size and sort filters,
 * results that page in as the list ends, and a repo's whole GGUF files to add. Only text-generation GGUF repos BOAR
 * can run are listed (HfQuery). The network is used only while this page is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: ModelsState,
    vm: ModelsViewModel,
    /** Download and Use on each added file; off in setup, which picks the added model itself. */
    rowActions: Boolean = true,
    onBack: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf(state.hfQuery) }
    // Opening the page lists what's trending in the default bucket, without typing anything. A size picked on an earlier
    // visit goes back to Any: it hid a model searched by name (Mellum under 1–3B, Oct 18).
    LaunchedEffect(Unit) { if (!state.hfSearched || state.hfSize != HfQuery.Size.ANY) vm.hfBrowse(query, size = HfQuery.Size.ANY) }
    fun search() { keyboard?.hide(); vm.hfBrowse(query.trim()) }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd, state.hfNext, state.hfSearching) { if (nearEnd && state.hfNext != null && !state.hfSearching) vm.hfMore() }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val searchLabel = t("flows.onboarding.category.search")
    Box(Modifier.fillMaxSize()) {
        // safeDrawing includes the keyboard, so the list shrinks above it as the search field is typed in.
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { BoarTopBar(title = t("native.browse.discoverTitle"), onBack = onBack, scrollBehavior = scroll) },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                // On the app bar's own surface, so the bar and the filters read as one header.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The stock outlined field: a visible focus ring, and the search button inside it as a named icon button.
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = { Text(t("modelBrowser.searchPlaceholder")) },
                        trailingIcon = {
                            IconButton(::search, enabled = !state.hfSearching) { Icon(Icons.Outlined.Search, contentDescription = searchLabel) }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                        shape = MaterialTheme.shapes.large)
                    ChipRow(t("native.browse.size"), HfQuery.Size.entries.map { it to sizeLabel(it) }, state.hfSize) { vm.hfBrowse(query.trim(), size = it) }
                    ChipRow(t("native.browse.sort"), listOf(
                        HfQuery.Sort.TRENDING to t("native.browse.sortTrending"), HfQuery.Sort.DOWNLOADS to t("native.browse.sortDownloads"),
                        HfQuery.Sort.LIKES to t("native.browse.sortLikes"), HfQuery.Sort.NEWEST to t("native.browse.sortNewest"),
                    ), state.hfSort) { vm.hfBrowse(query.trim(), sort = it) }
                }
                HorizontalDivider()
                LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t("native.browse.ctaNote"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            state.hfError?.let { Text(t(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (!state.hfSearching && state.hfError == null && state.hfSearched && state.hfResults.isEmpty() && state.hfNext == null)
                                Text(t("native.browse.empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(state.hfResults, key = { it.id }) { r -> RepoCard(r, state, vm, rowActions) }
                    if (state.hfSearching) item { Busy(if (state.hfResults.isEmpty()) "…" else t("native.browse.more")) }
                }
            }
        }
        if (rowActions) state.toast?.let { team.sopa.boar.ui.chat.Toast(it, vm::toastShown) }
    }
}

private fun sizeLabel(s: HfQuery.Size): String = when (s) {
    HfQuery.Size.ANY -> "≤20B"
    HfQuery.Size.UNDER_1B -> "<1B"
    HfQuery.Size.B1_3 -> "1–3B"
    HfQuery.Size.B3_6 -> "3–6B"
    HfQuery.Size.B6_9 -> "6–9B"
    HfQuery.Size.B9_20 -> "9–20B"
}

/** A row of stock filter chips, the chosen one checked; [label] leads the row when given. */
@Composable
internal fun <T> ChipRow(label: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((value, text) in options) {
            val on = value == selected
            // Material's own colors; the check says "selected" for color-blind users, TalkBack gets the selected state.
            FilterChip(
                selected = on, onClick = { onSelect(value) },
                label = { Text(text) },
                leadingIcon = if (on) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            )
        }
    }
}

@Composable
private fun RepoCard(r: HfBrowser.Repo, state: ModelsState, vm: ModelsViewModel, rowActions: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val open = state.hfOpenRepo == r.id && !r.gated
    val stateText = t(if (open) "clean.library.expanded" else "clean.library.collapsed")
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainerLow), border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Only the header opens and closes the repo (with a chevron that says so); tapping a file below doesn't collapse it.
            // A gated repo can't be downloaded without an account: it says so and doesn't open.
            Row(Modifier.fillMaxWidth().clickable(enabled = !r.gated, role = Role.Button) { vm.hfOpen(r.id) }.semantics { if (!r.gated) stateDescription = stateText },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.id, style = type.titleSmall)
                    Text(listOfNotNull(r.params?.let(HfQuery::formatParams), r.architecture).joinToString(" · "), style = type.bodySmall, color = scheme.onSurfaceVariant)
                    if (r.gated) Text(t("native.browse.gated"), style = type.labelMedium, color = scheme.error)
                    Text(t("modelBrowser.downloadsLikes", "downloads" to (r.downloads ?: 0), "likes" to (r.likes ?: 0)) + " · " + t("native.browse.unvetted"),
                        style = type.labelMedium, color = scheme.onSurfaceVariant)
                }
                if (!r.gated) Icon(if (open) BoarIcons.ExpandLess else BoarIcons.ExpandMore, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
            if (open) {
                if (state.hfBusy) Busy("…")
                else if (state.hfFiles.isEmpty()) Text(t("modelBrowser.noGgufFiles"), style = type.bodySmall, color = scheme.onSurfaceVariant)
                val rec = HfQuery.recommended(state.hfFiles.map { it.filename })
                state.hfFiles.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(f.filename, style = type.bodySmall)
                            Text(listOfNotNull(formatMB(f.sizeBytes), if (f.filename == rec) t("flows.onboarding.recommended") else null,
                                if (f.sha256 == null) t("modelBrowser.noChecksum") else null).joinToString(" · "),
                                style = type.labelMedium, color = if (f.filename == rec) scheme.primary else scheme.onSurfaceVariant)
                        }
                        FileAction(r.id, f, state, vm, rowActions)
                    }
                }
            }
        }
    }
}

/**
 * What a file row offers, following the model in Models: Add, then Download (with its progress and a cancel), then
 * Use now, then Active. A file already in the list (added before, or in BOAR's own catalog) is never added twice.
 */
@Composable
private fun FileAction(repo: String, f: HfBrowser.GgufFile, state: ModelsState, vm: ModelsViewModel, rowActions: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val asset = remember(repo, f) { runCatching { HfBrowser.toAsset(repo, f) }.getOrNull() }
    val item = asset?.let { a -> state.items.firstOrNull { it.id == a.id || it.filename == a.filename } }
    val row = item?.let { state.rows[it.id] }
    if (!rowActions || item == null) { SmallButton(t("modelBrowser.addButton")) { vm.hfAdd(repo, f) }; return }
    when {
        row?.downloading == true -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Busy("%.0f%%".format(row.progress * 100))
            // A 48 dp icon button with a name, not a bare glyph.
            IconButton({ vm.cancelDownload(item) }) { Icon(Icons.Filled.Close, contentDescription = t("native.models.cancelDownload"), tint = scheme.error) }
        }
        row?.present != true -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallButton(t(if (row?.error != null) "catalogItemCard.retryDownload" else "catalogItemCard.downloadAsset")) { vm.download(item) }
            Text(row?.error ?: t("native.models.inYourModels"), style = MaterialTheme.typography.labelMedium, color = if (row?.error != null) scheme.error else scheme.onSurfaceVariant)
        }
        item.id == state.activeLlm -> Check(t("catalogItemCard.activeNote"))
        state.activating == item.id -> Busy(t("catalogItemCard.loadingModel"))
        else -> SmallButton(t("native.models.useNow"), enabled = state.activating == null) { vm.use(item) }
    }
}
