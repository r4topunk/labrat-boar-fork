package team.sopa.boar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.DevOptions
import team.sopa.boar.engine.settings.AppSettings
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import team.sopa.boar.ui.theme.ThemeMode

/** Display settings the app applies live (theme, text size) or by recreating (language). */
interface DisplayPrefs {
    fun setTheme(id: String)
    fun setFontScale(scale: String)
    fun setLanguage(id: String?)
}

/**
 * Settings as a Material grouped list: how BOAR answers, what it knows, chats, display, language, PIN, About, then
 * Start over. The measurement knobs (technical details, answer length, Custom style, memory numbers) live in Developer
 * options, shown only after seven taps on the version in About. The engine's own knobs are no longer here: they keep
 * their saved values (Auto by default) and the engine reads them as before.
 */
@Composable
fun SettingsScreen(services: BoarServices, prefs: DisplayPrefs, onPin: (String) -> Unit, onSetup: () -> Unit, onOpen: (String) -> Unit = {}, onBack: () -> Unit) {
    val c = Boar.colors
    val s = services.settings
    val ctx = LocalContext.current
    var tech by remember { mutableStateOf(s.showLiveStats) }
    // Shared by the style choices and Developer options → Custom style, so either one shows the other's pick.
    var styleId by remember { mutableStateOf(s.personalityId ?: services.personalities.defaultId) }
    val dev = DevOptions.enabled(ctx)
    Column(Modifier.fillMaxSize().background(c.bgTerminal).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = t("native.settings.back"), onClick = onBack), contentAlignment = Alignment.Center) {
                Text("←", style = Boar.type.titleLg, color = c.textPrimary)
            }
            Text(t("modelSetupScreen.header.title"), style = Boar.type.titleLg, color = c.textHeading)
        }
        // Capped like the chat so rows and cards don't span a tablet or a landscape phone.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToneSection(services, styleId) { styleId = it }
            ModelsSection(services, onOpen)
            KnowledgeSection(services, onOpen)
            Section(t("modelSetupScreen.sections.memory")) {
                var titles by remember { mutableStateOf(s.autoGenerateTitles) }
                SwitchRow(t("memorySettings.autoTitlesLabel"), t("memorySettings.autoTitlesValue"), titles) { titles = it; s.autoGenerateTitles = it }
                var summarize by remember { mutableStateOf(s.autoSummarize) }
                SwitchRow(t("memorySettings.autoSummarizeLabel"), t("memorySettings.autoSummarizeValue"), summarize) { summarize = it; s.autoSummarize = it }
                ClearChats(services)
            }
            Section(t("modelSetupScreen.sections.displayTheme")) {
                var theme by remember { mutableStateOf(ThemeMode.normalize(s.themeId)) }
                Choices(t("clean.settings.theme"), ThemeMode.all.map { it to t("clean.settings.theme.$it") }, theme) { theme = it; s.themeId = it; prefs.setTheme(it) }
                var scale by remember { mutableStateOf(s.fontScale) }
                Choices(t("native.settings.textSize"), listOf("compact", "standard", "large").map { it to t("native.settings.$it") }, scale) { scale = it; s.fontScale = it; prefs.setFontScale(it) }
                var haptics by remember { mutableStateOf(s.hapticsEnabled) }
                SwitchRow(t("interfaceSettings.hapticFeedbackLabel"), t("native.interfaceSettings.hapticFeedbackValue"), haptics) { haptics = it; s.hapticsEnabled = it }
            }
            Section(t("modelSetupScreen.sections.language")) {
                var lang by remember { mutableStateOf(s.languageId ?: "system") }
                Choices(null, listOf("system" to t("clean.settings.language.system"), "en" to "English", "pt" to "Português"), lang) {
                    lang = it; val id = it.takeIf { v -> v != "system" }; s.languageId = id; prefs.setLanguage(id)
                }
            }
            SecuritySection(services, onPin)
            if (tech) TelemetrySection(services)
            if (dev) DeveloperSection(services, styleId, { styleId = it }, tech) { tech = it }
            Section(t("clean.settings.section.about")) {
                ActionRow(t("aboutScreen.title"), note = t("clean.settings.aboutNote")) { onOpen("about") }
            }
            RecoverySection(services, prefs, onSetup)
        }
        }
    }
}

/** ModelSetupScreen.tsx's Reasoning Models: what answers now, and the catalog (download, use, remove). */
@Composable
private fun ModelsSection(services: BoarServices, onOpen: (String) -> Unit) {
    Section(t("modelSetupScreen.sections.models")) {
        val llms = services.installed(team.sopa.boar.engine.models.AssetKind.LLM)
        ActionRow(
            t("native.settings.activeModel", "model" to (services.answerModel()?.shortName ?: "—")),
            note = t("modelSetupScreen.installedModels") + ": " + llms.joinToString(", ") { it.shortName }.ifEmpty { "—" },
            accent = false,
        ) { onOpen("models") }
        // The embedding model searches the knowledge packs and documents; it isn't a choice, so not in the catalog.
        val emb = services.catalog.models.firstOrNull { it.kind == team.sopa.boar.engine.models.AssetKind.EMBEDDING }
        if (emb != null) Info(t(if (services.fileOf(emb).isFile) "native.settings.embeddingOk" else "native.settings.embeddingMissing", "model" to emb.shortName))
        ActionRow(t("native.settings.openCatalog")) { onOpen("models") }
        var free by remember { mutableStateOf(services.settings.freeMemoryInBackground) }
        SwitchRow(t("native.settings.freeMemory"), t("native.settings.freeMemoryNote"), free) { free = it; services.settings.freeMemoryInBackground = it }
    }
}

/** CorpusSettingsTab.tsx: the knowledge packs on the phone and your documents. */
@Composable
private fun KnowledgeSection(services: BoarServices, onOpen: (String) -> Unit) {
    Section(t("modelSetupScreen.sections.knowledgeBase")) {
        val packs = services.catalog.corpusCatalog.filter { services.fileOf(it).isFile }
        ActionRow(
            t("corpusSettingsTab.downloadablePacks"),
            note = if (packs.isEmpty()) t("native.settings.noPacks") else t("native.settings.packsInstalled", "packs" to packs.joinToString(", ") { it.shortName }),
        ) { onOpen("documents") }
        ActionRow(t("native.settings.openDocuments"), note = t("native.settings.documents", "count" to services.knowledgeBase.collections().size)) { onOpen("documents") }
    }
}

/** UsageStatsContent / SystemMonitor: this phone's hardware, what BOAR uses now, and the evaluations it ran. Read only. */
@Composable
private fun TelemetrySection(services: BoarServices) {
    val c = Boar.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sharing = remember { team.sopa.boar.share.ResultsSharing(ctx, services.settings) }
    val d = remember { sharing.device() }
    // Read every 5 s while the section is open, like the menu's bars.
    var app by remember { mutableStateOf(0L) }
    var phone by remember { mutableStateOf(team.sopa.boar.app.AppMemory.Phone(0L, 0L)) }
    var storage by remember { mutableStateOf(0L) }
    var fetching by remember { mutableStateOf(0L) }
    var chatCount by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app = team.sopa.boar.app.AppMemory.residentBytes(); phone = team.sopa.boar.app.AppMemory.phone(ctx)
                storage = services.storageUsedBytes(); fetching = team.sopa.boar.engine.models.AssetStore.downloadingBytes(services.filesDir)
                chatCount = runCatching { services.chats.listSessions().size }.getOrNull()
            }
            kotlinx.coroutines.delay(5000)
        }
    }
    val evals = remember {
        java.io.File(ctx.filesDir, "eval").listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.lastModified() }
    }
    val last = remember(evals) {
        evals.lastOrNull()?.let { f ->
            val rows = f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }.getOrNull() }
            val tps = rows.mapNotNull { (it["tokPerSec"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }.sorted()
            val ok = rows.count { (it["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "success" }
            team.sopa.boar.ui.I18n.t(ctx, "native.telemetry.lastEvalValue", "ok" to ok, "n" to rows.size, "tps" to (if (tps.isEmpty()) "—" else String.format(java.util.Locale.US, "%.1f", tps[tps.size / 2])),
                "date" to java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(f.lastModified())))
        } ?: team.sopa.boar.ui.I18n.t(ctx, "native.telemetry.none")
    }
    // Decimal GB, like the catalog's model sizes and the drawer.
    fun gb(b: Long) = String.format(java.util.Locale.US, "%.1f GB", b / 1e9)
    Section(t("modelSetupScreen.sections.telemetry")) { Padded {
        // For anyone first: memory and storage as bars, the phone and the chats. Then the technical rows (Oct 18).
        val budget = services.catalog.storageBudgetBytes
        val others = maxOf(phone.usedBytes - app, 0L)
        team.sopa.boar.ui.drawer.UsageBar(t("native.telemetry.memory"), listOf(minOf(app, phone.usedBytes) to c.emerald400, others to c.textDim),
            phone.totalBytes, team.sopa.boar.ui.drawer.usage(phone.usedBytes, phone.totalBytes), large = true)
        team.sopa.boar.ui.drawer.UsageCaption(t("native.telemetry.memoryCaption", "app" to gb(app), "others" to gb(others)), large = true)
        team.sopa.boar.ui.drawer.UsageBar(t("native.telemetry.disk"), listOf(storage to c.cyan400, fetching to c.cyan400.copy(alpha = 0.35f)),
            budget, team.sopa.boar.ui.drawer.usage(storage + fetching, budget), large = true)
        team.sopa.boar.ui.drawer.UsageCaption(
            if (fetching > 0) t("native.drawerStats.diskDownloading", "size" to gb(fetching)) else t("native.drawerStats.diskLimit"), large = true,
        )
        @Composable fun rows(list: List<Pair<String, String>>) = list.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(k, style = Boar.type.subtext, color = c.textMuted, modifier = Modifier.weight(0.45f))
                Text(v, style = Boar.type["mono.xs"], color = c.textPrimary, modifier = Modifier.weight(0.55f))
            }
        }
        rows(listOf(
            t("native.telemetry.device") to listOfNotNull(d.brand, d.model).joinToString(" ") + " · Android ${d.osVersion}",
            t("native.telemetry.chats") to (chatCount?.toString() ?: "—"),
        ))
        Text(t("native.telemetry.technical"), style = Boar.type["mono.xs"], color = c.textDim, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        val inf = d.cpuFeatures.orEmpty()
        rows(listOf(
            t("native.telemetry.chip") to (team.sopa.boar.engine.eval.ShareResults.describeChipset(d) ?: "—"),
            t("native.telemetry.cores") to (team.sopa.boar.engine.eval.ShareResults.describeCores(d.coreMaxFreqKHz) ?: "${d.cpuCores ?: "—"}"),
            t("native.telemetry.features") to listOf("dotprod" to "asimddp", "i8mm" to "i8mm", "SVE" to "sve", "SME" to "sme").joinToString(" · ") { (n, f) -> (if (f in inf) "✓ " else "✗ ") + n },
            t("native.telemetry.build") to (team.sopa.boar.engine.BoarEngine.loadedVariant ?: "—"),
            t("native.telemetry.evals") to "${evals.size}",
            t("native.telemetry.lastEval") to last,
        ))
        // The sharing half only in builds that can share (EVAL_SHARING); otherwise nothing is ever sent.
        Text(t(if (sharing.available) "native.telemetry.noteSharing" else "native.telemetry.note"), style = Boar.type.micro, color = c.textDim)
    } }
}

@Composable
private fun SecuritySection(services: BoarServices, onPin: (String) -> Unit) {
    val c = Boar.colors
    val on = services.pinLock.isSet
    Section(t("native.settings.security")) {
        Info(t(if (on) "native.settings.pinOn" else "native.settings.pinOff"), color = if (on) c.textAccentEmerald else c.textSecondary)
        if (on) {
            ActionRow(t("native.settings.pinChange")) { onPin("CHANGE") }
            ActionRow(t("native.settings.pinRemove"), danger = true) { onPin("REMOVE") }
        } else ActionRow(t("native.settings.pinSet")) { onPin("NEW") }
    }
}

/** MemorySettings.tsx: Clear All Chat History, after a confirmation. */
@Composable
private fun ClearChats(services: BoarServices) {
    var asking by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    ActionRow(if (done) t("native.settings.clearChatsDone") else t("memorySettings.clearAllChatHistory"), danger = !done) { if (!done) asking = true }
    if (asking) androidx.compose.material3.AlertDialog({ asking = false },
        title = { Text(t("memorySettings.clearAllConfirmTitle")) },
        confirmButton = { androidx.compose.material3.TextButton({ services.chats.clearAll(); asking = false; done = true }) { Text(t("memorySettings.clearAllButton"), color = Boar.colors.crimson400) } },
        dismissButton = { androidx.compose.material3.TextButton({ asking = false }) { Text(t("common.cancel")) } })
}

/** ModelSetupScreen.tsx's System Recovery & Danger Zone: run setup again, reset settings, or wipe everything and start over. */
@Composable
private fun RecoverySection(services: BoarServices, prefs: DisplayPrefs, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    var purging by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    Section(t("modelSetupScreen.sections.recovery")) {
        ActionRow(t("modelSetupScreen.recovery.wizardButton"), note = t("modelSetupScreen.recovery.wizardDesc"), onClick = onSetup)
        // Defaults back without the multi-GB re-download "Clear all data" costs.
        ActionRow(t("native.settings.resetButton"), note = t("clean.settings.resetDesc")) { resetting = true }
        ActionRow(t("modelSetupScreen.recovery.dangerButton"), note = t("modelSetupScreen.recovery.dangerDesc"), danger = true) { purging = true }
    }
    if (resetting) androidx.compose.material3.AlertDialog({ resetting = false },
        title = { Text(t("native.settings.resetConfirmTitle")) },
        text = { Text(t("native.settings.resetConfirmBody")) },
        confirmButton = { androidx.compose.material3.TextButton({
            resetting = false
            services.settings.resetPreferences()
            services.applyDefaults()
            prefs.setTheme(ThemeMode.DEFAULT_ID)
            prefs.setFontScale("standard")
            onSetup()
        }) { Text(t("native.settings.resetConfirmButton"), color = Boar.colors.crimson400) } },
        dismissButton = { androidx.compose.material3.TextButton({ resetting = false }) { Text(t("common.cancel")) } })
    if (purging) androidx.compose.material3.AlertDialog({ purging = false },
        title = { Text(t("modelSetupScreen.dangerModal.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("modelSetupScreen.dangerModal.subtitle"))
                Text(t("modelSetupScreen.dangerModal.consequencesHeader"), fontWeight = FontWeight.Bold)
                listOf("consequenceModels", "consequenceEmbeddings", "consequenceHistory").forEach { Text("• " + t("modelSetupScreen.dangerModal.$it")) }
            }
        },
        // Android's own "clear data": every file, setting and key of the app, then it closes; next start is setup.
        confirmButton = { androidx.compose.material3.TextButton({ ctx.getSystemService(android.app.ActivityManager::class.java).clearApplicationUserData() }) {
            Text(t("modelSetupScreen.dangerModal.confirmButton"), color = Boar.colors.crimson400) } },
        dismissButton = { androidx.compose.material3.TextButton({ purging = false }) { Text(t("common.cancel")) } })
}

/** The answer styles (Custom only through Developer options), Deep Research and adaptive routing. */
@Composable
private fun ToneSection(services: BoarServices, id: String, onStyle: (String) -> Unit) {
    val s = services.settings
    val personalities = services.personalities
    Section(t("modelSetupScreen.sections.tone")) {
        Choices(null, personalities.personalities.filter { it.id != "custom" || BoarServices.SHOW_CUSTOM_STYLE }.map { it.id to "${it.icon} ${t("personalities.${it.id}.label")}" }, id) {
            onStyle(it); s.personalityId = it
        }
        Note(t("personalities.$id.description"))
        var deep by remember { mutableStateOf(s.deepResearchMode) }
        SwitchRow(t("personalitySettings.deepResearchTitle"), t("personalitySettings.deepResearchNote"), deep) { deep = it; s.deepResearchMode = it }
        var adaptive by remember { mutableStateOf(s.adaptiveRoutingEnabled) }
        SwitchRow(t("native.settings.adaptiveTitle"), t("native.settings.adaptiveNote"), adaptive) { adaptive = it; s.adaptiveRoutingEnabled = it }
    }
}

/**
 * Developer options (DevOptions, seven taps on the version in About): the technical details switch, answer length,
 * the Custom style, the memory numbers, and a switch that hides it all again. Turning it off also turns technical
 * details off, since its switch would no longer be anywhere to find.
 */
@Composable
private fun DeveloperSection(services: BoarServices, styleId: String, onStyle: (String) -> Unit, technical: Boolean, onTechnical: (Boolean) -> Unit) {
    val s = services.settings
    val ctx = LocalContext.current
    Section(t("clean.settings.section.developer")) {
        Note(t("clean.settings.dev.intro"))
        // One switch for everything technical in the chat and the menu (the receipt's time and speed stay for everyone).
        SwitchRow(t("native.tweaks.technicalDetails"), t("native.tweaks.technicalDetailsNote"), technical) { s.showLiveStats = it; onTechnical(it) }
        var tokens by remember { mutableStateOf(s.maxTokensChoice) }
        val auto = AppSettings.autoMaxTokens(styleId, s.deepResearchMode)
        Choices(t("personalitySettings.maxOutputTokens"),
            listOf<Pair<Int?, String>>(null to "${t("native.tweaks.auto")} ($auto)") + services.personalities.maxTokensOptions.map { it to "$it" },
            tokens) { tokens = it; s.maxTokensChoice = it }
        Note(t("personalitySettings.maxOutputTokensNote"))
        val custom = styleId == "custom"
        SwitchRow(t("clean.settings.dev.customStyle"), t("clean.settings.dev.customStyleNote"), custom) { on ->
            val next = if (on) "custom" else services.personalities.defaultId
            onStyle(next); s.personalityId = next
        }
        if (custom) {
            var prompt by remember { mutableStateOf(s.customSystemPrompt ?: "") }
            Field(prompt, t("personalitySettings.customPromptPlaceholder")) { prompt = it; s.customSystemPrompt = it }
        }
        var turns by remember { mutableStateOf(s.historyTurnThreshold) }
        Choices(t("memorySettings.turnsBeforeSummarizing"), listOf(4, 6, 8, 12).map { it to "$it" }, turns) { turns = it; s.historyTurnThreshold = it }
        var max by remember { mutableStateOf(s.maxSavedSessions) }
        Choices(t("memorySettings.maxSavedSessions"), listOf(5, 10, 25, 50).map { it to "$it" }, max) { max = it; s.maxSavedSessions = it }
        SwitchRow(t("clean.settings.dev.enabled"), t("clean.settings.dev.enabledNote"), true) { on ->
            if (!on) { onTechnical(false); DevOptions.setEnabled(ctx, false) }
        }
    }
}

/** A Material settings group: the title in the accent over its rows on one rounded container. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Boar.colors
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = Boar.type.caption, color = c.textAccentEmerald, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.bgCard).padding(vertical = 8.dp), content = content)
    }
}

/** Free-form content inside a group, at the rows' inset. */
@Composable
private fun Padded(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

/** A tappable row: an action in the accent (red when [danger]), or a plain row that opens something when not [accent]. */
@Composable
private fun ActionRow(label: String, note: String? = null, danger: Boolean = false, accent: Boolean = true, onClick: () -> Unit) {
    val c = Boar.colors
    ListItem(
        headlineContent = { Text(label, style = Boar.type.body, fontWeight = if (accent || danger) FontWeight.SemiBold else null) },
        supportingContent = if (note != null) { { Text(note, style = Boar.type.subtext) } } else null,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (danger) c.crimson400 else if (accent) c.textAccentEmerald else c.textPrimary,
            supportingColor = c.textMuted,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(label: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = Boar.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        label?.let { Text(it, style = Boar.type.body, color = c.textPrimary) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                FilterChip(
                    selected = value == selected, onClick = { onSelect(value) },
                    label = { Text(text, style = Boar.type.caption) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = c.textSecondary, selectedLabelColor = c.emerald50, selectedContainerColor = c.emerald900,
                    ),
                )
            }
        }
    }
}

/** A switch row: the whole row toggles, as in Android's own settings. */
@Composable
private fun SwitchRow(label: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Boar.colors
    ListItem(
        headlineContent = { Text(label, style = Boar.type.body) },
        supportingContent = if (note != null) { { Text(note, style = Boar.type.subtext) } } else null,
        trailingContent = { Switch(checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = c.textPrimary, supportingColor = c.textMuted),
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun Info(text: String, color: Color = Boar.colors.textSecondary) =
    Text(text, style = Boar.type.subtext, color = color, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))

@Composable
private fun Note(text: String) = Info(text, Boar.colors.textMuted)

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Boar.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(c.bgInput, RoundedCornerShape(12.dp)).border(1.dp, c.borderDefault, RoundedCornerShape(12.dp)).padding(12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Boar.type.body, color = c.textDim)
        BasicTextField(value, onChange, textStyle = Boar.type.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.emerald500), modifier = Modifier.fillMaxWidth())
    }
}
