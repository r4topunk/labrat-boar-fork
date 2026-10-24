package team.sopa.boar.models

import team.sopa.boar.app.safeScope

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.AssetStore
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.rag.SeedCorpus

/** One pack row: whether its file is on the phone with the right size. */
data class Row(val present: Boolean = false)

data class PacksState(
    /** Every knowledge pack of the catalog, as the catalog lists them. */
    val items: List<CatalogAsset> = emptyList(),
    val rows: Map<String, Row> = emptyMap(),
)

/** The knowledge packs on the phone (the Knowledge screen): which are installed, and removing one. */
class PacksViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    private val store = AssetStore(app.filesDir, services.catalog)
    private val _state = MutableStateFlow(PacksState())
    val state: StateFlow<PacksState> = _state

    init { refresh() }

    /** Reads each pack's file state off the main thread (AssetStore.status reads integrity.json and can delete an oversized file). */
    fun refresh() {
        safeScope.launch(Dispatchers.IO) {
            val items = services.catalog.corpusCatalog
            val rows = items.associate { a -> a.id to Row(store.status(a).present) }
            _state.update { PacksState(items, rows) }
        }
    }

    fun remove(a: CatalogAsset) {
        safeScope.launch(Dispatchers.IO) {
            if (a.kind == AssetKind.CORPUS && a.format != "sqlite-pack") services.knowledgeBase.deleteSeedChunksWithPrefix(SeedCorpus.chunkIdPrefix(a.id))
            if (a.kind == AssetKind.CORPUS) services.reloadPacks()
            store.delete(a)
            refresh()
        }
    }
}
