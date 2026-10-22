package team.sopa.boar.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.sopa.boar.chat.Source
import team.sopa.boar.ui.t
import team.sopa.boar.ui.theme.Boar
import team.sopa.boar.ui.theme.BrandType

/** components/SourceFootnotes.tsx: the numbered sources under an answer; tapping one shows the passage the model read. */
@Composable
fun SourceFootnotes(sources: List<Source>, title: String? = null) {
    val c = Boar.colors
    val mono = Boar.type["mono.xs"]
    var expanded by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📚", fontSize = 11.sp)
            Text(title ?: t("sourceFootnotes.title"), style = mono.copy(letterSpacing = 0.5.sp), color = c.textAccentCyan, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.weight(1f))
            Text("${sources.size}", style = mono, color = c.textMuted, modifier = Modifier.background(c.bgCardHover, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        sources.forEachIndexed { i, s ->
            val open = expanded == i
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        // Scheme surfaces, not a fixed navy: the title is onSurface and has to sit on a surface in both themes.
                        .background(if (open) c.cyanBgSubtle else c.bgCard)
                        .border(1.dp, if (open) c.cyan500 else c.borderDefault, RoundedCornerShape(6.dp))
                        .clickable { expanded = if (open) null else i }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(16.dp).background(c.cyanBgSubtle, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = mono.copy(fontSize = 9.sp), color = c.textAccentCyan, fontWeight = FontWeight.Bold)
                    }
                    Text(s.title, style = Boar.type.caption.copy(fontFamily = BrandType.familyFor(null, 600).first), color = c.textHeading,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // The real similarity to the question, not the fused score (that one is only relative).
                    s.similarity?.let { Text("%.0f%%".format(it * 100), style = mono, color = c.textAccentEmerald, fontWeight = FontWeight.SemiBold) }
                    Text(if (open) "▲" else "▼", fontSize = 8.sp, color = c.textDim)
                }
                if (open) Column(
                    Modifier.fillMaxWidth().background(c.bgTerminal, RoundedCornerShape(6.dp)).border(1.dp, c.borderDefault, RoundedCornerShape(6.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("sourceFootnotes.localIndex"), style = mono, color = c.textAccentEmerald, fontWeight = FontWeight.Bold)
                        // Where the passage comes from ("Wikipedia — https://…"), never the internal id ("pack:wiki-vital5:…").
                        // "Your documents" only for the user's own (DocumentImport's "custom-…" ids); BOAR's built-in passages name their title.
                        Text(s.source ?: if (s.docId?.startsWith("custom-") == true) t("native.sourceFootnotes.yourDocuments") else s.title,
                            style = mono, color = c.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text(s.body, style = mono.copy(lineHeight = mono.fontSize * 1.45f), color = c.textSecondary, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
