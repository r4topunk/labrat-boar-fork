package team.sopa.boar.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import team.sopa.boar.app.BoarServices
import team.sopa.boar.setup.IndexPhase
import team.sopa.boar.setup.SetupState
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.security.PinFlow
import team.sopa.boar.ui.security.PinSetup
import team.sopa.boar.ui.settings.Choices
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SwitchRow
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.ThemeMode

/** The tour's cards in order; each one's strings are clean.tour.<key>.title / .body (and .where where it has one). */
// No answer style card: the app answers in Succinct only and offers no Deep Research (r4to, Oct 24).
internal val TOUR_CARDS = listOf("welcome", "look", "chats", "sources", "end")
private val TOUR_WHERE = setOf("look", "chats", "sources")

/** The download under the tour, in one line, its fraction, whether BOAR is ready, and whether it needs the install screen. */
internal data class TourProgress(val line: String, val fraction: Float, val ready: Boolean, val problem: Boolean)

@Composable
internal fun tourProgress(state: SetupState): TourProgress {
    val problem = state.stalled || state.failed.isNotEmpty() || state.indexPhase == IndexPhase.ERROR
    val fraction = when {
        state.finished -> 1f
        state.downloads.isNotEmpty() && !state.allPresent -> minOf(state.overallFraction, 0.99f)
        state.indexTotal > 0 -> minOf(state.indexDone.toFloat() / state.indexTotal, 0.99f)
        else -> 0.99f
    }
    val line = when {
        state.finished -> t("clean.tour.lineReady")
        problem -> t("clean.tour.lineProblem")
        state.downloads.isNotEmpty() && !state.allPresent -> listOfNotNull(
            t("clean.tour.lineDownloading", "pct" to (fraction * 100).toInt()),
            state.etaSeconds?.let { t("flows.onboarding.minutesLeft", "count" to minutesLeft(it)) },
        ).joinToString(" · ")
        else -> t("design.install.organizing")
    }
    return TourProgress(line, fraction, state.finished, problem)
}

/** The card on the install step that opens the tour, while there's still something to wait for. */
@Composable
internal fun TourInvite(onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(BoarIcons.AutoAwesome, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t("clean.tour.inviteTitle"), style = MaterialTheme.typography.titleSmall)
                Text(t("clean.tour.inviteBody"), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onStart) { Text(t("clean.tour.start")) }
        }
    }
}

/**
 * BOAR's setup tour, full screen over the install step, one card at a time. Unlike a tour that only shows, its cards
 * are the real settings: theme and text size, chat titles, memory and the PIN, so
 * the minutes of downloading leave BOAR set up the way the user wants. The download stays on top; when it's done the
 * tour says so and Open BOAR leaves it. [index] is held by the caller, so the card stays while setup moves under it.
 */
@Composable
internal fun SetupTour(
    progress: TourProgress, prefs: DisplayPrefs, index: Int, onIndex: (Int) -> Unit, onOpen: () -> Unit, onClose: () -> Unit,
) {
    val i = index.coerceIn(0, TOUR_CARDS.lastIndex)
    val key = TOUR_CARDS[i]
    val last = i == TOUR_CARDS.lastIndex
    var bannerHidden by rememberSaveable { mutableStateOf(false) }
    val motion = rememberMotionEnabled()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)) {
        // Back goes to the previous card, and closes the tour on the first.
        BackHandler { if (i > 0) onIndex(i - 1) else onClose() }
        val cs = MaterialTheme.colorScheme
        // A dialog has its own window: its status bar icons follow the tour's colors, not the activity's.
        val view = LocalView.current
        val lightBars = cs.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars }
            }
        }
        Surface(Modifier.fillMaxSize(), color = cs.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)) {
                    TourTop(progress, motion, onClose)
                    AnimatedVisibility(progress.ready && !bannerHidden && !last) {
                        ReadyBanner(onOpen) { bannerHidden = true }
                    }
                    AnimatedContent(key, transitionSpec = {
                        if (motion) fadeIn(tween(220)) togetherWith fadeOut(tween(120)) else fadeIn(snap()) togetherWith fadeOut(snap())
                    }, label = "tourCard", modifier = Modifier.weight(1f)) { k ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = Gap),
                            verticalArrangement = Arrangement.spacedBy(Gap)) {
                            if (k == "welcome") Image(painterResource(team.sopa.boar.R.drawable.mascot), null, Modifier.size(112.dp))
                            Text(t("clean.tour.$k.title"), style = MaterialTheme.typography.headlineSmall, color = cs.onSurface,
                                modifier = Modifier.semantics { heading() })
                            Text(t(if (k == "end" && progress.ready) "clean.tour.end.bodyReady" else "clean.tour.$k.body"), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                            TourControls(k, prefs)
                            if (k in TOUR_WHERE) Text(t("clean.tour.$k.where"), style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer,
                                modifier = Modifier.background(cs.secondaryContainer, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                    TourNav(i, last, progress, onBack = { onIndex(i - 1) }, onNext = {
                        when {
                            !last -> onIndex(i + 1)
                            progress.ready -> onOpen()
                            else -> onClose()
                        }
                    })
                }
            }
        }
    }
}

/** The tour's header: its name, Close, and the download's own bar and line under them. A problem's line opens the install step. */
@Composable
private fun TourTop(progress: TourProgress, motion: Boolean, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shown by animateFloatAsState(progress.fraction, if (motion) tween(400) else snap(), label = "tourBar")
    Column(Modifier.fillMaxWidth().padding(start = Gutter, end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("clean.tour.title"), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
            TextButton(onClose) { Text(t("clean.tour.close")) }
        }
        Column(Modifier.padding(end = Gutter - 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth(),
                color = if (progress.problem) cs.error else cs.primary, trackColor = cs.surfaceContainerHighest, drawStopIndicator = {})
            Text(progress.line, style = MaterialTheme.typography.bodySmall, color = if (progress.problem) cs.error else cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (progress.problem) Modifier.clickable(onClick = onClose) else Modifier)
        }
    }
}

@Composable
private fun ReadyBanner(onOpen: () -> Unit, onKeep: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("clean.tour.ready"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onOpen, Modifier.weight(1f)) { Text(t("clean.tour.openBoar")) }
                OutlinedButton(onKeep, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onPrimaryContainer),
                    border = BorderStroke(1.dp, cs.onPrimaryContainer.copy(alpha = 0.5f))) { Text(t("clean.tour.keepGoing"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun TourNav(i: Int, last: Boolean, progress: TourProgress, onBack: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (i > 0) TextButton(onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(t("clean.tour.back"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TOUR_CARDS.indices.forEach { n -> Box(Modifier.size(if (n == i) 8.dp else 6.dp).background(if (n == i) cs.primary else cs.outlineVariant, CircleShape)) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            when {
                !last -> FilledTonalButton(onNext) {
                    Text(t("clean.tour.next"))
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
                progress.ready -> Button(onNext) { Text(t("clean.tour.openBoar")) }
                else -> Button(onNext) { Text(t("clean.tour.done")) }
            }
        }
    }
}

/** A card's settings: the same rows and stores as Settings, saved as they change. */
@Composable
private fun TourControls(key: String, prefs: DisplayPrefs) {
    val services = BoarServices.get(LocalContext.current)
    val s = services.settings
    when (key) {
        "look" -> Group {
            var theme by remember { mutableStateOf(ThemeMode.normalize(s.themeId)) }
            Choices(t("clean.settings.theme"), ThemeMode.all.map { it to t("clean.settings.theme.$it") }, theme) { theme = it; s.themeId = it; prefs.setTheme(it) }
            var scale by remember { mutableStateOf(s.fontScale) }
            Choices(t("native.settings.textSize"), listOf("compact", "standard", "large").map { it to t("native.settings.$it") }, scale) { scale = it; s.fontScale = it; prefs.setFontScale(it) }
            var haptics by remember { mutableStateOf(s.hapticsEnabled) }
            SwitchRow(t("interfaceSettings.hapticFeedbackLabel"), t("native.interfaceSettings.hapticFeedbackValue"), haptics) { haptics = it; s.hapticsEnabled = it }
        }
        "chats" -> Group {
            var titles by remember { mutableStateOf(s.autoGenerateTitles) }
            SwitchRow(t("memorySettings.autoTitlesLabel"), t("memorySettings.autoTitlesValue"), titles, icon = Icons.Outlined.Edit) { titles = it; s.autoGenerateTitles = it }
            var summarize by remember { mutableStateOf(s.autoSummarize) }
            SwitchRow(t("clean.settings.chats.remember"), t("clean.settings.chats.rememberNote"), summarize, icon = BoarIcons.Psychology) { summarize = it; s.autoSummarize = it }
            // The PIN flow on top of the tour, so setup keeps running under both.
            var pinOn by remember { mutableStateOf(services.pinLock.isSet) }
            var flow by remember { mutableStateOf<PinFlow?>(null) }
            SwitchRow(t("native.settings.security"), t(if (pinOn) "native.settings.pinOn" else "native.settings.pinOff"), pinOn, icon = Icons.Outlined.Lock) { on ->
                flow = if (on) PinFlow.NEW else PinFlow.REMOVE
            }
            flow?.let { f ->
                Dialog(onDismissRequest = { flow = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        PinSetup(services.pinLock, f, onDone = { pinOn = services.pinLock.isSet; flow = null }, onCancel = { flow = null })
                    }
                }
            }
        }
    }
}

/** The tonal group Settings uses for its rows. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}
