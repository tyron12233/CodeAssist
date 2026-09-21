package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.ide.ui.LtrContent
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.colors.ColorKeys
import dev.ide.ui.theme.colors.ResolvedColorScheme
import dev.ide.ui.theme.colors.toSpanStyle

/**
 * A miniature editor rendered entirely from the scheme being edited.
 *
 * It is a mock rather than the real [dev.ide.ui.editor.CodeEditor] because the things a color scheme is
 * mostly about — a current line, a caret, a selection, an error squiggle, the gutter and its border — only
 * appear in a real editor when a real project, a real caret and a real analysis pass put them there. The
 * mock stages all of them at once, which is the only way to judge a background against a selection against
 * a squiggle without going and finding a file that happens to have all three.
 *
 * Every run is tappable and reports the attribute that drew it, so the preview doubles as the index: see a
 * color you do not like, touch it, and you are editing the entry responsible.
 */
@Composable
fun ColorSchemePreview(
    colors: ResolvedColorScheme,
    sample: PreviewSample,
    onPickAttribute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val baseStyle = Ide.type.codeSmall
    // The editor stacks rows itself, so the theme's explicit lineHeight has to go for the mock to space its
    // rows the way the real canvas does.
    val codeStyle = remember(baseStyle, colors) {
        baseStyle.copy(color = colors.textColor, lineHeight = TextUnit.Unspecified)
    }
    val metrics = remember(measurer, codeStyle) {
        val probe = measurer.measure(AnnotatedString("MMMMMMMMMM"), style = codeStyle, softWrap = false, maxLines = 1)
        probe.size.width / 10f to probe.size.height.toFloat()
    }
    val charWidthPx = metrics.first
    val rowHeight = with(density) { metrics.second.toDp() }
    val gutterWidth = with(density) { (charWidthPx * 3.5f).toDp() }

    val background = colors.background(Ide.colors.editorBg)
    val currentLineFill = colors.currentLine(Ide.colors.currentLine)
    val gutterText = colors.gutterText(Ide.colors.gutterText)
    val gutterCurrent = colors.gutterCurrent(gutterText)
    val gutterBorder = colors.gutterBorder(background)
    val caretColor = colors.caret(gutterCurrent)
    val selectionFill = colors.selection(Ide.colors.selection)
    val errorColor = colors.error(Color(0xFFFF6B63))
    val warningColor = colors.warning(Ide.colors.warning)

    // LTR for the same reason the editor pins itself: a code sample mirrored is not the file it stands for,
    // and column positions (the caret, the squiggle, the indent guides) would land on the wrong side.
    LtrContent {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ca.radius.control))
                .background(background)
                .horizontalScroll(rememberScrollState()),
        ) {
            sample.lines.forEachIndexed { index, runs ->
                PreviewRow(
                    runs = runs,
                    lineNumber = index + 1,
                    isCurrent = index == sample.currentLine,
                    codeStyle = codeStyle,
                    colors = colors,
                    charWidthPx = charWidthPx,
                    rowHeight = rowHeight,
                    gutterWidth = gutterWidth,
                    currentLineFill = currentLineFill,
                    gutterText = gutterText,
                    gutterCurrent = gutterCurrent,
                    gutterBorder = gutterBorder,
                    caretColumn = if (index == sample.currentLine) sample.caretColumn else -1,
                    caretColor = caretColor,
                    selection = if (index == sample.selectionLine) sample.selectionColumns else null,
                    selectionFill = selectionFill,
                    squiggle = when (index) {
                        sample.errorLine -> sample.errorColumns to errorColor
                        sample.warningLine -> sample.warningColumns to warningColor
                        else -> null
                    },
                    diagnosticDot = when (index) {
                        sample.errorLine -> errorColor to ColorKeys.DIAGNOSTIC_ERROR
                        sample.warningLine -> warningColor to ColorKeys.DIAGNOSTIC_WARNING
                        else -> null
                    },
                    indentGuide = colors.indentGuide(gutterBorder),
                    onPickAttribute = onPickAttribute,
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(
    runs: List<SampleRun>,
    lineNumber: Int,
    isCurrent: Boolean,
    codeStyle: TextStyle,
    colors: ResolvedColorScheme,
    charWidthPx: Float,
    rowHeight: Dp,
    gutterWidth: Dp,
    currentLineFill: Color,
    gutterText: Color,
    gutterCurrent: Color,
    gutterBorder: Color,
    caretColumn: Int,
    caretColor: Color,
    selection: IntRange?,
    selectionFill: Color,
    squiggle: Pair<IntRange, Color>?,
    diagnosticDot: Pair<Color, String>?,
    indentGuide: Color,
    onPickAttribute: (String) -> Unit,
) {
    val text = remember(runs, colors, selection, selectionFill) {
        buildAnnotatedString {
            for (run in runs) {
                val start = length
                append(run.text)
                // Layered exactly as the editor layers them: the base attribute, then each modifier
                // attribute on top, so a deprecated static property reads here as it will in a file.
                var style = colors.styleOf(run.keys.firstOrNull() ?: continue)
                for (key in run.keys.drop(1)) style = colors.styleOf(key).mergedOnto(style)
                addStyle(style.toSpanStyle(), start, length)
            }
            if (selection != null) {
                val from = selection.first.coerceIn(0, length)
                val to = (selection.last + 1).coerceIn(from, length)
                if (to > from) addStyle(SpanStyle(background = selectionFill), from, to)
            }
        }
    }
    val indentColumns = remember(runs) { leadingSpaces(runs) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (isCurrent) currentLineFill else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(gutterWidth)
                .pointerInput(Unit) { detectTapGestures { onPickAttribute(ColorKeys.GUTTER_TEXT) } },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                lineNumber.toString(),
                style = codeStyle,
                color = if (isCurrent) gutterCurrent else gutterText,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        if (diagnosticDot != null) {
            val (dotColor, dotKey) = diagnosticDot
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(5.dp)
                    .background(dotColor, CircleShape)
                    .pointerInput(dotKey) { detectTapGestures { onPickAttribute(dotKey) } },
            )
        } else {
            Box(Modifier.padding(horizontal = 3.dp).size(5.dp))
        }
        Box(Modifier.width(1.dp).height(rowHeight).background(gutterBorder))
        Box(
            Modifier
                .padding(start = 6.dp)
                .drawBehind {
                    for (column in indentColumns) {
                        val x = column * charWidthPx
                        drawLine(indentGuide, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    }
                }
                .drawWithContent {
                    drawContent()
                    squiggle?.let { (columns, color) -> drawSquiggle(columns, color, charWidthPx) }
                    if (caretColumn >= 0) {
                        val x = caretColumn * charWidthPx
                        drawLine(caretColor, Offset(x, 1f), Offset(x, size.height - 1f), strokeWidth = 2f)
                    }
                }
                .pointerInput(runs) {
                    detectTapGestures { offset ->
                        val column = (offset.x / charWidthPx).toInt()
                        // Untagged code is drawn in the default text color, and past the end of the line
                        // there is nothing but the editor background — both are attributes worth reaching.
                        val run = runAt(runs, column)
                        onPickAttribute(
                            run?.primaryKey
                                ?: if (run != null) ColorKeys.TEXT else ColorKeys.EDITOR_BACKGROUND,
                        )
                    }
                },
        ) {
            Text(text, style = codeStyle, maxLines = 1, softWrap = false)
        }
    }
}

/** The run covering [column], or null past the end of the line. */
private fun runAt(runs: List<SampleRun>, column: Int): SampleRun? {
    var at = 0
    for (run in runs) {
        at += run.text.length
        if (column < at) return run
    }
    return null
}

/** Columns where an indent guide is drawn: one per four-space level inside the line's leading blanks.
 *  Column zero is skipped — a guide flush against the left edge reads as a border, not a guide. */
private fun leadingSpaces(runs: List<SampleRun>): List<Int> {
    val text = runs.joinToString("") { it.text }
    val indent = text.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }
    return (4 until indent step 4).toList()
}

/** The editor's wavy diagnostic underline, at preview scale. */
private fun DrawScope.drawSquiggle(columns: IntRange, color: Color, charWidthPx: Float) {
    val start = columns.first * charWidthPx
    val end = ((columns.last + 1) * charWidthPx).coerceAtMost(size.width)
    if (end <= start) return
    val amplitude = 1.6f
    val period = 4f
    val y = size.height - amplitude - 1f
    val path = Path().apply {
        moveTo(start, y)
        var x = start
        var up = true
        while (x < end) {
            x = (x + period).coerceAtMost(end)
            lineTo(x, if (up) y + amplitude else y - amplitude)
            up = !up
        }
    }
    drawPath(path, color, style = Stroke(width = 1.2f))
}
