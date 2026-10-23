package team.sopa.boar.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp

/**
 * An answer's text as the Expo app shows it (port of components/MarkdownMessage.tsx): ``` code blocks, # / ## / ###
 * headings, "- " and "* " bullets, `inline code` and **bold**; a blinking-free cursor while it streams.
 */
private sealed interface MdBlock {
    data class Text(val content: String) : MdBlock
    data class Code(val code: String, val language: String) : MdBlock
}

private val FENCE = Regex("```([a-zA-Z0-9_-]*)\\n([\\s\\S]*?)```")
private val INLINE = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*| ?\\[\\d{1,2}(?:,\\s*\\d{1,2})*\\])")

private fun parseBlocks(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    var last = 0
    for (m in FENCE.findAll(text)) {
        if (m.range.first > last) out += MdBlock.Text(text.substring(last, m.range.first))
        out += MdBlock.Code(m.groupValues[2].removeSuffix("\n"), m.groupValues[1].trim().ifEmpty { "CODE" })
        last = m.range.last + 1
    }
    if (last < text.length) out += MdBlock.Text(text.substring(last))
    return out
}

@Composable
private fun inline(text: String): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    return buildAnnotatedString {
        var last = 0
        for (m in INLINE.findAll(text)) {
            append(text.substring(last, m.range.first))
            val part = m.value
            when {
                part.startsWith("`") && part.length > 2 ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = scheme.surfaceContainerHighest)) { append(part.substring(1, part.length - 1)) }
                part.startsWith("**") && part.length > 4 ->
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part.substring(2, part.length - 2)) }
                // A citation "[1]" or "[1, 2]": a small raised number in primary that matches the numbered sources below.
                part.trimStart().startsWith("[") ->
                    withStyle(SpanStyle(fontSize = 0.72.em, baselineShift = BaselineShift(0.35f), color = scheme.primary, fontWeight = FontWeight.Medium)) {
                        append("\u2009" + part.trim().removePrefix("[").removeSuffix("]").replace(Regex(",\\s*"), ","))
                    }
                else -> append(part)
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

/**
 * Material type roles only: body in bodyLarge on onSurface, headings in the title roles, bullets marked in primary,
 * code on a surfaceContainerHighest block. A blank line is a short gap, not a whole empty line of text.
 */
@Composable
fun MarkdownMessage(content: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // One skippable composable per line: while an answer streams only its last line changes, so the lines
            // above keep their parsed text and layout instead of being rebuilt (regex, AnnotatedString) on every token.
            for (block in parseBlocks(content)) when (block) {
                is MdBlock.Code -> MdCode(block.code, block.language)
                is MdBlock.Text -> for (line in block.content.split("\n")) MdLine(line)
            }
            if (streaming) Box(Modifier.padding(top = 2.dp).size(10.dp).background(scheme.primary, CircleShape))
        }
    }
}

/**
 * Each line draws in its own layer: the answer's column is redrawn as it grows, and without one every line above
 * re-recorded its glyphs on every token (a fifth of the main thread late in a long answer).
 */
private val LineLayer = Modifier.graphicsLayer()

@Composable
private fun MdCode(code: String, language: String) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(
        LineLayer.fillMaxWidth().background(scheme.surfaceContainerHighest, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(language.uppercase(), style = type.labelSmall, color = scheme.onSurfaceVariant)
        Text(code, style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = scheme.onSurface)
    }
}

@Composable
private fun MdLine(line: String) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val body = type.bodyLarge.copy(color = scheme.onSurface)
    val t = line.trim()
    when {
        t.isEmpty() -> Spacer(Modifier.height(4.dp))
        t.startsWith("### ") -> Text(t.drop(4), style = type.titleSmall, color = scheme.onSurface, modifier = LineLayer.padding(top = 4.dp))
        t.startsWith("## ") -> Text(t.drop(3), style = type.titleMedium, color = scheme.onSurface, modifier = LineLayer.padding(top = 8.dp))
        t.startsWith("# ") -> Text(t.drop(2), style = type.titleLarge, color = scheme.onSurface, modifier = LineLayer.padding(top = 8.dp))
        t.startsWith("- ") || t.startsWith("* ") -> Row(LineLayer.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("•", style = body, color = scheme.primary)
            Text(inline(t.drop(2)), style = body)
        }
        else -> Text(inline(line), style = body, modifier = LineLayer)
    }
}
