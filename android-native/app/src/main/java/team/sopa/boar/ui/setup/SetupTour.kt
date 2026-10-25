package team.sopa.boar.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.BoarServices.ModelLoadState
import team.sopa.boar.engine.models.StartLoad
import team.sopa.boar.setup.KnowledgeCatalog
import team.sopa.boar.setup.SetupPhase
import team.sopa.boar.setup.SetupState
import team.sopa.boar.ui.t

/** The tour's cards, in order. */
private enum class TourCard { WELCOME, KNOWS, ASK, SOURCE, HEALTH, PRIVATE }

/** Where the first run is: the unpack, then the answer model's load. Shown only by Open LabRat on the last card. */
internal enum class TourStatus {
    UNPACKING, LOADING, READY, FAILED,
    /** The model may not fit, or closed the app last time: it isn't loaded on its own, the chat asks first (StartLoad). */
    ASK,
}

/** "Open LabRat" opens the chat only once the model is ready (or when the chat must ask before loading it). */
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
 * First run: six swipeable cards, each a stage where the app shows itself (the chat's own parts, animated) over a title
 * and one line: welcome, what LabRat knows, how to ask, checking a source, health, privacy. It starts at once while
 * the bundle unpacks and the answer model loads behind it, with no progress shown and no Skip: only Open LabRat, on
 * the last card, waits until the model is ready. [onExit] (setup reopened from Settings) is Back on the first card.
 */
@Composable
internal fun SetupTour(setup: SetupState, onExit: (() -> Unit)?, onOpen: () -> Unit) {
    val services = BoarServices.get(LocalContext.current)
    val model by services.modelState.collectAsState()
    val status = tourStatus(setup, model, services)
    val motion = rememberMotionEnabled()
    val packs = remember {
        services.catalog.corpusCatalog.filter { it.format == "sqlite-pack" }.let { all -> all.filter { services.fileOf(it).isFile }.ifEmpty { all } }
    }
    val groups = remember(packs) { KnowledgeCatalog.tourGroups(packs) }
    val cards = TourCard.entries
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    BackHandler(enabled = page > 0 || onExit != null) { if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } else onExit?.invoke() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)) {
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                    val play = page == i
                    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                        val stage = Modifier.weight(1f).padding(horizontal = 16.dp)
                        when (cards[i]) {
                            TourCard.WELCOME -> WelcomeScene(groups, packs.size, play, motion, stage)
                            TourCard.KNOWS -> KnowsScene(groups, packs.size, play, motion, stage)
                            TourCard.ASK -> AskScene(play, motion, stage)
                            TourCard.SOURCE -> SourceScene(play, motion, stage)
                            TourCard.HEALTH -> HealthScene(play, motion, stage)
                            TourCard.PRIVATE -> PrivateScene(packs.size, play, motion, stage)
                        }
                        val (title, body) = when (cards[i]) {
                            TourCard.WELCOME -> t("clean.tour.welcome.title") to t("clean.tour.welcome.body", "count" to packs.size)
                            TourCard.KNOWS -> t("clean.tour.knows.title") to t("clean.tour.knows.body", "count" to packs.size)
                            TourCard.ASK -> t("clean.tour.ask.headline") to t("clean.tour.ask.body")
                            TourCard.SOURCE -> t("clean.tour.source.title") to t("clean.tour.source.tap")
                            TourCard.HEALTH -> t("clean.tour.health.title") to t("clean.tour.health.fixed")
                            TourCard.PRIVATE -> t("clean.tour.private.title") to t("clean.tour.private.body")
                        }
                        Column(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { heading() })
                            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TourNav(page, cards.size, status,
                    onBack = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    onNext = { scope.launch { pager.animateScrollToPage(page + 1) } },
                    onOpen = onOpen, onRetry = services::startModelLoad)
            }
        }
    }
}

/**
 * Back, the card dots (the current one a pill) and Next; on the last card, Back and Open LabRat, which reads
 * "Getting ready…" until the model is loaded, or says the load failed with Retry.
 */
@Composable
private fun TourNav(page: Int, count: Int, status: TourStatus, onBack: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val last = page == count - 1
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (last) Dots(page, count, Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!last) {
                Box(Modifier.width(104.dp)) {
                    if (page > 0) TextButton(onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(t("clean.tour.back"), maxLines = 1)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Dots(page, count) }
                Button(onNext, Modifier.width(120.dp).height(52.dp)) {
                    Text(t("clean.tour.next"), maxLines = 1)
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            } else {
                FilledTonalIconButton(onBack, Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("clean.tour.back")) }
                when {
                    status.canOpen() -> Button(onOpen, Modifier.weight(1f).height(52.dp)) {
                        Text(t("clean.tour.open"), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                    status == TourStatus.FAILED -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("clean.tour.strip.failed"), style = MaterialTheme.typography.bodyMedium, color = cs.error,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onRetry, Modifier.height(52.dp)) { Text(t("clean.tour.retry"), maxLines = 1) }
                    }
                    else -> Button({}, Modifier.weight(1f).height(52.dp).semantics { liveRegion = LiveRegionMode.Polite }, enabled = false) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.size(10.dp))
                        Text(t("clean.tour.gettingReady"), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The card dots: the current card a 24 dp pill in primary, the others 8 dp dots. */
@Composable
private fun Dots(page: Int, count: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val where = t("clean.tour.cardA11y", "index" to page + 1, "count" to count)
    Row(modifier.semantics { contentDescription = where }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { n ->
            val on = n == page
            val w by animateDpAsState(if (on) 24.dp else 8.dp, label = "dot$n")
            val c by animateColorAsState(if (on) cs.primary else cs.outlineVariant, label = "dotColor$n")
            Box(Modifier.size(width = w, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(c))
        }
    }
}
