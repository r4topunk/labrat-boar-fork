package team.sopa.boar.engine.models

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

/** Status, verification and the integrity records of a file on disk (small fake payloads, never a real model). */
class AssetStoreTest {
    private val payload = Random(7).nextBytes(3_000_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private lateinit var dir: File

    @Before fun start() { dir = Files.createTempDirectory("boar-assets").toFile() }

    @After fun stop() { dir.deleteRecursively() }

    private fun asset(id: String = "m", required: Boolean = false, size: Long = payload.size.toLong(), digest: String = sha) = CatalogAsset(
        id = id, kind = AssetKind.LLM, label = "Test model", filename = "models/$id.gguf", sizeBytes = size, sha256 = digest,
        license = "MIT", description = "", required = required,
    )

    private fun store(vararg a: CatalogAsset) = AssetStore(dir, Catalog(50_000_000_000, 12L shl 30, a.toList()))

    private fun AssetStore.write(a: CatalogAsset, bytes: ByteArray = payload) = fileOf(a).apply { parentFile.mkdirs(); writeBytes(bytes) }

    @Test fun `verifies a file and records it`() = runBlocking {
        val a = asset()
        val s = store(a)
        s.write(a)
        assertNull(s.status(a).checksumOk)
        assertTrue(s.verify(a))
        assertEquals(true, s.status(a).checksumOk)
        assertTrue(File(dir, "integrity.json").readText().contains("models/m.gguf"))
    }

    @Test fun `a file that fails its checksum is not remembered`() = runBlocking {
        val a = asset(digest = "0".repeat(64))
        val s = store(a)
        s.write(a)
        assertFalse(s.verify(a))
        assertNull(s.status(a).checksumOk)
    }

    @Test fun `a smaller file is a partial copy, a larger one is deleted`() {
        val a = asset()
        val s = store(a)
        s.write(a, payload.copyOf(1_000_000))
        assertEquals(1_000_000L, s.status(a).partialBytes)
        assertFalse(s.status(a).present)
        s.write(a, payload + byteArrayOf(1))
        assertFalse(s.status(a).present)
        assertFalse(s.fileOf(a).exists())
    }

    @Test fun `setup is complete when every required asset is on disk`() {
        val llm = asset("llm", required = true)
        val other = asset("other")
        val s = store(llm, other)
        assertFalse(s.requiredPresent())
        s.write(llm)
        assertTrue(s.requiredPresent())
    }

    @Test fun `delete removes the file and its record`() = runBlocking {
        val a = asset()
        val s = store(a)
        s.write(a)
        s.verify(a)
        s.delete(a)
        assertFalse(s.fileOf(a).exists())
        assertFalse(File(dir, "integrity.json").readText().contains("models/m.gguf"))
    }

    @Test fun `reads integrity records written by an earlier build (mtime in seconds)`() {
        val a = asset()
        val s = store(a)
        val f = s.write(a)
        File(dir, "integrity.json").writeText("""{"models/m.gguf":{"size":${payload.size},"mtime":${f.lastModified() / 1000.0},"sha256":"$sha"}}""")
        assertEquals(true, s.status(a).checksumOk)
        f.setLastModified(f.lastModified() + 5_000)
        assertNull(s.status(a).checksumOk)
    }
}
