package team.sopa.boar.engine.models

import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.models.StartLoad.Decision

class StartLoadTest {
    private val gb = 1_000_000_000L
    /** An 8 GB phone: RED above 0.9 × 8 GB / 1.15 ≈ 6.26 GB of model, GREEN up to ≈ 4.52 GB. */
    private val ram = 8 * gb
    private val small = StartLoad.Model("qwen2.5-1.5b", 1 * gb)
    private val mid = StartLoad.Model("qwen3-4b", 2_500_000_000L)
    private val yellow = StartLoad.Model("lfm2.5-8b", 4_800_000_000L)
    private val red = StartLoad.Model("big-12b", 7 * gb)
    private val none: (String) -> Boolean = { false }

    private fun decide(active: StartLoad.Model?, installed: List<StartLoad.Model>, blocked: Set<String> = emptySet(), anyway: Set<String> = emptySet()) =
        StartLoad.decide(active, installed, { it in blocked }, ram, anyway)

    @Test fun `green and yellow models load`() {
        assertEquals(Decision.Load("qwen3-4b"), decide(mid, listOf(small, mid)))
        assertEquals(Decision.Load("lfm2.5-8b"), decide(yellow, listOf(yellow)))
    }

    @Test fun `a red model asks first, unless the user chose Load anyway`() {
        assertEquals(Decision.AskRisky("big-12b", onlyModel = false), decide(red, listOf(small, red)))
        assertEquals(Decision.AskRisky("big-12b", onlyModel = true), decide(red, listOf(red)))
        assertEquals(Decision.Load("big-12b"), decide(red, listOf(small, red), anyway = setOf("big-12b")))
    }

    @Test fun `unknown RAM is never risky`() {
        assertEquals(Decision.Load("big-12b"), StartLoad.decide(red, listOf(red), none, 0, emptySet()))
    }

    @Test fun `a blocked model loads nothing, whatever else is installed`() {
        assertEquals(Decision.NoFallback("lfm2.5-8b", onlyModel = false), decide(yellow, listOf(small, mid, yellow), setOf("lfm2.5-8b")))
        assertEquals(Decision.NoFallback("lfm2.5-8b", onlyModel = false), decide(yellow, listOf(small, yellow), setOf("lfm2.5-8b", "qwen2.5-1.5b")))
    }

    @Test fun `the only model, blocked, gets the download message`() {
        assertEquals(Decision.NoFallback("lfm2.5-8b", onlyModel = true), decide(yellow, listOf(yellow), setOf("lfm2.5-8b")))
        // Load anyway doesn't override a block: that's Try again with less memory.
        assertEquals(Decision.NoFallback("big-12b", onlyModel = true), decide(red, listOf(red), setOf("big-12b"), anyway = setOf("big-12b")))
    }

    @Test fun `no model installed`() {
        assertEquals(Decision.NoModel, decide(null, emptyList()))
    }
}
