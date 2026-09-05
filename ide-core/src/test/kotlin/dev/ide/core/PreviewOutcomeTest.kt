package dev.ide.core

import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // -- superseded-pass guards (the stale-tree race on a slow device) ----------------------------------

    @Test fun previewAttemptAnswersNullForAnOrdinaryFailure() {
        runBlocking {
            assertNull(previewAttempt<String> { error("engine blew up") })
        }
    }

    @Test fun previewAttemptLetsCancellationThrough() {
        // The bug this closes: `runCatching` swallowed the cancellation of a superseded pass, so the pass kept
        // running and published a program lowered from an already-stale buffer over the live one.
        runBlocking {
            val job = launch {
                assertFailsWith<CancellationException> {
                    previewAttempt<String> { throw CancellationException("superseded") }
                }
            }
            job.join()
        }
    }

    @Test fun supersededPassRefusesToPublish() {
        // A pass whose engine call finished BEFORE the cancellation arrived must still drop its result: it has
        // no other way to know the buffer moved on.
        runBlocking {
            var published = false
            val reachedTheSlowCall = CompletableDeferred<Unit>()
            val letItFinish = CompletableDeferred<Unit>()
            val job = launch {
                reachedTheSlowCall.complete(Unit)
                letItFinish.await() // stand in for a slow lower still in flight
                ensurePreviewPassCurrent()
                published = true
            }
            reachedTheSlowCall.await()
            job.cancel() // the next keystroke supersedes this pass
            letItFinish.complete(Unit) // ...and only THEN does its engine call come back
            job.join()
            assertFalse(published, "a superseded pass must not publish its result")
        }
    }

    @Test fun currentPassPublishes() {
        runBlocking {
            var published = false
            launch { ensurePreviewPassCurrent(); published = true }.join()
            assertTrue(published, "a pass that was never superseded must publish")
        }
    }
}
