package team.sopa.boar.engine.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of src/ui-android/setupModel.test.ts. */
class SetupModelTest {
    private val gb = 1024.0 * 1024 * 1024
    private fun asset(id: String, tier: AnswerTier) = CatalogAsset(id, AssetKind.LLM, id, null, "models/$id.gguf", 1, "", "", "", "", false, tier)
    private val big = asset("qwen3-4b", AnswerTier.DEFAULT)
    private val small = asset("qwen3-1.7b", AnswerTier.COMPACT)
    private val models = listOf(big, small)
    private fun ram(g: Double) = (g * gb).toLong()

    @Test fun `recommends the compact model on a 12 GB phone too (reads 10_7 GB)`() = assertEquals(small, SetupModel.recommended(models, ram(10.7)))

    @Test fun `recommends the compact model on an 8 GB phone and smaller`() {
        assertEquals(small, SetupModel.recommended(models, ram(7.3)))
        assertEquals(small, SetupModel.recommended(models, ram(3.8)))
    }

    @Test fun `recommends the compact model when the RAM couldn't be read`() = assertEquals(small, SetupModel.recommended(models, 0))

    @Test fun `flags the 4B under 12 GB, never the compact model`() {
        assertTrue(SetupModel.heavyForPhone(big, ram(7.3)))
        assertFalse(SetupModel.heavyForPhone(big, ram(10.7)))
        assertFalse(SetupModel.heavyForPhone(small, ram(3.8)))
    }

    @Test fun `doesn't warn when the RAM couldn't be read`() = assertFalse(SetupModel.heavyForPhone(big, 0))

    @Test fun `flags a 5 GB catalog model even on a 12 GB phone, a 1 GB one nowhere above 4 GB`() {
        fun other(bytes: Double) = CatalogAsset("other", AssetKind.LLM, "other", null, "models/other.gguf", (bytes * gb).toLong(), "", "", "", "", false)
        assertTrue(SetupModel.heavyForPhone(other(5.2), ram(10.7)))
        assertFalse(SetupModel.heavyForPhone(other(1.0), ram(7.3)))
    }

    @Test fun `takes the user's pick over everything`() =
        assertEquals(big, SetupModel.toInstall(models, setOf(small.id), ram(10.7), big.id))

    @Test fun `keeps a model already on the phone instead of downloading another`() =
        assertEquals(small, SetupModel.toInstall(models, setOf(small.id), ram(10.7)))

    @Test fun `falls back to the recommended one`() {
        assertEquals(small, SetupModel.toInstall(models, emptySet(), ram(10.7)))
        assertEquals(small, SetupModel.toInstall(models, emptySet(), ram(7.3)))
    }
}
