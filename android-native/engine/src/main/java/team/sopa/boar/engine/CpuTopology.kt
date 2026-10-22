package team.sopa.boar.engine

import java.io.File

/** The phone's cores grouped by top frequency, read from sysfs (no permissions needed). */
data class CpuTopology(val cores: List<Core>) {
    data class Core(val id: Int, val maxKhz: Long)

    /** Cores in the fastest frequency group, e.g. the 1-4 "big" cores of a big.LITTLE SoC. */
    val bigCores: List<Int> get() {
        val top = cores.maxOfOrNull { it.maxKhz } ?: return emptyList()
        val sorted = cores.map { it.maxKhz }.distinct().sortedDescending()
        // Take the fastest group, plus the second one when the fastest has fewer than 2 cores (prime + big).
        val keep = if (cores.count { it.maxKhz == top } < 2 && sorted.size > 1) sorted.take(2).toSet() else setOf(top)
        return cores.filter { it.maxKhz in keep }.map { it.id }
    }

    /**
     * The cores Auto runs on: the fastest frequency groups, adding the next group until there are 4 cores, but never the
     * slowest group once there are 2 (the little cores: every thread then waits for the slowest one; on the X6, 6 and 8
     * threads halved generation). 1 prime + 3 big (Dimensity 8300, Snapdragon 870): those 4. 2 + 2 + 4 (Tensor G1/G2,
     * Pixel 6 and 7): the 2 + 2 fast ones, where [bigCores] keeps 2. 2 big + 6 little: the 2 big. One group: all of them.
     */
    val autoCores: List<Int> get() {
        val groups = cores.groupBy { it.maxKhz }.toSortedMap(compareByDescending { it }).values.toList()
        val keep = ArrayList<Core>()
        for ((i, group) in groups.withIndex()) {
            if (keep.size >= 4 || (i == groups.lastIndex && keep.size >= 2)) break
            keep += group
        }
        return keep.map { it.id }.sorted()
    }

    /**
     * The [n] fastest of [autoCores] (never a little core), for work that wants a fixed thread count such as the setup
     * index: Tensor G3 (1 X3 + 4 A715 + 4 A510) gives the X3 and three A715. Empty when sysfs said nothing.
     */
    fun fastest(n: Int): List<Int> {
        val auto = autoCores.toSet()
        return cores.filter { it.id in auto }.sortedWith(compareByDescending<Core> { it.maxKhz }.thenBy { it.id }).take(n).map { it.id }.sorted()
    }

    /** "4-7" style list for [EngineParams.cpus]. */
    fun cpuList(ids: List<Int>): String = ids.sorted().joinToString(",")

    companion object {
        fun read(): CpuTopology {
            val dirs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
            val cores = dirs.mapNotNull { d ->
                val id = d.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                val khz = runCatching { File(d, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
                Core(id, khz)
            }.sortedBy { it.id }
            return CpuTopology(cores)
        }
    }
}
