package team.sopa.boar.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.setup.AssetProgress
import team.sopa.boar.setup.FileStatus
import team.sopa.boar.setup.IndexPhase
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/**
 * Step 5: everything being installed, in the open. An overall card (percent, bytes, speed, time left), then one row per
 * file with its own bar and status, grouped Models / Knowledge, and the library row, which starts as soon as the search
 * model is in. When everything is ready the rows stay checked for a moment and SetupScreen opens the chat.
 */
@Composable
internal fun InstallStep(state: SetupState, vm: SetupViewModel, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    // Nothing to download (every file already on the phone): the card follows the library instead.
    val target = when {
        state.downloads.isNotEmpty() -> state.overallFraction
        state.indexPhase == IndexPhase.READY -> 1f
        state.indexTotal > 0 -> (state.indexDone.toFloat() / state.indexTotal).coerceIn(0f, 1f)
        else -> 0f
    }
    val overall by animateFloatAsState(target, if (motion) tween(400) else snap(), label = "overall")
    // Back is offered only where leaving is safe: never while the library is being organized or once finished.
    val canBack = state.indexPhase != IndexPhase.BUILDING && !state.finished
    StepScaffold(progress = PROGRESS_KNOWLEDGE + (1f - PROGRESS_KNOWLEDGE) * overall, onBack = if (canBack) onBack else null) {
        item(key = "header") {
            if (state.finished) StepHeader(t("clean.onboarding.install.doneTitle"), t("clean.onboarding.install.doneSubtitle"))
            else StepHeader(t("clean.onboarding.install.title"), t("clean.onboarding.install.subtitle"))
        }
        item(key = "overall") { OverallCard(state, overall) }

        if (state.stalled) item(key = "stalled") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("flows.onboarding.restartHint"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton({ vm.restartDownloads() }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("flows.onboarding.restart"))
                    }
                }
            }
        }

        val models = state.modelDownloads
        if (models.isNotEmpty()) {
            item(key = "models-title") { SectionTitle(t("clean.onboarding.install.models")) }
            item(key = "models") { FileGroup(models, vm, onBack) }
        }
        item(key = "knowledge-title") { SectionTitle(t("clean.onboarding.install.knowledge")) }
        item(key = "knowledge") { FileGroup(state.knowledgeDownloads, vm, onBack, indexRow = { IndexRow(state, vm) }) }

        if (!state.finished) item(key = "note") {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Text(t("clean.onboarding.install.pauseNote"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OverallCard(state: SetupState, fraction: Float) {
    val cs = MaterialTheme.colorScheme
    val pct = (fraction * 100).toInt()
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cs.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$pct%", style = MaterialTheme.typography.displaySmall, color = cs.primary, modifier = Modifier.weight(1f))
                if (state.finished) Icon(Icons.Filled.CheckCircle, null, Modifier.size(32.dp), tint = cs.primary)
                else state.etaSeconds?.takeIf { !state.allPresent }?.let {
                    Text(t("flows.onboarding.minutesLeft", "count" to minutesLeft(it)), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                }
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = cs.primary,
                trackColor = cs.surfaceContainerHighest,
                drawStopIndicator = {},
            )
            val line = listOfNotNull(
                if (state.totalBytes > 0) t("clean.onboarding.install.bytes", "done" to sizeText(state.doneBytes), "total" to sizeText(state.totalBytes))
                else t("clean.onboarding.index.title"),
                state.speedBytesPerSec?.takeIf { !state.finished }?.let { t("clean.onboarding.install.speed", "speed" to sizeText(it)) },
            )
            Text(line.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            // What is moving right now, so the card says it even when that row is below the fold.
            val now = state.downloads.firstOrNull { it.status == FileStatus.DOWNLOADING || it.status == FileStatus.CHECKING }
            val nowText = when {
                state.finished -> null
                now != null -> t("design.install.now", "name" to (now.nameKey?.let { t(it) } ?: now.asset.shortName))
                state.indexPhase == IndexPhase.BUILDING -> t("design.install.organizing")
                else -> null
            }
            nowText?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** One tonal card per group (no outline, no hairlines): the rows' own spacing separates them; [indexRow] closes it. */
@Composable
private fun FileGroup(files: List<AssetProgress>, vm: SetupViewModel, onBack: () -> Unit, indexRow: (@Composable () -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 4.dp)) {
            // Moving and failed rows first, then waiting, then ready: what changes stays near the top.
            files.sortedBy { when (it.status) { FileStatus.FAILED, FileStatus.DOWNLOADING, FileStatus.CHECKING -> 0; FileStatus.QUEUED -> 1; FileStatus.READY -> 2 } }
                .forEach { f -> FileRow(f, vm, onBack) }
            indexRow?.invoke()
        }
    }
}

private fun kindIcon(f: AssetProgress): ImageVector = when (f.asset.kind) {
    AssetKind.LLM -> BoarIcons.Psychology
    AssetKind.EMBEDDING -> Icons.Outlined.Search
    else -> BoarIcons.MenuBook
}

@Composable
private fun FileRow(f: AssetProgress, vm: SetupViewModel, onBack: () -> Unit) {
    val name = f.nameKey?.let { t(it) } ?: f.asset.shortName
    val status = when (f.status) {
        FileStatus.QUEUED -> t("clean.onboarding.status.queued")
        FileStatus.DOWNLOADING -> t("clean.onboarding.status.downloading", "pct" to f.percent)
        FileStatus.CHECKING -> t("clean.onboarding.status.checking")
        FileStatus.READY -> t("clean.onboarding.status.ready")
        FileStatus.FAILED -> t("clean.onboarding.status.failed")
    }
    val meta = when (f.status) {
        FileStatus.READY -> sizeText(f.asset.sizeBytes)
        else -> if (f.bytesDone > 0) t("clean.onboarding.install.bytes", "done" to sizeText(f.bytesDone), "total" to sizeText(f.asset.sizeBytes))
            else sizeText(f.asset.sizeBytes)
    }
    ProgressRow(
        icon = kindIcon(f), name = name, status = status, meta = meta, fraction = f.fraction,
        indeterminate = f.status == FileStatus.CHECKING, ready = f.status == FileStatus.READY,
        failed = f.status == FileStatus.FAILED, active = f.status == FileStatus.DOWNLOADING,
        error = f.error,
    ) {
        TextButton({ vm.retry() }) { Text(t("flows.row.retry")) }
        if (f.storage) TextButton(onBack) { Text(t("clean.onboarding.install.lessKnowledge")) }
    }
}

/** "Organizing your library": its own row and bar; it starts as soon as the search model arrives. */
@Composable
private fun IndexRow(state: SetupState, vm: SetupViewModel) {
    val phase = state.indexPhase
    val building = phase == IndexPhase.BUILDING
    val fraction = when {
        phase == IndexPhase.READY -> 1f
        state.indexTotal > 0 -> (state.indexDone.toFloat() / state.indexTotal).coerceIn(0f, 1f)
        else -> 0f
    }
    val status = when (phase) {
        IndexPhase.WAITING -> t("clean.onboarding.status.waiting")
        IndexPhase.BUILDING -> if (state.indexTotal > 0) "${(fraction * 100).toInt()}%" else t("clean.onboarding.status.starting")
        IndexPhase.READY -> t("clean.onboarding.status.ready")
        IndexPhase.ERROR -> t("clean.onboarding.status.failed")
    }
    val meta = when (phase) {
        IndexPhase.WAITING -> t("clean.onboarding.index.waiting")
        IndexPhase.BUILDING -> if (state.indexTotal > 0) t("clean.onboarding.index.progress", "done" to countText(state.indexDone), "total" to countText(state.indexTotal))
            else t("clean.onboarding.index.starting")
        IndexPhase.READY -> if (state.indexTotal > 0) t("clean.onboarding.articles", "count" to state.indexTotal, "value" to countText(state.indexTotal)) else t("clean.onboarding.status.ready")
        IndexPhase.ERROR -> state.indexError ?: t("clean.onboarding.status.failed")
    }
    ProgressRow(
        icon = BoarIcons.AutoAwesome, name = t("clean.onboarding.index.title"), status = status, meta = meta, fraction = fraction,
        indeterminate = building && state.indexTotal == 0, ready = phase == IndexPhase.READY, failed = phase == IndexPhase.ERROR,
        active = building, error = null,
        detail = state.indexTitle.takeIf { building && it.isNotEmpty() },
    ) {
        TextButton({ vm.retryIndex() }) { Text(t("flows.row.retry")) }
    }
}

/**
 * One install row: status icon, name and status on one line, its own bar, then size or progress; on failure the
 * error and the [actions].
 */
@Composable
private fun ProgressRow(
    icon: ImageVector,
    name: String,
    status: String,
    meta: String,
    fraction: Float,
    indeterminate: Boolean,
    ready: Boolean,
    failed: Boolean,
    active: Boolean,
    error: String?,
    detail: String? = null,
    actions: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    val shown by animateFloatAsState(fraction, if (motion) tween(300) else snap(), label = "row")
    val tone: Color = when {
        failed -> cs.error
        ready || active -> cs.primary
        else -> cs.onSurfaceVariant
    }
    val a11y = "$name, $status"
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(when { ready -> Icons.Filled.CheckCircle; failed -> BoarIcons.ErrorOutline; else -> icon }, null, tint = tone)
            }
            Text(name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.labelMedium, color = tone)
        }
        Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!ready) {
                if (indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth(), color = tone, trackColor = cs.surfaceContainerHighest)
                else LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth(), color = tone, trackColor = cs.surfaceContainerHighest,
                    drawStopIndicator = {})
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = if (failed) cs.error else cs.onSurfaceVariant, maxLines = 3)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            AnimatedVisibility(failed) {
                Column {
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
                }
            }
        }
    }
}
