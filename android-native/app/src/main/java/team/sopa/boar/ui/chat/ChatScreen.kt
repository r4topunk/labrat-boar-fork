package team.sopa.boar.ui.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import team.sopa.boar.R
import team.sopa.boar.chat.Activity
import team.sopa.boar.chat.ChatState
import team.sopa.boar.chat.Receipt
import team.sopa.boar.chat.ReceiptFormat
import team.sopa.boar.chat.UiMessage
import team.sopa.boar.engine.UserErrors
import team.sopa.boar.engine.answer.AnswerText
import team.sopa.boar.engine.answer.ResearchStage
import team.sopa.boar.ui.I18n
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import team.sopa.boar.ui.theme.FixedSizeChrome

/**
 * The chat on Material 3: a TopAppBar with the mascot, the bubbles, the loading strip, the empty state and the input
 * bar (style chip, text field, send or stop). Chrome text uses the typography roles and the colour scheme; tertiary is
 * kept for Deep Research, error for failures.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    /** Answer options: the model to answer with, Sources (true = only the user's files), and Reset to defaults. */
    onSetModel: (String) -> Unit = {},
    onSetSourcesOwn: (Boolean) -> Unit = {},
    onResetOptions: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    // Android 13+: "BOAR is answering…" (AnswerService) needs the notification permission, which nothing asked for
    // (issue #91). Asked once, with the first question; a refusal stands and is never asked again.
    val notifyCtx = androidx.compose.ui.platform.LocalContext.current
    val notifyAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    val send: (String) -> Unit = { q -> askNotificationsOnce(notifyCtx) { notifyAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS) }; onSend(q) }
    var toneSheet by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    if (toneSheet) AnswerOptionsSheet(state, AnswerOptionsActions(onSetTone, onSetModel, onSetSourcesOwn, onSetDeepResearch, onResetOptions)) { toneSheet = false }
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
    // The bar tints to surfaceContainer once the messages scroll under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        // The status and navigation bar insets are applied inside the TopAppBar and InputBar, under their backgrounds, so the
        // bars show their own surface instead of a band of the page color; the keyboard still lifts the whole column.
        Column(Modifier.fillMaxSize().imePadding().nestedScroll(barScroll.nestedScrollConnection)) {
            FixedSizeChrome { Header(state, onMenu, onNewChat, barScroll) }
            state.loadingKey?.let { LoadingBanner(t(it)) }
            state.loadNote?.let { LoadNoteBanner(it, onDismissLoadNote, onRetryLessMemory, onLoadAnyway, onOpenModels) }
            // Deep Research shows in the composer (outline, placeholder, chip), not as a tint over the whole chat: the tint
            // washed out the tonal rows on it.
            val researchTint = Color.Transparent
            if (state.messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(researchTint)) {
                    EmptyState(state.suggestions, onAsk = { send(it) }, onFill = { input = it })
                }
            } else {
                LazyColumn(
                    // A readable line length on a wide screen (landscape, tablets), centred; a phone held upright is narrower.
                    Modifier.weight(1f).background(researchTint).widthIn(max = MAX_CHAT_WIDTH).fillMaxWidth().align(Alignment.CenterHorizontally), state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(state.messages, key = { it.id }) { m ->
                        Bubble(m, state, onReuse = { input = it; onReused() }, onRedo = onRedo, onRetry = onRetry, modifier = Modifier.animateItem())
                    }
                }
            }
            InputBar(input, { input = it }, state.answeringId != null, onSend = {
                // The answer gets the whole screen: the keyboard closes on send.
                send(input); input = ""; keyboard?.hide(); focus.clearFocus()
            }, onStop, state.stopping, state.deepResearch, state.sourcesOwn, state.optionsChanged) { toneSheet = true }
        }
    }
    state.toast?.let { Toast(it, onToastHidden) }
}

/** The chat column's widest: about 75 characters of body text. */
private val MAX_CHAT_WIDTH = 720.dp

/** "Qwen2.5-1.5B-Instruct" reads "Qwen2.5 · 1.5B": the suffixes that only mean "tuned for chat" or "file format" are dropped. */
private fun modelName(raw: String, technical: Boolean): String {
    if (technical) return raw
    val parts = raw.split('-').filter { p -> p.isNotBlank() && !p.equals("instruct", true) && !p.equals("it", true) && !p.equals("chat", true) && !p.equals("gguf", true) && !Regex("(?i)q\\d.*").matches(p) }
    return if (parts.isEmpty()) raw else parts.take(3).joinToString(" · ")
}

/**
 * ChatHeader.tsx as a small TopAppBar: menu, mascot with the name and the model, the constant "Works offline" chip
 * (it opens what online/offline means) and New chat. Deep Research lives in the style sheet and the input bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(state: ChatState, onMenu: () -> Unit, onNewChat: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    val scheme = MaterialTheme.colorScheme
    var info by remember { mutableStateOf(false) }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        confirmButton = { TextButton({ info = false }) { Text(t("common.close")) } },
        title = { Text(t(if (state.online) "chatHeader.onlineTitle" else "chatHeader.offlineTitle")) },
        text = { Text(t(if (state.online) "chatHeader.onlineBody" else "chatHeader.offlineBody")) },
    )
    // While adaptive routing has another model write the answer: "→ that model", then back to the pick.
    val routed = state.answeringModel?.takeIf { state.answeringId != null }
    val picked = state.modelLabel
    val subtitle = when {
        routed != null -> "→ " + modelName(routed, state.technicalDetails)
        picked != null -> modelName(picked, state.technicalDetails)
        else -> t("chatHeader.localLlmCore")
    }
    Column {
        TopAppBar(
            title = {
                // One tappable identity block: the mascot, the name, and one quiet line with the on-device claim and the
                // model. Tapping it opens what working offline means (it was a separate chip that crowded the bar).
                Row(
                    Modifier.clip(MaterialTheme.shapes.medium).clickable(onClickLabel = t(if (state.online) "chatHeader.onlineTitle" else "chatHeader.offlineTitle"), role = Role.Button) { info = true }
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The empty chat already shows the boar large in the middle: one brand mark per screen.
                    if (state.messages.isNotEmpty()) Image(painterResource(R.drawable.mascot), null, Modifier.size(36.dp), contentScale = ContentScale.Fit)
                    Column {
                        Text("BOAR", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(12.dp))
                            Text(t("clean.chat.worksOffline") + " · " + subtitle, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onMenu) { Icon(Icons.Filled.Menu, contentDescription = t("chatHeader.openDrawer")) }
            },
            actions = {
                IconButton(onNewChat) { Icon(Icons.Filled.Add, contentDescription = t("clean.drawer.newChat")) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface, scrolledContainerColor = scheme.surfaceContainer,
                actionIconContentColor = scheme.onSurfaceVariant),
            scrollBehavior = scrollBehavior,
        )
        // Live speed in the header is a technical detail (Advanced tweaks → Show technical details).
        val tps = if (state.activity == Activity.Generating && state.technicalDetails) state.liveTokPerSec else null
        if (tps != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.background(scheme.secondaryContainer, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(6.dp).background(scheme.primary, CircleShape))
                Text("%.1f tok/s".format(tps), style = MaterialTheme.typography.labelSmall, color = scheme.onSecondaryContainer)
            }
        }
    }
}

private fun Modifier.describe(text: String): Modifier = semantics { contentDescription = text }

/**
 * The answer model and the phone's memory: it closed the app last time (with "Use less memory" and a way to pick another
 * model), or it may not fit (with "Load anyway" and another model). Nothing is blocked for good. Material's banner:
 * a tonal card with a leading icon, a short title and line, and text buttons at the end.
 */
@Composable
private fun LoadNoteBanner(
    note: team.sopa.boar.chat.LoadNote,
    onDismiss: () -> Unit,
    onRetryLessMemory: (String) -> Unit,
    onLoadAnyway: (String) -> Unit,
    onOpenModels: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The same short model name as the header ("Qwen2.5 · 1.5B"), so the title fits on one line.
    val name = modelName(note.label, technical = false)
    val change = t("clean.chat.loadNote.changeModel") to onOpenModels
    data class Copy(val title: String, val body: String, val actions: List<Pair<String, () -> Unit>>)
    val copy = when (note) {
        is team.sopa.boar.chat.LoadNote.Crashed -> {
            val retry = t("clean.chat.loadNote.lessMemory") to { onRetryLessMemory(note.modelId) }
            val title = t("clean.chat.loadNote.crashedTitle", "model" to name)
            if (note.fallbackLabel != null) Copy(title, t("clean.chat.loadNote.crashedFallback", "fallback" to note.fallbackLabel), listOf(change, retry))
            else Copy(title, t("clean.chat.loadNote.crashedBody"), listOf(change, retry))
        }
        is team.sopa.boar.chat.LoadNote.Risky -> Copy(t("clean.chat.loadNote.riskyTitle", "model" to name), t("clean.chat.loadNote.riskyBody"),
            listOf(change, t("native.chat.loadAnyway") to { onLoadAnyway(note.modelId) }))
    }
    Card(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh, contentColor = scheme.onSurface),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(BoarIcons.Memory, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(copy.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Text(copy.body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            IconButton(onDismiss, modifier = Modifier.offset(y = (-8).dp)) {
                Icon(Icons.Filled.Close, contentDescription = t("common.close"), tint = scheme.onSurfaceVariant)
            }
        }
        // Text buttons at the end, the safer choice last (closest to the thumb), as Material's banner places them.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            copy.actions.forEach { (label, action) -> TextButton(action) { Text(label, maxLines = 1) } }
        }
    }
}

/** Toast.tsx: below the header, fades in, gone after 1.8 s. Also on the Models and Hugging Face pages. */
@Composable
internal fun Toast(message: String, onHide: () -> Unit) {
    val alpha = remember(message) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(message) {
        alpha.animateTo(1f, androidx.compose.animation.core.tween(150))
        delay(1800)
        alpha.animateTo(0f, androidx.compose.animation.core.tween(200))
        onHide()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
        // Material's snackbar pair: inverseSurface with inverseOnSurface, so it follows the wallpaper and the theme.
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.graphicsLayer { this.alpha = alpha.value }.clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun LoadingBanner(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(scheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = scheme.onSecondaryContainer, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
    }
}

/**
 * components/ChatEmptyState.tsx: one focal point (the boar and one question) in the middle of the free space, and the
 * suggested questions as a grouped tonal list at the bottom, next to the thumb and the input (tap asks, long-press
 * fills). The topic labels over each question are gone: the questions read on their own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyState(suggestions: List<String>, onAsk: (String) -> Unit, onFill: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    // Scrolls: with a large font size (or a small screen) the last ideas were below the input and out of reach.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = minH).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Spacer(Modifier.height(0.dp))
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.mascot), null, Modifier.size(88.dp), contentScale = ContentScale.Fit)
                    Spacer(Modifier.height(16.dp))
                    Text(t("design.chat.emptyTitle"), style = type.headlineSmall, color = scheme.onSurface, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(t("chat.empty.tagline"), style = type.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                // Three short ideas, one line each: enough to show what to ask without outweighing the focal point.
                val ideas = suggestions.take(3)
                if (ideas.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t("chat.empty.suggestionsLabel"), style = type.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                    ideas.forEachIndexed { i, k ->
                        val q = t("chat.suggestions.$k")
                        // Tap asks right away, long-press puts it in the input to edit: both are named for TalkBack.
                        val ask = t("chat.empty.ask", "question" to q)
                        val fill = t("chat.empty.fill")
                        Text(q, style = type.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(groupedShape(i, ideas.size, outer = 20.dp)).background(scheme.surfaceContainer)
                                .combinedClickable(onClickLabel = ask, onLongClickLabel = fill, role = Role.Button, onClick = { onAsk(q) }, onLongClick = { onFill(q) })
                                .heightIn(min = 56.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 16.dp, vertical = 12.dp))
                    }
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
private fun Bubble(m: UiMessage, state: ChatState, onReuse: (String) -> Unit, onRedo: (String, Int) -> Unit, onRetry: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Boar.colors
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val user = m.role == "user"
    val streaming = state.answeringId == m.id
    var reasoningShown by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val researched = !user && m.receipt?.path == "deep"
    // The question: a tonal bubble at the end (surfaceContainerHigh, the corner at its speaker squared a little), up to
    // 85 % of the width; held, it goes back to the input. The answer: no bubble, the text straight on the surface at
    // the full readable width, under a small byline (the boar, or Deep Research in its tertiary color).
    if (user) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            Text(m.text, style = type.bodyLarge, color = scheme.onSurface,
                modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * 0.85f).padding(start = 32.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp))
                    .background(scheme.surfaceContainerHigh)
                    .combinedClickable(onClick = {}, onLongClick = { onReuse(m.text) })
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }
        return
    }
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (researched) Icon(BoarIcons.ScienceFilled, null, tint = scheme.tertiary, modifier = Modifier.size(20.dp))
                else Image(painterResource(R.drawable.mascot), null, Modifier.size(24.dp), contentScale = ContentScale.Fit)
                Text(if (researched) t("native.chat.roleDeepResearch") else t("chatScreen.roleAssistant"), style = type.labelLarge,
                    color = if (researched) scheme.tertiary else scheme.onSurfaceVariant)
            }
            val split = AnswerText.splitThinking(m.text)
            // Sources are attached when the reply finishes, so invented citations are cleaned only then.
            val shown = if (streaming) split.answer else AnswerText.splitInlineBullets(AnswerText.cleanCitations(split.answer, m.sources.size))
            val reasoningStyle = Modifier.drawBehind { drawRect(c.borderDefault, size = size.copy(width = 2.dp.toPx())) }.padding(start = 8.dp, bottom = 8.dp)
            when {
                m.text.isEmpty() && streaming -> ProcessingIndicator(if (state.stopping) t("native.chat.stopping") else activityLabel(state.activity))
                split.thinkingInProgress && split.answer.isEmpty() && streaming -> ThinkingTail(split.thinking.orEmpty(), reasoningStyle)
                // The reasoning used the whole answer budget: the note points at the reasoning button, which shows what it reasoned.
                split.thinkingInProgress && split.answer.isEmpty() ->
                    Text(if (reasoningShown && split.thinking != null) split.thinking!! else t("chatScreen.reasoningUnfinished"),
                        fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant, modifier = reasoningStyle)
                else -> {
                    if (reasoningShown && split.thinking != null) Text(split.thinking!!, fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant, modifier = reasoningStyle)
                    MarkdownMessage(shown, streaming)
                }
            }
            // The answer failed (no model, an engine or storage error): a plain line, never the exception text.
            // Kept on the message (UiMessage.error), so it stays after the next question; the live error covers a failure
            // before the answer could be saved.
            val errorKey = m.error ?: state.error?.takeIf { state.messages.lastOrNull()?.id == m.id }?.let(UserErrors::chatKey)
            if (!streaming && !m.stopped && errorKey != null) {
                Text(t(errorKey), style = type.bodyLarge, color = scheme.error)
                if ((errorKey == UserErrors.chatKey(UserErrors.EMPTY_ANSWER) || errorKey == UserErrors.INTERRUPTED_KEY) && state.answeringId == null)
                    RedoButton(t("native.chat.tryAgain")) { onRetry(m.id) }
                // Holding a question already copies it back to the input (onReuse); say so where it helps.
                Text(t("native.chat.holdToReuse"), style = type.bodySmall, color = scheme.onSurface, modifier = Modifier.padding(top = 2.dp))
            }
            // Stopped before writing anything: these are what the search found, not what the answer cites.
            // BOAR answered in another style than the one picked (a health question under Detailed): say so, once, under it.
            if (!streaming) m.receipt?.styleNote?.let { Text(t("native.chat.styleNote.$it"), style = type.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
            if (m.sources.isNotEmpty()) SourceFootnotes(m.sources, if (m.stopped && !streaming && shown.isBlank()) t("native.chat.foundBeforeStop") else null, state.technicalDetails)
            if (m.stopped) NoteBadge(BoarIcons.StopCircle, t("chatScreen.stoppedByUser"))
            if (m.timedOut) NoteBadge(BoarIcons.Timer, t("chatScreen.stageTimedOut"))
            m.cutAt?.let { limit ->
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NoteBadge(BoarIcons.ContentCut, t("native.chat.cutAtLimit", "limit" to limit))
                    if (m.nextLimit != null && state.answeringId == null) RedoButton(t("native.chat.allowMore", "limit" to m.nextLimit)) { onRedo(m.id, m.nextLimit) }
                }
            }
            var receiptOpen by remember { mutableStateOf(false) }
            if (m.text.isNotEmpty() && !streaming) {
                // The actions first, the icon lined up with the text edge; the receipt after them, quiet, never cut.
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).offset(x = (-12).dp), horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
                    // With the reasoning open, it is copied too: reviewing a thinking model needs both.
                    IconButton({
                        clipboard.setText(AnnotatedString(if (reasoningShown && split.thinking != null) "💭 ${split.thinking}\n\n---\n\n$shown" else shown)); copied = true
                    }) {
                        Icon(if (copied) Icons.Filled.Check else BoarIcons.ContentCopy, contentDescription = t("chatScreen.copyResponse"),
                            tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    if (split.thinking != null) IconToggleButton(reasoningShown, { reasoningShown = !reasoningShown }) {
                        Icon(BoarIcons.Psychology, contentDescription = t(if (reasoningShown) "chatScreen.hideReasoning" else "chatScreen.showReasoning"),
                            tint = if (reasoningShown) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    // Everyone sees time and speed; the measured details open only with technical details on.
                    Box(Modifier.weight(1f, fill = false)) {
                        m.receipt?.let { r -> if (state.technicalDetails) ReceiptToggle(r, receiptOpen) { receiptOpen = !receiptOpen } else ReceiptShort(r) }
                    }
                }
                if (receiptOpen && state.technicalDetails) m.receipt?.let { r -> ReceiptDetails(r) }
            }
        }
    }
}

private fun receiptShort(r: Receipt, rate: String) = listOf(ReceiptFormat.seconds(r.totalMs), rate).joinToString(" · ")

/** The receipt everyone sees, quiet: "18 s · 10.4 tok/s" (time and speed stay visible by r4to's choice, c1). */
@Composable
private fun ReceiptShort(r: Receipt) =
    Text(receiptShort(r, t("chat.receipt.speed", "rate" to ReceiptFormat.rate(r.tokPerSec))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Two lines with Large text rather than losing "tok/s" off the end.
        maxLines = 2, textAlign = TextAlign.Start, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))

/** The answer's receipt (main's ReceiptToggle): time and speed, tapped open for the measured details. */
@Composable
private fun ReceiptToggle(r: Receipt, open: Boolean, onToggle: () -> Unit) {
    val short = receiptShort(r, t("chat.receipt.speed", "rate" to ReceiptFormat.rate(r.tokPerSec)))
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.minimumInteractiveComponentSize().clickable(onClickLabel = t("chat.receipt.details"), role = Role.Button, onClick = onToggle).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(short, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 2, textAlign = TextAlign.Start, modifier = Modifier.weight(1f, fill = false))
        Icon(if (open) BoarIcons.ExpandLess else BoarIcons.ExpandMore, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ReceiptDetails(r: Receipt) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        receiptRows(r).forEach { (label, value) -> Text("$label: $value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val tail = thinking.takeLast(400).let { t -> if (t.length < thinking.length) t.substringAfter(' ', t) else t }.replace(Regex("\\s+"), " ").trim()
    val box = with(androidx.compose.ui.platform.LocalDensity.current) { (17.sp * 3).toDp() }
    // The icon stays put at the top left while it reasons; only the text beside it scrolls.
    Row(modifier.fillMaxWidth().height(box), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(BoarIcons.Psychology, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            // Taller than the box and bottom-aligned: the newest line stays at the bottom, older ones slide out at the top.
            Text(tail, fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = tint,
                modifier = Modifier.align(Alignment.BottomStart).wrapContentHeight(Alignment.Bottom, unbounded = true))
        }
    }
}

/** ProcessingIndicator.tsx: the stage line while nothing has streamed yet. */
@Composable
private fun ProcessingIndicator(label: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = scheme.primary, strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

/** "Try again" and "Allow more and redo": a tonal button with the refresh icon. */
@Composable
private fun RedoButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

/** A note under the answer (stopped, timed out, cut at the limit): a small container with its icon. */
@Composable
private fun NoteBadge(icon: ImageVector, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 4.dp).background(scheme.secondaryContainer, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer)
    }
}

/**
 * The composer: one rounded surfaceContainerHigh block on one line: the Answer options button (it opens the sheet with
 * Style, Model, Sources and Deep Research), the text field, and send or stop. No chips: anything not at its default
 * shows as the options button's tonal fill and dot ("Answer options, changed"); Deep Research keeps the block's tertiary
 * outline and its own placeholder, and My files its own placeholder. Canvas F1–F5 (V4 field without chips, r4to Oct 22).
 */
@Composable
private fun InputBar(
    text: String, onText: (String) -> Unit, answering: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    stopping: Boolean, deepResearch: Boolean, sourcesOwn: Boolean, optionsChanged: Boolean, onOpenOptions: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val hint = t(when {
        deepResearch -> "native.chat.deepResearchPlaceholder"
        sourcesOwn -> "design.options.placeholderOwn"
        else -> "chatScreen.inputPlaceholder"
    })
    val canSend = text.isNotBlank() && !answering
    // After Stop, the button pulses until the answer really ends: a library search or a chunk of prompt finishes first.
    val transition = rememberInfiniteTransition(label = "stopping")
    val blink by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween<Float>(500), RepeatMode.Reverse), label = "pulse")
    val pulse = if (stopping) blink else 1f
    val optionsLabel = t(if (optionsChanged) "design.options.buttonChanged" else "design.options.button")
    // The gesture bar inset sits under the page background; the block itself keeps 12 dp from the edges.
    Column(Modifier.fillMaxWidth().background(scheme.background).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerHigh)
                // Deep Research on: the block's outline in the mode's color and its own placeholder, right where you type.
                .then(if (deepResearch) Modifier.border(1.5.dp, scheme.tertiary, shape) else Modifier)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onOpenOptions,
                    colors = if (optionsChanged) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant)) {
                    Icon(BoarIcons.Tune, contentDescription = optionsLabel)
                }
                // The dot: something differs from the defaults (the sheet's Reset to defaults puts it back).
                if (optionsChanged) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(scheme.primary))
            }
            TextField(
                text, onText,
                Modifier.weight(1f).describe(hint),
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (deepResearch) scheme.tertiary else scheme.onSurfaceVariant) },
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = scheme.primary),
            )
            // Stop is error/onError, send is primary/onPrimary: real scheme pairs in both themes. The disabled send state is
            // the stock one (onSurface at 12 % / 38 %); the pulse after Stop applies only while the button is live.
            FilledIconButton(
                { if (answering) onStop() else onSend() },
                Modifier.alpha(if (answering || canSend) pulse else 1f),
                enabled = answering || canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (answering) scheme.error else scheme.primary, contentColor = if (answering) scheme.onError else scheme.onPrimary),
            ) {
                Icon(if (answering) BoarIcons.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = t(if (answering) "chatScreen.stopGeneration" else "chatScreen.sendMessage"), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Runs [ask] once per install on Android 13+, when BOAR may not post notifications yet (issue #91). */
private fun askNotificationsOnce(ctx: android.content.Context, ask: () -> Unit) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
    val prefs = ctx.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("notificationsAsked", false)) return
    prefs.edit().putBoolean("notificationsAsked", true).apply()
    ask()
}
