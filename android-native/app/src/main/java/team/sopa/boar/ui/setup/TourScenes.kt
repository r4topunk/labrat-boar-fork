package team.sopa.boar.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.sopa.boar.R
import team.sopa.boar.chat.Source
import team.sopa.boar.engine.answer.AnswerNote
import team.sopa.boar.engine.answer.HealthGate
import team.sopa.boar.setup.TourGroup
import team.sopa.boar.ui.chat.MarkdownMessage
import team.sopa.boar.ui.chat.SourceFootnotes
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.IconSize
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The tour's scenes: the stage above each card's title, where the app shows itself. The chat scenes are built from the
 * chat's own parts (MarkdownMessage, SourceFootnotes, AnswerNote.LINE, HealthGate.NO_SOURCE) on a smaller type scale,
 * so the tour shows what the user will see. A scene plays when its card becomes the current one ([play]); with the
 * phone's animations off ([motion] false) every scene shows its last frame at once.
 */

/** The overshoot that lifts things in (the design's cubic-bezier(.2, .9, .3, 1.15)). */
private val Rise = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.15f)

/** A one-shot sequence: 0 until [play], then 1, 2, … after each delay; the last step at once without [motion]. */
@Composable
private fun rememberStep(play: Boolean, motion: Boolean, vararg delaysMs: Long): Int {
    var step by remember(play, motion) { mutableIntStateOf(if (motion) 0 else delaysMs.size) }
    LaunchedEffect(play, motion) {
        if (!motion || !play) return@LaunchedEffect
        for (d in delaysMs) { delay(d); step++ }
    }
    return step
}

/** Fades and lifts in when [visible] turns true; already shown (no animation) when it starts true. */
@Composable
private fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(280)) + slideInVertically(tween(420, easing = Rise)) { it / 3 },
        exit = fadeOut(tween(120))) { content() }

/** The stage: a rounded panel; [screen] draws it as a phone screen (the surface with an outline) for the chat scenes. */
@Composable
internal fun Stage(modifier: Modifier = Modifier, screen: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxWidth().clip(shape).background(if (screen) cs.surface else cs.surfaceContainer)
        .then(if (screen) Modifier.border(1.dp, cs.outlineVariant, shape) else Modifier)) {
        StageType { content() }
    }
}

/** The chat's type roles a step smaller, so a whole question, answer and sources fit on the stage. */
@Composable
private fun StageType(content: @Composable () -> Unit) {
    val t = MaterialTheme.typography
    fun TextStyle.s(f: Float) = copy(fontSize = if (fontSize.isSpecified) fontSize * f else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * f else lineHeight)
    MaterialTheme(MaterialTheme.colorScheme, MaterialTheme.shapes,
        t.copy(bodyLarge = t.bodyLarge.s(0.88f), bodyMedium = t.bodyMedium.s(0.9f), bodySmall = t.bodySmall.s(0.92f),
            labelLarge = t.labelLarge.s(0.9f), labelMedium = t.labelMedium.s(0.92f)), content)
}

// --- Chat parts ------------------------------------------------------------------------------------------------------

/** The question as the chat draws it (ChatScreen's user bubble). */
@Composable
private fun Question(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface,
            modifier = Modifier.align(Alignment.CenterEnd).padding(start = 32.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp))
                .background(cs.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** The answer's byline: the boar and "BoarLite". */
@Composable
private fun Byline() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.mascot), null, Modifier.size(IconSize.Standard), contentScale = ContentScale.Fit)
        Text(t("chatScreen.roleAssistant"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The input bar as the chat draws it, with the question being typed; the send button fills once there is text. */
@Composable
private fun DemoInput(typed: String) {
    val cs = MaterialTheme.colorScheme
    val ready = typed.isNotEmpty()
    val send by animateColorAsState(if (ready) cs.primary else cs.surfaceContainerHighest, tween(200), label = "demoSend")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(BoarIcons.Tune, null, tint = cs.onSurfaceVariant) }
        Text(typed.ifEmpty { t("chatScreen.inputPlaceholder") }, style = MaterialTheme.typography.bodyLarge,
            color = if (ready) cs.onSurface else cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).background(send), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Send, null, tint = if (ready) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** The sample answer's sources, as the engine names them (an illustration: the answer is not generated here). */
@Composable
private fun demoSources() = listOf(
    Source(t("clean.tour.demo.src1.title"), t("clean.tour.demo.src1.name"), null, body = t("clean.tour.demo.src1.body")),
    Source(t("clean.tour.demo.src2.title"), null, null, body = t("clean.tour.demo.src2.body")),
    Source(t("clean.tour.demo.src3.title"), null, null, body = t("clean.tour.demo.src3.body")),
)

/** A column that scrolls itself to its end as it grows, as the chat follows a streaming answer. */
@Composable
private fun FollowingColumn(modifier: Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier.verticalScroll(scroll, enabled = false).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

// --- 1. Welcome --------------------------------------------------------------------------------------------------------

/**
 * The pack count in a breathing disc inside two counter-turning rings, with the knowledge themes' icons orbiting it
 * (popping in one by one). The loops run only while the card is shown.
 */
@Composable
internal fun WelcomeScene(groups: List<TourGroup>, count: Int, play: Boolean, motion: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val looping = play && motion
    val angle: State<Float> = if (looping) {
        rememberInfiniteTransition(label = "orbit").animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "angle")
    } else remember { mutableFloatStateOf(0f) }
    val breathe: State<Float> = if (looping) {
        rememberInfiniteTransition(label = "breathe").animateFloat(1f, 1.04f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "scale")
    } else remember { mutableFloatStateOf(1f) }
    val icons = remember(groups) { groups.take(6).map { TourIcons.forGroup(it.titleKey) to (it.titleKey == "clean.tour.group.health") } }
    val pops = remember(icons.size) { List(icons.size) { Animatable(0f) } }
    LaunchedEffect(play, motion) {
        pops.forEachIndexed { i, a ->
            when {
                !motion -> a.snapTo(1f)
                !play -> a.snapTo(0f)
                else -> launch { delay(200L + i * 150L); a.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
            }
        }
    }
    val words = rememberStep(play, motion, 500)
    Stage(modifier) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(330.dp), contentAlignment = Alignment.Center) {
                val track = cs.outlineVariant
                val accent = cs.primary
                Canvas(Modifier.size(240.dp)) {
                    val a = angle.value
                    val w = 10.dp.toPx()
                    val r = 110.dp.toPx()
                    val tl = Offset(center.x - r, center.y - r)
                    rotate(a * 4.4f) {
                        drawCircle(track, r, style = Stroke(w, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 16.dp.toPx()))))
                        drawArc(accent, -90f, 85f, false, tl, Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round))
                    }
                    rotate(-a * 2.9f) {
                        drawCircle(accent.copy(alpha = 0.35f), 93.dp.toPx(),
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(40.dp.toPx(), 24.dp.toPx()))))
                    }
                }
                Box(Modifier.size(160.dp).graphicsLayer { scaleX = breathe.value; scaleY = breathe.value }
                    .shadow(10.dp, CircleShape).clip(CircleShape).background(cs.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$count", style = displayStyle(1.5f), color = cs.primary)
                        Text(t("clean.tour.welcome.packs"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                }
                icons.forEachIndexed { k, (icon, health) ->
                    Box(Modifier.align(Alignment.TopStart)
                        .offset {
                            val th = Math.toRadians((angle.value + k * 60f - 90f).toDouble())
                            val c = 165.dp.toPx()
                            val r = 140.dp.toPx()
                            val half = 22.dp.toPx()
                            IntOffset((c + r * cos(th) - half).roundToInt(), (c + r * sin(th) - half).roundToInt())
                        }
                        .graphicsLayer { val p = pops[k].value; scaleX = p; scaleY = p; alpha = p.coerceIn(0f, 1f) }
                        .size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(cs.surfaceContainerLowest),
                        contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (health) cs.error else cs.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Reveal(words >= 1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t("clean.tour.welcome.offline"), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text(t("clean.tour.welcome.inside"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

// --- 2. What BoarLite knows --------------------------------------------------------------------------------------------

/** The count, then one tile per theme (icon, pack count, pack names) popping in, then the calculator. */
@Composable
internal fun KnowsScene(groups: List<TourGroup>, count: Int, play: Boolean, motion: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val step = rememberStep(play, motion, *LongArray(groups.size + 1) { if (it == 0) 200L else 80L })
    Stage(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$count", style = displayStyle(1.2f), color = cs.primary)
                Text(t("clean.tour.knows.onPhone"), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            }
            groups.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { col, g -> Reveal(step > row * 2 + col, Modifier.weight(1f)) { PackTile(g) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Reveal(step > groups.size) {
                Row(Modifier.fillMaxWidth().dashedOutline(cs.outlineVariant).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(TourIcons.Calculator, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Text(t("clean.tour.knows.calculator"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                }
            }
        }
    }
}

@Composable
private fun PackTile(g: TourGroup) {
    val cs = MaterialTheme.colorScheme
    val health = g.titleKey == "clean.tour.group.health"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerLowest).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(if (health) cs.errorContainer else cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(TourIcons.forGroup(g.titleKey), null, tint = if (health) cs.onErrorContainer else cs.onPrimaryContainer, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("${g.names.size}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Text(t(g.titleKey), style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(g.names.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.dashedOutline(color: androidx.compose.ui.graphics.Color) = drawBehind {
    val r = 16.dp.toPx()
    drawRoundRect(color, cornerRadius = CornerRadius(r, r),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
}

// --- 3. How to ask -----------------------------------------------------------------------------------------------------

/** The question typed into the input bar and sent; the answer streams in word by word, then its line and sources. */
@Composable
internal fun AskScene(play: Boolean, motion: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val question = t("clean.tour.demo.question")
    val answer = t("clean.tour.demo.answer")
    val words = remember(answer) { answer.split(" ") }
    val sources = demoSources()
    var typed by remember(play, motion) { mutableStateOf("") }
    var asked by remember(play, motion) { mutableStateOf(!motion) }
    var shown by remember(play, motion) { mutableIntStateOf(if (motion) 0 else words.size) }
    var done by remember(play, motion) { mutableStateOf(!motion) }
    var cited by remember(play, motion) { mutableStateOf(!motion) }
    LaunchedEffect(play, motion) {
        if (!motion || !play) return@LaunchedEffect
        delay(500)
        for (i in 1..question.length) { typed = question.take(i); delay(38) }
        delay(350); typed = ""; asked = true
        delay(600)
        for (i in 1..words.size) { shown = i; delay(55) }
        done = true
        delay(300); cited = true
    }
    Stage(modifier, screen = true) {
        Column(Modifier.fillMaxSize()) {
            FollowingColumn(Modifier.weight(1f).fillMaxWidth()) {
                Reveal(asked) { Question(question) }
                Reveal(asked) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Byline()
                        MarkdownMessage(words.take(shown).joinToString(" "), streaming = !done)
                        if (done) Text(AnswerNote.LINE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Reveal(cited) { SourceFootnotes(sources) }
            }
            Box(Modifier.padding(8.dp)) { DemoInput(typed) }
        }
    }
}

// --- 4. Check the source -----------------------------------------------------------------------------------------------

/** The answer with its closing line called out; source 1 opens by itself to show the passage the answer used. */
@Composable
internal fun SourceScene(play: Boolean, motion: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val step = rememberStep(play, motion, 450, 900)
    Stage(modifier, screen = true) {
        FollowingColumn(Modifier.fillMaxSize()) {
            Byline()
            MarkdownMessage(t("clean.tour.demo.answer"), streaming = false)
            Reveal(step >= 1) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(BoarIcons.ErrorOutline, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    Text(AnswerNote.LINE, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
                }
            }
            SourceFootnotes(demoSources(), openAt = if (step >= 2) 0 else null)
        }
    }
}

// --- 5. Health & emergencies -------------------------------------------------------------------------------------------

/** A dose question; BoarLite "types", then the fixed health text (HealthGate) with the numbers, then the caveat. */
@Composable
internal fun HealthScene(play: Boolean, motion: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val step = rememberStep(play, motion, 300, 400, 1300, 400, 150, 500)
    val glow = remember(play, motion) { Animatable(0f) }
    LaunchedEffect(step >= 3, motion) {
        if (motion && step >= 3) { glow.snapTo(0.5f); glow.animateTo(0f, tween(1600)) }
    }
    Stage(modifier, screen = true) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Reveal(step >= 1) { Question(t("clean.tour.demo.healthQuestion")) }
            Reveal(step >= 2) { Byline() }
            if (motion && step == 2) TypingDots()
            Reveal(step >= 3) {
                Text(HealthGate.NO_SOURCE, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth().drawBehind {
                        if (glow.value > 0f) drawRoundRect(cs.primaryContainer.copy(alpha = glow.value), cornerRadius = CornerRadius(14.dp.toPx()))
                    })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Reveal(step >= 4) { Emergency(t("clean.tour.health.us")) }
                Reveal(step >= 5) { Emergency(t("clean.tour.health.eu")) }
            }
            Spacer(Modifier.weight(1f))
            Reveal(step >= 6) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.tertiaryContainer).padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(TourIcons.Shield, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(t("clean.tour.health.doctor")) }
                        append(" ")
                        append(t("clean.tour.health.caveat"))
                    }, style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer)
                }
            }
        }
    }
}

@Composable
private fun Emergency(label: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(cs.errorContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Call, null, tint = cs.onErrorContainer, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onErrorContainer)
    }
}

/** Three dots bobbing in turn while the answer is on its way. */
@Composable
private fun TypingDots() {
    val cs = MaterialTheme.colorScheme
    val tr = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val y by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(450, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(6.dp).graphicsLayer { translationY = -y * 3.dp.toPx(); alpha = 0.5f + y / 2 }.background(cs.outline, CircleShape))
        }
    }
}

// --- 6. Your chats stay here -------------------------------------------------------------------------------------------

/** The boar with the no-network badge, then the three things that keep chats on the phone. */
@Composable
internal fun PrivateScene(count: Int, play: Boolean, motion: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val step = rememberStep(play, motion, 100, 350, 250, 150, 150)
    val bob: State<Float> = if (play && motion) {
        rememberInfiniteTransition(label = "bob").animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "y")
    } else remember { mutableFloatStateOf(0f) }
    Stage(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(150.dp)) {
                Reveal(step >= 1) { Box(Modifier.size(150.dp).clip(CircleShape).background(cs.primaryContainer)) }
                Image(painterResource(R.drawable.mascot), t("clean.onboarding.welcome.mascotA11y"),
                    Modifier.align(Alignment.Center).size(118.dp).graphicsLayer { translationY = -bob.value * 6.dp.toPx() },
                    contentScale = ContentScale.Fit)
                Reveal(step >= 2, Modifier.align(Alignment.BottomEnd)) {
                    Box(Modifier.size(44.dp).border(4.dp, cs.surfaceContainer, CircleShape).padding(4.dp).clip(CircleShape).background(cs.onSurface),
                        contentAlignment = Alignment.Center) {
                        Icon(TourIcons.Offline, null, tint = cs.surface, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Reveal(step >= 3) { TrustRow(TourIcons.Offline, t("clean.tour.private.noNet"), t("clean.tour.private.noNetSub")) }
            Reveal(step >= 4) { TrustRow(TourIcons.Device, t("clean.tour.private.phone"), t("clean.tour.private.phoneSub")) }
            Reveal(step >= 5) { TrustRow(TourIcons.More, t("clean.tour.private.airplane"), t("clean.tour.private.airplaneSub", "count" to count)) }
        }
    }
}

@Composable
private fun TrustRow(icon: ImageVector, title: String, sub: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerLowest).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
