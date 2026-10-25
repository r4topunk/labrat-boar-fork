package team.sopa.boar

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import team.sopa.boar.ui.about.AboutCredits

/**
 * About, opened from Settings → About BOAR: the version and build line (and the seven taps on it that switch developer
 * options), the thread, the credits expanding, the addresses copied by their copy button (never opened), and Back to
 * Settings.
 */
@RunWith(AndroidJUnit4::class)
class AboutDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = mutableListOf<ActivityScenario<*>>()
    private var wasDev = false

    @Before fun needsTheChat() {
        assumeTrue("needs the chat screen (a model)", BoarServices.get(ctx).activeModel() != null)
        wasDev = DevOptions.enabled(ctx)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, false) }
    }

    @After fun closeScreens() {
        opened.forEach { runCatching { it.close() } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, wasDev) }
    }

    private fun openAbout(): ActivityScenario<MainActivity> {
        val scenario = launchApp(opened)
        compose.openFromSettings(tr("aboutScreen.title"))
        compose.waitForText(tr("native.about.bio"))
        return scenario
    }

    /** BuildConfig constants are inlined into this test at compile time; read them from the installed app instead. */
    private fun buildConfig(name: String): String = Class.forName("team.sopa.boar.BuildConfig").getField(name).get(null) as String

    private fun versionLine(dev: Boolean): String {
        val gitSha = buildConfig("GIT_SHA")
        val info = listOfNotNull(
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—",
            gitSha.ifEmpty { null },
            buildConfig("BUILD_DATE"),
        ).joinToString(" · ")
        val debug = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val badge = tr("aboutScreen.versionBadge", "version" to info, "build" to tr(if (debug) "aboutScreen.buildDevelopment" else "aboutScreen.buildRelease"))
        return if (dev) "$badge • llama.cpp ·" else badge
    }

    @Test fun theVersionLineNamesTheCommitAndTheThreadIsShown() {
        openAbout()
        val line = versionLine(dev = false)
        compose.scrollTo(hasContentDescription(line))
        assertTrue("the version line '$line'", compose.exists(hasContentDescription(line)))
        val gitSha = buildConfig("GIT_SHA")
        assertTrue("the build must name the commit it came from (BuildConfig.GIT_SHA is empty)", gitSha.isNotEmpty())
        assertFalse("the engine is a developer detail", compose.shows("llama.cpp ·", substring = true))
        for (key in listOf("native.about.tagline", "native.about.bio", "native.about.post.why", "native.about.post.offline")) {
            compose.scrollTo(hasText(tr(key)))
            assertTrue("missing $key", compose.shows(tr(key)))
        }
    }

    @Test fun theCreditsExpandAndCollapse() {
        openAbout()
        val libs = AboutCredits.libraries
        val beyond = libs.last().first
        val all = tr("native.about.creditsAll", "n" to 98765).substringBefore("98765")
        compose.scrollTo(hasText(all, substring = true))
        assertTrue(compose.shows(libs[0].first))
        assertFalse("'$beyond' should be hidden until the list is expanded", compose.shows(beyond))
        compose.press(all, substring = true)
        compose.waitForText(beyond)
        assertTrue("licenses are listed with their names", compose.shows(libs[0].second))
        val model = BoarServices.get(ctx).activeModel()!!
        compose.scrollTo(hasText(model.label))
        assertTrue("the installed model is credited", compose.shows(model.label))
        compose.scrollTo(hasText(tr("native.about.creditsLess")))
        compose.press(tr("native.about.creditsLess"))
        compose.waitForTextGone(beyond)
    }

    @Test fun theLicenseTextsOpenFromTheCredits() {
        openAbout()
        val row = tr("native.about.licenseTexts")
        compose.scrollTo(hasText(row))
        compose.press(row)
        compose.waitForText("Permission is hereby granted", substring = true)
        assertTrue("BoarLite's copyright line", compose.shows("BOAR contributors", substring = true))
        compose.pressInDialog(tr("common.close"))
        compose.waitForTextGone("Permission is hereby granted", substring = true)
    }

    @Test fun eachAddressHasACopyButtonThatCopiesIt() {
        openAbout()
        val links = listOf("github.com/rferrari/boar-app", "boarapp.com", "x.com/boar_app", "farcaster.xyz/boarapp", "huggingface.co/datasets/r4topunk/boar-packs")
        for (l in links) { compose.scrollTo(hasText(l)); assertTrue("missing address $l", compose.shows(l)) }
        val copy = hasContentDescription(tr("clean.settings.about.copyAddress"))
        compose.scrollTo(hasText("boarapp.com"))
        compose.pressNear("boarapp.com", copy)
        assertEquals("boarapp.com", clipboard())
        // A team member reads as a name over the handle and copies as the X address.
        compose.scrollTo(hasText("@r4topunk"))
        compose.pressNear("@r4topunk", copy)
        assertEquals("x.com/r4topunk", clipboard())
    }

    @Test fun sevenTapsOnTheVersionSwitchDeveloperOptionsOnAndOff() {
        openAbout()
        val off = versionLine(dev = false)
        compose.scrollTo(hasContentDescription(off))
        repeat(7) { compose.onNode(hasContentDescription(off) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) }
        compose.waitForIdle()
        assertTrue("seven taps did not switch developer options on", DevOptions.enabled(ctx))
        // The engine shows in the version line once they're on.
        val on = compose.onAllNodes(hasContentDescription(off, substring = true) and hasClickAction()).fetchSemanticsNodes()
            .single().config.getOrNull(SemanticsProperties.ContentDescription)!!.single()
        assertTrue("version line with developer options: '$on'", on.startsWith(versionLine(dev = true)))
        repeat(7) { compose.onNode(hasContentDescription(on) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) }
        compose.waitForIdle()
        assertFalse(DevOptions.enabled(ctx))
        // Six taps do nothing.
        repeat(6) { compose.onNode(hasContentDescription(off) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) }
        assertFalse(DevOptions.enabled(ctx))
    }

    @Test fun backReturnsToSettingsAndThenToTheChat() {
        val scenario = openAbout()
        scenario.pressBack()
        compose.waitForText(tr("modelSetupScreen.header.title"))
        compose.waitForTextGone(tr("native.about.bio"))
        compose.pressIcon(tr("common.back"))
        compose.waitForChat()
    }

    private fun clipboard(): String? {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
    }
}
