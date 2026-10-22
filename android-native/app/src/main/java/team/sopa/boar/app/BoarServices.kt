package team.sopa.boar.app

import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.sopa.boar.engine.BoarEngine
import team.sopa.boar.engine.CpuTopology
import team.sopa.boar.engine.EngineParams
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.models.LoadGuard
import team.sopa.boar.engine.answer.ModelHost
import team.sopa.boar.engine.answer.Personalities
import team.sopa.boar.engine.answer.SessionEngine
import team.sopa.boar.engine.answer.StepEngine
import team.sopa.boar.engine.chat.ChatStore
import team.sopa.boar.engine.db.NativeSqlite
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.Catalog
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.rag.KnowledgeBase
import team.sopa.boar.engine.rag.OpenPack
import team.sopa.boar.engine.rag.Packs
import team.sopa.boar.engine.rag.Retriever
import team.sopa.boar.engine.rag.WikiPack
import team.sopa.boar.engine.settings.AppSettings
import java.io.File

/**
 * Everything the screens share, opened once per process on the Expo app's own files (files/settings.json,
 * files/SQLite/aoair_knowledge.db, files/models, files/corpus), so an upgrade keeps models, chats and settings.
 * One generation model is loaded at a time ([host]); the embedder loads when retrieval first needs it.
 */
class BoarServices private constructor(private val context: Context) {
    private val files = context.filesDir
    /** The app's files dir, where models, packs and the chat database live. */
    val filesDir: java.io.File get() = files
    val settings = AppSettings(File(files, "settings.json"))
    private val baseCatalog: Catalog = Catalog.parse(asset("boar-data/catalog.json"))
    private val discoveredFile = File(files, "discovered_models.json")
    private val discoveredJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Models added from the Hugging Face search (discoveredModels.ts, same file as the Expo app): not vetted, so kept
     * apart from the built-in catalog and listed after it.
     */
    fun discovered(): List<CatalogAsset> = runCatching {
        discoveredJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CatalogAsset.serializer()), discoveredFile.readText())
    }.getOrDefault(emptyList())

    /** Written to a temporary file, then moved over the old one: a failed write never leaves a truncated list. */
    private fun writeDiscovered(list: List<CatalogAsset>) {
        val tmp = File(files, "discovered_models.json.tmp")
        tmp.writeText(discoveredJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(CatalogAsset.serializer()), list))
        java.nio.file.Files.move(tmp.toPath(), discoveredFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** The built-in catalog plus the discovered models (a search result already in the catalog isn't listed twice). */
    @Volatile var catalog: Catalog = withDiscovered()
        private set

    private fun withDiscovered(): Catalog {
        val extra = discovered().filter { d -> baseCatalog.models.none { it.filename == d.filename } }
        return baseCatalog.copy(models = baseCatalog.models + extra)
    }

    fun addDiscovered(asset: CatalogAsset) = synchronized(this) {
        val all = discovered()
        if (all.none { it.id == asset.id }) { writeDiscovered(all + asset); settings.stampModel(AppSettings.MODEL_ADDED, asset.id) }
        catalog = withDiscovered()
    }

    fun removeDiscovered(id: String) = synchronized(this) {
        writeDiscovered(discovered().filter { it.id != id })
        catalog = withDiscovered()
    }
    /**
     * The answer styles. The app starts on Succinct (personalities.json's defaultId, the one source for the default in
     * the chat, Settings and the benchmark runner); a style the user picked is kept.
     */
    val personalities: Personalities = Personalities.parse(asset("boar-data/personalities.json"))
    init { applyDefaults() }

    /**
     * At start and after "Reset settings". The engine's AppSettings.autoMaxTokens reads a missing style as Detailed (a
     * longer answer budget), so the default is written down rather than left implicit; technical details only show
     * while developer options are on, since their switch lives there.
     */
    fun applyDefaults() {
        if (settings.personalityId == null) settings.personalityId = personalities.defaultId
        if (settings.showLiveStats && !DevOptions.enabled(context)) settings.showLiveStats = false
    }
    val topology: CpuTopology = CpuTopology.read()
    /** Total RAM, for the Auto context size; 0 when Android doesn't say. */
    val totalRamBytes: Long = runCatching {
        android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    private val kbDb = NativeSqlite.open(File(files, "SQLite").apply { mkdirs() }.resolve("aoair_knowledge.db").path)
    val knowledgeBase = KnowledgeBase(kbDb)
    val chats = ChatStore(kbDb)
    /** The PIN that opens the app and seals the chats (none until the user sets one). */
    val pinLock = team.sopa.boar.security.PinLock(settings, chats)

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

    /** Portuguese article names for PT questions (assets/lexicon/pt-en.json), loaded on first use. */
    val lexicon: Map<String, String> by lazy {
        runCatching { Json.parseToJsonElement(asset("lexicon/pt-en.json")).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrDefault(emptyMap())
    }

    fun fileOf(asset: CatalogAsset) = File(files, asset.filename)
    /** Everything BOAR keeps on the phone: models, packs, the knowledge base with imported documents, chats, settings. */
    fun storageUsedBytes(): Long = files.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun installed(kind: AssetKind) = catalog.models.filter { it.kind == kind && fileOf(it).isFile }

    /** The user's model, else the first installed one (the catalog's order puts the default first). */
    fun activeModel(): CatalogAsset? =
        settings.activeModelId("llm")?.let(catalog::byId)?.takeIf { fileOf(it).isFile } ?: installed(AssetKind.LLM).firstOrNull()

    /** CR-2: the marker next to the models and the bookkeeping in settings.json. */
    val loadGuard = LoadGuard(object : LoadGuard.MarkerStore {
        private val f = File(files, "model-load.marker.json")
        // A marker left by the user closing BOAR (swiped from Recents, force-stopped) is not the model running out of
        // memory: on Android 11+ the exit reason tells them apart, so only a real kill blocks the model.
        override fun read() = f.takeIf { it.isFile && !lastExitByUser() }?.readText()
        override fun write(json: String) = f.writeText(json)
        override fun clear() { f.delete() }
    }, settings.loadLedger)

    private fun lastExitByUser(): Boolean = android.os.Build.VERSION.SDK_INT >= 30 && runCatching {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val reason = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason
        reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED || reason == android.app.ApplicationExitInfo.REASON_USER_STOPPED
    }.getOrDefault(false)

    /**
     * The model the chat answers with: the active one, unless its last load killed the app (CR-2). Then the model loaded
     * before it, else the smallest installed one that isn't blocked, so a phone doesn't crash on every start.
     */
    fun answerModel(): CatalogAsset? {
        val m = activeModel() ?: return null
        if (!loadGuard.isBlocked(m.id)) return m
        val llms = installed(AssetKind.LLM)
        val pick = team.sopa.boar.engine.models.StartLoad.fallback(llms.map(::startModel), loadGuard::isBlocked, settings.lastLoadedModelId)
        return pick?.let { p -> llms.firstOrNull { it.id == p.id } }
    }

    private fun startModel(a: CatalogAsset) = team.sopa.boar.engine.models.StartLoad.Model(a.id, a.sizeBytes)

    /** What the chat may load on its own now (StartLoad): a blocked model never, a RED one only after "Load anyway". */
    fun startDecision(): team.sopa.boar.engine.models.StartLoad.Decision =
        team.sopa.boar.engine.models.StartLoad.decide(activeModel()?.let(::startModel), installed(AssetKind.LLM).map(::startModel),
            loadGuard::isBlocked, settings.lastLoadedModelId, totalRamBytes, settings.loadAnywayIds.toSet())

    /** Rated RED for this phone's memory (Compatibility, as the Model Catalog shows it). */
    fun isRisky(a: CatalogAsset): Boolean = team.sopa.boar.engine.models.StartLoad.risky(startModel(a), totalRamBytes)

    /** "Load anyway": [modelId] loads at start from now on without asking. */
    fun rememberLoadAnyway(modelId: String) {
        val ids = settings.loadAnywayIds
        if (modelId !in ids) settings.loadAnywayIds = ids + modelId
    }

    // --- Packs and retrieval -----------------------------------------------------------------------------------

    private val embeddingSha = catalog.models.first { it.kind == AssetKind.EMBEDDING && it.required }.sha256

    @Volatile private var openPacks: List<OpenPack>? = null

    /** Installed knowledge packs in knowledgePacks()'s order: catalog packs first, then other files by name. */
    val packs: List<OpenPack> get() = openPacks ?: synchronized(this) { openPacks ?: scanPacks().also { openPacks = it } }

    /** After a pack is downloaded or removed: the next search opens the current set. */
    fun reloadPacks() = synchronized(this) {
        openPacks?.forEach { p -> runCatching { when (p) { is OpenPack.Format1 -> p.db.close(); is OpenPack.Format2 -> p.pack.close() } } }
        openPacks = null
    }

    private fun scanPacks(): List<OpenPack> {
        val dir = File(files, "corpus")
        val catalogFiles = catalog.corpusCatalog.filter { it.format == "sqlite-pack" }.map { it.id to File(files, it.filename) }
        val local = dir.listFiles { f -> f.name.endsWith(".sqlite") }.orEmpty().sortedBy { it.name }
            .filter { f -> catalogFiles.none { it.second == f } }.map { "local-${it.name.removeSuffix(".sqlite")}" to it }
        return (catalogFiles + local).filter { it.second.isFile }.mapNotNull { (id, f) ->
            runCatching {
                val db = NativeSqlite.open(f.path, readOnly = true)
                val meta = db.query("SELECT key, value FROM meta") { it.string(0)!! to (it.string(1) ?: "") }.toMap()
                when (Packs.packFormat(meta, embeddingSha)) {
                    1 -> OpenPack.Format1(id, db)
                    2 -> OpenPack.Format2(id, WikiPack.open(db, NativeSqlite.zstd))
                    else -> { db.close(); null }
                }
            }.getOrNull()
        }
    }

    private val embedLock = Mutex()
    private var embedder: LlamaSession? = null

    /** The embedding model, loaded on first use; an empty vector when none is installed. */
    suspend fun embed(text: String): FloatArray = embedLock.withLock {
        val e = embedder ?: run {
            val f = installed(AssetKind.EMBEDDING).firstOrNull()?.let(::fileOf) ?: return FloatArray(0)
            LlamaSession.load(f.path, EngineParams(nCtx = 512, threads = 2, embeddings = true)).also { embedder = it }
        } ?: return FloatArray(0)
        e.embed(text)
    }

    val retriever: Retriever by lazy {
        Retriever(knowledgeBase, { packs }, { text -> runBlocking { embed(text) } }, { lexicon })
    }

    // --- The generation model ----------------------------------------------------------------------------------

    /** One generation model at a time, loaded with the user's Advanced tweaks. */
    val host = SessionHost()

    inner class SessionHost : ModelHost {
        private val lock = Mutex()
        @Volatile private var session: LlamaSession? = null
        private var filename: String? = null
        private var residentId: String? = null
        private var residentRepacked = false
        /** The minute-later settle of the current load (LoadGuard.settled), cancelled when that load goes. */
        private var settleJob: kotlinx.coroutines.Job? = null
        /** Models that stalled with repacked weights in this process: mapped from storage until the app restarts (#73). */
        private val keepMapped = java.util.Collections.synchronizedSet(HashSet<String>())

        override fun residentFilename(): String? = filename
        override fun filenameOf(modelId: String): String? = catalog.byId(modelId)?.takeIf { fileOf(it).isFile }?.filename

        override suspend fun load(modelId: String): StepEngine? = lock.withLock {
            val asset = catalog.byId(modelId)?.takeIf { fileOf(it).isFile } ?: return null
            val tweaks = settings.engineTweaks
            // A stall while repacked: reload as the mapped file before the next generation (the caller waits on the lock).
            val reloadMapped = filename == asset.filename && residentRepacked && asset.id in keepMapped && tweaks.weightRepack != true
            if (filename != asset.filename || session == null || reloadMapped) {
                val previous = (residentId ?: settings.lastLoadedModelId)?.let(catalog::byId)?.let { LoadGuard.Meta(it.filename, it.id, it.label) }
                settleJob?.cancel(); settleJob = null
                session?.close()
                session = null
                filename = null
                residentId = null
                val meta = LoadGuard.Meta(asset.filename, asset.id, asset.label)
                val repack = tweaks.weightRepack ?: !(loadGuard.mappedNext(asset.id) || asset.id in keepMapped)
                loadGuard.begin(meta, previous, repack)
                val resolved = tweaks.resolve(topology, totalRamBytes).params.copy(repack = repack)
                // A cancelled load clears its marker: it didn't kill the app, and must not block the model next time.
                val s = try { LlamaSession.load(fileOf(asset).path, resolved) } catch (e: kotlinx.coroutines.CancellationException) { loadGuard.end(meta, false); throw e }
                loadGuard.end(meta, s != null)
                if (s == null) return null
                session = s
                filename = asset.filename
                residentId = asset.id
                residentRepacked = repack
                openedContext = resolved.nCtx
                s.onGenerated = { m ->
                    // A finished answer proves the model fits: the load guard's marker can go.
                    loadGuard.settled(asset.id)
                    if (repack && LoadGuard.isDecodeStall(m.generatedTokens, m.decodeMs) && keepMapped.add(asset.id))
                        android.util.Log.w("BOAR", "${asset.id} stalled with repacked weights (${m.generatedTokens} tokens in ${m.decodeMs.toInt()} ms): mapped from the next answer")
                }
                // Or a minute loaded without being killed: Android kills an app whose model doesn't fit within seconds.
                settleJob?.cancel()
                val loaded = s
                settleJob = lifeScope.launch {
                    kotlinx.coroutines.delay(LOAD_SETTLE_MS)
                    // Only for this very load: a model unloaded and loaded again since has its own marker and timer.
                    lock.withLock { if (session === loaded) loadGuard.settled(asset.id) }
                }
                settings.lastLoadedModelId = asset.id
                settings.stampModel(AppSettings.MODEL_LAST_USED, asset.id)
            }
            SessionEngine(session!!, tweaks.thinkingEnabled)
        }

        /** The context window the resident model was opened with (sizes Auto answer length); null before a load. */
        @Volatile var openedContext: Int? = null
            private set

        /** The loaded session, for the default path (AnswerPipeline). */
        fun current(): LlamaSession? = session

        /** Frees the model; the next answer loads it again (with changed Advanced tweaks, or another model). */
        /** BOAR left the foreground: a kill from now on is Android reclaiming a background app, not the model's load. */
        fun settleResident() { residentId?.let(loadGuard::settled) }

        suspend fun unload() = lock.withLock {
            // Unloaded on purpose (free memory): the model didn't kill the app, so its load marker goes.
            settleJob?.cancel(); settleJob = null
            residentId?.let(loadGuard::settled)
            session?.close()
            session = null
            filename = null
            residentId = null
        }
    }

    // --- Free memory when I leave BOAR -------------------------------------------------------------------------

    private val lifeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var backgroundRelease: kotlinx.coroutines.Job? = null
    @Volatile private var inForeground = true
    /** True while BOAR is on screen. */
    val isInForeground: Boolean get() = inForeground
    /** Run when BOAR comes back on screen (the chat's title deferred while it was in the background). */
    private val foregroundHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addForegroundHook(hook: () -> Unit) { foregroundHooks += hook }
    fun removeForegroundHook(hook: () -> Unit) { foregroundHooks -= hook }
    /** What uses the model besides a single generation: the chat's answer, its title or summary job, its preload. */
    private val busyChecks = java.util.concurrent.CopyOnWriteArrayList<() -> Boolean>()

    fun addBusyCheck(check: () -> Boolean) { busyChecks += check }
    fun removeBusyCheck(check: () -> Boolean) { busyChecks -= check }
    private fun modelBusy(): Boolean = busyChecks.any { runCatching { it() }.getOrDefault(false) }

    /**
     * BOAR left the screen: with Settings → Free memory when I leave BOAR on, the model is unloaded after the chosen
     * delay (Auto: at once). An answer, title or summary still running is waited for; coming back first cancels it. The chat stays as it is and the next question loads the model again.
     */
    @Synchronized fun onBackground() {
        inForeground = false
        host.settleResident()
        backgroundRelease?.cancel()
        backgroundRelease = null
        if (!settings.freeMemoryInBackground) return
        val wait = team.sopa.boar.engine.settings.FreeMemory.delayMs(settings.freeMemoryAfterMinutes)
        backgroundRelease = lifeScope.launch {
            kotlinx.coroutines.delay(wait)
            while (true) {
                val d = team.sopa.boar.engine.settings.FreeMemory.decide(settings.freeMemoryInBackground, inForeground, host.current() != null, modelBusy())
                when (d) {
                    team.sopa.boar.engine.settings.FreeMemory.Decision.SKIP -> return@launch
                    team.sopa.boar.engine.settings.FreeMemory.Decision.WAIT -> kotlinx.coroutines.delay(team.sopa.boar.engine.settings.FreeMemory.BUSY_POLL_MS)
                    team.sopa.boar.engine.settings.FreeMemory.Decision.UNLOAD -> {
                        host.unload()
                        android.util.Log.i("BOAR", "model unloaded after ${wait / 1000} s in the background")
                        return@launch
                    }
                }
            }
        }
    }

    @Synchronized fun onForeground() {
        inForeground = true
        backgroundRelease?.cancel()
        backgroundRelease = null
        foregroundHooks.forEach { runCatching { it() } }
    }

    /** Loads the active model if it isn't resident; null when no model is installed or it fails to load. */
    suspend fun activeSession(): LlamaSession? {
        val m = answerModel() ?: return null
        host.load(m.id) ?: return null
        return host.current()
    }

    companion object {
        /** How long a loaded model must stay alive before its load counts as safe (LoadGuard.settled). */
        const val LOAD_SETTLE_MS = 60_000L
        /**
         * The Custom answer style (a prompt the user writes) is hidden from Settings and the style sheet: it could be used to
         * make BOAR say anything and post it as BOAR's own words. The setting and prompt still work if already chosen.
         */
        const val SHOW_CUSTOM_STYLE = false
        /** The chat's style before settings load; the same as personalities.json's defaultId. */
        const val DEFAULT_PERSONALITY_ID = "succinct"

        @Volatile private var instance: BoarServices? = null

        fun get(context: Context): BoarServices = instance ?: synchronized(this) {
            instance ?: run {
                // The CPU build is chosen once per process, from the user's tweak (Auto by default).
                val app = context.applicationContext
                BoarEngine.init(app.applicationInfo.nativeLibraryDir, AppSettings(File(app.filesDir, "settings.json")).engineTweaks.cpuBuild)
                BoarServices(app).also { instance = it }
            }
        }
    }
}
