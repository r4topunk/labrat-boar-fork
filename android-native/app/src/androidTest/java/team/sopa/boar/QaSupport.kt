package team.sopa.boar

import android.app.Activity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import team.sopa.boar.ui.I18n

/**
 * Helpers shared by the device tests that drive the real MainActivity (drawer and screens) with semantic clicks: each
 * button's own click action, never injected touches, so the tests also run where MIUI blocks injected input.
 */

internal val qaCtx get() = InstrumentationRegistry.getInstrumentation().targetContext

internal fun tr(key: String, vararg args: Pair<String, Any?>) = I18n.t(qaCtx, key, *args)

/** An instrumentation argument (-e name value), e.g. -e qaPurge true for the tests that are opt-in. */
internal fun qaArg(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

/**
 * The first question asks for the notification permission (issue #91): granted up front, so the system dialog never
 * covers the chat while a test runs. Only the devtest install's own permission.
 */
internal fun grantNotifications() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    runCatching {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(qaCtx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Launches the app on its first screen and keeps the scenario for [opened]'s cleanup. */
internal fun launchApp(opened: MutableList<ActivityScenario<*>>): ActivityScenario<MainActivity> {
    grantNotifications()
    return ActivityScenario.launch(MainActivity::class.java).also { opened += it }
}

/** Back, as the system button sends it (BackHandler / PredictiveBackHandler). */
internal fun <A : Activity> ActivityScenario<A>.pressBack() = onActivity { (it as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed() }

/** Waits until the chat screen is up (its message field exists). */
internal fun ComposeTestRule.waitForChat(timeoutMs: Long = 60_000) {
    try { waitUntil(timeoutMs) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() && exists(hasContentDescription(tr("chatHeader.openDrawer"))) } }
    catch (e: Throwable) { throw AssertionError("the chat screen did not appear within ${timeoutMs / 1000} s", e) }
}

internal fun ComposeTestRule.exists(matcher: SemanticsMatcher): Boolean = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

/** On screen, not only composed: the closed drawer keeps its content composed, off the left edge. */
internal fun ComposeTestRule.displayed(matcher: SemanticsMatcher): Boolean =
    onAllNodes(matcher).fetchSemanticsNodes().indices.any { i -> runCatching { onAllNodes(matcher)[i].assertIsDisplayed() }.isSuccess }

/** The drawer is open: its New chat button is on screen. */
internal fun ComposeTestRule.drawerOpen(): Boolean = displayed(hasText(tr("clean.drawer.newChat")) and hasClickAction())

internal fun ComposeTestRule.shows(text: String, substring: Boolean = false): Boolean =
    onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

internal fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, what: String, timeoutMs: Long = 30_000) {
    try { waitUntil(timeoutMs) { exists(matcher) } }
    catch (e: Throwable) { throw AssertionError("$what did not appear within ${timeoutMs / 1000} s", e) }
}

internal fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 30_000, substring: Boolean = false) {
    try { waitUntil(timeoutMs) { shows(text, substring) } }
    catch (e: Throwable) { throw AssertionError("'$text' did not appear within ${timeoutMs / 1000} s", e) }
}

internal fun ComposeTestRule.waitForTextGone(text: String, timeoutMs: Long = 30_000, substring: Boolean = false) {
    try { waitUntil(timeoutMs) { !shows(text, substring) } }
    catch (e: Throwable) { throw AssertionError("'$text' is still on screen after ${timeoutMs / 1000} s", e) }
}

/** Presses the clickable node matching [matcher] (the first one when several match). */
internal fun ComposeTestRule.pressNode(matcher: SemanticsMatcher) {
    onAllNodes(matcher and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** Presses the clickable node showing [text] (a heading with the same text next to a button is skipped). */
internal fun ComposeTestRule.press(text: String, substring: Boolean = false) = pressNode(hasText(text, substring = substring))

/** Presses the button showing [text] inside the open dialog (its label can repeat the row that opened it). */
internal fun ComposeTestRule.pressInDialog(text: String) = pressNode(hasText(text) and hasAnyAncestor(isDialog()))

/** Presses the clickable node with this content description (icon buttons). */
internal fun ComposeTestRule.pressIcon(description: String) = pressNode(hasContentDescription(description))

/** Scrolls the screen's scrolling container until [matcher] is composed and visible. */
internal fun ComposeTestRule.scrollTo(matcher: SemanticsMatcher) {
    onAllNodes(hasScrollAction())[0].performScrollToNode(matcher)
    waitForIdle()
}

/** Scrolls to and presses the clickable row showing [text] on a scrolling screen (Settings). */
internal fun ComposeTestRule.scrollAndPress(text: String) {
    val m = hasText(text) and hasClickAction()
    scrollTo(m)
    pressNode(m)
}

/** The menu button (☰) on the chat screen: the drawer opens. */
internal fun ComposeTestRule.openDrawer() {
    waitForChat()
    pressIcon(tr("chatHeader.openDrawer"))
    try { waitUntil(10_000) { drawerOpen() } } catch (e: Throwable) { throw AssertionError("the drawer did not open", e) }
}

/** The drawer, then its entry [label] (Knowledge, Settings, Evaluation). */
internal fun ComposeTestRule.openFromDrawer(label: String) {
    openDrawer()
    waitFor(hasText(label) and hasClickAction(), "the drawer entry '$label'", 10_000)
    pressNode(hasText(label))
}

/** Drawer → Settings, then the Settings row [row] (Model, Packs and documents, About BOAR). */
internal fun ComposeTestRule.openFromSettings(row: String) {
    openFromDrawer(tr("chatScreen.drawerItems.settings"))
    waitFor(hasScrollAction(), "the Settings list")
    scrollAndPress(row)
}

/**
 * Presses the node matching [action] whose vertical centre is nearest to the row showing [label]: the screens repeat
 * the same icon button (⋮) on every row, and only the row's title tells them apart.
 */
internal fun ComposeTestRule.pressNear(label: String, action: SemanticsMatcher) {
    val y = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.center.y
    val match = action and hasClickAction()
    val nodes = onAllNodes(match).fetchSemanticsNodes()
    val idx = nodes.indices.minByOrNull { kotlin.math.abs(nodes[it].boundsInRoot.center.y - y) } ?: throw AssertionError("no action near '$label'")
    onAllNodes(match)[idx].performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** Polls [condition] every [stepMs] without keeping Compose busy (long waits: imports). */
internal fun waitSlowly(timeoutMs: Long, what: String, stepMs: Long = 500, condition: () -> Boolean) {
    val end = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        if (android.os.SystemClock.uptimeMillis() > end) throw AssertionError("$what: not within ${timeoutMs / 1000} s")
        Thread.sleep(stepMs)
    }
}
