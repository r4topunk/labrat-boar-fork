package team.sopa.boar.ui.chat

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
) {
    val scheme = MaterialTheme.colorScheme
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
    // The bar tints to surfaceContainer once the messages scroll under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        // The status and navigation bar insets are applied inside the TopAppBar and InputBar, under their backgrounds, so the
        // bars show their own surface instead of a band of the page color; the keyboard still lifts the whole column.
        Column(Modifier.fillMaxSize().imePadding().nestedScroll(barScroll.nestedScrollConnection)) {
            FixedSizeChrome { Header(state, onMenu, onNewChat, barScroll) }
            state.loadingKey?.let { LoadingBanner(t(it)) }
            state.loadNote?.let { LoadNoteBanner(it, onDismissLoadNote, onRetryLessMemory, onLoadAnyway, onOpenModels) }
            // Deep Research on: the chat area takes a light tint of its color, so the mode shows at a glance.
            val researchTint = if (state.deepResearch) scheme.tertiary.copy(alpha = 0.06f) else Color.Transparent
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
                    items(state.messages, key = { it.id }) { m ->
                        Bubble(m, state, onReuse = { input = it; onReused() }, onRedo = onRedo, onRetry = onRetry, modifier = Modifier.animateItem())
                    }
                }
            }
            InputBar(input, { input = it }, state.answeringId != null, onSend = {
                // The answer gets the whole screen: the keyboard closes on send.
                onSend(input); input = ""; keyboard?.hide(); focus.clearFocus()
            }, onStop, state.personalityId, state.stopping, state.deepResearch, onSetDeepResearch) { toneSheet = true }
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.mascot), null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                    Column {
                        Text("BOAR", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            navigationIcon = {
                IconButton(onMenu) { Icon(Icons.Filled.Menu, contentDescription = t("chatHeader.openDrawer")) }
            },
            actions = {
                // The same on-device claim in every state; the chip opens what being online means for BOAR.
                AssistChip(
                    onClick = { info = true },
                    label = { Text(t("clean.chat.worksOffline"), maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = scheme.secondaryContainer, labelColor = scheme.onSecondaryContainer, leadingIconContentColor = scheme.onSecondaryContainer),
                    border = null,
                )
                FilledTonalIconButton(onNewChat) { Icon(Icons.Filled.Add, contentDescription = t("clean.drawer.newChat")) }
                Spacer(Modifier.width(4.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface, scrolledContainerColor = scheme.surfaceContainer),
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
    val scheme = MaterialTheme.colorScheme
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
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.amber400, modifier = Modifier.weight(1f))
            IconButton(onDismiss, colors = IconButtonDefaults.iconButtonColors(contentColor = c.amber400)) {
                Icon(Icons.Filled.Close, contentDescription = t("common.close"))
            }
        }
        // The first action is the recommended one: filled. Both share the width and the height of the taller label.
        // The amber tokens are the tertiary role, so the filled button's text is onTertiary.
        val shape = MaterialTheme.shapes.small
        val pad = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEachIndexed { i, (label, action) ->
                val mod = Modifier.weight(1f).fillMaxHeight()
                val labelText = @Composable { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
                if (i == 0) Button(action, mod, shape = shape, contentPadding = pad, content = { labelText() },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.tertiary, contentColor = scheme.onTertiary))
                else OutlinedButton(action, mod, shape = shape, contentPadding = pad, content = { labelText() },
                    border = BorderStroke(1.dp, c.amberBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.amber400))
            }
        }
    }
    HorizontalDivider(color = c.amberBorder)
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

/** components/ChatEmptyState.tsx: the boar, the name and tagline, then the suggested questions (tap asks, long-press fills). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyState(suggestions: List<String>, onAsk: (String) -> Unit, onFill: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    // Scrolls: with a large font size (or a small screen) the last ideas were below the input and out of reach.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.mascot), null, Modifier.size(96.dp), contentScale = ContentScale.Fit)
            Text("BOAR", style = type.headlineMedium, color = scheme.onSurface)
            Text(t("chat.empty.tagline"), style = type.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (suggestions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("chat.empty.suggestionsLabel"), style = type.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            for (k in suggestions) {
                val q = t("chat.suggestions.$k")
                // Tap asks right away, long-press puts it in the input to edit: both are named for TalkBack.
                val ask = t("chat.empty.ask", "question" to q)
                val fill = t("chat.empty.fill")
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column(
                        Modifier.fillMaxWidth().combinedClickable(onClickLabel = ask, onLongClickLabel = fill, role = Role.Button, onClick = { onAsk(q) }, onLongClick = { onFill(q) })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(t("chat.suggestionTopics.$k"), style = type.labelMedium, color = scheme.primary)
                        // Two lines at most, so five ideas don't crowd the empty chat.
                        Text(q, style = type.bodyMedium, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    val shape = if (user) RoundedCornerShape(20.dp, 20.dp, 8.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 8.dp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxW = maxWidth * 0.92f
        var mod = Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart).widthIn(min = if (user) 0.dp else 200.dp, max = maxW).clip(shape)
            .background(if (user) c.bgCardHover else c.bgSurface)
        // A Deep Research answer keeps its mark in the history: the border and the header in the mode's color.
        val researched = !user && m.receipt?.path == "deep"
        if (!user) mod = mod.border(if (researched) 1.5.dp else 1.dp, if (researched) scheme.tertiary else scheme.outlineVariant, shape)
        if (user) mod = mod.combinedClickable(onClick = {}, onLongClick = { onReuse(m.text) })
        Column(mod.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!user) Image(painterResource(R.drawable.mascot), null, Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Fit)
                Text(if (user) t("chatScreen.roleYou") else if (researched) t("native.chat.roleDeepResearch") else t("chatScreen.roleAssistant"), style = type.labelMedium,
                    color = if (user) scheme.onSurfaceVariant else if (researched) scheme.tertiary else scheme.primary)
            }
            if (user) {
                Text(m.text, style = type.bodyLarge, color = scheme.onSurface)
                return@Column
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
                // The sources block has its own rule above it; a second one right under would stack.
                if (m.sources.isEmpty()) HorizontalDivider(Modifier.padding(top = 8.dp), color = scheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The receipt fills the gap before the reasoning and copy buttons (main's ReceiptToggle), tapped open below.
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        // Everyone sees time and speed; the measured details open only with technical details on.
                        m.receipt?.let { r -> if (state.technicalDetails) ReceiptToggle(r, receiptOpen) { receiptOpen = !receiptOpen } else ReceiptShort(r) }
                    }
                    if (split.thinking != null) IconToggleButton(reasoningShown, { reasoningShown = !reasoningShown }) {
                        Icon(BoarIcons.Psychology, contentDescription = t(if (reasoningShown) "chatScreen.hideReasoning" else "chatScreen.showReasoning"))
                    }
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
                    // With the reasoning open, it is copied too: reviewing a thinking model needs both.
                    IconButton({
                        clipboard.setText(AnnotatedString(if (reasoningShown && split.thinking != null) "💭 ${split.thinking}\n\n---\n\n$shown" else shown)); copied = true
                    }) {
                        Icon(if (copied) Icons.Filled.Check else BoarIcons.ContentCopy, contentDescription = t("chatScreen.copyResponse"))
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
    Text(receiptShort(r, t("chat.receipt.speed", "rate" to ReceiptFormat.rate(r.tokPerSec))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * The input bar: a chip row on top (the answer style, which opens the style sheet; Deep Research while it is on, with
 * a close icon that turns it off), then the text field with send or stop at its end.
 */
@Composable
private fun InputBar(
    text: String, onText: (String) -> Unit, answering: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    personalityId: String, stopping: Boolean, deepResearch: Boolean, onSetDeepResearch: (Boolean) -> Unit, onOpenStyles: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The background runs under the gesture bar (navigationBarsPadding comes after it).
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onOpenStyles,
                label = { Text(t("personalities.$personalityId.label"), maxLines = 1) },
                leadingIcon = { Icon(toneVector(personalityId), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
            if (deepResearch) InputChip(
                selected = true,
                onClick = { onSetDeepResearch(false) },
                label = { Text(t("chatHeader.deepResearch"), maxLines = 1) },
                leadingIcon = { Icon(BoarIcons.ScienceFilled, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = t("common.close"), modifier = Modifier.size(InputChipDefaults.IconSize)) },
                colors = InputChipDefaults.inputChipColors(
                    selectedContainerColor = scheme.tertiaryContainer, selectedLabelColor = scheme.onTertiaryContainer,
                    selectedLeadingIconColor = scheme.onTertiaryContainer, selectedTrailingIconColor = scheme.onTertiaryContainer),
            )
        }
        val shape = MaterialTheme.shapes.extraLarge
        val hint = t(if (deepResearch) "native.chat.deepResearchPlaceholder" else "chatScreen.inputPlaceholder")
        val canSend = text.isNotBlank() && !answering
        // After Stop, the button pulses until the answer really ends: a library search or a chunk of prompt finishes first.
        val transition = rememberInfiniteTransition(label = "stopping")
        val blink by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween<Float>(500), RepeatMode.Reverse), label = "pulse")
        val pulse = if (stopping) blink else 1f
        // Deep Research on: the field's border in the mode's color and its own placeholder, right where you type.
        TextField(
            text, onText,
            Modifier.fillMaxWidth().then(if (deepResearch) Modifier.border(1.5.dp, scheme.tertiary, shape) else Modifier).describe(hint),
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (deepResearch) scheme.tertiary else scheme.onSurfaceVariant) },
            // Stop is error/onError, send is primary/onPrimary: real scheme pairs in both themes. The disabled send state is
            // the stock one (onSurface at 12 % / 38 %); the pulse after Stop applies only while the button is live.
            trailingIcon = {
                FilledIconButton(
                    { if (answering) onStop() else onSend() },
                    Modifier.alpha(if (answering || canSend) pulse else 1f),
                    enabled = answering || canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (answering) scheme.error else scheme.primary, contentColor = if (answering) scheme.onError else scheme.onPrimary),
                ) {
                    Icon(if (answering) BoarIcons.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = t(if (answering) "chatScreen.stopGeneration" else "chatScreen.sendMessage"))
                }
            },
            maxLines = 5, shape = shape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = scheme.surfaceContainerHighest, unfocusedContainerColor = scheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                cursorColor = scheme.primary),
        )
    }
}
