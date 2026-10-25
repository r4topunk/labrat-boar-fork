package team.sopa.boar.ui.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.sopa.boar.documents.DocumentsState
import team.sopa.boar.documents.DocumentsViewModel
import team.sopa.boar.engine.rag.KnowledgeBase
import team.sopa.boar.models.PacksState
import team.sopa.boar.models.PacksViewModel
import team.sopa.boar.setup.KnowledgeCatalog
import team.sopa.boar.setup.KnowledgeItem
import team.sopa.boar.setup.KnowledgeTheme
import team.sopa.boar.ui.chat.groupedShape
import team.sopa.boar.ui.components.BoarTopBar
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.setup.TourIcons
import team.sopa.boar.ui.setup.countText
import team.sopa.boar.ui.setup.sizeText
import team.sopa.boar.ui.t

/**
 * Knowledge: what BOAR searches offline, by theme, as the setup step names and counts it (one
 * catalog: KnowledgeCatalog), then the user's own documents. Grouped tonal lists, no outlines; a row says its status only
 * when it isn't ready, and removing or exporting sits behind the row's ⋮ menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(state: DocumentsState, vm: DocumentsViewModel, packs: @Composable () -> Unit = {}, onAskFiles: () -> Unit = {}, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var removing by remember { mutableStateOf<KnowledgeBase.Collection?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val fallbackName = t("clean.library.defaultName")
    // A collection takes the first file's name: no name field and no blocking "Name required" dialog before the picker.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris, firstFileName(ctx, uris) ?: fallbackName)
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = { BoarTopBar(title = t("nav.knowledge"), onBack = onBack, scrollBehavior = scroll) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            packs()
            SectionHeader(t("clean.library.yourDocs"))
            Text(t("clean.library.docsHint"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            val busy = state.stage != null
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.collections.forEachIndexed { i, col ->
                    ListItem(
                        headlineContent = { Text(col.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(t("personalDocumentsManager.docCount", "count" to col.docCount)) },
                        leadingContent = { Icon(BoarIcons.Description, null) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(col.active, { vm.setActive(col.id, it) }, Modifier.semantics { contentDescription = col.name })
                                RowMenu(listOf(
                                    t("personalDocumentsManager.exportButton") to {
                                        scope.launch {
                                            try { ctx.startActivity(vm.exportIntent(col)) } catch (e: Exception) { exportError = e.message ?: e.toString() }
                                        }; Unit
                                    },
                                    t("common.remove") to { removing = col },
                                ))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainer, leadingIconColor = scheme.onSurfaceVariant),
                        modifier = Modifier.clip(groupedShape(i, state.collections.size, outer = 20.dp)),
                    )
                }
            }
            if (state.collections.isEmpty() && !busy) EmptyDocuments()
            state.stage?.let { stage ->
                val label = when (stage) {
                    "reading" -> t("personalDocumentsManager.stageReading")
                    "chunking" -> t("personalDocumentsManager.stageChunking")
                    else -> t("personalDocumentsManager.stageEmbedding", "current" to state.done, "total" to state.total)
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
                }
            }
            // The scanned-PDF and password caveats belong where an import went wrong, not above every import.
            state.error?.let {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${t("personalDocumentsManager.importFailedTitle")}: $it", style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                    Text(t("clean.library.pdfNote"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            // After an import: All sources can rank the library above files just added, so offer a chat that answers
            // only from the user's files (Sources = My files).
            if (!busy) state.imported?.let { col -> ImportedInvite(col) { vm.dismissImported(); onAskFiles() } }
            FilledTonalButton(
                onClick = { picker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "text/comma-separated-values", "application/json", "application/pdf", "text/*")) },
                enabled = !busy, modifier = Modifier.padding(top = 12.dp).height(48.dp),
            ) {
                Icon(BoarIcons.UploadFile, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(t("clean.library.addDocs"))
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
    exportError?.let { msg -> AlertDialog({ exportError = null }, confirmButton = { TextButton({ exportError = null }) { Text(t("native.common.ok")) } },
        title = { Text(t("personalDocumentsManager.exportFailedTitle")) }, text = { Text(msg) }) }
    removing?.let { col ->
        AlertDialog({ removing = null },
            confirmButton = { TextButton({ vm.remove(col.id); removing = null }, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) { Text(t("common.remove")) } },
            dismissButton = { TextButton({ removing = null }) { Text(t("common.cancel")) } },
            title = { Text(t("personalDocumentsManager.removeConfirmTitle")) },
            text = { Text(t("personalDocumentsManager.removeConfirmMessage", "name" to col.name, "count" to col.chunkCount)) })
    }
}

/** The import's result: what was added, and a chat that answers only from the user's files. */
@Composable
private fun ImportedInvite(collection: String, onAsk: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("design.documents.imported", "name" to collection), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onAsk, colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.surface, contentColor = scheme.primary),
                modifier = Modifier.height(48.dp)) {
                Icon(BoarIcons.Description, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(t("design.documents.askFiles"))
            }
        }
    }
}

/**
 * The built-in libraries by theme, named and counted as in setup. A library on the phone is a plain row (name, then
 * articles · size) with a menu to remove it; one that isn't there shows only what it would hold.
 */
@Composable
fun KnowledgeLibrary(state: PacksState, vm: PacksViewModel) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val corpora = state.items
    if (corpora.isEmpty()) return
    val previews = remember {
        runCatching { ctx.assets.open("boar-data/pack-previews.json").bufferedReader().use { it.readText() } }
            .map(KnowledgeCatalog::parsePreviews).getOrDefault(emptyMap())
    }
    val sections = remember(corpora, state.rows) {
        KnowledgeCatalog.sections(corpora, previews) { state.rows[it.id]?.present == true }
    }
    var removing by remember { mutableStateOf<KnowledgeItem?>(null) }
    Text(t("design.knowledge.summary"), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    sections.forEach { section ->
        SectionHeader(t(section.titleKey))
        if (section.theme == KnowledgeTheme.HEALTH) HealthNote()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            section.items.forEachIndexed { i, item ->
                val assets = item.assetIds.mapNotNull { id -> corpora.firstOrNull { it.id == id } }
                val articles = item.preview?.articles?.takeIf { it > 0 }
                val facts = listOfNotNull(
                    articles?.let { t("clean.onboarding.articles", "count" to it, "value" to countText(it)) },
                    sizeText(item.sizeBytes),
                ).joinToString(" · ")
                ListItem(
                    // The theme's line icon in a tonal disc, as on the tour; Health in the error tone there too.
                    leadingContent = {
                        Box(Modifier.size(40.dp).clip(CircleShape)
                            .background(scheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                            Icon(TourIcons.forPack(item.id, item.theme), null, Modifier.size(20.dp),
                                tint = if (item.theme == KnowledgeTheme.HEALTH) scheme.error else scheme.primary)
                        }
                    },
                    headlineContent = { Text(item.nameKey?.let { t(it) } ?: item.fallbackName) },
                    supportingContent = { Text(facts, color = scheme.onSurfaceVariant) },
                    trailingContent = {
                        if (item.installed && assets.any { !it.required }) RowMenu(listOf(t("design.knowledge.remove") to { removing = item }))
                    },
                    colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainer),
                    modifier = Modifier.clip(groupedShape(i, section.items.size, outer = 20.dp)),
                )
            }
        }
    }
    removing?.let { item ->
        AlertDialog({ removing = null },
            title = { Text(t("catalogItemCard.removeCorpusTitle")) },
            text = { Text(t("catalogItemCard.removeCorpusMessage", "size" to sizeText(item.sizeBytes))) },
            confirmButton = { TextButton({
                corpora.filter { it.id in item.assetIds && !it.required }.forEach(vm::remove); removing = null
            }, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) { Text(t("catalogItemCard.removeAssetButton")) } },
            dismissButton = { TextButton({ removing = null }) { Text(t("common.cancel")) } })
    }
}

/** The clinical caveat once under the Health header: a calm tonal note, not a warning. */
@Composable
private fun HealthNote() {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        Text(t("clean.onboarding.caveat"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

/** A group title, as in Settings: labelLarge in primary on the rows' 16 dp inset. */
@Composable
private fun SectionHeader(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })

/** The row's ⋮ menu: a named 48 dp target. */
@Composable
private fun RowMenu(actions: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = t("clean.library.more"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(open, { open = false }) {
            actions.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}

/** The one empty state: an icon, what it's for and what to do (the button is right below it). */
@Composable
private fun EmptyDocuments() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainer).padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(BoarIcons.Description, contentDescription = null, Modifier.size(32.dp), tint = scheme.onSurfaceVariant)
        Text(t("clean.library.emptyTitle"), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        Text(t("clean.library.emptyBody"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The first picked file's name without its extension, for the new collection. */
private fun firstFileName(ctx: Context, uris: List<Uri>): String? {
    val uri = uris.firstOrNull() ?: return null
    val raw = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
    return raw?.substringBeforeLast('.', raw)?.trim()?.takeIf { it.isNotEmpty() }
}
