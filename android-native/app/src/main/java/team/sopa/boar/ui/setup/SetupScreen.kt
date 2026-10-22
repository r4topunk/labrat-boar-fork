package team.sopa.boar.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.sopa.boar.R
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.Units
import team.sopa.boar.setup.AssetProgress
import team.sopa.boar.setup.IndexPhase
import team.sopa.boar.setup.PackagePlan
import team.sopa.boar.setup.SetupPackage
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Download speed the package cards' time estimate assumes (packages.ts REFERENCE_BYTES_PER_SEC). */
private const val REFERENCE_BYTES_PER_SEC = 5_000_000L
private const val LOADING_LINES = 12

/** The five stages the stepper names; the install step covers download and index. */
private val STAGES = listOf("start", "model", "knowledge", "download", "index")

/**
 * First-run setup (port of src/ui/SetupWizardScreen.tsx): welcome, answer model, knowledge, download and index,
 * then a summary. The model and knowledge steps show the recommended choice and keep the rest under "More options".
 * The language follows the phone (Settings has its picker). [onSkip] is set when setup was reopened from Settings and
 * the app already works.
 */
@Composable
fun SetupScreen(state: SetupState, vm: SetupViewModel, onSkip: (() -> Unit)?, onBrowse: () -> Unit = {}, onDone: () -> Unit) {
    var backOpen by remember { mutableStateOf(false) }
    BackHandler(state.step > 1 || onSkip != null) {
        when {
            state.step == 4 && state.indexPhase == IndexPhase.BUILDING -> Unit // the index keeps going: leaving would hide it
            state.step == 4 && state.finished -> Unit
            state.step == 4 && !state.allPresent -> backOpen = true
            state.step == 4 -> vm.back()
            state.step > 1 -> vm.back()
            else -> onSkip?.invoke()
        }
    }
    when {
        state.step == 1 -> Welcome(state, onSkip) { vm.next() }
        state.step == 2 -> ModelStep(state, vm, onBrowse)
        state.step == 3 -> PackageStep(state, vm)
        state.finished -> Done(state, onDone)
        else -> InstallStep(state, vm) { backOpen = true }
    }
    if (backOpen) {
        AlertDialog(
            onDismissRequest = { backOpen = false },
            title = { Text(t("flows.onboarding.backTitle")) },
            text = { Text(t("flows.onboarding.backBody")) },
            confirmButton = { TextButton({ backOpen = false; vm.back() }) { Text(t("flows.onboarding.goBack")) } },
            dismissButton = { TextButton({ backOpen = false }) { Text(t("flows.onboarding.stay")) } },
            containerColor = Boar.colors.bgCardElevated,
            titleContentColor = Boar.colors.textHeading,
            textContentColor = Boar.colors.textSecondary,
        )
    }
}

// --- Layout ------------------------------------------------------------------------------------------------------

/**
 * A step: its content scrolls (under the system bars, edge to edge), the actions stay in a bottom bar (the mockup's
 * footer). [radioGroup] marks the page as one choice group for TalkBack (the model and package steps).
 */
@Composable
private fun StepScreen(radioGroup: Boolean = false, footer: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = Boar.colors.bgTerminal,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, content = footer)
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).then(if (radioGroup) Modifier.selectableGroup() else Modifier)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun Stepper(stage: Int) {
    val c = Boar.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            STAGES.indices.forEach { i ->
                Box(Modifier.weight(1f).height(4.dp).background(if (i <= stage) c.emerald500 else c.bgSubtle, RoundedCornerShape(2.dp)))
            }
        }
        Text(t("flows.onboarding.stageOf", "n" to stage + 1, "total" to STAGES.size, "name" to t("flows.onboarding.stage.${STAGES[stage]}")).uppercase(),
            style = Boar.type.micro, color = c.textAccentEmerald, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StepHeader(stage: Int, title: String, subtitle: String?) {
    Stepper(stage)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = Boar.type.headline, color = Boar.colors.textHeading)
        subtitle?.let { Text(it, style = Boar.type.body, color = Boar.colors.textSecondary) }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, background: Color = Boar.colors.bgCard, border: Color = Boar.colors.borderSubtle, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(background, RoundedCornerShape(16.dp)).border(1.dp, border, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

// Real M3 buttons: TalkBack announces the button role, the shape is the M3 full round, and the disabled state is M3's.
@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled) { Text(label, style = Boar.type.title, textAlign = TextAlign.Center) }

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, style = Boar.type.titleSm, textAlign = TextAlign.Center) }

@Composable
private fun BackLink(label: String, onClick: () -> Unit) =
    TextButton(onClick) { Text("← $label", style = Boar.type.body) }

@Composable
private fun Badge(label: String) {
    val c = Boar.colors
    Text(label.uppercase(), style = Boar.type.micro, color = c.textInverse, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(c.emerald500, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** A selectable card (OptionCard): title, a badge, the number that decides on the right, then details. A radio for TalkBack and the eye. */
@Composable
private fun OptionCard(title: String, selected: Boolean, trailing: String?, badge: String?, onClick: () -> Unit, details: @Composable ColumnScope.() -> Unit) {
    val c = Boar.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) c.emeraldBgSubtle else c.bgCard)
        .border(if (selected) 2.dp else 1.dp, if (selected) c.emeraldBorder else c.borderSubtle, RoundedCornerShape(16.dp))
        .selectable(selected, onClick = onClick, role = Role.RadioButton).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RadioButton(selected, onClick = null)
            Text(title, style = Boar.type.title, color = c.textHeading, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, style = Boar.type["mono.sm"], color = c.textSecondary) }
        }
        badge?.let { Badge(it) }
        details()
    }
}

@Composable
private fun Meta(text: String, color: Color = Boar.colors.textMuted) = Text(text, style = Boar.type.subtext, color = color)

@Composable
private fun SectionLabel(text: String) =
    Text(text.uppercase(), style = Boar.type.micro, color = Boar.colors.textSecondary, fontWeight = FontWeight.Bold)

/** "More options ▾" / "Fewer options ▴": the choices beyond the recommended one. */
@Composable
private fun MoreToggle(open: Boolean, onClick: () -> Unit) {
    val c = Boar.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(t(if (open) "clean.setup.fewerOptions" else "clean.setup.moreOptions") + (if (open) "  ▴" else "  ▾"), style = Boar.type.titleSm, color = c.textAccentEmerald)
    }
}

@Composable
private fun BrandDisc(size: Int) =
    Image(painterResource(R.drawable.brand_pfp), null, Modifier.size(size.dp).clip(CircleShape))

private fun formatRam(bytes: Long): String =
    if (bytes >= 999.5 * 1024 * 1024) String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"

private fun minutesLeft(seconds: Long) = maxOf(1, ceil(seconds / 60.0).toInt())
private fun minutesAbout(seconds: Long) = maxOf(1, (seconds / 60.0).roundToLong().toInt())

// --- Step 1: welcome -----------------------------------------------------------------------------------------------

@Composable
private fun Welcome(state: SetupState, onSkip: (() -> Unit)?, onNext: () -> Unit) {
    val c = Boar.colors
    StepScreen(footer = {
        PrimaryButton(t("flows.onboarding.start") + "  →", onClick = onNext)
        onSkip?.let { BackLink(t("flows.onboarding.backToApp"), it) }
    }) {
        Stepper(0)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandDisc(56)
            Column {
                Text("boar", style = Boar.type.headline, color = c.textHeading)
                Text(t("flows.onboarding.brandSub"), style = Boar.type.caption, color = c.textAccentAmber)
            }
        }
        Card {
            Text(t("flows.onboarding.introLabel").uppercase(), style = Boar.type.micro, color = c.textAccentAmber, fontWeight = FontWeight.Bold)
            Text(t("flows.onboarding.introBody"), style = Boar.type.body, color = c.textPrimary)
        }
        Card {
            Text(t("flows.onboarding.phoneLabel").uppercase(), style = Boar.type.micro, color = c.textSecondary, fontWeight = FontWeight.Bold)
            // Measured on this phone; a value the OS doesn't give is left out, never guessed.
            listOfNotNull(
                state.totalRamBytes.takeIf { it > 0 }?.let { "phoneMemory" to formatRam(it) },
                state.freeDiskBytes.takeIf { it > 0 }?.let { "phoneFree" to Units.formatBytes(it) },
                "phoneEngine" to t("flows.onboarding.phoneEngineValue"),
            ).forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(t("flows.onboarding.$key"), style = Boar.type.body, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Text(value, style = Boar.type["mono.sm"], color = c.textPrimary)
                }
            }
        }
    }
}

// --- Step 2: answer model ------------------------------------------------------------------------------------------

@Composable
private fun ModelStep(state: SetupState, vm: SetupViewModel, onBrowse: () -> Unit) {
    val c = Boar.colors
    val rec = state.models.firstOrNull { it.id == state.recommendedId } ?: state.models.firstOrNull()
    // Another model chosen earlier (from the list or Discover, before a restart) keeps the list open so the choice shows.
    var more by remember { mutableStateOf(rec == null || state.chosenId != rec.id) }
    StepScreen(radioGroup = true, footer = {
        PrimaryButton(t("flows.onboarding.continue") + "  →", enabled = state.chosenId != null) { vm.next() }
        BackLink(t("flows.onboarding.back")) { vm.back() }
    }) {
        StepHeader(1, t("clean.setup.modelTitle"), t("clean.setup.modelSub"))
        if (rec != null) {
            SectionLabel(t("clean.setup.recommended"))
            ModelOption(rec, state, vm)
            if (vm.tierOf(rec) == "compact") Meta(t("flows.onboarding.compactWhy"), c.textSecondary)
            MoreToggle(more) { more = !more }
        }
        if (more) {
            for (m in state.models) if (m.id != rec?.id) ModelOption(m, state, vm)
            // The same card as Models: a model added there comes back here, listed and chosen.
            team.sopa.boar.ui.models.DiscoverGgufCard(onBrowse)
        }
    }
}

@Composable
private fun ModelOption(m: CatalogAsset, state: SetupState, vm: SetupViewModel) {
    val c = Boar.colors
    OptionCard(m.shortName, m.id == state.chosenId, Units.formatBytes(m.sizeBytes), null, { vm.choose(m.id) }) {
        // The two answer tiers have translated lines; the other catalog models carry their manifest description.
        if (m.answerTier != null) {
            val tier = vm.tierOf(m)
            Meta("${t("flows.onboarding.answerTier.$tier")} · ${t("flows.assistant.sub.$tier")}", c.textSecondary)
        } else Meta(m.description, c.textSecondary)
        if (vm.isHeavy(m)) Meta(t("flows.row.fit.thrashing"), c.textAccentAmber)
    }
}

// --- Step 3: knowledge ---------------------------------------------------------------------------------------------

@Composable
private fun PackageStep(state: SetupState, vm: SetupViewModel) {
    val c = Boar.colors
    val chosen = state.plans.firstOrNull { it.pkg == state.packageId }
    val rec = state.plans.firstOrNull { it.pkg == state.recommendedPackage } ?: state.plans.firstOrNull()
    // The recommended package is preselected: another one chosen earlier keeps the list open so the choice shows.
    var more by remember { mutableStateOf(rec != null && state.packageId != rec.pkg) }
    StepScreen(radioGroup = true, footer = {
        val short = chosen?.shortfallBytes ?: 0
        PrimaryButton(
            if ((chosen?.downloadBytes ?: 0) > 0) t("flows.onboarding.install", "size" to Units.formatBytes(chosen!!.downloadBytes)) + "  →"
            else t("flows.onboarding.continue") + "  →",
            enabled = chosen != null && short == 0L,
        ) { vm.install() }
        if (short > 0) Meta(t("flows.onboarding.noSpace", "size" to Units.formatBytes(short)), c.crimson400)
        BackLink(t("flows.onboarding.back")) { vm.back() }
    }) {
        StepHeader(2, t("flows.onboarding.knowledgeTitle"), t("clean.setup.knowledgeSub"))
        if (rec != null) {
            SectionLabel(t("clean.setup.recommended"))
            PackageOption(rec, state, vm)
            if (state.plans.size > 1) MoreToggle(more) { more = !more }
        }
        if (more) for (p in state.plans) if (p.pkg != rec?.pkg) PackageOption(p, state, vm)
    }
}

@Composable
private fun PackageOption(p: PackagePlan, state: SetupState, vm: SetupViewModel) {
    val c = Boar.colors
    OptionCard(t("flows.onboarding.package.${p.pkg.id}.name"), p.pkg == state.packageId, p.downloadBytes.takeIf { it > 0 }?.let { Units.formatBytes(it) },
        null, { vm.choosePackage(p.pkg) }) {
        Text(t("flows.onboarding.package.${p.pkg.id}.body"), style = Boar.type.body, color = c.textSecondary)
        val meta = listOfNotNull(
            if (p.downloadBytes == 0L) t("flows.onboarding.alreadyDownloaded") else null,
            if (Units.formatBytes(p.installedBytes) != Units.formatBytes(p.downloadBytes)) t("flows.onboarding.meta.onDisk", "size" to Units.formatBytes(p.installedBytes)) else null,
            if (p.downloadBytes > 0) t("flows.onboarding.meta.time", "minutes" to minutesAbout(p.downloadBytes / REFERENCE_BYTES_PER_SEC),
                "speed" to Units.formatBytes(REFERENCE_BYTES_PER_SEC)) else null,
        )
        if (meta.isNotEmpty()) Meta(meta.joinToString(" · "))
        if (p.shortfallBytes > 0) Meta(t("flows.onboarding.noSpace", "size" to Units.formatBytes(p.shortfallBytes)), c.crimson400)
    }
}

// --- Step 4: download and index ------------------------------------------------------------------------------------

private enum class Category(val key: String, val icon: String) { ANSWER("answer", "◆"), SEARCH("search", "⌕"), KNOWLEDGE("knowledge", "▤") }

private fun categoryOf(d: AssetProgress) = when (d.asset.kind) {
    AssetKind.LLM -> Category.ANSWER
    AssetKind.EMBEDDING -> Category.SEARCH
    else -> Category.KNOWLEDGE
}

@Composable
private fun InstallStep(state: SetupState, vm: SetupViewModel, onBack: () -> Unit) {
    val c = Boar.colors
    val indexing = state.indexPhase == IndexPhase.BUILDING || state.indexPhase == IndexPhase.ERROR
    StepScreen(footer = {
        // Disabled until every file is on the phone and indexed, and it says why.
        PrimaryButton(t("flows.onboarding.open"), enabled = false) {}
        Meta(t("flows.onboarding.openWhenReady"))
        if (!state.transferring && state.indexPhase != IndexPhase.BUILDING) BackLink(t("flows.onboarding.back"), onBack)
    }) {
        StepHeader(if (state.indexPhase == IndexPhase.WAITING) 3 else 4,
            t(if (indexing) "flows.onboarding.indexTitle" else "flows.onboarding.step3Title"),
            t(if (indexing) "flows.onboarding.indexSub" else "flows.onboarding.step3Sub"))

        // One hero: the download while files arrive, then the search index.
        val showIndexHero = indexing && state.indexTotal > 0
        if (!state.allPresent || showIndexHero) {
            val fraction = if (!state.allPresent) (if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f)
                else state.indexDone.toFloat() / state.indexTotal
            Card(background = c.bgCardElevated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t(if (!state.allPresent) "flows.onboarding.totalLabel" else "flows.onboarding.indexRow").uppercase(),
                            style = Boar.type.micro, color = c.textSecondary, fontWeight = FontWeight.Bold)
                        Text("${(fraction * 100).toInt()}%", style = Boar.type["mono.lg"], color = c.textHeading)
                    }
                    BrandDisc(52)
                }
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = c.emerald500, trackColor = c.bgSubtle)
                if (!state.allPresent) {
                    val i = state.downloads.indexOfFirst { it.moving }
                    if (i >= 0) Text(t("flows.onboarding.currentItem", "n" to i + 1, "total" to state.downloads.size, "name" to state.downloads[i].asset.shortName),
                        style = Boar.type.body, color = c.textPrimary, maxLines = 2)
                } else if (state.indexPhase == IndexPhase.BUILDING && state.indexTitle.isNotEmpty()) {
                    Text(t("flows.onboarding.indexReading", "title" to state.indexTitle), style = Boar.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.transferring || state.indexPhase == IndexPhase.BUILDING)
                    Meta(t("flows.onboarding.loadingLines.l${(state.tick / 18) % LOADING_LINES + 1}"), c.textDim)
                Row(Modifier.fillMaxWidth()) {
                    Meta(if (!state.allPresent) t("flows.onboarding.totalValue", "done" to Units.formatBytes(state.doneBytes), "total" to Units.formatBytes(state.totalBytes))
                        else t("flows.onboarding.indexCounter", "done" to state.indexDone, "total" to state.indexTotal), c.textSecondary)
                    Spacer(Modifier.weight(1f))
                    val eta = if (!state.allPresent) state.etaSeconds else state.indexEtaSeconds
                    eta?.let { Meta(t("flows.onboarding.minutesLeft", "count" to minutesLeft(it)), c.textSecondary) }
                }
            }
        }

        // One row per category; a tap shows its files and, for a failed one, Try again.
        val expanded = remember { mutableStateMapOf<Category, Boolean>() }
        Card(Modifier, c.bgCard) {
            Category.entries.forEach { cat ->
                val items = state.downloads.filter { categoryOf(it) == cat }
                if (items.isEmpty()) return@forEach
                val status = when {
                    items.any { it.error != null } -> "failed"
                    items.all { it.done } -> "ready"
                    items.any { it.moving } -> "moving"
                    else -> "queued"
                }
                val tone = when (status) { "failed" -> c.crimson400; "moving" -> c.textAccentEmerald; "ready" -> c.textAccentEmerald; else -> c.textSecondary }
                val open = expanded[cat] == true
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded[cat] = !open }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (status == "ready") "✓" else if (status == "failed") "!" else cat.icon, style = Boar.type.title, color = tone)
                    Text(t("flows.onboarding.category.${cat.key}"), style = Boar.type.title, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(t("flows.onboarding.categoryStatus.$status").uppercase(), style = Boar.type.micro, color = tone, fontWeight = FontWeight.Bold)
                    Text(if (open) "▴" else "▾", style = Boar.type.body, color = c.textMuted)
                }
                items.firstOrNull { it.error != null }?.error?.let { Meta(it, c.crimson400) }
                if (open) items.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.asset.label, style = Boar.type.subtext, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 2)
                        if (d.error != null) Text(t("flows.row.retry"), style = Boar.type.caption, color = c.textAccentEmerald, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.retry() }.padding(6.dp))
                        else Text(when {
                            d.done -> t("flows.onboarding.ready")
                            d.verifying -> t("flows.onboarding.checking")
                            d.bytesDone > 0 -> "${(d.fraction * 100).toInt()}%"
                            else -> t("flows.onboarding.queued")
                        }, style = Boar.type["mono.xs"], color = if (d.moving) c.textAccentEmerald else c.textMuted)
                    }
                }
            }
            // The index row; while the hero shows the index it would repeat it.
            if (!showIndexHero) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.indexPhase == IndexPhase.ERROR) "!" else "◷", style = Boar.type.title,
                        color = if (state.indexPhase == IndexPhase.ERROR) c.crimson400 else c.textSecondary)
                    Column(Modifier.weight(1f)) {
                        Text(t("flows.onboarding.indexRow"), style = Boar.type.title, color = c.textPrimary)
                        Meta(t(when (state.indexPhase) {
                            IndexPhase.WAITING -> "flows.onboarding.waitingDownloads"
                            IndexPhase.BUILDING -> "flows.onboarding.indexStarting"
                            else -> "flows.onboarding.indexFailed"
                        }), if (state.indexPhase == IndexPhase.ERROR) c.crimson400 else c.textSecondary)
                    }
                }
            }
        }

        if (state.transferring || state.indexPhase == IndexPhase.BUILDING) {
            val indexOnly = state.indexPhase == IndexPhase.BUILDING && !state.transferring
            Card(background = c.amberBgSubtle, border = c.amberBorder) {
                Text("ⓘ " + t(if (indexOnly) "flows.onboarding.canLeaveTitle" else "flows.onboarding.keepOpenTitle").uppercase(),
                    style = Boar.type.micro, color = c.textAccentAmber, fontWeight = FontWeight.Bold)
                Text(t(if (indexOnly) "flows.onboarding.keepOpenIndex" else "flows.onboarding.keepOpen"), style = Boar.type.body, color = c.textPrimary)
            }
        }

        if (state.failed.isNotEmpty()) {
            Card(background = c.crimsonBgSubtle, border = c.crimsonBorder) {
                Text(t("flows.onboarding.downloadFailed"), style = Boar.type.title, color = c.crimson400)
                state.failed.forEach { f -> Text("${f.asset.shortName}: ${f.error}", style = Boar.type.body, color = c.textPrimary) }
                PrimaryButton(t("flows.row.retry")) { vm.retry() }
                if (state.failed.any { it.storage }) SecondaryButton(t("flows.onboarding.smallerPackage")) { vm.back() }
            }
        }

        if (state.indexPhase == IndexPhase.ERROR) {
            Card(background = c.crimsonBgSubtle, border = c.crimsonBorder) {
                Text(t("flows.onboarding.indexFailed"), style = Boar.type.title, color = c.crimson400)
                state.indexError?.let { Text(it, style = Boar.type.subtext, color = c.textSecondary) }
                PrimaryButton(t("flows.row.retry")) { vm.retryIndex() }
            }
        }

        if (state.stalled) {
            Meta(t("flows.onboarding.restartHint"), c.textSecondary)
            SecondaryButton("↻ " + t("flows.onboarding.restart")) { vm.restartDownloads() }
        }
    }
}

// --- Done ----------------------------------------------------------------------------------------------------------

@Composable
private fun Done(state: SetupState, onDone: () -> Unit) {
    val c = Boar.colors
    StepScreen(footer = { PrimaryButton(t("flows.onboarding.open"), onClick = onDone) }) {
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandDisc(120)
            Text(t("flows.onboarding.doneTitle"), style = Boar.type.headline, color = c.textHeading, textAlign = TextAlign.Center)
            Text(t("flows.onboarding.doneBody"), style = Boar.type.body, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        val answer = state.downloads.firstOrNull { it.asset.kind == AssetKind.LLM }?.asset
        val collections = state.downloads.count { it.asset.kind == AssetKind.CORPUS }
        val rows = listOfNotNull(
            answer?.let { "doneAnswer" to it.shortName },
            if (collections > 0) "doneKnowledge" to t("flows.onboarding.doneCollections", "count" to collections, "value" to collections) else null,
            if (state.indexTotal > 0) "doneIndex" to t("flows.onboarding.doneArticles", "count" to state.indexTotal, "value" to state.indexTotal) else null,
        )
        if (rows.isNotEmpty()) Card {
            Text(t("flows.onboarding.doneSummary").uppercase(), style = Boar.type.micro, color = c.textSecondary, fontWeight = FontWeight.Bold)
            rows.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(t("flows.onboarding.$key"), style = Boar.type.body, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Text(value, style = Boar.type.body, color = c.textPrimary, textAlign = TextAlign.End)
                }
            }
        }
    }
}
