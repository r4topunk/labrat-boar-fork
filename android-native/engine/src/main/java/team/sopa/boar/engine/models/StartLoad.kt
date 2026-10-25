package team.sopa.boar.engine.models

/**
 * What the chat loads on its own (at start, or for a question while no model is loaded). It never loads a model whose
 * last load closed the app ([LoadGuard.isBlocked]), nor one rated RED for this phone ([Compatibility.compute]) unless the
 * user chose "Load anyway" for it. Nothing is hard-blocked: the chat offers the choice ([Decision]).
 */
object StartLoad {
    data class Model(val id: String, val sizeBytes: Long)

    sealed interface Decision {
        /** Load [modelId] (GREEN, YELLOW, unknown RAM, or RED the user already chose to load). */
        data class Load(val modelId: String) : Decision

        /** RED without "Load anyway": nothing loads; the chat asks. [onlyModel]: no other model is installed. */
        data class AskRisky(val modelId: String, val onlyModel: Boolean) : Decision

        /** [blockedId] closed the app last time and isn't loaded on its own. [onlyModel]: it's the only one installed. */
        data class NoFallback(val blockedId: String, val onlyModel: Boolean) : Decision

        /** No model installed. */
        data object NoModel : Decision
    }

    /**
     * [active] is the user's model (null when none is installed); [installed] every installed LLM; [loadAnyway] the
     * models the user chose to load although RED.
     */
    fun decide(
        active: Model?,
        installed: List<Model>,
        isBlocked: (String) -> Boolean,
        totalRamBytes: Long,
        loadAnyway: Set<String>,
    ): Decision {
        if (active == null) return Decision.NoModel
        val onlyModel = installed.none { it.id != active.id }
        if (isBlocked(active.id)) return Decision.NoFallback(active.id, onlyModel)
        if (risky(active, totalRamBytes) && active.id !in loadAnyway) return Decision.AskRisky(active.id, onlyModel)
        return Decision.Load(active.id)
    }

    /** RED for this phone; unknown RAM is never risky. */
    fun risky(model: Model, totalRamBytes: Long): Boolean = Compatibility.compute(model.sizeBytes, totalRamBytes) == Compatibility.RED
}
