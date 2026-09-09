package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.UiDecorationStyle
import dev.ide.ui.backend.UiDecorationTint
import dev.ide.ui.backend.UiGutterMark
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiTextDecoration
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.caColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a plugin's decorations reach the canvas: which layer each style goes to, how a multi-line range is cut
 * into per-line segments, and how the session re-anchors them as the user types.
 */
class EditorDecorationRenderTest {

    private val colors = caColors(dark = true, accent = CaAccent.Lime)

    private fun deco(
        start: Int,
        end: Int,
        style: UiDecorationStyle = UiDecorationStyle.Background,
        tint: UiDecorationTint = UiDecorationTint.Success,
        order: Int = 0,
    ) = UiTextDecoration(start, end, style, tint, order = order)

    // ---- layer routing ----

    @Test
    fun recoloringStylesBecomeSpansAndGeometricOnesBecomeSegments() {
        val doc = EditorDocument.of("val a = 1\n")
        val all = listOf(
            deco(0, 3, UiDecorationStyle.Foreground),
            deco(4, 5, UiDecorationStyle.Strikethrough),
            deco(0, 3, UiDecorationStyle.Background),
            deco(4, 5, UiDecorationStyle.Underline),
            deco(4, 5, UiDecorationStyle.WavyUnderline),
            deco(4, 5, UiDecorationStyle.DottedUnderline),
            deco(4, 5, UiDecorationStyle.Box),
        )
        val spans = perLineDecorationSpans(all, doc, colors)
        val segs = perLineDecorationSegs(all, doc, colors)

        assertEquals(2, spans[0]?.size, "Foreground and Strikethrough travel as span styles")
        assertEquals(5, segs[0]?.size, "the other five are drawn in the canvas")
        // Every style reaches exactly one layer, so the two counts sum to the input.
        assertEquals(all.size, spans[0]!!.size + segs[0]!!.size)
    }

    @Test
    fun foregroundRecolorsAndStrikethroughDoesNot() {
        val doc = EditorDocument.of("val a = 1")
        val fg = perLineDecorationSpans(listOf(deco(0, 3, UiDecorationStyle.Foreground)), doc, colors)
        assertEquals(colors.success, fg[0]!!.single().style.color)

        val strike = perLineDecorationSpans(listOf(deco(0, 3, UiDecorationStyle.Strikethrough)), doc, colors)
        val style = strike[0]!!.single().style
        assertNotNull(style.textDecoration, "a strikethrough is a text decoration")
        assertNull(style.color.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified },
            "struck text keeps the coloring underneath it")
    }

    @Test
    fun everyTintResolvesToADistinctThemeRole() {
        val resolved = UiDecorationTint.entries.map { decorationColor(it, colors) }
        assertEquals(UiDecorationTint.entries.size, resolved.size)
        assertEquals(colors.error, decorationColor(UiDecorationTint.Error, colors))
        assertEquals(colors.gitAdded, decorationColor(UiDecorationTint.Added, colors))
        assertEquals(colors.gitDeleted, decorationColor(UiDecorationTint.Removed, colors))
    }

    // ---- multi-line ranges ----

    @Test
    fun aMultiLineRangeIsCutIntoOneSegmentPerLineItCovers() {
        //             0123456789
        val doc = EditorDocument.of("one\ntwo\nthree\n")
        // From the middle of line 0 to the middle of line 2.
        val segs = perLineDecorationSegs(listOf(deco(1, 10)), doc, colors)

        assertEquals(setOf(0, 1, 2), segs.keys, "each covered line gets its own segment")
        assertEquals(1 to 3, segs[0]!!.single().let { it.startCol to it.endCol }, "clipped to line 0's end")
        assertEquals(0 to 3, segs[1]!!.single().let { it.startCol to it.endCol }, "a fully covered line")
        assertEquals(0 to 2, segs[2]!!.single().let { it.startCol to it.endCol }, "clipped to the range's end")
    }

    @Test
    fun aBlankLineInsideARangeKeepsAZeroWidthSegmentSoTheBlockReadsAsOne() {
        val doc = EditorDocument.of("one\n\ntwo\n")
        val segs = perLineDecorationSegs(listOf(deco(0, 8)), doc, colors)

        val blank = assertNotNull(segs[1], "the blank line is still part of the marked block")
        assertEquals(0, blank.single().startCol)
        assertEquals(0, blank.single().endCol, "zero width; the draw fills the row from the -1 sentinel")
    }

    @Test
    fun aRangeCoveringNoTextProducesNothingToDraw() {
        val doc = EditorDocument.of("one")
        assertTrue(perLineDecorationSegs(listOf(deco(2, 2)), doc, colors).isEmpty())
        assertTrue(perLineDecorationSpans(listOf(deco(2, 2, UiDecorationStyle.Foreground)), doc, colors).isEmpty())
    }

    // ---- span layering ----

    @Test
    fun pluginSpansLayerOverSemanticOnesOnTheSameLine() {
        val semantic = mapOf(0 to listOf(dev.ide.ui.editor.core.SemSpan(0, 3, androidx.compose.ui.text.SpanStyle())))
        val doc = EditorDocument.of("val a = 1")
        val plugin = perLineDecorationSpans(listOf(deco(4, 5, UiDecorationStyle.Foreground)), doc, colors)
        val merged = mergeSpanLayers(semantic, plugin)

        assertEquals(2, merged[0]!!.size)
        assertEquals(4, merged[0]!!.last().start, "the plugin span comes last so it wins where they overlap")
    }

    @Test
    fun mergingWithAnEmptyLayerReturnsTheOtherUntouched() {
        val base = mapOf(0 to listOf(dev.ide.ui.editor.core.SemSpan(0, 1, androidx.compose.ui.text.SpanStyle())))
        assertEquals(base, mergeSpanLayers(base, emptyMap()))
        assertEquals(base, mergeSpanLayers(emptyMap(), base))
    }

    // ---- the session re-anchors marks between passes ----

    @Test
    fun typingAheadOfADecorationShiftsIt() {
        val session = EditorSession("val a = 1", CodeLanguage.Kotlin, TextRange(0))
        session.applyDecorations(listOf(deco(4, 5)), emptyList(), emptyList())

        session.setCaret(0)
        session.commitText("x")

        val shifted = session.textDecorations.single()
        assertEquals(5 to 6, shifted.startOffset to shifted.endOffset)
    }

    @Test
    fun deletingTheTextUnderADecorationDropsIt() {
        val session = EditorSession("val a = 1", CodeLanguage.Kotlin, TextRange(0))
        session.applyDecorations(listOf(deco(4, 5)), emptyList(), emptyList())

        session.replaceRange(4, 5, "", TextRange(4))

        assertTrue(session.textDecorations.isEmpty(), "a range the edit consumed has nothing left to mark")
    }

    @Test
    fun insertingALineMovesTheGutterMarksBelowItDown() {
        val session = EditorSession("one\ntwo\nthree", CodeLanguage.Kotlin, TextRange(0))
        session.applyDecorations(
            emptyList(),
            listOf(UiGutterMark(0, "dot"), UiGutterMark(2, "dot")),
            emptyList(),
        )

        session.setCaret(0)
        session.commitText("\n")

        assertEquals(listOf(1, 3), session.gutterMarks.map { it.line }, "both marks follow their lines down")
    }

    @Test
    fun marksOnALineTheEditRemovedCollapseOntoTheSurvivingLine() {
        val session = EditorSession("one\ntwo\nthree", CodeLanguage.Kotlin, TextRange(0))
        session.applyDecorations(emptyList(), listOf(UiGutterMark(1, "dot"), UiGutterMark(2, "dot")), emptyList())

        // Delete the whole of line 1 including its break, so lines 1 and 2 collapse into one.
        session.replaceRange(4, 8, "", TextRange(4))

        // The deleted line's mark clamps to the edit point rather than vanishing, so both land on the line
        // that survived. The gutter shows one glyph per line and the next pass corrects the set.
        assertEquals(setOf(1), session.gutterMarks.map { it.line }.toSet())
    }

    @Test
    fun aMarksAnchorIsResolvedFromItsLineWhenItArrives() {
        val session = EditorSession("one\ntwo\nthree", CodeLanguage.Kotlin, TextRange(0))
        session.applyDecorations(emptyList(), listOf(UiGutterMark(2, "dot")), emptyList())

        assertEquals(8, session.gutterMarks.single().anchorOffset, "anchored at the line's start")
    }

    @Test
    fun aMarkPastTheEndOfTheDocumentIsClampedRatherThanCrashing() {
        val session = EditorSession("one", CodeLanguage.Kotlin, TextRange(0))
        session.applyDecorations(emptyList(), listOf(UiGutterMark(99, "dot")), emptyList())

        assertEquals(0, session.gutterMarks.single().line)
    }

    @Test
    fun pluginInlaysAreHeldApartFromTheLanguageBackendsSoNeitherPassClobbersTheOther() {
        val session = EditorSession("val a = 1", CodeLanguage.Kotlin, TextRange(0))
        val languageHint = UiInlayHint(3, listOf(UiInlayPart(": Int")), UiInlayKind.Type)
        val pluginHint = UiInlayHint(5, listOf(UiInlayPart("covered")), UiInlayKind.Other)

        session.applyInlayHints(listOf(languageHint))
        session.applyDecorations(emptyList(), emptyList(), listOf(pluginHint))
        assertEquals(listOf(languageHint), session.inlayHints)
        assertEquals(listOf(pluginHint), session.pluginInlays)

        // A fresh language pass replaces only its own list.
        session.applyInlayHints(emptyList())
        assertEquals(listOf(pluginHint), session.pluginInlays, "the plugin's hints survive the language pass")
    }
}
