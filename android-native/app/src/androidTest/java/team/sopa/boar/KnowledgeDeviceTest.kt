package team.sopa.boar

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.documents.DocumentsViewModel
import team.sopa.boar.engine.models.AssetKind
import java.io.File

/**
 * The Knowledge screen (drawer → Knowledge): the built-in libraries by theme, as setup names them (installed ones with
 * their ⋮ menu, missing ones with no Download), then "Your documents". Files are staged in the cache folder and imported
 * through [DocumentsViewModel.import] (the system file picker can't be driven), then the screen is used like a person
 * would: the row's switch, its ⋮ menu (Export, Remove) and Remove's confirmation. Needs the embedding model.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = ctx.applicationContext as Application
    private val services get() = BoarServices.get(ctx)
    private val opened = mutableListOf<ActivityScenario<*>>()
    private val dir = File(ctx.cacheDir, "qa-docs").apply { deleteRecursively(); mkdirs() }
    private val stamp = System.currentTimeMillis() % 1_000_000
    private val collection = "QA Docs $stamp"
    private val more get() = hasContentDescription(tr("clean.library.more"))

    @Before fun needsATestInstall() {
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        assumeTrue("needs a model and the embedding model",
            services.activeModel() != null && services.installed(AssetKind.EMBEDDING).isNotEmpty())
    }

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        val kb = services.knowledgeBase
        kb.collections().filter { it.name.startsWith("QA Docs") }.forEach { runCatching { kb.deleteCollection(it.id) } }
        dir.deleteRecursively()
        File(ctx.cacheDir, "exports").deleteRecursively()
    }

    private fun openKnowledge() {
        launchApp(opened)
        compose.openFromDrawer(tr("nav.knowledge"))
        compose.waitForText(tr("clean.library.yourDocs"))
    }

    private fun file(name: String, text: String): Uri = Uri.fromFile(File(dir, name).apply { writeText(text) })

    /** Imports through a view model of the screen's kind and waits for it to finish. */
    private fun import(vm: DocumentsViewModel, name: String, vararg uris: Uri) {
        vm.import(uris.toList(), name)
        waitSlowly(180_000, "import of $name", stepMs = 250) { vm.state.value.stage == null }
    }

    @Test fun theInstalledLibrariesAreListedByThemeAndMissingOnesHaveNoDownload() {
        val installed = services.catalog.models.filter { it.kind == AssetKind.CORPUS && it.format == "sqlite-pack" && services.fileOf(it).isFile }
        assumeTrue("no knowledge pack installed on this phone", installed.isNotEmpty())
        openKnowledge()
        compose.scrollTo(hasText(tr("design.knowledge.summary")))
        assertTrue(compose.shows(tr("design.knowledge.summary")))
        // Each installed pack by its setup name, with its ⋮ menu (Remove from phone).
        val names = mapOf("boar-medlineplus-en" to "clean.onboarding.pack.medlinePlus.name", "boar-factbook" to "clean.onboarding.pack.factbook.name")
        val shown = installed.mapNotNull { a -> names[a.id]?.let(::tr) }
        assumeTrue("neither MedlinePlus nor the Factbook is installed", shown.isNotEmpty())
        for (name in shown) {
            compose.scrollTo(hasText(name))
            assertTrue("installed pack '$name' not listed", compose.shows(name))
        }
        // The Health section carries the clinical caveat once.
        if ("boar-medlineplus-en" in installed.map { it.id }) {
            compose.scrollTo(hasText(tr("clean.onboarding.caveat")))
            assertTrue(compose.shows(tr("clean.onboarding.caveat")))
        }
        // A library that isn't on the phone has no Download button: LabRat can't download anything.
        val wikivoyage = tr("clean.onboarding.pack.wikivoyage.name")
        if (!File(ctx.filesDir, "corpus/boar-wikivoyage-en.sqlite").isFile) {
            compose.scrollTo(hasText(wikivoyage))
            assertFalse(compose.exists(hasContentDescription("Download")))
        }

        // Remove from phone asks first; Cancel keeps the file.
        val pack = installed.first { names.containsKey(it.id) }
        val name = tr(names.getValue(pack.id))
        val size = services.fileOf(pack).length()
        compose.scrollTo(hasText(name))
        compose.pressNear(name, more)
        compose.waitForText(tr("design.knowledge.remove"))
        compose.press(tr("design.knowledge.remove"))
        compose.waitForText(tr("catalogItemCard.removeCorpusTitle"))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("catalogItemCard.removeCorpusTitle"))
        assertTrue("Cancel deleted the pack", services.fileOf(pack).isFile)
        assertEquals(size, services.fileOf(pack).length())

        // Back to the chat.
        compose.pressIcon(tr("common.back"))
        compose.waitForChat()
    }

    @Test fun importedDocumentsShowToggleExportAndRemoveWithConfirmation() {
        val kb = services.knowledgeBase
        val vm = DocumentsViewModel(app)
        import(vm, collection,
            file("notes.md", "# Field notes\nThe QA marker for notes is zebra-quartz-$stamp."),
            file("plan.txt", "Plan for the week: test the knowledge screen on the phone, then export the collection."),
            // 2,600 characters: two chunks (2,000 + overlap).
            file("long.md", "# Long file\n" + "The long file repeats one sentence so that it needs more than one chunk. ".repeat(36)))
        assertNull("import failed: ${vm.state.value.error}", vm.state.value.error)
        val col = kb.collections().firstOrNull { it.name == collection }
        assertNotNull("collection not created", col)
        col!!
        assertEquals(3L, col.docCount)
        assertTrue("at least one passage per file", col.chunkCount >= 3)
        assertTrue(col.active)

        // The screen: the collection row with its count.
        openKnowledge()
        compose.scrollTo(hasText(collection))
        assertTrue(compose.shows(tr("personalDocumentsManager.docCount", "count" to 3L)))

        // The row's switch (named after the collection) turns it off and on in the knowledge base.
        val switch = hasContentDescription(collection) and isToggleable()
        assertEquals(ToggleableState.On, compose.onNode(switch).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        compose.onNode(switch).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.waitUntil(5_000) { !kb.collections().first { it.id == col.id }.active }
        compose.onNode(switch).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.waitUntil(5_000) { kb.collections().first { it.id == col.id }.active }

        // Export: the menu offers it; the file the share sheet gets is built through the view model (the chooser would
        // leave BOAR).
        compose.pressNear(collection, more)
        compose.waitForText(tr("personalDocumentsManager.exportButton"))
        assertTrue(compose.shows(tr("common.remove")))
        androidx.test.espresso.Espresso.pressBackUnconditionally() // closes the menu
        compose.waitForTextGone(tr("personalDocumentsManager.exportButton"))
        val chooser = runBlocking { vm.exportIntent(kb.collections().first { it.id == col.id }) }
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val exported = File(ctx.cacheDir, "exports").walkTopDown().filter { it.isFile && it.extension == "json" }.maxByOrNull { it.lastModified() }
        assertNotNull("no export file written", exported)
        assertEquals("qa-docs-$stamp.json", exported!!.name)
        val docs = JSONArray(exported.readText())
        assertEquals("one entry per passage", col.chunkCount.toInt(), docs.length())
        assertTrue(exported.readText().contains("zebra-quartz-$stamp"))

        // Remove: the menu, then a confirmation naming the collection; Cancel keeps it, Remove deletes it.
        compose.pressNear(collection, more)
        compose.press(tr("common.remove"))
        compose.waitForText(tr("personalDocumentsManager.removeConfirmTitle"))
        assertTrue("the message names the collection", compose.shows(collection, substring = true))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("personalDocumentsManager.removeConfirmTitle"))
        assertTrue("cancel removed the collection", kb.collections().any { it.id == col.id })
        compose.pressNear(collection, more)
        compose.press(tr("common.remove"))
        compose.waitForText(tr("personalDocumentsManager.removeConfirmTitle"))
        compose.pressInDialog(tr("common.remove"))
        compose.waitUntil(30_000) { kb.collections().none { it.id == col.id } }
        compose.waitForTextGone(collection)
        assertTrue("its passages go with the collection", kb.collectionDocs(col.id).isEmpty())
    }

    /** After an import the screen offers "Ask about your files": Sources = My files on, and a new chat. */
    @Test fun afterAnImportAskAboutYourFilesTurnsOnMyFilesAndOpensANewChat() {
        val prefs = ctx.getSharedPreferences("answer_options", android.content.Context.MODE_PRIVATE)
        val wasOwn = prefs.getBoolean("sourcesOwnOnly", false)
        prefs.edit().putBoolean("sourcesOwnOnly", false).commit()
        try {
            val scenario = launchApp(opened)
            compose.openFromDrawer(tr("nav.knowledge"))
            compose.waitForText(tr("clean.library.yourDocs"))
            // The screen's own view model (the system picker can't be driven): the activity's, as viewModel() gets it.
            var vm: DocumentsViewModel? = null
            scenario.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[DocumentsViewModel::class.java] }
            import(vm!!, collection, file("invite.md", "# Invite\nThe QA invite marker is amber-otter-$stamp."))
            assertNull("import failed: ${vm!!.state.value.error}", vm!!.state.value.error)
            val ask = tr("design.documents.askFiles")
            compose.waitForText(ask, 10_000)
            assertTrue(compose.shows(tr("design.documents.imported", "name" to collection)))
            compose.scrollTo(hasText(ask))
            compose.press(ask)
            compose.waitForChat()
            assertTrue("Sources = My files is on", prefs.getBoolean("sourcesOwnOnly", false))
            assertTrue("a new, empty chat", compose.shows(tr("design.chat.emptyTitle")))
        } finally {
            prefs.edit().putBoolean("sourcesOwnOnly", wasOwn).commit()
        }
    }

    @Test fun withNoDocumentsTheScreenSaysSoAndOffersAddDocuments() {
        assumeTrue("this install has documents", services.knowledgeBase.collections().isEmpty())
        openKnowledge()
        compose.scrollTo(hasText(tr("clean.library.emptyTitle")))
        assertTrue(compose.shows(tr("clean.library.emptyBody")))
        compose.scrollTo(hasText(tr("clean.library.addDocs")))
        assertTrue("Add documents (opens the system picker; not pressed)", compose.exists(hasText(tr("clean.library.addDocs")) and hasClickAction()))
        assertTrue(compose.shows(tr("clean.library.docsHint")))
    }

    @Test fun anUnreadableFileGivesAPlainErrorAndLeavesNoCollection() {
        val kb = services.knowledgeBase
        val before = kb.collections().size
        val vm = DocumentsViewModel(app)
        import(vm, collection, Uri.fromFile(File(dir, "does-not-exist.md")))
        assertEquals(tr("native.documents.failed"), vm.state.value.error)
        assertEquals("a failed import leaves nothing behind", before, kb.collections().size)
        // A file BOAR can't read (by its extension) is refused with its name.
        import(vm, collection, file("page.html", "<html>hi</html>"))
        assertTrue("unsupported type: ${vm.state.value.error}", vm.state.value.error?.contains("page.html") == true)
        assertEquals(before, kb.collections().size)
        // Nothing picked: no-op, no state change.
        vm.import(emptyList(), collection)
        assertNull(vm.state.value.stage)
    }

    @Test fun aJsonDocumentListAndACsvImportAsOneCollection() {
        val kb = services.knowledgeBase
        val vm = DocumentsViewModel(app)
        import(vm, collection,
            file("docs.json", """[{"title":"First","body":"alpha beta"},{"title":"Second","source":"s.txt","body":"gamma delta"}]"""),
            file("table.csv", "name,qty\nbolt,4\nnut,9\n"))
        assertNull(vm.state.value.error)
        val col = kb.collections().first { it.name == collection }
        assertEquals("two JSON documents + one CSV", 3L, col.docCount)
        assertEquals(3L, col.chunkCount)
        vm.setActive(col.id, false)
        assertFalse(kb.collections().first { it.id == col.id }.active)
        vm.setActive(col.id, true)
        vm.remove(col.id)
        waitSlowly(15_000, "collection removed", stepMs = 200) { kb.collections().none { it.id == col.id } }
    }
}
