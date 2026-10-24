package team.sopa.boar.ui.about

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.sopa.boar.R
import team.sopa.boar.app.DevOptions
import team.sopa.boar.engine.BoarEngine
import team.sopa.boar.ui.components.BoarTopBar
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar

/**
 * About BOAR as a short thread from @boar_app (brand book, manifesto register): what BOAR is, its addresses, and the
 * open work it stands on, with the brand mark (direction A, "Tusk moon") as the avatar. An address is plain text with its
 * own copy button: BoarLite has no network and opens nothing. The model line and the version and build line close the thread (the engine joins it once Developer
 * options are on); seven taps on it turn Developer options on or off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onClose: () -> Unit) {
    val c = Boar.colors
    val ctx = LocalContext.current
    val info = versionName(ctx)
    val debug = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // The top bar takes the status bar; the bottom inset is a spacer at the end of the scroll, so the thread draws behind the navigation bar.
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { BoarTopBar(t("aboutScreen.title"), onBack = onClose, scrollBehavior = scroll) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
            Profile()
            // Three posts: about BOAR, its addresses, the open work it stands on.
            Post(last = false) {
                listOf("why", "offline", "receipt", "budget").forEachIndexed { i, key ->
                    Text(t("native.about.post.$key"), style = Boar.type.body, color = c.textPrimary, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp))
                }
            }
            Post(last = false) {
                Text(t("native.about.post.open"), style = Boar.type.body, color = c.textPrimary)
                Link("github.com/rferrari/boar-app")
                Text(t("native.about.post.verify"), style = Boar.type.body, color = c.textPrimary, modifier = Modifier.padding(top = 6.dp))
                Link("boarapp.com"); Link("x.com/boar_app"); Link("farcaster.xyz/boarapp")
                Text(t("native.about.verify"), style = Boar.type.title, color = c.textAccentEmerald, fontWeight = FontWeight.ExtraBold)
                Text(t("native.about.post.packs"), style = Boar.type.body, color = c.textPrimary, modifier = Modifier.padding(top = 6.dp))
                Link("huggingface.co/datasets/r4topunk/boar-packs")
                Text(t("native.about.post.team"), style = Boar.type.body, color = c.textPrimary, modifier = Modifier.padding(top = 6.dp))
                for ((name, handle) in TEAM) Link("$name · @$handle", copy = "x.com/$handle")
            }
            Post(last = true) {
                Text(t("native.about.post.credits"), style = Boar.type.body, color = c.textPrimary)
                Credits()
            }
            // Seven taps in a row on the version toggle Developer options, as on Android's own build number.
            var taps by remember { mutableStateOf(0) }
            var lastTap by remember { mutableStateOf(0L) }
            val devOn = t("clean.about.devOn")
            val devOff = t("clean.about.devOff")
            var devShown by remember { mutableStateOf(DevOptions.enabled(ctx)) }
            val versionLine = t("aboutScreen.versionBadge", "version" to info, "build" to t(if (debug) "aboutScreen.buildDevelopment" else "aboutScreen.buildRelease")) +
                // The engine is a developer detail: it shows once Developer options are on.
                if (devShown) " • llama.cpp · ${BoarEngine.loadedVariant ?: "—"}" else ""
            // The one fixed answer model, read only.
            val model = remember { team.sopa.boar.app.BoarServices.get(ctx).catalog.answerModel }
            Text(t("clean.about.model", "model" to model.shortName), style = Boar.type["mono.xs"], color = c.textSecondary,
                modifier = Modifier.padding(start = 56.dp, top = 12.dp))
            Text(
                versionLine,
                style = Boar.type["mono.xs"], color = c.textSecondary,
                modifier = Modifier.padding(start = 56.dp, top = 12.dp).clip(RoundedCornerShape(4.dp)).background(c.bgSubtle)
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
            Spacer(Modifier.padding(bottom = 28.dp).windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

/**
 * The @boar_app profile from the brand book: the banner (the boar reading his phone by the fire), the tagline over
 * it, the round mark overlapping its lower edge, then the name, handle and bio.
 */
@Composable
private fun Profile() {
    val c = Boar.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(12.dp))) {
                Image(painterResource(R.drawable.boar_banner), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // The moon sits behind the tagline: a dark scrim fading to the right keeps the text readable.
                Box(Modifier.fillMaxHeight().fillMaxWidth(0.7f).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
                Text(t("native.about.tagline"), style = Boar.type.titleSm, color = Color(0xFFF3ECDC), fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    modifier = Modifier.fillMaxWidth(0.62f).padding(start = 14.dp, top = 12.dp))
            }
            Image(painterResource(R.drawable.boar_mark), null,
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp).offset(y = 40.dp).size(80.dp).clip(CircleShape)
                    .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape), contentScale = ContentScale.Crop)
        }
        Column(Modifier.padding(top = 48.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Boar", style = Boar.type.title, color = c.textHeading, fontWeight = FontWeight.Bold)
            Text("@boar_app", style = Boar.type["mono.xs"], color = c.textSecondary)
            Text(t("native.about.bio"), style = Boar.type.body, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** One post: the round avatar with the thread line under it (none after the last), the name, then the content. */
@Composable
private fun Post(last: Boolean, content: @Composable () -> Unit) {
    val c = Boar.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.boar_mark), null, Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.borderDefault, CircleShape),
                contentScale = ContentScale.Crop)
            if (!last) Box(Modifier.width(2.dp).weight(1f).padding(vertical = 4.dp).background(c.borderDefault))
        }
        Column(Modifier.weight(1f).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Boar", style = Boar.type.titleSm, color = c.textHeading, fontWeight = FontWeight.Bold)
                Text("@boar_app", style = Boar.type.caption, color = c.textSecondary)
            }
            content()
        }
    }
}

/** The open work BOAR stands on: libraries, then each model and pack with its license; the full list one tap away. */
@Composable
private fun Credits() {
    val c = Boar.colors
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val models = remember {
        team.sopa.boar.app.BoarServices.get(ctx).catalog.models
            .filter { !it.license.startsWith("Unknown") && it.license.isNotBlank() }
            .map { it.label to it.license }
            .distinct()
    }
    val all = AboutCredits.libraries + AboutCredits.extra() + models
    val shown = if (open) all else all.take(4)
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { (name, license) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = Boar.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text(license, style = Boar.type["mono.xs"], color = c.textSecondary)
            }
        }
        TextButton({ open = !open }) { Text(if (open) t("native.about.creditsLess") else t("native.about.creditsAll", "n" to all.size)) }
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

/** BOAR's core team on X, alphabetical by name. */
private val TEAM = listOf("Bielcx" to "bielthegoat", "Jasper" to "btcjasper", "r4to" to "r4topunk", "Sopa" to "sopaagency",
    "vaipraonde" to "arferrari", "vlad" to "sk8ordao")

/**
 * An address as plain text, never opened (BoarLite has no network); the trailing button copies it (the text itself by
 * default). [text] is what shows, so a team member reads as a name and the copy is the X address.
 */
@Composable
private fun Link(text: String, copy: String = text) {
    val c = Boar.colors
    val ctx = LocalContext.current
    val copied = t("native.about.copied")
    val copyLabel = t("clean.settings.about.copyAddress")
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = Boar.type["mono.sm"], color = c.textAccentCyan, modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
            IconButton({
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("BOAR", copy))
                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
            }) { Icon(BoarIcons.ContentCopy, contentDescription = copyLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
