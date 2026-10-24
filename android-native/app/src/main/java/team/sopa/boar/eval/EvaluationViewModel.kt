package team.sopa.boar.eval

import team.sopa.boar.app.safeScope

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.eval.EvalConfig
import team.sopa.boar.engine.eval.EvalQuery
import team.sopa.boar.engine.routing.Classify
import team.sopa.boar.engine.routing.TaskType
import java.io.File

data class EvaluationState(
    val configs: List<EvalConfig.Model>? = null,
    val selected: Set<String> = emptySet(),
    val queries: List<EvalQuery> = emptyList(),
    val setVersion: String = "",
    val running: Boolean = false,
    val stopping: Boolean = false,
    val progress: DeviceEvalRunner.Progress? = null,
    val rows: List<JsonObject> = emptyList(),
    val runId: String? = null,
    val savedPath: String? = null,
    val stopped: Boolean = false,
    val error: String? = null,
)

/** The Evaluation screen (EvaluationScreen.tsx): the fixed set against the chosen models, saved and exportable. */
class EvaluationViewModel(app: Application) : AndroidViewModel(app) {
    private val services = BoarServices.get(app)
    private val runner = DeviceEvalRunner(app, { q -> Classify.classifyTask(q).value }, { t -> Classify.isRetrievalIrrelevant(TaskType.of(t)) })
    private val _state = MutableStateFlow(EvaluationState())
    val state: StateFlow<EvaluationState> = _state

    init {
        safeScope.launch(Dispatchers.IO) {
            val configs = runner.installedModels().map { EvalConfig.Model(it.first.id, it.first.label) }
            val set = runner.evalSets().evalSet
            _state.update { it.copy(configs = configs, selected = configs.map { c -> c.id }.toSet(), queries = set, setVersion = set.firstOrNull()?.setVersion ?: "") }
        }
    }

    fun toggle(id: String) = _state.update { s ->
        if (s.running) s else s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun run() {
        val s = _state.value
        val chosen = s.configs.orEmpty().filter { it.id in s.selected }
        if (s.running || chosen.isEmpty()) return
        _state.update { it.copy(running = true, stopping = false, rows = emptyList(), runId = null, savedPath = null, stopped = false, error = null) }
        safeScope.launch(Dispatchers.IO) {
            try {
                // One model in memory at a time: the chat's is reloaded on its next message.
                services.host.unload()
                val result = runner.runConfigs(chosen, s.queries, object : DeviceEvalRunner.Listener {
                    override fun onProgress(p: DeviceEvalRunner.Progress) = _state.update { it.copy(progress = p) }
                    override fun onRow(row: JsonObject) = _state.update { it.copy(rows = it.rows + row) }
                })
                _state.update { it.copy(runId = result.runId, savedPath = result.file.path, stopped = result.stopped) }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
            _state.update { it.copy(running = false, stopping = false, progress = null) }
        }
    }

    fun stop() {
        _state.update { it.copy(stopping = true) }
        runner.stop()
    }

    /** Hands the results to the share sheet as JSONL or CSV (exportEvalResults). */
    suspend fun exportIntent(format: String): Intent? = withContext(Dispatchers.IO) {
        val s = _state.value
        val runId = s.runId ?: return@withContext null
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "$runId.$format")
        file.writeText(if (format == "jsonl") s.rows.joinToString("\n") { it.toString() } else DeviceEvalRunner.toCsv(s.rows))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (format == "jsonl") "application/x-ndjson" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export evaluation results")
    }

    override fun onCleared() {
        runner.stop()
    }
}
