package dev.ide.core

import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The "retain last good render" rule the preview hosts use so a mid-edit / syntactically-broken buffer never
 * blanks the preview or reaches the Compose runtime (see [resolvePreviewOutcome] / [PreviewOutcome]).
 */
class PreviewOutcomeTest {

    private fun lowered(name: String): LoweredComposePreview {
        val fn = ResolvedFunction(name, emptyList(), RNode.Block(emptyList(), isExpression = false, SourceSpan(0, 0)), emptyList())
        return LoweredComposePreview(fn, mapOf("$name/0" to fn))
    }

    @Test fun freshLowerRenders() {
        val fresh = lowered("A")
        assertEquals(
            PreviewOutcome.Render(fresh),
            resolvePreviewOutcome(fresh, lastGood = lowered("old")) { error("reasons must not be evaluated") },
        )
    }

    @Test fun brokenBufferKeepsLastGoodRender() {
        // The core fix: a null (broken/incomplete) lower keeps the previous clean render rather than blanking.
        val last = lowered("Good")
        assertEquals(
            PreviewOutcome.Render(last),
            resolvePreviewOutcome(fresh = null, lastGood = last) { error("reasons must not be evaluated when a last-good render exists") },
        )
    }

    @Test fun brokenBufferWithNoPriorRenderReportsReasons() {
        assertEquals(
            PreviewOutcome.Unavailable(listOf("unresolved: Text")),
            resolvePreviewOutcome(fresh = null, lastGood = null) { listOf("unresolved: Text") },
        )
    }

    @Test fun reasonsEvaluatedLazilyOnlyWhenUnavailable() {
        var evaluated = false
        resolvePreviewOutcome(fresh = lowered("A"), lastGood = null) { evaluated = true; emptyList() }
        assertFalse(evaluated, "diagnostics must not run when a fresh lower succeeds")
        resolvePreviewOutcome(fresh = null, lastGood = lowered("G")) { evaluated = true; emptyList() }
        assertFalse(evaluated, "diagnostics must not run when the last good render is kept")
    }
}
