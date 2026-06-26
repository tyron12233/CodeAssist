package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The receiver-lambda scope functions `apply` / `with` / `T.run` (the `@InlineOnly` stdlib forms whose block
 * binds an implicit `this`). The lowerer gives the block a leading `<this>` slot and resolves its bare members
 * (calls AND property reads) against the receiver; the interpreter runs the block with the receiver bound there.
 */
class ScopeFunctionTest {

    @Test fun withResolvesBareMemberPropertyAndReturnsTheResult() {
        val code = "package demo\nfun f(): Int = with(\"hello\") { length }"
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test fun withResolvesBareMemberCall() {
        val code = "package demo\nfun f(): Boolean = with(\"hello\") { startsWith(\"h\") }"
        assertEquals(true, runProgram(code, "f/0", emptyList()))
    }

    @Test fun withExplicitThisReceiver() {
        val code = "package demo\nfun f(): Int = with(\"hello\") { this.length }"
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test fun runExtensionResolvesBareMemberAgainstTheReceiver() {
        val code = "package demo\nfun f(): Int = \"hello\".run { length }"
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test fun bareObjectNameInsideAReceiverScopeResolvesToTheObjectNotAScopeProperty() {
        // Regression: a bare object/type name used inside a receiver scope (`with("x") { Palette.red }`, like
        // `Column { MaterialTheme.colorScheme }`) must resolve to the OBJECT, not a (bogus) property read on the
        // scope receiver — the receiver-scope property path only binds members the scope's type actually has.
        val code = """
            package demo
            object Palette { val red: String = "RED" }
            fun f(): String = with("hello") { Palette.red }
        """.trimIndent()
        assertEquals("RED", runProgram(code, "f/0", emptyList()))
    }

    @Test fun applyReturnsTheReceiverAfterRunningTheBlock() {
        // `apply` runs the block (a bare member read here) with `x` as `this`, then yields `x` for the chain.
        val code = "package demo\nfun f(): Int = \"hi\".apply { length }.length"
        assertEquals(2, runProgram(code, "f/0", emptyList()))
    }
}
