package team.sopa.boar.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.sopa.boar.engine.models.HfQuery
import team.sopa.boar.models.HfBrowser
import team.sopa.boar.models.ModelsState
import team.sopa.boar.models.ModelsViewModel
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar

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
    val c = Boar.colors
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
    Column(Modifier.fillMaxSize().background(c.bgTerminal).nestedScroll(scroll.nestedScrollConnection).navigationBarsPadding().imePadding()) {
        CatalogTopBar(onBack, scroll) { Text(t("native.browse.discoverTitle"), style = Boar.type.titleLg) }
        // On the app bar's own surface, so the bar and the filters read as one header.
        Column(Modifier.fillMaxWidth().background(c.bgTerminal).padding(horizontal = 12.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The stock outlined field: a visible focus ring, and the search button inside it as a named icon button.
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Boar.type.body,
                placeholder = { Text(t("modelBrowser.searchPlaceholder"), style = Boar.type.body) },
                trailingIcon = {
                    IconButton(::search, Modifier.semantics { contentDescription = searchLabel }, enabled = !state.hfSearching) {
                        Text("🔍", fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics {})
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                shape = MaterialTheme.shapes.large)
            ChipRow(t("native.browse.size"), HfQuery.Size.entries.map { it to sizeLabel(it) }, state.hfSize) { vm.hfBrowse(query.trim(), size = it) }
            ChipRow(t("native.browse.sort"), listOf(
                HfQuery.Sort.TRENDING to t("native.browse.sortTrending"), HfQuery.Sort.DOWNLOADS to t("native.browse.sortDownloads"),
                HfQuery.Sort.LIKES to t("native.browse.sortLikes"), HfQuery.Sort.NEWEST to t("native.browse.sortNewest"),
            ), state.hfSort) { vm.hfBrowse(query.trim(), sort = it) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDefault))
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t("native.browse.ctaNote"), style = Boar.type.subtext, color = c.textMuted)
                    state.hfError?.let { Text(t(it), style = Boar.type.subtext, color = c.crimson400) }
                    if (!state.hfSearching && state.hfError == null && state.hfSearched && state.hfResults.isEmpty() && state.hfNext == null)
                        Text(t("native.browse.empty"), style = Boar.type.body, color = c.textMuted)
                }
            }
            items(state.hfResults, key = { it.id }) { r -> RepoCard(r, state, vm, rowActions) }
            if (state.hfSearching) item { Busy(if (state.hfResults.isEmpty()) "…" else t("native.browse.more")) }
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

@Composable
internal fun <T> ChipRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = Boar.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Boar.type.caption, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
        for ((value, text) in options) {
            // The same stock chip, with the same colors, as Settings: selected state for TalkBack and a 48 dp target.
            FilterChip(
                selected = value == selected, onClick = { onSelect(value) },
                label = { Text(text, style = Boar.type.caption) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = c.textSecondary, selectedLabelColor = c.emerald50, selectedContainerColor = c.emerald900,
                ),
            )
        }
    }
}

@Composable
private fun RepoCard(r: HfBrowser.Repo, state: ModelsState, vm: ModelsViewModel, rowActions: Boolean) {
    val c = Boar.colors
    Column(Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Only the header opens and closes the repo; tapping a file below doesn't collapse it.
        Column(Modifier.fillMaxWidth().clickable { vm.hfOpen(r.id) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.id, style = Boar.type.body, color = c.textHeading, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(r.params?.let(HfQuery::formatParams), r.architecture).joinToString(" · "), style = Boar.type.subtext, color = c.textSecondary)
            Text(t("modelBrowser.downloadsLikes", "downloads" to (r.downloads ?: 0), "likes" to (r.likes ?: 0)) + " · " + t("native.browse.unvetted"),
                style = Boar.type.micro, color = c.textDim)
        }
        if (state.hfOpenRepo == r.id) {
            if (state.hfBusy) Busy("…")
            else if (state.hfFiles.isEmpty()) Text(t("modelBrowser.noGgufFiles"), style = Boar.type.subtext, color = c.textMuted)
            val rec = HfQuery.recommended(state.hfFiles.map { it.filename })
            state.hfFiles.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.filename, style = Boar.type.subtext, color = c.textPrimary)
                        Text(listOfNotNull(formatMB(f.sizeBytes), if (f.filename == rec) t("flows.onboarding.recommended") else null,
                            if (f.sha256 == null) t("modelBrowser.noChecksum") else null).joinToString(" · "),
                            style = Boar.type.micro, color = if (f.filename == rec) c.textAccentEmerald else c.textDim)
                    }
                    FileAction(r.id, f, state, vm, rowActions)
                }
            }
        }
    }
}

/**
 * What a file row offers, following the model in Models: Add, then Download (with its progress and a cancel), then
 * Use now, then ✓ Active. A file already in the list (added before, or in BOAR's own catalog) is never added twice.
 */
@Composable
private fun FileAction(repo: String, f: HfBrowser.GgufFile, state: ModelsState, vm: ModelsViewModel, rowActions: Boolean) {
    val c = Boar.colors
    val asset = remember(repo, f) { runCatching { HfBrowser.toAsset(repo, f) }.getOrNull() }
    val item = asset?.let { a -> state.items.firstOrNull { it.id == a.id || it.filename == a.filename } }
    val row = item?.let { state.rows[it.id] }
    if (!rowActions || item == null) { SmallButton(t("modelBrowser.addButton")) { vm.hfAdd(repo, f) }; return }
    when {
        row?.downloading == true -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Busy("%.0f%%".format(row.progress * 100))
            Text("✕", style = Boar.type.body, color = c.crimson400, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { vm.cancelDownload(item) }.padding(4.dp))
        }
        row?.present != true -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallButton(t(if (row?.error != null) "catalogItemCard.retryDownload" else "catalogItemCard.downloadAsset")) { vm.download(item) }
            Text(row?.error ?: t("native.models.inYourModels"), style = Boar.type.micro, color = if (row?.error != null) c.crimson400 else c.textDim)
        }
        item.id == state.activeLlm -> Check(t("catalogItemCard.activeNote"))
        state.activating == item.id -> Busy(t("catalogItemCard.loadingModel"))
        else -> SmallButton(t("native.models.useNow"), enabled = state.activating == null) { vm.use(item) }
    }
}
