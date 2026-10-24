package team.sopa.boar.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.models.AssetStore
import team.sopa.boar.engine.models.CatalogAsset

/** What the first-run screen shows: the required files (the answer model and the search model) still missing from the phone. */
data class SetupState(val missing: List<CatalogAsset> = emptyList()) {
    val missingBytes get() = missing.sumOf { it.sizeBytes }
}

/**
 * The first-run gate. BoarLite has no network, so nothing is downloaded here: the app is ready once the answer model
 * and the search model are on disk. This is a stub: the installer that copies them (and the knowledge packs) from the
 * app's own bundle replaces it.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    private val store = AssetStore(app.filesDir, services.catalog)
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state

    /** Setup never resumes in a half-finished step now: there are no steps. */
    val unfinished = false

    init { refresh() }

    fun refresh() = _state.update { SetupState(services.catalog.requiredModels.filter { !store.status(it).present }) }

    /** Whether the app can answer: everything required is on disk. */
    fun ready(): Boolean = store.requiredPresent()

    /** Settings → Run setup again: look at the phone again. */
    fun restart() = refresh()

    /** Setup is over (finished, or left from Settings). Nothing is saved between runs now. */
    fun forget() {}

    /** Back in the app: the files may have arrived while it was away. */
    fun onForeground() = refresh()
}
