package team.sopa.boar.eval

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.sopa.boar.engine.CpuTopology
import team.sopa.boar.engine.EngineParams
import team.sopa.boar.engine.EngineTweaks
import team.sopa.boar.engine.FlashAttn
import team.sopa.boar.engine.KvType
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.answer.AnswerEvent
import team.sopa.boar.engine.answer.AnswerPipeline
import team.sopa.boar.engine.answer.AnswerRequest
import team.sopa.boar.engine.answer.Personalities
import team.sopa.boar.engine.db.NativeSqlite
import team.sopa.boar.engine.eval.DeviceEval
import team.sopa.boar.engine.eval.EvalConfig
import team.sopa.boar.engine.eval.EvalQuery
import team.sopa.boar.engine.eval.EvalRequest
import team.sopa.boar.engine.eval.EvalRequestException
import team.sopa.boar.engine.eval.EvalSets
import team.sopa.boar.engine.eval.ModelRef
import team.sopa.boar.engine.eval.Resolved
import team.sopa.boar.engine.rag.KnowledgeBase
import team.sopa.boar.engine.rag.OpenPack
import team.sopa.boar.engine.rag.Packs
import team.sopa.boar.engine.rag.Retriever
import team.sopa.boar.engine.rag.WikiPack
import java.io.File

/**
 * The native app's side of scripts/eval-device.mjs, byte-compatible with the Expo app's (src/eval/deviceEvalRequest.ts):
 * takes files/eval/requests/pending.json, reports in <requestId>.status.json, writes eval/<runId>.jsonl and
 * .run.json. Runs the legacy pipeline (the fixed-model answer path); adaptive routing and the "answer" pipeline
 * are reported as not supported yet. Request polling is for debug builds only (the caller checks); [runConfigs]
 * also backs the Evaluation screen.
 */
class DeviceEvalRunner(
    private val context: Context,
    /** classifyTask and isRetrievalIrrelevant, as task type names. */
    private val classify: (String) -> String,
    private val retrievalIrrelevant: (String) -> Boolean,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /**
     * Speed-run knobs from adb (BenchActivity extras "ov.<name>"), never set by the app: seed, threads, threadsBatch, cpus
     * ("auto", "all", "fast<n>" or a list), batch, ubatch, kv, fa, ctx, embThreads, embCpus, coolC (wait for the battery to
     * cool to this before each question), probe (time each retrieval stage after each answer).
     */
    private val overrides: Map<String, String> = emptyMap(),
) {
    private val files = context.filesDir
    private val evalDir = File(files, "eval")
    private val requestsDir = File(evalDir, "requests")
    private val json = Json { prettyPrint = false }
    /** The app version on every row, so a saved or exported run says which build answered. */
    private val appVersion: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" 

    private fun asset(name: String) = context.assets.open("boar-data/$name").bufferedReader().readText()

    private fun writeStatus(requestId: String, vararg fields: Pair<String, Any?>) {
        requestsDir.mkdirs()
        val all = listOf<Pair<String, Any?>>("requestId" to requestId) + fields.toList() + listOf("updatedAt" to System.currentTimeMillis())
        val o = json(*all.toTypedArray())
        atomicWrite(File(requestsDir, "$requestId.status.json"), o.toString())
    }

    /** Waits up to [waitMs] for a request and runs it. Returns true when one ran. */
    suspend fun pollOnce(waitMs: Long): Boolean = withContext(Dispatchers.IO) {
        val pending = File(requestsDir, "pending.json")
        val until = System.currentTimeMillis() + waitMs
        while (!pending.exists()) {
            if (System.currentTimeMillis() > until) return@withContext false
            delay(1000)
        }
        val text = pending.readText()
        pending.delete()
        val request = try {
            DeviceEval.parseRequest(text)
        } catch (e: EvalRequestException) {
            writeStatus(DeviceEval.requestIdOf(text), "state" to "failed", "error" to "invalid request: ${e.message}")
            return@withContext true
        }
        run(request)
        true
    }

    /** Installed language models from the catalog, in catalog order. */
    fun installedModels(): List<Pair<ModelRef, File>> {
        val catalog = Json.parseToJsonElement(asset("catalog.json")).jsonObject["models"]!!.jsonArray.map { it.jsonObject }
        return catalog.filter { it["kind"]!!.jsonPrimitive.content == "llm" }.mapNotNull { m ->
            val f = File(files, m["filename"]!!.jsonPrimitive.content)
            if (f.isFile) ModelRef(m["id"]!!.jsonPrimitive.content, m["label"]!!.jsonPrimitive.content) to f else null
        }
    }

    fun evalSets(): EvalSets = EvalSets.parse(asset("eval-set.json"))

    private suspend fun run(request: EvalRequest) {
        val installed = installedModels()
        val installedIds = installed.map { it.first.id }
        if (request.pipeline == "answer") {
            writeStatus(request.requestId, "state" to "failed", "error" to "this native build runs the legacy pipeline only", "installedModels" to installedIds)
            return
        }
        val sets = evalSets()
        val resolved = DeviceEval.resolve(request, installed.map { it.first }, sets.evalSet, "Adaptive routing", sets.extraSets)
        if (resolved is Resolved.Err) {
            writeStatus(request.requestId, "state" to "failed", "error" to resolved.error, "installedModels" to installedIds)
            return
        }
        resolved as Resolved.Ok
        val configs = resolved.configs.map { it.id }
        val total = resolved.configs.size * resolved.queries.size
        writeStatus(request.requestId, "state" to "accepted", "configs" to configs, "total" to total, "completed" to 0, "installedModels" to installedIds)
        log("[EVAL] device request ${request.requestId}: ${configs.joinToString(", ")} x ${resolved.queries.size} queries")
        var completed = 0
        try {
            val result = runConfigs(resolved.configs, resolved.queries, object : Listener {
                override fun onProgress(p: Progress) = writeStatus(request.requestId, "state" to "running", "configs" to configs, "total" to total,
                    "completed" to completed, "current" to "${p.config.id} / ${p.query.id}", "installedModels" to installedIds)
                override fun onRow(row: JsonObject) { completed++ }
            })
            writeStatus(request.requestId, "state" to "done", "configs" to configs, "total" to total, "completed" to result.rows.size, "runId" to result.runId,
                "resultPath" to "files/eval/${result.runId}.jsonl", "stopped" to result.stopped, "installedModels" to installedIds)
        } catch (e: Throwable) {
            writeStatus(request.requestId, "state" to "failed", "configs" to configs, "total" to total, "completed" to completed, "error" to (e.message ?: e.toString()), "installedModels" to installedIds)
            throw e
        }
    }

    class Progress(val configIndex: Int, val configCount: Int, val queryIndex: Int, val queryCount: Int, val config: EvalConfig, val query: EvalQuery)
    class RunResult(val runId: String, val rows: List<JsonObject>, val file: File, val stopped: Boolean)
    interface Listener {
        fun onProgress(p: Progress) {}
        fun onRow(row: JsonObject) {}
    }

    @Volatile private var stopRequested = false
    @Volatile private var running: LlamaSession? = null

    /** Stops after the current answer, which is cut short and recorded as cancelled; the rows so far stay saved. */
    fun stop() {
        stopRequested = true
        running?.cancel()
    }

    /**
     * Answers every query with every configuration, one model loaded at a time, saving eval/<runId>.jsonl after each
     * row (evalHarness.runEvaluation). Used by device requests and the Evaluation screen.
     */
    suspend fun runConfigs(configs: List<EvalConfig>, queries: List<EvalQuery>, listener: Listener): RunResult = withContext(Dispatchers.IO) {
        stopRequested = false
        val runId = DeviceEval.newRunId()
        evalDir.mkdirs()
        val rowsFile = File(evalDir, "$runId.jsonl")
        val rows = ArrayList<JsonObject>()
        val installed = installedModels()
        val personalities = Personalities.parse(asset("personalities.json"))
        // As the chat answers by default: the default style's prompt and reminder (the small-model variant below 2 GB),
        // at that style's Auto length (Detailed 1,024).
        val maxTokens = team.sopa.boar.engine.settings.AppSettings.autoMaxTokens(personalities.defaultId)
        val topology = CpuTopology.read()
        // The same Auto context as the chat (contextSizeForRam).
        val totalRam = runCatching {
            android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
        val retrieval = openRetrieval(topology)
        try {
            run@ for ((ci, config) in configs.withIndex()) {
                var session: LlamaSession? = null
                var loadMs = 0.0
                var residency = "cold"
                try {
                    for ((qi, q) in queries.withIndex()) {
                        if (stopRequested) break@run
                        overrides["coolC"]?.toDoubleOrNull()?.let { coolTo(it) }
                        val current = "${config.id} / ${q.id}"
                        listener.onProgress(Progress(ci, configs.size, qi, queries.size, config, q))
                        atomicWrite(File(evalDir, "$runId.run.json"), json(
                            "runId" to runId, "configs" to configs.map { it.id }, "queries" to queries.map { it.id }, "current" to current,
                        ).toString())
                        val row = when (config) {
                            is EvalConfig.Adaptive -> failureRow(runId, config, q, personalities.defaultId, maxTokens, "adaptive routing is not in the native build yet")
                            is EvalConfig.Model -> {
                                if (session == null) {
                                    val file = installed.firstOrNull { it.first.id == config.modelId }?.second
                                    val t0 = System.nanoTime()
                                    val params = modelParams(EngineTweaks().resolve(topology, totalRam).params, topology)
                                    log("[EVAL] model params ${params.toJson()}")
                                    session = file?.let { LlamaSession.load(it.absolutePath, params) }
                                    loadMs = (System.nanoTime() - t0) / 1e6
                                    // As the chat's preload: the default answer's instructions read in before the first question.
                                    if (overrides["warm"] == "1") session?.let { warm(it, personalities, installed, config) }
                                } else {
                                    residency = "resident"
                                    loadMs = 0.0
                                }
                                val s = session
                                if (s == null) failureRow(runId, config, q, personalities.defaultId, maxTokens, "model failed to load")
                                else {
                                    running = s
                                    val small = (installed.firstOrNull { it.first.id == config.modelId }?.second?.length() ?: Long.MAX_VALUE) < team.sopa.boar.engine.answer.Personalities.SMALL_MODEL_BYTES
                                    // The chat's short-answer rules too: small talk, health and seed-phrase questions answer in
                                    // Succinct, and small talk at Succinct's length (ShortAnswer, ChatViewModel).
                                    val style = if (team.sopa.boar.engine.routing.ShortAnswer.reason(personalities.defaultId, q.query, null) != null) "succinct" else personalities.defaultId
                                    val (systemPrompt, styleReminder) = personalities.promptFor(style, null, small)
                                    val limit = if (team.sopa.boar.engine.routing.ShortAnswer.isSmallTalk(q.query)) minOf(maxTokens, team.sopa.boar.engine.settings.AppSettings.AUTO_MAX_TOKENS) else maxTokens
                                    val r = answerRow(s, retrieval, runId, config, q, style, systemPrompt, styleReminder, limit, residency, loadMs)
                                    running = null
                                    // As the chat after an answer without sources: the sourced instructions read back in.
                                    if (overrides["rewarm"] == "1" && r["retrievalUsed"]?.jsonPrimitive?.content != "true") warm(s, personalities, installed, config)
                                    if (stopRequested) JsonObject(r + ("outcome" to JsonPrimitive("cancelled"))) else r
                                }
                            }
                        }
                        rows += row
                        atomicWrite(rowsFile, rows.joinToString("") { it.toString() + "\n" })
                        listener.onRow(row)
                        log("[EVAL] ${q.id}: ${row["outcome"]} ${row["tokPerSec"] ?: ""}")
                    }
                } finally {
                    running = null
                    session?.close()
                }
            }
            File(evalDir, "$runId.run.json").delete()
            RunResult(runId, rows, rowsFile, stopRequested)
        } finally {
            retrieval?.close()
        }
    }

    /** The knowledge base, embedder and packs at the Expo app's paths; null when the knowledge base is missing. */
    private class Retrieval(val retriever: Retriever, val embedder: LlamaSession, val closeables: List<AutoCloseable>, val kb: KnowledgeBase, val packs: List<OpenPack>) : AutoCloseable {
        override fun close() { closeables.forEach { runCatching { it.close() } }; embedder.close() }
    }

    private suspend fun openRetrieval(topology: CpuTopology): Retrieval? {
        val dbFile = File(files, "SQLite/aoair_knowledge.db")
        val embFile = File(files, "models/embedding.gguf")
        if (!dbFile.isFile || !embFile.isFile) { log("[EVAL] no knowledge base or embedding model: answers without retrieval"); return null }
        val embParams = EngineParams(nCtx = 512, threads = overrides["embThreads"]?.toIntOrNull() ?: 2, embeddings = true,
            cpus = overrides["embCpus"]?.let { cpusFor(it, topology) } ?: "")
        val te = System.nanoTime()
        val embedder = LlamaSession.load(embFile.absolutePath, embParams) ?: return null
        log("[EVAL] embedder loaded in ${"%.0f".format((System.nanoTime() - te) / 1e6)} ms, ${embParams.toJson()}")
        val kbDb = NativeSqlite.open(dbFile.absolutePath)
        val closeables = mutableListOf<AutoCloseable>(kbDb)
        val sha = Json.parseToJsonElement(asset("catalog.json")).jsonObject["models"]!!.jsonArray.map { it.jsonObject }
            .first { it["kind"]!!.jsonPrimitive.content == "embedding" && it["required"]?.jsonPrimitive?.content == "true" }["sha256"]!!.jsonPrimitive.content
        // Local pack files in name order (knowledgePacks lists catalog packs first; same files, by name).
        val packs = File(files, "corpus").listFiles { f -> f.name.endsWith(".sqlite") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            runCatching {
                val db = NativeSqlite.open(f.absolutePath, readOnly = true)
                val meta = db.query("SELECT key, value FROM meta") { it.string(0)!! to (it.string(1) ?: "") }.toMap()
                val id = "local-${f.name.removeSuffix(".sqlite")}"
                when (Packs.packFormat(meta, sha)) {
                    1 -> OpenPack.Format1(id, db).also { closeables += db }
                    2 -> OpenPack.Format2(id, WikiPack.open(db, NativeSqlite.zstd)).also { closeables += db }
                    else -> { db.close(); null }
                }
            }.getOrNull()
        }
        // The Portuguese article names ship in the APK (assets/lexicon/pt-en.json), as the chat reads them.
        val lexicon: Map<String, String> = runCatching {
            context.assets.open("lexicon/pt-en.json").bufferedReader().use { r -> Json.parseToJsonElement(r.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content } }
        }.getOrDefault(emptyMap())
        val kb = KnowledgeBase(kbDb)
        val retriever = Retriever(kb, { packs }, { text -> runBlocking { embedder.embed(text) } }, { lexicon }, port = team.sopa.boar.engine.rag.SearchPort.BOARLITE_DEFAULT)
        log("[EVAL] retrieval: knowledge base + ${packs.size} packs, lexicon ${lexicon.size} names")
        return Retrieval(retriever, embedder, closeables, kb, packs)
    }

    /** ChatViewModel.warmPrefix for the default style with sources; logs how long it took. */
    private suspend fun warm(s: LlamaSession, personalities: Personalities, installed: List<Pair<ModelRef, File>>, config: EvalConfig.Model) {
        val small = (installed.firstOrNull { it.first.id == config.modelId }?.second?.length() ?: Long.MAX_VALUE) < Personalities.SMALL_MODEL_BYTES
        val (head, _) = team.sopa.boar.engine.rag.RagPure.answerPromptPrefix(personalities.promptFor(personalities.defaultId, null, small).first, true)
        val t0 = System.nanoTime()
        val prompt = s.formatChat(listOf(team.sopa.boar.engine.ChatMessage("system", head))).prompt
        s.generate(prompt, team.sopa.boar.engine.Sampling(nPredict = 0)).collect {}
        log("[EVAL] warm prefix ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
    }

    private fun cpusFor(spec: String, topology: CpuTopology): String = when {
        spec == "auto" -> topology.cpuList(topology.autoCores)
        spec == "all" -> ""
        spec.startsWith("fast") -> topology.cpuList(topology.fastest(spec.removePrefix("fast").toIntOrNull() ?: 4))
        else -> spec
    }

    /** The chat's engine parameters with the speed-run overrides applied. */
    private fun modelParams(base: EngineParams, topology: CpuTopology): EngineParams {
        val o = overrides
        val t = o["threads"]?.toIntOrNull() ?: base.threads
        return base.copy(
            threads = t, threadsBatch = o["threadsBatch"]?.toIntOrNull() ?: if (o["threads"] != null) t else base.threadsBatch,
            cpus = o["cpus"]?.let { cpusFor(it, topology) } ?: base.cpus,
            nBatch = o["batch"]?.toIntOrNull() ?: base.nBatch,
            nUbatch = o["ubatch"]?.toIntOrNull() ?: o["batch"]?.toIntOrNull()?.let { minOf(it, 512) } ?: base.nUbatch,
            typeK = o["kv"]?.let { KvType.valueOf(it) } ?: base.typeK, typeV = o["kv"]?.let { KvType.valueOf(it) } ?: base.typeV,
            flashAttn = o["fa"]?.let { FlashAttn.valueOf(it) } ?: base.flashAttn,
            nCtx = o["ctx"]?.toIntOrNull() ?: base.nCtx,
            strictCpu = o["strict"]?.toBoolean() ?: base.strictCpu,
        )
    }

    private fun batteryTempC(): Double? = runCatching {
        context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.div(10.0)
    }.getOrNull()

    /** Waits (up to 15 minutes) for the battery to cool to [c] °C, so thermal throttling doesn't decide a comparison. */
    private suspend fun coolTo(c: Double) {
        val until = System.currentTimeMillis() + 15 * 60_000L
        while (System.currentTimeMillis() < until) {
            val t = batteryTempC() ?: return
            if (t <= c) return
            log("[EVAL] cooling: battery $t °C > $c")
            delay(20_000)
        }
    }

    /** Each retrieval stage timed alone (the answer's own search already ran, so caches are warm as in a chat). */
    private fun probe(r: Retrieval, query: String): JsonObject {
        fun <T> timed(block: () -> T): Pair<T, Double> { val t0 = System.nanoTime(); val v = block(); return v to (System.nanoTime() - t0) / 1e6 }
        val (vec, embedMs) = timed { runBlocking { r.embedder.embed(query) } }
        val k = team.sopa.boar.engine.answer.AnswerPipeline.ANSWER_CONTEXT_CHUNKS
        val (_, lexMs) = timed { r.kb.lexicalSearch(query, vec, k * 2) }
        val (_, semMs) = timed { r.kb.semanticSearch(vec, k * 2) }
        val packMs = r.packs.map { p ->
            val (_, ms) = timed {
                runCatching {
                    when (p) {
                        is OpenPack.Format1 -> Packs.searchFormat1(p.db, p.id, query, vec, k * 2, null)
                        is OpenPack.Format2 -> p.pack.searchDetailed(query, team.sopa.boar.engine.rag.PackSearchOptions(k = k, queryVec = vec))
                    }
                }
            }
            p.id to ms
        }
        val (_, totalMs) = timed { r.retriever.retrieve(query, k) }
        return json("embedMs" to embedMs, "kbLexicalMs" to lexMs, "kbSemanticMs" to semMs,
            "packMs" to JsonObject(packMs.associate { it.first to JsonPrimitive(it.second) }), "retrieveMs" to totalMs)
    }

    private suspend fun answerRow(
        s: LlamaSession, retrieval: Retrieval?, runId: String, config: EvalConfig.Model, q: EvalQuery, personalityId: String,
        systemPrompt: String, styleReminder: String?, maxTokens: Int, residency: String, loadMs: Double,
    ): JsonObject {
        val pipeline = AnswerPipeline(s, { query, k -> retrieval?.retriever?.retrieve(query, k) ?: emptyList() }, classify, retrievalIrrelevant,
            headPass = { query, raw, kept, chars -> retrieval?.retriever?.headPass(query, raw, kept, chars) ?: emptyList() })
        val tempBefore = batteryTempC()
        val peak = PeakRss().apply { start() }
        val start = System.nanoTime()
        var genStart = 0L
        var firstToken = 0L
        var tokens = 0
        var done: AnswerEvent.Done? = null
        var error: String? = null
        try {
            pipeline.answer(AnswerRequest(q.query, systemPrompt, styleReminder, maxTokens, seed = overrides["seed"]?.toIntOrNull() ?: -1)).collect { e ->
                when (e) {
                    is AnswerEvent.Stage -> if (e.stage == team.sopa.boar.engine.answer.AnswerStage.THINKING) genStart = System.nanoTime()
                    is AnswerEvent.Piece -> { tokens++; if (firstToken == 0L) firstToken = System.nanoTime() }
                    is AnswerEvent.Done -> done = e
                    else -> {}
                }
            }
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }
        val end = System.nanoTime()
        val peakRss = peak.stop()
        val probed = if (overrides["probe"] == "1" && retrieval != null && d0(done)) runCatching { probe(retrieval, q.query) }.getOrNull() else null
        val d = done
        val answer = d?.text ?: ""
        val ttftMs = if (genStart > 0) ((if (firstToken > 0) firstToken else end) - genStart) / 1e6 else null
        val genLatency = if (genStart > 0 && ttftMs != null) (end - genStart) / 1e6 - ttftMs else null
        val outcome = if (error != null) "failure" else if (answer.isNotBlank()) "success" else "failure"
        val m = d?.metrics
        val titles = d?.sources?.map { it.title } ?: emptyList()
        val taskType = d?.taskType ?: classify(q.query)
        return json(
            "modelId" to config.modelId, "taskType" to taskType, "adaptiveRoutingUsed" to false,
            "reasonCodes" to DeviceEval.fixedPlanReasonCodes(!retrievalIrrelevant(taskType), config.modelId),
            "retrievalUsed" to (titles.isNotEmpty()), "modelSwitches" to 0, "crossMessageModelSwitch" to false,
            "modelResidency" to residency, "modelLoadMs" to loadMs, "ttftMs" to ttftMs, "generationLatencyMs" to genLatency,
            "totalLatencyMs" to (end - start) / 1e6, "tokensGenerated" to tokens, "tokPerSec" to DeviceEval.tokensPerSecond(tokens, genLatency),
            "peakRssBytes" to peakRss, "outcome" to outcome, "errorMessage" to (error ?: if (outcome == "failure") "empty answer" else null),
            "runId" to runId, "evalSetVersion" to q.setVersion, "retrievalMs" to d?.retrievalMs,
            "contextTokensBefore" to d?.tokensBefore, "contextTokensAfter" to d?.tokensAfter,
            "promptTokens" to m?.promptTokensDecoded, "promptMs" to m?.prefillMs, "cachedTokens" to m?.let { it.promptTokens - it.promptTokensDecoded },
            "predictedTokens" to m?.generatedTokens, "predictedMs" to m?.decodeMs,
            "configId" to config.id, "configLabel" to config.label, "personalityId" to personalityId, "maxTokens" to maxTokens,
            "queryId" to q.id, "category" to q.category, "query" to q.query, "answer" to answer, "promptFormat" to d?.promptFormat,
            "retrievedTitles" to titles, "expectedKbTitles" to q.expectedKbTitles, "expectedKbHit" to DeviceEval.expectedKbHit(q.expectedKbTitles, titles),
            "timedOut" to false, "createdAt" to System.currentTimeMillis(), "engine" to "native", "appVersion" to appVersion,
            "batteryTempBefore" to tempBefore, "batteryTempAfter" to batteryTempC(), "stopReason" to m?.stopReason,
            "nativeTtftMs" to m?.ttftMs, "userTtftMs" to (if (firstToken > 0) (firstToken - start) / 1e6 else null), "decodeTokPerSec" to m?.decodeTokPerSec, "probe" to probed,
            "params" to overrides.takeIf { it.isNotEmpty() }?.entries?.joinToString(" ") { "${it.key}=${it.value}" },
        )
    }

    private fun d0(d: AnswerEvent.Done?) = d?.retrieved == true

    private fun failureRow(runId: String, config: EvalConfig, q: EvalQuery, personalityId: String, maxTokens: Int, error: String) = json(
        "modelId" to (config as? EvalConfig.Model)?.modelId, "taskType" to classify(q.query), "adaptiveRoutingUsed" to (config is EvalConfig.Adaptive),
        "outcome" to "failure", "errorMessage" to error, "tokensGenerated" to 0, "runId" to runId, "evalSetVersion" to q.setVersion,
        "configId" to config.id, "configLabel" to config.label, "personalityId" to personalityId, "maxTokens" to maxTokens,
        "queryId" to q.id, "category" to q.category, "query" to q.query, "answer" to "", "retrievedTitles" to emptyList<String>(),
        "expectedKbTitles" to q.expectedKbTitles, "expectedKbHit" to DeviceEval.expectedKbHit(q.expectedKbTitles, emptyList()),
        "timedOut" to false, "createdAt" to System.currentTimeMillis(), "engine" to "native", "appVersion" to appVersion,
    )

    companion object {
        const val TAG = "BOAR_EVAL"

        /** evalRowsToCsv: the same columns, arrays joined with "|". */
        val CSV_COLUMNS = listOf("runId", "evalSetVersion", "configId", "configLabel", "routingPreset", "personalityId", "maxTokens",
            "queryId", "category", "query", "modelId", "promptFormat", "taskType", "adaptiveRoutingUsed", "retrievalUsed",
            "retrievedTitles", "expectedKbTitles", "expectedKbHit", "reasonCodes", "modelSwitches", "crossMessageModelSwitch",
            "modelResidency", "modelLoadMs", "ttftMs", "retrievalMs", "contextTokensBefore", "contextTokensAfter",
            "generationLatencyMs", "promptTokens", "promptMs", "cachedTokens", "predictedTokens", "predictedMs", "totalLatencyMs",
            "tokensGenerated", "tokPerSec", "peakRssBytes", "outcome", "errorMessage", "timedOut", "answer", "createdAt", "appVersion")

        fun toCsv(rows: List<JsonObject>): String =
            (listOf(CSV_COLUMNS.joinToString(",")) + rows.map { r -> CSV_COLUMNS.joinToString(",") { csvCell(r[it]) } }).joinToString("\n")

        private fun csvCell(v: kotlinx.serialization.json.JsonElement?): String {
            val s = when (v) {
                null, kotlinx.serialization.json.JsonNull -> return ""
                is JsonArray -> v.joinToString("|") { cellText(it) }
                else -> cellText(v)
            }
            return if (s.contains(Regex("[\",\n]"))) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }

        /** String(value) for a JSON value: whole numbers without ".0", as JavaScript prints them. */
        private fun cellText(v: kotlinx.serialization.json.JsonElement): String {
            val p = v as? JsonPrimitive ?: return v.toString()
            if (p.isString) return p.content
            val d = p.content.toDoubleOrNull()
            return if (d != null && d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e21 && p.content.contains('.')) d.toLong().toString() else p.content
        }
    }
}

/** Peak resident memory while a question runs, sampled from /proc (VmRSS) every 250 ms. */
private class PeakRss {
    @Volatile private var peak = 0L
    @Volatile private var running = false
    private fun rss(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() * 1024
    }.getOrDefault(0L)
    fun start() {
        running = true
        peak = rss()
        Thread { while (running) { peak = maxOf(peak, rss()); Thread.sleep(250) } }.apply { isDaemon = true }.start()
    }
    fun stop(): Long { running = false; return maxOf(peak, rss()) }
}

private fun atomicWrite(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    tmp.renameTo(f)
}

/** A JSON object from pairs; null values are left out, as JSON.stringify drops undefined. */
internal fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to toJsonElement(it) } }.toMap())

private fun toJsonElement(v: Any): kotlinx.serialization.json.JsonElement = when (v) {
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.filterNotNull().map(::toJsonElement))
    else -> JsonPrimitive(v.toString())
}
