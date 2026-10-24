package team.sopa.boar.engine.models

/**
 * The answer model the setup wizard recommends (port of src/ui-android/setupModel.ts, changed Oct 2026).
 *
 * Qwen3 1.7B, thinking off, is BOAR's answer model on every phone, 8 GB ones included: it is the model the research
 * measured. Measured earlier (eval_scores, 2026-10-02): on a POCO F3 (8 GB, reads 7.3 GB) a 1.5B answers at 16-18 tok/s
 * while 4B-class models ran at ~5 tok/s with 3.4-3.9 GB peak memory, and on a POCO X6 Pro (12 GB, reads 10.7 GB)
 * Android closed the backgrounded app holding the 4B. The 4B stays listed as the optional, fuller answer.
 */
object SetupModel {
    /** 12 GB phones report about 10.7 GB, 8 GB phones about 7.3 GB. Under it the default tier is heavy (see [heavyForPhone]). */
    const val RECOMMEND_DEFAULT_MIN_RAM_BYTES: Long = 10L * 1024 * 1024 * 1024

    /** The compact tier (Qwen3 1.7B) whatever the RAM, so [totalRamBytes] doesn't change the pick. */
    @Suppress("UNUSED_PARAMETER")
    fun recommended(models: List<CatalogAsset>, totalRamBytes: Long): CatalogAsset? =
        models.firstOrNull { it.answerTier == AnswerTier.COMPACT } ?: models.firstOrNull()

    /**
     * True when the model is bigger than this phone is recommended for: it may be slow or closed in the background.
     * The 4B (2.5 GB) is heavy under 12 GB, as measured above; that is a file over a quarter of the RAM, which
     * the same rule applies to the other catalog models (7-8B, ~5 GB: heavy on a 12 GB phone too). Never the compact tier.
     */
    fun heavyForPhone(model: CatalogAsset, totalRamBytes: Long): Boolean = when {
        model.answerTier == AnswerTier.COMPACT || totalRamBytes <= 0 -> false
        model.answerTier == AnswerTier.DEFAULT -> totalRamBytes < RECOMMEND_DEFAULT_MIN_RAM_BYTES
        else -> model.sizeBytes > totalRamBytes / 4
    }

    /** The model to download: the user's pick, else one already on the phone, else the recommended one. */
    fun toInstall(models: List<CatalogAsset>, present: Set<String>, totalRamBytes: Long, chosenId: String? = null): CatalogAsset? =
        models.firstOrNull { it.id == chosenId } ?: models.firstOrNull { it.id in present } ?: recommended(models, totalRamBytes)
}
