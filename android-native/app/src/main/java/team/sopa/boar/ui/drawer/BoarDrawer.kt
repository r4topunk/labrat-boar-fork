package team.sopa.boar.ui.drawer

import team.sopa.boar.ui.components.BoarMark
import team.sopa.boar.ui.theme.IconSize
import team.sopa.boar.ui.theme.onTextLine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import team.sopa.boar.engine.models.Units
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import team.sopa.boar.engine.chat.ChatSession
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import java.util.Locale

/** A destination under "New chat"; [icon] leads the label (the Material drawer spec: every destination has one), [note] goes under it. */
class DrawerItem(val key: String, val label: String, val icon: ImageVector? = null, val note: String? = null, val onClick: () -> Unit)

/**
 * A standard Material 3 navigation drawer: the brand, a new chat, the screens, recent sessions (open,
 * delete), and (developer options only) the RAM/disk footer. Colors come from the Material theme, so it follows
 * Material You and the light/dark choice. Everything lines up at 28 dp: the sheet's 12 dp item padding plus the 16 dp
 * inset the item text, the header and the section label share.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoarDrawer(
    sessions: List<ChatSession>,
    activeSessionId: String?,
    items: List<DrawerItem>,
    lastTokPerSec: Double?,
    storageUsed: () -> Long,
    /** What the running downloads still have to fetch: they count against BOAR's limit before they land. */
    storageBudgetBytes: Long,
    /** The memory and storage bars at the bottom: a technical detail (Developer options → Show technical details). */
    showStats: Boolean,
    /** BOAR is answering: chats can't be switched, opened or deleted until it ends (the list is dimmed, with a hint). */
    answering: Boolean = false,
    /** The line under the name in the brand card ("13 packs · works offline"). */
    brandNote: String = "",
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    /** The caller removes the row right away and offers an undo; the chat is erased only when that lapses. */
    onDelete: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    // The ModalDrawerSheet around it pads for the system bars.
    Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp).padding(top = 16.dp)) {
        // Fixed parts at the top and bottom; the chat history takes the height left under them and scrolls inside
        // it, so the menu doesn't move when the sessions load.
        Column(Modifier.weight(1f)) {
            // The brand card (r4to 2026-10-24, proposal C): the mark on a light tile, the name and what is inside, then
            // New chat as the card's one filled action.
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(24.dp)).background(scheme.primaryContainer).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surface), contentAlignment = Alignment.Center) {
                        BoarMark(null, Modifier.size(44.dp)) // about 34 dp of mark, as in the design
                    }
                    Column {
                        Text("LabRat", style = Boar.type.title, fontWeight = FontWeight.ExtraBold, color = scheme.onPrimaryContainer)
                        if (brandNote.isNotEmpty()) Text(brandNote, style = type.bodySmall, color = scheme.onPrimaryContainer)
                    }
                }
                Button(onClick = onNewChat, enabled = !answering, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(ButtonDefaults.IconSize).onTextLine(MaterialTheme.typography.labelLarge))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(t("clean.drawer.newChat"))
                }
            }
            // The destinations sit right under the button, so a short history leaves the empty space at the bottom
            // instead of a gap in the middle of the sheet.
            items.forEach { item ->
                val icon = item.icon
                NavigationDrawerItem(
                    icon = if (icon != null) ({ Icon(icon, null, Modifier.onTextLine(type.labelLarge)) }) else null,
                    label = {
                        Column {
                            Text(item.label, style = type.labelLarge)
                            item.note?.let { Text(it, style = type.bodySmall, color = scheme.onSurfaceVariant) }
                        }
                    },
                    selected = false,
                    onClick = item.onClick,
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = scheme.outlineVariant)
            if (answering) Text(t("native.drawer.answeringHint"), style = type.bodySmall, color = scheme.tertiary, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            // The history grouped by day (Today, Yesterday, Previous 7 days, Older) instead of a time under every row: one
            // line per chat. Delete is in each row's long-press menu (and TalkBack's actions), so the list isn't a column
            // of trash cans; the Undo bar still follows a delete.
            val groups = remember(sessions) { sessions.groupBy { dayGroup(it.updatedAt) }.toSortedMap() }
            val deleteLabel = t("clean.nav.deleteChat")
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                groups.forEach { (group, list) ->
                    item(key = "group-$group") {
                        Text(t(GROUP_KEYS[group]), style = type.titleSmall, color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                    }
                    items(list, key = { it.id }) { s ->
                        val active = s.id == activeSessionId
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Text(displayTitle(s.title), style = type.bodyLarge, color = if (active) scheme.onSecondaryContainer else scheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().height(48.dp).clip(CircleShape)
                                    .background(if (active) scheme.secondaryContainer else Color.Transparent)
                                    .semantics {
                                        selected = active
                                        if (!answering) customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(s.id); true })
                                    }
                                    .combinedClickable(enabled = !answering, onLongClickLabel = deleteLabel, onLongClick = { menu = true }) { onSelect(s.id) }
                                    .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 16.dp)
                                    .alpha(if (answering && !active) 0.4f else 1f))
                            DropdownMenu(menu, onDismissRequest = { menu = false }, offset = DpOffset(16.dp, 0.dp)) {
                                DropdownMenuItem(text = { Text(deleteLabel) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menu = false; onDelete(s.id) })
                            }
                        }
                    }
                }
            }
        }
        if (showStats) FooterStats(lastTokPerSec, storageUsed, storageBudgetBytes)
    }
}

/**
 * A chat title as the list shows it: no "Title:" prefix, quotes or trailing period, and sentence case when the model
 * wrote it in Title Case ("Australia's Capital" -> "Australia's capital"; acronyms such as "DNA" stay).
 */
internal fun displayTitle(raw: String): String {
    var s = raw.removePrefix("Title:").trim().trim('"', '\u201C', '\u201D', '*').trim().trimEnd('.').trim()
    val words = s.split(" ")
    val long = words.drop(1).filter { it.length >= 3 }
    if (long.isNotEmpty() && long.all { it.first().isUpperCase() }) {
        s = words.mapIndexed { i, w -> if (i == 0 || w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() }) w else w.lowercase() }.joinToString(" ")
    }
    return s.ifEmpty { raw }
}

private val GROUP_KEYS = listOf("design.drawer.today", "design.drawer.yesterday", "design.drawer.week", "design.drawer.older")

/** 0 today, 1 yesterday, 2 the 7 days before, 3 older: calendar days in the phone's time zone. */
private fun dayGroup(ms: Long): Int {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    return when { days <= 0 -> 0; days == 1L -> 1; days <= 7 -> 2; else -> 3 }
}

/** "1.8/12GB": one decimal, a whole number without it, the unit once. */
/** Decimal GB, like the catalog's model sizes and the phone's own storage screen. */
internal fun usage(used: Long, total: Long): String {
    fun gb(b: Long) = if (b <= 0) "0" else String.format(Locale.US, "%.1f", b / 1e9).removeSuffix(".0")
    return "${gb(used)}/${gb(total)}GB"
}

/**
 * The footer stats, with real numbers: the phone's memory in use (BOAR's part highlighted) against the phone's
 * total, and BOAR's disk use plus what running downloads still fetch against BOAR's own storage limit, which the
 * caption names so it isn't read as the phone's space. Then the last answer's speed.
 */
@Composable
private fun FooterStats(lastTokPerSec: Double?, storageUsed: () -> Long, storageBudget: Long) {
    val c = Boar.colors
    val ctx = LocalContext.current
    var app by remember { mutableLongStateOf(0L) }
    var phone by remember { mutableStateOf(team.sopa.boar.app.AppMemory.Phone(0L, 0L)) }
    var disk by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                app = team.sopa.boar.app.AppMemory.residentBytes(); phone = team.sopa.boar.app.AppMemory.phone(ctx)
                disk = storageUsed()
            }
            delay(5000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
        Bar(t("drawerFooterStats.ram"), listOf(minOf(app, phone.usedBytes) to c.emerald400, maxOf(phone.usedBytes - app, 0L) to c.textDim), phone.totalBytes,
            usage(phone.usedBytes, phone.totalBytes))
        Caption(t("native.drawerStats.ramBoar", "app" to Units.formatBytes(app, Locale.ENGLISH)))
        Bar(t("drawerFooterStats.disk"), listOf(disk to c.cyan400), storageBudget, usage(disk, storageBudget))
        Caption(t("native.drawerStats.diskLimit"))
        if (lastTokPerSec != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
            Text("●", fontSize = 8.sp, color = c.cyan400)
            Text(t("drawerFooterStats.lastQuery", "rate" to String.format(Locale.US, "%.1f", lastTokPerSec)), style = Boar.type["mono.xs"].copy(fontSize = 9.sp),
                color = c.textAccentCyan, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Caption(text: String) = UsageCaption(text, large = false)

@Composable
private fun Bar(label: String, parts: List<Pair<Long, Color>>, total: Long, text: String) = UsageBar(label, parts, total, text, large = false)

/** The line under a [UsageBar], lined up with the bar. */
@Composable
internal fun UsageCaption(text: String, large: Boolean) =
    Text(text, style = if (large) Boar.type.subtext else Boar.type["mono.xs"].copy(fontSize = 9.sp), color = Boar.colors.textDim,
        modifier = Modifier.padding(start = if (large) 92.dp else 46.dp))

/**
 * A bar of stacked [parts] (bytes, color) out of [total]; red when they pass it. Small in the menu, [large] in
 * Settings (readable text, a taller bar).
 */
@Composable
internal fun UsageBar(label: String, parts: List<Pair<Long, Color>>, total: Long, text: String, large: Boolean) {
    val c = Boar.colors
    val sum = parts.sumOf { it.first }
    val over = total > 0 && sum > total
    val small = Boar.type["mono.xs"].copy(fontSize = 9.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = if (large) Boar.type.subtext else small, color = if (large) c.textSecondary else c.textDim, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.widthIn(min = if (large) 86.dp else 40.dp))
        Row(Modifier.weight(1f).height(if (large) 9.dp else 5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(1.dp, c.borderDefault, RoundedCornerShape(3.dp))) {
            if (total > 0) parts.forEach { (bytes, color) ->
                val frac = (bytes.toFloat() / maxOf(total, sum)).coerceIn(0f, 1f)
                if (frac > 0f) Box(Modifier.weight(frac).fillMaxHeight().background(if (over) c.crimson500 else color))
            }
            val rest = if (total > 0) 1f - (sum.toFloat() / maxOf(total, sum)).coerceIn(0f, 1f) else 1f
            if (rest > 0f) Box(Modifier.weight(rest))
        }
        Text(text, style = if (large) Boar.type["mono.xs"] else small, color = c.textMuted, maxLines = 1)
    }
}
