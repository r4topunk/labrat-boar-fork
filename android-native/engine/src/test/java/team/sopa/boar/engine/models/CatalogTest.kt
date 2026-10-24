package team.sopa.boar.engine.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reads the real exported catalog (scripts/export-native-data.mjs), so a mismatch with manifest.ts fails here. */
class CatalogTest {
    private val catalog = Catalog.parse(File("../app/src/main/assets/boar-data/catalog.json").readText())

    @Test fun `answer models are the default then the compact tier`() {
        assertEquals("qwen3-4b-instruct-2507-q4km", catalog.defaultAnswerModel.id)
        assertEquals("qwen3-1.7b-q4km", catalog.compactAnswerModel.id)
        assertEquals(listOf(AnswerTier.DEFAULT, AnswerTier.COMPACT), catalog.answerModels.map { it.answerTier })
    }

    @Test fun `setup offers every suggested model, smallest first, never a hidden one`() {
        val ids = catalog.setupModels.map { it.id }
        assertEquals("qwen2.5-1.5b-instruct-q4km", ids.first())
        assertTrue(catalog.answerModels.all { it.id in ids })
        assertTrue("phi-3.5-mini-instruct-q4km" !in ids)
        assertEquals(catalog.setupModels.sortedBy { it.sizeBytes }, catalog.setupModels)
    }

    @Test fun `required models are the embedding model plus the default answer model`() {
        assertEquals(setOf(AssetKind.EMBEDDING, AssetKind.LLM), catalog.requiredModels.map { it.kind }.toSet())
    }

    @Test fun `every asset has a pinned sha256 and a file under the app's folder`() {
        catalog.models.forEach {
            assertTrue(it.id, it.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(it.id, it.filename.isNotBlank() && !it.filename.startsWith("/"))
        }
    }

    @Test fun `every tier points at corpus assets that exist`() {
        val corpus = catalog.corpusCatalog.map { it.id }.toSet()
        catalog.tiers.forEach { t -> t.corpusPackIds.forEach { assertTrue("${t.id}: $it", it in corpus) } }
    }

    @Test fun `Phi-3_5-mini is no longer suggested, and nothing setup needs is hidden`() {
        assertEquals(listOf("phi-3.5-mini-instruct-q4km"), catalog.models.filter { it.hidden }.map { it.id })
        (catalog.requiredModels + catalog.answerModels).forEach { assertTrue(it.id, !it.hidden) }
    }
}
