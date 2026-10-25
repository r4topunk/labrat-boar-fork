package team.sopa.boar.engine.eval

/**
 * What the Settings telemetry and the Evaluation screen say about this phone, and how the raw hardware values read as
 * text. Nothing here is sent anywhere: LabRat has no network permission.
 */
object DeviceInfo {
    data class Device(
        val platform: String = "android",
        val osVersion: String? = null,
        val apiLevel: Int? = null,
        val brand: String? = null,
        val model: String? = null,
        val soc: String? = null,
        val socManufacturer: String? = null,
        val hardware: String? = null,
        val ramBytes: Long? = null,
        val cpuCores: Int? = null,
        val cpuFeatures: List<String>? = null,
        val coreMaxFreqKHz: List<Long>? = null,
    )

    fun parseCpuFeatures(line: String?): List<String> = (line ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun describeChipset(d: Device): String? =
        if (!d.soc.isNullOrEmpty()) listOfNotNull(d.socManufacturer?.ifEmpty { null }, d.soc).joinToString(" ") else d.hardware?.ifEmpty { null }

    /** "1 × 3.35 GHz + 3 × 3.2 GHz + 4 × 2.2 GHz": cores grouped by top frequency, fastest first. */
    fun describeCores(freqs: List<Long>?): String? {
        val known = freqs.orEmpty().filter { it > 0 }
        if (known.isEmpty()) return null
        return known.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }.joinToString(" + ") { (f, n) ->
            "$n × ${java.math.BigDecimal(f / 1e6).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} GHz"
        }
    }
}
