package team.sopa.boar.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The setup index's cores: the fastest few of the Auto set, never a little core. */
class CpuTopologyTest {
    private fun soc(vararg groups: Pair<Int, Long>): CpuTopology {
        var id = 0
        return CpuTopology(groups.flatMap { (n, khz) -> List(n) { CpuTopology.Core(id++, khz) } })
    }

    @Test fun `fastest 4 - Tensor G3 takes the X3 and three A715`() {
        val g3 = soc(4 to 1_704_000L, 4 to 2_450_000L, 1 to 2_910_000L)
        assertEquals(listOf(4, 5, 6, 8), g3.fastest(4))
    }

    @Test fun `fastest never reaches the little cores, even when fewer than asked`() {
        // 2 big + 6 little: Auto keeps the 2 big, so 2 threads, not 4.
        val twoBig = soc(6 to 1_800_000L, 2 to 2_600_000L)
        assertEquals(listOf(6, 7), twoBig.fastest(4))
        // Dimensity 8300 (X6 Pro): 1 prime + 3 big.
        val x6 = soc(4 to 2_200_000L, 1 to 3_350_000L, 3 to 3_200_000L)
        assertEquals(listOf(4, 5, 6, 7), x6.fastest(4))
    }

    @Test fun `fastest is empty when sysfs gave nothing`() {
        assertEquals(emptyList<Int>(), CpuTopology(emptyList()).fastest(4))
    }
}
