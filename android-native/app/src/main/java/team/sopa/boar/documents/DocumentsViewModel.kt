package team.sopa.boar.documents

import team.sopa.boar.app.safeScope

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.rag.DocumentImport
import team.sopa.boar.engine.rag.KnowledgeBase

data class DocumentsState(
    val collections: List<KnowledgeBase.Collection> = emptyList(),
    /** null, "reading", "chunking" or "embedding". */
    val stage: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/** My Documents (port of PersonalDocumentsManager.tsx): import files into a collection, switch it on or off, remove it. */
class DocumentsViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    private val _state = MutableStateFlow(DocumentsState())
    val state: StateFlow<DocumentsState> = _state

    init { refresh() }

    fun refresh() = _state.update { it.copy(collections = services.knowledgeBase.collections()) }

    fun import(uris: List<Uri>, name: String) {
        if (uris.isEmpty() || _state.value.stage != null) return
        _state.update { it.copy(stage = "reading", error = null, done = 0, total = 0) }
        safeScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val files = uris.map { uri ->
                    var fileName = uri.lastPathSegment ?: "document"
                    var size = 0L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) { fileName = c.getString(0) ?: fileName; size = c.getLong(1) }
                    }
                    if (size <= 0) size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
                    // Only what BOAR can read: anything else (an .html page, a .docx) would go in as raw code, found by nothing.
                    if (fileName.substringAfterLast('.', "").lowercase() !in READABLE) throw Failed(text("native.documents.unsupported", fileName))
                    if (DocumentImport.tooLargeToRead(size)) throw TooLarge(fileName)
                    val text = try {
                        if (fileName.lowercase().endsWith(".pdf")) pdfText(uri, fileName)
                        else resolver.openInputStream(uri)?.use { readCapped(it) ?: throw TooLarge(fileName) }?.toString(Charsets.UTF_8) ?: throw Failed(text("native.documents.unreadable", fileName))
                    } catch (e: OutOfMemoryError) {
                        // Not an Exception: uncaught, it closes the app. The file's text is dropped with the frame.
                        throw TooLarge(fileName)
                    }
                    DocumentImport.File(fileName, if (size > 0) size else text.toByteArray().size.toLong(), text)
                }
                _state.update { it.copy(stage = "chunking") }
                DocumentImport.import(services.knowledgeBase, services::embed, files, name, onProgress = { d, t ->
                    _state.update { it.copy(stage = "embedding", done = d, total = t) }
                })
            } catch (e: TooLarge) {
                _state.update { it.copy(error = tooLarge(e.fileName)) }
            } catch (e: DocumentImport.TooLarge) {
                _state.update { it.copy(error = tooLarge(e.fileName)) }
            } catch (e: Failed) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                // Storage or embedding failures: a plain line, not the exception text.
                _state.update { it.copy(error = text("native.documents.failed")) }
            }
            _state.update { it.copy(stage = null) }
            refresh()
        }
    }

    private class TooLarge(val fileName: String) : Exception()

    private companion object {
        /** The extensions the importer reads (the screen's note lists them). */
        val READABLE = setOf("txt", "md", "markdown", "csv", "json", "pdf")
    }

    /** An IOException, so PDDocument.load closes and deletes its scratch file before it reaches [pdfText]. */
    private class InputTooLarge : java.io.IOException()

    /** A failure the user can act on, already in their language. */
    private class Failed(message: String) : Exception(message)

    private fun text(key: String, name: String? = null) = team.sopa.boar.ui.I18n.t(getApplication(), key, "name" to name)

    private fun tooLarge(fileName: String) =
        team.sopa.boar.ui.I18n.t(getApplication(), "native.documents.tooLarge", "name" to fileName, "mb" to DocumentImport.READ_LIMIT / 1_000_000)

    /**
     * The PDF's selectable text (no OCR); password-protected PDFs aren't supported, as in the Expo app. PDFBox keeps the
     * file in a temp file in the cache, not on the heap, and writes the text in one pass, so a large PDF doesn't fill
     * the memory a loaded model needs. Both the file and its text stop at [DocumentImport.READ_LIMIT], also when the
     * picker didn't say how big the file is.
     */
    private fun pdfText(uri: Uri, fileName: String): String {
        val app = getApplication<Application>()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val input = app.contentResolver.openInputStream(uri) ?: throw Failed(text("native.documents.unreadable", fileName))
        val memory = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().setTempDir(app.cacheDir)
        val limit = DocumentImport.READ_LIMIT
        return try {
            input.use { raw ->
                val bounded = object : java.io.FilterInputStream(raw) {
                    private var read = 0L
                    private fun count(n: Int): Int { if (n > 0) { read += n; if (read > limit) throw InputTooLarge() }; return n }
                    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
                }
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(bounded, memory).use { doc ->
                    val out = StringBuilder()
                    val writer = object : java.io.Writer() {
                        override fun write(cbuf: CharArray, off: Int, len: Int) {
                            out.append(cbuf, off, len)
                            if (out.length > limit) throw TooLarge(fileName)
                        }
                        override fun flush() {}
                        override fun close() {}
                    }
                    com.tom_roush.pdfbox.text.PDFTextStripper().writeText(doc, writer)
                    out.toString()
                }
            }
        } catch (e: InputTooLarge) {
            throw TooLarge(fileName)
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw Failed(text("native.documents.passwordProtected", fileName))
        } catch (e: java.io.IOException) {
            throw Failed(text("native.documents.unreadable", fileName))
        }.also { if (it.isBlank()) throw Failed(text("native.documents.noText", fileName)) }
    }

    fun setActive(id: String, on: Boolean) {
        services.knowledgeBase.setCollectionActive(id, on)
        refresh()
    }

    /** exportCollection: the collection's passages as JSON, handed to the share sheet. */
    suspend fun exportIntent(col: KnowledgeBase.Collection): android.content.Intent = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        // A folder per export: the file keeps the collection's name, and a later export can't overwrite one still being shared.
        val dir = java.io.File(app.cacheDir, "exports/${System.currentTimeMillis()}").apply { mkdirs() }
        val file = java.io.File(dir, DocumentImport.slug(col.name).ifEmpty { "collection" } + ".json")
        file.writeText(DocumentImport.exportJson(services.knowledgeBase.collectionDocs(col.id)))
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, team.sopa.boar.ui.I18n.t(app, "native.documents.shareTitle", "name" to col.name))
    }

    fun remove(id: String) {
        safeScope.launch(Dispatchers.IO) { services.knowledgeBase.deleteCollection(id); refresh() }
    }

    /** Reads at most [DocumentImport.READ_LIMIT] bytes; null as soon as the file is longer (its size wasn't known up front). */
    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > DocumentImport.READ_LIMIT) return null
            out.write(buf, 0, n)
        }
    }
}
