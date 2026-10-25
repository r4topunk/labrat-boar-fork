package team.sopa.boar.engine.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** One file of the bundle: where it is installed under the app's files folder, its size and its sha256. */
@Serializable
data class BundleEntry(val path: String, val sizeBytes: Long, val sha256: String)

/**
 * The bundle's manifest.json (written by scripts/stage-bundle.sh): a JSON array of [BundleEntry], one per file, each
 * path the file's catalog filename. A malformed manifest throws, so a broken build never installs half a bundle.
 */
object BundleManifest {
    private val json = Json { ignoreUnknownKeys = true }
    private val SHA = Regex("[0-9a-f]{64}")

    fun parse(text: String): List<BundleEntry> {
        val entries = json.decodeFromString<List<BundleEntry>>(text)
        require(entries.isNotEmpty()) { "the bundle manifest lists no files" }
        for (e in entries) {
            require(e.path.isNotBlank() && !e.path.startsWith("/") && e.path.split('/').none { it == ".." || it.isEmpty() }) { "bad path in the bundle manifest: '${e.path}'" }
            require(e.sizeBytes > 0) { "${e.path}: size must be positive" }
            require(e.sha256.lowercase().matches(SHA)) { "${e.path}: sha256 is not 64 hex digits" }
        }
        require(entries.map { it.path }.toSet().size == entries.size) { "the bundle manifest lists a file twice" }
        return entries
    }

    /** The sha256 of the manifest text: names the bundle an install came from, so a newer APK's bundle installs again. */
    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Where the bundle's files are read from: the APK's own assets, or a folder a tester filled with adb push. */
interface BundleSource {
    /** For the logs: "apk" or the folder's path. */
    val name: String

    /** The manifest text, or null when this source holds no bundle. */
    fun manifest(): String?

    fun open(path: String): InputStream

    /** Frees the space this source takes once its files are installed; the APK's own assets can't be freed. */
    fun discard() {}
}

/** A bundle in a plain folder (`<external files>/bundle`, filled by adb push). Discarded after a verified install. */
class DirBundleSource(private val dir: File) : BundleSource {
    override val name: String get() = dir.path
    override fun manifest(): String? = File(dir, MANIFEST).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    override fun open(path: String): InputStream = File(dir, path).inputStream()
    override fun discard() { dir.deleteRecursively() }

    companion object { const val MANIFEST = "manifest.json" }
}

/**
 * Installs LabRat's data (the answer model, the search model, the knowledge packs and the prebuilt index) from its
 * bundle into the files folder, where [AssetStore] and the app expect them. LabRat has no network: this copy is the
 * only way files arrive.
 *
 * Each file is streamed to `<filename>.part` and hashed as it is written; only a full-size copy with the catalog's
 * sha256 is renamed into place and recorded in integrity.json, as [AssetStore.verify] would record it. A file already
 * verified is skipped, so an interrupted install resumes at the file it stopped in. Free space is checked before the
 * first byte is written, so a full phone gets a clear message instead of a half-copied model.
 */
class BundleInstaller(
    private val filesDir: File,
    private val catalog: Catalog,
    private val store: AssetStore = AssetStore(filesDir, catalog),
    /** Bytes free on the volume of [filesDir]. */
    private val freeBytes: () -> Long = { filesDir.usableSpace },
    private val log: (String) -> Unit = {},
) {
    /** A bundle file and the catalog asset it installs as. */
    data class Item(val entry: BundleEntry, val asset: CatalogAsset)

    data class Progress(val file: String, val fileIndex: Int, val fileCount: Int, val bytesDone: Long, val bytesTotal: Long)

    data class Result(val copied: Int, val skipped: Int, val bytesCopied: Long)

    class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) :
        Exception("not enough free space: the install needs $neededBytes bytes and the phone has $freeBytes free")

    class Mismatch(path: String, why: String) : Exception("$path: $why")

    private val markerFile get() = File(filesDir, MARKER)

    /** The first source that holds a bundle: the APK's own, else the adb-pushed folder; null when neither does. */
    fun choose(vararg sources: BundleSource?): BundleSource? = sources.firstOrNull { it?.manifest() != null }

    /**
     * Pairs every manifest entry with its catalog asset. An entry the catalog doesn't know, or whose size or sha256
     * differs from the catalog's, is a broken build: it throws rather than installing a file the app would then reject
     * (AssetStore deletes a file larger than its catalog size).
     */
    fun resolve(entries: List<BundleEntry>): List<Item> = entries.map { e ->
        val asset = catalog.models.firstOrNull { it.filename == e.path } ?: throw Mismatch(e.path, "not in the catalog")
        if (asset.sizeBytes != e.sizeBytes) throw Mismatch(e.path, "size ${e.sizeBytes} in the bundle, ${asset.sizeBytes} in the catalog")
        if (!asset.sha256.equals(e.sha256, ignoreCase = true)) throw Mismatch(e.path, "sha256 differs from the catalog")
        Item(e, asset)
    }

    /** Catalog-listed files of [items] that are not verified on disk yet. */
    fun pending(items: List<Item>): List<Item> = items.filter { store.status(it.asset).checksumOk != true }

    /**
     * Copies what is missing. A file of the right size that integrity.json doesn't vouch for (a record lost with an
     * older build) is hashed in place first and kept when it matches; anything else is copied again from [open].
     */
    suspend fun install(items: List<Item>, open: (Item) -> InputStream, onProgress: (Progress) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val todo = ArrayList<Item>()
        for (item in pending(items)) {
            coroutineContext.ensureActive()
            val status = store.status(item.asset)
            if (status.present && store.verify(item.asset)) continue
            store.delete(item.asset)
            partOf(item.asset).delete()
            todo += item
        }
        val needed = todo.sumOf { it.entry.sizeBytes }
        val free = freeBytes()
        if (todo.isNotEmpty() && free < needed + SPACE_MARGIN_BYTES) throw NotEnoughSpace(needed + SPACE_MARGIN_BYTES, free)
        var done = 0L
        todo.forEachIndexed { i, item ->
            copyOne(item, open) { n -> onProgress(Progress(item.entry.path, i + 1, todo.size, done + n, needed)) }
            done += item.entry.sizeBytes
            onProgress(Progress(item.entry.path, i + 1, todo.size, done, needed))
        }
        log("bundle: ${todo.size} files copied (${done / 1_000_000} MB), ${items.size - todo.size} already installed")
        Result(todo.size, items.size - todo.size, done)
    }

    private fun partOf(asset: CatalogAsset) = File(filesDir, asset.filename + ".part")

    private suspend fun copyOne(item: Item, open: (Item) -> InputStream, onBytes: (Long) -> Unit) {
        val asset = item.asset
        val part = partOf(asset).apply { parentFile?.mkdirs() }
        val md = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            open(item).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (written + n > asset.sizeBytes) throw Mismatch(item.entry.path, "longer than its ${asset.sizeBytes} bytes")
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        written += n
                        if (written - lastReport >= REPORT_EVERY_BYTES) { lastReport = written; onBytes(written) }
                    }
                }
            }
            if (written != asset.sizeBytes) throw Mismatch(item.entry.path, "$written bytes copied, ${asset.sizeBytes} expected")
            val digest = md.digest().joinToString("") { "%02x".format(it) }
            if (!digest.equals(asset.sha256, ignoreCase = true)) throw Mismatch(item.entry.path, "sha256 $digest, catalog ${asset.sha256}")
            val target = store.fileOf(asset)
            target.delete()
            if (!part.renameTo(target)) throw Mismatch(item.entry.path, "could not move the copy into place")
            store.markVerified(asset)
        } catch (e: Throwable) {
            // A bad or interrupted copy never stays half-written next to the real file name.
            part.delete()
            throw e
        }
    }

    /** The fingerprint of the bundle last installed in full; null on a fresh phone or after Purge. */
    fun installedFingerprint(): String? =
        runCatching { markerFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } }.getOrNull()

    fun markInstalled(fingerprint: String) { markerFile.writeText(fingerprint) }

    /**
     * Whether launch must run the installer: the app can't answer yet, no bundle was ever installed in full, or this APK
     * carries a different bundle ([apkFingerprint], null when it carries none) than the one installed.
     */
    fun needsInstall(apkFingerprint: String?): Boolean {
        if (!store.requiredPresent()) return true
        val installed = installedFingerprint() ?: return true
        return apkFingerprint != null && apkFingerprint != installed
    }

    companion object {
        const val MARKER = "bundle-installed.txt"
        const val BUFFER_BYTES = 1 shl 20
        private const val REPORT_EVERY_BYTES = 4L shl 20
        /** Room left after the copy for the knowledge base's merge of the prebuilt index and the chats. */
        const val SPACE_MARGIN_BYTES = 200L shl 20
    }
}
