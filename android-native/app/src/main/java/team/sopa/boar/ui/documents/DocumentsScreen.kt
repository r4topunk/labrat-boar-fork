package team.sopa.boar.ui.documents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.sopa.boar.documents.DocumentsState
import team.sopa.boar.documents.DocumentsViewModel
import team.sopa.boar.engine.rag.KnowledgeBase
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar

/** My Documents (PersonalDocumentsManager.tsx). */
@Composable
fun DocumentsScreen(state: DocumentsState, vm: DocumentsViewModel, packs: @Composable () -> Unit = {}, onBack: () -> Unit) {
    val c = Boar.colors
    var name by remember { mutableStateOf("") }
    var nameMissing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<KnowledgeBase.Collection?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.import(uris, name.trim()); name = "" }
    // Top and sides are insets; the bottom one is a spacer at the end of the scroll, so the list draws behind the navigation bar.
    Scaffold(containerColor = c.bgTerminal, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)) { inner ->
    Column(Modifier.fillMaxSize().padding(inner)) {
        Row(Modifier.fillMaxWidth().background(c.bgSurface).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("‹ " + t("common.back"), style = Boar.type.body, color = c.textAccentEmerald, modifier = Modifier.clickable(onClick = onBack).padding(4.dp))
            Text(t("nav.knowledge"), style = Boar.type.titleLg, color = c.textHeading)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDefault))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("personalDocumentsManager.hint"), style = Boar.type.subtext, color = c.textMuted)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bgInput).border(1.dp, c.borderDefault, RoundedCornerShape(8.dp)).padding(12.dp)) {
                if (name.isEmpty()) Text(t("personalDocumentsManager.namePlaceholder"), style = Boar.type.body, color = c.textDim)
                BasicTextField(name, { name = it }, textStyle = Boar.type.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.emerald500), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            val busy = state.stage != null
            Button(
                onClick = {
                    if (name.isBlank()) nameMissing = true
                    else picker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "text/comma-separated-values", "application/json", "application/pdf", "text/*"))
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(t("personalDocumentsManager.pickButton"), style = Boar.type.title) }
            state.stage?.let { stage ->
                val label = when (stage) {
                    "reading" -> t("personalDocumentsManager.stageReading")
                    "chunking" -> t("personalDocumentsManager.stageChunking")
                    else -> t("personalDocumentsManager.stageEmbedding", "current" to state.done, "total" to state.total)
                }
                Text(label, style = Boar.type["mono.xs"], color = c.textAccentCyan)
                LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth(), color = c.emerald500, trackColor = c.bgSubtle)
            }
            state.error?.let { Text("${t("personalDocumentsManager.importFailedTitle")}: $it", style = Boar.type.subtext, color = c.crimson400) }
            if (state.collections.isEmpty()) Text(t("personalDocumentsManager.empty"), style = Boar.type.body, color = c.textMuted)
            for (col in state.collections) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bgCardElevated).border(1.dp, if (col.active) c.emeraldBorder else c.borderDefault, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(col.name, style = Boar.type.titleSm, color = c.textHeading)
                        Text(t("personalDocumentsManager.docCount", "count" to col.docCount) + " · " + t("personalDocumentsManager.chunkCount", "count" to col.chunkCount),
                            style = Boar.type["mono.xs"], color = c.textDim)
                        col.sourceFilename?.let { Text(it, style = Boar.type.micro, color = c.textDim, maxLines = 1) }
                    }
                    Switch(col.active, { vm.setActive(col.id, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.emerald500, checkedThumbColor = c.textInverse))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = {
                            exporting = col.id
                            scope.launch {
                                try { ctx.startActivity(vm.exportIntent(col)) } catch (e: Exception) { exportError = e.message ?: e.toString() }
                                exporting = null
                            }
                        },
                        enabled = exporting == null,
                    ) { Text(if (exporting == col.id) t("personalDocumentsManager.exporting") else t("personalDocumentsManager.exportButton"), style = Boar.type.caption) }
                    TextButton({ removing = col }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(t("common.remove"), style = Boar.type.caption, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            // Your documents first, then the knowledge packs (KnowledgeScreen.tsx's order).
            packs()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
    }
    exportError?.let { msg -> AlertDialog({ exportError = null }, confirmButton = { TextButton({ exportError = null }) { Text(t("native.common.ok")) } },
        title = { Text(t("personalDocumentsManager.exportFailedTitle")) }, text = { Text(msg) }) }
    if (nameMissing) AlertDialog({ nameMissing = false }, confirmButton = { TextButton({ nameMissing = false }) { Text(t("native.common.ok")) } },
        title = { Text(t("personalDocumentsManager.nameRequiredTitle")) }, text = { Text(t("personalDocumentsManager.nameRequiredMessage")) },
        containerColor = c.bgCard, titleContentColor = c.textHeading, textContentColor = c.textSecondary)
    removing?.let { col ->
        AlertDialog({ removing = null },
            confirmButton = { TextButton({ vm.remove(col.id); removing = null }) { Text(t("common.remove"), color = c.crimson400) } },
            dismissButton = { TextButton({ removing = null }) { Text(t("common.cancel")) } },
            title = { Text(t("personalDocumentsManager.removeConfirmTitle")) },
            text = { Text(t("personalDocumentsManager.removeConfirmMessage", "name" to col.name, "count" to col.chunkCount)) },
            containerColor = c.bgCard, titleContentColor = c.textHeading, textContentColor = c.textSecondary)
    }
}
