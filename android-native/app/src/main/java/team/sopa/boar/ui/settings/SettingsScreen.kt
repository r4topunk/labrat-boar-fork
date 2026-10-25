package team.sopa.boar.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.DevOptions
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.ui.components.BoarTopBar
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import team.sopa.boar.ui.theme.ThemeMode

/** Display settings the app applies live (theme, text size). */
interface DisplayPrefs {
    fun setTheme(id: String)
    fun setFontScale(scale: String)
}

/**
 * Settings as a Material grouped list: the model, knowledge, chats, display, About, then
 * Start over. No answer style, answer length or Deep Research: the app answers in Succinct at its Auto length (r4to,
 * Oct 24). The measurement knobs (technical details, adaptive routing, memory numbers) live in Developer options, shown only after seven taps on the version in About. The engine's
 * own knobs keep their saved values (Auto by default) and the engine reads them as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(services: BoarServices, prefs: DisplayPrefs, onSetup: () -> Unit, onOpen: (String) -> Unit = {}, onBack: () -> Unit) {
    val s = services.settings
    val ctx = LocalContext.current
    var tech by remember { mutableStateOf(s.showLiveStats) }
    // State, so switching Developer options off in its own section hides it at once.
    var dev by remember { mutableStateOf(DevOptions.enabled(ctx)) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { BoarTopBar(t("modelSetupScreen.header.title"), onBack = onBack, scrollBehavior = scroll) },
    ) { inner ->
        // Capped like the chat so rows and cards don't span a tablet or a landscape phone.
        Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                ModelsSection(services)
                KnowledgeSection(services, onOpen)
                Section(t("modelSetupScreen.sections.memory")) {
                    var titles by remember { mutableStateOf(s.autoGenerateTitles) }
                    SwitchRow(t("memorySettings.autoTitlesLabel"), t("memorySettings.autoTitlesValue"), titles, icon = Icons.Outlined.Edit) { titles = it; s.autoGenerateTitles = it }
                    // No switch for the long-chat summary or a PIN (r4to 2026-10-24): BoarLite writes no summary
                    // (BoarServices.applyDefaults) and has no PIN lock.
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
                AnimatedVisibility(tech, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { TelemetrySection(services) }
                AnimatedVisibility(dev, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    DeveloperSection(services, tech, { tech = it }) { dev = false }
                }
                // About under its own header, like every other group; the version is the supporting line.
                Section(t("design.settings.about")) {
                    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
                    ActionRow(t("aboutScreen.title"), note = version, icon = Icons.Outlined.Info, chevron = true) { onOpen("about") }
                }
                RecoverySection(services, prefs, onSetup)
            }
        }
    }
}

/**
 * Only a note when the search model's file is missing. The answer model is not a choice (About names it), and memory is
 * always freed when you leave (freeMemoryInBackground stays at its default, on; r4to 2026-10-24: no switch for it).
 */
@Composable
private fun ModelsSection(services: BoarServices) {
    // The embedding model searches the packs and documents; it isn't a choice, so it shows here only when it's missing
    // (Developer options has the ok line).
    val emb = services.catalog.models.firstOrNull { it.kind == AssetKind.EMBEDDING }
    if (emb != null && !services.fileOf(emb).isFile) Section(t("modelSetupScreen.sections.models")) {
        Info(t("native.settings.embeddingMissing", "model" to emb.shortName), MaterialTheme.colorScheme.error)
    }
}

/** CorpusSettingsTab.tsx in one row: the knowledge packs on the phone and your documents. */
@Composable
private fun KnowledgeSection(services: BoarServices, onOpen: (String) -> Unit) {
    Section(t("modelSetupScreen.sections.knowledgeBase")) {
        val packs = services.catalog.corpusCatalog.count { services.fileOf(it).isFile }
        val docs = services.knowledgeBase.collections().size
        val note = listOf(
            if (packs == 0) t("clean.settings.knowledge.noPacks") else t("clean.settings.knowledge.packs", "count" to packs),
            if (docs == 0) t("clean.settings.knowledge.noDocs") else t("clean.settings.knowledge.docs", "count" to docs),
        ).joinToString(" · ")
        ActionRow(t("clean.settings.knowledge.title"), note = note, icon = BoarIcons.LibraryBooks, chevron = true) { onOpen("documents") }
    }
}

/** UsageStatsContent / SystemMonitor: this phone's hardware, what BOAR uses now, and the evaluations it ran. Read only. */
@Composable
private fun TelemetrySection(services: BoarServices) {
    val c = Boar.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val d = remember { team.sopa.boar.app.DeviceReader.device(ctx) }
    // Read every 5 s while the section is open, like the menu's bars.
    var app by remember { mutableStateOf(0L) }
    var phone by remember { mutableStateOf(team.sopa.boar.app.AppMemory.Phone(0L, 0L)) }
    var storage by remember { mutableStateOf(0L) }
    var chatCount by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app = team.sopa.boar.app.AppMemory.residentBytes(); phone = team.sopa.boar.app.AppMemory.phone(ctx)
                storage = services.storageUsedBytes()
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
        team.sopa.boar.ui.drawer.UsageBar(t("native.telemetry.memory"), listOf(minOf(app, phone.usedBytes) to c.emerald400, others to c.textSecondary),
            phone.totalBytes, team.sopa.boar.ui.drawer.usage(phone.usedBytes, phone.totalBytes), large = true)
        team.sopa.boar.ui.drawer.UsageCaption(t("native.telemetry.memoryCaption", "app" to gb(app), "others" to gb(others)), large = true)
        team.sopa.boar.ui.drawer.UsageBar(t("native.telemetry.disk"), listOf(storage to c.cyan400),
            budget, team.sopa.boar.ui.drawer.usage(storage, budget), large = true)
        team.sopa.boar.ui.drawer.UsageCaption(t("native.drawerStats.diskLimit"), large = true)
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
        Text(t("native.telemetry.technical"), style = Boar.type["mono.xs"], color = c.textSecondary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        val inf = d.cpuFeatures.orEmpty()
        rows(listOf(
            t("native.telemetry.chip") to (team.sopa.boar.engine.eval.DeviceInfo.describeChipset(d) ?: "—"),
            t("native.telemetry.cores") to (team.sopa.boar.engine.eval.DeviceInfo.describeCores(d.coreMaxFreqKHz) ?: "${d.cpuCores ?: "—"}"),
            t("native.telemetry.features") to listOf("dotprod" to "asimddp", "i8mm" to "i8mm", "SVE" to "sve", "SME" to "sme").joinToString(" · ") { (n, f) -> (if (f in inf) "✓ " else "✗ ") + n },
            t("native.telemetry.build") to (team.sopa.boar.engine.BoarEngine.loadedVariant ?: "—"),
            t("native.telemetry.evals") to "${evals.size}",
            t("native.telemetry.lastEval") to last,
        ))
        Text(t("native.telemetry.note"), style = Boar.type.micro, color = c.textSecondary)
    } }
}


/** MemorySettings.tsx: Clear All Chat History, after a confirmation. */
@Composable
private fun ClearChats(services: BoarServices) {
    var asking by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    ActionRow(if (done) t("native.settings.clearChatsDone") else t("memorySettings.clearAllChatHistory"), danger = !done,
        icon = if (done) Icons.Filled.Check else Icons.Outlined.Delete) { if (!done) asking = true }
    if (asking) AlertDialog({ asking = false },
        title = { Text(t("memorySettings.clearAllConfirmTitle")) },
        confirmButton = { TextButton({ services.chats.clearAll(); asking = false; done = true }) { Text(t("memorySettings.clearAllButton"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ asking = false }) { Text(t("common.cancel")) } })
}

/** ModelSetupScreen.tsx's System Recovery & Danger Zone: run setup again, reset settings, or wipe everything and start over. */
@Composable
private fun RecoverySection(services: BoarServices, prefs: DisplayPrefs, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val error = MaterialTheme.colorScheme.error
    var purging by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    Section(t("modelSetupScreen.sections.recovery")) {
        ActionRow(t("modelSetupScreen.recovery.wizardButton"), note = t("modelSetupScreen.recovery.wizardDesc"), icon = Icons.Outlined.PlayArrow, onClick = onSetup)
        // Defaults back without the multi-GB re-download "Clear all data" costs.
        ActionRow(t("native.settings.resetButton"), note = t("clean.settings.resetDesc"), icon = Icons.Filled.Refresh) { resetting = true }
        ActionRow(t("modelSetupScreen.recovery.dangerButton"), note = t("modelSetupScreen.recovery.dangerDesc"), danger = true, icon = Icons.Outlined.Delete) { purging = true }
    }
    if (resetting) AlertDialog({ resetting = false },
        title = { Text(t("native.settings.resetConfirmTitle")) },
        text = { Text(t("native.settings.resetConfirmBody")) },
        confirmButton = { TextButton({
            resetting = false
            services.settings.resetPreferences()
            services.applyDefaults(afterReset = true)
            prefs.setTheme(ThemeMode.DEFAULT_ID)
            prefs.setFontScale("standard")
            onSetup()
        }) { Text(t("native.settings.resetConfirmButton"), color = error) } },
        dismissButton = { TextButton({ resetting = false }) { Text(t("common.cancel")) } })
    if (purging) AlertDialog({ purging = false },
        icon = { Icon(Icons.Outlined.Delete, null) },
        iconContentColor = error,
        title = { Text(t("modelSetupScreen.dangerModal.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("modelSetupScreen.dangerModal.subtitle"))
                Text(t("modelSetupScreen.dangerModal.consequencesHeader"), fontWeight = FontWeight.Bold)
                listOf("consequenceModels", "consequenceEmbeddings", "consequenceHistory").forEach { Text("• " + t("modelSetupScreen.dangerModal.$it")) }
            }
        },
        // Android's own "clear data": every file, setting and key of the app, then it closes; next start is setup.
        confirmButton = { TextButton({ ctx.getSystemService(android.app.ActivityManager::class.java).clearApplicationUserData() }) {
            Text(t("modelSetupScreen.dangerModal.confirmButton"), color = error) } },
        dismissButton = { TextButton({ purging = false }) { Text(t("common.cancel")) } })
}

/**
 * Developer options (DevOptions, seven taps on the version in About): the technical details switch, adaptive routing,
 * the memory numbers, and a switch that hides it all again. Turning it off also
 * turns technical details off ([onDisable] hides the section), since its switch would no longer be anywhere to find.
 */
@Composable
private fun DeveloperSection(services: BoarServices, technical: Boolean, onTechnical: (Boolean) -> Unit, onDisable: () -> Unit) {
    val s = services.settings
    val ctx = LocalContext.current
    Section(t("clean.settings.section.developer")) {
        Note(t("clean.settings.dev.intro"))
        // One switch for everything technical in the chat and the menu (the receipt's time and speed stay for everyone).
        SwitchRow(t("native.tweaks.technicalDetails"), t("native.tweaks.technicalDetailsNote"), technical) { s.showLiveStats = it; onTechnical(it) }
        // An engine knob, off by default: it changes how the answer is produced even with a single model installed.
        var adaptive by remember { mutableStateOf(s.adaptiveRoutingEnabled) }
        SwitchRow(t("native.settings.adaptiveTitle"), t("native.settings.adaptiveNote"), adaptive) { adaptive = it; s.adaptiveRoutingEnabled = it }
        val emb = services.catalog.models.firstOrNull { it.kind == AssetKind.EMBEDDING }
        if (emb != null && services.fileOf(emb).isFile) Note(t("native.settings.embeddingOk", "model" to emb.shortName))
        var turns by remember { mutableStateOf(s.historyTurnThreshold) }
        Choices(t("memorySettings.turnsBeforeSummarizing"), listOf(4, 6, 8, 12).map { it to "$it" }, turns) { turns = it; s.historyTurnThreshold = it }
        var max by remember { mutableStateOf(s.maxSavedSessions) }
        Choices(t("memorySettings.maxSavedSessions"), listOf(5, 10, 25, 50).map { it to "$it" }, max) { max = it; s.maxSavedSessions = it }
        SwitchRow(t("clean.settings.dev.enabled"), t("clean.settings.dev.enabledNote"), true) { on ->
            if (!on) { onTechnical(false); DevOptions.setEnabled(ctx, false); onDisable() }
        }
    }
}

/** A settings group's title: labelLarge in primary, on the rows' 16 dp inset. */
@Composable
private fun SectionHeader(title: String) = Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp))

/** A Material settings group: the title in the primary color over its rows on one rounded container ([title] null: no header). */
@Composable
internal fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = if (title == null) 24.dp else 0.dp)) {
        if (title != null) SectionHeader(title)
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(scheme.surfaceContainer).padding(vertical = 8.dp), content = content)
    }
}

/** Free-form content inside a group, at the rows' inset. */
@Composable
private fun Padded(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

/**
 * A tappable list item: the label in onSurface (error when [danger]), an optional leading [icon] and, for rows that open
 * another screen, a trailing [chevron].
 */
@Composable
private fun ActionRow(label: String, note: String? = null, danger: Boolean = false, icon: ImageVector? = null, chevron: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (note != null) { { Text(note) } } else null,
        leadingContent = if (icon != null) { { Icon(icon, contentDescription = null) } } else null,
        trailingContent = if (chevron) { { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) } } else null,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (danger) scheme.error else scheme.onSurface,
            supportingColor = scheme.onSurfaceVariant,
            leadingIconColor = if (danger) scheme.error else scheme.onSurfaceVariant,
            trailingIconColor = scheme.onSurfaceVariant,
        ),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * A single choice: Material segmented buttons for up to four options, filter chips (with a check when selected) when
 * there are more, since they would not fit one row.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choices(label: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        if (options.size <= 4) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (value, text) ->
                    SegmentedButton(
                        selected = value == selected, onClick = { onSelect(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, text) in options) {
                    FilterChip(
                        selected = value == selected, onClick = { onSelect(value) },
                        label = { Text(text) },
                        leadingIcon = if (value == selected) { { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                    )
                }
            }
        }
    }
}

/** A switch row: the whole row toggles, as in Android's own settings. */
@Composable
internal fun SwitchRow(label: String, note: String?, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (note != null) { { Text(note) } } else null,
        leadingContent = if (icon != null) { { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } } else null,
        trailingContent = { Switch(checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun Info(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))

@Composable
private fun Note(text: String) = Info(text)
