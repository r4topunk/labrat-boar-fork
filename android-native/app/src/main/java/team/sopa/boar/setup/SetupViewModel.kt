package team.sopa.boar.setup

import android.app.Application
import android.content.res.AssetManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.safeScope
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.AssetStore
import team.sopa.boar.engine.models.BundleEntry
import team.sopa.boar.engine.models.BundleInstaller
import team.sopa.boar.engine.models.BundleManifest
import team.sopa.boar.engine.models.BundleSource
import team.sopa.boar.engine.models.DirBundleSource
import team.sopa.boar.engine.rag.SeedCorpus
import java.io.File
import java.io.InputStream

/** Where first-run setup is. */
enum class SetupPhase {
    /** Looking for the bundle and at what is already installed. */
    CHECKING,
    /** Copying files out of the bundle. */
    COPYING,
    /** Merging the prebuilt search index and opening the packs. */
    INDEXING,
    DONE,
    /** Neither the APK nor the adb-pushed folder holds a bundle, and the phone lacks files. */
    NO_DATA,
    NO_SPACE,
    FAILED,
}

/** What the "Unpacking BoarLite" screen shows. */
data class SetupState(
    val phase: SetupPhase = SetupPhase.CHECKING,
    /** The file being copied, by its catalog name ("Qwen3 1.7B"). */
    val file: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** NO_SPACE: what the install needs and what the phone has free. */
    val neededBytes: Long = 0,
    val freeBytes: Long = 0,
    /** NO_DATA: the adb command that fills the fallback folder. FAILED: what went wrong. */
    val detail: String? = null,
)

/** The bundle inside the APK (assets/bundle/, present when the build had -Pboar.bundleDir). */
private class ApkBundleSource(private val assets: AssetManager) : BundleSource {
    override val name = "apk"
    override fun manifest(): String? = runCatching { assets.open("$DIR/${DirBundleSource.MANIFEST}").bufferedReader().use { it.readText() } }.getOrNull()
    override fun open(path: String): InputStream = assets.open("$DIR/$path", AssetManager.ACCESS_STREAMING)

    companion object { const val DIR = "bundle" }
}

/**
 * First run: installs BoarLite's data from its own bundle (BundleInstaller), then merges the prebuilt search index,
 * opens the packs and selects the one answer model, and the chat opens. BoarLite has no network: the bundle comes from
 * the APK, or, for a build without one, from `<external files>/bundle` filled by adb push (deleted once installed).
 * The JSON Wikipedia corpora are always copied from the APK's assets/corpus.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    private val catalog = services.catalog
    private val store = AssetStore(app.filesDir, catalog)
    private val installer = BundleInstaller(app.filesDir, catalog, store, log = { Log.i(TAG, it) })
    private val apk = ApkBundleSource(app.assets)
    private val external: File? get() = getApplication<Application>().getExternalFilesDir(null)?.let { File(it, ApkBundleSource.DIR) }
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state
    private var job: Job? = null

    /** The APK's bundle, read once: its fingerprint decides whether a launch installs again. */
    private val apkManifest: String? by lazy { apk.manifest() }

    /** Whether the app can open the chat: the answer model is on the phone and a bundle was installed in full. */
    fun ready(): Boolean = !installer.needsInstall(apkManifest?.let(BundleManifest::fingerprint))

    /** Runs setup unless it is running: the screen calls it when it shows. */
    fun start() {
        if (job?.isActive == true) return
        if (_state.value.phase == SetupPhase.DONE) return
        job = safeScope.launch(Dispatchers.IO) { run() }
    }

    /** Settings → Run setup again: a pack removed since comes back from the bundle, and the index is merged again. */
    fun restart() {
        job?.cancel()
        _state.value = SetupState()
    }

    /** Retry after a failure, or after freeing space. */
    fun retry() {
        restart()
        start()
    }

    /** Back in the app: a tester may have pushed the bundle, or freed space, while it was away. */
    fun onForeground() {
        if (_state.value.phase in setOf(SetupPhase.NO_DATA, SetupPhase.NO_SPACE)) retry()
    }

    /** The adb command that fills the fallback folder (for testers with a build that carries no bundle). */
    fun pushCommand(): String {
        val pkg = getApplication<Application>().packageName
        return "adb push <outDir>/bundle /sdcard/Android/data/$pkg/files/"
    }

    private suspend fun run() {
        val started = System.currentTimeMillis()
        _state.value = SetupState(SetupPhase.CHECKING)
        try {
            val source = installer.choose(apk, external?.let(::DirBundleSource))
            val manifest = source?.manifest()
            val bundled = manifest?.let { installer.resolve(BundleManifest.parse(it)) }.orEmpty()
            // The JSON corpora ship as plain APK assets in every build, bundle or not.
            val builtIn = catalog.models.filter { it.kind == AssetKind.CORPUS && it.format == null }
                .map { BundleInstaller.Item(BundleEntry(it.filename, it.sizeBytes, it.sha256), it) }
            if (source == null && installer.needsInstall(null)) {
                Log.w(TAG, "no bundle in the APK or in ${external?.path}")
                _state.value = SetupState(SetupPhase.NO_DATA, detail = pushCommand())
                return
            }
            Log.i(TAG, "bundle source: ${source?.name ?: "none (already installed)"}")
            val names = catalog.models.associate { it.filename to it.shortName }
            var shownAt = 0L
            val result = installer.install(bundled + builtIn, open = { item ->
                if (item in builtIn) getApplication<Application>().assets.open("corpus/" + item.entry.path.substringAfterLast('/'), AssetManager.ACCESS_STREAMING)
                else source!!.open(item.entry.path)
            }) { p ->
                val now = System.currentTimeMillis()
                if (now - shownAt >= 100 || p.bytesDone >= p.bytesTotal) {
                    shownAt = now
                    _state.value = SetupState(SetupPhase.COPYING, names[p.file] ?: p.file, p.fileIndex, p.fileCount, p.bytesDone, p.bytesTotal)
                }
            }
            _state.value = SetupState(SetupPhase.INDEXING)
            prepareSearch()
            services.settings.setActiveModelId("llm", catalog.answerModel.id)
            catalog.models.firstOrNull { it.kind == AssetKind.EMBEDDING && it.required }?.let { services.settings.setActiveModelId("embedding", it.id) }
            if (manifest != null) installer.markInstalled(BundleManifest.fingerprint(manifest))
            // The adb-pushed copy is a second copy of every file: gone once the install is verified.
            if (source != null && source !== apk) { source.discard(); Log.i(TAG, "removed the pushed bundle at ${source.name}") }
            Log.i(TAG, "bundle installed in ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)} s " +
                "(${result.copied} files copied, ${result.bytesCopied / 1_000_000} MB; ${result.skipped} already installed; source ${source?.name ?: "none"})")
            _state.value = SetupState(SetupPhase.DONE)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: BundleInstaller.NotEnoughSpace) {
            Log.w(TAG, e.message ?: "not enough space")
            _state.value = SetupState(SetupPhase.NO_SPACE, neededBytes = e.neededBytes, freeBytes = e.freeBytes)
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            _state.value = SetupState(SetupPhase.FAILED, detail = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * The prebuilt index (embedded with the catalog's search model) is merged into the knowledge base, so the seeder
     * below finds the built-in and Wikipedia articles already there and embeds nothing; if the index can't be used, the
     * seeder embeds them on the phone, which takes minutes, not seconds. Then the packs open with their new files.
     */
    private suspend fun prepareSearch() {
        val app = getApplication<Application>()
        val kb = services.knowledgeBase
        val embedderSha = catalog.models.firstOrNull { it.kind == AssetKind.EMBEDDING && it.required }?.sha256
        catalog.models.filter { it.kind == AssetKind.INDEX && it.format == KB_SNAPSHOT && it.embeddingModelSha256 == embedderSha && store.status(it).present }
            .forEach { snap ->
                val t0 = System.currentTimeMillis()
                runCatching { kb.importSnapshot(services.fileOf(snap).path, SNAPSHOT_DIMS) }
                    .onSuccess { n -> Log.i(TAG, "index snapshot ${snap.id}: $n articles merged in ${(System.currentTimeMillis() - t0) / 1000.0} s") }
                    .onFailure { e -> Log.w(TAG, "index snapshot ${snap.id} not used: ${e.message}") }
            }
        val builtin = app.assets.open("corpus/corpus.json").bufferedReader().use { it.readText() }
        val corpora = catalog.corpusCatalog.filter { it.format == null && store.status(it).present }
        val collections = listOf(SeedCorpus.Collection(SeedCorpus.BUILTIN, SeedCorpus.docs(SeedCorpus.BUILTIN, builtin))) +
            corpora.map { p ->
                runCatching { SeedCorpus.Collection(p.id, SeedCorpus.docs(p.id, services.fileOf(p).readText())) }
                    .getOrElse { e -> SeedCorpus.Collection(p.id, emptyList(), e.message) }
            }
        val t0 = System.currentTimeMillis()
        val embedded = SeedCorpus(kb) { text -> services.embed(text) }.seed(collections)
        Log.i(TAG, "index ready: $embedded articles embedded on the phone in ${(System.currentTimeMillis() - t0) / 1000.0} s")
        services.reloadPacks()
    }

    private companion object {
        const val TAG = "BoarSetup"
        const val KB_SNAPSHOT = "kb-snapshot"
        /** bge-small-en-v1.5: the vector size a prebuilt index must have. */
        const val SNAPSHOT_DIMS = 384
    }
}
