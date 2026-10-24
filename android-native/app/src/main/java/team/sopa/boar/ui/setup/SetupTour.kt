package team.sopa.boar.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.BoarServices.ModelLoadState
import team.sopa.boar.engine.answer.AnswerNote
import team.sopa.boar.engine.models.StartLoad
import team.sopa.boar.setup.KnowledgeCatalog
import team.sopa.boar.setup.SetupPhase
import team.sopa.boar.setup.SetupState
import team.sopa.boar.ui.security.PinFlow
import team.sopa.boar.ui.security.PinSetup
import team.sopa.boar.ui.settings.Choices
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SwitchRow
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.ThemeMode

/** The tour's cards, in order. */
private enum class TourCard { KNOWS, ASK, HEALTH, SOURCE, PRIVATE }

/** What the strip over the cards says: the unpack, then the answer model's load. */
internal enum class TourStatus {
    UNPACKING, LOADING, READY, FAILED,
    /** The model may not fit, or closed the app last time: it isn't loaded on its own, the chat asks first (StartLoad). */
    ASK,
}

/** "Open BoarLite" opens the chat only once the model is ready (or when the chat must ask before loading it). */
internal fun TourStatus.canOpen() = this == TourStatus.READY || this == TourStatus.ASK

@Composable
private fun tourStatus(setup: SetupState, model: ModelLoadState, services: BoarServices): TourStatus {
    val ask = remember(model) {
        model != ModelLoadState.READY && services.startDecision().let { it is StartLoad.Decision.AskRisky || it is StartLoad.Decision.NoFallback }
    }
    return when {
        setup.phase != SetupPhase.DONE -> TourStatus.UNPACKING
        model == ModelLoadState.READY -> TourStatus.READY
        model == ModelLoadState.FAILED -> TourStatus.FAILED
        ask -> TourStatus.ASK
        // IDLE too: the install has just asked for the load (BoarServices.startModelLoad), which starts a moment later.
        else -> TourStatus.LOADING
    }
}

/**
 * First run, after "Unpacking BoarLite": five swipeable cards (what BoarLite knows, how to ask, health, sources,
 * privacy with the PIN, theme and text size) under a strip that shows the answer model loading in the background. Open
 * BoarLite is enabled once the model is ready; Skip opens the chat at any time, which shows the load until it ends.
 */
@Composable
internal fun SetupTour(setup: SetupState, prefs: DisplayPrefs, onOpen: () -> Unit) {
    val services = BoarServices.get(LocalContext.current)
    val model by services.modelState.collectAsState()
    val status = tourStatus(setup, model, services)
    val cards = TourCard.entries
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val last = page == cards.lastIndex
    BackHandler(enabled = page > 0) { scope.launch { pager.animateScrollToPage(page - 1) } }
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)) {
                StatusStrip(setup, status, onRetry = services::startModelLoad, onSkip = if (setup.phase == SetupPhase.DONE) onOpen else null)
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = Gap),
                        verticalArrangement = Arrangement.spacedBy(Gap)) {
                        when (cards[i]) {
                            TourCard.KNOWS -> KnowsCard(services)
                            TourCard.ASK -> AskCard()
                            TourCard.HEALTH -> HealthCard()
                            TourCard.SOURCE -> SourceCard()
                            TourCard.PRIVATE -> PrivateCard(services, prefs, status)
                        }
                    }
                }
                TourNav(page, cards.size, last, status,
                    onBack = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    onNext = { if (last) onOpen() else scope.launch { pager.animateScrollToPage(page + 1) } })
            }
        }
    }
}

/** The strip on top of every card: the unpack's bytes, then the model's load, Ready, or a failure with Retry; and Skip. */
@Composable
private fun StatusStrip(setup: SetupState, status: TourStatus, onRetry: () -> Unit, onSkip: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        TourStatus.READY -> cs.primaryContainer to cs.onPrimaryContainer
        TourStatus.FAILED -> cs.errorContainer to cs.onErrorContainer
        else -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Surface(color = container, contentColor = content) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (status) {
                    TourStatus.READY -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                    TourStatus.FAILED -> Icon(Icons.Filled.Warning, null, Modifier.size(18.dp))
                    TourStatus.ASK -> {}
                    else -> CircularProgressIndicator(Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
                }
                val line = when (status) {
                    TourStatus.UNPACKING -> if (setup.phase == SetupPhase.COPYING && setup.bytesTotal > 0)
                        t("clean.tour.strip.unpacking", "done" to sizeText(setup.bytesDone), "total" to sizeText(setup.bytesTotal))
                    else t("clean.tour.strip.preparing")
                    TourStatus.LOADING -> t("clean.tour.strip.loading")
                    TourStatus.READY -> t("clean.tour.strip.ready")
                    TourStatus.FAILED -> t("clean.tour.strip.failed")
                    TourStatus.ASK -> t("clean.tour.strip.ask")
                }
                Text(line, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                if (status == TourStatus.FAILED) TextButton(onRetry) { Text(t("clean.tour.retry"), color = content) }
                if (onSkip != null) TextButton(onSkip) { Text(t("clean.tour.skip"), color = content) }
            }
            if (status == TourStatus.UNPACKING && setup.phase == SetupPhase.COPYING && setup.bytesTotal > 0) {
                val f = (setup.bytesDone.toFloat() / setup.bytesTotal).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth(), color = content, trackColor = container, drawStopIndicator = {})
            } else if (status == TourStatus.UNPACKING || status == TourStatus.LOADING) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = content, trackColor = container)
            }
        }
    }
}

/** Back, the card dots, and Next (Open BoarLite on the last card, enabled once the model is ready). */
@Composable
private fun TourNav(page: Int, count: Int, last: Boolean, status: TourStatus, onBack: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val where = t("clean.tour.cardA11y", "index" to page + 1, "count" to count)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (page > 0) TextButton(onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(t("clean.tour.back"))
            }
        }
        Row(Modifier.semantics { contentDescription = where }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { n -> Box(Modifier.size(if (n == page) 8.dp else 6.dp).background(if (n == page) cs.primary else cs.outlineVariant, CircleShape)) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (!last) FilledTonalButton(onNext) {
                Text(t("clean.tour.next"))
                Spacer(Modifier.size(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            } else Button(onNext, enabled = status.canOpen()) { Text(t("clean.tour.open")) }
        }
    }
}

@Composable
private fun CardTitle(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp).semantics { heading() })

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A short list of plain sentences, one per row, with a bullet. */
@Composable
private fun Bullets(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEach { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Text(l, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A tonal group, as Settings shows its rows. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** 1. The bundled packs, by theme, named from the catalog. */
@Composable
private fun KnowsCard(services: BoarServices) {
    val cs = MaterialTheme.colorScheme
    val packs = remember {
        services.catalog.corpusCatalog.filter { it.format == "sqlite-pack" }.let { all -> all.filter { services.fileOf(it).isFile }.ifEmpty { all } }
    }
    val groups = remember(packs) { KnowledgeCatalog.tourGroups(packs) }
    CardTitle(t("clean.tour.knows.title"))
    Line(t("clean.tour.knows.body", "count" to packs.size))
    Group {
        groups.forEach { g ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t(g.titleKey), style = MaterialTheme.typography.titleSmall, color = cs.primary)
                Text(g.names.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            }
        }
    }
}

/** 2. How to ask. */
@Composable
private fun AskCard() {
    CardTitle(t("clean.tour.ask.title"))
    Bullets(t("clean.tour.ask.one"), t("clean.tour.ask.short"), t("clean.tour.ask.followUp"), t("clean.tour.ask.sources"))
}

/** 3. Health and emergencies, with the packs' review caveat. */
@Composable
private fun HealthCard() {
    val cs = MaterialTheme.colorScheme
    CardTitle(t("clean.tour.health.title"))
    Bullets(t("clean.tour.health.fixed"), t("clean.tour.health.doctor"))
    Text(t("clean.tour.health.caveat"), style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().background(cs.tertiaryContainer, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp))
}

/** 4. The answer's closing line (the engine's own text) and the numbered sources. */
@Composable
private fun SourceCard() {
    val cs = MaterialTheme.colorScheme
    CardTitle(t("clean.tour.source.title"))
    Line(t("clean.tour.source.line"))
    Text(AnswerNote.LINE, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = cs.onSurface,
        modifier = Modifier.fillMaxWidth().background(cs.surfaceContainerLow, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp))
    Line(t("clean.tour.source.tap"))
}

/** 5. Privacy, and the real controls: PIN, theme and text size, saved and applied as they change (as in Settings). */
@Composable
private fun PrivateCard(services: BoarServices, prefs: DisplayPrefs, status: TourStatus) {
    val s = services.settings
    val cs = MaterialTheme.colorScheme
    CardTitle(t("clean.tour.private.title"))
    Line(t("clean.tour.private.body"))
    Group {
        var pinOn by remember { mutableStateOf(services.pinLock.isSet) }
        var flow by remember { mutableStateOf<PinFlow?>(null) }
        SwitchRow(t("native.settings.security"), t(if (pinOn) "native.settings.pinOn" else "native.settings.pinOff"), pinOn, icon = Icons.Outlined.Lock) { on ->
            flow = if (on) PinFlow.NEW else PinFlow.REMOVE
        }
        // The PIN flow over the tour, so the model keeps loading under it.
        flow?.let { f ->
            Dialog(onDismissRequest = { flow = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                Surface(Modifier.fillMaxSize(), color = cs.surface) {
                    PinSetup(services.pinLock, f, onDone = { pinOn = services.pinLock.isSet; flow = null }, onCancel = { flow = null })
                }
            }
        }
        var theme by remember { mutableStateOf(ThemeMode.normalize(s.themeId)) }
        Choices(t("clean.settings.theme"), ThemeMode.all.map { it to t("clean.settings.theme.$it") }, theme) { theme = it; s.themeId = it; prefs.setTheme(it) }
        var scale by remember { mutableStateOf(s.fontScale) }
        Choices(t("native.settings.textSize"), listOf("compact", "standard", "large").map { it to t("native.settings.$it") }, scale) { scale = it; s.fontScale = it; prefs.setFontScale(it) }
    }
    Text(t("clean.tour.private.settings"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    // Reached before the model is ready: say so here, next to the disabled Open BoarLite (never a silent wait).
    if (status == TourStatus.LOADING || status == TourStatus.UNPACKING) {
        Row(Modifier.fillMaxWidth().background(cs.secondaryContainer, MaterialTheme.shapes.small).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = cs.onSecondaryContainer, strokeWidth = 2.dp)
            Text(t("clean.tour.private.loading"), style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
        }
    }
}
