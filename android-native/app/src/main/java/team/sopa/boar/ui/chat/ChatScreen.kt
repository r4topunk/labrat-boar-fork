package team.sopa.boar.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import team.sopa.boar.R
import team.sopa.boar.chat.Activity
import team.sopa.boar.chat.ChatState
import androidx.compose.ui.platform.LocalContext
import team.sopa.boar.ui.I18n
import team.sopa.boar.chat.Receipt
import team.sopa.boar.chat.ReceiptFormat
import team.sopa.boar.chat.Source
import team.sopa.boar.chat.UiMessage
import team.sopa.boar.engine.UserErrors
import team.sopa.boar.engine.answer.AnswerText
import team.sopa.boar.engine.answer.ResearchStage
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import team.sopa.boar.ui.theme.BrandType

/**
 * The chat, laid out as src/ui-android/ChatScreen.tsx and ChatHeader.tsx with their style values: the ambient light,
 * header and sub-bar, the loading strip, the empty state, the bubbles and the input bar.
 */
@Composable
fun ChatScreen(
    state: ChatState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onMenu: () -> Unit,
    onSetTone: (String) -> Unit,
    onSetDeepResearch: (Boolean) -> Unit,
    /** The cut answer's "Allow more and redo": its id and the higher limit. */
    onRedo: (String, Int) -> Unit = { _, _ -> },
    onRetry: (String) -> Unit = {},
    /** The memory note (LoadNote): close it, its actions by model id, and the models list. */
    onDismissLoadNote: () -> Unit = {},
    onRetryLessMemory: (String) -> Unit = {},
    onLoadAnyway: (String) -> Unit = {},
    onOpenModels: () -> Unit = {},
    onReused: () -> Unit = {},
    onToastHidden: () -> Unit = {},
) {
    val c = Boar.colors
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var toneSheet by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    if (toneSheet) ToneSheet(state, onSetTone, onSetDeepResearch) { toneSheet = false }
    val last = state.messages.lastOrNull()
    // Follow the end of the answer while it streams, unless the user scrolled up to read: a drag that ends short of
    // the bottom stops following, reaching the bottom again resumes it, and a new message always does.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving -> if (!moving) follow = !list.canScrollForward }
    }
    LaunchedEffect(state.messages.size) { follow = true }
    LaunchedEffect(state.messages.size, last?.text?.length, list.isScrollInProgress) {
        // Jump to the end of the last message (the offset is clamped to the list's end); not animated, since this
        // runs on every streamed token.
        if (follow && !list.isScrollInProgress && state.messages.isNotEmpty()) list.scrollToItem(state.messages.size - 1, Int.MAX_VALUE / 2)
    }
    Box(Modifier.fillMaxSize().background(c.bgTerminal)) {
        Ambient()
        // The status and navigation bar insets are applied inside Header and InputBar, under their backgrounds, so the bars
        // show the bar's own surface instead of a band of the page color; the keyboard still lifts the whole column.
        Column(Modifier.fillMaxSize().imePadding()) {
            team.sopa.boar.ui.theme.FixedSizeChrome { Header(state, onMenu, onNewChat, onSetDeepResearch) }
            state.loadingKey?.let { LoadingBanner(t(it)) }
            state.loadNote?.let { LoadNoteBanner(it, onDismissLoadNote, onRetryLessMemory, onLoadAnyway, onOpenModels) }
            // Deep Research on: the chat area takes a light tint of its color, so the mode shows at a glance.
            val researchTint = if (state.deepResearch) Boar.colors.frontierText.copy(alpha = 0.06f) else Color.Transparent
            if (state.messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(researchTint)) {
                    EmptyState(state.suggestions, onAsk = { onSend(it) }, onFill = { input = it })
                }
            } else {
                LazyColumn(
                    // A readable line length on a wide screen (landscape, tablets), centred; a phone held upright is narrower.
                    Modifier.weight(1f).background(researchTint).widthIn(max = MAX_CHAT_WIDTH).fillMaxWidth().align(Alignment.CenterHorizontally), state = list,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { m -> Bubble(m, state, onReuse = { input = it; onReused() }, onRedo = onRedo, onRetry = onRetry) }
                }
            }
            InputBar(input, { input = it }, state.answeringId != null, onSend = {
                // The answer gets the whole screen: the keyboard closes on send.
                onSend(input); input = ""; keyboard?.hide(); focus.clearFocus()
            }, onStop, state.toneIcon, state.stopping, deepResearch = state.deepResearch) { toneSheet = true }
        }
    }
    state.toast?.let { Toast(it, onToastHidden) }
}

/** The chat column's widest: about 75 characters of body text. */
private val MAX_CHAT_WIDTH = 720.dp

/** Material You has no ember glow; only the faint moon stays, and only in the dark theme. */
@Composable
private fun Ambient() {
    val c = Boar.colors
    if (c.id != "dark") return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = 120.dp).size(110.dp).alpha(0.13f).background(c.cyan500, CircleShape))
    }
}

/** ChatHeader.tsx */
@Composable
private fun Header(state: ChatState, onMenu: () -> Unit, onNewChat: () -> Unit, onSetDeepResearch: (Boolean) -> Unit) {
    val c = Boar.colors
    val type = Boar.type
    Column(Modifier.fillMaxWidth().background(c.bgSurface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Stock icon buttons: 48 dp touch target, button role, and a name for TalkBack (the glyph itself is hidden from it).
            IconButton(onMenu, Modifier.describe(t("drawer.recentSessions"))) { Glyph("☰", 18.sp, FontWeight.Bold) }
            Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.mascot), null, Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("BOAR", style = type.titleLg.copy(letterSpacing = 0.5.sp), color = c.textHeading)
                        StatusPill(state.online)
                    }
                    // While adaptive routing has another model write the answer: "→ that model", then back to the pick.
                    val headerModel = state.answeringModel?.takeIf { state.answeringId != null }?.let { "→ $it" } ?: state.modelLabel
                    Text((headerModel ?: t("chatHeader.localLlmCore")).uppercase(), style = type["mono.xs"], color = c.textDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DeepResearchButton(state.deepResearch, onSetDeepResearch)
                FilledTonalIconButton(onNewChat, Modifier.describe(t("clean.drawer.newChat"))) { Glyph("+", 18.sp, FontWeight.Bold) }
            }
        }
        // Live speed in the header is a technical detail (Advanced tweaks → Show technical details).
        val tps = if (state.activity == Activity.Generating && state.technicalDetails) state.liveTokPerSec else null
        if (tps != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            tps?.let {
                Row(
                    Modifier.background(c.cyanBgSubtle, RoundedCornerShape(6.dp)).border(1.dp, c.cyanBorder, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("●", fontSize = 8.sp, color = c.cyan400)
                    Text("%.1f".format(it), style = type["mono.xs"], color = c.textAccentCyan, fontWeight = FontWeight.Bold)
                    Text("tok/s", style = type["mono.xs"], color = c.textMuted)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDefault))
}

/**
 * Deep Research beside New chat: 🔬 lit when on, dim when off. A tap opens what it does and its switch, so it's found
 * and understood in one place (the style sheet has the same switch).
 */
@Composable
private fun DeepResearchButton(on: Boolean, onSet: (Boolean) -> Unit) {
    val c = Boar.colors
    var open by remember { mutableStateOf(false) }
    Box {
        // Tonal toggle: the tertiary container when on, a visible surface when off (white at 5 % vanished on light).
        FilledTonalIconToggleButton(on, { open = true }, Modifier.describe(t("chatHeader.deepResearch")), colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = c.bgCardElevated, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer, checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )) {
            Glyph("🔬", 17.sp, modifier = Modifier.alpha(if (on) 1f else 0.45f))
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.background(c.bgCardElevated).widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔬 " + t("personalitySettings.deepResearchTitle"), style = Boar.type.titleSm, color = c.textHeading)
                        Text(t(if (on) "native.chat.deepResearchOn" else "native.chat.deepResearchOff"), style = Boar.type["mono.xs"], color = if (on) c.frontierText else c.textDim, fontWeight = FontWeight.Bold)
                    }
                    androidx.compose.material3.Switch(on, onSet, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.emerald500, checkedThumbColor = c.textInverse))
                }
                Text(t("native.chat.deepResearchExplain"), style = Boar.type.subtext, color = c.textSecondary)
            }
        }
    }
}

/** A text glyph inside an icon button: the button carries the name, so TalkBack doesn't also read "trigram for heaven". */
@Composable
private fun Glyph(glyph: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight? = null, modifier: Modifier = Modifier) =
    Text(glyph, fontSize = size, fontWeight = weight, modifier = modifier.clearAndSetSemantics {})

private fun Modifier.describe(text: String): Modifier = semantics { contentDescription = text }

/** The brand's moon blue (brand book: moonlight means no signal), for the OFFLINE pill; light blue only reads on dark. */
private val MOON = Color(0xFF9EC7F0)

@Composable
private fun StatusPill(online: Boolean) {
    val c = Boar.colors
    // The word shows only when it fits: with Large text and Labs badges the header is tight ("ONLIN / E", then "ONL").
    // Without room the pill is just its dot. Told apart by hue and by shape (Oct 16, both were yellow in Moonlight):
    // OFFLINE is a moon-blue outline, the calm state; ONLINE is a filled gold pill, noticed without reading "danger".
    var fits by remember { mutableStateOf(true) }
    val tint = if (online) c.amber400 else if (c.id == "dark") MOON else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier.background(if (online) c.amber400.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(4.dp))
            .border(1.dp, tint.copy(alpha = if (online) 0.9f else 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = if (fits) 1.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(5.dp).background(tint, CircleShape))
        if (fits) Text(t(if (online) "chatHeader.online" else "chatHeader.offline"), fontFamily = BrandType.familyFor("monospace", 800).first, fontSize = 8.sp,
            fontWeight = FontWeight.ExtraBold, color = tint, maxLines = 1, softWrap = false,
            onTextLayout = { if (it.hasVisualOverflow) fits = false })
    }
}

/**
 * The answer model and the phone's memory: it closed the app last time (with "Try again with less memory" and a way
 * to pick another model), or it may not fit (with "Load anyway" and another model). Nothing is blocked for good.
 */
@Composable
private fun LoadNoteBanner(
    note: team.sopa.boar.chat.LoadNote,
    onDismiss: () -> Unit,
    onRetryLessMemory: (String) -> Unit,
    onLoadAnyway: (String) -> Unit,
    onOpenModels: () -> Unit,
) {
    val c = Boar.colors
    val catalog = t("native.chat.pickAnother")
    val (text, actions) = when (note) {
        is team.sopa.boar.chat.LoadNote.Crashed -> {
            val retry = t("native.chat.retryLessMemory") to { onRetryLessMemory(note.modelId) }
            when {
                note.onlyModel -> t("native.chat.loadCrashedOnly", "model" to note.label) to listOf(catalog to onOpenModels, retry)
                note.fallbackLabel != null -> t("native.chat.loadCrashed", "crashed" to note.label, "fallback" to note.fallbackLabel) to listOf(retry, catalog to onOpenModels)
                else -> t("native.chat.loadCrashedNoFallback", "crashed" to note.label) to listOf(retry, catalog to onOpenModels)
            }
        }
        is team.sopa.boar.chat.LoadNote.Risky -> {
            val load = t("native.chat.loadAnyway") to { onLoadAnyway(note.modelId) }
            // The safer choice first (filled): another model; loading anyway is the user's call.
            if (note.onlyModel) t("native.chat.mayNotFitOnly", "model" to note.label) to listOf(catalog to onOpenModels, load)
            else t("native.chat.mayNotFit", "model" to note.label) to listOf(t("native.chat.pickAnother") to onOpenModels, load)
        }
    }
    Column(Modifier.fillMaxWidth().background(c.amberBgSubtle).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(text, style = Boar.type.subtext, color = c.amber400, modifier = Modifier.weight(1f))
            IconButton(onDismiss, Modifier.describe(t("common.close")), colors = IconButtonDefaults.iconButtonColors(contentColor = c.amber400)) {
                Text("✕", style = Boar.type["mono.sm"], modifier = Modifier.clearAndSetSemantics {})
            }
        }
        // The first action is the recommended one: filled. Both share the width and the height of the taller label.
        // Amber is not a scheme role, so its filled button carries its own on-color: dark text on the light amber of
        // the dark theme, white on the deep amber of the light one (onPrimary is only the pair of primary).
        val onAmber = if (c.id == "dark") Color(0xFF261A00) else Color.White
        val shape = RoundedCornerShape(8.dp)
        val pad = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEachIndexed { i, (label, action) ->
                val mod = Modifier.weight(1f).fillMaxHeight()
                val labelText = @Composable { Text(label, style = Boar.type.subtext, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                if (i == 0) Button(action, mod, shape = shape, contentPadding = pad, content = { labelText() },
                    colors = ButtonDefaults.buttonColors(containerColor = c.amber400, contentColor = onAmber))
                else OutlinedButton(action, mod, shape = shape, contentPadding = pad, content = { labelText() },
                    border = BorderStroke(1.dp, c.amberBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.amber400))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.amberBorder))
}

/** Toast.tsx: below the header, fades in, gone after 1.8 s. Also on the Models and Hugging Face pages. */
@Composable
internal fun Toast(message: String, onHide: () -> Unit) {
    val alpha = remember(message) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(message) {
        alpha.animateTo(1f, androidx.compose.animation.core.tween(150))
        kotlinx.coroutines.delay(1800)
        alpha.animateTo(0f, androidx.compose.animation.core.tween(200))
        onHide()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
        // Material's snackbar pair: inverseSurface with inverseOnSurface, so it follows the wallpaper and the theme.
        Text(message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.graphicsLayer { this.alpha = alpha.value }.clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun LoadingBanner(text: String) {
    val c = Boar.colors
    Row(Modifier.fillMaxWidth().background(c.emeraldBgSubtle).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = c.textAccentEmerald, strokeWidth = 1.5.dp)
        Text(text, style = Boar.type["mono.xs"], color = c.textAccentEmerald, fontWeight = FontWeight.SemiBold)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.emeraldBorder))
}

/** components/ChatEmptyState.tsx: the boar, the name and tagline, then the suggested questions (tap asks, long-press fills). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyState(suggestions: List<String>, onAsk: (String) -> Unit, onFill: (String) -> Unit) {
    val c = Boar.colors
    val type = Boar.type
    // Scrolls: with a large font size (or a small screen) the last ideas were below the input and out of reach.
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp).padding(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.mascot), null, Modifier.size(96.dp), contentScale = ContentScale.Fit)
            Text("BOAR", style = type.headline, color = c.textHeading)
            Text(t("chat.empty.tagline"), style = type.subtext.copy(fontFamily = BrandType.familyFor(null, 500).first), color = c.textAccentEmerald, textAlign = TextAlign.Center)
        }
        if (suggestions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("chat.empty.suggestionsLabel"), style = type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
            for (k in suggestions) {
                val q = t("chat.suggestions.$k")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bgCard).combinedClickable(onClick = { onAsk(q) }, onLongClick = { onFill(q) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(t("chat.suggestionTopics.$k"), style = type.caption.copy(fontFamily = BrandType.familyFor(null, 600).first), color = c.textAccentEmerald)
                    // Two lines at most, so five ideas don't crowd the empty chat.
                    Text(q, style = type.body, color = c.textPrimary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun activityLabel(a: Activity?): String = when (a) {
    Activity.Retrieving -> t("processingIndicator.retrieving")
    Activity.Thinking -> t("processingIndicator.thinking")
    Activity.Generating -> t("processingIndicator.generating")
    is Activity.Researching -> when (a.stage) {
        ResearchStage.DECOMPOSING -> t("chatScreen.research.decomposing")
        ResearchStage.RESEARCHING -> t("chatScreen.research.researching", "index" to (a.index ?: 1), "count" to (a.count ?: 1))
        ResearchStage.SYNTHESIZING -> t("chatScreen.research.synthesizing")
    }
    null -> ""
}

/** A message bubble (ChatScreen.tsx styles.bubble / userBubble / assistantBubble). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: UiMessage, state: ChatState, onReuse: (String) -> Unit, onRedo: (String, Int) -> Unit, onRetry: (String) -> Unit) {
    val c = Boar.colors
    val type = Boar.type
    val user = m.role == "user"
    val streaming = state.answeringId == m.id
    var reasoningShown by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val shape = if (user) RoundedCornerShape(20.dp, 20.dp, 8.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 8.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxW = maxWidth * 0.92f
        var mod = Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart).widthIn(min = if (user) 0.dp else 200.dp, max = maxW).clip(shape)
            .background(if (user) c.bgCardHover else c.bgSurface)
        // A Deep Research answer keeps its mark in the history: the border and the header in the mode's color.
        val researched = !user && m.receipt?.path == "deep"
        if (!user) mod = mod.border(if (researched) 1.5.dp else 1.dp, if (researched) c.frontierBadgeBorder else c.borderDefault, shape)
        if (user) mod = mod.combinedClickable(onClick = {}, onLongClick = { onReuse(m.text) })
        Column(mod.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!user) Image(painterResource(R.drawable.mascot), null, Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Fit)
                Text(if (user) t("chatScreen.roleYou") else if (researched) t("native.chat.roleDeepResearch") else t("chatScreen.roleAssistant"), style = type["mono.xs"].copy(letterSpacing = 0.5.sp),
                    color = if (user) c.textAccentCyan else c.textAccentEmerald, fontWeight = FontWeight.SemiBold)
            }
            if (user) {
                Text(m.text, style = type.bodyLg, color = c.textPrimary)
                return@Column
            }
            val split = AnswerText.splitThinking(m.text)
            // Sources are attached when the reply finishes, so invented citations are cleaned only then.
            val shown = if (streaming) split.answer else AnswerText.splitInlineBullets(AnswerText.cleanCitations(split.answer, m.sources.size))
            val reasoningStyle = Modifier.drawBehind { drawRect(c.borderDefault, size = size.copy(width = 2.dp.toPx())) }.padding(start = 8.dp, bottom = 8.dp)
            when {
                m.text.isEmpty() && streaming -> ProcessingIndicator(if (state.stopping) t("native.chat.stopping") else activityLabel(state.activity))
                split.thinkingInProgress && split.answer.isEmpty() && streaming -> ThinkingTail(split.thinking.orEmpty(), reasoningStyle)
                // The reasoning used the whole answer budget: the note says "tap 💭", so 💭 shows what it reasoned.
                split.thinkingInProgress && split.answer.isEmpty() ->
                    Text(if (reasoningShown && split.thinking != null) split.thinking!! else "💭 " + t("chatScreen.reasoningUnfinished"),
                        fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = c.textDim, modifier = reasoningStyle)
                else -> {
                    if (reasoningShown && split.thinking != null) Text(split.thinking!!, fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = c.textDim, modifier = reasoningStyle)
                    MarkdownMessage(shown, streaming)
                }
            }
            // The answer failed (no model, an engine or storage error): a plain line, never the exception text.
            // Kept on the message (UiMessage.error), so it stays after the next question; the live error covers a failure
            // before the answer could be saved.
            val errorKey = m.error ?: state.error?.takeIf { state.messages.lastOrNull()?.id == m.id }?.let(UserErrors::chatKey)
            if (!streaming && !m.stopped && errorKey != null) {
                Text(t(errorKey), style = type.bodyLg, color = c.crimson400)
                if ((errorKey == UserErrors.chatKey(UserErrors.EMPTY_ANSWER) || errorKey == UserErrors.INTERRUPTED_KEY) && state.answeringId == null)
                    FooterButton("↻", t("native.chat.tryAgain"), null, true, c.textAccentEmerald, c.emeraldBgSubtle, c.emeraldBorder) { onRetry(m.id) }
                // Holding a question already copies it back to the input (onReuse); say so where it helps.
                Text(t("native.chat.holdToReuse"), style = Boar.type.subtext, color = c.textPrimary, modifier = Modifier.padding(top = 2.dp))
            }
            // Stopped before writing anything: these are what the search found, not what the answer cites.
            // BOAR answered in another style than the one picked (a health question under Detailed): say so, once, under it.
            if (!streaming) m.receipt?.styleNote?.let { Text(t("native.chat.styleNote.$it"), style = Boar.type.subtext, color = c.textDim, modifier = Modifier.padding(top = 2.dp)) }
            if (m.sources.isNotEmpty()) SourceFootnotes(m.sources, if (m.stopped && !streaming && shown.isBlank()) t("native.chat.foundBeforeStop") else null)
            if (m.stopped) Badge("⏹ " + t("chatScreen.stoppedByUser"))
            if (m.timedOut) Badge("⏱ " + t("chatScreen.stageTimedOut"))
            m.cutAt?.let { limit ->
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge("✂ " + t("native.chat.cutAtLimit", "limit" to limit))
                    if (m.nextLimit != null && state.answeringId == null) FooterButton("↻", t("native.chat.allowMore", "limit" to m.nextLimit), null, true, c.textAccentEmerald, c.emeraldBgSubtle, c.emeraldBorder) { onRedo(m.id, m.nextLimit) }
                }
            }
            var receiptOpen by remember { mutableStateOf(false) }
            if (m.text.isNotEmpty() && !streaming) {
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(0.5.dp).background(c.borderSubtle))
                Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The receipt fills the gap before the reasoning and copy buttons (main's ReceiptToggle), tapped open below.
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        // Everyone sees time and speed; the measured details open only with technical details on.
                        m.receipt?.let { r -> if (state.technicalDetails) ReceiptToggle(r, receiptOpen) { receiptOpen = !receiptOpen } else ReceiptShort(r) }
                    }
                    if (split.thinking != null) FooterButton("💭", null, t(if (reasoningShown) "chatScreen.hideReasoning" else "chatScreen.showReasoning"), reasoningShown, c.textAccentEmerald, c.emeraldBgSubtle, c.emeraldBorder) { reasoningShown = !reasoningShown }
                    var copied by remember { mutableStateOf(false) }
                    // With 💭 open, the reasoning is copied too: reviewing a thinking model needs both.
                    FooterButton("⧉", null, t("chatScreen.copyResponse"), copied, c.textAccentEmerald, c.emeraldBgSubtle, c.emeraldBorder) {
                        clipboard.setText(AnnotatedString(if (reasoningShown && split.thinking != null) "💭 ${split.thinking}\n\n---\n\n$shown" else shown)); copied = true
                    }
                }
                if (receiptOpen && state.technicalDetails) m.receipt?.let { r -> ReceiptDetails(r) }
            }
        }
    }
}

private fun receiptShort(r: Receipt, rate: String) = listOf(ReceiptFormat.seconds(r.totalMs), rate).joinToString(" · ")

/** The receipt everyone sees: "18 s · 10.4 tok/s", nothing to open. */
@Composable
private fun ReceiptShort(r: Receipt) =
    Text(receiptShort(r, t("chat.receipt.speed", "rate" to ReceiptFormat.rate(r.tokPerSec))), style = Boar.type["mono.xs"], color = Boar.colors.textMuted,
        // Two lines with Large text rather than losing "tok/s" off the end.
        maxLines = 2, textAlign = TextAlign.Start, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))

/** The answer's receipt (main's ReceiptToggle): time and speed, tapped open for the measured details. */
@Composable
private fun ReceiptToggle(r: Receipt, open: Boolean, onToggle: () -> Unit) {
    val short = receiptShort(r, t("chat.receipt.speed", "rate" to ReceiptFormat.rate(r.tokPerSec)))
    Text("$short " + if (open) "▴" else "▾", style = Boar.type["mono.xs"], color = Boar.colors.textMuted, maxLines = 2, textAlign = TextAlign.Start,
        modifier = Modifier.clickable(onClickLabel = t("chat.receipt.details"), onClick = onToggle).padding(vertical = 8.dp, horizontal = 4.dp))
}

@Composable
private fun ReceiptDetails(r: Receipt) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        receiptRows(r).forEach { (label, value) -> Text("$label: $value", style = Boar.type["mono.xs"], color = Boar.colors.textSecondary) }
    }
}

/** main's receiptDetails: what the answer took, step by step. */
@Composable
private fun receiptRows(r: Receipt): List<Pair<String, String>> {
    val ctx = LocalContext.current
    fun count(tokens: Int) = I18n.t(ctx, "chat.receipt.tokenCount", "tokens" to "$tokens", "words" to "${ReceiptFormat.words(tokens.toDouble())}")
    // Mode first: the answer style, then how it was written (Deep Research, from this chat, adaptive routing and its plan).
    val mode = r.style?.let { style ->
        listOfNotNull(
            t("native.receipt.style.$style").takeUnless { it == "native.receipt.style.$style" } ?: style,  // an unknown style shows its id
            when (r.path) {
                "deep" -> t("native.receipt.path.deep")
                "chat" -> t("native.receipt.path.chat")
                "adaptive" -> listOfNotNull(t("native.receipt.path.adaptive"), r.task, if (r.checked) t("native.receipt.checked") else null).joinToString(" · ")
                else -> null
            },
        ).joinToString(" · ")
    }
    return listOfNotNull(
        mode?.let { t("native.receipt.mode") to it },
        r.retrievalMs?.let { t("chat.receipt.search") to ReceiptFormat.seconds(it) },
        r.prefillMs?.let { t("chat.receipt.prefill") to ReceiptFormat.seconds(it) },
        r.contextTokens?.let { t("chat.receipt.context") to count(it) },
        t("chat.receipt.start") to ReceiptFormat.seconds(r.ttftMs),
        r.model?.let { t("chat.receipt.model") to it },
        t("chat.receipt.speedLabel") to t("chat.receipt.speedBoth", "rate" to ReceiptFormat.rate(r.tokPerSec), "words" to "${ReceiptFormat.words(r.tokPerSec)}"),
        t("chat.receipt.words") to count(r.tokens),
        t("chat.receipt.total") to ReceiptFormat.seconds(r.totalMs),
    )
}

/**
 * The reasoning while it streams: always 3 lines high, showing its last 3 lines, cut at a word. A window of the last
 * 160 characters wrapped to 2, 3 or 4 lines as tokens came in, and the bubble (and the auto-scroll with it) jumped.
 */
@Composable
private fun ThinkingTail(thinking: String, modifier: Modifier) {
    val c = Boar.colors
    val tail = thinking.takeLast(400).let { t -> if (t.length < thinking.length) t.substringAfter(' ', t) else t }.replace(Regex("\\s+"), " ").trim()
    val box = with(androidx.compose.ui.platform.LocalDensity.current) { (17.sp * 3).toDp() }
    // 💭 stays put at the top left while it reasons; only the text beside it scrolls.
    Row(modifier.fillMaxWidth().height(box), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("💭", fontSize = 12.sp, lineHeight = 17.sp)
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            // Taller than the box and bottom-aligned: the newest line stays at the bottom, older ones slide out at the top.
            Text(tail, fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = c.textDim,
                modifier = Modifier.align(Alignment.BottomStart).wrapContentHeight(Alignment.Bottom, unbounded = true))
        }
    }
}

/** ProcessingIndicator.tsx: the stage line while nothing has streamed yet. */
@Composable
private fun ProcessingIndicator(label: String) {
    val c = Boar.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = c.textAccentEmerald, strokeWidth = 1.5.dp)
        Text(label, style = Boar.type.body, color = c.textMuted)
    }
}

@Composable
private fun FooterButton(glyph: String, label: String?, desc: String?, on: Boolean, onColor: Color, onBg: Color, onBorder: Color, onClick: () -> Unit) {
    val c = Boar.colors
    // The chip stays compact, but its touch target is the 48 dp minimum, and a glyph-only button is named for TalkBack.
    Row(
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(4.dp)).background(if (on) onBg else c.bgSubtle).border(1.dp, if (on) onBorder else c.borderSubtle, RoundedCornerShape(4.dp))
            .then(if (desc != null) Modifier.describe(desc) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = if (on) onColor else c.textDim, modifier = Modifier.clearAndSetSemantics {})
        label?.let { Text(it, style = Boar.type.micro.copy(fontSize = 10.sp, letterSpacing = 0.2.sp), fontWeight = FontWeight.SemiBold, color = onColor) }
    }
}

@Composable
private fun Badge(text: String) {
    val c = Boar.colors
    Text(text, style = Boar.type["mono.xs"], color = c.textAccentAmber, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp).background(c.amberBgSubtle, RoundedCornerShape(4.dp)).border(1.dp, c.amberBorder, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * The input bar (styles.inputContainer / input / sendBtn / stopBtn): the answer style on the left (opens the style
 * sheet), the field, send or stop.
 */
@Composable
private fun InputBar(text: String, onText: (String) -> Unit, answering: Boolean, onSend: () -> Unit, onStop: () -> Unit, toneIcon: String, stopping: Boolean, deepResearch: Boolean = false, onOpenStyles: () -> Unit) {
    val c = Boar.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDefault))
    // Tight gaps, full-size buttons: the field gets the room, and the round buttons keep a 48 dp tap target.
    // The background runs under the gesture bar (navigationBarsPadding comes after it).
    Row(Modifier.fillMaxWidth().background(c.bgCardElevated).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onOpenStyles, Modifier.size(48.dp).describe(t("chatHeader.cycleTone"))) { Glyph(toneIcon, 18.sp) }
        // A pill on one line; a rounded box once the question wraps (a pill's ends cut into the text).
        var lines by remember { mutableStateOf(1) }
        val fieldShape = if (lines > 1) RoundedCornerShape(16.dp) else RoundedCornerShape(50)
        // Deep Research on: the field's border in the mode's color and its own placeholder, right where you type.
        Row(Modifier.weight(1f).clip(fieldShape).background(c.bgInput).border(if (deepResearch) 1.5.dp else 1.dp, if (deepResearch) c.frontierBadgeBorder else c.borderDefault, fieldShape)
            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                val style = Boar.type.body.copy(fontSize = 14.sp, color = c.textPrimary)
                val hint = t(if (deepResearch) "native.chat.deepResearchPlaceholder" else "chatScreen.inputPlaceholder")
                if (text.isEmpty()) Text(hint, style = style, color = if (deepResearch) c.frontierText else c.textDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(text, onText, textStyle = style, cursorBrush = SolidColor(c.emerald500), modifier = Modifier.fillMaxWidth().describe(hint), maxLines = 5,
                    onTextLayout = { lines = it.lineCount })
            }
        }
        val canSend = text.isNotBlank() && !answering
        // After Stop, the button pulses until the answer really ends: a library search or a chunk of prompt finishes first.
        val transition = rememberInfiniteTransition(label = "stopping")
        val blink by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween<Float>(500), RepeatMode.Reverse), label = "pulse")
        val pulse = if (stopping) blink else 1f
        // Stop is error/onError, send is primary/onPrimary: real scheme pairs in both themes. The disabled send state is
        // the stock one (onSurface at 12 % / 38 %); the pulse after Stop applies only while the button is live.
        val scheme = MaterialTheme.colorScheme
        FilledIconButton(
            { if (answering) onStop() else onSend() },
            Modifier.size(48.dp).alpha(if (answering || canSend) pulse else 1f).describe(t(if (answering) "chatScreen.stopGeneration" else "chatScreen.sendMessage")),
            enabled = answering || canSend,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (answering) scheme.error else scheme.primary, contentColor = if (answering) scheme.onError else scheme.onPrimary),
        ) { Glyph(if (answering) "■" else "➤", if (answering) 14.sp else 16.sp, FontWeight.ExtraBold) }
    }
}

