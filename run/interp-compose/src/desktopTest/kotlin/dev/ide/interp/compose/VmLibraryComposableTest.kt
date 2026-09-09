package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import dev.ide.interp.InterpretedLambda
import dev.ide.jvm.ClassBytesSource
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** A real (host-compiled) function that invokes its callback — the shape of framework code driving an
 *  interpreted lambda after dispatch (a modifier block during measure, a click handler from input dispatch). */
fun runCallback(f: () -> Unit): String {
    f()
    return "ran"
}

/**
 * End to end through [ComposeDispatcher]: a library COMPOSABLE whose class the host cannot load (the
 * jar-only shape, forced through the test seam) is interpreted by the bytecode VM with the live Composer
 * threaded — including `$default` filling for omitted parameters and a composable content lambda proxied
 * into the interpreted body.
 */
class VmLibraryComposableTest {

    private val pkg = "dev.ide.interp.compose.libfixture"
    private val facade = "$pkg.LibComposableKt"
    private val span = SourceSpan(0, 0)

    private fun dispatcher(): ComposeDispatcher = ComposeDispatcher(
        libraryExecutor = VmLibraryExecutor(
            hostLoadable = { !it.startsWith(pkg) },
            source = ClassBytesSource.fromClasspath(),
        ),
    )

    private fun libComposableCall(name: String, paramCount: Int, rawArgs: List<RArg>): RNode.Call = RNode.Call(
        callee = ResolvedCallable.Library(
            displayName = name, ownerFqn = facade, methodName = name,
            paramTypes = List(paramCount) { null }, isStatic = true, isConstructor = false,
            isInline = false, isComposable = true,
        ),
        dispatch = DispatchKind.TOP_LEVEL,
        receiver = null,
        args = rawArgs,
        callSiteKey = CallSiteKey(11),
        source = span,
    )

    private fun compose(d: ComposeDispatcher, body: () -> Unit) {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(UnitApplier, recomposer)
        composition.setContent {
            d.composer = currentComposer
            body()
        }
        composition.dispose()
        recomposer.cancel()
    }

    @Test fun interpretedLibraryComposableRunsWithDefaultsFilled() {
        val d = dispatcher()
        val log = mutableListOf<String>()
        // `suffix` omitted: the VM path must set its `$default` bit so the interpreted body substitutes "!".
        val call = libComposableCall("LibBadge", paramCount = 3, rawArgs = listOf(RArg(RNode.Const(3, null, span)), RArg(RNode.Const(0, null, span))))
        compose(d) { d.dispatch(call, receiver = null, args = listOf(3, log)) }
        assertEquals(listOf("badge:3:6!"), log)
    }

    @Test fun composableContentLambdaThreadsIntoTheInterpretedContainer() {
        val d = dispatcher()
        val log = mutableListOf<String>()
        val content = object : InterpretedLambda {
            override val paramCount: Int = 0
            override fun invoke(args: List<Any?>): Any? {
                log.add("inner")
                return null
            }
        }
        val call = libComposableCall(
            "LibFrame", paramCount = 2,
            rawArgs = listOf(RArg(RNode.Const(0, null, span)), RArg(RNode.Const(0, null, span), trailingLambda = true)),
        )
        compose(d) { d.dispatch(call, receiver = null, args = listOf(log, content)) }
        assertEquals(listOf("frame<", "inner", ">"), log)
    }

    @Test fun composablePropertyGetterOnAVmOwnedReceiverThreadsTheComposer() {
        val executor = VmLibraryExecutor(hostLoadable = { !it.startsWith(pkg) }, source = ClassBytesSource.fromClasspath())
        val d = ComposeDispatcher(libraryExecutor = executor)
        val theme = executor.objectInstance("$pkg.LibTheme")!!
        assertEquals("plain", executor.propertyOrNull(theme, "plain")!!.value, "a plain property reads without a composer")

        var value: Any? = null
        compose(d) { value = d.readComposableProperty(theme, "label")?.value }
        assertEquals("themed:plain", value, "the composable getter ran interpreted with the live composer")
    }

    @Test fun interpretedLambdaFailureInvokedByRealCodeIsRecordedNotThrown() {
        // The graphicsLayer-block crash shape: REAL code invokes an interpreted callback whose body fails
        // (e.g. a property read on a slot a skipped statement never wrote). The guarded proxy must record the
        // failure on the partial-render channel and degrade, never propagate into the framework caller.
        val d = ComposeDispatcher()
        val throwing = object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? =
                throw dev.ide.interp.InterpreterException("no readable property `value` on kotlin.Unit")
        }
        val call = RNode.Call(
            callee = ResolvedCallable.Library(
                displayName = "runCallback", ownerFqn = "dev.ide.interp.compose.VmLibraryComposableTestKt",
                methodName = "runCallback", paramTypes = listOf(null),
                isStatic = true, isConstructor = false, isInline = false,
            ),
            dispatch = DispatchKind.TOP_LEVEL,
            receiver = null,
            args = listOf(RArg(RNode.Const(0, null, span), trailingLambda = true)),
            callSiteKey = CallSiteKey(21),
            source = span,
        )
        val result = d.dispatch(call, receiver = null, args = listOf(throwing))
        assertEquals("ran", result, "the real callee completed despite the failing interpreted callback")
        assertEquals(
            "no readable property `value` on kotlin.Unit",
            d.contentLambdaError?.message,
            "the failure was recorded for the partial-render chip",
        )
    }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }
}
