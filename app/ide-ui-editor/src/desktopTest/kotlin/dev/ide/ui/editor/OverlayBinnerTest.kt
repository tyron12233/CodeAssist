package dev.ide.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.SemSpan
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.caColors
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import dev.ide.ui.theme.colors.ResolvedColorScheme
import dev.ide.ui.theme.colors.SchemeDefaults
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-edit overlay re-bin against the whole-file bin: after any sequence of edits, batches and fresh
 * passes, the per-line overlays the binner has written must equal what binning the session's current lists
 * over the whole document produces. The recorder below mirrors the render cache's overlay store, including
 * the line splice the session drives through `onLinesShifted`.
 */
class OverlayBinnerTest {

    private val scheme = ResolvedColorScheme(BuiltInColorSchemes.DEFAULT, true, SchemeDefaults.EMPTY)
    private val chrome = caColors(true, CaAccent.Lime)
    private val inlayStyle = SpanStyle(color = Color.Gray)

    /** An overlay store with the render cache's semantics: per-line values, spliced on line shifts. */
    private class Recorder : OverlaySink {
        var semantic = HashMap<Int, List<SemSpan>>()
        var inlays = HashMap<Int, List<InlayPiece>>()
        var lineWrites = 0

        override fun setSemanticSpans(spans: Map<Int, List<SemSpan>>) {
            semantic = HashMap(spans.filterValues { it.isNotEmpty() })
        }
        override fun setSemanticLine(line: Int, spans: List<SemSpan>) {
            lineWrites++
            if (spans.isEmpty()) semantic.remove(line) else semantic[line] = spans
        }
        override fun setInlays(inlays: Map<Int, List<InlayPiece>>, style: SpanStyle) {
            this.inlays = HashMap(inlays.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.sortedBy { it.col } })
        }
        override fun setInlayLine(line: Int, pieces: List<InlayPiece>) {
            lineWrites++
            if (pieces.isEmpty()) inlays.remove(line) else inlays[line] = pieces.sortedBy { it.col }
        }

        fun shift(from: Int, delta: Int) {
            semantic = spliced(semantic, from, delta)
            inlays = spliced(inlays, from, delta)
        }

        private fun <V> spliced(map: HashMap<Int, V>, from: Int, delta: Int): HashMap<Int, V> {
            val out = HashMap<Int, V>()
            for ((k, v) in map) {
                when {
                    k >= from -> if (k + delta >= 0) out[k + delta] = v
                    delta < 0 && k >= from + delta -> {} // overwritten by the lines moving up
                    else -> out[k] = v
                }
            }
            return out
        }
    }

    private val kinds = listOf("class", "function", "parameter", "localVariable", "property", "unknownKind")

    private fun randomTokens(rnd: Random, len: Int): List<UiSemanticToken> = List(rnd.nextInt(0, 60)) {
        val start = rnd.nextInt(0, len + 1)
        val mods = UiHighlightModifier.entries.filter { rnd.nextInt(6) == 0 }.toSet()
        UiSemanticToken(start, start + rnd.nextInt(1, 12), kinds[rnd.nextInt(kinds.size)], mods)
    }

    private fun randomHints(rnd: Random, len: Int): List<UiInlayHint> = List(rnd.nextInt(0, 12)) {
        UiInlayHint(
            rnd.nextInt(0, len + 1), listOf(UiInlayPart(": T$it")), UiInlayKind.Type,
            paddingLeft = rnd.nextBoolean(),
        )
    }

    private fun randomText(rnd: Random, n: Int): String {
        val alphabet = "abc xyz\n\n()"
        return buildString { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    private fun expectedSemantic(s: EditorSession): Map<Int, List<SemSpan>> =
        perLineSemanticSpans(s.semanticTokens, s.doc, scheme).filterValues { it.isNotEmpty() }

    private fun expectedInlays(s: EditorSession): Map<Int, List<InlayPiece>> =
        perLineInlayPieces(s.inlayHints, s.pluginInlays, s.doc, 0, s.doc.lineCount)
            .mapValues { (_, v) -> v.sortedBy { it.col } }

    @Test
    fun perEditRebinMatchesTheWholeFileBin() {
        val rnd = Random(21)
        repeat(20) { round ->
            val s = EditorSession(randomText(rnd, 400), CodeLanguage.Kotlin, TextRange(0))
            val rec = Recorder()
            s.onLinesShifted = { from, delta -> rec.shift(from, delta) }
            s.applySemanticTokens(randomTokens(rnd, s.doc.length))
            s.applyInlayHints(randomHints(rnd, s.doc.length))
            val binner = OverlayBinner()
            val key = Any()
            fun sync() = binner.syncInto(s, key, rec, scheme, chrome, inlayStyle)
            sync()
            var sawSemantic = false
            repeat(150) { step ->
                val len = s.doc.length
                when (rnd.nextInt(20)) {
                    0 -> s.applySemanticTokens(randomTokens(rnd, len))
                    1 -> s.applyInlayHints(randomHints(rnd, len))
                    2, 3 -> { // a batch of edits, as an IME or a completion accept makes
                        s.beginBatch()
                        repeat(rnd.nextInt(1, 4)) {
                            val l = s.doc.length
                            val a = rnd.nextInt(0, l + 1)
                            val b = (a + rnd.nextInt(0, 5)).coerceAtMost(l)
                            s.replaceRange(a, b, randomText(rnd, rnd.nextInt(0, 4)), TextRange(a))
                        }
                        s.endBatch()
                    }
                    else -> {
                        val a = rnd.nextInt(0, len + 1)
                        val b = (a + rnd.nextInt(0, 4)).coerceAtMost(len)
                        s.replaceRange(a, b, randomText(rnd, rnd.nextInt(0, 3)), TextRange(a))
                    }
                }
                // Sometimes several edits land before the next composition syncs.
                if (rnd.nextInt(3) != 0) {
                    sync()
                    assertEquals(expectedSemantic(s), rec.semantic, "round $round step $step: semantic")
                    assertEquals(expectedInlays(s), rec.inlays, "round $round step $step: inlays")
                    if (rec.semantic.isNotEmpty()) sawSemantic = true
                }
            }
            sync()
            assertEquals(expectedSemantic(s), rec.semantic)
            assertEquals(expectedInlays(s), rec.inlays)
            assertTrue(sawSemantic, "the fixture must actually produce semantic spans")
        }
    }

    @Test
    fun aKeystrokeRewritesOnlyTheEditedLine() {
        val text = (0 until 200).joinToString("\n") { "val v$it = $it" }
        val s = EditorSession(text, CodeLanguage.Kotlin, TextRange(0))
        val rec = Recorder()
        s.onLinesShifted = { from, delta -> rec.shift(from, delta) }
        s.applySemanticTokens((0 until 200).map { UiSemanticToken(s.doc.lineStart(it) + 4, s.doc.lineStart(it) + 6, "property") })
        val binner = OverlayBinner()
        binner.syncInto(s, this, rec, scheme, chrome, inlayStyle)
        rec.lineWrites = 0
        val at = s.doc.lineStart(100) + 5
        s.replaceRange(at, at, "x", TextRange(at + 1))
        binner.syncInto(s, this, rec, scheme, chrome, inlayStyle)
        assertEquals(1, rec.lineWrites, "one semantic line rewritten, and no inlay work with no inlays")
        assertEquals(expectedSemantic(s), rec.semantic)
    }
}
