package team.sopa.boar.engine.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGuardTest {
    private class Mem : LoadGuard.MarkerStore {
        var v: String? = null
        override fun read() = v
        override fun write(json: String) { v = json }
        override fun clear() { v = null }
    }
    private class L(crashed: List<String> = emptyList()) : LoadGuard.Ledger {
        override var crashedIds: List<String> = crashed
        override var mappedIds: List<String> = emptyList()
        override var pendingCrash: LoadGuard.Crash? = null
    }

    private val big = LoadGuard.Meta("models/qwen3-4b.gguf", "qwen3-4b", "Qwen3-4B")
    private val small = LoadGuard.Meta("models/qwen2.5-1.5b.gguf", "qwen2.5-1.5b", "Qwen2.5-1.5B")

    /** A process that starts the load and is killed before it returns; then a new process on the same files. */
    private fun killedDuring(store: Mem, ledger: L, meta: LoadGuard.Meta, repacked: Boolean, previous: LoadGuard.Meta? = null): LoadGuard {
        LoadGuard(store, ledger) { 1000 }.begin(meta, previous, repacked)
        return LoadGuard(store, ledger)
    }

    @Test fun `a load that returns and settles leaves no marker and nothing blocked`() {
        val store = Mem(); val ledger = L()
        val g = LoadGuard(store, ledger)
        g.begin(big, null, repacked = true)
        g.end(big, loaded = true)
        g.settled("qwen3-4b")
        assertNull(store.v)
        assertFalse(LoadGuard(store, ledger).isBlocked("qwen3-4b"))
    }

    @Test fun `killed after the load returned but before it settled still counts as a load kill`() {
        val store = Mem(); val ledger = L()
        val g = LoadGuard(store, ledger)
        g.begin(big, null, repacked = true)
        g.end(big, loaded = true)
        // The process dies here (F3, Oct 18: 4 s after the load returned).
        val next = LoadGuard(store, ledger)
        assertTrue(next.isBlocked("qwen3-4b"))
        assertEquals("qwen3-4b", next.consume()!!.crashedModelId)
    }

    @Test fun `settling another model leaves the marker`() {
        val store = Mem(); val ledger = L()
        val g = LoadGuard(store, ledger)
        g.begin(big, null, repacked = true); g.end(big, loaded = true)
        g.settled("qwen2.5-1.5b")
        assertTrue(LoadGuard(store, ledger).isBlocked("qwen3-4b"))
    }

    @Test fun `a killed repacked load blocks the model for automatic loads after one kill`() {
        val store = Mem(); val ledger = L()
        val g = killedDuring(store, ledger, big, repacked = true, previous = small)
        assertTrue(g.isBlocked("qwen3-4b"))
        assertFalse(g.mappedNext("qwen3-4b"))
        val crash = g.consume()!!
        assertEquals("qwen3-4b", crash.crashedModelId)
        assertEquals("Qwen2.5-1.5B", crash.fallbackLabel)
        assertNull(g.consume())
        assertNull(store.v)
    }

    @Test fun `try again with less memory loads the mapped file, and keeps it after it works`() {
        val store = Mem(); val ledger = L()
        val g = killedDuring(store, ledger, big, repacked = true)
        g.retryMapped("qwen3-4b")
        assertFalse(g.isBlocked("qwen3-4b"))
        assertTrue(g.mappedNext("qwen3-4b"))
        g.begin(big, null, repacked = false)
        g.end(big, loaded = true)
        g.settled("qwen3-4b")
        // The next start doesn't go back to the repacked load that closed the app.
        val g2 = LoadGuard(store, ledger)
        assertTrue(g2.mappedNext("qwen3-4b"))
        assertFalse(g2.isBlocked("qwen3-4b"))
    }

    @Test fun `a killed mapped load is a crash, reported once with the fallback`() {
        val store = Mem(); val ledger = L()
        val g1 = killedDuring(store, ledger, big, repacked = true)
        g1.consume()
        g1.retryMapped("qwen3-4b")
        assertTrue(g1.mappedNext("qwen3-4b"))
        g1.begin(big, small, repacked = false)
        val g2 = LoadGuard(store, ledger)
        assertTrue(g2.isBlocked("qwen3-4b"))
        assertFalse(g2.mappedNext("qwen3-4b"))
        val crash = g2.consume()!!
        assertEquals(listOf("qwen3-4b", "Qwen3-4B", "qwen2.5-1.5b", "Qwen2.5-1.5B"), listOf(crash.crashedModelId, crash.crashedLabel, crash.fallbackModelId, crash.fallbackLabel))
        assertNull(g2.consume())
    }

    @Test fun `ledger and marker JSON from the earlier version still parse`() {
        // A marker written before this change (no "repacked" key: a mapped load) and a crash as settings.json stored it.
        val store = Mem().apply { v = """{"filename":"models/qwen3-4b.gguf","modelId":"qwen3-4b","label":"Qwen3-4B","previous":{"filename":"models/qwen2.5-1.5b.gguf","modelId":"qwen2.5-1.5b","label":"Qwen2.5-1.5B"},"at":1000}""" }
        val ledger = L().apply { mappedIds = listOf("qwen3-4b", "lfm2.5-8b") }
        val g = LoadGuard(store, ledger)
        assertTrue(g.isBlocked("qwen3-4b"))
        assertFalse(g.mappedNext("qwen3-4b"))
        assertTrue(g.mappedNext("lfm2.5-8b"))
        val stored = LoadGuard.crashFromJson("""{"crashedModelId":"a","crashedLabel":"A","fallbackModelId":"b","fallbackLabel":"B","at":5,"extra":1}""")!!
        assertEquals(LoadGuard.Crash("a", "A", "b", "B", 5), stored)
        assertEquals(stored, LoadGuard.crashFromJson(LoadGuard.crashToJson(stored)))
    }

    @Test fun `a load that works unblocks it`() {
        val store = Mem(); val ledger = L()
        val g = killedDuring(store, ledger, big, repacked = false)
        assertTrue(g.isBlocked("qwen3-4b"))
        ledger.crashedIds = listOf("qwen3-4b")
        g.begin(big, null, repacked = true); g.end(big, loaded = true); g.settled("qwen3-4b")
        assertFalse(g.isBlocked("qwen3-4b"))
    }

    @Test fun `a failed load (returned, not killed) is neither a crash nor a mapped retry`() {
        val store = Mem(); val ledger = L()
        val g = LoadGuard(store, ledger)
        g.begin(big, null, repacked = true); g.end(big, loaded = false)
        val g2 = LoadGuard(store, ledger)
        assertFalse(g2.isBlocked("qwen3-4b")); assertFalse(g2.mappedNext("qwen3-4b"))
    }

    @Test fun `an unreadable marker is dropped`() {
        val store = Mem().apply { v = "{not json" }; val ledger = L()
        val g = LoadGuard(store, ledger)
        assertFalse(g.isBlocked("qwen3-4b"))
        assertNull(store.v)
    }

    @Test fun `a stall is under 1 tok per s for at least 10 s of writing`() {
        assertTrue(LoadGuard.isDecodeStall(5, 12_000.0))
        assertFalse(LoadGuard.isDecodeStall(5, 9_000.0)) // too short to tell
        assertFalse(LoadGuard.isDecodeStall(12, 10_000.0)) // 1.2 tok/s
        assertTrue(LoadGuard.isDecodeStall(0, 10_000.0))
    }

    // Issue #89: a process the user swiped away, force-stopped or updated isn't a load kill.
    @Test fun `an exit Android records as the user's or an update clears the marker without blocking`() {
        val store = Mem(); val ledger = L()
        LoadGuard(store, ledger) { 1000 }.begin(big, small, repacked = true)
        val g = LoadGuard(store, ledger).apply { exitWasNotLoadKill = { at -> at == 1000L } }
        assertFalse(g.isBlocked("qwen3-4b"))
        assertNull(g.consume())
        assertNull(store.v)
    }

    @Test fun `an unknown exit still counts as a load kill`() {
        val store = Mem(); val ledger = L()
        val g = killedDuring(store, ledger, big, repacked = true, previous = small)
        assertTrue(g.isBlocked("qwen3-4b"))
        assertEquals("qwen3-4b", g.consume()?.crashedModelId)
    }

    @Test fun `a failing exit check counts as a load kill`() {
        val store = Mem(); val ledger = L()
        LoadGuard(store, ledger) { 1000 }.begin(big, small, repacked = true)
        val g = LoadGuard(store, ledger).apply { exitWasNotLoadKill = { error("no record") } }
        assertTrue(g.isBlocked("qwen3-4b"))
    }

    @Test fun `the warm-up prefill does not settle the load, a real answer does`() {
        val store = Mem(); val ledger = L()
        val g = LoadGuard(store, ledger)
        g.begin(big, small, repacked = true)
        g.end(big, loaded = true)
        // The chat's 0-token warm prefill, then a cancelled and a failed generation: the marker stays.
        g.generated("qwen3-4b", 0, null)
        g.generated("qwen3-4b", 0, "cancelled")
        g.generated("qwen3-4b", 0, "prefill failed")
        assertTrue(store.v != null)
        // Killed now (on the first real answer): the next process still sees the load kill and blocks the model.
        val next = LoadGuard(store, ledger)
        assertTrue(next.isBlocked("qwen3-4b"))
        assertEquals("qwen3-4b", next.consume()?.crashedModelId)
    }

    @Test fun `an answer that wrote tokens settles the load and unblocks the model`() {
        val store = Mem(); val ledger = L(crashed = listOf("qwen3-4b"))
        val g = LoadGuard(store, ledger)
        g.begin(big, null, repacked = false)
        g.end(big, loaded = true)
        g.generated("qwen3-4b", 42, null)
        assertNull(store.v)
        assertFalse(LoadGuard(store, ledger).isBlocked("qwen3-4b"))
    }

    @Test fun `only tokens without an error prove the model fits`() {
        assertFalse(LoadGuard.provesFit(0, null))
        assertFalse(LoadGuard.provesFit(5, "prompt longer than the context"))
        assertTrue(LoadGuard.provesFit(1, null))
        assertTrue(LoadGuard.provesFit(1, ""))
    }
}
