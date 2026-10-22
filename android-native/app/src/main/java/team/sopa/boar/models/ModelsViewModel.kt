package team.sopa.boar.models

import team.sopa.boar.app.safeScope

import android.app.ActivityManager
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.EngineParams
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.AssetStore
import team.sopa.boar.engine.models.HfQuery
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.ModelOrder
import team.sopa.boar.engine.settings.AppSettings
import team.sopa.boar.engine.rag.SeedCorpus

/** One catalog row (CatalogRowState): on disk, downloading with progress, speed and ETA, or failed. */
data class Row(
    val present: Boolean = false,
    val downloading: Boolean = false,
    val progress: Double = 0.0,
    val bytesWritten: Long = 0,
    val speedBytesPerSec: Double? = null,
    val etaSeconds: Double? = null,
    val error: String? = null,
    val indexing: Boolean = false,
)

data class ModelsState(
    val items: List<CatalogAsset> = emptyList(),
    val rows: Map<String, Row> = emptyMap(),
    val activeLlm: String? = null,
    val activating: String? = null,
    val totalRamBytes: Long = 0,
    val toast: String? = null,
    /** The Hugging Face search (ModelBrowser.tsx). */
    val hfResults: List<HfBrowser.Repo> = emptyList(),
    val hfOpenRepo: String? = null,
    val hfFiles: List<HfBrowser.GgufFile> = emptyList(),
    val hfBusy: Boolean = false,
    val hfError: String? = null,
    val hfAdded: String? = null,
    /** The catalog id of the model just added from Hugging Face (setup picks it up and uses it). */
    val hfAddedId: String? = null,
    /** The browse page's filters and paging: no request until the page is opened. */
    val hfQuery: String = "",
    val hfSize: HfQuery.Size = HfQuery.Size.ANY,
    val hfSort: HfQuery.Sort = HfQuery.Sort.TRENDING,
    val hfSearching: Boolean = false,
    val hfNext: String? = null,
    val hfSearched: Boolean = false,
    /** The Models screen's sort and filter, and what they sort by. */
    val sort: ModelOrder.Sort = ModelOrder.Sort.SMART,
    val filter: ModelOrder.Filter = ModelOrder.Filter.ALL,
    val lastUsed: Map<String, Long> = emptyMap(),
    val added: Map<String, Long> = emptyMap(),
    val mine: Set<String> = emptySet(),
)

/** The models and knowledge catalog (port of ModelCatalogScreen.tsx): download, use, remove. */
class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    @Volatile private var store = AssetStore(app.filesDir, services.catalog)
    private val _state = MutableStateFlow(ModelsState())
    val state: StateFlow<ModelsState> = _state

    init {
        val ram = runCatching { ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
        // Answer models first, then the embedding model, then knowledge, as the catalog lists them.
        val sort = services.settings.modelSort?.let { runCatching { ModelOrder.Sort.valueOf(it) }.getOrNull() } ?: ModelOrder.Sort.SMART
        val filter = services.settings.modelFilter?.let { runCatching { ModelOrder.Filter.valueOf(it) }.getOrNull() } ?: ModelOrder.Filter.ALL
        _state.update { it.copy(items = sortedItems(), totalRamBytes = ram, sort = sort, filter = filter) }
        refresh()
    }

    // Answer models first (curated, then discovered), then the embedding model, then knowledge, as the catalog lists them.
    // A hidden model (no longer suggested) is listed only when it's on the phone.
    private fun sortedItems() = services.catalog.models.filter { it.kind != AssetKind.INDEX && (!it.hidden || services.fileOf(it).isFile) }
        .sortedBy { when (it.kind) { AssetKind.LLM -> 0; AssetKind.EMBEDDING -> 1; AssetKind.CORPUS -> 2; AssetKind.INDEX -> 3 } }

    private val hfSearch = java.util.concurrent.atomic.AtomicInteger()

    /** A new search with these filters (the browse page): replaces the results; an older answer still running is dropped. */
    fun hfBrowse(query: String = _state.value.hfQuery, size: HfQuery.Size = _state.value.hfSize, sort: HfQuery.Sort = _state.value.hfSort) {
        val request = hfSearch.incrementAndGet()
        hfRequest.incrementAndGet() // a repo's files still loading belong to the old results
        _state.update { it.copy(hfQuery = query, hfSize = size, hfSort = sort, hfSearching = true, hfSearched = true, hfError = null,
            hfResults = emptyList(), hfNext = null, hfOpenRepo = null, hfFiles = emptyList(), hfBusy = false, hfAdded = null, hfAddedId = null) }
        fetchPage(request, HfQuery.searchUrl(query, size, sort), append = false)
    }

    /** The next page of the same search, at the end of the list. */
    fun hfMore() {
        val s = _state.value
        val next = s.hfNext ?: return
        if (s.hfSearching) return
        _state.update { it.copy(hfSearching = true, hfError = null) }
        fetchPage(hfSearch.get(), next, append = true)
    }

    private fun fetchPage(request: Int, url: String, append: Boolean) {
        safeScope.launch(Dispatchers.IO) {
            runCatching { HfBrowser.browse(url) }
                .onSuccess { p -> _state.update { if (hfSearch.get() != request) it else it.copy(hfResults = ((if (append) it.hfResults else emptyList()) + p.repos).distinctBy { r -> r.id }, hfNext = p.next, hfSearching = false) } }
                .onFailure { e -> _state.update { if (hfSearch.get() != request) it else it.copy(hfError = team.sopa.boar.engine.UserErrors.browseKey(e), hfSearching = false) } }
        }
    }

    private val hfRequest = java.util.concurrent.atomic.AtomicInteger()

    fun hfOpen(repo: String) {
        // Each tap is a new request; an older answer (the repo closed, or opened again since) is dropped.
        val request = hfRequest.incrementAndGet()
        if (_state.value.hfOpenRepo == repo) { _state.update { it.copy(hfOpenRepo = null, hfFiles = emptyList(), hfBusy = false) }; return }
        _state.update { it.copy(hfBusy = true, hfError = null, hfOpenRepo = repo, hfFiles = emptyList()) }
        safeScope.launch(Dispatchers.IO) {
            runCatching { HfBrowser.files(repo) }
                .onSuccess { f -> _state.update { if (hfRequest.get() != request) it else it.copy(hfFiles = f, hfBusy = false) } }
                .onFailure { e -> _state.update { if (hfRequest.get() != request) it else it.copy(hfError = team.sopa.boar.engine.UserErrors.browseKey(e), hfBusy = false) } }
        }
    }

    /** Forgets the last model added, so a page opened now only reacts to what's added from here on (setup). */
    fun clearAdded() = _state.update { it.copy(hfAdded = null, hfAddedId = null) }

    /** Adds the file to the list above as a downloadable model (not downloaded yet). */
    fun hfAdd(repo: String, f: HfBrowser.GgufFile) {
        safeScope.launch(Dispatchers.IO) {
            val asset = runCatching { HfBrowser.toAsset(repo, f) }.getOrElse { e -> _state.update { it.copy(hfError = team.sopa.boar.engine.UserErrors.browseKey(e)) }; return@launch }
            services.addDiscovered(asset)
            store = AssetStore(getApplication<Application>().filesDir, services.catalog)
            _state.update { it.copy(items = sortedItems(), hfAdded = asset.label, hfAddedId = asset.id, toast = text("native.models.addedToast")) }
            refresh()
        }
    }

    fun refresh() {
        // The list itself too: a hidden model drops out once it's removed.
        val items = sortedItems()
        val lastUsed = services.settings.modelTimes(AppSettings.MODEL_LAST_USED)
        // Models from before these times were kept: the file's date stands in for when it was added, once it's complete
        // (a download in progress isn't added yet: it sorted second under Recently added, Oct 18).
        val stamped = services.settings.modelTimes(AppSettings.MODEL_ADDED)
        val added = items.associate { a -> a.id to (stamped[a.id] ?: services.fileOf(a).takeIf { store.status(a).present }?.lastModified()) }
            .mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        val mine = services.discovered().map { it.id }.toSet()
        _state.update { s ->
            s.copy(items = items, activeLlm = services.activeModel()?.id, lastUsed = lastUsed, added = added, mine = mine, rows = items.associate { a ->
                val prev = s.rows[a.id] ?: Row()
                a.id to prev.copy(present = store.status(a).present)
            })
        }
    }

    private fun row(id: String, change: (Row) -> Row) = _state.update { s -> s.copy(rows = s.rows + (id to change(s.rows[id] ?: Row()))) }

    private val downloads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun download(a: CatalogAsset) {
        if (_state.value.rows[a.id]?.downloading == true) return
        row(a.id) { Row(downloading = true) }
        downloads[a.id] = safeScope.launch(Dispatchers.IO) {
            var lastT = System.nanoTime()
            var lastB = 0L
            var speed: Double? = null
            try {
                store.download(a) { p ->
                    val now = System.nanoTime()
                    val dt = (now - lastT) / 1e9
                    if (dt >= 1.0) {
                        val inst = (p.bytesDone - lastB) / dt
                        speed = speed?.let { it * 0.7 + inst * 0.3 } ?: inst
                        lastT = now
                        lastB = p.bytesDone
                    }
                    val eta = speed?.takeIf { it > 0 }?.let { (p.bytesTotal - p.bytesDone) / it }
                    row(a.id) { it.copy(progress = if (p.bytesTotal > 0) p.bytesDone.toDouble() / p.bytesTotal else 0.0, bytesWritten = p.bytesDone, speedBytesPerSec = speed, etaSeconds = eta) }
                }
                row(a.id) { Row(present = true) }
                services.settings.stampModel(AppSettings.MODEL_ADDED, a.id)
                if (a.kind == AssetKind.CORPUS && a.format != "sqlite-pack") index(a)
                if (a.kind == AssetKind.CORPUS) services.reloadPacks()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by the user: the partial file goes too, so nothing is left half-downloaded.
                store.delete(a)
                row(a.id) { Row() }
                throw e
            } catch (e: Exception) {
                row(a.id) { Row(error = team.sopa.boar.ui.I18n.t(getApplication(), team.sopa.boar.engine.UserErrors.downloadKey(e), *team.sopa.boar.engine.UserErrors.downloadArgs(e))) }
            } finally {
                // Only this job's entry: a retry started right after a cancel has already registered its own.
                coroutineContext[kotlinx.coroutines.Job]?.let { downloads.remove(a.id, it) }
            }
            refresh()
        }
    }

    /** Stops a download and deletes what it wrote so far. */
    fun cancelDownload(a: CatalogAsset) {
        downloads[a.id]?.cancel() ?: return
        refresh()
    }

    /** A model added from Hugging Face but not downloaded: in the list only, so it can leave it (the Expo app's ✕). */
    fun isDiscovered(a: CatalogAsset) = services.discovered().any { it.id == a.id }

    fun removeFromList(a: CatalogAsset) {
        safeScope.launch(Dispatchers.IO) {
            // Wait for the download's own cleanup, so it can't run after the delete and bring the row back.
            downloads[a.id]?.cancelAndJoin()
            store.delete(a)
            services.removeDiscovered(a.id)
            store = AssetStore(getApplication<Application>().filesDir, services.catalog)
            _state.update { s -> s.copy(rows = s.rows - a.id) }
            refresh()
        }
    }

    /** A corpus pack is searchable once indexed with the embedding model (as setup does). */
    private suspend fun index(pack: CatalogAsset) {
        val emb = services.installed(AssetKind.EMBEDDING).firstOrNull() ?: return
        val embedder = LlamaSession.load(services.fileOf(emb).path, EngineParams(nCtx = 512, threads = 2, embeddings = true)) ?: return
        row(pack.id) { it.copy(indexing = true) }
        try {
            val builtin = getApplication<Application>().assets.open("corpus/corpus.json").bufferedReader().use { it.readText() }
            val collections = listOf(SeedCorpus.Collection(SeedCorpus.BUILTIN, SeedCorpus.docs(SeedCorpus.BUILTIN, builtin))) +
                services.catalog.corpusCatalog.filter { it.format != "sqlite-pack" && services.fileOf(it).isFile }
                    .map { SeedCorpus.Collection(it.id, SeedCorpus.docs(it.id, services.fileOf(it).readText())) }
            SeedCorpus(services.knowledgeBase) { embedder.embed(it) }.seed(collections)
        } finally {
            embedder.close()
            row(pack.id) { it.copy(indexing = false) }
        }
    }

    /** "Use": the model the chat answers with from now on, loaded right away. */
    fun use(a: CatalogAsset) {
        if (_state.value.activating != null) return
        _state.update { it.copy(activating = a.id) }
        safeScope.launch(Dispatchers.Default) {
            services.settings.setActiveModelId(a.kind.name.lowercase(), a.id)
            // Picked again after it closed the app: the user's call (CR-2's confirmation).
            if (a.kind == AssetKind.LLM) services.loadGuard.confirm(a.id)
            // Picked although it may not fit this phone: the user's call, so the chat doesn't ask again at start.
            if (a.kind == AssetKind.LLM && services.isRisky(a)) services.rememberLoadAnyway(a.id)
            if (a.kind == AssetKind.LLM) runCatching { services.host.unload(); services.activeSession() }
            _state.update { it.copy(activating = null, toast = text("native.models.nowUsing", "model" to a.shortName)) }
            refresh()
        }
    }

    fun remove(a: CatalogAsset) {
        safeScope.launch(Dispatchers.IO) {
            if (a.id == _state.value.activeLlm) services.host.unload()
            if (a.kind == AssetKind.CORPUS && a.format != "sqlite-pack") services.knowledgeBase.deleteSeedChunksWithPrefix(SeedCorpus.chunkIdPrefix(a.id))
            if (a.kind == AssetKind.CORPUS) services.reloadPacks()
            store.delete(a)
            row(a.id) { Row() }
            refresh()
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    fun setSort(sort: ModelOrder.Sort) { services.settings.modelSort = sort.name; _state.update { it.copy(sort = sort) } }
    fun setFilter(filter: ModelOrder.Filter) { services.settings.modelFilter = filter.name; _state.update { it.copy(filter = filter) } }

    private fun text(key: String, vararg args: Pair<String, Any?>) = team.sopa.boar.ui.I18n.t(getApplication(), key, *args)
}
