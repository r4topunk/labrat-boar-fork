package team.sopa.boar.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import team.sopa.boar.engine.BoarEngine
import team.sopa.boar.engine.CpuVariants
import team.sopa.boar.engine.routing.Classify
import team.sopa.boar.engine.routing.TaskType
import team.sopa.boar.engine.ChatMessage
import team.sopa.boar.engine.CpuTopology
import team.sopa.boar.engine.EngineParams
import team.sopa.boar.engine.FlashAttn
import team.sopa.boar.engine.GenerationEvent
import team.sopa.boar.engine.KvType
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.Sampling
import java.io.File

/**
 * Week-1 bench: load a GGUF with any engine setting, run a llama-bench style test or ask a question.
 * Also runs headless from adb (Xiaomi blocks injected taps), for example:
 *   adb shell am start -n team.sopa.boar.lite.debug/team.sopa.boar.bench.BenchActivity \
 *     --es model qwen2.5-1.5b-instruct-q4km.gguf --ei threads 4 --es cpus big --es run bench
 * Results go to logcat (tag BOAR_BENCH) and to Android/data/<pkg>/files/results/.
 */
class BenchActivity : ComponentActivity() {
    private var session: LlamaSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BoarEngine.init(applicationInfo.nativeLibraryDir, intent.getStringExtra("variant") ?: CpuVariants.AUTO)
        val topology = CpuTopology.read()
        val log = mutableStateOf("${BoarEngine.systemInfo()}\ncores: ${topology.cores.joinToString { "${it.id}@${it.maxKhz / 1000}MHz" }}\nbig: ${topology.bigCores}\n")
        val models = modelFiles()

        // Only on a fresh start: a recreated activity must not load a second copy of the model.
        if (savedInstanceState == null && intent.getStringExtra("run") == "eval") {
            // A timing run keeps the screen on: with the screen off Android moves the app to the slow cores (10x slower).
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Over the lock screen too (never unlocking it): behind the keyguard the activity isn't resumed either.
            if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
            // scripts/eval-device.mjs --package team.sopa.boar.lite.debug --no-reload: wait for its request, answer it.
            val runner = team.sopa.boar.eval.DeviceEvalRunner(
                this,
                classify = { q -> Classify.classifyTask(q).value },
                retrievalIrrelevant = { t -> Classify.isRetrievalIrrelevant(TaskType.of(t)) },
                log = { line -> log.value += line + "\n"; Log.i(team.sopa.boar.eval.DeviceEvalRunner.TAG, line) },
                // Speed-run knobs: --es ov.threads 5 --es ov.seed 42 … (DeviceEvalRunner.overrides).
                overrides = intent.extras?.keySet().orEmpty().filter { it.startsWith("ov.") }
                    .associate { it.removePrefix("ov.") to intent.extras!!.get(it).toString() },
            )
            val waitMs = intent.getIntExtra("waitmin", 10) * 60_000L
            // A release build can't be reached with run-as: the request can come base64 in the intent (request64), and
            // the results are then copied to Android/data/<pkg>/files/eval-out, where adb can pull them.
            val request64 = intent.getStringExtra("request64")
            lifecycleScope.launch {
                if (request64 != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    File(filesDir, "eval/requests").apply { mkdirs() }.resolve("pending.json")
                        .writeBytes(android.util.Base64.decode(request64, android.util.Base64.DEFAULT))
                }
                if (!runner.pollOnce(waitMs)) log.value += "no evaluation request within the wait\n"
                if (request64 != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val out = File(getExternalFilesDir(null), "eval-out").apply { mkdirs() }
                    File(filesDir, "eval").walkTopDown().filter { it.isFile }.forEach { f -> f.copyTo(File(out, f.relativeTo(File(filesDir, "eval")).path), overwrite = true) }
                    Log.i(team.sopa.boar.eval.DeviceEvalRunner.TAG, "eval-out copied: ${out.path}")
                }
            }
        } else if (savedInstanceState == null) intent.getStringExtra("run")?.let { run ->
            val params = paramsFromIntent(topology)
            val model = intent.getStringExtra("model")?.let { name -> models.firstOrNull { it.name == name } } ?: models.firstOrNull()
            lifecycleScope.launch { runHeadless(run, model, params, log) }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF7A3D), background = Color(0xFF17110D))) {
                BenchScreen(models, topology, log.value, onLog = { log.value += it + "\n" }, onRun = { run, model, params -> lifecycleScope.launch { runHeadless(run, model, params, log) } })
            }
        }
    }

    private fun modelFiles(): List<File> {
        val dirs = listOfNotNull(getExternalFilesDir("models"), File(filesDir, "models"))
        return dirs.flatMap { it.listFiles { f -> f.name.endsWith(".gguf") }.orEmpty().toList() }.sortedBy { it.length() }
    }

    private fun paramsFromIntent(topology: CpuTopology): EngineParams {
        val i = intent
        val threads = i.getIntExtra("threads", 4)
        val cpus = when (val c = i.getStringExtra("cpus") ?: "") {
            "big" -> topology.cpuList(topology.bigCores)
            "all", "" -> ""
            else -> c
        }
        return EngineParams(
            nCtx = i.getIntExtra("nctx", 4096),
            nBatch = i.getIntExtra("batch", 512),
            nUbatch = i.getIntExtra("ubatch", 512),
            threads = threads,
            cpus = cpus,
            strictCpu = i.getBooleanExtra("strict", false),
            typeK = KvType.valueOf(i.getStringExtra("kv") ?: "F16"),
            typeV = KvType.valueOf(i.getStringExtra("kv") ?: "F16"),
            flashAttn = FlashAttn.valueOf(i.getStringExtra("fa") ?: "AUTO"),
        )
    }

    private suspend fun runHeadless(run: String, model: File?, params: EngineParams, log: androidx.compose.runtime.MutableState<String>) {
        fun say(s: String) { log.value += s + "\n"; Log.i("BOAR_BENCH", s) }
        if (model == null) { say("no .gguf in ${getExternalFilesDir("models")}"); return }
        val rssBefore = rssKb()
        val t0 = System.nanoTime()
        session?.close()
        val s = LlamaSession.load(model.absolutePath, params) ?: run { say("load failed: ${model.name}"); return }
        session = s
        val loadMs = (System.nanoTime() - t0) / 1e6
        say("loaded ${model.name} in ${"%.0f".format(loadMs)} ms · ${s.info}")
        val out = JSONObject()
            .put("model", model.name).put("params", JSONObject(params.toJson())).put("cpuBuild", BoarEngine.loadedVariant).put("loadMs", loadMs)
            .put("device", android.os.Build.MODEL).put("soc", if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE)
            .put("system", BoarEngine.systemInfo())
        when (run) {
            "bench" -> {
                val r = s.bench(intent.getIntExtra("pp", 512), intent.getIntExtra("tg", 128), intent.getIntExtra("reps", 3))
                say("pp${r.nPp}: ${r.pp?.mean?.let { "%.1f".format(it) }} t/s ±${r.pp?.sd?.let { "%.1f".format(it) }} · tg${r.nTg}: ${r.tg?.mean?.let { "%.1f".format(it) }} t/s ±${r.tg?.sd?.let { "%.1f".format(it) }} · threads ${r.threads}")
                out.put("pp", r.pp?.mean).put("ppSd", r.pp?.sd).put("tg", r.tg?.mean).put("tgSd", r.tg?.sd)
            }
            else -> {
                val q = intent.getStringExtra("prompt") ?: "Why do we have seasons on Earth? Answer in three sentences."
                val fmt = s.formatChat(listOf(ChatMessage("user", q)), enableThinking = false)
                val text = StringBuilder()
                s.generate(fmt.prompt, Sampling(nPredict = intent.getIntExtra("npredict", 256), stops = fmt.stops)).collect { e ->
                    when (e) {
                        is GenerationEvent.Piece -> text.append(e.text)
                        is GenerationEvent.Done -> {
                            val m = e.metrics
                            say(text.toString())
                            say("ttft ${"%.0f".format(m.ttftMs)} ms · prefill ${"%.1f".format(m.prefillTokPerSec)} t/s · decode ${"%.1f".format(m.decodeTokPerSec)} t/s · ${m.generatedTokens} tok · ${m.stopReason}")
                            out.put("answer", text.toString()).put("ttftMs", m.ttftMs).put("prefillTokPerSec", m.prefillTokPerSec)
                                .put("decodeTokPerSec", m.decodeTokPerSec).put("generatedTokens", m.generatedTokens).put("stopReason", m.stopReason)
                        }
                    }
                }
            }
        }
        out.put("rssKbBefore", rssBefore).put("rssKbAfter", rssKb()).put("pssKb", pssKb()).put("thermal", thermal())
        val dir = File(getExternalFilesDir(null), "results").apply { mkdirs() }
        File(dir, "${run}-${System.currentTimeMillis()}.json").writeText(out.toString(2))
        say("RESULT ${out}")
    }

    private fun rssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()
    }.getOrDefault(-1L)

    private fun pssKb(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: -1
    }

    private fun thermal(): Int = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }
}

@Composable
private fun BenchScreen(
    models: List<File>,
    topology: CpuTopology,
    log: String,
    onLog: (String) -> Unit,
    onRun: (String, File?, EngineParams) -> Unit,
) {
    var model by remember { mutableStateOf(models.firstOrNull()) }
    var threads by remember { mutableStateOf(4f) }
    var bigOnly by remember { mutableStateOf(false) }
    var nCtx by remember { mutableStateOf("4096") }
    var batch by remember { mutableStateOf("512") }
    var kv by remember { mutableStateOf(KvType.F16) }
    var fa by remember { mutableStateOf(FlashAttn.AUTO) }
    val scope = rememberCoroutineScope()

    fun params() = EngineParams(
        nCtx = nCtx.toIntOrNull() ?: 4096,
        nBatch = batch.toIntOrNull() ?: 512,
        nUbatch = minOf(batch.toIntOrNull() ?: 512, 512),
        threads = threads.toInt(),
        cpus = if (bigOnly) topology.cpuList(topology.bigCores) else "",
        typeK = kv, typeV = kv, flashAttn = fa,
    )

    Column(
        Modifier.fillMaxSize().background(Color(0xFF17110D)).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("BoarLite engine bench", color = Color(0xFFFFF4E8), fontSize = 22.sp)
        if (models.isEmpty()) Text("Push a .gguf to Android/data/team.sopa.boar.lite.debug/files/models", color = Color(0xFFF2C14E))
        models.forEach { f ->
            FilterChip(selected = model == f, onClick = { model = f }, label = { Text("${f.name} · ${f.length() / 1_000_000} MB") })
        }
        Text("Threads: ${threads.toInt()}", color = Color(0xFFD8CABA))
        Slider(value = threads, onValueChange = { threads = it }, valueRange = 1f..topology.cores.size.coerceAtLeast(1).toFloat(), steps = (topology.cores.size - 2).coerceAtLeast(0))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !bigOnly, onClick = { bigOnly = false }, label = { Text("Any core") })
            FilterChip(selected = bigOnly, onClick = { bigOnly = true }, label = { Text("Big cores ${topology.bigCores}") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(nCtx, { nCtx = it }, label = { Text("n_ctx") }, modifier = Modifier.weight(1f))
            OutlinedTextField(batch, { batch = it }, label = { Text("n_batch") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KvType.entries.forEach { k -> FilterChip(selected = kv == k, onClick = { kv = k }, label = { Text("KV $k") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlashAttn.entries.forEach { f -> FilterChip(selected = fa == f, onClick = { fa = f }, label = { Text("FA $f") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRun("bench", model, params()) }, modifier = Modifier.weight(1f)) { Text("Bench pp512/tg128") }
            Button(onClick = { onRun("ask", model, params()) }, modifier = Modifier.weight(1f)) { Text("Ask") }
        }
        Text(log, color = Color(0xFFD8CABA), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
    }
}
