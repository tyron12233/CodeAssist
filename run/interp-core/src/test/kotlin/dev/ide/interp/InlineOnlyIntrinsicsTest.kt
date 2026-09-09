package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `@kotlin.internal.InlineOnly` stdlib scope functions (`repeat`/`let`/`also`/`takeIf`/`takeUnless`/`run`)
 * have NO callable JVM method, so the reflective dispatcher can't invoke them. The interpreter executes them
 * as intrinsics, running the interpreted lambda directly. (Regression for the Compose-preview `repeat(n) {
 * … }` "no static repeat" / "Start/end imbalance" failure.)
 */
class InlineOnlyIntrinsicsTest {

    private val span = SourceSpan(0, 0)
    private fun run(body: RNode): Any? =
        Interpreter(emptyMap()).call(ResolvedFunction("f", emptyList(), RNode.Block(listOf(body), false, span), emptyList()), emptyList())

    private fun stdlib(name: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = "kotlin.StandardKt", methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = true,
    )

    private fun ext(name: String, facade: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = facade, methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = true,
    )

    /** A `receiver.name()` zero-arg extension intrinsic call (`"  ".isNotBlank()`). */
    private fun predicate(name: String, facade: String, receiver: Any?): Any? =
        run(RNode.Call(ext(name, facade), DispatchKind.EXTENSION, receiver = const(receiver), args = emptyList(), callSiteKey = CallSiteKey(0), source = span))

    private val STRINGS = "kotlin.text.StringsKt"
    private val COLLECTIONS = "kotlin.collections.CollectionsKt"

    private fun const(value: Any?) = RNode.Const(value, null, span)
    private fun arg(node: RNode) = RArg(node)

    @Test
    fun repeatRunsTheActionForEachIndex() {
        // repeat(3) { i -> sink.add(i) } — accumulate the indices the action observed via a captured var.
        val sink = ArrayList<Int>()
        val indexSlot = SlotId(0)
        // The body calls a SOURCE-less host method? We can't reach `sink` from the tree, so instead sum indices
        // into a `var total` local and assert. Build: var total = 0; repeat(3){ i -> total = total + i }; total.
        val totalSlot = SlotId(1)
        val totalDecl = RNode.LocalVar(totalSlot, "total", mutable = true, initializer = const(0), span)
        val plus = RNode.Call(
            ResolvedCallable.Library("plus", null, "plus", emptyList(), false, false, false),
            DispatchKind.OPERATOR,
            receiver = RNode.Name(Binding.Local(totalSlot, "total", true), span),
            args = listOf(arg(RNode.Name(Binding.Local(indexSlot, "i", false), span))),
            callSiteKey = CallSiteKey(0), source = span,
        )
        val accumulate = RNode.Assign(RNode.Name(Binding.Local(totalSlot, "total", true), span), plus, span)
        val action = RNode.Lambda(listOf(RParam(indexSlot, "i", null)), RNode.Block(listOf(accumulate), false, span), emptyList(), span)
        val repeatCall = RNode.Call(stdlib("repeat"), DispatchKind.TOP_LEVEL, null, listOf(arg(const(3)), arg(action)), CallSiteKey(1), span)
        val total = RNode.Name(Binding.Local(totalSlot, "total", true), span)
        val body = RNode.Block(listOf(totalDecl, repeatCall, total), isExpression = true, span)
        assertEquals(3, run(body), "repeat(3) should run the action for indices 0,1,2 → 0+1+2")
    }

    @Test
    fun letPassesTheReceiverAsIt() {
        // 7.let { it } → 7
        val itSlot = SlotId(0)
        val lambda = RNode.Lambda(listOf(RParam(itSlot, "it", null)), RNode.Block(listOf(RNode.Name(Binding.Local(itSlot, "it", false), span)), true, span), emptyList(), span)
        val call = RNode.Call(stdlib("let"), DispatchKind.EXTENSION, receiver = const(7), args = listOf(arg(lambda)), callSiteKey = CallSiteKey(0), source = span)
        assertEquals(7, run(call))
    }

    @Test
    fun alsoReturnsTheReceiverNotTheBlockResult() {
        // 7.also { 99 } → 7 (the block's value is discarded)
        val lambda = RNode.Lambda(listOf(RParam(SlotId(0), "it", null)), RNode.Block(listOf(const(99)), true, span), emptyList(), span)
        val call = RNode.Call(stdlib("also"), DispatchKind.EXTENSION, receiver = const(7), args = listOf(arg(lambda)), callSiteKey = CallSiteKey(0), source = span)
        assertEquals(7, run(call))
    }

    @Test
    fun takeIfKeepsOrDropsTheReceiver() {
        fun takeIf(pred: Boolean): Any? {
            val lambda = RNode.Lambda(listOf(RParam(SlotId(0), "it", null)), RNode.Block(listOf(const(pred)), true, span), emptyList(), span)
            return run(RNode.Call(stdlib("takeIf"), DispatchKind.EXTENSION, receiver = const(7), args = listOf(arg(lambda)), callSiteKey = CallSiteKey(0), source = span))
        }
        assertEquals(7, takeIf(true), "takeIf { true } keeps the receiver")
        assertNull(takeIf(false), "takeIf { false } yields null")
    }

    @Test
    fun runWithoutReceiverInvokesTheBlock() {
        // run { 42 } → 42
        val lambda = RNode.Lambda(emptyList(), RNode.Block(listOf(const(42)), true, span), emptyList(), span)
        val call = RNode.Call(stdlib("run"), DispatchKind.TOP_LEVEL, receiver = null, args = listOf(arg(lambda)), callSiteKey = CallSiteKey(0), source = span)
        assertEquals(42, run(call))
    }

    @Test
    fun isNotBlankOnCharSequence() {
        assertEquals(false, predicate("isNotBlank", STRINGS, "   "), "blank string → !isNotBlank")
        assertEquals(true, predicate("isNotBlank", STRINGS, " x "), "non-blank string → isNotBlank")
    }

    @Test
    fun isNotEmptyOnStringAndCollection() {
        assertEquals(false, predicate("isNotEmpty", STRINGS, ""), "empty string")
        assertEquals(true, predicate("isNotEmpty", STRINGS, " "), "blank-but-non-empty string")
        assertEquals(false, predicate("isNotEmpty", COLLECTIONS, emptyList<Int>()), "empty list")
        assertEquals(true, predicate("isNotEmpty", COLLECTIONS, listOf(1)), "non-empty list")
        assertEquals(true, predicate("isNotEmpty", COLLECTIONS, mapOf(1 to 2)), "non-empty map")
    }

    @Test
    fun isNullOrBlankHandlesNull() {
        assertEquals(true, predicate("isNullOrBlank", STRINGS, null), "null → isNullOrBlank")
        assertEquals(true, predicate("isNullOrBlank", STRINGS, "  "), "blank → isNullOrBlank")
        assertEquals(false, predicate("isNullOrBlank", STRINGS, "x"), "non-blank → !isNullOrBlank")
    }

    @Test
    fun isNullOrEmptyHandlesNullStringAndCollection() {
        assertEquals(true, predicate("isNullOrEmpty", STRINGS, null), "null → isNullOrEmpty")
        assertEquals(true, predicate("isNullOrEmpty", STRINGS, ""), "empty string → isNullOrEmpty")
        assertEquals(false, predicate("isNullOrEmpty", STRINGS, "x"), "non-empty string")
        assertEquals(true, predicate("isNullOrEmpty", COLLECTIONS, emptyList<Int>()), "empty list → isNullOrEmpty")
        assertEquals(false, predicate("isNullOrEmpty", COLLECTIONS, listOf(1)), "non-empty list")
    }

    @Test
    fun unmodeledInlineOnlyFunctionGivesALegibleError() {
        // An @InlineOnly function (no JVM method) that ISN'T in the intrinsic table: the dispatch failure must be
        // rewritten into a clear "inline-only … not modeled" message, not the cryptic `no static … on
        // kotlin.StandardKt`. (The scope functions `let`/`also`/`run`/`apply`/`with` ARE modeled now, so this
        // uses a synthetic unmodeled callee to keep exercising the fallback path.)
        val callee = ResolvedCallable.Library(
            displayName = "someUnmodeledInlineFn", ownerFqn = "kotlin.StandardKt", methodName = "someUnmodeledInlineFn",
            paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = true,
        )
        val call = RNode.Call(callee, DispatchKind.TOP_LEVEL, receiver = null, args = listOf(arg(const(7))), callSiteKey = CallSiteKey(0), source = span)
        val ex = assertFailsWith<InterpreterException> { run(call) }
        assertTrue("inline-only" in (ex.message ?: ""), "expected an inline-only diagnostic; got: ${ex.message}")
    }
}
