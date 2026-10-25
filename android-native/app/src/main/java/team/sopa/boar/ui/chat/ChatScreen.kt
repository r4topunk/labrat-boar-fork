package team.sopa.boar.ui.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.LineHeightStyle
import team.sopa.boar.ui.components.BoarMark
import team.sopa.boar.ui.theme.IconSize
import team.sopa.boar.ui.theme.onTextLine
import team.sopa.boar.ui.theme.textOnRowCenter
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import team.sopa.boar.chat.Activity
import team.sopa.boar.chat.ChatState
import team.sopa.boar.chat.Receipt
import team.sopa.boar.chat.ReceiptFormat
import team.sopa.boar.chat.UiMessage
import team.sopa.boar.engine.UserErrors
import team.sopa.boar.engine.answer.AnswerText
import team.sopa.boar.engine.answer.AnswerNote
import team.sopa.boar.ui.I18n
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import team.sopa.boar.ui.theme.FixedSizeChrome

/**
 * The chat on Material 3: a TopAppBar with the mascot, the bubbles, the loading strip, the empty state and the input
 * bar (options button, text field, send or stop). Chrome text uses the typography roles and the colour scheme; tertiary is
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
    /** The cut answer's "Allow more and redo": its id and the higher limit. */
    onRetry: (String) -> Unit = {},
    /** The memory note (LoadNote): close it, and its actions by model id. */
    onDismissLoadNote: () -> Unit = {},
    onRetryLessMemory: (String) -> Unit = {},
    onLoadAnyway: (String) -> Unit = {},
    onReused: () -> Unit = {},
    onToastHidden: () -> Unit = {},
    /**
     * Answer options: the model to answer with, Sources (true = Only my files, false = Everything; a checklist row
     * ticked or unticked by id; the list read again when the sheet opens), and Reset to defaults.
     */
    onSetSourcesOwn: (Boolean) -> Unit = {},
    onToggleSource: (String) -> Unit = {},
    onRefreshSources: () -> Unit = {},
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
    if (toneSheet) AnswerOptionsSheet(state, AnswerOptionsActions(onSetSourcesOwn, onResetOptions, onToggleSource, onRefreshSources)) { toneSheet = false }
    val last = state.messages.lastOrNull()
    // Follow the end of the answer while it streams, unless the user scrolled up to read: a drag that ends short of
    // the bottom stops following, reaching the bottom again resumes it, and a new message always does.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving -> if (!moving) follow = !list.canScrollForward }
    }
    LaunchedEffect(state.messages.size) { follow = true }
    // Jump to the end of the last message (the offset is clamped to the list's end); not animated, since this runs on
    // every streamed token. Requested from the composition that shows the token, so the list lands in that same frame:
    // a scrollToItem launched after it drew a second frame per token (the text, then the scroll).
    val count = state.messages.size
    val lastLength = last?.text?.length ?: 0
    val followed = remember { intArrayOf(-1, -1) }
    val scrolling = list.isScrollInProgress
    SideEffect {
        if (follow && !scrolling && count > 0 && (followed[0] != count || followed[1] != lastLength)) {
            followed[0] = count; followed[1] = lastLength
            list.requestScrollToItem(count - 1, Int.MAX_VALUE / 2)
        }
    }
    // Back at the end after a drag: follow again from where the finger left it.
    LaunchedEffect(follow, scrolling) { if (follow && !scrolling && count > 0) list.scrollToItem(count - 1, Int.MAX_VALUE / 2) }
    // The bar tints to surfaceContainer once the messages scroll under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        // The status and navigation bar insets are applied inside the TopAppBar and InputBar, under their backgrounds, so the
        // bars show their own surface instead of a band of the page color; the keyboard still lifts the whole column.
        Column(Modifier.fillMaxSize().imePadding().nestedScroll(barScroll.nestedScrollConnection)) {
            // Header and bubbles take only what they show: [state] is a new object on every streamed token, so passing it
            // whole re-ran the app bar and every earlier answer (its Markdown included) per token.
            FixedSizeChrome {
                Header(state.liveTokPerSec.takeIf { state.activity == Activity.Generating && state.technicalDetails }, onMenu, onNewChat, barScroll)
            }
            state.loadingKey?.let { LoadingBanner(t(it)) } ?: run { if (state.modelLoading) LoadingBanner(t("clean.chat.loadingModel")) }
            state.loadNote?.let { LoadNoteBanner(it, onDismissLoadNote, onRetryLessMemory, onLoadAnyway) }
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
                        val streaming = state.answeringId == m.id
                        Bubble(m, streaming, busy = state.answeringId != null, stopping = streaming && state.stopping,
                            activity = if (streaming) state.activity else null,
                            liveError = state.error?.takeIf { state.messages.lastOrNull()?.id == m.id }, technicalDetails = state.technicalDetails,
                            onReuse = { input = it; onReused() }, onRetry = onRetry, modifier = Modifier.animateItem())
                    }
                }
            }
            InputBar(input, { input = it }, state.answeringId != null, onSend = {
                // The answer gets the whole screen: the keyboard closes on send.
                send(input); input = ""; keyboard?.hide(); focus.clearFocus()
            }, onStop, state.stopping, state.sourcesOwn, state.optionsChanged) { toneSheet = true }
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
 * A small TopAppBar: menu, the name alone and New chat (r4to 2026-10-24: no mark and no "Works offline · model" line,
 * which also cut off with Large text). Tapping the name still opens what working offline means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(
    tps: Double?, onMenu: () -> Unit, onNewChat: () -> Unit, scrollBehavior: TopAppBarScrollBehavior,
) {
    val scheme = MaterialTheme.colorScheme
    var info by remember { mutableStateOf(false) }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        confirmButton = { TextButton({ info = false }) { Text(t("common.close")) } },
        title = { Text(t("chatHeader.offlineTitle")) },
        text = { Text(t("chatHeader.offlineBody")) },
    )
    Column {
        TopAppBar(
            title = {
                Text("BoarLite", style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    modifier = Modifier.clip(MaterialTheme.shapes.medium)
                        .clickable(onClickLabel = t("chatHeader.offlineTitle"), role = Role.Button) { info = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp))
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
        // Live speed in the header ([tps]) is a technical detail (Advanced tweaks → Show technical details).
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

/** The answer footer's pull-back: the 48 dp copy button's padding (14 dp around a 20 dp icon) plus ContentCopy's ink inset. */
private val FooterPullBack = (-15.5).dp

private fun Modifier.describe(text: String): Modifier = semantics { contentDescription = text }

/**
 * The answer model and the phone's memory: it closed the app last time (with "Use less memory"), or it may not fit
 * (with "Load anyway"). Nothing is blocked for good. Material's banner:
 * a tonal card with a leading icon, a short title and line, and text buttons at the end.
 */
@Composable
private fun LoadNoteBanner(
    note: team.sopa.boar.chat.LoadNote,
    onDismiss: () -> Unit,
    onRetryLessMemory: (String) -> Unit,
    onLoadAnyway: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The same short model name as the header ("Qwen3 · 1.7B"), so the title fits on one line.
    val name = modelName(note.label, technical = false)
    data class Copy(val title: String, val body: String, val actions: List<Pair<String, () -> Unit>>)
    val copy = when (note) {
        is team.sopa.boar.chat.LoadNote.Crashed -> {
            val retry = t("clean.chat.loadNote.lessMemory") to { onRetryLessMemory(note.modelId) }
            val title = t("clean.chat.loadNote.crashedTitle", "model" to name)
            if (note.fallbackLabel != null) Copy(title, t("clean.chat.loadNote.crashedFallback", "fallback" to note.fallbackLabel), listOf(retry))
            else Copy(title, t("clean.chat.loadNote.crashedBody"), listOf(retry))
        }
        is team.sopa.boar.chat.LoadNote.Risky -> Copy(t("clean.chat.loadNote.riskyTitle", "model" to name), t("clean.chat.loadNote.riskyBody"),
            listOf(t("native.chat.loadAnyway") to { onLoadAnyway(note.modelId) }))
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

/** Toast.tsx: below the header, fades in, gone after 1.8 s. */
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
        // Material's snackbar pair: inverseSurface with inverseOnSurface, so it follows the color scheme and the theme.
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
                    BoarMark(null, Modifier.size(88.dp), contentScale = ContentScale.Fit)
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
    null -> ""
}

/** A message bubble (ChatScreen.tsx styles.bubble / userBubble / assistantBubble). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: UiMessage,
    /** This is the answer being written, BOAR is answering (any message), and Stop was tapped on it. */
    streaming: Boolean, busy: Boolean, stopping: Boolean,
    /** The stage of the answer being written (null for the others). */
    activity: Activity?,
    /** The live error, on the last message only (before the answer could be saved). */
    liveError: String?,
    technicalDetails: Boolean,
    onReuse: (String) -> Unit, onRetry: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val c = Boar.colors
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val user = m.role == "user"
    var reasoningShown by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // The question: a tonal bubble at the end (surfaceContainerHigh, the corner at its speaker squared a little), up to
    // 85 % of the width; held, it goes back to the input. The answer: no bubble, the text straight on the surface at
    // the full readable width, under a small byline (the boar).
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
                // The label alone, no mark (r4to 2026-10-24).
                Text(t("chatScreen.roleAssistant"), style = type.labelLarge, color = scheme.onSurfaceVariant)
            }
            val split = AnswerText.splitThinking(m.text)
            // Sources are attached when the reply finishes, so invented citations are cleaned only then.
            val shown = if (streaming) split.answer else AnswerText.splitInlineBullets(AnswerText.cleanCitations(split.answer, m.sources.size))
            val reasoningStyle = Modifier.drawBehind { drawRect(c.borderDefault, size = size.copy(width = 2.dp.toPx())) }.padding(start = 8.dp, bottom = 8.dp)
            when {
                m.text.isEmpty() && streaming -> ProcessingIndicator(if (stopping) t("native.chat.stopping") else activityLabel(activity))
                split.thinkingInProgress && split.answer.isEmpty() && streaming -> ThinkingTail(split.thinking.orEmpty(), reasoningStyle)
                // The reasoning used the whole answer budget: the note points at the reasoning button, which shows what it reasoned.
                split.thinkingInProgress && split.answer.isEmpty() ->
                    Text(if (reasoningShown && split.thinking != null) split.thinking!! else t("chatScreen.reasoningUnfinished"),
                        fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant, modifier = reasoningStyle)
                else -> {
                    if (reasoningShown && split.thinking != null) Text(split.thinking!!, fontSize = 12.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant, modifier = reasoningStyle)
                    MarkdownMessage(shown, streaming)
                    // Every answer the model wrote ends with one quiet line (AnswerNote), under it, once it is whole: read after
                    // the answer and above its time, speed and sources. Small secondary text, not a banner; not part of the saved text.
                    if (!streaming && m.receipt?.note == true) Text(AnswerNote.LINE, style = type.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            // The answer failed (no model, an engine or storage error): a plain line, never the exception text.
            // Kept on the message (UiMessage.error), so it stays after the next question; the live error covers a failure
            // before the answer could be saved.
            val errorKey = m.error ?: liveError?.let(UserErrors::chatKey)
            if (!streaming && !m.stopped && errorKey != null) {
                Text(t(errorKey), style = type.bodyLarge, color = scheme.error)
                if ((errorKey == UserErrors.chatKey(UserErrors.EMPTY_ANSWER) || errorKey == UserErrors.INTERRUPTED_KEY) && !busy)
                    RedoButton(t("native.chat.tryAgain")) { onRetry(m.id) }
                // Holding a question already copies it back to the input (onReuse); say so where it helps.
                Text(t("native.chat.holdToReuse"), style = type.bodySmall, color = scheme.onSurface, modifier = Modifier.padding(top = 2.dp))
            }
            // Stopped before writing anything: these are what the search found, not what the answer cites.
            if (m.stopped) NoteBadge(BoarIcons.StopCircle, t("chatScreen.stoppedByUser"))
            if (m.timedOut) NoteBadge(BoarIcons.Timer, t("chatScreen.stageTimedOut"))
            m.cutAt?.let { limit ->
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NoteBadge(BoarIcons.ContentCut, t("native.chat.cutAtLimit", "limit" to limit))
                }
            }
            var receiptOpen by remember { mutableStateOf(false) }
            if (m.text.isNotEmpty() && !streaming) {
                // The actions first, the icon lined up with the text edge; the receipt after them, quiet, never cut.
                // The 48 dp button pulls back by its padding plus the glyph's own inset, so the copy ink meets the text edge.
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).offset(x = FooterPullBack), horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
                    // With the reasoning open, it is copied too: reviewing a thinking model needs both.
                    IconButton({
                        clipboard.setText(AnnotatedString(if (reasoningShown && split.thinking != null) "💭 ${split.thinking}\n\n---\n\n$shown" else shown)); copied = true
                    }) {
                        Icon(if (copied) Icons.Filled.Check else BoarIcons.ContentCopy, contentDescription = t("chatScreen.copyResponse"),
                            tint = scheme.onSurfaceVariant, modifier = Modifier.size(IconSize.InlineAction))
                    }
                    if (split.thinking != null) IconToggleButton(reasoningShown, { reasoningShown = !reasoningShown }) {
                        Icon(BoarIcons.Psychology, contentDescription = t(if (reasoningShown) "chatScreen.hideReasoning" else "chatScreen.showReasoning"),
                            tint = if (reasoningShown) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(IconSize.InlineAction))
                    }
                    // Everyone sees time and speed; the measured details open only with technical details on.
                    // The receipt about 12 dp from the icon's ink (the button's own padding would leave ~18 dp).
                    Box(Modifier.weight(1f, fill = false).offset(x = (-6).dp)) {
                        m.receipt?.let { r -> if (technicalDetails) ReceiptToggle(r, receiptOpen) { receiptOpen = !receiptOpen } else ReceiptShort(r) }
                    }
                }
                if (receiptOpen && technicalDetails) m.receipt?.let { r -> ReceiptDetails(r) }
            }
            // The sources last, under the time and speed (r4to 2026-10-24: the receipt reads with the answer, above its sources).
            if (m.sources.isNotEmpty()) SourceFootnotes(m.sources, if (m.stopped && !streaming && shown.isBlank()) t("native.chat.foundBeforeStop") else null, technicalDetails)
        }
    }
}

private fun receiptShort(r: Receipt, rate: String) = listOf(ReceiptFormat.seconds(r.totalMs), rate).joinToString(" · ")

/** The receipt everyone sees, quiet: "18 s · 10.4 tok/s" (time and speed stay visible by r4to's choice, c1). */
@Composable
private fun ReceiptShort(r: Receipt) =
    Text(receiptShort(r, t("chat.receipt.speed", "rate" to ReceiptFormat.rate(r.tokPerSec))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Two lines with Large text rather than losing "tok/s" off the end.
        maxLines = 2, textAlign = TextAlign.Start, modifier = Modifier.padding(vertical = 8.dp).textOnRowCenter(MaterialTheme.typography.bodySmall))

/** The answer's receipt (main's ReceiptToggle): time and speed, tapped open for the measured details. */
@Composable
private fun ReceiptToggle(r: Receipt, open: Boolean, onToggle: () -> Unit) {
    val short = receiptShort(r, t("chat.receipt.speed", "rate" to ReceiptFormat.rate(r.tokPerSec)))
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.minimumInteractiveComponentSize().clickable(onClickLabel = t("chat.receipt.details"), role = Role.Button, onClick = onToggle).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(short, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 2, textAlign = TextAlign.Start, modifier = Modifier.weight(1f, fill = false))
        Icon(if (open) BoarIcons.ExpandLess else BoarIcons.ExpandMore, contentDescription = null, tint = tint,
            modifier = Modifier.size(IconSize.Meta).onTextLine(MaterialTheme.typography.labelSmall))
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
    // Mode first: the answer style, then how it was written (from this chat, adaptive routing and its plan).
    val mode = r.style?.let { style ->
        listOfNotNull(
            t("native.receipt.style.$style").takeUnless { it == "native.receipt.style.$style" } ?: style,  // an unknown style shows its id
            when (r.path) {
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
        Icon(BoarIcons.Psychology, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 0.5.dp).size(IconSize.Meta))
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
    // The spinner in the byline's 24 dp slot with its 8 dp gap, so the stage text starts on the label's column.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(IconSize.Standard).onTextLine(MaterialTheme.typography.bodyMedium), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), color = scheme.primary, strokeWidth = 2.dp)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

/** "Try again" and "Allow more and redo": a tonal button with the refresh icon. */
@Composable
private fun RedoButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize).onTextLine(MaterialTheme.typography.labelLarge))
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
        Icon(icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(IconSize.Meta).onTextLine(MaterialTheme.typography.labelMedium))
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer)
    }
}

/**
 * The composer: one rounded surfaceContainerHigh block on one line: the Answer options button (it opens the sheet with
 * Model and Sources), the text field, and send or stop. No chips: anything not at its default shows as the options
 * button's tonal fill and dot ("Answer options, changed"); My files has its own placeholder. Canvas F1–F5 (V4 field
 * without chips, r4to Oct 22).
 */
@Composable
private fun InputBar(
    text: String, onText: (String) -> Unit, answering: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    stopping: Boolean, sourcesOwn: Boolean, optionsChanged: Boolean, onOpenOptions: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val hint = t(if (sourcesOwn) "design.options.placeholderOwn" else "chatScreen.inputPlaceholder")
    val canSend = text.isNotBlank() && !answering
    // After Stop, the button pulses until the answer really ends: a library search or a chunk of prompt finishes first.
    // The infinite transition exists only then: composed always, it ran on every frame (13 % of the main thread
    // while an answer streamed, and a wake-up per vsync at rest) for a value read only while stopping.
    val pulse: () -> Float = if (stopping) {
        val transition = rememberInfiniteTransition(label = "stopping")
        val blink = transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween<Float>(500), RepeatMode.Reverse), label = "pulse")
        ({ blink.value })
    } else ({ 1f })
    val optionsLabel = t(if (optionsChanged) "design.options.buttonChanged" else "design.options.button")
    // The gesture bar inset sits under the page background; the block itself keeps 12 dp from the edges.
    Column(Modifier.fillMaxWidth().background(scheme.background).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerHigh)
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
            // A bare field in a 48 dp box: one line sits on the buttons' centreline (its x-height, not its line box), and
            // the text starts ~15 dp after the options glyph. TextField's 56 dp box and 16 dp inset pushed it up and away.
            // Every line keeps its full 24 sp box (no first/last-line trim), so the last line of a long question sits on the
            // buttons' centreline exactly as a single line does (trimmed, the last line dropped ~1.3 dp under it).
            val bodyLarge = MaterialTheme.typography.bodyLarge.copy(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
            BasicTextField(
                text, onText,
                Modifier.weight(1f).describe(hint),
                textStyle = bodyLarge.copy(color = scheme.onSurface),
                maxLines = 6,
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 48.dp).padding(end = 8.dp, top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.textOnRowCenter(bodyLarge)) {
                            if (text.isEmpty()) Text(hint, style = bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = scheme.onSurfaceVariant)
                            inner()
                        }
                    }
                },
            )
            // Stop is error/onError, send is primary/onPrimary: real scheme pairs in both themes. The disabled send state is
            // the stock one (onSurface at 12 % / 38 %); the pulse after Stop applies only while the button is live.
            FilledIconButton(
                { if (answering) onStop() else onSend() },
                // Read in the draw layer, so the pulse redraws the button without recomposing the bar.
                Modifier.graphicsLayer { alpha = if (answering || canSend) pulse() else 1f },
                enabled = answering || canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (answering) scheme.error else scheme.primary, contentColor = if (answering) scheme.onError else scheme.onPrimary),
            ) {
                Icon(if (answering) BoarIcons.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = t(if (answering) "chatScreen.stopGeneration" else "chatScreen.sendMessage"))
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
