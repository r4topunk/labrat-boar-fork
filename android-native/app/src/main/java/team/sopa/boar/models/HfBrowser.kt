package team.sopa.boar.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.HfQuery
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Searching Hugging Face for GGUF models beyond the curated catalog (src/services/modelBrowser.ts). One of the two
 * places BOAR uses the network, only when the user searches here; never during chat. Results aren't vetted: the
 * download is pinned to the commit that was listed, and checked against the LFS sha256 when Hugging Face gives one.
 */
object HfBrowser {
    private const val API = HfQuery.API

    /** A repo as listed, with the facts from its GGUF header: parameter count, architecture, context length. */
    /** [gated]: the repo needs a Hugging Face account and its license accepted ("manual"/"auto"), which BOAR can't download. */
    data class Repo(val id: String, val downloads: Long?, val likes: Long?, val params: Long? = null, val architecture: String? = null, val contextLength: Long? = null, val tags: List<String> = emptyList(),
        val gated: Boolean = false)
    data class GgufFile(val filename: String, val sizeBytes: Long, val sha256: String?, val revision: String?)
    data class Page(val repos: List<Repo>, val next: String?)

    private fun get(url: String): Pair<String, String?> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000 }
        try {
            if (c.responseCode !in 200..299) error("Hugging Face answered ${c.responseCode}")
            return c.inputStream.bufferedReader().readText() to c.getHeaderField("Link")
        } finally {
            c.disconnect()
        }
    }

    /**
     * One page of the search ([HfQuery.searchUrl], or the `next` of the previous page): text-generation GGUF repos
     * whose architecture BOAR can chat with, without uncensored ones unless the build allows them. A page can come back shorter than asked, or empty with a next page.
     */
    fun browse(url: String): Page {
        require(url.startsWith("$API/models?")) { "Not a Hugging Face model listing: $url" }
        val (body, link) = get(url)
        val repos = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }.map { o ->
            val g = o["gguf"] as? JsonObject
            val tags = (o["tags"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            Repo(o.str("id") ?: "", o.num("downloads"), o.num("likes"), g?.num("total"), g?.str("architecture"), g?.num("context_length"), tags,
                gated = (o["gated"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != "false" })
        }.filter { it.id.isNotEmpty() && HfQuery.chatArchitecture(it.architecture) }
            // Uncensored and adult models only in builds with ALLOW_UNCENSORED_MODELS=true (app/build.gradle.kts).
            .filter { team.sopa.boar.BuildConfig.ALLOW_UNCENSORED_MODELS || !HfQuery.uncensored(it.id, it.tags) }
        return Page(repos, HfQuery.nextUrl(link))
    }

    fun search(query: String): List<Repo> = browse(HfQuery.searchUrl(query, HfQuery.Size.ANY, HfQuery.Sort.DOWNLOADS)).repos

    private val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")

    /** "owner/name" only: anything else could reach another API path. */
    fun checkRepo(repoId: String): String { require(REPO.matches(repoId) && ".." !in repoId) { "Not a Hugging Face repo id: $repoId" }; return repoId }

    private fun enc(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    private fun repoPath(repoId: String) = checkRepo(repoId).split("/").joinToString("/") { enc(it) }

    fun files(repoId: String): List<GgufFile> {
        val o = Json.parseToJsonElement(get("$API/models/${repoPath(repoId)}?blobs=true").first).jsonObject
        val revision = o.str("sha")?.takeIf { Regex("^[0-9a-f]{40}$").matches(it) }
        return (o["siblings"] as? JsonArray).orEmpty().map { it.jsonObject }
            .map { GgufFile(it.str("rfilename") ?: "", it.num("size") ?: 0, (it["lfs"] as? JsonObject)?.str("oid"), revision) }
            .filter { HfQuery.addableFile(it.filename, it.sizeBytes) }
            .sortedBy { it.sizeBytes }
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** toCatalogModel: the same id and file name as the Expo app, so a model added in either is the same file. */
    fun toAsset(repoId: String, f: GgufFile): CatalogAsset {
        require(".." !in f.filename && !f.filename.startsWith("/")) { "Unsafe file name: ${f.filename}" }
        // The Expo app's id (cut at 120 characters); a longer one gets a short hash so two long names can't collide.
        val full = "hf-${slug(repoId)}-${slug(f.filename)}"
        val id = if (full.length <= 120) full else full.take(111) + "-" + java.security.MessageDigest.getInstance("SHA-1").digest(full.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        return CatalogAsset(
            id = id, kind = AssetKind.LLM, label = f.filename.removeSuffix(".gguf").removeSuffix(".GGUF"),
            filename = "models/$id.gguf", sizeBytes = f.sizeBytes, sha256 = f.sha256 ?: "",
            sourceUrl = "https://huggingface.co/${repoPath(repoId)}/resolve/${enc(f.revision ?: "main")}/${f.filename.split("/").joinToString("/") { enc(it) }}",
            license = "Unknown — check the model's Hugging Face page",
            description = "$repoId — added via model search, not vetted by BOAR", required = false,
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
}
