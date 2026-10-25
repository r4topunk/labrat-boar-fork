package team.sopa.boar.engine.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.EngineTweaks
import team.sopa.boar.engine.KvType
import java.nio.file.Files

class AppSettingsTest {
    /** A settings.json as an earlier build wrote it, with keys this build doesn't use. */
    private val earlierBuild = """{"activeModelId":{"llm":"qwen3-4b-instruct-2507-q4km","embedding":"bge-small-en-v1.5-q8"},"personalityId":"detailed","maxTokens":1024,
        |"themeId":"moonlight","fontScale":"large","deepResearchMode":true,"adaptiveRoutingEnabled":true,"routingPreset":"research","modelRoleAssignments":{"fast":"x"},"autoSummarize":true,"historyTurnThreshold":8,"placeTileNames":{"a":"b"},"installId":"abc-123","largeModelConfirmedIds":["x"]}""".trimMargin()

    private fun file(text: String?) = Files.createTempDirectory("boar-settings").toFile().resolve("settings.json").also { f -> text?.let { f.writeText(it) } }

    @Test fun `reads an earlier build's settings, with its stored style, length and Deep Research ignored`() {
        val s = AppSettings(file(earlierBuild))
        assertEquals("qwen3-4b-instruct-2507-q4km", s.activeModelId("llm"))
        // Stored "detailed", "maxTokens": 1024 and Deep Research on: the app answers in Succinct at its length anyway.
        assertEquals("succinct", s.personalityId)
        assertEquals(AppSettings.AUTO_MAX_TOKENS, s.maxTokens)
        assertEquals("moonlight", s.themeId)
        assertEquals("large", s.fontScale)
        assertEquals(EngineTweaks(), s.engineTweaks)
    }

    @Test fun `a style, length, Deep Research or routing option stored by an earlier build is rewritten to Succinct and dropped`() {
        val f = file(earlierBuild)
        AppSettings(f).retireRemovedOptions()
        val again = AppSettings(f)
        assertEquals("\"succinct\"", again.raw()["personalityId"].toString())
        assertFalse("maxTokens" in again.raw())
        assertFalse("deepResearchMode" in again.raw())
        for (k in listOf("adaptiveRoutingEnabled", "routingPreset", "modelRoleAssignments", "autoSummarize", "historyTurnThreshold")) assertFalse(k, k in again.raw())
        // Everything else is kept.
        assertEquals("moonlight", again.themeId)
        assertEquals("abc-123", again.raw()["installId"].toString().trim('"'))
        // Already clean: nothing is written.
        val stamp = f.lastModified()
        f.setLastModified(stamp - 10_000)
        again.retireRemovedOptions()
        assertEquals(stamp - 10_000, f.lastModified())
    }

    @Test fun `keeps keys it doesn't know when it writes`() {
        val f = file(earlierBuild)
        val s = AppSettings(f)
        s.engineTweaks = EngineTweaks(threads = 6, kvCache = KvType.Q8_0, stageTimeoutSeconds = 0)
        s.setActiveModelId("llm", "qwen2.5-1.5b-instruct-q4km")
        val again = AppSettings(f)
        assertEquals(EngineTweaks(threads = 6, kvCache = KvType.Q8_0, stageTimeoutSeconds = 0), again.engineTweaks)
        assertEquals("qwen2.5-1.5b-instruct-q4km", again.activeModelId("llm"))
        assertEquals("bge-small-en-v1.5-q8", again.activeModelId("embedding"))
        assertEquals("abc-123", again.raw()["installId"].toString().trim('"'))
        assertTrue("placeTileNames" in again.raw() && "largeModelConfirmedIds" in again.raw())
        s.engineTweaks = EngineTweaks()
        assertFalse("engineTweaks" in AppSettings(f).raw())
    }

    @Test fun `reset settings clears preferences, keeps Succinct and models`() {
        val f = file(earlierBuild)
        val s = AppSettings(f)
        s.setActiveModelId("llm", "qwen3-4b")
        s.themeId = "campfire"
        s.fontScale = "large"; s.showLiveStats = false
        s.engineTweaks = EngineTweaks(threads = 2)
        s.resetPreferences()
        val again = AppSettings(f)
        assertEquals("qwen3-4b", again.activeModelId("llm"))
        assertNull(again.themeId)
        assertEquals("succinct", again.personalityId)
        assertEquals(AppSettings.AUTO_MAX_TOKENS, again.maxTokens)
        assertFalse("personalityId" in again.raw() || "maxTokens" in again.raw() || "deepResearchMode" in again.raw())
        assertTrue(again.showLiveStats)
        assertEquals("standard", again.fontScale)
        assertEquals(EngineTweaks(), again.engineTweaks)
    }

    @Test fun `missing or broken file means defaults`() {
        for (s in listOf(AppSettings(file(null)), AppSettings(file("{not json")))) {
            assertNull(s.activeModelId("llm"))
            assertEquals("succinct", s.personalityId)
            assertEquals(AppSettings.AUTO_MAX_TOKENS, s.maxTokens)
            assertEquals("standard", s.fontScale)
        }
    }

    @Test fun `Auto length fits the context the model was opened with`() {
        assertEquals(2048, AppSettings.fitToContext(2048, null))
        assertEquals(2048, AppSettings.fitToContext(2048, 4096))
        assertEquals(1572, AppSettings.fitToContext(2048, 3072))
        assertEquals(548, AppSettings.fitToContext(2048, 2048))
        assertEquals(256, AppSettings.fitToContext(1024, 1024))
        val s = AppSettings(file(null))
        assertEquals(512, s.answerTokens(null))
        assertEquals(512, s.answerTokens(4096))
        assertEquals(512, s.answerTokens(2048))
        assertEquals(256, s.answerTokens(1024))
    }

    @Test fun `no stored style gets the Succinct cap`() {
        val s = AppSettings(file(null))
        assertEquals(512, s.maxTokens)
    }
}
