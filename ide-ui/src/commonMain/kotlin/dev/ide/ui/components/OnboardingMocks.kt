package dev.ide.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import dev.ide.ui.editor.blocks.BlockCat
import dev.ide.ui.editor.blocks.BlockMetrics
import dev.ide.ui.editor.blocks.blockColor
import dev.ide.ui.editor.blocks.rememberBlockShape
import dev.ide.ui.editor.blocks.rememberCBlockShape
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.SyntaxColors

/**
 * The full-bleed feature mocks for the first-run onboarding hero. Each mock fills the 250dp
 * hero, owns its own background, and is a scaled slice of the real surface built from the same
 * design tokens. They are static (non-interactive): the only motion is the editor caret blink.
 *
 * The blocks mock reuses the real puzzle geometry + category palette (`dev.ide.ui.editor.blocks`) so it
 * matches the projectional editor (incl. the theme's Control=amber / Data=violet hues).
 */

// ---------------------------------------------------------------------------
// Shared: syntax-colored code lines + a blinking caret
// ---------------------------------------------------------------------------

/** A colored run of code text. */
private class Tok(val text: String, val color: Color)

/** Terse token builders bound to the active syntax palette (`with(Syn(...)) { kw("if ") + … }`). */
private class Syn(val c: SyntaxColors) {
    fun kw(t: String) = Tok(t, c.keyword)
    fun fn(t: String) = Tok(t, c.func)
    fun ty(t: String) = Tok(t, c.type)
    fun st(t: String) = Tok(t, c.string)
    fun nu(t: String) = Tok(t, c.number)
    fun pn(t: String) = Tok(t, c.punctuation)
    fun pr(t: String) = Tok(t, c.property)
    fun df(t: String) = Tok(t, c.default)
}

/** Hard on/off caret blink (~1.05s steps); disabled-clock-safe (a frozen clock just shows it lit). */
@Composable
private fun caretAlpha(): Float {
    val t = rememberInfiniteTransition(label = "caret")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 1050; 1f at 0; 1f at 524; 0f at 525; 0f at 1049 },
            repeatMode = RepeatMode.Restart,
        ),
        label = "caretAlpha",
    )
    return a
}

/** One editor line: right-aligned gutter (30dp) + tokenized text + optional trailing caret. */
@Composable
private fun MockCodeLine(
    lineNumber: Int,
    tokens: List<Tok>,
    current: Boolean = false,
    showCaret: Boolean = false,
) {
    val mono = Ca.type.codeFamily
    Row(
        Modifier.fillMaxWidth().height(21.dp)
            .background(if (current) Ca.colors.currentLine else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            lineNumber.toString(),
            color = if (current) Ca.colors.textSecondary else Ca.colors.gutterText,
            fontFamily = mono,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp).padding(end = 8.dp),
        )
        Text(
            buildAnnotatedString { tokens.forEach { withStyle(SpanStyle(color = it.color)) { append(it.text) } } },
            fontFamily = mono,
            fontSize = 12.5f.sp,
            lineHeight = 21.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (showCaret) {
            Box(Modifier.padding(start = 1.dp).width(1.5.dp).height(14.dp).background(Ca.colors.accent.copy(alpha = caretAlpha())))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared: the completion popup (jdt / kotlin / xml)
// ---------------------------------------------------------------------------

private val PropertyColor = Color(0xFF57B6C2)

/** One completion row: a kind badge glyph/color, the label (with [prefix] highlighted), and a signature. */
private class CompRow(
    val glyph: String,
    val color: Color,
    val label: String,
    val prefix: String,
    val signature: String,
    val selected: Boolean = false,
)

@Composable
private fun MockKindBadge(glyph: String, color: Color) {
    Box(
        Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = color,
            fontFamily = Ca.type.codeFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (if (glyph.length > 1) 7.6 else 10.0).sp,
        )
    }
}

private fun highlightedLabel(label: String, prefix: String, accent: Color) = buildAnnotatedString {
    if (prefix.isNotEmpty() && label.startsWith(prefix, ignoreCase = true)) {
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(label.take(prefix.length)) }
        append(label.substring(prefix.length))
    } else {
        append(label)
    }
}

/** Glass-thick completion card: a doc strip (signature · doc · tab/BETA chip) over selectable rows. */
@Composable
private fun MockCompletionPopup(
    signature: String,
    doc: String,
    rows: List<CompRow>,
    beta: Boolean,
    modifier: Modifier = Modifier,
) {
    val mono = Ca.type.codeFamily
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Ca.colors.glassThick)
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Ca.colors.accent.copy(alpha = 0.07f)).padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(signature, color = Ca.colors.accent, fontFamily = mono, fontSize = 11.5f.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(doc, color = Ca.colors.textSecondary, fontFamily = Ca.type.uiFamily, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (beta) BetaChip() else TabChip()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
        Column(Modifier.padding(4.dp)) {
            rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (r.selected) Ca.colors.accentSoft else Color.Transparent)
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    MockKindBadge(r.glyph, r.color)
                    Text(highlightedLabel(r.label, r.prefix, Ca.colors.accent), fontFamily = mono, fontSize = 12.5f.sp, color = Ca.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(r.signature, fontFamily = mono, fontSize = 11.5f.sp, color = Ca.colors.textTertiary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TabChip() {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Ca.colors.surface3).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text("⇥ tab", color = Ca.colors.textSecondary, fontFamily = Ca.type.uiFamily, fontSize = 10.5f.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BetaChip() {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Ca.colors.info.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text("BETA", color = Ca.colors.info, fontFamily = Ca.type.uiFamily, fontSize = 9.5f.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}

/** Editor-bg crop: a couple of code lines, then the completion popup tucked under the gutter. */
@Composable
private fun CompletionHero(
    lines: List<Pair<Int, List<Tok>>>,
    signature: String,
    doc: String,
    rows: List<CompRow>,
    beta: Boolean = false,
) {
    Column(Modifier.fillMaxSize().background(Ca.colors.editorBg).padding(start = 10.dp, end = 10.dp, top = 18.dp)) {
        lines.forEachIndexed { i, (n, toks) ->
            MockCodeLine(n, toks, current = i == lines.lastIndex, showCaret = i == lines.lastIndex)
        }
        Spacer(Modifier.height(10.dp))
        MockCompletionPopup(signature, doc, rows, beta, modifier = Modifier.padding(start = 22.dp, end = 8.dp))
    }
}

@Composable
internal fun JdtCompletionMock() {
    val s = Syn(Ca.colors.syntax)
    CompletionHero(
        lines = listOf(
            18 to with(s) { listOf(kw("public "), kw("boolean "), fn("visible"), pn("("), ty("Note "), df("note"), pn(") {")) },
            19 to with(s) { listOf(df("    "), kw("if "), pn("("), df("note"), pn("."), df("is")) },
        ),
        signature = "isPinned(): boolean",
        doc = "Whether the note is pinned to top",
        rows = listOf(
            CompRow("M", Ca.colors.accent, "isPinned", "is", "boolean", selected = true),
            CompRow("M", Ca.colors.accent, "isArchived", "is", "boolean"),
            CompRow("M", Ca.colors.accent, "isEmpty", "is", "boolean"),
        ),
    )
}

@Composable
internal fun KotlinCompletionMock() {
    val s = Syn(Ca.colors.syntax)
    CompletionHero(
        lines = listOf(
            24 to with(s) { listOf(kw("val "), df("repo "), pn("= "), ty("NoteRepository"), pn("()")) },
            25 to with(s) { listOf(df("  "), kw("return "), df("repo"), pn("."), df("pin")) },
        ),
        signature = "pinnedNotes: List<Note>",
        doc = "Notes pinned to the top, newest first",
        beta = true,
        rows = listOf(
            CompRow("P", PropertyColor, "pinnedNotes", "pin", "List<Note>", selected = true),
            CompRow("M", Ca.colors.accent, "pin", "pin", "(id: Long)"),
            CompRow("M", Ca.colors.accent, "pinAll", "pin", "(ids)"),
        ),
    )
}

@Composable
internal fun XmlCompletionMock() {
    val s = Syn(Ca.colors.syntax)
    CompletionHero(
        lines = listOf(
            11 to with(s) { listOf(pn("  <"), ty("TextView")) },
            12 to with(s) { listOf(df("    "), pr("android"), pn(":"), pr("layout")) },
        ),
        signature = "android:layout_width",
        doc = "Required. Width of the view",
        rows = listOf(
            CompRow("P", PropertyColor, "layout_width", "layout", "dimension", selected = true),
            CompRow("P", PropertyColor, "layout_height", "layout", "dimension"),
            CompRow("P", PropertyColor, "layout_margin", "layout", "dimension"),
        ),
    )
}

// ---------------------------------------------------------------------------
// the IDE: glass top bar + tab strip + code + smart key bar
// ---------------------------------------------------------------------------

@Composable
internal fun IdeMock() {
    val mono = Ca.type.codeFamily
    val s = Syn(Ca.colors.syntax)
    Column(Modifier.fillMaxSize().background(Ca.colors.editorBg)) {
        // Top bar (its left cluster sits under the floating kicker chip — by design).
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(Ca.colors.glassReg).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CaIcons.sidebar, null, Modifier.size(18.dp), tint = Ca.colors.textSecondary)
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFB487F7), Color(0xFF5A3A9E)))),
                contentAlignment = Alignment.Center,
            ) { Text("A", color = Color.White, fontFamily = Ca.type.uiFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Text("Aurora", color = Ca.colors.textPrimary, fontFamily = Ca.type.uiFamily, fontSize = 13.5f.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Icon(CaIcons.command, null, Modifier.size(18.dp), tint = Ca.colors.textSecondary)
            Row(
                Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).background(Ca.colors.accent).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(CaIcons.play, null, Modifier.size(14.dp), tint = Color.White)
                Text("Run", color = Color.White, fontFamily = Ca.type.uiFamily, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // Tab strip with an active underline + modified dot.
        val accent = Ca.colors.accent
        val sep = Ca.colors.separator
        Row(
            Modifier.fillMaxWidth().height(30.dp).background(Ca.colors.editorBg).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.fillMaxHeight().drawBehind {
                    drawLine(accent, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 2f)
                }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                LetterBadge("J", Color(0xFFD9A066), size = 16)
                Text("NoteRepository.java", color = Ca.colors.textPrimary, fontFamily = Ca.type.uiFamily, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.size(6.dp).background(Ca.colors.gitModified, CircleShape))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
        // The pinned() stream.
        Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
            MockCodeLine(14, with(s) { listOf(kw("public "), ty("List<Note> "), fn("pinned"), pn("() {")) })
            MockCodeLine(15, with(s) { listOf(df("  "), kw("return "), df("notes"), pn("."), fn("stream"), pn("()")) })
            MockCodeLine(16, with(s) { listOf(df("    "), pn("."), fn("filter"), pn("("), df("n "), pn("-> "), df("n"), pn("."), fn("isPinned"), pn("())")) }, current = true)
            MockCodeLine(17, with(s) { listOf(df("    "), pn("."), fn("collect"), pn("("), ty("Collectors"), pn("."), fn("toList"), pn("());")) })
        }
        Spacer(Modifier.weight(1f))
        // Smart key bar.
        Row(
            Modifier.fillMaxWidth().drawBehind {
                drawLine(sep, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
            }.background(Ca.colors.glassReg).padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyCap("Tab", Ca.colors.accentSoft, Ca.colors.accent, mono, 13f, FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("{", "}", "(", ")", ";", "=", "<", ">").forEach {
                    KeyCap(it, Ca.colors.surface2, Ca.colors.textSecondary, mono, 14f, FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, bg: Color, fg: Color, font: FontFamily, size: Float, weight: FontWeight) {
    Box(
        Modifier.height(30.dp).widthIn(min = 28.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontFamily = font, fontSize = size.sp, fontWeight = weight) }
}

// ---------------------------------------------------------------------------
// block editor: method hat → if C-block wrapping a return (real puzzle geometry)
// ---------------------------------------------------------------------------

@Composable
internal fun BlocksMock() {
    Box(Modifier.fillMaxSize().background(Ca.colors.editorBg)) {
        Column(Modifier.padding(start = 16.dp, top = 26.dp)) {
            MethodHatBlock()
            IfReturnBlock()
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(Ca.colors.run, CircleShape))
            Text("live projection · synced with Code", color = Ca.colors.textTertiary, fontFamily = Ca.type.uiFamily, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BlockSocket(text: String, color: Color, white: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (white) Ca.colors.block.socket else color).padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            color = if (white) Ca.colors.block.socketText else Color.White,
            fontFamily = Ca.type.codeFamily,
            fontSize = 11.5f.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MethodHatBlock() {
    val d = LocalDensity.current
    val shape = rememberBlockShape(notchTop = false, bumpBottom = true, topRadius = with(d) { BlockMetrics.hatCorner.toPx() })
    Row(
        Modifier.background(blockColor(BlockCat.Method), shape)
            .padding(start = 12.dp, end = 12.dp, top = 7.dp)
            .padding(bottom = 7.dp + BlockMetrics.connDepth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CaIcons.caretDown, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.8f))
        Text("pinned", color = Color.White, fontFamily = Ca.type.codeFamily, fontSize = 12.5f.sp, fontWeight = FontWeight.Medium)
        Text("()", color = Color.White.copy(alpha = 0.85f), fontFamily = Ca.type.codeFamily, fontSize = 12.5f.sp)
        BlockSocket("List<Note>", blockColor(BlockCat.Method), white = true)
    }
}

@Composable
private fun IfReturnBlock() {
    val d = LocalDensity.current
    val headerH = 34.dp
    val shape = rememberCBlockShape(
        headerHeightPx = with(d) { headerH.toPx() },
        footerHeightPx = with(d) { (BlockMetrics.footer + BlockMetrics.connDepth).toPx() },
        armWidthPx = with(d) { BlockMetrics.arm.toPx() },
        mouthInsetPx = with(d) { (BlockMetrics.arm + BlockMetrics.connInset).toPx() },
        bumpBottom = false,
    )
    Column(Modifier.offset(y = -BlockMetrics.connDepth).background(blockColor(BlockCat.Control), shape)) {
        Row(
            Modifier.height(headerH).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("if", color = Color.White, fontFamily = Ca.type.codeFamily, fontSize = 12.5f.sp, fontWeight = FontWeight.Medium)
            BlockSocket("note.isPinned()", blockColor(BlockCat.Call))
        }
        Box(Modifier.offset(y = -BlockMetrics.connDepth).padding(start = BlockMetrics.arm)) { ReturnBlock() }
        Spacer(Modifier.height(BlockMetrics.footer))
    }
}

@Composable
private fun ReturnBlock() {
    val shape = rememberBlockShape(notchTop = true, bumpBottom = false)
    Row(
        Modifier.background(blockColor(BlockCat.Return), shape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("return", color = Color.White, fontFamily = Ca.type.codeFamily, fontSize = 12.5f.sp, fontWeight = FontWeight.Medium)
        BlockSocket("note", blockColor(BlockCat.Data))
    }
}

// ---------------------------------------------------------------------------
// build & run console: step graph + success footer
// ---------------------------------------------------------------------------

@Composable
internal fun BuildConsoleMock() {
    Column(Modifier.fillMaxSize().background(Ca.colors.bg).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(CaIcons.terminal, null, Modifier.size(17.dp), tint = Ca.colors.textSecondary)
            Text("Build", color = Ca.colors.textPrimary, fontFamily = Ca.type.uiFamily, fontSize = 13.5f.sp, fontWeight = FontWeight.SemiBold)
            Text("app", color = Ca.colors.textTertiary, fontFamily = Ca.type.codeFamily, fontSize = 11.5f.sp)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Ca.colors.run.copy(alpha = 0.16f)).padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(CaIcons.check, null, Modifier.size(12.dp), tint = Ca.colors.run)
                Text("Succeeded", color = Ca.colors.run, fontFamily = Ca.type.uiFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("3.2s", color = Ca.colors.textTertiary, fontFamily = Ca.type.codeFamily, fontSize = 11.5f.sp)
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            BuildStepRow("Resolve", "42 deps")
            BuildStepRow("Compile", "javac · kotlinc")
            BuildStepRow("Dex", "API 26")
            BuildStepRow("Package", "app-debug.apk")
            BuildStepRow("Sign", "debug key")
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ca.colors.run.copy(alpha = 0.12f)).padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DoneCircle(18)
            Text(
                buildAnnotatedString {
                    append("Installed ")
                    withStyle(SpanStyle(fontFamily = Ca.type.codeFamily, color = Ca.colors.textSecondary)) { append("app-debug.apk") }
                    append(" on Pixel 8")
                },
                color = Ca.colors.textPrimary,
                fontFamily = Ca.type.uiFamily,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun BuildStepRow(name: String, detail: String) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        DoneCircle(16)
        Text(name, color = Ca.colors.textSecondary, fontFamily = Ca.type.uiFamily, fontSize = 12.5f.sp)
        Spacer(Modifier.weight(1f))
        Text(detail, color = Ca.colors.textTertiary, fontFamily = Ca.type.codeFamily, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DoneCircle(size: Int) {
    Box(Modifier.size(size.dp).background(Ca.colors.run, CircleShape), contentAlignment = Alignment.Center) {
        Icon(CaIcons.check, null, Modifier.size((size * 0.66f).dp), tint = Color.White)
    }
}

// ---------------------------------------------------------------------------
// command palette: search + RUN / GO TO groups
// ---------------------------------------------------------------------------

@Composable
internal fun CommandPaletteMock() {
    Column(Modifier.fillMaxSize().background(Ca.colors.bg).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ca.colors.glassThick).border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(14.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(CaIcons.command, null, Modifier.size(17.dp), tint = Ca.colors.accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("isPinned", color = Ca.colors.textPrimary, fontFamily = Ca.type.uiFamily, fontSize = 13.sp)
                    Box(Modifier.padding(start = 1.dp).width(1.5.dp).height(14.dp).background(Ca.colors.accent.copy(alpha = caretAlpha())))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Ca.colors.surface3).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text("esc", color = Ca.colors.textTertiary, fontFamily = Ca.type.uiFamily, fontSize = 10.5f.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            PaletteGroupHeader("RUN")
            PaletteRowMock(CaIcons.play, "Run app", "app", "⌘R", selected = true)
            PaletteGroupHeader("GO TO")
            PaletteRowMock(CaIcons.file, "NoteRepository.java", "app/data", null)
            PaletteRowMock(CaIcons.code, "isPinned()", "symbol", null)
        }
    }
}

@Composable
private fun PaletteGroupHeader(text: String) {
    Text(
        text,
        color = Ca.colors.textTertiary,
        fontFamily = Ca.type.uiFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 11.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun PaletteRowMock(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, sub: String, shortcut: String?, selected: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp).height(34.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Ca.colors.accentSoft else Color.Transparent).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = if (selected) Ca.colors.accent else Ca.colors.textSecondary)
        Text(label, color = Ca.colors.textPrimary, fontFamily = Ca.type.uiFamily, fontSize = 12.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(sub, color = Ca.colors.textTertiary, fontFamily = Ca.type.codeFamily, fontSize = 11.sp, maxLines = 1)
        if (shortcut != null) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Ca.colors.surface3).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(shortcut, color = Ca.colors.textTertiary, fontFamily = Ca.type.codeFamily, fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Jetpack Compose @Preview: dotted canvas + device frame + LIVE badge + FAB
// ---------------------------------------------------------------------------

@Composable
internal fun ComposePreviewMock() {
    val dotColor = Ca.colors.separator
    Box(
        Modifier.fillMaxSize().background(Ca.colors.surface2).drawBehind {
            val pitch = 14.dp.toPx()
            val r = 1.dp.toPx() / 2f
            var y = pitch / 2f
            while (y < size.height) {
                var x = pitch / 2f
                while (x < size.width) { drawCircle(dotColor, r, Offset(x, y)); x += pitch }
                y += pitch
            }
        },
    ) {
        // Header label (left, under the kicker) + LIVE badge (right).
        Text(
            "NoteListPreview · Pixel 8",
            color = Ca.colors.textSecondary,
            fontFamily = Ca.type.codeFamily,
            fontSize = 11.5f.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp),
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 10.dp).clip(RoundedCornerShape(50)).background(Ca.colors.run.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(6.dp).background(Ca.colors.run, CircleShape))
            Text("LIVE", color = Ca.colors.run, fontFamily = Ca.type.uiFamily, fontSize = 10.5f.sp, fontWeight = FontWeight.Bold)
        }
        // The device frame, centered.
        Box(Modifier.align(Alignment.Center)) {
            Column(
                Modifier.width(168.dp).clip(RoundedCornerShape(18.dp)).background(Ca.colors.editorBg).border(1.dp, Ca.colors.separator, RoundedCornerShape(18.dp)).padding(14.dp),
            ) {
                Text("Notes", color = Ca.colors.textPrimary, fontFamily = Ca.type.uiFamily, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                NoteCard(pinned = true)
                Spacer(Modifier.height(8.dp))
                NoteCard(pinned = false)
            }
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = (-10).dp, y = (-10).dp).size(34.dp).background(Ca.colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(CaIcons.plus, null, Modifier.size(18.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun NoteCard(pinned: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Ca.colors.surface).border(1.dp, Ca.colors.separator, RoundedCornerShape(11.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (pinned) Icon(CaIcons.pin, null, Modifier.size(14.dp), tint = Ca.colors.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(6.dp).clip(RoundedCornerShape(50)).background(Ca.colors.textSecondary.copy(alpha = 0.55f)))
            Box(Modifier.fillMaxWidth(0.92f).height(5.dp).clip(RoundedCornerShape(50)).background(Ca.colors.separatorStrong))
        }
    }
}

// ---------------------------------------------------------------------------
// Files access — projects live in a real folder, browsable from any file manager
// ---------------------------------------------------------------------------

/** A static mock of the "your files are accessible" story: a folder card with its on-disk path and an
 *  "Open in Files" pill, over a couple of file-manager rows — conveying that other apps can browse it. */
@Composable
internal fun FilesAccessMock() {
    Column(
        Modifier.fillMaxSize().background(Ca.colors.bg).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg)).background(Ca.colors.surface)
                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(Ca.radius.sm)).background(Ca.colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) { Icon(CaIcons.folder, null, Modifier.size(18.dp), tint = Ca.colors.accent) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Your project files", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
                    Text("Open in any file manager — add icons, layouts, assets.", color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 2)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm)).background(Ca.colors.surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(".../CodeAssist/projects", color = Ca.colors.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(CaIcons.copy, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.pill)).background(Ca.colors.accentSoft).padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(CaIcons.share, null, Modifier.size(15.dp), tint = Ca.colors.accent)
                Spacer(Modifier.width(6.dp))
                Text("Open in file manager", color = Ca.colors.accent, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
