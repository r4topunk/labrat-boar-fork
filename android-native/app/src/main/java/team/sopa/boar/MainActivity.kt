package team.sopa.boar

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import team.sopa.boar.engine.settings.AppSettings
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SettingsScreen
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.models.ModelsViewModel
import team.sopa.boar.documents.DocumentsViewModel
import team.sopa.boar.ui.documents.DocumentsScreen
import team.sopa.boar.ui.about.AboutScreen
import team.sopa.boar.ui.drawer.BoarDrawer
import team.sopa.boar.ui.drawer.DrawerItem
import team.sopa.boar.ui.eval.EvaluationScreen
import team.sopa.boar.eval.EvaluationViewModel
import team.sopa.boar.ui.models.BrowseScreen
import team.sopa.boar.ui.models.KnowledgePacks
import team.sopa.boar.ui.models.ModelsScreen
import team.sopa.boar.ui.setup.SetupScreen
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.io.File
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.chat.ChatViewModel
import team.sopa.boar.engine.chat.ChatSession
import team.sopa.boar.ui.chat.ChatScreen
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import team.sopa.boar.ui.theme.BoarTheme

/** The app: first-run setup until the required models are on disk, then the chat with past chats and settings in the drawer. */
class MainActivity : ComponentActivity() {
    /** The app language from settings ("en", "pt"), else the phone's. */
    override fun attachBaseContext(base: Context) {
        val lang = AppSettings(File(base.filesDir, "settings.json")).languageId
        if (lang == null) return super.attachBaseContext(base)
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /** With a PIN: locked at start (the chats' key isn't in memory yet) and after a minute in the background. */
    private val locked = mutableStateOf(false)
    private var stoppedAt = 0L

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
        // A new process has no chat key yet; a recreated activity (rotation, font size) in the same process keeps it.
        if (services.pinLock.isSet && services.chats.cipher == team.sopa.boar.engine.security.PlainCipher) locked.value = true
        setContent {
            var theme by remember { mutableStateOf(services.settings.themeId) }
            var fontScale by remember { mutableStateOf(services.settings.fontScale) }
            val prefs = object : DisplayPrefs {
                override fun setTheme(id: String) { theme = id }
                override fun setFontScale(scale: String) { fontScale = scale }
                override fun setLanguage(id: String?) { recreate() }
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

@Composable
private fun App(services: BoarServices, prefs: DisplayPrefs, vm: ChatViewModel = viewModel(), setup: SetupViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(if (setup.ready() && !setup.unfinished) "chat" else "setup") }
    // Where Back leads from Models, Knowledge, About and Evaluation: Settings when it opened them, else the chat.
    var backTo by rememberSaveable { mutableStateOf("chat") }
    val devOptions = team.sopa.boar.app.DevOptions.enabled(androidx.compose.ui.platform.LocalContext.current)
    if (screen == "setup") {
        val setupState by setup.state.collectAsState()
        // Downloads pause in the background on MIUI: the screen stays on while setup runs.
        val view = LocalView.current
        DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        // Reopened from Settings (the app already works): step 1 offers the way back to the chat.
        val wasReady = remember { setup.ready() }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START) setup.onForeground() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        // "Discover GGUF models" from the model step: the Hugging Face page on top of setup; a model added there is
        // listed in setup and chosen, and setup comes back.
        var browsing by rememberSaveable { mutableStateOf(false) }
        val setupModels: ModelsViewModel = viewModel()
        if (browsing) {
            val modelsState by setupModels.state.collectAsState()
            LaunchedEffect(modelsState.hfAddedId) { modelsState.hfAddedId?.let { id -> setupModels.toastShown(); setup.pickDiscovered(id); browsing = false } }
            BackHandler { browsing = false }
            BrowseScreen(modelsState, setupModels, rowActions = false) { browsing = false }
            return
        }
        SetupScreen(setupState, setup,
            // Settings too: after "Reset settings" the chat must show the default style and Deep Research off.
            onSkip = if (wasReady) ({ setup.forget(); vm.refreshSettings(); screen = "chat" }) else null,
            // Cleared first: a model added earlier from Models must not be picked the moment the page opens.
            onBrowse = { setupModels.clearAdded(); browsing = true },
        ) { setup.forget(); vm.refreshSettings(); vm.refreshModel(); screen = afterSetup() }
        return
    }
    if (screen.startsWith("pin:")) {
        // pin:<flow>:<screen to return to>
        val (_, flow, back) = screen.split(":")
        BackHandler { screen = back }
        team.sopa.boar.ui.security.PinSetup(services.pinLock, team.sopa.boar.ui.security.PinFlow.valueOf(flow),
            onDone = { vm.refreshSettings(); screen = back }, onCancel = { screen = back })
        return
    }
    if (screen == "models") {
        val models: ModelsViewModel = viewModel()
        val modelsState by models.state.collectAsState()
        BackHandler { vm.refreshModel(); screen = backTo }
        ModelsScreen(modelsState, models) { vm.refreshModel(); screen = backTo }
        return
    }
    if (screen == "documents") {
        val docs: DocumentsViewModel = viewModel()
        val docsState by docs.state.collectAsState()
        val models: ModelsViewModel = viewModel()
        val modelsState by models.state.collectAsState()
        BackHandler { screen = backTo }
        DocumentsScreen(docsState, docs, packs = { KnowledgePacks(modelsState, models) }) { screen = backTo }
        return
    }
    if (screen == "evaluation") {
        val eval: EvaluationViewModel = viewModel()
        val evalState by eval.state.collectAsState()
        // A run takes many minutes: the screen stays on, and back does nothing until it ends (as Done hides).
        val view = LocalView.current
        DisposableEffect(evalState.running) { view.keepScreenOn = evalState.running; onDispose { view.keepScreenOn = false } }
        BackHandler { if (!evalState.running) { vm.refreshModel(); screen = backTo } }
        EvaluationScreen(evalState, eval, chatBusy = state.answeringId != null) { vm.refreshModel(); screen = backTo }
        return
    }
    if (screen == "about") {
        BackHandler { screen = backTo }
        AboutScreen { screen = backTo }
        return
    }
    if (screen == "settings") {
        BackHandler { vm.refreshSettings(); screen = "chat" }
        SettingsScreen(services, prefs, onPin = { flow -> screen = "pin:$flow:settings" }, onSetup = { setup.restart(); screen = "setup" }, onOpen = { backTo = "settings"; screen = it }) { vm.refreshSettings(); screen = "chat" }
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    LaunchedEffect(drawer.isOpen, state.answeringId) {
        if (drawer.isOpen) sessions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Old "<think>" titles (named by a reasoning model before Oct 15) read as the chat's first question.
            services.chats.repairThinkingTitles()
            services.chats.listSessions()
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                fun go(target: () -> Unit) { backTo = "chat"; target(); scope.launch { drawer.close() } }
                BoarDrawer(
                    sessions = sessions,
                    activeSessionId = state.sessionId,
                    // ChatScreen.tsx's items. Evaluation stands in for Execution Telemetry and shows only with developer
                    // options; Models is reached from Settings, and About from there too.
                    items = buildList {
                        add(DrawerItem("knowledge", t("nav.knowledge")) { go { screen = "documents" } })
                        if (devOptions) add(DrawerItem("evaluation", t("evaluation.title")) { go { screen = "evaluation" } })
                        add(DrawerItem("settings", t("chatScreen.drawerItems.settings")) { go { screen = "settings" } })
                    },
                    lastTokPerSec = state.liveTokPerSec.takeIf { state.answeringId == null },
                    storageUsed = { services.storageUsedBytes() },
                    downloading = { team.sopa.boar.engine.models.AssetStore.downloadingBytes(services.filesDir) },
                    storageBudgetBytes = services.catalog.storageBudgetBytes,
                    showStats = state.technicalDetails,
                    answering = state.answeringId != null,
                    onNewChat = { go { vm.newChat() } },
                    onSelect = { id -> go { vm.openSession(id) } },
                    onDelete = { id -> vm.deleteSession(id); sessions = sessions.filter { it.id != id } },
                )
            }
        },
    ) {
        ChatScreen(
            state,
            onSend = vm::send,
            onStop = vm::stop,
            onNewChat = vm::newChat,
            onMenu = { scope.launch { drawer.open() } },
            onSetTone = vm::setTone,
            onSetDeepResearch = vm::setDeepResearch,
            onRedo = vm::redoWithLimit,
            onRetry = vm::retry,
            onDismissLoadNote = vm::dismissLoadNote,
            onRetryLessMemory = vm::retryWithLessMemory,
            onLoadAnyway = vm::loadAnyway,
            onOpenModels = { vm.openedCatalog(); backTo = "chat"; screen = "models" },
            onReused = { vm.showToast("chatScreen.messageReused") },
            onToastHidden = vm::hideToast,
        )
    }
}
