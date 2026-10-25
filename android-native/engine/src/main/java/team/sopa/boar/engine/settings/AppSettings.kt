package team.sopa.boar.engine.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import team.sopa.boar.engine.EngineTweaks
import java.io.File

/**
 * The app's settings in files/settings.json, the Expo app's own file (src/models/settings.ts), read and written in
 * place so an upgrade keeps every setting. Keys this build doesn't know are kept as they are on every write. The
 * native-only Advanced tweaks live under "engineTweaks". Writes go to a temporary file renamed over the old one.
 */
class AppSettings(private val file: File) {
    private val json = Json { prettyPrint = false }
    @Volatile private var data: JsonObject = load()

    private fun load(): JsonObject = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    @Synchronized private fun update(change: MutableMap<String, JsonElement>.() -> Unit) {
        val m = data.toMutableMap()
        m.change()
        data = JsonObject(m)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), data))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun str(key: String) = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun int(key: String) = (data[key] as? JsonPrimitive)?.intOrNull
    private fun bool(key: String) = (data[key] as? JsonPrimitive)?.booleanOrNull
    private fun set(key: String, value: JsonElement?) = update { if (value == null || value is JsonNull) remove(key) else put(key, value) }

    /** activeModelId.llm, .embedding… */
    fun activeModelId(kind: String): String? =
        (data["activeModelId"] as? JsonObject)?.get(kind)?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
    fun setActiveModelId(kind: String, id: String?) = update {
        val m = (this["activeModelId"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (id == null) m.remove(kind) else m[kind] = JsonPrimitive(id)
        put("activeModelId", JsonObject(m))
    }

    /** modelRoleAssignments: role ("fast", "general"…) to model id. */
    fun modelRoleAssignments(): Map<String, String> =
        (data["modelRoleAssignments"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }?.let { k to it } }?.toMap() ?: emptyMap()

    /**
     * Always Succinct: the app has one answer style and no picker (r4to, Oct 24): in the Oct 23 UX tests 35 % of answers
     * were right with Succinct against 28 % with it off, and an audit of Detailed answers (Oct 17) found 20 % of claims wrong. A style stored by an earlier build (Detailed,
     * Summary, Custom) is ignored here and rewritten by [retireRemovedOptions].
     */
    val personalityId: String get() = ANSWER_STYLE
    /** The answer's token limit: Succinct's Auto length. There is no length setting; a stored "maxTokens" is ignored. */
    val maxTokens: Int get() = autoMaxTokens(personalityId)

    /** The limit for an answer now: Succinct's Auto length fitted to the context the model was opened with. */
    fun answerTokens(openedContext: Int?): Int = fitToContext(autoMaxTokens(personalityId), openedContext)

    /**
     * Writes over what an earlier build let the user choose: the style becomes Succinct, and a picked answer length and the
     * Deep Research switch are dropped, so settings.json says what the app does. Writes nothing when already clean.
     */
    fun retireRemovedOptions() {
        if (str("personalityId") == ANSWER_STYLE && "maxTokens" !in data && "deepResearchMode" !in data) return
        update { put("personalityId", JsonPrimitive(ANSWER_STYLE)); remove("maxTokens"); remove("deepResearchMode") }
    }

    /**
     * Settings → Reset settings: every preference back to its default (theme, Advanced tweaks, display and voice switches,
     * chat history options; an old build's style, length and Deep Research keys too). The style stays Succinct, which
     * [personalityId] always is. Kept: models and their roles, the PIN and the app's own
     * bookkeeping. Chats live elsewhere and stay too.
     */
    fun resetPreferences() = update { PREFERENCE_KEYS.forEach { remove(it) } }

    companion object {
        /** What "Reset settings" clears ([resetPreferences]). */
        val PREFERENCE_KEYS = listOf(
            "themeId", "fontScale", "screenOrientation", "personalityId", "maxTokens", "deepResearchMode", "researchBannerHidden",
            "adaptiveRoutingEnabled", "routingPreset", "engineTweaks", "showLiveStats", "voiceInputEnabled", "hapticsEnabled",
            "hidePromptIdeas", "autoSummarize", "historyTurnThreshold", "maxSavedSessions", "autoGenerateTitles",
            "freeMemoryInBackground", "freeMemoryAfterMinutes",
        )
        /** The one answer style (personalities.json's "succinct"). */
        const val ANSWER_STYLE = "succinct"
        const val AUTO_MAX_TOKENS = 512
        /** Detailed answers ran past 512 tokens and stopped mid-sentence (X6, Oct 11). */
        const val AUTO_MAX_TOKENS_DETAILED = 1024

        /** Context kept for the prompt (instructions, sources, history) when Auto sizes the answer. */
        const val PROMPT_RESERVE = 1500
        /** Auto never goes below this, however small the context. */
        const val MIN_AUTO_TOKENS = 256

        /**
         * Auto length on a small context: a phone with up to 4.5 GB of RAM opens 2048 tokens (contextSizeForRam), where
         * a 2048-token answer can't fit next to its prompt. Unknown context (no model loaded yet): [cap].
         */
        fun fitToContext(cap: Int, openedContext: Int?): Int =
            if (openedContext == null) cap else minOf(cap, maxOf(MIN_AUTO_TOKENS, openedContext - PROMPT_RESERVE))

        /**
         * Only Detailed gets the longer cap. No style set is Succinct, the app's one style ([ANSWER_STYLE], personalities.json's
         * defaultId): reading it as Detailed gave a fresh install 1024 tokens while it answered
         * in Succinct.
         */
        fun autoMaxTokens(personalityId: String?): Int = when {
            personalityId == "detailed" -> AUTO_MAX_TOKENS_DETAILED
            else -> AUTO_MAX_TOKENS
        }
    }
    var adaptiveRoutingEnabled: Boolean get() = bool("adaptiveRoutingEnabled") ?: false; set(v) = set("adaptiveRoutingEnabled", JsonPrimitive(v))
    var routingPreset: String? get() = str("routingPreset"); set(v) = set("routingPreset", v?.let(::JsonPrimitive))
    /** "campfire" or "moonlight"; the theme's default when unset. */
    var themeId: String? get() = str("themeId"); set(v) = set("themeId", v?.let(::JsonPrimitive))
    var fontScale: String get() = str("fontScale") ?: "standard"; set(v) = set("fontScale", JsonPrimitive(v))
    var hapticsEnabled: Boolean get() = bool("hapticsEnabled") ?: false; set(v) = set("hapticsEnabled", JsonPrimitive(v))
    /**
     * Advanced tweaks → Show technical details (the Expo app's live-stats key): off, the chat shows the short receipt
     * only; on, the receipt's details, live tok/s in the header, the menu's memory and storage bars, Hardware telemetry.
     */
    // On by default: the bounty's judges and early users want the numbers (time to first word, speed, memory).
    var showLiveStats: Boolean get() = bool("showLiveStats") ?: true; set(v) = set("showLiveStats", JsonPrimitive(v))

    /** Settings → Free memory when I leave BOAR (on by default): see [FreeMemory]. */
    var freeMemoryInBackground: Boolean get() = bool("freeMemoryInBackground") ?: true; set(v) = set("freeMemoryInBackground", JsonPrimitive(v))
    /** Advanced tweaks → Free memory after, in minutes (0 = immediately); null is Auto ([FreeMemory.AUTO_SECONDS], in seconds). */
    var freeMemoryAfterMinutes: Int? get() = int("freeMemoryAfterMinutes"); set(v) = set("freeMemoryAfterMinutes", v?.let(::JsonPrimitive))

    /** DEFAULT_MEMORY_SETTINGS when unset. */
    var autoSummarize: Boolean get() = bool("autoSummarize") ?: true; set(v) = set("autoSummarize", JsonPrimitive(v))
    var historyTurnThreshold: Int get() = int("historyTurnThreshold") ?: 6; set(v) = set("historyTurnThreshold", JsonPrimitive(v))
    var maxSavedSessions: Int get() = int("maxSavedSessions") ?: 10; set(v) = set("maxSavedSessions", JsonPrimitive(v))
    var autoGenerateTitles: Boolean get() = bool("autoGenerateTitles") ?: true; set(v) = set("autoGenerateTitles", JsonPrimitive(v))

    /** Advanced tweaks (native only); all Auto when unset or unreadable. */
    var engineTweaks: EngineTweaks
        get() = EngineTweaks.fromJson(data["engineTweaks"]?.toString())
        set(v) = set("engineTweaks", if (v.isAuto) null else json.parseToJsonElement(v.toJson()))

    /** Every key as stored, for tests and diagnostics. */
    private fun strings(key: String): List<String> = (data[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    private fun setStrings(key: String, v: List<String>) = set(key, if (v.isEmpty()) null else kotlinx.serialization.json.JsonArray(v.map(::JsonPrimitive)))

    /** CR-2's bookkeeping, under the Expo app's keys (loadCrashedIds, pendingLoadCrash) plus #73's loadMappedIds. */
    val loadLedger = object : team.sopa.boar.engine.models.LoadGuard.Ledger {
        override var crashedIds: List<String> get() = strings("loadCrashedIds"); set(v) = setStrings("loadCrashedIds", v)
        override var mappedIds: List<String> get() = strings("loadMappedIds"); set(v) = setStrings("loadMappedIds", v)
        override var pendingCrash: team.sopa.boar.engine.models.LoadGuard.Crash?
            get() = data["pendingLoadCrash"]?.takeIf { it is JsonObject }?.let { team.sopa.boar.engine.models.LoadGuard.crashFromJson(it.toString()) }
            set(v) = set("pendingLoadCrash", v?.let { json.parseToJsonElement(team.sopa.boar.engine.models.LoadGuard.crashToJson(it)) })
    }

    /**
     * Models the user chose to load although rated RED for this phone ("Load anyway"): loaded at start without asking
     * again. Kept by "Reset settings", like the load ledger above.
     */
    var loadAnywayIds: List<String> get() = strings("loadAnywayIds"); set(v) = setStrings("loadAnywayIds", v.distinct())

    /** The last model that loaded: CR-2's fallback after a crash on a cold start. */
    var lastLoadedModelId: String? get() = str("lastLoadedModelId"); set(v) = set("lastLoadedModelId", v?.let(::JsonPrimitive))

    // The PIN lock (native app): the data key sealed with the PIN and the device key, its salt, and the wrong-try count.
    var pinWrapped: String? get() = str("pinWrapped"); set(v) = set("pinWrapped", v?.let(::JsonPrimitive))
    var pinSalt: String? get() = str("pinSalt"); set(v) = set("pinSalt", v?.let(::JsonPrimitive))
    var pinIterations: Int get() = int("pinIterations") ?: team.sopa.boar.engine.security.PinKeys.ITERATIONS; set(v) = set("pinIterations", JsonPrimitive(v))
    var pinFailures: Int get() = int("pinFailures") ?: 0; set(v) = set("pinFailures", JsonPrimitive(v))
    var pinLockedUntil: Long get() = (data["pinLockedUntil"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L; set(v) = set("pinLockedUntil", JsonPrimitive(v))

    fun raw(): JsonObject = data
}
