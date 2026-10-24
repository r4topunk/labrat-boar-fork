package team.sopa.boar.engine.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reads the real catalog (app/src/main/assets/boar-data/catalog.json), so a change that breaks the closed one-model app fails here. */
class CatalogTest {
    private val catalog = Catalog.parse(File("../app/src/main/assets/boar-data/catalog.json").readText())

    @Test fun `Qwen3 1_7B is the only language model`() {
        assertEquals(listOf("qwen3-1.7b-q4km"), catalog.models.filter { it.kind == AssetKind.LLM }.map { it.id })
        assertEquals("qwen3-1.7b-q4km", catalog.answerModel.id)
        assertEquals("b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897", catalog.answerModel.sha256)
    }

    @Test fun `there is one search model, BGE small`() {
        assertEquals(listOf("bge-small-en-v1.5-q8"), catalog.models.filter { it.kind == AssetKind.EMBEDDING }.map { it.id })
    }

    @Test fun `required models are the search model plus the answer model`() {
        assertEquals(setOf("bge-small-en-v1.5-q8", "qwen3-1.7b-q4km"), catalog.requiredModels.map { it.id }.toSet())
    }

    @Test fun `every asset has a pinned sha256 and a file under the app's folder`() {
        catalog.models.forEach {
            assertTrue(it.id, it.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(it.id, it.filename.isNotBlank() && !it.filename.startsWith("/"))
        }
    }
}
