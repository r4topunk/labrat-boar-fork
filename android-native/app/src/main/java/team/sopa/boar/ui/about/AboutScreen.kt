package team.sopa.boar.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.sopa.boar.R
import team.sopa.boar.app.DevOptions
import team.sopa.boar.engine.BoarEngine
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar

/**
 * About BOAR as a short thread from @boar_app (brand book, manifesto register): what BOAR is, its addresses, and the
 * open work it stands on, with the brand mark (direction A, "Tusk moon") as the avatar. Addresses copy on press and hold; nothing opens from
 * here. The version, build and engine line closes the thread; seven taps on it turn Developer options on or off.
 */
@Composable
fun AboutScreen(onClose: () -> Unit) {
    val c = Boar.colors
    val ctx = LocalContext.current
    val info = versionName(ctx)
    val debug = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    Column(Modifier.fillMaxSize().background(c.bgSurface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(c.bgCardElevated).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("aboutScreen.title"), style = Boar.type.titleSm, color = c.textHeading, modifier = Modifier.weight(1f))
            Text(t("common.done"), style = Boar.type["mono.xs"], color = c.textAccentCyan, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.bgSubtle).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDefault))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp).padding(bottom = 28.dp)) {
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
            Text(t("native.about.holdToCopy"), style = Boar.type["mono.xs"], color = c.textDim, modifier = Modifier.padding(start = 56.dp, top = 4.dp))
            // Seven taps in a row on the version toggle Developer options, as on Android's own build number.
            var taps by remember { mutableStateOf(0) }
            var lastTap by remember { mutableStateOf(0L) }
            val devOn = t("clean.about.devOn")
            val devOff = t("clean.about.devOff")
            Text(
                t("aboutScreen.versionBadge", "version" to info, "build" to t(if (debug) "aboutScreen.buildDevelopment" else "aboutScreen.buildRelease")) + " • llama.cpp · ${BoarEngine.loadedVariant ?: "—"}",
                style = Boar.type["mono.xs"], color = c.textSecondary,
                modifier = Modifier.padding(start = 56.dp, top = 12.dp).clip(RoundedCornerShape(4.dp)).background(c.bgSubtle)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        taps = if (now - lastTap > TAP_WINDOW_MS) 1 else taps + 1
                        lastTap = now
                        if (taps >= DevOptions.TAPS_TO_UNLOCK) {
                            taps = 0
                            val on = !DevOptions.enabled(ctx)
                            DevOptions.setEnabled(ctx, on)
                            android.widget.Toast.makeText(ctx, if (on) devOn else devOff, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
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
                Text(t("native.about.tagline"), style = Boar.type.titleSm, color = Color(0xFFF3ECDC), fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    modifier = Modifier.fillMaxWidth(0.62f).padding(start = 14.dp, top = 12.dp))
            }
            Image(painterResource(R.drawable.boar_mark), null,
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp).offset(y = 40.dp).size(80.dp).clip(CircleShape)
                    .border(3.dp, c.bgSurface, CircleShape), contentScale = ContentScale.Crop)
        }
        Column(Modifier.padding(top = 48.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Boar", style = Boar.type.title, color = c.textHeading, fontWeight = FontWeight.Bold)
            Text("@boar_app", style = Boar.type["mono.xs"], color = c.textDim)
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
                Text("@boar_app", style = Boar.type.caption, color = c.textDim)
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
            // Hidden models (no longer offered, such as Phi-3.5) aren't credited.
            .filter { !it.hidden && !it.license.startsWith("Unknown") && it.license.isNotBlank() }
            .map { it.label to it.license }
            .distinct()
    }
    val all = AboutCredits.libraries + AboutCredits.extra() + models
    val shown = if (open) all else all.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { (name, license) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = Boar.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text(license, style = Boar.type["mono.xs"], color = c.textDim)
            }
        }
        Text(if (open) t("native.about.creditsLess") else t("native.about.creditsAll", "n" to all.size), style = Boar.type.caption, color = c.textAccentCyan,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = !open }.padding(vertical = 4.dp))
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

/** An address to type or paste, never opened from here; press and hold copies [copy] (the text itself by default). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Link(text: String, copy: String = text) {
    val c = Boar.colors
    val ctx = LocalContext.current
    val copied = t("native.about.copied")
    Text(text, style = Boar.type["mono.sm"], color = c.textAccentCyan,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.bgTerminal, RoundedCornerShape(6.dp)).border(1.dp, c.borderDefault, RoundedCornerShape(6.dp))
            .combinedClickable(onClick = {}, onLongClick = {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("BOAR", copy))
                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
            })
            .padding(10.dp))
}
