package team.sopa.boar.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTweaksTest {
    /** Dimensity 8300 (X6 Pro): 1 prime + 3 big + 4 little. */
    private val x6 = CpuTopology(List(4) { CpuTopology.Core(it, 2_200_000) } + listOf(CpuTopology.Core(4, 3_350_000)) + List(3) { CpuTopology.Core(5 + it, 3_200_000) })
    private val x6Features = CpuVariants.readFeatures(
        "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 bti mte mte3\n",
    )
    /** Snapdragon 870 (POCO F3): dotprod and fp16, no i8mm. */
    private val f3Features = setOf("fp", "asimd", "asimdhp", "asimddp", "fphp")

    /** Qwen3 1.7B, the default answer model, is a hybrid model: the template's own default is thinking on. */
    @Test fun `thinking is off on Auto and in a plain answer request`() {
        assertEquals(false, EngineTweaks().thinkingEnabled)
        assertEquals(false, team.sopa.boar.engine.answer.AnswerRequest("q", null).enableThinking)
    }

    @Test fun `all Auto gives the measured defaults`() {
        val r = EngineTweaks().resolve(x6)
        assertEquals(4, r.params.threads)
        assertEquals("4,5,6,7", r.params.cpus)
        assertEquals(4096, r.params.nCtx)
        assertEquals(KvType.F16, r.params.typeK)
        assertTrue(r.notes.isEmpty())
        assertTrue(EngineTweaks().isAuto)
    }

    /** Cores from (count, max kHz) groups, numbered from 0 in the order given (sysfs lists the little cores first). */
    private fun soc(vararg groups: Pair<Int, Long>): CpuTopology {
        var id = 0
        return CpuTopology(groups.flatMap { (n, khz) -> List(n) { CpuTopology.Core(id++, khz) } })
    }

    @Test fun `Auto threads - 4 on the fast cores of every layout, never the little ones`() {
        // Tensor G1 (Pixel 6, 6a, 6 Pro): 4x A55 1.80 + 2x A76 2.25 + 2x X1 2.80 GHz. bigCores keeps only the 2 X1.
        val g1 = soc(4 to 1_803_000L, 2 to 2_253_000L, 2 to 2_802_000L)
        // Tensor G2 (Pixel 7, 7a, 7 Pro): 4x A55 1.80 + 2x A78 2.35 + 2x X1 2.85 GHz.
        val g2 = soc(4 to 1_803_000L, 2 to 2_348_000L, 2 to 2_850_000L)
        // Snapdragon 870 (POCO F3): 4x A55 1.80 + 3x A77 2.42 + 1x A77 3.19 GHz.
        val sd870 = soc(4 to 1_804_800L, 3 to 2_419_200L, 1 to 3_187_200L)
        // Tensor G3 (Pixel 8): 4x A510 1.70 + 4x A715 2.45 + 1x X3 2.91 GHz.
        val g3 = soc(4 to 1_704_000L, 4 to 2_450_000L, 1 to 2_910_000L)
        // 6 little + 2 big (Helio G99 class).
        val sixTwo = soc(6 to 2_000_000L, 2 to 2_200_000L)
        // One cluster, or no frequency readable.
        val flat = soc(8 to 0L)

        assertEquals(listOf(6, 7), g1.bigCores)
        assertEquals(listOf(4, 5, 6, 7), g1.autoCores)
        assertEquals(listOf(4, 5, 6, 7), g2.autoCores)
        assertEquals(listOf(4, 5, 6, 7), sd870.autoCores)
        assertEquals(listOf(4, 5, 6, 7), x6.autoCores)
        assertEquals(listOf(4, 5, 6, 7, 8), g3.autoCores)
        assertEquals(listOf(6, 7), sixTwo.autoCores)
        assertEquals((0..7).toList(), flat.autoCores)

        for (t in listOf(g1, g2, sd870, x6, g3, flat)) assertEquals(4, EngineTweaks().resolve(t).params.threads)
        assertEquals("4,5,6,7", EngineTweaks().resolve(g1).params.cpus)
        assertEquals(2, EngineTweaks().resolve(sixTwo).params.threads)
        // The user's own setting still wins.
        assertEquals(2, EngineTweaks(threads = 2, cores = "6,7").resolve(g1).params.threads)
        assertEquals("6,7", EngineTweaks(threads = 2, cores = "6,7").resolve(g1).params.cpus)
    }

    @Test fun `every knob can be set, and impossible values are clamped with a note`() {
        val r = EngineTweaks(threads = 64, cores = "all", contextTokens = 100, batch = 256, microBatch = 1024, kvCache = KvType.Q8_0, flashAttention = FlashAttn.ON).resolve(x6)
        assertEquals(8, r.params.threads)
        assertEquals("", r.params.cpus)
        assertEquals(256, r.params.nCtx)
        assertEquals(256, r.params.nUbatch)
        assertEquals(KvType.Q8_0, r.params.typeV)
        assertEquals(3, r.notes.size)
        assertEquals("1,3", EngineTweaks(cores = "3, 1, 99").resolve(x6).params.cpus)
        assertEquals("4,5,6,7", EngineTweaks(cores = "none").resolve(x6).params.cpus)
    }

    @Test fun `saved tweaks round-trip, and a broken file means all Auto`() {
        val t = EngineTweaks(cpuBuild = "android_armv9.0_1", threads = 6, kvCache = KvType.Q4_0)
        assertEquals(t, EngineTweaks.fromJson(t.toJson()))
        assertEquals("{}", EngineTweaks().toJson())
        assertEquals(EngineTweaks(), EngineTweaks.fromJson("{not json"))
        assertEquals(EngineTweaks(), EngineTweaks.fromJson(null))
    }

    @Test fun `CPU build - Auto picks armv8_6 on the X6 and leaves the F3 to ggml`() {
        assertEquals("android_armv8.6_1", CpuVariants.auto(x6Features))
        assertEquals(listOf("android_armv8.0_1", "android_armv8.2_1", "android_armv8.2_2", "android_armv8.6_1", "android_armv9.0_1"), CpuVariants.supported(x6Features).map { it.name })
        assertNull(CpuVariants.auto(f3Features))
        assertEquals(listOf("android_armv8.0_1", "android_armv8.2_1", "android_armv8.2_2"), CpuVariants.supported(f3Features).map { it.name })
        assertNull(CpuVariants.auto(x6Features + "sme"))
    }

    @Test fun `CPU build - a setting the phone can't run falls back to Auto`() {
        assertEquals("android_armv9.0_1", CpuVariants.resolve("android_armv9.0_1", x6Features))
        assertNull(CpuVariants.resolve("best", x6Features))
        assertNull(CpuVariants.resolve("android_armv8.6_1", f3Features))
        assertEquals("android_armv8.6_1", CpuVariants.resolve("android_armv9.2_2", x6Features))
    }

    @Test fun `generation time limit - Auto grows with the token cap, the user can fix it or turn it off`() {
        assertEquals(120_000L + 512 * 500L, EngineTweaks().stageTimeoutMs(512))
        assertEquals(120_000L + 2048 * 500L, EngineTweaks().stageTimeoutMs(2048))
        assertEquals(300_000L, EngineTweaks(stageTimeoutSeconds = 300).stageTimeoutMs(2048))
        assertNull(EngineTweaks(stageTimeoutSeconds = 0).stageTimeoutMs(2048))
        assertEquals(EngineTweaks(stageTimeoutSeconds = 0), EngineTweaks.fromJson(EngineTweaks(stageTimeoutSeconds = 0).toJson()))
    }

    @Test fun `a Deep Research write-up has no time limit on Auto, and a limit set by hand still applies`() {
        assertNull(EngineTweaks().writeUpTimeoutMs(2048))
        assertEquals(300_000L, EngineTweaks(stageTimeoutSeconds = 300).writeUpTimeoutMs(2048))
        assertNull(EngineTweaks(stageTimeoutSeconds = 0).writeUpTimeoutMs(2048))
    }

    @Test fun `Auto context follows the phone's RAM, as contextSizeForRam`() {
        val gib = 1024L * 1024 * 1024
        val topo = CpuTopology(listOf(CpuTopology.Core(0, 2_000_000)))
        assertEquals(4096, EngineTweaks().resolve(topo).params.nCtx)
        assertEquals(2048, EngineTweaks().resolve(topo, 4 * gib).params.nCtx)
        assertEquals(3072, EngineTweaks().resolve(topo, 6 * gib).params.nCtx)
        assertEquals(3072, EngineTweaks().resolve(topo, (6.5 * gib).toLong()).params.nCtx)
        assertEquals(4096, EngineTweaks().resolve(topo, 8 * gib).params.nCtx)
        assertEquals(8192, EngineTweaks(contextTokens = 8192).resolve(topo, 4 * gib).params.nCtx)
    }
}
