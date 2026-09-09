package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A read of a library extension property whose `…Kt` facade can't be loaded (a Compose icon like
 * `Icons.Filled.Remove` → `RemoveKt`, when the icons artifact isn't reachable) is a RECOVERABLE cannot-load
 * boundary. Under partial rendering ([Interpreter] `tolerateGaps`) the statement that hit it is skipped so the
 * rest of the preview still renders; without partial rendering it aborts loudly (an [InterpreterBoundaryException]).
 */
class PreviewBoundaryTest {

    private val span = SourceSpan(0, 0)

    /** A block: read an extension property on an unloadable facade (throws a boundary), then yield 42. */
    private fun program(): Pair<Map<String, ResolvedFunction>, ResolvedFunction> {
        val unloadableIcon = RNode.PropertyGet(
            RNode.Const(0, null, span),
            Binding.Property("Remove", "madeup.icons.RemoveKt", backingField = false, isExtension = true),
            span,
        )
        val body = RNode.Block(listOf(unloadableIcon, RNode.Const(42, null, span)), isExpression = true, span)
        val fn = ResolvedFunction("f", emptyList(), body, emptyList())
        return mapOf("f/0" to fn) to fn
    }

    @Test fun partialRenderSkipsUnloadableFacadeStatement() {
        val (functions, fn) = program()
        val result = Interpreter(functions, tolerateGaps = true).call(fn, emptyList())
        assertEquals(42, result, "the statement after the unloadable icon should still run under partial rendering")
    }

    @Test fun withoutPartialRenderTheBoundaryAborts() {
        val (functions, fn) = program()
        assertFailsWith<InterpreterBoundaryException> {
            Interpreter(functions, tolerateGaps = false).call(fn, emptyList())
        }
    }
}
