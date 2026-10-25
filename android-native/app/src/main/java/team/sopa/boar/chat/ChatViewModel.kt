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
import team.sopa.boar.engine.answer.SessionEngine
import team.sopa.boar.engine.answer.Summarize
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.StartLoad
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.ConversationTurn
import team.sopa.boar.engine.routing.Classify
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
    /** It ended at this token limit, not by itself (shown as a note). */
    val cutAt: Int? = null,
    /** Why it failed (a native.chat.error* key): stays under the answer after the next question and in the saved chat. */
    val error: String? = null,
)

/** What the assistant is doing for the message being answered. */
sealed interface Activity {
    data object Retrieving : Activity
    data object Thinking : Activity
    data object Generating : Activity
}

/** A note over the chat about the answer model and the phone's memory, with what the user can do about it. */
sealed interface LoadNote {
    val modelId: String
    val label: String

    /**
     * [label]'s last load closed the app (not enough memory) and it isn't loaded on its own. [onlyModel]: no other
     * model is installed.
     */
    data class Crashed(override val modelId: String, override val label: String, val onlyModel: Boolean) : LoadNote

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
    /** Advanced tweaks → Show technical details (AppSettings.showLiveStats). */
    val technicalDetails: Boolean = false,
    val error: String? = null,
    /** The answer model's memory note (StartLoad): it closed the app last time, or may not fit; with its actions. */
    val loadNote: LoadNote? = null,
    /** While the model loads or the corpus indexes: the line shown under the header (chatScreen.*). */
    val loadingKey: String? = null,
    /**
     * The answer model is loading (BoarServices.modelState): after the first-run tour's Skip the chat opens while the
     * install's background load runs, and says so until it ends; a question sent meanwhile waits for it.
     */
    val modelLoading: Boolean = false,
    /** A short message over the chat for about 2 s: a reused question. */
    val toast: String? = null,
    /** Stop was tapped and the answer hasn't ended yet (a step in progress finishes first). */
    val stopping: Boolean = false,
    /** Suggested question keys for the empty chat (chat.suggestions.*). */
    val suggestions: List<String> = emptyList(),
    /** Answer options → Sources = Only my files: the user's own files only (files-only answers, Retriever ownOnly). */
    val sourcesOwn: Boolean = false,
    /** Answer options → Sources: what the checklist lists (the user's files, the built-in library when indexed, each installed pack). */
    val sourceRows: List<SourceRow> = emptyList(),
    /** Sources left out of the search (ids of [sourceRows]); empty = Everything. Not read while [sourcesOwn]. */
    val sourcesOff: Set<String> = emptySet(),
) {
    /** Sources = Everything: every listed source searched (a pack installed later too). */
    val sourcesEverything: Boolean
        get() = !sourcesOwn && (if (sourceRows.isEmpty()) sourcesOff.isEmpty() else sourceRows.none { it.id in sourcesOff })

    /** The checklist's ticked rows. */
    val sourcesChecked: Set<String>
        get() = if (sourcesOwn) setOf(SourceRow.FILES) else sourceRows.map { it.id }.filter { it !in sourcesOff }.toSet()

    /** Anything the composer's options button must flag: sources other than the default. */
    val optionsChanged: Boolean
        get() = !sourcesEverything
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


/** The history title until the model writes one: the question trimmed, cut at a word with "…" when it runs past 60 characters. */
internal fun provisionalTitle(q: String): String {
    val t = q.trim().replace(Regex("\\s+"), " ")
    if (t.length <= 60) return t
    val cut = t.take(60)
    return (cut.substringBeforeLast(' ', cut).ifBlank { cut }).trimEnd(',', ';', ':', '-') + "…"
}

/**
 * The chat: a recap of the chat when asked for, the
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
        _state.update { it.copy(answeringId = null, activity = null, stopping = false, error = "generic") }
    }
    private val _state = MutableStateFlow(ChatState(technicalDetails = services.settings.showLiveStats,
        sourcesOwn = app.getSharedPreferences("answer_options", android.content.Context.MODE_PRIVATE).getBoolean(SOURCES_OWN, false),
        sourcesOff = app.getSharedPreferences("answer_options", android.content.Context.MODE_PRIVATE).getStringSet(SOURCES_OFF, null).orEmpty().toSet()))
    val state: StateFlow<ChatState> = _state
    private var job: Job? = null
    /** A chat title still being written; a new question stops it (one model, one generation). */
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
     * A title skipped because the answer ended with BOAR in the background, written when BOAR is back on
     * screen. Run then, it would hold the model past MIUI's freeze about 8 s after the answering service stops, and MIUI
     * force-stops a frozen BOAR still holding the model (F3, Oct 19, 17:46:30-40).
     */
    @Volatile private var deferredAfterAnswer: (() -> Unit)? = null
    private val onForeground: () -> Unit = { resumeAfterAnswer(); reloadOnReturn() }
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
    /** For Free memory when I leave BOAR: the model isn't unloaded while an answer, title or preload runs. */
    private val busy: () -> Boolean = { job?.isActive == true || background?.isActive == true || preloading?.isActive == true }

    init {
        refreshModel()
        viewModelScope.launch {
            services.modelState.collect { m -> _state.update { it.copy(modelLoading = m == BoarServices.ModelLoadState.LOADING) } }
        }
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
     * Loads the model when the chat opens, so the first answer doesn't wait for it. Not a model
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
     * Back on screen after Free memory when I leave BOAR unloaded the model (Auto: as soon as BOAR leaves): load it and
     * warm the instructions again, as the chat's preload does. Before, only the next question loaded it, and it also read
     * the ~250-token instructions again (2.7 s on the 1.5B, Pixel 8a). Not while an answer or a title (which
     * loads the model itself) or a preload runs, nor for a model the chat would ask about first.
     */
    private fun reloadOnReturn() {
        if (services.activeModel() == null || services.host.current() != null) return
        if (job?.isActive == true || preloading?.isActive == true || background?.isActive == true) return
        if (services.startDecision() !is StartLoad.Decision.Load) return
        _state.update { it.copy(loadingKey = "chatScreen.mountingWeights") }
        preloading = safeScope.launch(Dispatchers.Default) {
            val session = runCatching { services.activeSession() }.getOrNull()
            _state.update { it.copy(loadingKey = null) }
            if (session != null && services.isInForeground && job?.isActive != true) {
                val key = chatWarmKey()
                warmKey = key
                warmJob = launch { runCatching { warmPrefix(session, key.systemPrompt, true, key.filesOnly) } }
            }
        }
    }

    /**
     * The prefix a question gets, with the current Sources choice: My files' instruction differs from the first words
     * after the style's.
     */
    private fun chatWarmKey(): team.sopa.boar.engine.answer.WarmPrefix.Key =
        team.sopa.boar.engine.answer.WarmPrefix.forChat(services.personalities, services.settings.personalityId, _state.value.sourcesOwn)

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
        is StartLoad.Decision.NoFallback -> LoadNote.Crashed(d.blockedId, labelOf(d.blockedId), d.onlyModel)
        else -> null
    }

    private fun crashNote(c: team.sopa.boar.engine.models.LoadGuard.Crash): LoadNote.Crashed {
        val only = services.installed(AssetKind.LLM).none { it.id != c.crashedModelId }
        val label = services.catalog.byId(c.crashedModelId)?.label?.let(::shortLabel) ?: c.crashedLabel
        return LoadNote.Crashed(c.crashedModelId, label, only)
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
        // Already loaded in this process: it answers.
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

    /** Back from settings: the model and technical details may have changed. */
    fun refreshSettings() {
        refreshModel()
        _state.update { it.copy(technicalDetails = services.settings.showLiveStats) }
        // The open chat was erased (Clear all chat history): start a new one.
        val open = _state.value.sessionId
        if (open != null && services.chats.listSessions().none { it.id == open }) newChat()
    }

    fun showToast(key: String) = _state.update { it.copy(toast = team.sopa.boar.ui.I18n.t(getApplication(), key)) }
    fun hideToast() = _state.update { it.copy(toast = null) }

    /** After setup or a load: the answer model's name, or that there's none. */
    fun refreshModel() {
        val m = services.answerModel()
        val installed = services.catalog.corpusCatalog.filter { services.fileOf(it).isFile }.map { it.id }
        // shortModelLabel: the catalog label without its parenthesis ("Qwen3-4B-Instruct-2507").
        val label = m?.label?.let(::shortLabel)
        // The ideas tested on the answer model, only for packs on the phone (also while its file is missing).
        val tested = suggestionData.forModel((m ?: services.catalog.answerModel).id, installed)
        // Plus the short questions of BOAR's own 17-question evaluation, so each new chat shows a different few.
        ideaPool = (tested + BENCH_IDEAS).distinct()
        _state.update { it.copy(modelLabel = label, suggestions = pickIdeas()) }
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

    // --- Answer options (the composer's one sheet: Sources) -----------------------------------------------------

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

    init {
        // A partial selection: the Sources row's "4 of 7" needs the list.
        if (_state.value.sourcesOff.isNotEmpty()) refreshSources()
    }

    /** Answer options → Reset to defaults: all sources. */
    fun resetOptions() = setSourcesOwn(false)

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
            // Off the main thread, each meta parsed once: it holds every source's text (5-20 KB per answer), and parsing a
            // long chat three times on Main hitched the drawer closing.
            val msgs = runCatching {
                withContext(Dispatchers.IO) {
                    services.chats.messages(id).map { r ->
                        val meta = metaObject(r.meta)
                        UiMessage(r.id, r.role, r.text, sourcesFromMeta(meta), r.feedback, receipt = receiptFromMeta(meta), error = errorFromMeta(meta))
                    }
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

    fun send(query: String) {
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
            val style = settings.personalityId
            val (systemPrompt, styleReminder) = services.personalities.promptFor(style)
            val history = ConversationHistory(
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
            // The health gate's fixed text (HealthGate) was written without the model: no receipt, no note, no sources.
            var healthFixed = false
            // A computed conversion or sum ([QuickMath]): the same, no model wrote it.
            var calculated = false
            var exposureComputed = false
            var firstAt = 0L
            var tokens = 0
            // The limit the answer could hit, set once the model is loaded.
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
                val budget = settings.answerTokens(services.host.openedContext).let {
                    // A thank-you or greeting: Succinct's length.
                    if (team.sopa.boar.engine.routing.ShortAnswer.isSmallTalk(q)) minOf(it, team.sopa.boar.engine.settings.AppSettings.AUTO_MAX_TOKENS) else it
                }
                limited = budget
                // Small talk never starts a recap (AnswerPath).
                val way = team.sopa.boar.engine.routing.AnswerPath.of(aboutConversation(q, history, conversation),
                    team.sopa.boar.engine.routing.ShortAnswer.isSmallTalk(q))
                // The preload's warm prefill holds the model's lock: waited for only when it fills this question's prefix
                // (the plain path, same style and Sources), else stopped now instead of making the question wait for it.
                if (way != team.sopa.boar.engine.routing.AnswerPath.PIPELINE ||
                    !team.sopa.boar.engine.answer.WarmPrefix.reusable(warmKey, team.sopa.boar.engine.answer.WarmPrefix.Key(systemPrompt, filesOnly))) cancelWarm()
                if (stopRequested) {
                    // Stop pressed while the model loaded: the load can't be interrupted, the answer can be skipped.
                } else if (session == null) {
                    _state.update { it.copy(error = team.sopa.boar.engine.UserErrors.NO_MODEL) }
                } else if (way == team.sopa.boar.engine.routing.AnswerPath.RECAP) {
                    path = "chat"
                    val r = DeepResearch(SessionEngine(session, settings.engineTweaks.thinkingEnabled), settings.engineTweaks::stageTimeoutMs, settings.engineTweaks::writeUpTimeoutMs).run(
                        q, systemPrompt, history, budget,
                        onProgress = { _state.update { it.copy(activity = Activity.Thinking) } },
                        onToken = ::onPiece,
                        previousAnswer = lastAnswer?.text?.let(AnswerText::stripThinking),
                        conversation = conversation,
                    )
                    sources = r.citations
                    // Written from the last answer: its numbers [n] are that answer's sources.
                    if (r.fromPreviousAnswer) reusedSources = lastAnswer?.sources
                    timedOut = r.timedOut
                    metrics = r.metrics
                } else {
                    val pipeline = AnswerPipeline(session, retrieve, { Classify.classifyTask(it).value }, { Classify.isRetrievalIrrelevant(TaskType.of(it)) },
                        headPass = services.retriever::headPass,
                        // Search port ON-NH: a health turn searches without the port (SearchPort.Mode.ON_NO_HEALTH).
                        plainRetrieve = if (team.sopa.boar.engine.rag.SearchPort.BOARLITE_MODE == team.sopa.boar.engine.rag.SearchPort.Mode.ON_NO_HEALTH)
                            { query, k -> services.retriever.retrieve(query, k, sources = sourceFilter(), port = false) } else null)
                    val compact = compactAnswerModel()
                    val base = AnswerRequest(q, systemPrompt, styleReminder, budget, history, enableThinking = settings.engineTweaks.thinkingEnabled, compactModel = compact)
                    val req = if (filesOnly) PersonalFiles.request(base, services.host.openedContext ?: AnswerPipeline.FALLBACK_CONTEXT, notFoundLine) else base
                    // Stop pressed while this waited for the model (behind the warm prefill) is honoured inside the lock.
                    pipeline.answer(req, shouldStop = { stopRequested }).collect { e ->
                        when (e) {
                            is AnswerEvent.Stage -> _state.update { it.copy(activity = when (e.stage) { AnswerStage.RETRIEVING -> Activity.Retrieving; AnswerStage.THINKING -> Activity.Thinking; AnswerStage.GENERATING -> Activity.Generating }) }
                            is AnswerEvent.Sources -> sources = e.chunks
                            is AnswerEvent.Piece -> onPiece(e.text)
                            is AnswerEvent.Done -> {
                                metrics = e.metrics; retrievalMs = e.retrievalMs; notFound = e.notFound; healthFixed = e.healthFixed; calculated = e.calculated; exposureComputed = e.exposureComputed
                                if (e.healthFixed || e.calculated) sources = emptyList()
                            }
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
            val receipt = receiptOf(startAt, firstAt, tokens, metrics, retrievalMs)?.takeIf { !notFound && !healthFixed && !calculated && !exposureComputed }?.let { r ->
                r.copy(style = style, path = path,
                    // AnswerNote's line under every answer the model wrote, never under small talk.
                    note = !team.sopa.boar.engine.routing.ShortAnswer.isSmallTalk(q))
            }
            val cutAt = limited?.takeIf { !stopped && !timedOut && text.isNotBlank() && AnswerLimit.reached(metrics?.generatedTokens ?: tokens, it) }
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
                s.copy(answeringId = null, activity = null, stopping = false,
                    messages = s.messages.map { if (it.id == answerId) it.copy(text = text.toString(), sources = shown, stopped = stopped, timedOut = timedOut, receipt = receipt, cutAt = cutAt, error = errorKey) else it })
            }
        }
        // However the answer ends (done, stopped, failed, or cancelled before it started), the notification goes with it.
        job?.invokeOnCompletion { team.sopa.boar.app.AnswerService.stop(getApplication()) }
    }

    /**
     * Stops a title still being written instead of making the question wait for it. Cancelling the job keeps one that
     * hasn't started from starting, and makes one that has throw instead of returning, so nothing half-written is saved;
     * the native cancel stops the prompt reading or writing in progress. A stopped title keeps the question as the
     * chat's title.
     */
    private suspend fun stopBackground() {
        val running = background?.takeIf { it.isActive } ?: return
        running.cancel()
        services.host.current()?.cancel()
        running.join()
    }

    /**
     * After an answer: a new chat gets a model-written title (autoGenerateTitles), in the background.
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
        }
    }

    /**
     * Back on screen: the title skipped in the background, once the model is loaded again (as the chat's
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
     * A title left its own prompt in the KV cache, so the next question would read the whole system prompt
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

    /** A model of unknown size counts as compact: BoarLite ships one model, Qwen3 1.7B, and it is. */
    private fun compactAnswerModel(): Boolean =
        services.answerModel()?.sizeBytes?.let { it <= team.sopa.boar.engine.answer.AnswerNote.COMPACT_MODEL_BYTES } ?: true

    /**
     * The answer's receipt, from the engine's own numbers (for a recap, those of the step that
     * wrote the answer), else from the tokens as they streamed. None when nothing was generated.
     */
    private fun receiptOf(startAt: Long, firstAt: Long, streamed: Int, m: GenerationMetrics?, retrievalMs: Double?): Receipt? {
        if (firstAt == 0L || streamed == 0) return null
        val end = System.nanoTime()
        val wallRate = streamed / ((end - firstAt) / 1e9).coerceAtLeast(1e-3)
        return Receipt(
            totalMs = (end - startAt) / 1e6, ttftMs = (firstAt - startAt) / 1e6,
            tokPerSec = m?.decodeTokPerSec?.takeIf { it > 0 } ?: wallRate, tokens = m?.generatedTokens?.takeIf { it > 0 } ?: streamed,
            model = services.activeModel()?.shortName, retrievalMs = retrievalMs, prefillMs = m?.prefillMs, contextTokens = m?.promptTokens,
        )
    }

    private fun metaObject(meta: String?): JsonObject? = runCatching { Json.parseToJsonElement(meta ?: return null).jsonObject }.getOrNull()

    private fun receiptFromMeta(meta: JsonObject?): Receipt? = runCatching {
        meta?.get("receipt")?.jsonObject?.let(Receipt::fromJson)
    }.getOrNull()

    private fun metaOf(sources: List<Source>, receipt: Receipt?, errorKey: String? = null): String = JsonObject(listOfNotNull("sources" to JsonArray(sources.map { s ->
        JsonObject(listOfNotNull(
            "title" to JsonPrimitive(s.title), s.source?.let { "source" to JsonPrimitive(it) }, s.relevance?.let { "relevance" to JsonPrimitive(it) },
            s.similarity?.let { "similarity" to JsonPrimitive(it) }, s.docId?.let { "docId" to JsonPrimitive(it) }, "body" to JsonPrimitive(s.body),
        ).toMap())
    }), receipt?.let { "receipt" to it.toJson() }, errorKey?.let { "error" to JsonPrimitive(it) }).toMap()).toString()

    private fun errorFromMeta(meta: JsonObject?): String? = runCatching {
        meta?.get("error")?.jsonPrimitive?.content
    }.getOrNull()

    private fun sourcesFromMeta(meta: JsonObject?): List<Source> = runCatching {
        (meta ?: return emptyList())["sources"]!!.jsonArray.map { e ->
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
        /** Messages sent verbatim as history. */
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
