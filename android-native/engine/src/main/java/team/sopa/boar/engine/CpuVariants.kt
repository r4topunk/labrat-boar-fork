package team.sopa.boar.engine

import java.io.File

/**
 * The ggml CPU builds packaged in the app (one per instruction set, see ggml/src/CMakeLists.txt) and which one to
 * load. The user picks in advanced settings; Auto is the default.
 *
 * Auto: ggml ranks the SVE build (armv9.0) highest on phones without SME, but on the X6 Pro (Dimensity 8300) its
 * prompt processing for Q4_K weights was 15–45% slower than the dotprod+i8mm build without SVE (armv8.6), with the
 * same generation speed. So Auto takes armv8.6 on
 * phones with i8mm and no SME, and leaves every other phone (SME, or no i8mm) to ggml's own choice, which nothing
 * measured contradicts yet.
 */
object CpuVariants {
    data class Variant(val name: String, val label: String, val needs: Set<String>)

    /** Lowest to highest, as ggml declares them; [Variant.needs] are /proc/cpuinfo feature flags. */
    val ALL = listOf(
        Variant("android_armv8.0_1", "armv8.0", emptySet()),
        Variant("android_armv8.2_1", "armv8.2 · dotprod", setOf("asimddp")),
        Variant("android_armv8.2_2", "armv8.2 · dotprod, fp16", setOf("asimddp", "asimdhp")),
        Variant("android_armv8.6_1", "armv8.6 · dotprod, fp16, i8mm", setOf("asimddp", "asimdhp", "i8mm")),
        Variant("android_armv9.0_1", "armv9.0 · i8mm, SVE2", setOf("asimddp", "asimdhp", "i8mm", "sve2")),
        Variant("android_armv9.2_1", "armv9.2 · i8mm, SVE, SME", setOf("asimddp", "asimdhp", "i8mm", "sve", "sme")),
        Variant("android_armv9.2_2", "armv9.2 · i8mm, SVE2, SME", setOf("asimddp", "asimdhp", "i8mm", "sve", "sve2", "sme")),
    )

    /** The user's setting: "auto" (default), "best" (ggml's own pick) or a variant name. */
    const val AUTO = "auto"
    const val BEST = "best"

    /** Feature flags of the first CPU in /proc/cpuinfo (all cores of a phone report the same set). */
    fun readFeatures(cpuinfo: String = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")): Set<String> =
        cpuinfo.lineSequence().firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet() ?: emptySet()

    /** The builds this CPU can run, lowest first: the only ones settings offer. */
    fun supported(features: Set<String>): List<Variant> = ALL.filter { features.containsAll(it.needs) }

    /** What Auto loads: a variant name, or null for ggml's own pick. */
    fun auto(features: Set<String>): String? =
        if ("i8mm" in features && "sme" !in features && supported(features).any { it.name == "android_armv8.6_1" }) "android_armv8.6_1" else null

    /**
     * The library to load for a setting: null means ggml's own pick. A named variant the CPU can't run (a setting
     * restored on another phone) falls back to Auto.
     */
    fun resolve(setting: String?, features: Set<String>): String? = when (setting ?: AUTO) {
        BEST -> null
        AUTO -> auto(features)
        else -> if (supported(features).any { it.name == setting }) setting else auto(features)
    }
}
