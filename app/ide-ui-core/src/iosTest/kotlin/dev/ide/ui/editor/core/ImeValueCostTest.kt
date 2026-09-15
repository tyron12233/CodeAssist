package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What a keystroke costs the iOS IME bridge.
 *
 * `ComposeSceneMediator.ios.kt` drives the bridge through `request.value()`, which builds a
 * [TextFieldValue] and therefore materializes the whole document. `EditorDocument.text` is lazy but lives on
 * the document instance, and every edit produces a new instance, so the cache never survives a keystroke.
 * UIKit asks around the caret constantly, so the question is whether that is a rounding error or the thing
 * that makes a real file unusable.
 *
 * The comparison is against the same information read as a window out of the rope, which is what a
 * `CharSequence`-shaped path would cost.
 *
 * Measured on the simulator: 0.025 ms per keystroke at 200 lines, 0.044 ms at 1000, and 0.23 ms at 5000
 * (178 KB) against an 8.3 ms frame. So materializing per keystroke is affordable and needs no caching; the
 * windowed read is about three times cheaper at the top end, but both are far inside the budget. What would
 * hurt is the cost turning quadratic, which is what this guards: the ratio is machine-independent, unlike
 * an absolute millisecond bound, so it does not flake on a slower host.
 */
class ImeValueCostTest {

    private fun sessionOf(lines: Int): EditorSession {
        val text = buildString {
            repeat(lines) { i ->
                append("    val property").append(i).append(" = \"value ").append(i).append("\"\n")
            }
        }
        return EditorSession(text, CodeLanguage.Kotlin, TextRange(text.length / 2))
    }

    private fun measure(label: String, lines: Int, block: (EditorSession) -> Unit): Double {
        val session = sessionOf(lines)
        val caret = session.selection.min
        repeat(20) { block(session) } // warm up the lazy paths and the allocator
        val mark = TimeSource.Monotonic.markNow()
        repeat(KEYSTROKES) {
            // One keystroke: an edit (which replaces the document) followed by the reads UIKit makes.
            session.imeCommitText("x", 1)
            repeat(READS_PER_KEYSTROKE) { block(session) }
        }
        val perKeystrokeMs = mark.elapsedNow().inWholeMicroseconds / 1000.0 / KEYSTROKES
        println("  $label, $lines lines (${session.doc.length} chars): ${perKeystrokeMs} ms per keystroke")
        session.imeSetSelection(caret, caret)
        return perKeystrokeMs
    }

    @Test
    fun materializingTheDocumentPerKeystrokeIsMeasured() {
        println("iOS IME per-keystroke cost, $READS_PER_KEYSTROKE reads per keystroke:")
        val full = mutableListOf<Double>()
        val windowed = mutableListOf<Double>()
        for (lines in intArrayOf(200, 1_000, 5_000)) {
            full += measure("value() materializes", lines) { s ->
                TextFieldValue(s.doc.text, s.selection, s.composing)
            }
            windowed += measure("windowed rope read  ", lines) { s ->
                val end = s.selection.min
                s.doc.substring((end - WINDOW).coerceAtLeast(0), end)
            }
        }
        // Materializing necessarily scales with the document, so the guard is the SHAPE of that growth: 5000
        // lines is 5.2x the text of 1000, so linear is ~5x and quadratic would be ~27x. Anything past the
        // budget below means a per-keystroke cost that no longer merely tracks file size.
        val growth = full.last() / full[1]
        assertTrue(growth < LINEAR_GROWTH_BUDGET, "value() grew ${growth}x for 5.2x the text: $full")

        // A fixed window out of the rope must not scale with the document at all.
        assertTrue(
            windowed.last() <= windowed.first() * SLACK,
            "a windowed read should not scale with document size: $windowed",
        )
    }

    private companion object {
        const val KEYSTROKES = 200
        /** UIKit asks for text around the caret several times per edit (autocorrect, the loupe, range maths). */
        const val READS_PER_KEYSTROKE = 4
        const val WINDOW = 256
        const val SLACK = 6.0

        /** 5.2x the text; linear lands near 5x, quadratic near 27x. */
        const val LINEAR_GROWTH_BUDGET = 12.0
    }
}
