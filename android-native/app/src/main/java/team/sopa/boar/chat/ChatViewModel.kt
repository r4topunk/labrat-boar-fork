package team.sopa.boar.chat

import kotlinx.coroutines.plus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.GenerationMetrics
import team.sopa.boar.engine.answer.AnswerEvent
import team.sopa.boar.engine.answer.AnswerPipeline
import team.sopa.boar.engine.answer.AnswerRequest
import team.sopa.boar.engine.answer.AnswerStage
import team.sopa.boar.engine.answer.AnswerText
import team.sopa.boar.engine.answer.SourceGroups
import team.sopa.boar.engine.answer.DeepResearch
import team.sopa.boar.engine.answer.HistoryBudget
import team.sopa.boar.engine.answer.PersonalFiles
import team.sopa.boar.engine.answer.PipelineInput
import team.sopa.boar.engine.answer.PlanExecutor
import team.sopa.boar.engine.answer.ResearchStage
import team.sopa.boar.engine.answer.SessionEngine
import team.sopa.boar.engine.answer.Summarize
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.StartLoad
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.ConversationTurn
import team.sopa.boar.engine.routing.AdaptivePlan
import team.sopa.boar.engine.routing.Classify
import team.sopa.boar.engine.routing.ModelRole
import team.sopa.boar.engine.routing.Profiles
import team.sopa.boar.engine.routing.RoutingPreset
import team.sopa.boar.engine.routing.TaskType
import team.sopa.boar.engine.settings.AnswerLimit

/** A source as the chat shows it (and stores with the answer): the passage the model read, and its similarity to the question. */
data class Source(val title: String, val source: String?, val relevance: Double?, val similarity: Double? = null, val docId: String? = null, val body: String = "")

data class UiMessage(
    val id: String,
    val role: String,
    val text: String,
    val sources: List<Source> = emptyList(),
    val feedback: String? = null,
    val stopped: Boolean = false,
    val timedOut: Boolean = false,
    val receipt: Receipt? = null,
    /** It ended at this token limit, not by itself (shown as a note with a redo at [nextLimit]). */
    val cutAt: Int? = null,
    val nextLimit: Int? = null,
    /** Why it failed (a native.chat.error* key): stays under the answer after the next question and in the saved chat. */
    val error: String? = null,
)

/** What the assistant is doing for the message being answered. */
sealed interface Activity {
    data object Retrieving : Activity
    data object Thinking : Activity
    data object Generating : Activity
    /** Deep Research: stage, sub-question index (1-based) and count. */
    data class Researching(val stage: ResearchStage, val index: Int?, val count: Int?) : Activity
}

/** A note over the chat about the answer model and the phone's memory, with what the user can do about it. */
sealed interface LoadNote {
    val modelId: String
    val label: String

    /**
     * [label]'s last load closed the app (not enough memory) and it isn't loaded on its own. [fallbackLabel] answers
     * instead (null: no model can); [onlyModel]: no other model is installed.
     */
    data class Crashed(override val modelId: String, override val label: String, val fallbackLabel: String?, val onlyModel: Boolean) : LoadNote

    /** [label] is rated RED for this phone's memory and wasn't loaded; "Load anyway" loads it. */
    data class Risky(override val modelId: String, override val label: String, val onlyModel: Boolean) : LoadNote
}

data class ChatState(
    val sessionId: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val answeringId: String? = null,
    val activity: Activity? = null,
    val liveTokPerSec: Double? = null,
    val modelLabel: String? = null,
    /** The model writing the answer in progress when it isn't [modelLabel] (adaptive routing sent it elsewhere). */
    val answeringModel: String? = null,
    val noModel: Boolean = false,
    val deepResearch: Boolean = false,
    /** Advanced tweaks → Show technical details (AppSettings.showLiveStats). */
    val technicalDetails: Boolean = false,
    val error: String? = null,
    /** The answer model's memory note (StartLoad): it closed the app last time, or may not fit; with its actions. */
    val loadNote: LoadNote? = null,
    /** While the model loads or the corpus indexes: the line the Expo app shows under the header (chatScreen.*). */
    val loadingKey: String? = null,
    val personalityId: String = BoarServices.DEFAULT_PERSONALITY_ID,
    /** A short message over the chat for about 2 s (Toast.tsx): the new tone, a reused question. */
    val toast: String? = null,
    val toneIcon: String = "",
    /** Stop was tapped and the answer hasn't ended yet (a step in progress finishes first). */
    val stopping: Boolean = false,
    /** The styles the header sheet offers (id to icon): "custom" only when a custom prompt is written. */
    val tones: List<Pair<String, String>> = emptyList(),
    val online: Boolean = false,
    /** Suggested question keys for the empty chat (chat.suggestions.*). */
    val suggestions: List<String> = emptyList(),
    /** Answer options → Sources = Only my files: the user's own files only (files-only answers, Retriever ownOnly). */
    val sourcesOwn: Boolean = false,
    /** Answer options → Sources: what the checklist lists (the user's files, the built-in library when indexed, each installed pack). */
    val sourceRows: List<SourceRow> = emptyList(),
    /** Sources left out of the search (ids of [sourceRows]); empty = Everything. Not read while [sourcesOwn]. */
    val sourcesOff: Set<String> = emptySet(),
    /** Answer options → Model: the answer models on the phone, the one answering, and the one recommended for it. */
    val models: List<ModelChoice> = emptyList(),
    val activeModelId: String? = null,
    val defaultModelId: String? = null,
    /** The answer model switch in progress (Answer options → Model): its id until it has loaded. */
    val switchingModelId: String? = null,
    val defaultPersonalityId: String = BoarServices.DEFAULT_PERSONALITY_ID,
) {
    /** Sources = Everything: every listed source searched (a pack installed later too). */
    val sourcesEverything: Boolean
        get() = !sourcesOwn && (if (sourceRows.isEmpty()) sourcesOff.isEmpty() else sourceRows.none { it.id in sourcesOff })

    /** The checklist's ticked rows. */
    val sourcesChecked: Set<String>
        get() = if (sourcesOwn) setOf(SourceRow.FILES) else sourceRows.map { it.id }.filter { it !in sourcesOff }.toSet()

    /** Anything the composer's options button must flag: a style, model or sources other than the defaults, or Deep Research. */
    val optionsChanged: Boolean
        get() = personalityId != defaultPersonalityId || !sourcesEverything || deepResearch ||
            (defaultModelId != null && activeModelId != null && activeModelId != defaultModelId)
}

/**
 * One row of Answer options → Sources: the user's files ([FILES]), the built-in library's seed chunks ([LIBRARY]), or an
 * installed pack by its id. [nameKey] is the t() key of its name, [name] the fallback (catalog label); [articles] from
 * pack-previews.json when known.
 */
data class SourceRow(val id: String, val nameKey: String?, val name: String, val articles: Int?) {
    companion object {
        const val FILES = "@files"
        const val LIBRARY = "@library"
    }
}

/** An installed answer model as Answer options → Model lists it. */
data class ModelChoice(val id: String, val name: String, val sizeBytes: Long, val recommended: Boolean)

/** The history title until the model writes one: the question trimmed, cut at a word with "…" when it runs past 60 characters. */
internal fun provisionalTitle(q: String): String {
    val t = q.trim().replace(Regex("\\s+"), " ")
    if (t.length <= 60) return t
    val cut = t.take(60)
    return (cut.substringBeforeLast(' ', cut).ifBlank { cut }).trimEnd(',', ';', ':', '-') + "…"
}

/**
 * The chat (port of ChatScreen.tsx's send flow): Deep Research when it's on, adaptive routing when that's on, the
 * default answer path otherwise; the answer streams into its message, and both turns are saved with the answer's
 * sources. Stop cancels the generation in flight and the stages after it.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    /**
     * Background work runs here: an unexpected error ends that job with a toast instead of closing the app, and an
     * answer it cut short is closed with the generic error line (layer 1 of the error handler, [team.sopa.boar.app.Safe]).
     */
    private val safeScope = viewModelScope + team.sopa.boar.app.Safe.handler(app) { _ ->
        _state.update { it.copy(answeringId = null, answeringModel = null, activity = null, stopping = false, error = "generic") }
    }
    private val _state = MutableStateFlow(ChatState(deepResearch = services.settings.deepResearchMode, technicalDetails = services.settings.showLiveStats,
        sourcesOwn = app.getSharedPreferences("answer_options", android.content.Context.MODE_PRIVATE).getBoolean(SOURCES_OWN, false),
        sourcesOff = app.getSharedPreferences("answer_options", android.content.Context.MODE_PRIVATE).getStringSet(SOURCES_OFF, null).orEmpty().toSet(),
        defaultPersonalityId = services.personalities.defaultId))
    val state: StateFlow<ChatState> = _state
    private var job: Job? = null
    /** A chat title or summary still being written; a new question stops it (one model, one generation). */
    private var background: Job? = null
    @Volatile private var stopRequested = false

    private val suggestionData = team.sopa.boar.engine.chat.Suggestions.parse(app.assets.open("boar-data/suggestions.json").bufferedReader().use { it.readText() })

    /**
     * Every prompt idea this model and language can show; [pickIdeas] draws a few at random. Declared before init:
     * a property declared after it is reset to its initial value once init's refreshModel() has filled it.
     */
    private var ideaPool: List<String> = emptyList()

    private var preloading: Job? = null
    /** The preload's warm prefill (inside [preloading]) and the prefix it fills; a question that can't reuse it stops it. */
    @Volatile private var warmJob: Job? = null
    @Volatile private var warmKey: team.sopa.boar.engine.answer.WarmPrefix.Key? = null
    /**
     * A title or summary skipped because the answer ended with BOAR in the background, written when BOAR is back on
     * screen. Run then, it would hold the model past MIUI's freeze about 8 s after the answering service stops, and MIUI
     * force-stops a frozen BOAR still holding the model (F3, Oct 19, 17:46:30-40).
     */
    @Volatile private var deferredAfterAnswer: (() -> Unit)? = null
    private val onForeground: () -> Unit = { resumeAfterAnswer() }
    /**
     * BOAR left the screen: the warm prefill stops (natively too, unless an answer is running), so it doesn't hold the
     * model without the answering service and Free memory when I leave can unload at once.
     */
    private val onBackground: () -> Unit = {
        warmJob?.takeIf { it.isActive }?.let { w ->
            w.cancel()
            // Natively only when the warm prefill itself holds the model: not an answer, nor a bench or eval run it waits behind.
            if (job?.isActive != true && warmInside) services.host.current()?.cancel()
        }
    }
    /** For Free memory when I leave BOAR: the model isn't unloaded while an answer, title, summary or preload runs. */
    private val busy: () -> Boolean = { job?.isActive == true || background?.isActive == true || preloading?.isActive == true }

    init {
        refreshModel()
        refreshTone()
        watchConnectivity(app)
        preload()
        services.addBusyCheck(busy)
        services.addForegroundHook(onForeground)
        services.addBackgroundHook(onBackground)
    }

    override fun onCleared() {
        services.removeBusyCheck(busy)
        services.removeForegroundHook(onForeground)
        services.removeBackgroundHook(onBackground)
        super.onCleared()
    }

    /**
     * Loads the model when the chat opens, as the Expo app does, so the first answer doesn't wait for it. Not a model
     * whose last load closed the app, nor one that may not fit this phone's memory: the chat asks first (StartLoad).
     */
    private fun preload() {
        if (services.activeModel() == null) return
        val decision = services.startDecision()
        if (decision is StartLoad.Decision.AskRisky || decision is StartLoad.Decision.NoFallback) {
            services.loadGuard.consume()
            _state.update { it.copy(loadNote = noteFor(decision)) }
            return
        }
        _state.update { it.copy(loadingKey = "chatScreen.mountingWeights") }
        // Meanwhile the packs open and the search model loads, which the first question waited for (about 0.5 s). Its own
        // job: the busy check (Free memory when I leave) waits for the model's work only, not for the search model.
        // After the first frame: opening every pack and the search model at launch competed with the first draw for the CPU
        // (cold start 1.45 → 1.65 s debug, 0.62 → 0.76 s perf on the Pixel 8a, Oct 23).
        val retrieval = safeScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.android.awaitFrame()
            withContext(Dispatchers.IO) { runCatching { services.warmRetrieval() } }
        }
        preloading = safeScope.launch(Dispatchers.Default) {
            val session = runCatching { services.activeSession() }.getOrNull()
            // Shown once: the model that closed the app last time, and the one answering instead.
            val crash = services.loadGuard.consume()
            _state.update { it.copy(loadingKey = null, loadNote = crash?.let(::crashNote) ?: it.loadNote) }
            if (crash != null) refreshModel()
            // The default answer's instructions go into the KV cache while the user types, as after a title: the first
            // answer then reads only its sources and question, like every later one (about 250 tokens, 2.7 s on the
            // 1.5B, Pixel 8a). A question sent meanwhile waits for it at the model's lock and reuses all of it, when it
            // uses this prefix (send stops it otherwise). Not once BOAR has left the screen, nor with a question already
            // on its way (it would only make it wait).
            if (session != null && crash == null && services.isInForeground && job?.isActive != true) {
                val key = chatWarmKey()
                warmKey = key
                warmJob = launch { runCatching { warmPrefix(session, key.systemPrompt, true, key.filesOnly) } }
            }
        }
        safeScope.launch { preloading?.join(); retrieval.join(); qaMark("preload.done") }
    }

    /**
     * The prefix a question in the user's chosen style gets (send's own choice, before any short-answer rule), with the
     * current Sources choice: My files' instruction differs from the first words after the style's.
     */
    private fun chatWarmKey(): team.sopa.boar.engine.answer.WarmPrefix.Key {
        val settings = services.settings
        val style = settings.personalityId ?: services.personalities.defaultId
        val smallModel = (services.answerModel()?.sizeBytes ?: Long.MAX_VALUE) < team.sopa.boar.engine.answer.Personalities.SMALL_MODEL_BYTES
        return team.sopa.boar.engine.answer.WarmPrefix.forChat(services.personalities, style, settings.customSystemPrompt, smallModel, _state.value.sourcesOwn)
    }

    /**
     * Stops the preload's warm prefill (the coroutine, and the native prefill in progress) and waits for it to end.
     * Called only when no generation of the chat's own is in native code, so the native cancel can only stop the warm-up.
     */
    private suspend fun cancelWarm() {
        val w = warmJob?.takeIf { it.isActive } ?: return
        w.cancel()
        services.host.current()?.cancel()
        w.join()
    }

    /** The note for a decision that doesn't load the user's model on its own; null when it loads. */
    private fun noteFor(d: StartLoad.Decision): LoadNote? = when (d) {
        is StartLoad.Decision.AskRisky -> LoadNote.Risky(d.modelId, labelOf(d.modelId), d.onlyModel)
        is StartLoad.Decision.NoFallback -> LoadNote.Crashed(d.blockedId, labelOf(d.blockedId), null, d.onlyModel)
        else -> null
    }

    private fun crashNote(c: team.sopa.boar.engine.models.LoadGuard.Crash): LoadNote.Crashed {
        val answering = services.answerModel()?.takeIf { it.id != c.crashedModelId }
        val only = services.installed(AssetKind.LLM).none { it.id != c.crashedModelId }
        val label = services.catalog.byId(c.crashedModelId)?.label?.let(::shortLabel) ?: c.crashedLabel
        return LoadNote.Crashed(c.crashedModelId, label, answering?.label?.let(::shortLabel), only)
    }

    private fun labelOf(modelId: String) = services.catalog.byId(modelId)?.label?.let(::shortLabel) ?: modelId

    /** A question asked while the memory note's model wasn't loaded: sent once the user loads it. */
    @Volatile private var heldQuestion: String? = null

    /** The note a question gets instead of an answer: the user's model may not fit, or closed the app last time. */
    private fun gateNote(): LoadNote? {
        val d = services.startDecision()
        val id = when (d) {
            is StartLoad.Decision.AskRisky -> d.modelId
            is StartLoad.Decision.NoFallback -> d.blockedId
            else -> return null
        }
        // Already loaded in this process (picked in the Model Catalog): it answers.
        val file = services.catalog.byId(id)?.filename
        if (file != null && services.host.residentFilename() == file && services.host.current() != null) return null
        return noteFor(d)
    }

    /** The memory note's "Load anyway": loads the model now, and at start from now on without asking. */
    fun loadAnyway(modelId: String) {
        services.rememberLoadAnyway(modelId)
        userLoad(modelId)
    }

    /** The crash note's "Try again with less memory": loads the model now, keeping the mapped file (LoadGuard). */
    fun retryWithLessMemory(modelId: String) {
        services.loadGuard.retryMapped(modelId)
        // The user's call: a model that may not fit isn't asked about again at start.
        if (services.catalog.byId(modelId)?.let(services::isRisky) == true) services.rememberLoadAnyway(modelId)
        userLoad(modelId)
    }

    /** The note's "Model Catalog" / "Pick another model": the caller opens the catalog. */
    fun openedCatalog() {
        heldQuestion = null
        _state.update { it.copy(loadNote = null) }
    }

    private fun userLoad(modelId: String) {
        val note = _state.value.loadNote
        val held = heldQuestion.also { heldQuestion = null }
        _state.update { it.copy(loadNote = null, loadingKey = "chatScreen.mountingWeights") }
        val previous = preloading
        preloading = safeScope.launch(Dispatchers.Default) {
            previous?.join()
            val loaded = runCatching { services.host.load(modelId) }.getOrNull() != null
            withContext(Dispatchers.Main) {
                refreshModel()
                // A load that returned without a model (not a closed app): the note comes back, so the user can still choose.
                _state.update { it.copy(loadingKey = null, loadNote = if (loaded) null else note) }
                if (loaded && held != null) send(held)
            }
        }
    }

    private fun refreshTone() {
        val id = services.settings.personalityId ?: services.personalities.defaultId
        val custom = BoarServices.SHOW_CUSTOM_STYLE && services.settings.customSystemPrompt.orEmpty().isNotBlank()
        val tones = services.personalities.personalities.filter { it.id != "custom" || custom }.map { it.id to it.icon }
        _state.update { it.copy(personalityId = id, toneIcon = services.personalities.get(id).icon, tones = tones) }
    }

    /** The header sheet: answer in this style from the next question on. */
    fun setTone(id: String) {
        services.settings.personalityId = id
        refreshTone()
    }

    /** Back from settings: the tone, Deep Research and model may have changed. */
    fun refreshSettings() {
        refreshTone()
        refreshModel()
        _state.update { it.copy(deepResearch = services.settings.deepResearchMode, technicalDetails = services.settings.showLiveStats) }
        // The open chat was erased (Clear all chat history, or a forgotten PIN): start a new one.
        val open = _state.value.sessionId
        if (open != null && services.chats.listSessions().none { it.id == open }) newChat()
    }

    fun showToast(key: String) = _state.update { it.copy(toast = team.sopa.boar.ui.I18n.t(getApplication(), key)) }
    fun hideToast() = _state.update { it.copy(toast = null) }

    /** ONLINE / OFFLINE in the header (connectivity.pure.ts isOnline: connected and the internet not known unreachable). */
    private fun watchConnectivity(app: Application) {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        // connectivity.pure.ts isOnline: connected and reachable (Android's validated internet), not just a network.
        fun online(caps: android.net.NetworkCapabilities?) = caps != null &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _state.update { it.copy(online = online(cm.getNetworkCapabilities(cm.activeNetwork))) }
        runCatching {
            // The callback's own network and capabilities: re-reading activeNetwork in onLost can still return the
            // network just lost (airplane mode showed ONLINE).
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { _state.update { it.copy(online = online(caps)) } }
                override fun onLost(network: android.net.Network) { _state.update { it.copy(online = false) } }
            })
        }
    }

    /** After setup or a model change: the active model's name, or that there's none. */
    fun refreshModel() {
        val m = services.answerModel()
        val lang = getApplication<Application>().resources.configuration.locales[0]?.language
        val installed = services.catalog.corpusCatalog.filter { services.fileOf(it).isFile }.map { it.id }
        // shortModelLabel: the catalog label without its parenthesis ("Qwen3-4B-Instruct-2507").
        val label = m?.label?.let(::shortLabel)
        // Fewer than 3 tested on this model (a Hugging Face model has none; Qwen2.5 1.5B has 1 in Portuguese): fill from
        // the ideas tested on the other models, same language, only for packs on the phone.
        val own = suggestionData.forModel(m?.id, lang, installed)
        val tested = if (own.size >= 3) own else (own + suggestionData.validation.byModel.keys.flatMap { suggestionData.forModel(it, lang, installed) }).distinct()
        // Plus the short questions of BOAR's own 17-question evaluation, so each new chat shows a different few.
        ideaPool = (tested + BENCH_IDEAS).distinct()
        _state.update { it.copy(modelLabel = label, noModel = m == null, suggestions = pickIdeas()) }
        refreshModelChoices()
        // A note that no longer holds (the model was loaded, unblocked or replaced) goes away.
        val note = _state.value.loadNote
        val holds = when (note) {
            null -> true
            is LoadNote.Crashed -> services.loadGuard.isBlocked(note.modelId)
            is LoadNote.Risky -> services.startDecision().let { it is StartLoad.Decision.AskRisky && it.modelId == note.modelId }
        }
        if (!holds) _state.update { it.copy(loadNote = null) }
    }

    /** shortModelLabel: the catalog label without its parenthesis ("Qwen3-4B-Instruct-2507"). */
    private fun shortLabel(label: String): String =
        label.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { label.trim() }

    fun dismissLoadNote() {
        heldQuestion = null
        _state.update { it.copy(loadNote = null) }
    }

    fun setDeepResearch(on: Boolean) {
        // Not with Sources = My files: its write-up isn't held to the files (exp/dr-files-only, Oct 23: C lost 3 of 8 answers).
        if (on && _state.value.sourcesOwn) return
        services.settings.deepResearchMode = on
        _state.update { it.copy(deepResearch = on) }
    }

    // --- Answer options (the composer's one sheet: Style, Model, Sources, Deep Research) ---------------------------------

    private val optionPrefs get() = getApplication<Application>().getSharedPreferences("answer_options", android.content.Context.MODE_PRIVATE)

    /** Every search of an answer goes through here, so Answer options → Sources reaches Deep Research and plans too. */
    private val retrieve: (String, Int) -> List<team.sopa.boar.engine.rag.Chunk> =
        { query, k -> services.retriever.retrieve(query, k, sources = sourceFilter()) }

    /** The search's sources from Answer options → Sources: the packs left out by id, so a pack installed later is searched. */
    private fun sourceFilter(): team.sopa.boar.engine.rag.SourceFilter {
        val s = _state.value
        if (s.sourcesOwn) return team.sopa.boar.engine.rag.SourceFilter.OWN_ONLY
        val off = s.sourcesOff
        if (off.isEmpty()) return team.sopa.boar.engine.rag.SourceFilter.ALL
        val packsOff = off - SourceRow.FILES - SourceRow.LIBRARY
        val f = team.sopa.boar.engine.rag.SourceFilter(
            seed = SourceRow.LIBRARY !in off, own = SourceRow.FILES !in off,
            packs = if (packsOff.isEmpty()) null else services.packs.map { it.id }.filter { it !in packsOff }.toSet(),
        )
        // Nothing left (the only kept pack was removed since): everything, never an empty search.
        return if (!f.seed && !f.own && f.packs?.isEmpty() == true) team.sopa.boar.engine.rag.SourceFilter.ALL else f
    }

    private val packPreviews by lazy {
        runCatching { getApplication<Application>().assets.open("boar-data/pack-previews.json").bufferedReader().use { it.readText() } }
            .map(team.sopa.boar.setup.KnowledgeCatalog::parsePreviews).getOrDefault(emptyMap())
    }

    /**
     * Answer options → Sources' checklist: the user's files, the built-in library (when its seed chunks are indexed) and
     * every installed pack, named and counted as in Knowledge. Off the main thread: it opens the packs.
     */
    fun refreshSources() {
        safeScope.launch(Dispatchers.IO) {
            val essentials = services.installed(team.sopa.boar.engine.models.AssetKind.CORPUS)
                .filter { it.id in team.sopa.boar.setup.KnowledgeCatalog.WIKI_ESSENTIALS_ASSETS }
            val rows = buildList {
                add(SourceRow(SourceRow.FILES, "design.options.sourcesFiles", "", null))
                if (services.knowledgeBase.seedChunkCount() > 0) add(SourceRow(SourceRow.LIBRARY, "clean.onboarding.pack.wikiEssentials.name", "",
                    team.sopa.boar.setup.KnowledgeCatalog.mergePreviews(essentials.mapNotNull { packPreviews[it.id] })?.articles))
                services.packs.forEach { p ->
                    val asset = services.catalog.byId(p.id)
                    add(SourceRow(p.id, team.sopa.boar.setup.KnowledgeCatalog.packNameKey(p.id), asset?.label ?: p.id.removePrefix("local-"), packPreviews[p.id]?.articles))
                }
            }
            _state.update { it.copy(sourceRows = rows) }
            // Nothing ticked any more (the packs kept were removed): back to Everything.
            val s = _state.value
            if (!s.sourcesOwn && s.sourceRows.isNotEmpty() && s.sourcesChecked.isEmpty()) setSourcesOwn(false)
        }
    }

    /**
     * Answer options → Sources' quick choices: Only my files (true) searches only the documents the user added, with
     * files-only answers; Everything (false) every source. Kept across launches.
     */
    fun setSourcesOwn(own: Boolean) {
        saveSources(own, emptySet())
        // My files answers through the plain pipeline only, so Deep Research goes off rather than showing on and not running.
        if (own && _state.value.deepResearch) setDeepResearch(false)
    }

    /**
     * Ticks or unticks one checklist row. The last ticked row can't be unticked (the sheet disables it); all ticked is
     * Everything, only Your files is Only my files, anything else filters the search with the normal answer.
     */
    fun toggleSource(id: String) {
        val s = _state.value
        val all = s.sourceRows.map { it.id }.toSet()
        if (id !in all) return
        val next = if (id in s.sourcesChecked) s.sourcesChecked - id else s.sourcesChecked + id
        when {
            next.isEmpty() -> return
            next == all -> setSourcesOwn(false)
            next == setOf(SourceRow.FILES) -> setSourcesOwn(true)
            else -> saveSources(false, all - next)
        }
    }

    private fun saveSources(own: Boolean, off: Set<String>) {
        optionPrefs.edit().putBoolean(SOURCES_OWN, own).putStringSet(SOURCES_OFF, off).apply()
        _state.update { it.copy(sourcesOwn = own, sourcesOff = off) }
    }

    // Saved before this rule (both on): My files wins, as setSourcesOwn would have decided.
    init {
        if (_state.value.sourcesOwn && _state.value.deepResearch) setDeepResearch(false)
        // A partial selection: the Sources row's "4 of 7" needs the list.
        if (_state.value.sourcesOff.isNotEmpty()) refreshSources()
    }

    /** The installed answer models, and the default: the one setup recommends for this phone's memory, if it is here. */
    private fun refreshModelChoices() {
        val installed = services.installed(team.sopa.boar.engine.models.AssetKind.LLM)
        val rec = team.sopa.boar.engine.models.SetupModel.recommended(services.catalog.answerModels, services.totalRamBytes)?.id
        val default = rec?.takeIf { id -> installed.any { it.id == id } } ?: installed.minByOrNull { it.sizeBytes }?.id
        val choices = installed.sortedBy { it.sizeBytes }.map { ModelChoice(it.id, it.shortName, it.sizeBytes, it.id == rec) }
        _state.update { it.copy(models = choices, activeModelId = services.answerModel()?.id, defaultModelId = default) }
    }

    /**
     * Answer options → Model: answer with [id] from now on. Same steps as Models → Use (the user's pick confirms a model
     * that closed the app before, or may not fit): the next model loads right away, with the loading line meanwhile.
     */
    fun setModel(id: String) {
        val a = services.catalog.byId(id) ?: return
        if (_state.value.switchingModelId != null || id == _state.value.activeModelId || job?.isActive == true) return
        _state.update { it.copy(switchingModelId = id, loadingKey = "chatScreen.mountingWeights") }
        safeScope.launch(Dispatchers.Default) {
            services.settings.setActiveModelId("llm", id)
            services.loadGuard.confirm(id)
            if (services.isRisky(a)) services.rememberLoadAnyway(id)
            runCatching { services.host.unload(); services.activeSession() }
            _state.update { it.copy(switchingModelId = null, loadingKey = null) }
            refreshModel()
        }
    }

    /** Answer options → Reset to defaults: the default style, all sources, the recommended model, Deep Research off. */
    fun resetOptions() {
        setTone(services.personalities.defaultId)
        setSourcesOwn(false)
        setDeepResearch(false)
        _state.value.defaultModelId?.let { if (it != _state.value.activeModelId) setModel(it) }
    }

    private fun pickIdeas() = ideaPool.shuffled().take(IDEAS_SHOWN)

    fun newChat() {
        if (job?.isActive == true) return
        _state.update { it.copy(sessionId = null, messages = emptyList(), error = null, suggestions = pickIdeas()) }
    }

    /** The drawer's 🗑️: the chat and its messages; the open one becomes a new chat. */
    fun deleteSession(id: String) {
        // Not the chat still being answered: its answer would be saved into a deleted session.
        if (job?.isActive == true && _state.value.sessionId == id) return
        // A database error (disk full, a damaged file) is told in a toast; uncaught here it would close the app.
        safeScope.launch(Dispatchers.IO) { runCatching { services.chats.deleteSession(id) }.onFailure { showToast("native.chat.dbFailed") } }
        if (_state.value.sessionId == id) newChat()
    }

    fun openSession(id: String) {
        if (job?.isActive == true) return
        safeScope.launch {
            val msgs = runCatching {
                withContext(Dispatchers.IO) { services.chats.messages(id) }.map { r ->
                    UiMessage(r.id, r.role, r.text, sourcesFromMeta(r.meta), r.feedback, receipt = receiptFromMeta(r.meta), error = errorFromMeta(r.meta))
                }
            }.getOrElse { showToast("native.chat.dbFailed"); return@launch }
            _state.update { it.copy(sessionId = id, messages = msgs, error = null) }
        }
    }

    fun stop() {
        stopRequested = true
        if (_state.value.answeringId != null) _state.update { it.copy(stopping = true) }
        services.host.current()?.cancel()
    }

    /** Asks the question of [answerId] again (an answer that came back empty). */
    fun retry(answerId: String) {
        val messages = _state.value.messages
        val i = messages.indexOfFirst { it.id == answerId }
        val q = messages.take(i.coerceAtLeast(0)).lastOrNull { it.role == "user" }?.text ?: return
        send(q)
    }

    /** Asks [answerId]'s question again with a higher token limit, for this answer only (the setting stays as it is). */
    fun redoWithLimit(answerId: String, limit: Int) {
        val messages = _state.value.messages
        val i = messages.indexOfFirst { it.id == answerId }
        val q = messages.take(i.coerceAtLeast(0)).lastOrNull { it.role == "user" }?.text ?: return
        send(q, limit)
    }

    /** [limit] overrides the answer's token limit (the "Allow more and redo" note); null uses the setting. */
    fun send(query: String, limit: Int? = null) {
        val q = query.trim()
        if (q.isEmpty() || job?.isActive == true) return
        // The user's model may not fit, or closed the app last time: the note asks first, and the question waits.
        gateNote()?.let { n ->
            heldQuestion = q
            _state.update { it.copy(loadNote = n) }
            return
        }
        team.sopa.boar.app.Haptics.sent(getApplication())
        stopRequested = false
        routedModelId = null
        // Started while BOAR is on screen (Android allows it only then); keeps BOAR alive if the user leaves mid-answer.
        team.sopa.boar.app.AnswerService.start(getApplication())
        job = safeScope.launch(Dispatchers.Default) {
            stopBackground()
            val chats = services.chats
            val isNewSession = _state.value.sessionId == null
            val prior = _state.value.messages.filter { it.text.isNotEmpty() }
            val userId = chats.newId()
            val answerId = chats.newId()
            // A failed write (disk full) shows as a failed answer instead of closing the app.
            val sessionId = try {
                // The question stands in as the title until the model writes one.
                (_state.value.sessionId ?: chats.createSession().also { s -> chats.setTitle(s.id, provisionalTitle(q)) }.id)
                    .also { chats.addMessage(it, "user", q, userId) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(error = e.message ?: e.toString(), messages = it.messages + UiMessage(userId, "user", q) + UiMessage(answerId, "assistant", "")) }
                return@launch
            }
            _state.update {
                it.copy(sessionId = sessionId, error = null, answeringId = answerId, activity = Activity.Retrieving, liveTokPerSec = null,
                    messages = it.messages + UiMessage(userId, "user", q) + UiMessage(answerId, "assistant", ""))
            }
            val settings = services.settings
            // Health and first aid answer in Succinct even when Detailed is picked: a sentence-level audit of 15 Detailed
            // answers (r4to, Oct 17) found 20 % of claims wrong, 5 of them safety errors ("a first-degree burn is painless").
            val chosenStyle = settings.personalityId ?: services.personalities.defaultId
            // Health and seed-phrase questions answer short even under Detailed: a padded detail there can hurt (r4to's audits).
            // A follow-up in such a chat too ("and for how long?" after a burn question).
            val shortReason = team.sopa.boar.engine.routing.ShortAnswer.reason(chosenStyle, q, prior.lastOrNull { it.role == "user" }?.text)
            val style = if (shortReason != null) "succinct" else chosenStyle
            // A small answer model (the 1.5B) gets Detailed without D4's "Always do what the user asks" (r4to, Oct 18).
            val smallModel = (services.answerModel()?.sizeBytes ?: Long.MAX_VALUE) < team.sopa.boar.engine.answer.Personalities.SMALL_MODEL_BYTES
            val (systemPrompt, styleReminder) = services.personalities.promptFor(style, settings.customSystemPrompt, smallModel)
            val history = ConversationHistory(
                summary = runCatching { chats.listSessions().firstOrNull { it.id == sessionId }?.summary }.getOrNull(),
                // Within a token budget: earlier answers cut to their opening sentences (HistoryBudget).
                turns = HistoryBudget.budget(prior.takeLast(VERBATIM_MESSAGE_COUNT).map { ConversationTurn(it.role, if (it.role == "assistant") AnswerText.stripThinking(it.text) else it.text) }),
            )
            val startAt = System.nanoTime()
            qaMark("answer.start")
            var metrics: GenerationMetrics? = null
            var retrievalMs: Double? = null
            val text = StringBuilder()
            var sources: List<Chunk> = emptyList()
            var reusedSources: List<Source>? = null
            // The answer a meta follow-up ("summary", "continue") is written from: the last one with text.
            val lastAnswer = prior.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }
            // Every answered question so far, oldest first: what "summarize our conversation" is written from.
            // How this answer is written, for the receipt's Mode row (set where the path is chosen below).
            var path: String? = null
            val conversation = prior.zipWithNext().filter { (u, a) -> u.role == "user" && a.role == "assistant" && a.text.isNotBlank() }
                .map { (u, a) -> DeepResearch.Companion.Exchange(u.text, AnswerText.stripThinking(a.text)) }
            var timedOut = false
            // Sources = My files, as this question started: answers only from the user's files (PersonalFiles).
            val filesOnly = _state.value.sourcesOwn
            val notFoundLine = team.sopa.boar.ui.I18n.t(getApplication(), "design.chat.filesNotFound")
            // The fixed line was written without the model: no receipt (no speed to show).
            var notFound = false
            var firstAt = 0L
            var tokens = 0
            // The limit the answer could hit, set once the model is loaded; the adaptive router picks its own.
            var limited: Int? = null
            // Saved as it's written, marked interrupted until it ends: if Android closes BOAR mid-answer (Xiaomi did 10-35 s
            // after leaving, Oct 19), the chat reopens with what was written and the note, not an empty bubble.
            var savedAt = 0L
            fun savePartial(force: Boolean = false) {
                val now = System.nanoTime()
                if (!force && now - savedAt < PARTIAL_SAVE_NS) return
                savedAt = now
                runCatching {
                    chats.upsertMessage(sessionId, "assistant", text.toString(), answerId)
                    chats.setMessageMeta(answerId, metaOf(emptyList(), null, team.sopa.boar.engine.UserErrors.INTERRUPTED_KEY))
                }
            }
            savePartial(force = true)
            fun onPiece(piece: String) {
                tokens++
                if (firstAt == 0L) { firstAt = System.nanoTime(); qaMark("answer.first") }
                if (tokens == 100 || tokens == 400) qaMark("answer.t$tokens")
                text.append(piece)
                savePartial()
                val elapsed = (System.nanoTime() - firstAt) / 1e9
                _state.update { s ->
                    s.copy(activity = Activity.Generating, liveTokPerSec = if (elapsed > 0.5) tokens / elapsed else s.liveTokPerSec,
                        messages = s.messages.map { if (it.id == answerId) it.copy(text = text.toString()) else it })
                }
            }
            try {
                val session = services.activeSession()
                qaMark("answer.loaded")
                // Read after the load: Auto fits the length to the context the model was opened with.
                val budget = (limit ?: settings.answerTokens(services.host.openedContext)).let {
                    // A thank-you or greeting: Succinct's length in any style. Not the router's 128: a model that thinks first
                    // (LFM2.5) would spend it all on reasoning.
                    if (team.sopa.boar.engine.routing.ShortAnswer.isSmallTalk(q)) minOf(it, team.sopa.boar.engine.settings.AppSettings.AUTO_MAX_TOKENS) else it
                }
                limited = budget
                // Small talk never starts a Deep Research or the router (AnswerPath): "hi" took minutes on a big model.
                // My files answers through the plain pipeline only: Deep Research's and the router's write-ups have no
                // files-only prompt or not-found line, so they could answer from memory. A recap of this chat still runs.
                val way = team.sopa.boar.engine.routing.AnswerPath.of(_state.value.deepResearch, aboutConversation(q, history, conversation),
                    settings.adaptiveRoutingEnabled, team.sopa.boar.engine.routing.ShortAnswer.isSmallTalk(q)).let {
                        if (filesOnly && (it == team.sopa.boar.engine.routing.AnswerPath.RESEARCH || it == team.sopa.boar.engine.routing.AnswerPath.ADAPTIVE))
                            team.sopa.boar.engine.routing.AnswerPath.PIPELINE else it
                    }
                // The preload's warm prefill holds the model's lock: waited for only when it fills this question's prefix
                // (the plain path, same style and Sources), else stopped now instead of making the question wait for it.
                if (way != team.sopa.boar.engine.routing.AnswerPath.PIPELINE ||
                    !team.sopa.boar.engine.answer.WarmPrefix.reusable(warmKey, team.sopa.boar.engine.answer.WarmPrefix.Key(systemPrompt, filesOnly))) cancelWarm()
                if (stopRequested) {
                    // Stop pressed while the model loaded: the load can't be interrupted, the answer can be skipped.
                } else if (session == null) {
                    _state.update { it.copy(noModel = true, error = team.sopa.boar.engine.UserErrors.NO_MODEL) }
                } else if (way == team.sopa.boar.engine.routing.AnswerPath.RESEARCH || way == team.sopa.boar.engine.routing.AnswerPath.RECAP) {
                    val research = way == team.sopa.boar.engine.routing.AnswerPath.RESEARCH
                    path = if (research) "deep" else "chat"
                    val r = DeepResearch(SessionEngine(session, settings.engineTweaks.thinkingEnabled), retrieve, settings.engineTweaks::stageTimeoutMs, settings.engineTweaks::writeUpTimeoutMs).run(
                        q, systemPrompt, history, budget,
                        onProgress = { p -> _state.update { it.copy(activity = if (research) Activity.Researching(p.stage, p.subQuestionIndex?.plus(1), p.subQuestionCount) else Activity.Thinking) } },
                        onToken = ::onPiece,
                        shouldStop = { stopRequested },
                        previousAnswer = lastAnswer?.text?.let(AnswerText::stripThinking),
                        conversation = conversation,
                    )
                    sources = r.citations
                    // Written from the last answer: its numbers [n] are that answer's sources.
                    if (r.fromPreviousAnswer) reusedSources = lastAnswer?.sources
                    timedOut = r.timedOut
                    metrics = r.metrics
                } else if (way == team.sopa.boar.engine.routing.AnswerPath.ADAPTIVE) {
                    // The router answers within the same budget, so a cut answer gets its note and "Allow more".
                    path = "adaptive"
                    val r = adaptive(q, systemPrompt, styleReminder, history, budget, ::onPiece)
                    sources = r.first
                    timedOut = r.second
                    metrics = r.third
                } else {
                    val pipeline = AnswerPipeline(session, retrieve, { Classify.classifyTask(it).value }, { Classify.isRetrievalIrrelevant(TaskType.of(it)) })
                    val base = AnswerRequest(q, systemPrompt, styleReminder, budget, history, enableThinking = settings.engineTweaks.thinkingEnabled)
                    val req = if (filesOnly) PersonalFiles.request(base, services.host.openedContext ?: AnswerPipeline.FALLBACK_CONTEXT, notFoundLine) else base
                    // Stop pressed while this waited for the model (behind the warm prefill) is honoured inside the lock.
                    pipeline.answer(req, shouldStop = { stopRequested }).collect { e ->
                        when (e) {
                            is AnswerEvent.Stage -> _state.update { it.copy(activity = when (e.stage) { AnswerStage.RETRIEVING -> Activity.Retrieving; AnswerStage.THINKING -> Activity.Thinking; AnswerStage.GENERATING -> Activity.Generating }) }
                            is AnswerEvent.Sources -> sources = e.chunks
                            is AnswerEvent.Piece -> onPiece(e.text)
                            is AnswerEvent.Done -> { metrics = e.metrics; retrievalMs = e.retrievalMs; notFound = e.notFound }
                        }
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
            // Ended without a word, no error and not stopped: say so (with Try again) instead of an empty bubble.
            if (text.isBlank() && !stopRequested && _state.value.error == null) _state.update { it.copy(error = team.sopa.boar.engine.UserErrors.EMPTY_ANSWER) }
            val passages = sources.map { Source(it.title, it.source, it.relevance, it.similarity, it.docId, it.body) }
            // One source per article, best first; the answer's numbers follow (a reused list is already grouped).
            val grouped = SourceGroups.group(passages, { SourceGroups.articleKey(it.source, it.docId, it.title) }, { it.similarity ?: it.relevance }) { g ->
                g.first().copy(similarity = g.mapNotNull { it.similarity }.maxOrNull(), relevance = g.mapNotNull { it.relevance }.maxOrNull(),
                    body = g.joinToString("\n…\n") { it.body })
            }
            val shown = reusedSources ?: grouped.items
            if (reusedSources == null) SourceGroups.renumber(text.toString(), grouped.renumber).let { if (it != text.toString()) { text.setLength(0); text.append(it) } }
            // My files: a not-found line the model opened with goes when a cited answer from the files follows it (the
            // instruction's English line, or the chat's own language's).
            if (filesOnly) PersonalFiles.dropHedge(PersonalFiles.dropHedge(text.toString()), notFoundLine).let { if (it != text.toString()) { text.setLength(0); text.append(it) } }
            val stopped = stopRequested
            // The failure line goes with this answer, so it's still there after the next question and when the chat opens again.
            val errorKey = _state.value.error?.takeIf { !stopped }?.let(team.sopa.boar.engine.UserErrors::chatKey)
            val receipt = receiptOf(startAt, firstAt, tokens, metrics, retrievalMs)?.takeIf { !notFound }?.let { r ->
                val (task, checked) = if (path == "adaptive") (lastAdaptive ?: (null to false)) else (null to false)
                r.copy(style = style, path = path, task = task, checked = checked, styleNote = shortReason?.takeIf { it != team.sopa.boar.engine.routing.ShortAnswer.SMALL_TALK })
            }
            val cutAt = limited?.takeIf { !stopped && !timedOut && text.isNotBlank() && AnswerLimit.reached(metrics?.generatedTokens ?: tokens, it) }
            val nextLimit = cutAt?.let { AnswerLimit.next(it, services.personalities.maxTokensOptions, services.host.openedContext) }
            try {
                chats.upsertMessage(sessionId, "assistant", text.toString(), answerId)
                // Always written: it replaces the interrupted mark of the partial saves.
                chats.setMessageMeta(answerId, metaOf(shown, receipt, errorKey))
                afterAnswer(sessionId, isNewSession, stopped, prior + UiMessage(userId, "user", q) + UiMessage(answerId, "assistant", text.toString()), systemPrompt, sources.isNotEmpty(), filesOnly)
                chats.prune(settings.maxSavedSessions)
            } catch (e: Exception) {
                // Not saved (disk full): the answer stays on screen with the error under it; the app keeps running.
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
            qaMark("answer.done tokens=$tokens stopped=$stopped chars=${text.length}")
            if (!stopped && text.isNotEmpty()) team.sopa.boar.app.Haptics.finished(getApplication())
            _state.update { s ->
                s.copy(answeringId = null, answeringModel = null, activity = null, stopping = false,
                    messages = s.messages.map { if (it.id == answerId) it.copy(text = text.toString(), sources = shown, stopped = stopped, timedOut = timedOut, receipt = receipt, cutAt = cutAt, nextLimit = nextLimit, error = errorKey) else it })
            }
        }
        // However the answer ends (done, stopped, failed, or cancelled before it started), the notification goes with it.
        job?.invokeOnCompletion { team.sopa.boar.app.AnswerService.stop(getApplication()) }
    }

    /**
     * Stops a title or summary still being written, as ChatScreen.tsx does (cancelBackgroundTask), instead of making the
     * question wait for it: a summary reads every older turn first, which on a slow phone held the next question for tens
     * of seconds. Cancelling the job keeps one that hasn't started from starting, and makes one that has throw instead of
     * returning, so nothing half-written is saved; the native cancel stops the prompt reading or writing in progress. A
     * stopped title keeps the question as the chat's title; a stopped summary is written after a later answer.
     */
    private suspend fun stopBackground() {
        val running = background?.takeIf { it.isActive } ?: return
        running.cancel()
        services.host.current()?.cancel()
        running.join()
    }

    /**
     * As ChatScreen.tsx after an answer: a new chat gets a model-written title (autoGenerateTitles); a long one gets its
     * older turns summarized (autoSummarize, past historyTurnThreshold), in the background.
     */
    private fun afterAnswer(sessionId: String, isNewSession: Boolean, stopped: Boolean, all: List<UiMessage>, systemPrompt: String?, hadSources: Boolean, filesOnly: Boolean) {
        if (stopped) return
        // In the background the model is freed now; the question stays the title until BOAR is back on screen.
        if (!services.isInForeground) {
            deferredAfterAnswer = { afterAnswer(sessionId, isNewSession, false, all, systemPrompt, hadSources, filesOnly) }
            return
        }
        val s = services.settings
        val session = services.host.current() ?: return
        // Reads the model's info: throws once the model was unloaded (switched in Models or Settings) after the answer.
        val gen = runCatching { SessionEngine(session) }.getOrNull() ?: return
        if (isNewSession && s.autoGenerateTitles) {
            background = safeScope.launch(Dispatchers.Default) {
                runCatching { services.chats.setTitle(sessionId, Summarize.title(gen, all.first { it.role == "user" }.text)) }
                runCatching { warmPrefix(session, systemPrompt, hadSources, filesOnly) }
            }
        } else if (s.autoSummarize && all.size / 2 > s.historyTurnThreshold) {
            val older = all.dropLast(VERBATIM_MESSAGE_COUNT)
            if (older.isEmpty()) return
            val previous = services.chats.listSessions().firstOrNull { it.id == sessionId }?.summary
            background = safeScope.launch(Dispatchers.Default) {
                runCatching {
                    // Bounded like the answer's history: a whole detailed chat (thousands of tokens) took tens of seconds to read.
                    val turns = HistoryBudget.budget(older.map { ConversationTurn(it.role, if (it.role == "assistant") AnswerText.stripThinking(it.text) else it.text) })
                    services.chats.setSummary(sessionId, Summarize.summary(gen, turns, previous))
                }
                runCatching { warmPrefix(session, systemPrompt, hadSources, filesOnly) }
            }
        }
    }

    /**
     * Back on screen: the title or summary skipped in the background, once the model is loaded again (as the chat's
     * preload, only when it may load on its own). A question asked meanwhile stops it, and the question stays the title.
     */
    private fun resumeAfterAnswer() {
        val pending = deferredAfterAnswer ?: return
        deferredAfterAnswer = null
        if (job?.isActive == true || services.startDecision() !is StartLoad.Decision.Load) return
        background = safeScope.launch(Dispatchers.Default) {
            if (runCatching { services.activeSession() }.getOrNull() != null) pending()
        }
    }

    /**
     * A title or summary left its own prompt in the KV cache, so the next question would read the whole system prompt
     * again (about 230 tokens, 8 s on a 4B). Prefill it back: the next answer reuses it (#72's warm-up).
     */
    private suspend fun warmPrefix(session: team.sopa.boar.engine.LlamaSession, systemPrompt: String?, withSources: Boolean, filesOnly: Boolean) {
        // The variant the last answer used (with or without sources, My files or all): a follow-up most often matches it.
        val (head, plain) = team.sopa.boar.engine.rag.RagPure.answerPromptPrefix(systemPrompt, withSources, filesOnly)
        val self = kotlin.coroutines.coroutineContext[Job]
        val prompt = if (session.info.optBoolean("hasChatTemplate", false))
            session.formatChat(listOf(team.sopa.boar.engine.ChatMessage("system", head)), services.settings.engineTweaks.thinkingEnabled).prompt
        else plain
        // Cancelled while it waited for the lock: skipped, not run after a native cancel that came too early. shouldStop
        // is first asked once the lock is held, which tells the background hook the prefill is the one in native code.
        try {
            session.generate(prompt, team.sopa.boar.engine.Sampling(nPredict = 0), shouldStop = { warmInside = true; self?.isActive == false }).collect {}
        } finally { warmInside = false }
    }

    /** The warm prefill holds the model's lock (set from its first shouldStop check, cleared when it ends). */
    @Volatile private var warmInside = false

    /** runAdaptiveChat: plan with the installed models and the user's routing settings, then execute. */
    /** The last adaptive plan's task type and whether it had a verify step, for the receipt. */
    @Volatile private var lastAdaptive: Pair<String, Boolean>? = null
    /** The model the adaptive plan's generate step runs, for the header and the receipt; null on the other paths. */
    @Volatile private var routedModelId: String? = null

    private suspend fun adaptive(q: String, systemPrompt: String, styleReminder: String?, history: ConversationHistory, budget: Int, onPiece: (String) -> Unit): Triple<List<Chunk>, Boolean, GenerationMetrics?> {
        lastAdaptive = null  // a plan that fails mustn't leave the previous answer's task on this receipt
        val s = services.settings
        val statuses = services.catalog.models.filter { it.kind == AssetKind.LLM }.map { Profiles.AvailableModel(it, services.fileOf(it).isFile) }
        val preset = s.routingPreset?.let { runCatching { RoutingPreset.of(it) }.getOrNull() } ?: RoutingPreset.DEFAULT
        val overrides = s.modelRoleAssignments().mapNotNull { (k, v) -> runCatching { ModelRole.of(k) to v }.getOrNull() }.toMap()
        val plan = AdaptivePlan.plan(q, statuses, preset, overrides, services.answerModel()?.id ?: s.activeModelId("llm"), totalRamBytes(), budget)
        lastAdaptive = plan.taskType.value to plan.plan.steps.any { it.type == team.sopa.boar.engine.routing.RoutingStepType.VERIFY }
        val r = PlanExecutor(services.host, retrieve, { Classify.classifyTask(it).value }, timeoutFor = s.engineTweaks::stageTimeoutMs)
            .execute(plan.plan, PipelineInput(q, systemPrompt, styleReminder, history), onToken = onPiece, onStepStart = { step ->
                if (step.type == team.sopa.boar.engine.routing.RoutingStepType.GENERATE && step.modelId != null) {
                    routedModelId = step.modelId
                    val other = step.modelId.takeIf { it != services.answerModel()?.id }?.let(services.catalog::byId)?.let { shortLabel(it.label) }
                    _state.update { it.copy(answeringModel = other) }
                }
                _state.update { it.copy(activity = if (step.type.value == "retrieve") Activity.Retrieving else Activity.Thinking) }
            }, shouldStop = { stopRequested })
        if (r.answer.isBlank() && !stopRequested) error("adaptive routing produced no answer: ${r.warnings.joinToString()}")
        return Triple(r.citations, r.timedOut, r.metrics)
    }

    /** The phone's RAM (ram-monitor's getDeviceTotalRamBytes); 0 when unreadable. */
    private fun totalRamBytes(): Long = runCatching {
        val am = getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    /**
     * The answer's receipt, from the engine's own numbers (for Deep Research and adaptive routing, those of the step that
     * wrote the answer), else from the tokens as they streamed. None when nothing was generated.
     */
    private fun receiptOf(startAt: Long, firstAt: Long, streamed: Int, m: GenerationMetrics?, retrievalMs: Double?): Receipt? {
        if (firstAt == 0L || streamed == 0) return null
        val end = System.nanoTime()
        val wallRate = streamed / ((end - firstAt) / 1e9).coerceAtLeast(1e-3)
        return Receipt(
            totalMs = (end - startAt) / 1e6, ttftMs = (firstAt - startAt) / 1e6,
            tokPerSec = m?.decodeTokPerSec?.takeIf { it > 0 } ?: wallRate, tokens = m?.generatedTokens?.takeIf { it > 0 } ?: streamed,
            // The model that wrote it: the one adaptive routing picked, else the active one.
            model = (routedModelId?.let(services.catalog::byId) ?: services.activeModel())?.shortName, retrievalMs = retrievalMs, prefillMs = m?.prefillMs, contextTokens = m?.promptTokens,
        )
    }

    private fun receiptFromMeta(meta: String?): Receipt? = runCatching {
        Json.parseToJsonElement(meta ?: return null).jsonObject["receipt"]?.jsonObject?.let(Receipt::fromJson)
    }.getOrNull()

    private fun metaOf(sources: List<Source>, receipt: Receipt?, errorKey: String? = null): String = JsonObject(listOfNotNull("sources" to JsonArray(sources.map { s ->
        JsonObject(listOfNotNull(
            "title" to JsonPrimitive(s.title), s.source?.let { "source" to JsonPrimitive(it) }, s.relevance?.let { "relevance" to JsonPrimitive(it) },
            s.similarity?.let { "similarity" to JsonPrimitive(it) }, s.docId?.let { "docId" to JsonPrimitive(it) }, "body" to JsonPrimitive(s.body),
        ).toMap())
    }), receipt?.let { "receipt" to it.toJson() }, errorKey?.let { "error" to JsonPrimitive(it) }).toMap()).toString()

    private fun errorFromMeta(meta: String?): String? = runCatching {
        Json.parseToJsonElement(meta ?: return null).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun sourcesFromMeta(meta: String?): List<Source> = runCatching {
        Json.parseToJsonElement(meta ?: return emptyList()).jsonObject["sources"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Source(
                o["title"]!!.jsonPrimitive.content, o["source"]?.jsonPrimitive?.content, o["relevance"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                o["similarity"]?.jsonPrimitive?.content?.toDoubleOrNull(), o["docId"]?.jsonPrimitive?.content, o["body"]?.jsonPrimitive?.content ?: "",
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        /** Answer options → Sources = Only my files, in the "answer_options" preferences (the flag of the earlier All / My files choice). */
        const val SOURCES_OWN = "sourcesOwnOnly"
        /** Answer options → Sources: the ids left out (SourceRow ids), a string set; absent or empty = Everything. */
        const val SOURCES_OFF = "sourcesOff"
        /** Messages sent verbatim as history (ChatScreen.tsx VERBATIM_MESSAGE_COUNT). */
        const val VERBATIM_MESSAGE_COUNT = 6
        /** How often an answer being written is saved (savePartial). */
        const val PARTIAL_SAVE_NS = 3_000_000_000L

        /**
         * A message about the conversation itself, answered from it without a search: "summarize our conversation", or
         * "TL;DR" / "a summary please" after an answer, as Deep Research already did. "Summarize our conversation" covered
         * only the last three exchanges (X6, Oct 16). "continue" keeps the search: it asks for more than the chat holds.
         */
        fun aboutConversation(query: String, history: ConversationHistory, conversation: List<DeepResearch.Companion.Exchange>): Boolean =
            (DeepResearch.isConversationRecap(query) && conversation.any { !DeepResearch.isConversationRecap(it.question) && !DeepResearch.isSummaryFollowUp(it.question) }) ||
                (DeepResearch.isSummaryFollowUp(query) && DeepResearch.lastAnswered(query, history) != null)

        /** Prompt ideas shown in an empty chat, drawn again for each new chat. */
        const val IDEAS_SHOWN = 5

        /**
         * Short questions (two lines at most) from the evaluations: b1…b9 from BOAR's 17-question set (eval-set.json),
         * b10…b21 from the public BOAR Bench v0.1 items, without its trap, emergency, crypto and restaurant questions.
         * Their text is chat.suggestions.b* in the app's languages.
         */
        val BENCH_IDEAS = (1..21).map { "b$it" }
    }
}

/** Timing markers for the on-device fluidity scenarios (logcat tag BOARQA): debug and the local perf build only. */
internal fun qaMark(what: String) {
    if (team.sopa.boar.BuildConfig.DEBUG || team.sopa.boar.BuildConfig.BUILD_TYPE == "perf") android.util.Log.i("BOARQA", what)
}
