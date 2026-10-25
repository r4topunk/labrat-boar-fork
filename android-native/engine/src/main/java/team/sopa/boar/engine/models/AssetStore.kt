package team.sopa.boar.engine.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Installed assets on disk: status and sha256 verification (BoarLite has no network permission, so files only
 * arrive from the app's own bundle).
 *
 * Layout: assets live at `<filesDir>/<asset.filename>` and verified files are listed in
 * `<filesDir>/integrity.json` as `{filename: {size, mtime (seconds), sha256}}`.
 */
class AssetStore(
    private val filesDir: File,
    private val catalog: Catalog,
    private val log: (String) -> Unit = {},
) {
    data class Status(
        val asset: CatalogAsset,
        val present: Boolean,
        val sizeOnDiskBytes: Long,
        /** true = sha256 verified on this phone for this exact file; null = not checked yet. */
        val checksumOk: Boolean?,
        /** Bytes of an interrupted copy, smaller than the asset. */
        val partialBytes: Long = 0,
    )

    data class Progress(val bytesDone: Long, val bytesTotal: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val integrityFile get() = File(filesDir, "integrity.json")

    fun fileOf(asset: CatalogAsset) = File(filesDir, asset.filename)

    fun status(asset: CatalogAsset): Status {
        val f = fileOf(asset)
        if (!f.exists()) return Status(asset, false, 0, null)
        val size = f.length()
        if (size != asset.sizeBytes) {
            // Smaller = an interrupted copy. Larger can't be a prefix: delete it.
            if (size < asset.sizeBytes) return Status(asset, false, 0, null, partialBytes = size)
            log("${asset.id}: deleting oversized file ($size > ${asset.sizeBytes})")
            f.delete()
            return Status(asset, false, 0, null)
        }
        val rec = loadVerified()[asset.filename]
        val verified = rec != null && rec.size == size && sameMtime(rec.mtime, f) && rec.sha256.equals(asset.sha256, ignoreCase = true)
        return Status(asset, true, size, if (verified) true else null)
    }

    /** Setup is complete: every required asset (the search model and the answer model) is on disk with the right size. */
    fun requiredPresent(): Boolean = catalog.requiredModels.all { status(it).present }

    /** Streams the installed file through SHA-256 and remembers a match. */
    suspend fun verify(asset: CatalogAsset, onProgress: (Progress) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (asset.sha256.isEmpty()) return@withContext true
        val f = fileOf(asset)
        val md = MessageDigest.getInstance("SHA-256")
        hashInto(md, f, f.length()) { done -> onProgress(Progress(done, f.length())) }
        val ok = md.digest().hex().equals(asset.sha256, ignoreCase = true)
        if (ok) recordVerified(asset) else forgetVerified(asset)
        log("${asset.id}: sha256 ${if (ok) "ok" else "MISMATCH"}")
        ok
    }

    /**
     * Records [asset] as verified without reading it again: for a file whose sha256 the caller has just computed while
     * writing it (the bundle installer hashes each file as it copies it).
     */
    fun markVerified(asset: CatalogAsset) = recordVerified(asset)

    fun delete(asset: CatalogAsset) {
        fileOf(asset).delete()
        forgetVerified(asset)
    }

    // integrity.json, in the format earlier builds wrote.
    @Serializable
    private data class VerifiedRecord(val size: Long, val mtime: Double, val sha256: String)

    private fun loadVerified(): MutableMap<String, VerifiedRecord> = runCatching {
        json.decodeFromString<Map<String, VerifiedRecord>>(integrityFile.readText()).toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun saveVerified(records: Map<String, VerifiedRecord>) {
        runCatching { integrityFile.writeText(json.encodeToString(records)) }
    }

    private fun recordVerified(asset: CatalogAsset) {
        val f = fileOf(asset)
        if (!f.exists()) return
        val records = loadVerified()
        records[asset.filename] = VerifiedRecord(f.length(), f.lastModified() / 1000.0, asset.sha256)
        saveVerified(records)
    }

    private fun forgetVerified(asset: CatalogAsset) {
        val records = loadVerified()
        if (records.remove(asset.filename) != null) saveVerified(records)
    }

    private fun sameMtime(seconds: Double, f: File) = abs(seconds - f.lastModified() / 1000.0) < 0.002

    private companion object {
        fun hashInto(md: MessageDigest, f: File, bytes: Long, onChunk: (Long) -> Unit) {
            f.inputStream().buffered(1 shl 20).use { input ->
                val buf = ByteArray(1 shl 20)
                var left = bytes
                var done = 0L
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    md.update(buf, 0, n)
                    left -= n
                    done += n
                    onChunk(done)
                }
            }
        }

        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
