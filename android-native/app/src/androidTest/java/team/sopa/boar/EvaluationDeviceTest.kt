package team.sopa.boar

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.DevOptions
import team.sopa.boar.ui.I18n
import java.io.File

/**
 * Evaluation, a developer tool (drawer entry only with developer options on), opened in the real MainActivity: the
 * model list and its checkboxes, a run that is started and stopped after its first answer, and Back being ignored while
 * it runs. One answer on the installed model.
 */
@RunWith(AndroidJUnit4::class)
class EvaluationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun t(key: String, vararg args: Pair<String, Any?>) = I18n.t(ctx, key, *args)
    private val opened = mutableListOf<ActivityScenario<*>>()
    private val startedAt = System.currentTimeMillis()
    private var wasDev = false

    @Before fun needsATestInstall() {
        val services = BoarServices.get(ctx)
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        assumeTrue("needs an active model", services.activeModel() != null)
        wasDev = DevOptions.enabled(ctx)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, true) }
    }

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, wasDev) }
        File(ctx.filesDir, "eval").listFiles().orEmpty().filter { it.isFile && it.lastModified() >= startedAt - 1000 }.forEach { it.delete() }
        File(ctx.cacheDir, "exports").deleteRecursively()
    }

    private fun openEvaluation(): ActivityScenario<MainActivity> {
        val scenario = launchApp(opened)
        compose.openFromDrawer(t("evaluation.title"))
        compose.waitForText(t("evaluation.run"))
        return scenario
    }

    private val modelLabel get() = BoarServices.get(ctx).activeModel()!!.label

    @Test fun theModelListTogglesAndRunNeedsAChosenModel() {
        openEvaluation()
        compose.waitForText(modelLabel)
        assertTrue("installed model starts chosen", compose.shows(t("native.eval.modelsTitle", "chosen" to 1, "count" to 1)))
        assertTrue(compose.shows(t("native.eval.keepScreenOn")))
        // Unchoose it: Run is disabled and the title counts 0 of 1.
        compose.press(modelLabel)
        compose.waitForText(t("native.eval.modelsTitle", "chosen" to 0, "count" to 1))
        compose.onNode(hasText(t("evaluation.run"))).assertIsNotEnabled()
        // Choose it again.
        compose.press(modelLabel)
        compose.waitForText(t("native.eval.modelsTitle", "chosen" to 1, "count" to 1))
        // Collapse and expand the model list (the section heading is the toggle).
        compose.press(t("native.eval.modelsTitle", "chosen" to 1, "count" to 1))
        compose.waitForTextGone(modelLabel)
        compose.press(t("native.eval.modelsTitle", "chosen" to 1, "count" to 1))
        compose.waitForText(modelLabel)
        // Done leaves for the chat.
        compose.press(t("common.done"))
        compose.waitForChat()
    }

    @Test fun aRunStopsAfterItsFirstAnswerAndBackIsIgnoredWhileItRuns() {
        val scenario = openEvaluation()
        compose.waitForText(modelLabel)
        compose.press(t("evaluation.run"))

        // Running: Stop is offered, Done is hidden, and the model list closes.
        compose.waitForText(t("evaluation.stop"), 30_000)
        assertFalse("Done is shown while running", compose.shows(t("common.done")))
        // Back does nothing while it runs (MainActivity's BackHandler).
        scenario.pressBack()
        compose.waitForIdle()
        assertTrue("Back left the Evaluation screen while a run was going", compose.shows(t("evaluation.stop")) || compose.shows(t("evaluation.stopping")))
        assertTrue("the chat is showing", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())

        // The first answer: the score line goes from "0/N" to "1/N" answers.
        val running = { d: Int -> t("native.eval.score.running", "done" to d, "total" to 7777).substringBefore("7777") }
        compose.waitUntil(360_000) { compose.shows(running(1), substring = true) || compose.shows(running(2), substring = true) || compose.shows(t("evaluation.runFailedTitle"), substring = true) }
        assertFalse("the run failed: the screen shows its error", compose.shows(t("evaluation.runFailedTitle"), substring = true))

        // Stop: "Stopping…" while the current answer is cut, then back to a non-running state.
        compose.press(t("evaluation.stop"))
        assertTrue(compose.shows(t("evaluation.stopping")) || compose.shows(t("evaluation.run")))
        compose.waitForText(t("evaluation.run"), 180_000)
        assertTrue("Done is back once it stopped", compose.shows(t("common.done")))
        assertFalse(compose.shows(t("evaluation.stop")))
        assertTrue("stopped-early note", compose.shows(t("evaluation.stoppedEarly"), substring = true))
        assertTrue("saved-to note", compose.shows("eval/", substring = true))
        assertTrue("export buttons", compose.shows(t("evaluation.exportJsonl")) && compose.shows(t("evaluation.exportCsv")))

        // The saved file has the answers so far, each a JSON row.
        val saved = File(ctx.filesDir, "eval").listFiles().orEmpty().filter { it.extension == "jsonl" && it.lastModified() >= startedAt - 1000 }
        assertEquals("one saved run", 1, saved.size)
        val rows = saved[0].readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        assertTrue("no rows saved", rows.isNotEmpty())
        assertTrue(rows.all { it.has("queryId") && it.has("outcome") })
        android.util.Log.i("EvaluationDeviceTest", "rows saved: ${rows.size}, outcomes: ${rows.map { it.getString("outcome") }}")

        // Back works again now: the Evaluation screen closes to the chat.
        scenario.pressBack()
        compose.waitForChat()
    }
}
