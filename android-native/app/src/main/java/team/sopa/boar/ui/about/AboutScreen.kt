package team.sopa.boar.ui.about

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import team.sopa.boar.ui.components.BoarMark
import team.sopa.boar.app.DevOptions
import team.sopa.boar.engine.BoarEngine
import team.sopa.boar.ui.components.BoarTopBar
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.settings.Section
import team.sopa.boar.ui.setup.TourIcons
import team.sopa.boar.ui.t

/**
 * About BoarLite in the app's own grouped-list style (Settings, Knowledge): the boar and the tagline, why it exists,
 * then groups for how it works, the open source addresses, the official addresses, the core team and the credits. An
 * address is plain text with its own copy button: BoarLite has no network and opens nothing. The model line and the
 * version and build line close the screen (the engine joins it once Developer options are on); seven taps on it turn
 * Developer options on or off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val info = versionName(ctx)
    val debug = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // The top bar takes the status bar; the bottom inset is a spacer at the end of the scroll, so the list draws behind the navigation bar.
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = scheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { BoarTopBar(t("aboutScreen.title"), onBack = onClose, scrollBehavior = scroll) },
    ) { inner ->
        // Capped like Settings so the groups don't span a tablet or a landscape phone.
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                Hero()
                Section(t("native.about.howTitle")) {
                    IconRow(TourIcons.Offline, t("native.about.how.offline"), t("native.about.post.offline"))
                    IconRow(TourIcons.Book, t("native.about.how.receipt"), t("native.about.post.receipt"))
                    IconRow(TourIcons.Device, t("native.about.how.budget"), t("native.about.post.budget"))
                }
                Section(t("native.about.openTitle")) {
                    Note(t("native.about.post.open"))
                    CopyRow(t("native.about.codeLabel"), "github.com/rferrari/boar-app")
                    CopyRow(t("native.about.packsTitle"), "huggingface.co/datasets/r4topunk/boar-packs")
                }
                Section(t("native.about.addressesTitle")) {
                    Note(t("native.about.post.verify"))
                    CopyRow(t("native.about.siteLabel"), "boarapp.com")
                    CopyRow("X", "x.com/boar_app")
                    CopyRow("Farcaster", "farcaster.xyz/boarapp")
                    Text(t("native.about.verify"), style = MaterialTheme.typography.titleSmall, color = scheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Section(t("native.about.teamTitle")) {
                    for ((name, handle) in TEAM) CopyRow(name, "@$handle", copy = "x.com/$handle")
                }
                Section(t("native.about.creditsTitle")) {
                    Note(t("native.about.post.credits"))
                    Credits()
                }
                Footer(ctx, info, debug)
                Spacer(Modifier.padding(bottom = 28.dp).windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
    }
}

/** The boar in a tonal disc (as on the tour's last card), the name, the tagline and bio, then why BoarLite exists. */
@Composable
private fun Hero() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
            BoarMark(null, Modifier.size(96.dp), contentScale = ContentScale.Fit)
        }
        Text("BoarLite", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, modifier = Modifier.padding(top = 6.dp))
        Text(t("native.about.tagline"), style = MaterialTheme.typography.titleMedium, color = scheme.primary, textAlign = TextAlign.Center)
        Text(t("native.about.bio"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Text(t("native.about.post.why"), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp))
}

/** A row with a tour line icon in a tonal disc (as on Knowledge), a short title and the longer line under it. */
@Composable
private fun IconRow(icon: ImageVector, title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        leadingContent = {
            Box(Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = scheme.primary)
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = scheme.onSurfaceVariant),
    )
}

/** Free text inside a group, at the rows' inset. */
@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))

/**
 * An address as plain text, never opened (BoarLite has no network): [label] over the address, and the row and its
 * trailing button copy [copy] (the address itself by default; a team member's X address behind their handle).
 */
@Composable
private fun CopyRow(label: String, address: String, copy: String = address) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val copied = t("native.about.copied")
    val copyLabel = t("clean.settings.about.copyAddress")
    val doCopy = {
        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("BoarLite", copy))
        android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
    }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { IconButton(doCopy) { Icon(BoarIcons.ContentCopy, contentDescription = copyLabel, tint = scheme.onSurfaceVariant) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = scheme.onSurfaceVariant),
        modifier = Modifier.clickable(role = Role.Button, onClick = doCopy),
    )
}

/** The open work BoarLite stands on: libraries, then each model and pack with its license; the full list one tap away. */
@Composable
private fun Credits() {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val models = remember {
        team.sopa.boar.app.BoarServices.get(ctx).catalog.models
            .filter { !it.license.startsWith("Unknown") && it.license.isNotBlank() }
            .map { it.label to it.license }
            .distinct()
    }
    val all = AboutCredits.libraries + models
    val shown = if (open) all else all.take(4)
    Column(Modifier.animateContentSize()) {
        shown.forEach { (name, license) ->
            ListItem(
                headlineContent = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                trailingContent = { Text(license, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        TextButton({ open = !open }, Modifier.padding(horizontal = 8.dp)) {
            Text(if (open) t("native.about.creditsLess") else t("native.about.creditsAll", "n" to all.size))
        }
    }
}

/** The one answer model, then the version and build; seven taps in a row on the version toggle Developer options. */
@Composable
private fun Footer(ctx: android.content.Context, info: String, debug: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    val devOn = t("clean.about.devOn")
    val devOff = t("clean.about.devOff")
    var devShown by remember { mutableStateOf(DevOptions.enabled(ctx)) }
    val versionLine = t("aboutScreen.versionBadge", "version" to info, "build" to t(if (debug) "aboutScreen.buildDevelopment" else "aboutScreen.buildRelease")) +
        // The engine is a developer detail: it shows once Developer options are on.
        if (devShown) " • llama.cpp · ${BoarEngine.loadedVariant ?: "—"}" else ""
    val model = remember { team.sopa.boar.app.BoarServices.get(ctx).catalog.answerModel }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("clean.about.model", "model" to model.shortName), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        Text(
            versionLine,
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(scheme.surfaceContainer)
                .semantics { contentDescription = versionLine }
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    taps = if (now - lastTap > TAP_WINDOW_MS) 1 else taps + 1
                    lastTap = now
                    if (taps >= DevOptions.TAPS_TO_UNLOCK) {
                        taps = 0
                        val on = !DevOptions.enabled(ctx)
                        DevOptions.setEnabled(ctx, on)
                        devShown = on
                        android.widget.Toast.makeText(ctx, if (on) devOn else devOff, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
/** "2.0.0-dev · 14193eea · 2026-10-15": the APK's version, the commit it was built from (+ if it had local changes) and the day. */
private fun versionName(ctx: android.content.Context): String = listOfNotNull(
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—",
    team.sopa.boar.BuildConfig.GIT_SHA.ifEmpty { null },
    team.sopa.boar.BuildConfig.BUILD_DATE,
).joinToString(" · ")

/** Taps further apart than this start the count again. */
private const val TAP_WINDOW_MS = 1500L

/** BoarLite's core team on X, alphabetical by name. */
private val TEAM = listOf("Bielcx" to "bielthegoat", "Jasper" to "btcjasper", "r4to" to "r4topunk", "Sopa" to "sopaagency",
    "vaipraonde" to "arferrari", "vlad" to "sk8ordao")
