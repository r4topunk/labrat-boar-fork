package team.sopa.boar.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.sopa.boar.R
import team.sopa.boar.engine.models.AnswerTier
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.Units
import team.sopa.boar.setup.AssetProgress
import team.sopa.boar.setup.IndexPhase
import team.sopa.boar.setup.PackagePlan
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Download speed the package cards' time estimate assumes (packages.ts REFERENCE_BYTES_PER_SEC). */
private const val REFERENCE_BYTES_PER_SEC = 5_000_000L

/**
 * The three stages the stepper names (string keys). Download and indexing are one screen, so one stage, and each
 * screen's headline says the stage's own word. The welcome is not a step.
 */
private val STAGES = listOf("clean.setup.stage.assistant", "flows.onboarding.stage.knowledge", "flows.onboarding.stage.install")

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
        )
    }
}

// --- Layout ------------------------------------------------------------------------------------------------------

/**
 * A step: its content scrolls (under the system bars, edge to edge), the actions stay in a bottom bar (the mockup's
 * footer). [radioGroup] marks the page as one choice group for TalkBack (the model and package steps); [footerVisible]
 * false drops the bar (the install step has nothing to press while files arrive).
 */
@Composable
private fun StepScreen(
    radioGroup: Boolean = false,
    footerVisible: Boolean = true,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (footerVisible) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).then(if (radioGroup) Modifier.selectableGroup() else Modifier)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/**
 * The wizard footer: Back at the start, the step's action at the end, both at the standard button height. With no Back
 * the action fills the width (the welcome). [action] receives the modifier to apply.
 */
@Composable
private fun FooterRow(back: (() -> Unit)? = null, backLabel: String = t("flows.onboarding.back"), action: (@Composable (Modifier) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = when {
        back != null && action != null -> Arrangement.SpaceBetween
        back != null -> Arrangement.Start
        else -> Arrangement.End
    }) {
        back?.let {
            TextButton(it) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(backLabel)
            }
        }
        action?.invoke(if (back == null) Modifier.fillMaxWidth() else Modifier)
    }
}

/** "Step 2 of 3: Knowledge" over a determinate bar, then the headline in the stage's own word. */
@Composable
private fun StepHeader(stage: Int, title: String, subtitle: String?) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("flows.onboarding.stageOf", "n" to stage + 1, "total" to STAGES.size, "name" to t(STAGES[stage])),
            style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        LinearProgressIndicator(progress = { (stage + 1f) / STAGES.size }, modifier = Modifier.fillMaxWidth())
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
    }
}

/** A filled M3 card for info and summary blocks; the tinted ones (keep open, failures) pass their container pair. */
@Composable
private fun InfoCard(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

// Real M3 buttons: TalkBack announces the button role, the shape is the M3 full round, and the disabled state is M3's.
@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                          icon: ImageVector? = Icons.AutoMirrored.Filled.ArrowForward, onClick: () -> Unit) =
    Button(onClick, modifier, enabled = enabled) {
        Text(label)
        if (icon != null) {
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
        }
    }

@Composable
private fun SecondaryButton(label: String, icon: ImageVector? = null, onClick: () -> Unit) =
    OutlinedButton(onClick) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(label)
    }

/**
 * A selectable card (OptionCard): the selected one is tinted (secondaryContainer) with a 2 dp primary border, the others
 * are plain surface with a 1 dp outline. A radio for TalkBack and the eye; the number that decides sits on the right.
 */
@Composable
private fun OptionCard(title: String, selected: Boolean, trailing: String?, onClick: () -> Unit, details: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    OutlinedCard(
        Modifier.fillMaxWidth().clip(shape).selectable(selected, onClick = onClick, role = Role.RadioButton),
        shape = shape,
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) cs.secondaryContainer else cs.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) cs.onSecondaryContainer else cs.onSurface)
                details()
            }
            trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Meta(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** "More options" / "Fewer options" with the chevron: the choices beyond the recommended one. */
@Composable
private fun MoreToggle(open: Boolean, onClick: () -> Unit) {
    val stateText = t(if (open) "clean.setup.expanded" else "clean.setup.collapsed")
    TextButton(onClick, Modifier.fillMaxWidth().semantics { stateDescription = stateText }) {
        Text(t(if (open) "clean.setup.fewerOptions" else "clean.setup.moreOptions"))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Icon(if (open) BoarIcons.ExpandLess else BoarIcons.ExpandMore, null, Modifier.size(ButtonDefaults.IconSize))
    }
}

@Composable
private fun BrandDisc(size: Int) =
    Image(painterResource(R.drawable.brand_pfp), null, Modifier.size(size.dp).clip(CircleShape))

private fun formatRam(bytes: Long): String =
    if (bytes >= 999.5 * 1024 * 1024) String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"

/** 5300 -> "5,300" (the phone's separators): the same grouping on every screen that shows a count. */
private fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

private fun minutesLeft(seconds: Long) = maxOf(1, ceil(seconds / 60.0).toInt())
private fun minutesAbout(seconds: Long) = maxOf(1, (seconds / 60.0).roundToLong().toInt())

// --- Step 1: welcome -----------------------------------------------------------------------------------------------

@Composable
private fun Welcome(state: SetupState, onSkip: (() -> Unit)?, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepScreen(footer = {
        FooterRow(back = onSkip, backLabel = t("flows.onboarding.backToApp")) { m -> PrimaryButton(t("flows.onboarding.start"), m, onClick = onNext) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandDisc(56)
            Column {
                Text("BOAR", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
                Text(t("flows.onboarding.brandSub"), style = MaterialTheme.typography.labelMedium, color = cs.primary)
            }
        }
        InfoCard {
            Text(t("flows.onboarding.introLabel"), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            Text(t("flows.onboarding.introBody"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        }
        // Measured on this phone; a value the OS doesn't give is left out, never guessed.
        val rows = listOfNotNull(
            state.totalRamBytes.takeIf { it > 0 }?.let { "phoneMemory" to formatRam(it) },
            state.freeDiskBytes.takeIf { it > 0 }?.let { "phoneFree" to Units.formatBytes(it) },
        )
        if (rows.isNotEmpty()) InfoCard {
            Text(t("flows.onboarding.phoneLabel"), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            rows.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(t("flows.onboarding.$key"), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f).alignByBaseline())
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.alignByBaseline())
                }
            }
        }
    }
}

// --- Step 2: answer model ------------------------------------------------------------------------------------------

@Composable
private fun ModelStep(state: SetupState, vm: SetupViewModel, onBrowse: () -> Unit) {
    val rec = state.models.firstOrNull { it.id == state.recommendedId } ?: state.models.firstOrNull()
    // Another model chosen earlier (from the list or Discover, before a restart) keeps the list open so the choice shows.
    var more by remember { mutableStateOf(rec == null || state.chosenId != rec.id) }
    StepScreen(radioGroup = true, footer = {
        FooterRow(back = { vm.back() }) { m -> PrimaryButton(t("flows.onboarding.continue"), m, enabled = state.chosenId != null) { vm.next() } }
    }) {
        StepHeader(0, t("clean.setup.modelTitle"), t("clean.setup.modelSub"))
        if (rec != null) {
            SectionLabel(t("clean.setup.recommended"))
            ModelOption(rec, state, vm)
            if (vm.tierOf(rec) == "compact") Meta(t("clean.setup.chosenFor", "ram" to formatRam(state.totalRamBytes)))
            MoreToggle(more) { more = !more }
        }
        if (more) {
            // The memory warning is said once, above the models that need more memory than this phone has.
            val (heavy, fits) = state.models.filter { it.id != rec?.id }.partition { vm.isHeavy(it) }
            fits.forEach { ModelOption(it, state, vm) }
            if (heavy.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Meta(t("flows.row.fit.thrashing"), MaterialTheme.colorScheme.tertiary)
                }
                heavy.forEach { ModelOption(it, state, vm) }
            }
            // The same card as Models: a model added there comes back here, listed and chosen.
            team.sopa.boar.ui.models.DiscoverGgufCard(onBrowse)
        }
    }
}

/** The two answer tiers read by their plain name ("Fast", "More accurate") with the model id as a supporting line. */
@Composable
private fun ModelOption(m: CatalogAsset, state: SetupState, vm: SetupViewModel) {
    val tier = if (m.answerTier != null) vm.tierOf(m) else null
    OptionCard(tier?.let { t("flows.onboarding.answerTier.$it") } ?: m.shortName, m.id == state.chosenId, Units.formatBytes(m.sizeBytes), { vm.choose(m.id) }) {
        if (tier != null) {
            Meta(t("flows.assistant.sub.$tier"))
            Meta(m.shortName)
        } else Meta(t(if (vm.isHeavy(m)) "clean.setup.modelLarge" else "clean.setup.modelOther"))
    }
}

// --- Step 3: knowledge ---------------------------------------------------------------------------------------------

@Composable
private fun PackageStep(state: SetupState, vm: SetupViewModel) {
    val cs = MaterialTheme.colorScheme
    val chosen = state.plans.firstOrNull { it.pkg == state.packageId }
    val rec = state.plans.firstOrNull { it.pkg == state.recommendedPackage } ?: state.plans.firstOrNull()
    // The recommended package is preselected: another one chosen earlier keeps the list open so the choice shows.
    var more by remember { mutableStateOf(rec != null && state.packageId != rec.pkg) }
    StepScreen(radioGroup = true, footer = {
        val short = chosen?.shortfallBytes ?: 0
        if (short > 0) Meta(t("flows.onboarding.noSpace", "size" to Units.formatBytes(short)), cs.error)
        val download = (chosen?.downloadBytes ?: 0) > 0
        FooterRow(back = { vm.back() }) { m ->
            PrimaryButton(
                if (download) t("flows.onboarding.install", "size" to Units.formatBytes(chosen!!.downloadBytes)) else t("flows.onboarding.continue"),
                m, enabled = chosen != null && short == 0L, icon = if (download) BoarIcons.Download else Icons.AutoMirrored.Filled.ArrowForward,
            ) { vm.install() }
        }
    }) {
        StepHeader(1, t("clean.setup.knowledgeTitle"), t("clean.setup.knowledgeSub"))
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
    val cs = MaterialTheme.colorScheme
    OptionCard(t("flows.onboarding.package.${p.pkg.id}.name"), p.pkg == state.packageId, p.downloadBytes.takeIf { it > 0 }?.let { Units.formatBytes(it) },
        { vm.choosePackage(p.pkg) }) {
        Text(t("flows.onboarding.package.${p.pkg.id}.body"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        val meta = listOfNotNull(
            if (p.downloadBytes == 0L) t("flows.onboarding.alreadyDownloaded") else null,
            if (Units.formatBytes(p.installedBytes) != Units.formatBytes(p.downloadBytes)) t("flows.onboarding.meta.onDisk", "size" to Units.formatBytes(p.installedBytes)) else null,
            if (p.downloadBytes > 0) t("flows.onboarding.meta.time", "minutes" to minutesAbout(p.downloadBytes / REFERENCE_BYTES_PER_SEC),
                "speed" to Units.formatBytes(REFERENCE_BYTES_PER_SEC)) else null,
        )
        if (meta.isNotEmpty()) Meta(meta.joinToString(" · "))
        if (p.shortfallBytes > 0) Meta(t("flows.onboarding.noSpace", "size" to Units.formatBytes(p.shortfallBytes)), cs.error)
    }
}

// --- Step 4: download and index ------------------------------------------------------------------------------------

private enum class Category(val key: String) { ANSWER("answer"), SEARCH("search"), KNOWLEDGE("knowledge") }

private fun iconOf(cat: Category): ImageVector = when (cat) {
    Category.ANSWER -> BoarIcons.Psychology
    Category.SEARCH -> Icons.Outlined.Search
    Category.KNOWLEDGE -> BoarIcons.MenuBook
}

private fun categoryOf(d: AssetProgress) = when (d.asset.kind) {
    AssetKind.LLM -> Category.ANSWER
    AssetKind.EMBEDDING -> Category.SEARCH
    else -> Category.KNOWLEDGE
}

@Composable
private fun InstallStep(state: SetupState, vm: SetupViewModel, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // The index now builds while the answer model still downloads: the screen is about the index only once every file is here.
    val indexing = state.allPresent && (state.indexPhase == IndexPhase.BUILDING || state.indexPhase == IndexPhase.ERROR)
    // Nothing to press while files arrive (Open BOAR is on the Done screen): only Back, and only when leaving is safe.
    val canBack = !state.transferring && state.indexPhase != IndexPhase.BUILDING
    // The file being fetched: the one with bytes moving, else the first not finished, so the screen never reads "waiting" while bytes flow.
    val current = if (state.transferring) {
        state.downloads.indexOfFirst { it.moving }.takeIf { it >= 0 } ?: state.downloads.indexOfFirst { !it.done && it.error == null }
    } else -1
    val currentId = state.downloads.getOrNull(current)?.asset?.id
    StepScreen(footerVisible = canBack, footer = { FooterRow(back = onBack) }) {
        StepHeader(2, t(if (indexing) "clean.setup.indexTitle" else "flows.onboarding.step3Title"),
            t(if (indexing) "clean.setup.indexSub" else "flows.onboarding.step3Sub"))

        // One hero: the download while files arrive, then the search index.
        val showIndexHero = indexing && state.indexTotal > 0
        if (!state.allPresent || showIndexHero) {
            val fraction = if (!state.allPresent) (if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f)
                else state.indexDone.toFloat() / state.indexTotal
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t(if (!state.allPresent) "flows.onboarding.totalLabel" else "clean.setup.indexLabel"),
                            style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                        Text("${(fraction * 100).toInt()}%", style = Boar.type["mono.lg"], color = cs.onSurface)
                    }
                    BrandDisc(52)
                }
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (!state.allPresent) {
                    if (current >= 0) Text(t("flows.onboarding.currentItem", "n" to current + 1, "total" to state.downloads.size, "name" to state.downloads[current].asset.shortName),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 2)
                } else if (state.indexPhase == IndexPhase.BUILDING && state.indexTitle.isNotEmpty()) {
                    Text(t("flows.onboarding.indexReading", "title" to state.indexTitle), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth()) {
                    Meta(if (!state.allPresent) t("flows.onboarding.totalValue", "done" to Units.formatBytes(state.doneBytes), "total" to Units.formatBytes(state.totalBytes))
                        else t("clean.setup.indexCounter", "done" to formatCount(state.indexDone), "total" to formatCount(state.indexTotal)))
                    Spacer(Modifier.weight(1f))
                    val eta = if (!state.allPresent) state.etaSeconds else state.indexEtaSeconds
                    eta?.let { Meta(t("flows.onboarding.minutesLeft", "count" to minutesLeft(it))) }
                }
                if (!state.allPresent) Meta(t("clean.setup.openWhenReady"))
            }
        }

        // One row per category; a tap shows its files and, for a failed one, Try again.
        val expanded = remember { mutableStateMapOf<Category, Boolean>() }
        InfoCard {
            Category.entries.forEach { cat ->
                val items = state.downloads.filter { categoryOf(it) == cat }
                if (items.isEmpty()) return@forEach
                val status = when {
                    items.any { it.error != null } -> "failed"
                    items.all { it.done } -> "ready"
                    items.any { it.moving || it.asset.id == currentId } -> "moving"
                    else -> "queued"
                }
                val tone = when (status) { "failed" -> cs.error; "moving", "ready" -> cs.primary; else -> cs.onSurfaceVariant }
                val open = expanded[cat] == true
                val name = t("clean.setup.cat.${cat.key}")
                val statusText = t("flows.onboarding.categoryStatus.$status")
                val a11y = t("flows.onboarding.categoryA11y", "name" to name, "done" to items.count { it.done }, "total" to items.size,
                    "pct" to (items.map { it.fraction }.average() * 100).toInt()) + ", " + statusText
                val stateText = t(if (open) "clean.setup.expanded" else "clean.setup.collapsed")
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { expanded[cat] = !open }
                    .semantics { contentDescription = a11y; stateDescription = stateText },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(when (status) { "ready" -> Icons.Filled.CheckCircle; "failed" -> BoarIcons.ErrorOutline; else -> iconOf(cat) }, null, tint = tone)
                    }
                    Text(name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text(statusText, style = MaterialTheme.typography.labelMedium, color = tone)
                    Icon(if (open) BoarIcons.ExpandLess else BoarIcons.ExpandMore, null, tint = cs.onSurfaceVariant)
                }
                items.firstOrNull { it.error != null }?.error?.let { Meta(it, cs.error) }
                if (open) items.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.asset.label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 2)
                        if (d.error != null) TextButton(onClick = { vm.retry() }) { Text(t("flows.row.retry")) }
                        else Text(when {
                            d.done -> t("flows.onboarding.ready")
                            d.verifying -> t("flows.onboarding.checking")
                            d.bytesDone > 0 -> "${(d.fraction * 100).toInt()}%"
                            else -> t("flows.onboarding.queued")
                        }, style = MaterialTheme.typography.labelMedium, color = if (d.moving) cs.primary else cs.onSurfaceVariant)
                    }
                }
            }
            // The index row; while the hero shows the index it would repeat it.
            if (!showIndexHero) {
                val failed = state.indexPhase == IndexPhase.ERROR
                val ready = state.indexPhase == IndexPhase.READY
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(when { failed -> BoarIcons.ErrorOutline; ready -> Icons.Filled.CheckCircle; else -> BoarIcons.Schedule }, null,
                            tint = when { failed -> cs.error; ready -> cs.primary; else -> cs.onSurfaceVariant })
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t("clean.setup.indexTitle"), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                        // While the model downloads, the index runs here with its own count.
                        Meta(when (state.indexPhase) {
                            IndexPhase.WAITING -> t("flows.onboarding.waitingDownloads")
                            IndexPhase.BUILDING -> if (state.indexTotal > 0) t("clean.setup.indexCounter", "done" to formatCount(state.indexDone), "total" to formatCount(state.indexTotal))
                                else t("flows.onboarding.indexStarting")
                            IndexPhase.READY -> t("flows.onboarding.ready")
                            else -> t("flows.onboarding.indexFailed")
                        }, if (failed) cs.error else cs.onSurfaceVariant)
                    }
                }
            }
        }

        if (state.transferring || state.indexPhase == IndexPhase.BUILDING) {
            val indexOnly = state.indexPhase == IndexPhase.BUILDING && !state.transferring
            InfoCard(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(20.dp))
                    Text(t(if (indexOnly) "flows.onboarding.canLeaveTitle" else "flows.onboarding.keepOpenTitle"), style = MaterialTheme.typography.titleSmall)
                }
                Text(t(if (indexOnly) "flows.onboarding.keepOpenIndex" else "flows.onboarding.keepOpen"), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.failed.isNotEmpty()) {
            InfoCard(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer) {
                Text(t("flows.onboarding.downloadFailed"), style = MaterialTheme.typography.titleMedium)
                state.failed.forEach { f -> Text("${f.asset.shortName}: ${f.error}", style = MaterialTheme.typography.bodyMedium) }
                PrimaryButton(t("flows.row.retry"), icon = null) { vm.retry() }
                if (state.failed.any { it.storage }) SecondaryButton(t("flows.onboarding.smallerPackage")) { vm.back() }
            }
        }

        if (state.indexPhase == IndexPhase.ERROR) {
            InfoCard(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer) {
                Text(t("flows.onboarding.indexFailed"), style = MaterialTheme.typography.titleMedium)
                state.indexError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                PrimaryButton(t("flows.row.retry"), icon = null) { vm.retryIndex() }
            }
        }

        if (state.stalled) {
            Meta(t("flows.onboarding.restartHint"))
            SecondaryButton(t("flows.onboarding.restart"), Icons.Filled.Refresh) { vm.restartDownloads() }
        }
    }
}

// --- Done ----------------------------------------------------------------------------------------------------------

@Composable
private fun Done(state: SetupState, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepScreen(footer = { FooterRow { m -> PrimaryButton(t("flows.onboarding.open"), m, icon = null, onClick = onDone) } }) {
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandDisc(120)
            Text(t("flows.onboarding.doneTitle"), style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, textAlign = TextAlign.Center)
            Text(t("flows.onboarding.doneBody"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        val answer = state.downloads.firstOrNull { it.asset.kind == AssetKind.LLM }?.asset
        val answerName = answer?.let { m ->
            when (m.answerTier) {
                null -> m.shortName
                else -> "${t(if (m.answerTier == AnswerTier.COMPACT) "flows.onboarding.answerTier.compact" else "flows.onboarding.answerTier.default")} · ${m.shortName}"
            }
        }
        // The package the person chose, by name: the same words the knowledge step used, so no count here contradicts its promise.
        val hasKnowledge = state.downloads.any { it.asset.kind == AssetKind.CORPUS }
        val rows = listOfNotNull(
            answerName?.let { "doneAnswer" to it },
            if (hasKnowledge) "doneKnowledge" to t("flows.onboarding.package.${state.packageId.id}.name") else null,
            if (state.indexTotal > 0) "doneIndex" to t("flows.onboarding.doneArticles", "count" to state.indexTotal, "value" to formatCount(state.indexTotal)) else null,
        )
        if (rows.isNotEmpty()) InfoCard {
            Text(t("flows.onboarding.doneSummary"), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            rows.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(t("flows.onboarding.$key"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f).alignByBaseline())
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, textAlign = TextAlign.End, modifier = Modifier.alignByBaseline())
                }
            }
        }
    }
}
