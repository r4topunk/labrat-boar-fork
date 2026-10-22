package team.sopa.boar.ui.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.sopa.boar.documents.DocumentsState
import team.sopa.boar.documents.DocumentsViewModel
import team.sopa.boar.engine.rag.KnowledgeBase
import team.sopa.boar.ui.components.BoarTopBar
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/**
 * Knowledge (KnowledgeScreen.tsx): what BOAR knows offline first (the built-in packs, in [packs]), then the user's own
 * documents (PersonalDocumentsManager.tsx), imported from this screen and kept on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(state: DocumentsState, vm: DocumentsViewModel, packs: @Composable () -> Unit = {}, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<KnowledgeBase.Collection?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val fallbackName = t("clean.library.defaultName")
    // A collection with no name takes the first file's name: no blocking "Name required" dialog before the picker.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.import(uris, name.trim().ifEmpty { firstFileName(ctx, uris) ?: fallbackName })
            name = ""
        }
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // The bar takes the top inset; the bottom one is a spacer at the end of the scroll, so the list draws behind the navigation bar.
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = { BoarTopBar(title = t("nav.knowledge"), onBack = onBack, scrollBehavior = scroll) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // The packs are what answers use, so they come first (KnowledgePacks draws its own heading).
            packs()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t("clean.library.yourDocs"), style = MaterialTheme.typography.titleMedium)
                    Text(t("clean.library.docsHint"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                val busy = state.stage != null
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(t("clean.library.collectionName")) },
                    supportingText = { Text(t("clean.library.collectionHelp")) },
                )
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "text/comma-separated-values", "application/json", "application/pdf", "text/*")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(BoarIcons.UploadFile, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(t("clean.library.addDocs"))
                }
                state.stage?.let { stage ->
                    val label = when (stage) {
                        "reading" -> t("personalDocumentsManager.stageReading")
                        "chunking" -> t("personalDocumentsManager.stageChunking")
                        else -> t("personalDocumentsManager.stageEmbedding", "current" to state.done, "total" to state.total)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
                }
                // The scanned-PDF and password caveats belong where an import went wrong, not above every import.
                state.error?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${t("personalDocumentsManager.importFailedTitle")}: $it", style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                        Text(t("clean.library.pdfNote"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                if (state.collections.isEmpty()) EmptyDocuments()
                for (col in state.collections) OutlinedCard(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainerLow),
                    border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(col.name, style = MaterialTheme.typography.titleMedium)
                                Text(t("personalDocumentsManager.docCount", "count" to col.docCount) + " · " + t("personalDocumentsManager.chunkCount", "count" to col.chunkCount),
                                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                col.sourceFilename?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            Switch(col.active, { vm.setActive(col.id, it) }, Modifier.semantics { contentDescription = col.name })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    exporting = col.id
                                    scope.launch {
                                        try { ctx.startActivity(vm.exportIntent(col)) } catch (e: Exception) { exportError = e.message ?: e.toString() }
                                        exporting = null
                                    }
                                },
                                enabled = exporting == null,
                            ) { Text(if (exporting == col.id) t("personalDocumentsManager.exporting") else t("personalDocumentsManager.exportButton")) }
                            TextButton({ removing = col }, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) { Text(t("common.remove")) }
                        }
                    }
                }
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

/** The one empty state: an icon, what it's for and what to do (the button is right above it). */
@Composable
private fun EmptyDocuments() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(BoarIcons.Description, contentDescription = null, Modifier.size(40.dp), tint = scheme.onSurfaceVariant)
        Text(t("clean.library.emptyTitle"), style = MaterialTheme.typography.titleSmall)
        Text(t("clean.library.emptyBody"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The first picked file's name without its extension, for a collection the user left unnamed. */
private fun firstFileName(ctx: Context, uris: List<Uri>): String? {
    val uri = uris.firstOrNull() ?: return null
    val raw = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
    return raw?.substringBeforeLast('.', raw)?.trim()?.takeIf { it.isNotEmpty() }
}
