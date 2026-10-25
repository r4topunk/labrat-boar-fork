package team.sopa.boar.engine.models

/**
 * Rough RAM-fit estimate for a GGUF model on this device, used by the catalog
 * UI and the start-up memory check. Heuristic, not a measurement: a quantized model's resident working set tracks its file
 * size plus context/KV overhead, approximated as 1.15x.
 */
enum class Compatibility(val value: String) {
    GREEN("green"),
    YELLOW("yellow"),
    RED("red"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun compute(sizeBytes: Long, deviceRamBytes: Long): Compatibility {
            if (deviceRamBytes <= 0) return UNKNOWN
            val estimatedRamBytes = sizeBytes.toDouble() * 1.15
            if (estimatedRamBytes <= deviceRamBytes.toDouble() * 0.65) return GREEN
            if (estimatedRamBytes <= deviceRamBytes.toDouble() * 0.9) return YELLOW
            return RED
        }
    }
}
