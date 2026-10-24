package team.sopa.boar.engine.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.Catalog
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.Compatibility
import team.sopa.boar.engine.models.ModelCapabilities
import team.sopa.boar.engine.routing.Profiles.AvailableModel
import team.sopa.boar.engine.routing.Profiles.ModelProfile
import team.sopa.boar.engine.routing.Router.RoutingPlan
import java.io.File

/**
 * Adaptive-routing planning (profiles, router, default model, compatibility, adaptiveChat's planning half) must match
 * the TypeScript engine on every golden case (scripts/export-native-golden-routing-plan.mjs). Outputs are compared as
 * plain trees (maps, lists, strings, booleans, doubles), every number exactly. The catalog is the old multi-model one
 * (golden/routing-catalog.json): the router stays model-agnostic, though the app now ships one model.
 */
class RoutingPlanGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/routing-plan.json").readText()).jsonObject
    private val catalog = Catalog.parse(File("src/test/resources/golden/routing-catalog.json").readText())
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun cases(name: String): JsonArray = g[name]!!.jsonArray.also { assertTrue("$name has cases", it.size > 0) }

    /** JSON as a plain tree; every number a Double. */
    private fun tree(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { tree(it.value) }
        is JsonArray -> e.map { tree(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.double
    }

    private fun check(name: String, each: (JsonObject) -> Triple<String, Any?, Any?>) {
        val bad = ArrayList<String>()
        for (c in cases(name)) {
            val (input, want, got) = each(c.jsonObject)
            if (want != got) bad += "$input\n    want: $want\n    got:  $got"
        }
        assertEquals("$name: ${bad.size} mismatches\n" + bad.take(10).joinToString("\n"), 0, bad.size)
    }

    private fun profileTree(p: ModelProfile) = mapOf(
        "modelId" to p.modelId, "role" to p.role.value, "enabled" to p.enabled, "userOverride" to p.userOverride,
        "estimatedMemoryMb" to p.estimatedMemoryMb, "estimatedTokensPerSecond" to p.estimatedTokensPerSecond,
        "compatibilityStatus" to p.compatibilityStatus.value,
    )

    private fun planTree(p: RoutingPlan) = mapOf(
        "steps" to p.steps.map {
            mapOf(
                "id" to it.id, "type" to it.type.value, "modelId" to it.modelId, "required" to it.required,
                "maxTokens" to it.maxTokens?.toDouble(), "timeoutMs" to it.timeoutMs?.toDouble(),
            )
        },
        "selectedModelIds" to p.selectedModelIds,
        "estimatedCost" to mapOf("modelSwitches" to p.estimatedCost.modelSwitches.toDouble()),
        "fallbackPolicy" to p.fallbackPolicy.value,
        "reasonCodes" to p.reasonCodes,
    )

    private val extraModels: Map<String, CatalogAsset> = g["extraModels"]!!.jsonArray.associate {
        val o = it.jsonObject
        val id = o["id"]!!.str!!
        id to CatalogAsset(
            id = id, kind = AssetKind.LLM, label = id, filename = "models/$id.gguf", sizeBytes = o["sizeBytes"]!!.jsonPrimitive.long,
            sha256 = "", sourceUrl = "", license = "MIT", description = "", required = false,
            capabilities = Json.decodeFromJsonElement(ModelCapabilities.serializer(), o["capabilities"]!!),
        )
    }

    private fun overrides(o: JsonObject): Map<ModelRole, String> = o.entries.associate { ModelRole.of(it.key) to it.value.str!! }

    /** ModelManager.statusAll as the script built it: the whole catalog, then the set's extra models. */
    private fun statuses(set: String, reversed: Boolean): List<AvailableModel> {
        val s = g["modelSets"]!!.jsonObject[set]!!.jsonObject
        val present = s["present"]!!.jsonArray.map { it.str!! }.toSet()
        val models = catalog.models + s["extras"]!!.jsonArray.map { extraModels.getValue(it.str!!) }
        val list = models.map { AvailableModel(it, it.id in present) }
        return if (reversed) list.reversed() else list
    }

    @Test fun `adaptive chat planning`() {
        val allOverrides = g["overrides"]!!.jsonArray.map { overrides(it.jsonObject) }
        check("adaptive") {
            val set = it["set"]!!.str!!
            val reversed = it["reversed"]!!.jsonPrimitive.boolean
            val preset = RoutingPreset.of(it["preset"]!!.str!!)
            val ov = allOverrides[it["overrides"]!!.jsonPrimitive.int]
            val active = it["activeId"]!!.str
            val ram = it["ram"]!!.let { r -> if (r is JsonNull) 0L else r.jsonPrimitive.long }
            val maxTokens = it["maxTokens"]!!.jsonPrimitive.int
            val query = it["query"]!!.str!!
            val r = AdaptivePlan.plan(query, statuses(set, reversed), preset, ov, active, ram, maxTokens)
            val want = mapOf(
                "taskType" to it["taskType"]!!.str, "profiles" to tree(it["profiles"]!!),
                "plan" to tree(it["plan"]!!), "modelUsed" to it["modelUsed"]!!.str,
            )
            val got = mapOf(
                "taskType" to r.taskType.value, "profiles" to r.profiles.map(::profileTree),
                "plan" to planTree(r.plan), "modelUsed" to r.modelUsed(catalog)?.id,
            )
            Triple("plan($query, $set${if (reversed) " reversed" else ""}, $preset, $ov, $active, $ram, $maxTokens)", want, got)
        }
    }

    /** planRoute's hand-built profile sets (disabled, duplicate and missing roles). */
    private val profileSets = g["profileSets"]!!.jsonArray.map { set ->
        set.jsonArray.map {
            val o = it.jsonObject
            ModelProfile(
                modelId = o["modelId"]!!.str!!, role = ModelRole.of(o["role"]!!.str!!), enabled = o["enabled"]!!.jsonPrimitive.boolean,
                userOverride = false, compatibilityStatus = Compatibility.UNKNOWN,
            )
        }
    }

    @Test fun `planRoute on every task, preset, profile set and budget`() = check("planRoute") {
        val profiles = profileSets[it["profileSet"]!!.jsonPrimitive.int]
        val lowPower = it["lowPower"]!!.let { l -> if (l is JsonNull) null else l.jsonPrimitive.boolean }
        val ctx = Router.RoutingContext(
            taskType = TaskType.of(it["taskType"]!!.str!!),
            query = "",
            hasLocalKnowledgeBase = it["hasKb"]!!.jsonPrimitive.boolean,
            retrievalAvailable = it["retrievalAvailable"]!!.jsonPrimitive.boolean,
            availableModels = profiles,
            preset = RoutingPreset.of(it["preset"]!!.str!!),
            deviceState = lowPower?.let { l -> Router.DeviceState(lowPowerMode = l) },
            budget = Router.InferenceBudget(
                maxTokens = it["maxTokens"]!!.jsonPrimitive.int,
                maxTotalLatencyMs = it["latency"]!!.let { l -> if (l is JsonNull) null else l.jsonPrimitive.long },
                allowVerification = it["allowVerification"]!!.jsonPrimitive.boolean,
                allowRetrieval = it["allowRetrieval"]!!.jsonPrimitive.boolean,
            ),
        )
        Triple("planRoute($it)", tree(it["plan"]!!), planTree(Router.planRoute(ctx)))
    }

    @Test fun `rankAnswerModels and the low-RAM caps`() = check("rankAnswerModels") {
        val ram = it["ram"]!!.jsonPrimitive.long
        val candidates = it["candidates"]!!.jsonArray.map { c ->
            val o = c.jsonObject
            DefaultModel.Candidate(
                id = o["id"]!!.str!!,
                answerTier = o["answerTier"]!!.str?.let { t -> DefaultModel.Tier.entries.first { e -> e.value == t } },
                fit = o["fit"]!!.str?.let(FitVerdict::of),
                sizeBytes = o["sizeBytes"]!!.let { s -> if (s is JsonNull) null else s.jsonPrimitive.long },
                tokPerSec = o["tokPerSec"]!!.let { s -> if (s is JsonNull) null else s.jsonPrimitive.double },
            )
        }
        val r = DefaultModel.rankAnswerModels(candidates, ram)
        val want = listOf(tree(it["pick"]!!), tree(it["ranked"]!!), tree(it["tooBigForLowRam"]!!), tree(it["tooBigForAutoPick"]!!), tree(it["isCompact"]!!))
        val got = listOf(
            r.pick?.let { p -> mapOf("id" to p.id, "reason" to p.reason) },
            r.ranked.map { x -> mapOf("id" to x.id, "picked" to x.picked, "reason" to x.reason) },
            candidates.map { c -> DefaultModel.tooBigForLowRam(c, ram) },
            candidates.map { c -> DefaultModel.tooBigForAutoPick(c, ram) },
            candidates.map { c -> DefaultModel.isCompactModel(c) },
        )
        Triple("rankAnswerModels(${it["candidates"]}, $ram)", want, got)
    }

    @Test fun `computeCompatibility`() = check("compatibility") {
        val size = it["size"]!!.jsonPrimitive.long
        val ram = it["ram"]!!.jsonPrimitive.long
        Triple("computeCompatibility($size, $ram)", it["out"]!!.str, Compatibility.compute(size, ram).value)
    }

    @Test fun `profiles keep the preset's role order`() {
        assertEquals(
            listOf(ModelRole.REASONING, ModelRole.GENERAL, ModelRole.VERIFIER, ModelRole.EMBEDDING),
            Profiles.buildModelProfiles(RoutingPreset.RESEARCH, emptyList(), emptyMap(), 0).map { it.role },
        )
    }
}
