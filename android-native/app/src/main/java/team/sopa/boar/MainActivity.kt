package team.sopa.boar

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import team.sopa.boar.ui.icons.BoarIcons
import androidx.compose.runtime.saveable.rememberSaveable
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SettingsScreen
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.models.PacksViewModel
import team.sopa.boar.documents.DocumentsViewModel
import team.sopa.boar.ui.documents.DocumentsScreen
import team.sopa.boar.ui.about.AboutScreen
import team.sopa.boar.ui.drawer.BoarDrawer
import team.sopa.boar.ui.drawer.DrawerItem
import team.sopa.boar.ui.eval.EvaluationScreen
import team.sopa.boar.eval.EvaluationViewModel
import team.sopa.boar.ui.setup.SetupScreen
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.chat.ChatViewModel
import team.sopa.boar.engine.chat.ChatSession
import team.sopa.boar.ui.chat.ChatScreen
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.BoarTheme

/** The app: first-run setup unpacks the bundle (model, packs, index), then the chat with past chats and settings in the drawer. */
class MainActivity : ComponentActivity() {
    /** BOAR is English only: the texts, sizes and numbers are English whatever language the phone is set to. */
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /** With a PIN: locked at start (the chats' key isn't in memory yet) and after a minute in the background. */
    private val locked = mutableStateOf(false)
    private var stoppedAt = 0L

    /**
     * With a PIN, the screen is secure: blank in recent apps, screenshots and screen recordings, so chats can't be seen
     * there without the PIN. Without one, screenshots work as usual. Checked on every resume and pause, so a PIN set or
     * removed in Settings applies before the recent-apps picture is taken.
     */
    private fun applySecure() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (BoarServices.get(this).pinLock.isSet) window.addFlags(flag) else window.clearFlags(flag)
    }

    override fun onResume() {
        super.onResume()
        applySecure()
    }

    override fun onPause() {
        applySecure()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // A rotation or font change stops and starts the activity: that isn't leaving BOAR.
        if (!isChangingConfigurations) BoarServices.get(this).onBackground()
        stoppedAt = android.os.SystemClock.elapsedRealtime() // not the wall clock: changing the time can't skip the lock
    }

    override fun onStart() {
        super.onStart()
        BoarServices.get(this).onForeground()
        if (stoppedAt > 0 && BoarServices.get(this).pinLock.isSet && android.os.SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) locked.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val services = BoarServices.get(this)
        // A PIN set or removed in Settings protects the screen now, not only after BOAR next pauses.
        services.pinLock.onChange = { runOnUiThread { applySecure() } }
        // A new process has no chat key yet; a recreated activity (rotation, font size) in the same process keeps it.
        if (services.pinLock.isSet && services.chats.cipher == team.sopa.boar.engine.security.PlainCipher) locked.value = true
        setContent {
            var theme by remember { mutableStateOf(services.settings.themeId) }
            var fontScale by remember { mutableStateOf(services.settings.fontScale) }
            val prefs = object : DisplayPrefs {
                override fun setTheme(id: String) { theme = id }
                override fun setFontScale(scale: String) { fontScale = scale }
            }
            BoarTheme(theme, fontScale) {
                if (locked.value) team.sopa.boar.ui.security.LockScreen(services.pinLock) { locked.value = false }
                else App(services, prefs)
            }
        }
    }

    private companion object { const val RELOCK_AFTER_MS = 60_000L }
}

/** Where setup leads: straight to the chat. A PIN is set in Settings only, never offered here; [pinSet] is kept for old callers. */
@Suppress("UNUSED_PARAMETER")
internal fun afterSetup(pinSet: Boolean = false) = "chat"

/** How deep a screen sits, so a screen switch slides forward into a deeper one and back out of it. */
private fun screenDepth(screen: String) = when (screen.substringBefore(':')) {
    "setup" -> 0
    "chat" -> 1
    "settings" -> 2
    else -> 3 // documents, evaluation, about, pin:*
}

/** M3 emphasized: the easing of the shared-axis and fade-through transitions. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The screen switch: the Material shared axis (X), a ~10 % slide with a fade, 300 ms, forward when the target is
 * deeper and back when it is shallower. Setup is not part of that stack: it fades through (a fade with a slight scale).
 */
private fun AnimatedContentTransitionScope<String>.screenTransition(): ContentTransform {
    val from = screenDepth(initialState)
    val to = screenDepth(targetState)
    val fadeInSpec = tween<Float>(210, delayMillis = 90, easing = LinearOutSlowInEasing)
    if (from == 0 || to == 0) {
        return (fadeIn(fadeInSpec) + scaleIn(tween(300, easing = Emphasized), initialScale = 0.92f)) togetherWith fadeOut(tween(90))
    }
    val dir = if (to >= from) 1 else -1
    return (fadeIn(fadeInSpec) + slideInHorizontally(tween(300, easing = Emphasized)) { dir * it / 10 }) togetherWith
        (fadeOut(tween(90)) + slideOutHorizontally(tween(300, easing = Emphasized)) { -dir * it / 10 })
}

/**
 * Back from a pushed screen with the system's predictive preview: the screen shrinks (down to 90 %) and rounds its
 * corners as the finger moves, and the swipe commits with [onBack] (the screen then slides out) or springs back when
 * cancelled. Without a predictive gesture (button navigation, Android < 14) the handler just calls [onBack], as the
 * plain BackHandler did. [blocked] swallows Back instead (a run that must not be interrupted).
 */
@Composable
private fun PredictiveBackScreen(blocked: Boolean = false, onBack: () -> Unit, content: @Composable () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var edge by remember { mutableFloatStateOf(1f) }
    if (blocked) BackHandler {}
    else PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                dragging = true
                progress = e.progress
                edge = if (e.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
            }
        } catch (c: CancellationException) {
            dragging = false // cancelled: back to full size
            throw c
        }
        // Committed: [dragging] stays true so the screen leaves from where the finger left it.
        onBack()
    }
    val spec: AnimationSpec<Float> = if (dragging) snap() else tween(200)
    val shown by animateFloatAsState(if (dragging) progress else 0f, spec, label = "backProgress")
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val scale = 1f - 0.1f * shown
            scaleX = scale
            scaleY = scale
            translationX = edge * shown * 8.dp.toPx()
            if (shown > 0f) {
                clip = true
                shape = RoundedCornerShape((28f * shown).dp)
            }
        },
    ) { content() }
}

@Composable
private fun App(services: BoarServices, prefs: DisplayPrefs, vm: ChatViewModel = viewModel(), setup: SetupViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(if (setup.ready()) "chat" else "setup") }
    // Where Back leads from Knowledge, About and Evaluation: Settings when it opened them, else the chat.
    var backTo by rememberSaveable { mutableStateOf("chat") }
    AnimatedContent(
        targetState = screen,
        transitionSpec = { screenTransition() },
        // The three pin flows are one screen, not a switch between screens.
        contentKey = { it.substringBefore(':') },
        label = "screen",
    ) { current ->
        when {
            current == "setup" -> SetupHost(setup, vm) { screen = it }
            current.startsWith("pin:") -> {
                // pin:<flow>:<screen to return to>
                val (_, flow, back) = current.split(":")
                PredictiveBackScreen(onBack = { screen = back }) {
                    team.sopa.boar.ui.security.PinSetup(services.pinLock, team.sopa.boar.ui.security.PinFlow.valueOf(flow),
                        onDone = { vm.refreshSettings(); screen = back }, onCancel = { screen = back })
                }
            }
            current == "documents" -> {
                val docs: DocumentsViewModel = viewModel()
                val docsState by docs.state.collectAsState()
                val packs: PacksViewModel = viewModel()
                val packsState by packs.state.collectAsState()
                LaunchedEffect(Unit) { packs.refresh() }
                PredictiveBackScreen(onBack = { screen = backTo }) {
                    DocumentsScreen(docsState, docs, packs = { team.sopa.boar.ui.documents.KnowledgeLibrary(packsState, packs) },
                        // "Ask about your files": Sources = My files, then a new chat.
                        onAskFiles = { vm.setSourcesOwn(true); vm.newChat(); backTo = "chat"; screen = "chat" }) { screen = backTo }
                }
            }
            current == "evaluation" -> {
                val eval: EvaluationViewModel = viewModel()
                val evalState by eval.state.collectAsState()
                val chatBusy = vm.state.collectAsState().value.answeringId != null
                // A run takes many minutes: the screen stays on, and back does nothing until it ends (as Done hides).
                val view = LocalView.current
                DisposableEffect(evalState.running) { view.keepScreenOn = evalState.running; onDispose { view.keepScreenOn = false } }
                PredictiveBackScreen(blocked = evalState.running, onBack = { vm.refreshModel(); screen = backTo }) {
                    EvaluationScreen(evalState, eval, chatBusy = chatBusy) { vm.refreshModel(); screen = backTo }
                }
            }
            current == "about" -> PredictiveBackScreen(onBack = { screen = backTo }) { AboutScreen { screen = backTo } }
            current == "settings" -> PredictiveBackScreen(onBack = { vm.refreshSettings(); screen = "chat" }) {
                SettingsScreen(services, prefs, onPin = { flow -> screen = "pin:$flow:settings" }, onSetup = { setup.restart(); screen = "setup" }, onOpen = { backTo = "settings"; screen = it }) { vm.refreshSettings(); screen = "chat" }
            }
            else -> ChatHost(services, vm, onNavigate = { backTo = "chat"; screen = it })
        }
    }
}

/** First-run setup, and setup reopened from Settings. */
@Composable
private fun SetupHost(setup: SetupViewModel, vm: ChatViewModel, goTo: (String) -> Unit) {
    val setupState by setup.state.collectAsState()
    // Reopened from Settings (the app already works): the screen offers the way back to the chat.
    val wasReady = remember { setup.ready() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START) setup.onForeground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SetupScreen(setupState, setup,
        // Settings too: after "Reset settings" the chat must show the default options.
        onSkip = if (wasReady) ({ vm.refreshSettings(); goTo("chat") }) else null,
    ) { vm.refreshSettings(); vm.refreshModel(); goTo(afterSetup()) }
}

/** The chat with the drawer: past chats (delete with undo), Knowledge, Settings. [onNavigate] opens a screen; Back returns here. */
@Composable
private fun ChatHost(services: BoarServices, vm: ChatViewModel, onNavigate: (String) -> Unit) {
    val state by vm.state.collectAsState()
    val devOptions = team.sopa.boar.app.DevOptions.enabled(androidx.compose.ui.platform.LocalContext.current)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Back with the menu open closes the menu; it used to leave BOAR (smoke drawer-back, Oct 22).
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    val sessionsState = remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var sessions by sessionsState
    // Chats the user just deleted whose Undo is still open: out of the list, still in the database.
    val pendingDelete = remember { mutableSetOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(drawer.isOpen, state.answeringId) {
        if (drawer.isOpen) {
            val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Old "<think>" titles (named by a reasoning model before Oct 15) read as the chat's first question.
                services.chats.repairThinkingTitles()
                services.chats.listSessions()
            }
            sessions = all.filter { it.id !in pendingDelete }
        }
    }
    // The drawer reads only what it shows, through its own composable: the chat's state changes on every streamed
    // token, and the closed drawer stays composed, so reading `state` here re-ran the whole sheet per token.
    val answering = state.answeringId != null
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ChatDrawer(services, vm, drawer, sessionsState, pendingDelete, snackbar, devOptions, state.sessionId, answering,
                state.liveTokPerSec.takeIf { !answering }, state.technicalDetails, onNavigate)
        },
    ) {
        ChatScreen(
            state,
            onSend = vm::send,
            onStop = vm::stop,
            onNewChat = vm::newChat,
            onMenu = { scope.launch { drawer.open() } },
            onSetSourcesOwn = vm::setSourcesOwn,
            onToggleSource = vm::toggleSource,
            onRefreshSources = vm::refreshSources,
            onResetOptions = vm::resetOptions,
            onRedo = vm::redoWithLimit,
            onRetry = vm::retry,
            onDismissLoadNote = vm::dismissLoadNote,
            onRetryLessMemory = vm::retryWithLessMemory,
            onLoadAnyway = vm::loadAnyway,
            onReused = { vm.showToast("chatScreen.messageReused") },
            onToastHidden = vm::hideToast,
        )
    }
}

/** The drawer's sheet: past chats (delete with undo), Knowledge, Evaluation, Settings. Skipped while its inputs hold still. */
@Composable
private fun ChatDrawer(
    services: BoarServices, vm: ChatViewModel, drawer: androidx.compose.material3.DrawerState,
    sessionsState: androidx.compose.runtime.MutableState<List<ChatSession>>, pendingDelete: MutableSet<String>, snackbar: SnackbarHostState,
    devOptions: Boolean, activeSessionId: String?, answering: Boolean, lastTokPerSec: Double?, showStats: Boolean, onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sessions by sessionsState
    val deletedText = t("clean.nav.chatDeleted")
    val undoText = t("clean.nav.undo")
    // The chat the drawer's Delete is about to remove, while the dialog asks to confirm it (the dialog names the chat).
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    fun deleteNow(id: String) {
        // Optimistic: the row goes now, the database delete waits for the Undo bar to lapse. A second
        // delete closes the first bar, which commits it.
        val removed = sessions.firstOrNull { it.id == id }
        snackbar.currentSnackbarData?.dismiss()
        sessions = sessions.filter { it.id != id }
        pendingDelete += id
        scope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
            } finally {
                // Also when the scope is cancelled (the user left the chat): that counts as "let it go".
                pendingDelete -= id
                if (undone) { if (removed != null) sessions = (sessions + removed).sortedByDescending { it.updatedAt } }
                else vm.deleteSession(id)
            }
        }
    }
    confirmDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(t("clean.nav.deleteConfirmTitle")) },
            // The drawer's own title (no "Title:" prefix, quotes or final period); a blank one gets the title-only dialog.
            text = sessions.firstOrNull { it.id == id }?.title?.let { team.sopa.boar.ui.drawer.displayTitle(it) }?.takeIf { it.isNotBlank() }
                ?.let { shown -> { Text(t("clean.nav.deleteConfirmMessage", "title" to shown)) } },
            confirmButton = {
                androidx.compose.material3.TextButton({ confirmDelete = null; deleteNow(id) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(t("clean.nav.deleteButton")) }
            },
            dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = null }) { Text(t("common.cancel")) } },
        )
    }
    ModalDrawerSheet {
        fun go(target: () -> Unit) { target(); scope.launch { drawer.close() } }
        // The undo bar lives in the sheet: the drawer stays open while a chat is deleted.
        Box(Modifier.fillMaxSize()) {
            BoarDrawer(
                sessions = sessions,
                activeSessionId = activeSessionId,
                // ChatScreen.tsx's items. Evaluation stands in for Execution Telemetry and shows only with developer
                // options; Models is reached from Settings, and About from there too.
                items = buildList {
                    add(DrawerItem("knowledge", t("nav.knowledge"), BoarIcons.LibraryBooks) { go { onNavigate("documents") } })
                    if (devOptions) add(DrawerItem("evaluation", t("evaluation.title"), BoarIcons.Science) { go { onNavigate("evaluation") } })
                    add(DrawerItem("settings", t("chatScreen.drawerItems.settings"), Icons.Outlined.Settings) { go { onNavigate("settings") } })
                },
                lastTokPerSec = lastTokPerSec,
                storageUsed = { services.storageUsedBytes() },
                storageBudgetBytes = services.catalog.storageBudgetBytes,
                showStats = showStats,
                answering = answering,
                onNewChat = { go { vm.newChat() } },
                onSelect = { id -> go { vm.openSession(id) } },
                // Always asks first: one tap on the menu's Delete must not lose a chat.
                onDelete = { id -> confirmDelete = id },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
