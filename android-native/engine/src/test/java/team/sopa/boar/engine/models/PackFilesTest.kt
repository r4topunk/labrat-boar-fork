package team.sopa.boar.engine.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.answer.HealthGate
import java.io.File
import java.nio.file.Files

/**
 * The ids and order packs open under (Catalog.packFiles), shared by the app (BoarServices) and the device eval runner:
 * the runner once opened every .sqlite file in corpus/ as "local-<file>" in name order, so HealthGate never saw a health pack
 * there (poidh bounty 1520, claims 9243 and 9221).
 */
class PackFilesTest {
    private val catalog = Catalog.parse(File("../app/src/main/assets/boar-data/catalog.json").readText())
    private val catalogPacks = catalog.corpusCatalog.filter { it.format == "sqlite-pack" }

    private fun filesDir(vararg corpusFiles: String): File = Files.createTempDirectory("boar-packs").toFile().also { dir ->
        File(dir, "corpus").mkdirs()
        corpusFiles.forEach { File(dir, "corpus/$it").writeText("") }
    }

    @Test fun `catalog packs open under their catalog id, in catalog order, before other files by name`() {
        val dir = filesDir(*(catalogPacks.map { File(it.filename).name } + listOf("zz-mine.sqlite", "aa-mine.sqlite", "notes.txt")).toTypedArray())
        val ids = catalog.packFiles(dir).map { it.first }
        assertEquals(catalogPacks.map { it.id } + listOf("local-aa-mine", "local-zz-mine"), ids)
        assertEquals(catalogPacks.map { File(dir, it.filename) }, catalog.packFiles(dir).take(catalogPacks.size).map { it.second })
    }

    @Test fun `the health packs open under the ids HealthGate licenses`() {
        val dir = filesDir(*catalogPacks.map { File(it.filename).name }.toTypedArray())
        assertTrue(catalog.packFiles(dir).map { it.first }.containsAll(HealthGate.HEALTH_PACKS))
    }

    @Test fun `a sideloaded file named like a health pack is not a catalog pack`() {
        val dir = filesDir("boar-medlineplus-en-copy.sqlite")
        assertEquals(listOf("local-boar-medlineplus-en-copy"), catalog.packFiles(dir).map { it.first })
    }

    @Test fun `packs not on disk are left out`() {
        val dir = filesDir("qa-health-se.sqlite", "boar-crypto.sqlite")
        assertEquals(listOf("boar-crypto", "qa-health-se"), catalog.packFiles(dir).map { it.first })
    }

    @Test fun `no corpus folder opens nothing`() {
        assertEquals(emptyList<Pair<String, File>>(), catalog.packFiles(Files.createTempDirectory("boar-packs").toFile()))
    }
}
