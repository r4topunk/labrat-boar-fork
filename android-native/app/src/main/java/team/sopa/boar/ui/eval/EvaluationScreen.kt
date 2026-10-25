package team.sopa.boar.ui.eval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import team.sopa.boar.eval.EvaluationState
import team.sopa.boar.eval.EvaluationViewModel
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import java.util.Locale

private fun formatMs(ms: Double?): String = when {
    ms == null -> "—"
    ms < 1000 -> String.format(Locale.US, "%.0fms", ms)
    else -> String.format(Locale.US, "%.1fs", ms / 1000)
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

/** Runs the fixed evaluation set against the chosen models. Results save as they come. */
@Composable
fun EvaluationScreen(state: EvaluationState, vm: EvaluationViewModel, chatBusy: Boolean, onClose: () -> Unit) {
    val c = Boar.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    // The model list closes when a run starts and the answers start closed, so progress and score fit the first screen.
    var modelsOpen by remember { mutableStateOf(true) }
    var answersOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.running) { if (state.running) modelsOpen = false }
    val chosen = state.configs.orEmpty().count { it.id in state.selected }
    val canRun = !state.running && !chatBusy && chosen > 0 && state.configs != null
    val export: (String) -> Unit = { format -> scope.launch { vm.exportIntent(format)?.let(ctx::startActivity) } }

    Column(Modifier.fillMaxSize().background(c.bgSurface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(c.bgCardElevated).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("evaluation.title"), style = Boar.type.titleSm.copy(letterSpacing = 0.5.sp), color = c.textHeading)
                Text(t("evaluation.subtitle", "version" to state.setVersion, "count" to state.queries.size) + " · BoarLite " + team.sopa.boar.app.DeviceReader.appVersion(ctx), style = Boar.type["mono.xs"].copy(fontSize = 9.sp), color = c.textDim)
            }
            if (!state.running) {
                Text(t("common.done"), style = Boar.type["mono.xs"], color = c.textAccentCyan, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDefault))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val configs = state.configs
            item {
                SectionToggle(t("native.eval.modelsTitle", "chosen" to chosen, "count" to configs.orEmpty().size), modelsOpen) { modelsOpen = !modelsOpen }
            }
            if (configs == null) item { CircularProgressIndicator(color = c.textAccentEmerald, modifier = Modifier.size(24.dp)) }
            else if (modelsOpen) {
                if (configs.isEmpty()) item { Note(t("evaluation.noModels")) }
                items(configs, key = { it.id }) { cfg ->
                    val on = cfg.id in state.selected
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.bgCardElevated).border(1.dp, c.borderDefault, RoundedCornerShape(6.dp))
                        .clickable { vm.toggle(cfg.id) }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (on) "☑" else "☐", fontSize = 16.sp, color = if (on) c.textAccentEmerald else c.textDim)
                        Text(cfg.label, style = Boar.type.subtext, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Note(t("evaluation.summary", "queries" to state.queries.size, "configs" to chosen, "total" to state.queries.size * chosen))
                    Note(if (chatBusy) t("evaluation.chatBusy") else t("native.eval.keepScreenOn"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.running) {
                            Action(if (state.stopping) t("evaluation.stopping") else t("evaluation.stop"), enabled = !state.stopping, danger = true) { vm.stop() }
                        } else {
                            Action(t("evaluation.run"), enabled = canRun) { vm.run() }
                        }
                        if (state.runId != null && !state.running) {
                            Action(t("evaluation.exportJsonl")) { export("jsonl") }
                            Action(t("evaluation.exportCsv")) { export("csv") }
                        }
                    }
                }
            }
            // Under the buttons, so Run and Stop stay put: progress and each model's score, live while it runs.
            if (state.running || state.rows.isNotEmpty()) item { ScoreSection(state, total = state.queries.size * chosen) }
            state.error?.let { item { Text(t("evaluation.runFailedTitle") + ": " + it, style = Boar.type["mono.xs"], color = c.crimson400) } }
            state.savedPath?.let { path ->
                item { Note((if (state.stopped) t("evaluation.stoppedEarly") + " " else "") + t("evaluation.savedTo", "path" to path)) }
            }
            if (state.rows.isNotEmpty()) item {
                SectionToggle(t("native.eval.answersTitle", "count" to state.rows.size), answersOpen) { answersOpen = !answersOpen }
            }
            if (answersOpen) items(state.rows, key = { "${it.str("configId")}/${it.str("queryId")}" }) { r ->
                val key = "${r.str("configId")}/${r.str("queryId")}"
                val open = expanded == key
                val outcome = r.str("outcome")
                val outcomeColor = when (outcome) { "failure" -> c.crimson400; "cancelled" -> c.amber400; else -> c.textAccentEmerald }
                val titles = r.list("retrievedTitles")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgCardElevated).border(1.dp, c.borderDefault, RoundedCornerShape(10.dp))
                    .clickable { expanded = if (open) null else key }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${r.str("queryId")} · ${r.str("configLabel")}", style = Boar.type.subtext, color = c.textHeading, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text((outcome ?: "—").uppercase(), style = Boar.type["mono.xs"].copy(fontSize = 9.sp), color = outcomeColor, fontWeight = FontWeight.SemiBold)
                    }
                    val meta = Boar.type["mono.xs"].copy(fontSize = 9.sp)
                    r.str("query")?.let { q -> Text("Q: $q", style = Boar.type.subtext, color = c.textMuted, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) }
                    Text("${r.str("modelId") ?: "—"} · ${r.str("taskType") ?: "—"} · ${r.str("modelResidency") ?: "—"} · " +
                        (if (r.bool("retrievalUsed")) titles.take(2).joinToString(", ") else t("evaluation.noRetrieval")), style = meta, color = c.textDim)
                    Text((r.num("tokPerSec")?.let { String.format(Locale.US, "%.1f t/s", it) } ?: "—") +
                        " · load ${formatMs(r.num("modelLoadMs"))} · TTFT ${formatMs(r.num("ttftMs"))} · total ${formatMs(r.num("totalLatencyMs"))}", style = meta, color = c.textDim)
                    Text(if (outcome == "failure" && r.str("errorMessage") != null) r.str("errorMessage")!! else "A: " + r.str("answer").orEmpty(),
                        style = Boar.type.subtext, color = c.textPrimary, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A section heading that opens and closes what follows (▸ closed, ▾ open). */
@Composable
private fun SectionToggle(title: String, open: Boolean, onToggle: () -> Unit) {
    val c = Boar.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Boar.type.subtext, color = c.textHeading, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(if (open) "▾" else "▸", style = Boar.type.subtext, color = c.textMuted)
    }
}

/**
 * The run's progress and each model's score v1 (EvalScore: speed 60 %, reliability 25 %, sources found 15 %) from the
 * answers so far: a ring with the score and a bar per part. Speed and reliability on this phone, not correctness.
 */
@Composable
private fun ScoreSection(state: EvaluationState, total: Int) {
    val c = Boar.colors
    val scores = remember(state.rows) { team.sopa.boar.engine.eval.EvalScore.scoreRunJson(state.rows) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.borderDefault, RoundedCornerShape(8.dp)).background(c.bgCardElevated).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t("native.eval.score.title"), style = Boar.type.body, color = c.textHeading, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val done = state.rows.size
            Text(t(if (state.running) "native.eval.score.running" else "native.eval.score.finished", "done" to done, "total" to total),
                style = Boar.type["mono.xs"], color = if (state.running) c.textAccentCyan else c.textMuted, fontWeight = FontWeight.SemiBold)
        }
        if (state.running) state.progress?.let { p ->
            Text("${p.config.label} — ${p.query.query}", style = Boar.type.subtext, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        scores.forEach { ScoreRow(it) }
        if (scores.isNotEmpty()) Note(t("native.eval.score.note"))
    }
}

@Composable
private fun ScoreRow(s: team.sopa.boar.engine.eval.EvalScore.ModelScore) {
    val c = Boar.colors
    // Real traffic-light colors for the ring only: the theme's "emerald" is gold in some themes, so a 96 looked like a warning.
    val tone = when { s.score >= 70 -> Color(0xFF22C55E); s.score >= 40 -> Color(0xFFF59E0B); else -> Color(0xFFEF4444) }
    val track = c.borderDefault
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val inset = 3.dp.toPx()
                val arc = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, arc, style = stroke)
                if (s.score > 0) drawArc(tone, -90f, 360f * s.score / 100f, false, topLeft, arc, style = stroke)
            }
            Text("${s.score}", color = c.textHeading, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.modelLabel ?: s.configId, style = Boar.type.subtext, color = c.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PartBar(t("native.eval.score.speed"), s.speed, s.medianTokPerSec?.let { String.format(Locale.US, "%.1f tok/s", it) } ?: "—", c.cyan400)
            PartBar(t("native.eval.score.reliability"), s.reliability, t("native.eval.score.answered", "done" to s.completed, "total" to s.answers), c.emerald400)
            PartBar(t("native.eval.score.sources"), s.retrieval ?: 0.0,
                if (s.retrieval == null) t("native.eval.score.noneExpected") else t("native.eval.score.found", "hits" to s.retrievalHits, "total" to s.retrievalQuestions), c.amber400)
        }
    }
}

@Composable
private fun PartBar(label: String, fraction: Double, value: String, color: Color) {
    val c = Boar.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = Boar.type["mono.xs"].copy(fontSize = 10.sp), color = c.textMuted, modifier = Modifier.weight(1f))
            Text(value, style = Boar.type["mono.xs"].copy(fontSize = 10.sp), color = c.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.borderDefault)) {
            Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, style = Boar.type["mono.xs"].copy(fontSize = 10.sp), color = Boar.colors.textDim)

@Composable
private fun RowScope.Action(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val c = Boar.colors
    Box(Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(6.dp))
        .background(if (danger) c.crimsonBgSubtle else c.bgCardElevated).border(1.dp, if (danger) c.crimsonBorder else c.borderDefault, RoundedCornerShape(6.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = Boar.type["mono.xs"], color = if (danger) c.crimson400 else c.textAccentCyan, fontWeight = FontWeight.SemiBold)
    }
}
