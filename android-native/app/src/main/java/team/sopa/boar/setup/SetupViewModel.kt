package team.sopa.boar.setup

import team.sopa.boar.app.safeScope

import android.app.ActivityManager
import android.app.Application
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.CpuTopology
import team.sopa.boar.engine.EngineParams
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.models.AnswerTier
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.AssetStore
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.SetupModel
import team.sopa.boar.engine.rag.SeedCorpus
import java.io.File
import java.io.IOException

/** The two groups of the install list. */
enum class InstallGroup { MODELS, KNOWLEDGE }

/** An install row's status: Queued / Downloading NN% / Checking / Ready / Failed (+ Retry). */
enum class FileStatus { QUEUED, DOWNLOADING, CHECKING, READY, FAILED }

/** One file of the install step, as its row shows it. */
data class AssetProgress(
    val asset: CatalogAsset,
    val bytesDone: Long,
    val verifying: Boolean,
    val done: Boolean,
    val error: String? = null,
    /** Not enough space: the step offers to go back and pick less knowledge. */
    val storage: Boolean = false,
    /** The file the download loop is on right now. */
    val current: Boolean = false,
    /** MODELS for the search model and answer models, KNOWLEDGE for corpora. */
    val group: InstallGroup = if (asset.kind == AssetKind.CORPUS || asset.kind == AssetKind.INDEX) InstallGroup.KNOWLEDGE else InstallGroup.MODELS,
    /** t() key of the row name; null = use [CatalogAsset.shortName] (answer models, unknown corpora). */
    val nameKey: String? = KnowledgeCatalog.fileNameKey(asset),
) {
    val moving get() = !done && error == null && (bytesDone > 0 || verifying)
    val fraction get() = if (done) 1f else if (asset.sizeBytes > 0) (bytesDone.toFloat() / asset.sizeBytes).coerceIn(0f, 1f) else 0f
    /** 0..100 for "Downloading NN%". */
    val percent get() = (fraction * 100).toInt()
    val status: FileStatus get() = when {
        error != null -> FileStatus.FAILED
        done -> FileStatus.READY
        verifying -> FileStatus.CHECKING
        current -> FileStatus.DOWNLOADING
        else -> FileStatus.QUEUED
    }
}

enum class IndexPhase { WAITING, BUILDING, READY, ERROR }

data class SetupState(
    /** A [SetupSteps] value: 1 welcome, 2 models, 3 default model (only with 2+ selected), 4 knowledge, 5 install. */
    val step: Int = SetupSteps.WELCOME,
    val totalRamBytes: Long = 0,
    /** Free space on the phone; -1 when Android doesn't say. */
    val freeDiskBytes: Long = 0,
    /** Every answer model the user can pick (catalog LLMs + discovered ones), in card order (recommended first). */
    val models: List<ModelOption> = emptyList(),
    val recommendedModelId: String? = null,
    val selectedModelIds: Set<String> = emptySet(),
    /** The model that answers after setup (becomes the active LLM when the install starts); always one of [selectedModelIds]. */
    val defaultModelId: String? = null,
    /** The knowledge step's sections, every corpus of the catalog once. */
    val knowledge: List<KnowledgeSection> = emptyList(),
    /** Catalog asset ids of the recommended knowledge (tier research / encyclopedia / full by free space). */
    val recommendedCorpusIds: Set<String> = emptySet(),
    /** Catalog asset ids selected (the Wikipedia essentials card = both corpus-standard and corpus-full). */
    val selectedCorpusIds: Set<String> = emptySet(),
    /** Sizes of the current selection. */
    val plan: SelectionPlan = SelectionPlan(),
    /** What the recommended selection downloads (search model + recommended model + recommended knowledge): the welcome line. */
    val recommendedDownloadBytes: Long = 0,
    /** One row per file being installed, in download order. */
    val downloads: List<AssetProgress> = emptyList(),
    /** Measured download speed over the last ~5 s; null before a second of data or while stalled. */
    val speedBytesPerSec: Long? = null,
    /** Time left for the downloads, once 5 s of bytes came in; null before. */
    val etaSeconds: Long? = null,
    /** No byte for a minute while downloading: the step offers to restart the downloads. */
    val stalled: Boolean = false,
    val indexPhase: IndexPhase = IndexPhase.WAITING,
    val indexDone: Int = 0,
    val indexTotal: Int = 0,
    val indexTitle: String = "",
    val indexEtaSeconds: Long? = null,
    val indexError: String? = null,
    /** Counts progress updates: the loading line under the bar changes every few of them, without a timer. */
    val tick: Int = 0,
) {
    val selectedModels get() = models.filter { it.asset.id in selectedModelIds }
    /** The default-model step is part of the flow. */
    val showsDefaultStep get() = selectedModelIds.size >= 2
    val canContinueModels get() = selectedModelIds.isNotEmpty()
    fun isSelected(item: KnowledgeItem) = item.assetIds.all { it in selectedCorpusIds }
    private val allCorpusIds get() = knowledge.flatMap { s -> s.items.flatMap { it.assetIds } }
    /** The quick-select chip that matches the selection exactly; null when none does. */
    val knowledgePreset get() = KnowledgeCatalog.matchingPreset(selectedCorpusIds, recommendedCorpusIds, allCorpusIds)
    val selectedKnowledgeCount get() = knowledge.sumOf { s -> s.items.count(::isSelected) }

    val modelDownloads get() = downloads.filter { it.group == InstallGroup.MODELS }
    val knowledgeDownloads get() = downloads.filter { it.group == InstallGroup.KNOWLEDGE }
    val allPresent get() = downloads.isNotEmpty() && downloads.all { it.done }
    /** Files are still coming in and nothing has failed: Back asks first. */
    val transferring get() = downloads.isNotEmpty() && !allPresent && failed.isEmpty()
    val failed get() = downloads.filter { it.error != null }
    val totalBytes get() = downloads.sumOf { it.asset.sizeBytes }
    val doneBytes get() = downloads.sumOf { if (it.done) it.asset.sizeBytes else it.bytesDone }
    /** 0..1 over all files. */
    val overallFraction get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else if (allPresent) 1f else 0f
    /** The index can finish while the answer model still downloads (they overlap): done means both. */
    val finished get() = indexPhase == IndexPhase.READY && allPresent
}

/**
 * The first-run setup: welcome, the answer models (any number; the RAM pick preselected), the default model when two or
 * more are picked, the knowledge (every pack, the free-space pick preselected), then the downloads (resumable,
 * verified, retried) with the search index overlapping them. Everything after it works offline.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    private val store = AssetStore(app.filesDir, services.catalog)
    private val progressFile = File(app.filesDir, "setup_progress.json")
    private val previews: Map<String, PackPreview> =
        runCatching { app.assets.open("boar-data/pack-previews.json").bufferedReader().use { it.readText() } }
            .map(KnowledgeCatalog::parsePreviews).getOrDefault(emptyMap())
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state
    /** The downloads. */
    private var job: Job? = null
    /** Asset ids of the run [job] downloads, to tell a re-entry from a changed selection. */
    private var jobIds: List<String> = emptyList()
    /** The index, which starts as soon as its inputs are in and overlaps the rest of the downloads. */
    private var indexJob: Job? = null
    /** JSON corpora the running/last index read; a newly selected one runs it again once it ends. */
    @Volatile private var indexedInputs: Set<String> = emptySet()
    @Volatile private var reindex = false
    /** The user touched the selection: the recommendation no longer overrides it. */
    private var modelsChosen = false
    private var knowledgeChosen = false
    /** Bytes and time when downloads were first seen moving, for the measured ETA. */
    private var rateStart: Pair<Long, Long>? = null
    /** (time ms, bytes) over the last few seconds, for the speed. */
    private val speedSamples = ArrayDeque<Pair<Long, Long>>()
    private var lastMove = Pair(0L, System.currentTimeMillis())
    private var indexStart: Pair<Int, Long>? = null
    /** Setup was in its install step when the process ended: the app opens on it, not on the chat. */
    var unfinished = false
        private set

    init {
        val ram = runCatching { ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
        val rec = SetupModel.recommended(services.catalog.answerModels, ram)
        _state.update { it.copy(totalRamBytes = ram, recommendedModelId = rec?.id) }
        initialSelection()
        restore()
        refreshPlans()
        // Process killed in the install step (downloading or indexing): pick up there. Files already in are skipped
        // and the index skips the articles it has.
        unfinished = _state.value.step == SetupSteps.INSTALL
        if (unfinished) install()
        watchStall()
    }

    /** Free space on the phone; -1 when Android doesn't say (a real 0 means the disk is full). */
    private fun freeBytes() = runCatching { StatFs(getApplication<Application>().filesDir.path).availableBytes }.getOrDefault(-1L)

    private fun present(a: CatalogAsset) = store.status(a).present

    /** Answer models offered: every non-hidden catalog LLM, discovered ones included, plus hidden ones already on the phone. */
    private fun modelCandidates(): List<CatalogAsset> =
        services.catalog.models.filter { it.kind == AssetKind.LLM && (!it.hidden || present(it)) }

    /** Recommended model + the answer models already on the phone; default = the active model when on the phone. */
    private fun initialSelection() {
        val candidates = modelCandidates()
        val rec = _state.value.recommendedModelId
        val installed = candidates.filter(::present).map { it.id }
        val selected = (listOfNotNull(rec?.takeIf { id -> candidates.any { it.id == id } }) + installed).toSet()
            .ifEmpty { candidates.firstOrNull()?.let { setOf(it.id) } ?: emptySet() }
        val active = services.settings.activeModelId("llm")?.takeIf { it in selected }
        _state.update { it.copy(selectedModelIds = selected, defaultModelId = ModelPlanner.resolveDefault(emptyList(), selected, active, rec)) }
    }

    private fun restore() {
        val o = runCatching { org.json.JSONObject(progressFile.readText()) }.getOrNull() ?: return
        val catalog = services.catalog
        fun ids(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val step: Int
        val models: Set<String>
        val default: String?
        val corpusRaw: Set<String>?
        if (o.optInt("v") >= 2) {
            step = o.optInt("step", SetupSteps.WELCOME)
            models = ids("models").toSet()
            default = o.optString("default").ifEmpty { null }
            corpusRaw = ids("corpus").toSet()
            modelsChosen = o.optBoolean("modelsChosen")
            knowledgeChosen = o.optBoolean("knowledgeChosen")
        } else {
            // Before 2026-10-22: one package (a tier) and one answer model; steps 3 knowledge, 4 install.
            step = when (val s = o.optInt("step", 1)) { 3 -> SetupSteps.KNOWLEDGE; 4 -> SetupSteps.INSTALL; else -> s }
            val chosen = o.optString("chosenId").ifEmpty { null }
            models = setOfNotNull(chosen?.takeIf { o.optBoolean("answerChosen") })
            default = chosen
            val tier = when (o.optString("packageId")) { "research" -> "research"; "encyclopedia" -> "encyclopedia"; "essential" -> "full"; else -> null }
            corpusRaw = tier?.let { catalog.tier(it)?.corpusPackIds?.toSet() }
            modelsChosen = models.isNotEmpty()
            knowledgeChosen = o.optBoolean("packageChosen")
        }
        val known = modelCandidates().map { it.id }.toSet()
        val keptModels = models.filter { it in known || catalog.byId(it)?.kind == AssetKind.LLM }.toSet()
        val corpus: Set<String>? = corpusRaw?.filter { id -> catalog.byId(id)?.kind == AssetKind.CORPUS }?.toSet()
        _state.update { s ->
            val sel = keptModels.ifEmpty { s.selectedModelIds }
            s.copy(
                step = step.coerceIn(SetupSteps.WELCOME, SetupSteps.INSTALL).let { if (it == SetupSteps.DEFAULT_MODEL && sel.size < 2) SetupSteps.KNOWLEDGE else it },
                selectedModelIds = sel,
                defaultModelId = ModelPlanner.resolveDefault(emptyList(), sel, default, s.recommendedModelId),
                selectedCorpusIds = if (knowledgeChosen && corpus != null) corpus else s.selectedCorpusIds,
            )
        }
        if (corpus == null) knowledgeChosen = false
    }

    private fun save() {
        val s = _state.value
        runCatching {
            val tmp = File(progressFile.path + ".tmp")
            tmp.writeText(org.json.JSONObject().put("v", 2).put("step", s.step)
                .put("models", org.json.JSONArray(s.selectedModelIds.toList()))
                .put("default", s.defaultModelId ?: "")
                .put("corpus", org.json.JSONArray(s.selectedCorpusIds.toList()))
                .put("modelsChosen", modelsChosen).put("knowledgeChosen", knowledgeChosen).toString())
            tmp.renameTo(progressFile)
        }
    }

    /** Setup is over (finished, or left from Settings): the next start doesn't resume it. */
    fun forget() { progressFile.delete() }

    private fun setStep(step: Int) {
        _state.update { it.copy(step = step) }
        save()
    }

    /** Settings → Run setup again: the wizard from step 1, the selection back to the recommendation + what is installed. */
    fun restart() {
        job?.cancel()
        job = null
        jobIds = emptyList()
        indexJob?.cancel()
        indexJob = null
        modelsChosen = false
        knowledgeChosen = false
        _state.update { it.copy(step = SetupSteps.WELCOME, downloads = emptyList(), indexPhase = IndexPhase.WAITING, indexError = null, stalled = false, speedBytesPerSec = null, etaSeconds = null) }
        initialSelection()
        refreshPlans()
        save()
    }

    /** Forward one step (skipping the default-model step with fewer than 2 models); from the knowledge step it starts [install]. */
    fun next() {
        val s = _state.value
        when (s.step) {
            SetupSteps.KNOWLEDGE -> install()
            SetupSteps.MODELS -> if (s.canContinueModels) setStep(ModelPlanner.nextStep(s.step, s.selectedModelIds.size))
            else -> setStep(ModelPlanner.nextStep(s.step, s.selectedModelIds.size))
        }
    }

    /**
     * Back one step. From the install step it stops the downloads (the bytes on disk are kept and resume later) but
     * never the index: the UI must not call it while the index builds (indexPhase BUILDING).
     */
    fun back() {
        val s = _state.value
        if (s.step == SetupSteps.INSTALL) {
            job?.cancel()
            job = null
            jobIds = emptyList()
            _state.update { st -> st.copy(downloads = st.downloads.map { it.copy(current = false, verifying = false) }, speedBytesPerSec = null, stalled = false) }
        }
        setStep(ModelPlanner.previousStep(s.step, s.selectedModelIds.size))
    }

    // --- Models ------------------------------------------------------------------------------------------------------

    /** Checks or unchecks an answer model; the default follows when it was unchecked. */
    fun toggleModel(id: String) {
        modelsChosen = true
        _state.update { s ->
            val sel = if (id in s.selectedModelIds) s.selectedModelIds - id else s.selectedModelIds + id
            s.copy(selectedModelIds = sel, defaultModelId = ModelPlanner.resolveDefault(s.models.map { it.asset.id }, sel, s.defaultModelId, s.recommendedModelId))
        }
        refreshPlans()
        save()
    }

    /** The default-model step's radio: must be one of the selected models. */
    fun setDefaultModel(id: String) {
        if (id !in _state.value.selectedModelIds) return
        _state.update { it.copy(defaultModelId = id) }
        save()
    }

    /** A model added from Hugging Face in setup's Discover page: listed with the others and selected. */
    fun pickDiscovered(id: String) {
        if (services.catalog.byId(id) == null) return
        modelsChosen = true
        _state.update { s ->
            val sel = s.selectedModelIds + id
            s.copy(selectedModelIds = sel, defaultModelId = ModelPlanner.resolveDefault(s.models.map { it.asset.id }, sel, s.defaultModelId, s.recommendedModelId))
        }
        refreshPlans()
        save()
    }

    fun isHeavy(m: CatalogAsset) = SetupModel.heavyForPhone(m, _state.value.totalRamBytes)

    // --- Knowledge ---------------------------------------------------------------------------------------------------

    /** Checks or unchecks a knowledge card (the Wikipedia essentials card toggles both of its files). */
    fun toggleKnowledge(item: KnowledgeItem) {
        knowledgeChosen = true
        _state.update { it.copy(selectedCorpusIds = KnowledgeCatalog.toggle(it.selectedCorpusIds, item)) }
        refreshPlans()
        save()
    }

    /** The quick-select chips: Recommended, Everything, Minimal (Wikipedia essentials only). */
    fun applyPreset(preset: KnowledgePreset) {
        knowledgeChosen = true
        _state.update { s ->
            s.copy(selectedCorpusIds = KnowledgeCatalog.presetIds(preset, s.recommendedCorpusIds, s.knowledge.flatMap { k -> k.items.flatMap { it.assetIds } }))
        }
        refreshPlans()
        save()
    }

    // --- Plans -------------------------------------------------------------------------------------------------------

    /** Required files other than answer models (the search model). */
    private fun requiredAssets() = services.catalog.models.filter { it.required && it.kind != AssetKind.LLM }

    /**
     * Everything the selection installs, in download order: what the index needs first (search model and JSON corpora,
     * ~40 MB), so it runs while the models come in; then the default model, the prebuilt sqlite packs (not indexed on
     * the phone), and the other selected models last.
     */
    private fun installAssets(): List<CatalogAsset> {
        val s = _state.value
        val catalog = services.catalog
        val models = s.selectedModelIds.mapNotNull(catalog::byId)
        val corpora = catalog.corpusCatalog.filter { it.id in s.selectedCorpusIds }
        return (requiredAssets() + models + corpora + snapshotsFor(corpora)).distinctBy { it.id }.sortedBy {
            when {
                feedsIndex(it) -> 0
                it.id == s.defaultModelId -> 1
                it.kind == AssetKind.CORPUS -> 2
                else -> 3
            }
        }
    }

    /** Files the index reads: the search model, the JSON corpora (sqlite packs ship their own index) and a prebuilt index. */
    private fun feedsIndex(a: CatalogAsset) =
        a.kind == AssetKind.EMBEDDING || a.kind == AssetKind.INDEX || (a.kind == AssetKind.CORPUS && a.format != SQLITE_PACK)

    /**
     * The prebuilt index that covers exactly the selected JSON corpora (what tiers full, encyclopedia and research
     * index) and was embedded with the catalog's search model. Any other selection indexes on the phone as before.
     */
    private fun snapshotsFor(corpora: List<CatalogAsset>): List<CatalogAsset> {
        val json = corpora.filter { it.format != SQLITE_PACK }.map { it.id }.toSet()
        val embedder = services.catalog.models.firstOrNull { it.kind == AssetKind.EMBEDDING && it.required }
        return services.catalog.models.filter { a ->
            a.kind == AssetKind.INDEX && a.format == KB_SNAPSHOT && a.embeddingModelSha256 == embedder?.sha256 &&
                a.requires.filter { id -> services.catalog.byId(id)?.kind == AssetKind.CORPUS }.toSet() == json && json.isNotEmpty()
        }
    }

    /**
     * Free space, the model cards, the knowledge sections, the recommendations and the selection's sizes, recomputed
     * whenever the selection changes or a file lands. The recommended knowledge follows the selected models' size until
     * the user touches the knowledge step.
     */
    fun refreshPlans() {
        val free = freeBytes()
        val catalog = services.catalog
        val ram = _state.value.totalRamBytes
        val presentIds = catalog.models.filter(::present).map { it.id }.toSet()
        fun pending(ids: Collection<String>) = ids.filter { it !in presentIds }.sumOf { catalog.byId(it)?.sizeBytes ?: 0L }
        val rec = _state.value.recommendedModelId
        val discoveredIds = services.discovered().map { it.id }.toSet()
        val models = ModelPlanner.order(modelCandidates().map { m ->
            val heavy = SetupModel.heavyForPhone(m, ram)
            ModelOption(
                asset = m, name = m.shortName, sizeBytes = m.sizeBytes, recommended = m.id == rec, tier = ModelPlanner.tier(m),
                fit = if (ram <= 0) RamFit.UNKNOWN else if (heavy) RamFit.HEAVY else RamFit.FITS,
                license = ModelPlanner.licenseShort(m.license), installed = m.id in presentIds,
                discovered = m.id in discoveredIds || m.id.startsWith("hf-"),
            )
        })
        val required = requiredAssets().map { it.id }
        val research = catalog.tier("research")?.corpusPackIds.orEmpty().toSet()
        val knowledge = KnowledgeCatalog.sections(catalog.corpusCatalog, previews, research) { it.id in presentIds }
        _state.update { s ->
            val modelIds = s.selectedModelIds.filter { id -> models.any { it.asset.id == id } }.toSet()
            val recCorpus = KnowledgeCatalog.recommendedCorpus(catalog.tiers, free, pending(required + modelIds)) { pending(it) }
            val corpus = if (knowledgeChosen) s.selectedCorpusIds else recCorpus
            val modelsDl = pending(modelIds)
            val knowledgeDl = pending(corpus)
            val download = pending(required) + modelsDl + knowledgeDl
            val installedBytes = (required + modelIds + corpus).distinct().sumOf { catalog.byId(it)?.sizeBytes ?: 0L }
            val recModelIds = listOfNotNull(rec)
            val recCorpusForRec = KnowledgeCatalog.recommendedCorpus(catalog.tiers, free, pending(required + recModelIds)) { pending(it) }
            s.copy(
                freeDiskBytes = free, models = models, selectedModelIds = modelIds,
                defaultModelId = ModelPlanner.resolveDefault(models.map { it.asset.id }, modelIds, s.defaultModelId, rec),
                knowledge = knowledge, recommendedCorpusIds = recCorpus, selectedCorpusIds = corpus,
                plan = SelectionPlan(
                    modelsDownloadBytes = modelsDl, knowledgeDownloadBytes = knowledgeDl, downloadBytes = download,
                    installedBytes = installedBytes, shortfallBytes = if (free >= 0) maxOf(0, download - free) else 0,
                    estimatedMinutes = KnowledgeCatalog.estimatedMinutes(download),
                ),
                recommendedDownloadBytes = pending(required) + pending(recModelIds) + pending(recCorpusForRec),
            )
        }
    }

    // --- Install -----------------------------------------------------------------------------------------------------

    /**
     * Knowledge step → install: downloads what is missing, one file at a time (one stream already fills the link), and
     * builds the index as soon as its inputs are in, in parallel with the rest. Re-entered with the same selection it
     * just shows the running install; with a changed selection it restarts the downloads from the bytes on disk.
     */
    fun install() {
        val assets = installAssets()
        val ids = assets.map { it.id }
        _state.value.defaultModelId?.let { services.settings.setActiveModelId("llm", it) }
        if (job?.isActive == true && ids == jobIds) { setStep(SetupSteps.INSTALL); return }
        val old = job
        old?.cancel()
        // A finished or failed index is checked again for this selection (fast when nothing is missing); a running one goes on.
        val indexing = indexJob?.isActive == true
        _state.update { s ->
            s.copy(step = SetupSteps.INSTALL, stalled = false, speedBytesPerSec = null, etaSeconds = null, downloads = assets.map { a ->
                val st = store.status(a)
                s.downloads.firstOrNull { it.asset.id == a.id }?.copy(error = null, storage = false, current = false, verifying = false, done = st.present)
                    ?: AssetProgress(a, if (st.present) a.sizeBytes else st.partialBytes, false, st.present)
            }, indexPhase = if (indexing) s.indexPhase else IndexPhase.WAITING, indexError = if (indexing) s.indexError else null)
        }
        save()
        rateStart = null
        speedSamples.clear()
        lastMove = Pair(_state.value.doneBytes, System.currentTimeMillis())
        jobIds = ids
        job = safeScope.launch(Dispatchers.IO) {
            // The previous run may still be inside a file: let it stop before this one opens the same file.
            old?.cancelAndJoin()
            val started = System.currentTimeMillis()
            val indexInputs = assets.filter { it.kind == AssetKind.CORPUS && feedsIndex(it) }.map { it.id }.toSet()
            var indexStarted = false
            fun maybeIndex() {
                if (indexStarted || !assets.filter { feedsIndex(it) }.all(::present)) return
                indexStarted = true
                // A running index that already reads every selected JSON corpus goes on; otherwise one (more) run.
                if (indexJob?.isActive == true && indexedInputs.containsAll(indexInputs)) return
                startIndex()
            }
            maybeIndex()
            for (a in assets) {
                if (present(a)) {
                    update(a) { it.copy(done = true, bytesDone = a.sizeBytes, current = false) }
                } else {
                    update(a) { it.copy(current = true) }
                    val ok = fetch(a)
                    update(a) { it.copy(current = false) }
                    if (!ok) return@launch
                    // A prebuilt pack is searchable once opened; the index doesn't read it, so it needn't wait for it.
                    if (a.format == SQLITE_PACK) services.reloadPacks()
                }
                maybeIndex()
            }
            _state.update { it.copy(speedBytesPerSec = null) }
            Log.i(TAG, "downloads done in ${(System.currentTimeMillis() - started) / 1000.0} s")
        }
    }

    /**
     * Runs [index] in its own job, so a download retry or restart neither waits for it nor starts a second one; a run
     * asked for while one is going (a new JSON corpus selected) follows it.
     */
    private fun startIndex() {
        if (indexJob?.isActive == true) { reindex = true; return }
        indexJob = safeScope.launch(Dispatchers.IO) {
            do {
                reindex = false
                index()
            } while (reindex && _state.value.indexPhase == IndexPhase.READY)
        }
    }

    /** One file, with up to three quiet retries when the connection stalls (the bytes already in are kept). */
    private suspend fun fetch(a: CatalogAsset): Boolean {
        var attempt = 0
        while (true) {
            try {
                var shownAt = 0L
                store.download(a) { p ->
                    // At most 4 screen updates a second: one per downloaded chunk swamped the UI thread.
                    val now = System.currentTimeMillis()
                    val verifying = p.phase == AssetStore.Progress.Phase.VERIFYING
                    if (now - shownAt >= 250 || verifying || p.bytesDone >= p.bytesTotal) {
                        shownAt = now
                        update(a) { it.copy(bytesDone = p.bytesDone, verifying = verifying) }
                        moved()
                    }
                }
                update(a) { it.copy(done = true, bytesDone = a.sizeBytes, verifying = false) }
                moved()
                refreshPlans()
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val transient = e is AssetStore.DownloadException.Paused || (e is IOException && e !is AssetStore.DownloadException)
                if (transient && attempt++ < 3) { delay(5_000L * attempt); continue }
                update(a) { it.copy(error = team.sopa.boar.ui.I18n.t(getApplication(), team.sopa.boar.engine.UserErrors.downloadKey(e), *team.sopa.boar.engine.UserErrors.downloadArgs(e)), verifying = false, storage = e is AssetStore.DownloadException.Storage) }
                return false
            }
        }
    }

    private fun moved() {
        val s = _state.value
        val now = System.currentTimeMillis()
        val bytes = s.doneBytes
        if (bytes != lastMove.first) lastMove = Pair(bytes, now)
        val start = rateStart ?: Pair(bytes, now).also { rateStart = it }
        val elapsed = (now - start.second) / 1000.0
        val bps = (bytes - start.first) / maxOf(elapsed, 1.0)
        val eta = if (elapsed >= 5 && bps > 0) ((s.totalBytes - bytes) / bps).toLong() else null
        val speed = synchronized(speedSamples) {
            speedSamples.addLast(now to bytes)
            while (speedSamples.size > 2 && now - speedSamples.first().first > SPEED_WINDOW_MS) speedSamples.removeFirst()
            val (t0, b0) = speedSamples.first()
            if (now - t0 >= 1_000) maxOf(0L, (bytes - b0) * 1000 / (now - t0)) else null
        }
        _state.update { it.copy(etaSeconds = eta, speedBytesPerSec = speed ?: it.speedBytesPerSec, stalled = false, tick = it.tick + 1) }
    }

    /** No byte for a minute while a download runs: offer to restart (the Expo wizard's STALL_MS). */
    private fun watchStall() = safeScope.launch(Dispatchers.Default) {
        while (isActive) {
            delay(5_000)
            val s = _state.value
            val running = job?.isActive == true && s.step == SetupSteps.INSTALL && !s.allPresent
            val idle = System.currentTimeMillis() - lastMove.second
            val stalled = running && idle > STALL_MS
            if (stalled != s.stalled) _state.update { it.copy(stalled = stalled) }
            // Nothing moved for a while: the last measured speed is no longer true.
            if (running && idle > SPEED_WINDOW_MS && s.speedBytesPerSec != null) _state.update { it.copy(speedBytesPerSec = null) }
        }
    }

    /** Back in the app: retry what retrying can fix (stalls, network), as the Expo wizard does on AppState active. */
    fun onForeground() {
        val s = _state.value
        if (s.step == SetupSteps.INSTALL && job?.isActive != true && s.failed.isNotEmpty() && s.failed.none { it.storage }) install()
    }

    /** "Restart downloads": drops the running transfer and starts again from the bytes on disk. */
    fun restartDownloads() {
        job?.cancel()
        jobIds = emptyList()
        install()
    }

    /** A failed row's Retry (retries every failed file, from the bytes on disk). */
    fun retry() = install()

    private fun update(a: CatalogAsset, change: (AssetProgress) -> AssetProgress) =
        _state.update { s -> s.copy(downloads = s.downloads.map { if (it.asset.id == a.id) change(it) else it }) }

    /** Runs the index again after it failed (the embedder didn't load, or seeding stopped). */
    fun retryIndex() = startIndex()

    private suspend fun index() {
        _state.update { it.copy(indexPhase = IndexPhase.BUILDING, indexError = null) }
        indexStart = null
        val app = getApplication<Application>()
        val started = System.currentTimeMillis()
        // Whole files only (status checks the size): the index can start while other files are still arriving.
        val emb = services.catalog.models.firstOrNull { it.kind == AssetKind.EMBEDDING && store.status(it).present }
            ?: run { indexFailed(team.sopa.boar.ui.I18n.t(getApplication(), "native.setup.indexNoEmbedder")); return }
        val embedder = LlamaSession.load(services.fileOf(emb).path, indexParams())
            ?: LlamaSession.load(services.fileOf(emb).path, EngineParams(nCtx = 512, threads = 2, embeddings = true))
            ?: run { indexFailed(team.sopa.boar.ui.I18n.t(getApplication(), "native.setup.indexEmbedderFailed")); return }
        try {
            val builtin = app.assets.open("corpus/corpus.json").bufferedReader().use { it.readText() }
            val corpora = services.catalog.corpusCatalog.filter { it.format != SQLITE_PACK && store.status(it).present }
            indexedInputs = corpora.map { it.id }.toSet()
            // A downloaded (sha-checked) prebuilt index for exactly these corpora: merge it, and the seeder below finds
            // nothing left to embed. If it can't be used, the phone indexes as before.
            snapshotsFor(corpora).firstOrNull { store.status(it).present }?.let { snap ->
                val t0 = System.currentTimeMillis()
                runCatching { services.knowledgeBase.importSnapshot(services.fileOf(snap).path, SNAPSHOT_DIMS) }
                    .onSuccess { n -> Log.i(TAG, "index snapshot ${snap.id}: $n articles merged in ${(System.currentTimeMillis() - t0) / 1000.0} s") }
                    .onFailure { e -> Log.w(TAG, "index snapshot ${snap.id} not used: ${e.message}") }
            }
            val collections = listOf(SeedCorpus.Collection(SeedCorpus.BUILTIN, SeedCorpus.docs(SeedCorpus.BUILTIN, builtin))) +
                corpora.map { p ->
                    runCatching { SeedCorpus.Collection(p.id, SeedCorpus.docs(p.id, services.fileOf(p).readText())) }
                        .getOrElse { e -> SeedCorpus.Collection(p.id, emptyList(), e.message) }
                }
            var shownAt = 0L
            val seeder = SeedCorpus(services.knowledgeBase, embedMany = { texts -> embedder.embedBatch(texts) }, batchSize = INDEX_BATCH) { text -> embedder.embed(text) }
            val embedded = seeder.seed(collections, onProgress = { p ->
                val now = System.currentTimeMillis()
                if (now - shownAt >= 250 || p.done >= p.total) {
                    shownAt = now
                    val start = indexStart ?: Pair(p.done, now).also { indexStart = it }
                    val elapsed = (now - start.second) / 1000.0
                    val indexed = p.done - start.first
                    val eta = if (elapsed >= 3 && indexed >= 5) ((p.total - p.done) * elapsed / indexed).toLong() else null
                    _state.update { it.copy(indexDone = p.done, indexTotal = p.total, indexTitle = p.title, indexEtaSeconds = eta, tick = it.tick + 1) }
                }
            })
            Log.i(TAG, "index done: $embedded articles embedded in ${(System.currentTimeMillis() - started) / 1000.0} s (${embedder.params.threads} threads, cpus '${embedder.params.cpus}')")
            services.reloadPacks()
            _state.update { it.copy(indexPhase = IndexPhase.READY) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            indexFailed(team.sopa.boar.ui.I18n.t(getApplication(), "native.setup.indexFailed"))
        } finally {
            // Also after a cancel (Restart, Recovery): the native session must not leak.
            withContext(NonCancellable + Dispatchers.IO) { embedder.close() }
        }
    }

    /**
     * The setup embedder: 4 threads pinned to the fast cores (2 unpinned threads could land on the little ones) and up to
     * [INDEX_BATCH] articles per call. Each article still stops at 512 tokens, as the chat's embedder does.
     */
    private fun indexParams(): EngineParams {
        val topology = runCatching { CpuTopology.read() }.getOrNull()
        val cores = topology?.fastest(INDEX_THREADS).orEmpty()
        return EngineParams(
            nCtx = 512, nBatch = 512, nUbatch = 512, threads = cores.size.takeIf { it > 0 } ?: INDEX_THREADS,
            cpus = if (cores.isNotEmpty() && topology != null) topology.cpuList(cores) else "", embeddings = true, nSeqMax = INDEX_BATCH,
        )
    }

    private fun indexFailed(message: String) = _state.update { it.copy(indexPhase = IndexPhase.ERROR, indexError = message) }

    /** Whether the app can answer: everything required and an answer model on disk (requiredModelsPresent). */
    fun ready(): Boolean = store.requiredPresent()

    /** The answer model's tier, for its friendly name ("More accurate" / "Fast"). Kept for older callers. */
    fun tierOf(m: CatalogAsset) = if (m.answerTier == AnswerTier.COMPACT) "compact" else "default"

    private companion object {
        const val STALL_MS = 60_000L
        const val SPEED_WINDOW_MS = 5_000L
        const val TAG = "BoarSetup"
        const val SQLITE_PACK = "sqlite-pack"
        const val KB_SNAPSHOT = "kb-snapshot"
        /** bge-small-en-v1.5: the vector size a prebuilt index must have. */
        const val SNAPSHOT_DIMS = 384
        const val INDEX_THREADS = 4
        /** Articles handed to one embedBatch call; the native side packs them into 512-token decodes. */
        const val INDEX_BATCH = 8
    }
}
