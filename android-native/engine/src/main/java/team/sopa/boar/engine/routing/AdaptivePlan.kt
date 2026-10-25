package team.sopa.boar.engine.routing

import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.Catalog
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.routing.Profiles.AvailableModel
import team.sopa.boar.engine.routing.Profiles.ModelProfile
import team.sopa.boar.engine.routing.Router.RoutingPlan

/**
 * The planning half of src/services/adaptiveChat.ts runAdaptiveChat: classify -> profiles -> plan, from on-device
 * state the caller resolved (installed models, preset, role assignments, active model, RAM). Execution is separate.
 */
object AdaptivePlan {
    data class Result(
        /** Classify.classifyTask(query): the plan doesn't carry it, only the decisions made from it. */
        val taskType: TaskType,
        val profiles: List<ModelProfile>,
        val plan: RoutingPlan,
    ) {
        /** The generate step's model id, null if the plan has no generate step. */
        val generateModelId: String? get() = plan.steps.firstOrNull { it.type == RoutingStepType.GENERATE }?.modelId

        /** adaptiveChat's modelUsed: the generate step's model, looked up in the catalog (null for a non-catalog model). */
        fun modelUsed(catalog: Catalog): CatalogAsset? = generateModelId?.let(catalog::byId)
    }

    /**
     * [statuses]: every catalog asset with whether it is on disk (the whole catalog); only LLMs are used.
     * [overrides]: the user's role assignments. [activeModelId]: the model the user picked ("Use"); it fills the fast
     * and general roles unless the user assigned those explicitly (without this, balanced routing ignored the user's
     * choice). [deviceRamBytes]: total RAM, 0 when unreadable (compatibility becomes "unknown").
     */
    fun plan(
        query: String,
        statuses: List<AvailableModel>,
        preset: RoutingPreset,
        overrides: Map<ModelRole, String>,
        activeModelId: String?,
        deviceRamBytes: Long,
        maxTokens: Int,
    ): Result {
        val available = statuses.filter { it.model.kind == AssetKind.LLM }
        val taskType = Classify.classifyTask(query)
        val effectiveOverrides = if (!activeModelId.isNullOrEmpty()) {
            mapOf(ModelRole.FAST to activeModelId, ModelRole.GENERAL to activeModelId) + overrides
        } else {
            overrides
        }
        val profiles = Profiles.buildModelProfiles(preset, available, effectiveOverrides, deviceRamBytes, activeModelId)
        val context = Router.RoutingContext(
            taskType = taskType,
            query = query,
            hasLocalKnowledgeBase = true,
            retrievalAvailable = true,
            availableModels = profiles,
            preset = preset,
            budget = Router.InferenceBudget(maxTokens = maxTokens, allowVerification = true, allowRetrieval = true),
        )
        return Result(taskType, profiles, Router.planRoute(context))
    }
}
