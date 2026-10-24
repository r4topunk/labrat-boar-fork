package team.sopa.boar.engine.models

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

/** The bundle installer on tiny fake files (never a real model or pack): manifest, checksums, resume, sources, space. */
class BundleInstallerTest {
    private val model = Random(1).nextBytes(300_000)
    private val pack = Random(2).nextBytes(120_000)
    private lateinit var root: File
    private lateinit var files: File
    private lateinit var bundle: File

    @Before fun start() {
        root = Files.createTempDirectory("boar-bundle").toFile()
        files = File(root, "files").apply { mkdirs() }
        bundle = File(root, "external/bundle").apply { mkdirs() }
    }

    @After fun stop() { root.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun asset(id: String, filename: String, bytes: ByteArray, kind: AssetKind, required: Boolean) = CatalogAsset(
        id = id, kind = kind, label = id, filename = filename, sizeBytes = bytes.size.toLong(), sha256 = sha(bytes),
        license = "MIT", description = "", required = required,
    )

    private val llm = asset("llm", "models/llm.gguf", model, AssetKind.LLM, required = true)
    private val packAsset = asset("p", "corpus/p.sqlite", pack, AssetKind.CORPUS, required = false)
    private val catalog = Catalog(50_000_000_000, 12L shl 30, listOf(llm, packAsset))

    private fun installer(free: Long = Long.MAX_VALUE) = BundleInstaller(files, catalog, freeBytes = { free })

    private fun manifestJson(vararg a: CatalogAsset) =
        a.joinToString(",", "[", "]") { """{"path":"${it.filename}","sizeBytes":${it.sizeBytes},"sha256":"${it.sha256}"}""" }

    /** Fills the fake adb-push folder with [content] for each file (the real bytes unless overridden). */
    private fun stage(content: Map<String, ByteArray> = mapOf(llm.filename to model, packAsset.filename to pack)): DirBundleSource {
        content.forEach { (path, bytes) -> File(bundle, path).apply { parentFile.mkdirs(); writeBytes(bytes) } }
        File(bundle, DirBundleSource.MANIFEST).writeText(manifestJson(llm, packAsset))
        return DirBundleSource(bundle)
    }

    private fun items(src: BundleSource, inst: BundleInstaller = installer()) = inst.resolve(BundleManifest.parse(src.manifest()!!))

    @Test fun `parses the manifest and rejects a malformed one`() {
        val entries = BundleManifest.parse(manifestJson(llm, packAsset))
        assertEquals(listOf("models/llm.gguf", "corpus/p.sqlite"), entries.map { it.path })
        assertEquals(model.size.toLong(), entries[0].sizeBytes)
        for (bad in listOf("[]", """[{"path":"../x","sizeBytes":1,"sha256":"${"a".repeat(64)}"}]""",
            """[{"path":"a","sizeBytes":1,"sha256":"xyz"}]""", manifestJson(llm, llm))) {
            try { BundleManifest.parse(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `installs every file and records it as verified`() = runBlocking {
        val src = stage()
        val inst = installer()
        val result = inst.install(items(src, inst), { src.open(it.entry.path) })
        assertEquals(2, result.copied)
        assertTrue(File(files, "models/llm.gguf").readBytes().contentEquals(model))
        val store = AssetStore(files, catalog)
        assertEquals(true, store.status(llm).checksumOk)
        assertEquals(true, store.status(packAsset).checksumOk)
        assertFalse(File(files, "models/llm.gguf.part").exists())
    }

    @Test fun `a file whose bytes don't match its sha256 is rejected and not left behind`() = runBlocking {
        val corrupt = model.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val src = stage(mapOf(llm.filename to corrupt, packAsset.filename to pack))
        val inst = installer()
        try { inst.install(items(src, inst), { src.open(it.entry.path) }); fail("installed a corrupt file") } catch (_: BundleInstaller.Mismatch) {}
        assertFalse(File(files, "models/llm.gguf").exists())
        assertFalse(File(files, "models/llm.gguf.part").exists())
    }

    @Test fun `a manifest that disagrees with the catalog fails before copying`() {
        val inst = installer()
        val wrong = """[{"path":"models/llm.gguf","sizeBytes":${model.size},"sha256":"${"0".repeat(64)}"}]"""
        try { inst.resolve(BundleManifest.parse(wrong)); fail("accepted a sha the catalog doesn't list") } catch (_: BundleInstaller.Mismatch) {}
        val unknown = """[{"path":"models/other.gguf","sizeBytes":1,"sha256":"${"0".repeat(64)}"}]"""
        try { inst.resolve(BundleManifest.parse(unknown)); fail("accepted a file the catalog doesn't list") } catch (_: BundleInstaller.Mismatch) {}
    }

    @Test fun `a second run skips the files already verified`() = runBlocking {
        val src = stage()
        val inst = installer()
        inst.install(items(src, inst), { src.open(it.entry.path) })
        val opened = mutableListOf<String>()
        val again = inst.install(items(src, inst), { opened += it.entry.path; src.open(it.entry.path) })
        assertEquals(0, again.copied)
        assertEquals(2, again.skipped)
        assertTrue(opened.isEmpty())
    }

    @Test fun `an interrupted install resumes at the missing file`() = runBlocking {
        val src = stage()
        val inst = installer()
        // The pack fails half way (the app was killed): the model stays installed, the pack is copied on the next run.
        try {
            inst.install(items(src, inst), { if (it.asset.id == "p") throw java.io.IOException("killed") else src.open(it.entry.path) })
            fail("expected the failure")
        } catch (_: java.io.IOException) {}
        assertEquals(true, AssetStore(files, catalog).status(llm).checksumOk)
        val opened = mutableListOf<String>()
        val resumed = inst.install(items(src, inst), { opened += it.asset.id; src.open(it.entry.path) })
        assertEquals(listOf("p"), opened)
        assertEquals(1, resumed.skipped)
    }

    @Test fun `a right-size file without an integrity record is hashed in place, not copied again`() = runBlocking {
        File(files, "models/llm.gguf").apply { parentFile.mkdirs(); writeBytes(model) }
        val src = stage()
        val inst = installer()
        val opened = mutableListOf<String>()
        inst.install(items(src, inst), { opened += it.asset.id; src.open(it.entry.path) })
        assertEquals(listOf("p"), opened)
    }

    @Test fun `the APK's bundle wins, the adb-pushed folder is the fallback, and none means none`() {
        val pushed = stage()
        val apk = object : BundleSource {
            override val name = "apk"
            override fun manifest() = manifestJson(llm, packAsset)
            override fun open(path: String): InputStream = throw UnsupportedOperationException()
        }
        val empty = DirBundleSource(File(root, "nothing"))
        val inst = installer()
        assertSame(apk, inst.choose(apk, pushed))
        assertSame(pushed, inst.choose(empty, pushed))
        assertSame(pushed, inst.choose(null, pushed))
        assertNull(inst.choose(empty, null))
    }

    @Test fun `the adb-pushed copy is deleted once installed`() = runBlocking {
        val src = stage()
        val inst = installer()
        inst.install(items(src, inst), { src.open(it.entry.path) })
        src.discard()
        assertFalse(bundle.exists())
    }

    @Test fun `not enough free space stops before the first byte`() = runBlocking {
        val src = stage()
        val inst = installer(free = model.size.toLong())
        try {
            inst.install(items(src, inst), { src.open(it.entry.path) })
            fail("installed without the space")
        } catch (e: BundleInstaller.NotEnoughSpace) {
            assertEquals(model.size + pack.size + BundleInstaller.SPACE_MARGIN_BYTES, e.neededBytes)
        }
        assertFalse(File(files, "models/llm.gguf").exists())
        assertFalse(File(files, "models/llm.gguf.part").exists())
    }

    @Test fun `launch installs on a fresh phone, after Purge and for a new bundle, not otherwise`() = runBlocking {
        val src = stage()
        val inst = installer()
        val print = BundleManifest.fingerprint(src.manifest()!!)
        assertTrue(inst.needsInstall(print))
        inst.install(items(src, inst), { src.open(it.entry.path) })
        assertTrue("installed but not marked", inst.needsInstall(print))
        inst.markInstalled(print)
        assertFalse(inst.needsInstall(print))
        assertFalse("an APK without a bundle keeps the adb-pushed install", inst.needsInstall(null))
        assertTrue("a newer APK's bundle", inst.needsInstall("0".repeat(64)))
        File(files, "models/llm.gguf").delete()
        assertTrue("the answer model is gone", inst.needsInstall(print))
    }
}
