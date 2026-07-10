package dev.ide.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import dev.ide.ui.components.Chip
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.search
import dev.ide.ui.generated.resources.search_find_in_files_hint
import dev.ide.ui.generated.resources.search_hint
import dev.ide.ui.generated.resources.search_indexing
import dev.ide.ui.generated.resources.search_min_chars
import dev.ide.ui.generated.resources.search_no_members
import dev.ide.ui.generated.resources.search_no_symbols
import dev.ide.ui.generated.resources.search_no_text_matches
import dev.ide.ui.generated.resources.search_tab_members
import dev.ide.ui.generated.resources.search_tab_symbols
import dev.ide.ui.generated.resources.search_tab_text
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class SearchTab(val labelRes: StringResource, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Symbols(Res.string.search_tab_symbols, CaIcons.code),
    Members(Res.string.search_tab_members, CaIcons.layers),
    Text(Res.string.search_tab_text, CaIcons.docText),
}

/**
 * The Search destination: **Symbols** and **Members** are index-backed (go-to-symbol + complete-by-member),
 * **Text** is a full-text find-in-files across the project. Symbol/text hits are navigable via [onOpenAt].
 * Hosted as a side pane on desktop and a bottom sheet on phone (see EditorScreen). Talks only to [IdeBackend].
 */
@Composable
fun SearchScreen(
    backend: IdeBackend,
    indexing: Boolean,
    onOpenAt: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    codeFont: FontFamily = FontFamily.Monospace,
) {
    var tab by remember { mutableStateOf(SearchTab.Symbols) }
    var query by remember { mutableStateOf("") }
    var symbols by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    var members by remember { mutableStateOf<List<SymbolHit>>(emptyList()) }
    var matches by remember { mutableStateOf<List<UiTextMatch>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(UiSearchOptions()) }

    // Debounced query — re-runs on the active tab and (for text) the options. ≥2 chars, like the palette.
    LaunchedEffect(query, tab, options) {
        val q = query.trim()
        if (q.length < 2) { symbols = emptyList(); members = emptyList(); matches = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(250)
        when (tab) {
            SearchTab.Symbols -> symbols = runCatching { backend.search.searchSymbols(q, 60) }.getOrDefault(emptyList())
            SearchTab.Members -> members = runCatching { backend.search.searchMembers(q, 60) }.getOrDefault(emptyList())
            SearchTab.Text -> matches = runCatching { backend.search.findInFiles(q, options, 300) }.getOrDefault(emptyList())
        }
        searching = false
    }

    Column(modifier.fillMaxSize().background(Ca.colors.bg)) {
        // Title + search field
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(stringResource(Res.string.search), color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp)
                    .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                    .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(CaIcons.search, null, Modifier.size(18.dp), tint = Ca.colors.accent)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        val hint = if (tab == SearchTab.Text) stringResource(Res.string.search_find_in_files_hint)
                            else stringResource(Res.string.search_hint, stringResource(tab.labelRes).lowercase())
                        Text(hint, color = Ca.colors.textTertiary, style = Ca.type.subhead)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Ca.type.subhead.copy(color = Ca.colors.textPrimary),
                        cursorBrush = SolidColor(Ca.colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searching) CircularProgressIndicator(Modifier.size(14.dp), color = Ca.colors.textTertiary, strokeWidth = 2.dp)
            }
        }

        // Tabs
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchTab.entries.forEach { t -> TabPill(t, t == tab) { tab = t } }
        }

        // Text-search options (only on the Text tab)
        if (tab == SearchTab.Text) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OptionToggle("Aa", "Case sensitive", options.caseSensitive) { options = options.copy(caseSensitive = it) }
                OptionToggle("\\b", "Whole word", options.wholeWord) { options = options.copy(wholeWord = it) }
                OptionToggle(".*", "Regex", options.regex) { options = options.copy(regex = it) }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator).padding(top = 8.dp))

        // Results
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val q = query.trim()
            when {
                q.length < 2 -> Hint(stringResource(Res.string.search_min_chars))
                tab == SearchTab.Symbols && indexing && symbols.isEmpty() -> Hint(stringResource(Res.string.search_indexing))
                tab == SearchTab.Symbols -> SymbolList(symbols, codeFont, navigable = true, onOpenAt = onOpenAt, empty = stringResource(Res.string.search_no_symbols), searching = searching)
                tab == SearchTab.Members -> SymbolList(members, codeFont, navigable = false, onOpenAt = onOpenAt, empty = stringResource(Res.string.search_no_members), searching = searching)
                tab == SearchTab.Text -> TextMatchList(matches, codeFont, onOpenAt, searching)
            }
        }
    }
}

@Composable
private fun SymbolList(
    hits: List<SymbolHit>,
    codeFont: FontFamily,
    navigable: Boolean,
    onOpenAt: (String, Int) -> Unit,
    empty: String,
    searching: Boolean,
) {
    if (hits.isEmpty()) { if (!searching) Hint(empty); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(hits, key = { "${it.kind}:${it.name}:${it.filePath}:${it.offset}:${it.detail}" }) { hit ->
            val path = hit.filePath
            val off = hit.offset
            val canNav = navigable && path != null && off != null
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = canNav) { if (path != null && off != null) onOpenAt(path, off) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KindBadge(hit.kind)
                Column(Modifier.weight(1f)) {
                    Text(hit.name, color = Ca.colors.textPrimary, style = Ca.type.footnote.copy(fontFamily = codeFont),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hit.detail.isNotBlank()) Text(hit.detail, color = Ca.colors.textTertiary, style = Ca.type.caption2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (canNav) Icon(CaIcons.arrowRight, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun TextMatchList(matches: List<UiTextMatch>, codeFont: FontFamily, onOpenAt: (String, Int) -> Unit, searching: Boolean) {
    if (matches.isEmpty()) { if (!searching) Hint(stringResource(Res.string.search_no_text_matches)); return }
    val accent = Ca.colors.accent
    val grouped = remember(matches) { matches.groupBy { it.filePath } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
        grouped.forEach { (path, hits) ->
            item("file:$path") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(CaIcons.docText, null, Modifier.size(15.dp), tint = Ca.colors.textSecondary)
                    Text(hits.first().fileName, color = Ca.colors.textSecondary, style = Ca.type.caption,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Chip("${hits.size}", fill = Ca.colors.surface2, textColor = Ca.colors.textTertiary)
                }
            }
            items(hits, key = { "${path}:${it.line}:${it.matchStart}" }) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenAt(m.filePath, m.offset) }
                        .padding(start = 22.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${m.line}", color = Ca.colors.textTertiary, style = Ca.type.caption2.copy(fontFamily = codeFont),
                        modifier = Modifier.width(34.dp))
                    Text(highlight(m, accent), style = Ca.type.caption.copy(fontFamily = codeFont),
                        color = Ca.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The matched line with the hit span tinted accent + semibold, trimming long leading whitespace. */
private fun highlight(m: UiTextMatch, accent: Color) = buildAnnotatedString {
    val raw = m.lineText
    // Trim leading indentation but keep the match indices aligned.
    val lead = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val start = (m.matchStart - lead).coerceAtLeast(0)
    val end = (m.matchEnd - lead).coerceAtLeast(start)
    val line = if (lead in 1..m.matchStart) raw.substring(lead) else raw
    val s = start.coerceIn(0, line.length)
    val e = end.coerceIn(s, line.length)
    append(line.substring(0, s))
    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(line.substring(s, e)) }
    append(line.substring(e))
}

@Composable
private fun TabPill(tab: SearchTab, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Ca.colors.accentSoft else Ca.colors.surface2, tween(Motion.FAST), label = "tabBg")
    Row(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(tab.icon, null, Modifier.size(14.dp), tint = if (active) Ca.colors.accent else Ca.colors.textSecondary)
        Text(stringResource(tab.labelRes), color = if (active) Ca.colors.accent else Ca.colors.textSecondary, style = Ca.type.caption,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun OptionToggle(label: String, description: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (on) Ca.colors.accent else Ca.colors.surface2, tween(Motion.FAST), label = "optBg")
    Box(
        Modifier.size(width = 38.dp, height = 28.dp).background(bg, RoundedCornerShape(Ca.radius.sm))
            .clickable(remember { MutableInteractionSource() }, null) { onToggle(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Ca.colors.textOnAccent else Ca.colors.textSecondary,
            style = Ca.type.caption2, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun KindBadge(kind: String) {
    val letter = kind.firstOrNull()?.uppercase() ?: "?"
    val color = when (kind.lowercase()) {
        "class", "interface", "enum", "record", "annotation" -> Ca.colors.accent
        "method", "constructor" -> Ca.colors.run
        "field", "enumconstant" -> Ca.colors.warning
        else -> Ca.colors.info
    }
    Box(Modifier.size(20.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.xs)), contentAlignment = Alignment.Center) {
        Text(letter, color = color, style = Ca.type.caption2, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ca.colors.textTertiary, style = Ca.type.footnote)
    }
}
