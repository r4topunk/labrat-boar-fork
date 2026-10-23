package team.sopa.boar.engine.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CR-2 (src/inference/loadMarker.ts) plus #73's mapped retry. A model load that gets the app killed (low memory) leaves
 * no error behind, so a marker is written right before the load and cleared after it returns. A marker found on the
 * next start means that load killed the app, with repacked weights or the mapped file alike: the model isn't loaded
 * again on its own ([isBlocked]), so a model too big for the phone closes the app once, not on every start. The chat
 * says so once ([consume]) and lets the user choose:
 * - "Try again with less memory" ([retryMapped]): unblocked, and loaded keeping the mapped file instead of repacking
 *   it (llama.cpp copies repacked weights into app memory for its fast ARM kernels, which Android can only swap);
 * - picking it again in the Model Catalog ([confirm]).
 * A mapped load that works stays mapped ([mappedNext]), so it doesn't go back to the repacked load that closed the app
 * (Advanced tweaks → Repacked weights still decides when the user sets it).
 */
class LoadGuard(private val store: MarkerStore, private val ledger: Ledger, private val now: () -> Long = System::currentTimeMillis) {
    @Serializable
    data class Meta(val filename: String, val modelId: String? = null, val label: String? = null)

    @Serializable
    data class Marker(
        val filename: String,
        val modelId: String? = null,
        val label: String? = null,
        val previous: Meta? = null,
        val at: Long,
        /** The load used repacked weights (the default); a mapped-file load that dies is a crash. */
        val repacked: Boolean = false,
    )

    /** What the chat shows once: "<crashed> closed the app … using <fallback>". */
    @Serializable
    data class Crash(val crashedModelId: String, val crashedLabel: String, val fallbackModelId: String, val fallbackLabel: String, val at: Long)

    interface MarkerStore {
        fun read(): String?
        fun write(json: String)
        fun clear()
    }

    /** Where the outcome is remembered (settings.json, the Expo app's keys plus loadMappedIds). */
    interface Ledger {
        var crashedIds: List<String>
        var mappedIds: List<String>
        var pendingCrash: Crash?
    }

    private var checked = false

    /**
     * True when Android says the last process ended for a reason that isn't a load kill: the user swiped BOAR away or
     * force-stopped it, or the app was updated (issue #89). Given the marker's time, so an older exit doesn't count. Such
     * an exit only clears the marker; unknown (older Android, no record) keeps counting it as a kill, as before.
     */
    var exitWasNotLoadKill: (markerAt: Long) -> Boolean = { false }

    /** Once per process, before any new marker: a marker left over means the last load killed the app. */
    @Synchronized
    fun ensureChecked() {
        if (checked) return
        checked = true
        val raw = runCatching { store.read() }.getOrNull() ?: return
        runCatching {
            val m = json.decodeFromString(Marker.serializer(), raw)
            if (runCatching { exitWasNotLoadKill(m.at) }.getOrDefault(false)) return@runCatching
            val id = m.modelId ?: m.filename
            // Repacked or mapped, the load closed the app: never again on its own (one kill, not two).
            ledger.crashedIds = (ledger.crashedIds + id).distinct()
            if (!m.repacked) ledger.mappedIds = ledger.mappedIds - id
            ledger.pendingCrash = toCrash(m)
        }
        runCatching { store.clear() }
    }

    /** True when the next load of [modelId] should keep the mapped file (the user chose "Try again with less memory"). */
    fun mappedNext(modelId: String): Boolean { ensureChecked(); return modelId in ledger.mappedIds }

    /** A model whose last load killed the app: not loaded again on its own. */
    fun isBlocked(modelId: String): Boolean { ensureChecked(); return modelId in ledger.crashedIds }

    fun begin(meta: Meta, previous: Meta?, repacked: Boolean) {
        ensureChecked()
        store.write(json.encodeToString(Marker.serializer(), Marker(meta.filename, meta.modelId, meta.label, previous, now(), repacked)))
    }

    /**
     * After the load returned. A load that failed clears the marker. One that loaded keeps it until [settled]: Android
     * kills the app when the model's memory fills, which can come seconds after the load returned (F3, LFM2.5 8B, Oct 18:
     * killed 4 s after llama.cpp finished loading). A kill in that window still counts as a load kill.
     */
    fun end(meta: Meta, loaded: Boolean) {
        if (!loaded) store.clear()
    }

    /**
     * [modelId] has stayed loaded long enough (its first answer finished, or a minute passed): the marker goes, and a model
     * blocked before is unblocked. One the user retried with the mapped file keeps it ([mappedNext]).
     */
    fun settled(modelId: String) {
        val raw = runCatching { store.read() }.getOrNull()
        val marker = raw?.let { runCatching { json.decodeFromString(Marker.serializer(), it) }.getOrNull() }
        if (marker != null && (marker.modelId ?: marker.filename) == modelId) store.clear()
        if (modelId in ledger.crashedIds) ledger.crashedIds = ledger.crashedIds - modelId
    }

    /**
     * After a generation of [modelId]: only one that wrote tokens without an error proves the model fits and [settled] it.
     * The chat's warm-up prefill (0 tokens, seconds after the load), a cancelled or a failed one doesn't: a model killed on
     * its first real answer must still leave its marker. The minute-later rule (BoarServices.LOAD_SETTLE_MS) still applies.
     */
    fun generated(modelId: String, generatedTokens: Int, error: String?) {
        if (provesFit(generatedTokens, error)) settled(modelId)
    }

    /** "Try again with less memory": the user's call to load [modelId] again now, keeping the mapped file. */
    fun retryMapped(modelId: String) {
        ensureChecked()
        if (modelId in ledger.crashedIds) ledger.crashedIds = ledger.crashedIds - modelId
        if (modelId !in ledger.mappedIds) ledger.mappedIds = ledger.mappedIds + modelId
    }

    /** The user picked the model again in the Model Catalog: let it load (CR-2's confirmation). */
    fun confirm(modelId: String) {
        ensureChecked()
        if (modelId in ledger.crashedIds) ledger.crashedIds = ledger.crashedIds - modelId
    }

    /** The last crash, once. */
    @Synchronized
    fun consume(): Crash? {
        ensureChecked()
        return ledger.pendingCrash?.also { ledger.pendingCrash = null }
    }

    companion object {
        /** Decode below this rate, for at least [STALL_MIN_MS], is a stall (a 4B on a 6 GB phone normally writes 3.5–6 tok/s). */
        const val STALL_TOK_PER_SEC = 1.0
        const val STALL_MIN_MS = 10_000.0

        /**
         * memoryFit.ts isDecodeStall (#73): repacked weights pushed to swap make every token wait for them to be
         * decompressed (0.1 tok/s on a Pixel 6a with the 4B, about 1 answer in 6 in long conversations).
         */
        /** A generation that wrote at least one token and ended without an error (a stop word or the limit). */
        fun provesFit(generatedTokens: Int, error: String?): Boolean = generatedTokens > 0 && error.isNullOrEmpty()

        fun isDecodeStall(generatedTokens: Int, decodeMs: Double): Boolean =
            decodeMs >= STALL_MIN_MS && generatedTokens * 1000.0 / decodeMs < STALL_TOK_PER_SEC

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun toCrash(m: Marker) = Crash(
            crashedModelId = m.modelId ?: m.filename,
            crashedLabel = m.label ?: m.modelId ?: m.filename,
            fallbackModelId = m.previous?.modelId ?: m.previous?.filename ?: "",
            fallbackLabel = m.previous?.label ?: m.previous?.modelId ?: m.previous?.filename ?: "",
            at = m.at,
        )

        fun crashFromJson(s: String): Crash? = runCatching { json.decodeFromString(Crash.serializer(), s) }.getOrNull()
        fun crashToJson(c: Crash): String = json.encodeToString(Crash.serializer(), c)
    }
}
