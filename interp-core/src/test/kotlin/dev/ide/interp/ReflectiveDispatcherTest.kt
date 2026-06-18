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
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The reflective library/member/constructor dispatch path (4a), exercised directly with hand-built
 * [RNode.Call]s — independent of whether the resolver can resolve `java.*` from a sparse test classpath.
 * This is the same mechanism the interpreter delegates to for non-source calls.
 */
class ReflectiveDispatcherTest {

    private val dispatcher = ReflectiveDispatcher()

    private fun lib(owner: String, name: String, isCtor: Boolean = false, isStatic: Boolean = false) =
        ResolvedCallable.Library(
            displayName = name, ownerFqn = owner, methodName = if (isCtor) "<init>" else name,
            paramTypes = emptyList(), isStatic = isStatic, isConstructor = isCtor, isInline = false,
            descriptorPrecise = true,
        )

    private fun call(dispatch: DispatchKind, callee: ResolvedCallable) =
        RNode.Call(callee, dispatch, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(0), source = SourceSpan(0, 0))

    @Test
    fun staticTopLevelMethod() {
        val c = call(DispatchKind.TOP_LEVEL, lib("java.lang.Math", "max", isStatic = true))
        assertEquals(7, dispatcher.dispatch(c, receiver = null, args = listOf(3, 7)))
    }

    @Test
    fun varargStaticMethodPacksTrailingArgsIntoAnArray() {
        // `String.format("%s-%s", "a", "b")` — the JVM method is `format(String, Object[])` (arity 2, vararg),
        // so the two trailing args must pack into an Object[] rather than fail the exact-arity match. This is
        // the `mutableStateListOf("a", "b")` execution path.
        val c = call(DispatchKind.TOP_LEVEL, lib("java.lang.String", "format", isStatic = true))
        assertEquals("a-b", dispatcher.dispatch(c, receiver = null, args = listOf("%s-%s", "a", "b")))
    }

    @Test
    fun varargWithZeroTrailingArgsStillBinds() {
        // `String.format("plain")` — the vararg absorbs ZERO trailing args (an empty Object[]).
        val c = call(DispatchKind.TOP_LEVEL, lib("java.lang.String", "format", isStatic = true))
        assertEquals("plain", dispatcher.dispatch(c, receiver = null, args = listOf("plain")))
    }

    @Test
    fun memberCallOnANonPublicJdkClassResolvesToThePublicInterfaceMethod() {
        // `listOf("a","b")[1]` → `get(1)`. Kotlin's `listOf` returns a `java.util.Arrays$ArrayList` — a
        // NON-public, non-exported JDK class. Invoking its own `get` fails under the module system
        // (IllegalAccessException even after setAccessible); dispatch must re-resolve to the public `List.get`,
        // whose virtual invoke reaches the real impl. This is the `items[selectedItem]` execution path.
        val list = java.util.Arrays.asList("a", "b") // an Arrays$ArrayList
        val c = call(DispatchKind.MEMBER, lib("java.util.List", "get"))
        assertEquals("b", dispatcher.dispatch(c, receiver = list, args = listOf(1)))
    }

    @Test
    fun constructorThenInstanceMember() {
        val sb = dispatcher.dispatch(call(DispatchKind.CONSTRUCTOR, lib("java.lang.StringBuilder", "StringBuilder", isCtor = true)), null, emptyList())
        assertTrue(sb is StringBuilder, "constructor should produce a StringBuilder")
        // Member dispatch reflects on the runtime class — no precise static owner needed.
        val result = dispatcher.dispatch(call(DispatchKind.MEMBER, lib("java.lang.StringBuilder", "append")), sb, listOf("hi"))
        assertEquals("hi", (result as StringBuilder).toString())
    }

    @Test
    fun kotlinMappedTypeOwnerResolves() {
        // A Kotlin classifier owner (kotlin.text.StringBuilder) maps to its JVM class for reflection.
        val sb = dispatcher.dispatch(call(DispatchKind.CONSTRUCTOR, lib("kotlin.text.StringBuilder", "StringBuilder", isCtor = true)), null, emptyList())
        assertTrue(sb is StringBuilder)
    }

    @Test
    fun unloadableOwnerFailsLoudly() {
        val c = call(DispatchKind.TOP_LEVEL, lib("com.nope.DoesNotExistKt", "f", isStatic = true))
        assertFailsWith<InterpreterException> { dispatcher.dispatch(c, null, emptyList()) }
    }

    @Test
    fun propertyReadGoesThroughTheKotlinGetter() {
        // A `receiver.value` read resolves to the Kotlin getter `getValue()` — the same path a
        // `MutableState.value` read takes (which is what registers the snapshot dependency under Compose).
        val span = SourceSpan(0, 0)
        val slot = SlotId(0)
        val body = RNode.PropertyGet(RNode.Name(Binding.Param(slot, "h"), span), Binding.Property("value", null, false), span)
        val fn = ResolvedFunction("f", listOf(RParam(slot, "h", null)), body, emptyList())
        assertEquals("hi", Interpreter(emptyMap()).call(fn, listOf(Holder("hi"))))
    }

    @Test
    fun trailingLambdaBindsToLastParamForComposableDetection() {
        // `LazyListScope.items(items, key = …, contentType = …, itemContent: @Composable …)` called as
        // `items(xs) { … }`: source args [list, lambda] don't line up with declaration order, but the Kotlin
        // trailing-lambda rule binds the lambda to the LAST value parameter (the composable `itemContent`).
        val composable = KotlinType("kotlin.Function2", isComposable = true)
        val plain = KotlinType("kotlin.Function1")
        val list = KotlinType("kotlin.collections.List")
        val items = ResolvedCallable.Library(
            displayName = "items", ownerFqn = "X", methodName = "items",
            paramTypes = listOf(list, plain, plain, composable),
            isStatic = false, isConstructor = false, isInline = false,
        )
        val lambda = object : InterpretedLambda {
            override val paramCount = 1
            override fun invoke(args: List<Any?>): Any? = null
        }
        assertEquals(listOf(false, true), composableParamFlags(items, listOf("xs", lambda)))
        // A non-trailing-lambda call lines up positionally; the extension receiver is skipped.
        assertEquals(listOf(false, false), composableParamFlags(items, listOf<Any?>("recv", "xs"), leadingReceiver = true))
    }

    @Test
    fun composableContentLambdaRoutesThroughTheStrategy() {
        // The Compose bridge's seam: a lambda bound to a `@Composable` param routes through the injected
        // strategy with composableParam=true, even though the callee (`forEach`) isn't itself composable.
        var sawComposable: Boolean? = null
        val strategy = LambdaProxyStrategy { lam, fi, composable ->
            sawComposable = composable
            java.lang.reflect.Proxy.newProxyInstance(fi.classLoader, arrayOf(fi)) { _, m, a ->
                if (m.name == "accept") lam.invoke(a?.toList() ?: emptyList()) else null
            }
        }
        val collected = ArrayList<Any?>()
        val lambda = object : InterpretedLambda {
            override val paramCount = 1
            override fun invoke(args: List<Any?>): Any? { collected.add(args.getOrNull(0)); return null }
        }
        val callee = ResolvedCallable.Library(
            displayName = "forEach", ownerFqn = "java.util.ArrayList", methodName = "forEach",
            paramTypes = listOf(KotlinType("kotlin.Function1", isComposable = true)),
            isStatic = false, isConstructor = false, isInline = false, descriptorPrecise = true,
        )
        val c = RNode.Call(callee, DispatchKind.MEMBER, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(0), source = SourceSpan(0, 0))
        ReflectiveDispatcher(lambdaProxies = strategy).dispatch(c, receiver = arrayListOf(1, 2, 3), args = listOf(lambda))
        assertEquals(true, sawComposable, "a composable content-lambda param must route through the strategy as composable")
        assertEquals(listOf<Any?>(1, 2, 3), collected.toList(), "the proxied lambda should receive each element")
    }

    @Test
    fun composablePropertyReadFallsBackToTheDispatcherSeam() {
        // A property with no plain no-arg getter (a `@Composable` getter takes a Composer) — the interpreter
        // can't read it reflectively, so it delegates to the dispatcher's composable-property seam. Here a stub
        // dispatcher stands in for the Compose bridge.
        val span = SourceSpan(0, 0)
        val slot = SlotId(0)
        val get = RNode.PropertyGet(RNode.Name(Binding.Param(slot, "h"), span), Binding.Property("themed", null, false), span)
        val fn = ResolvedFunction("f", listOf(RParam(slot, "h", null)), get, emptyList())
        val seam = object : Dispatcher {
            override fun dispatch(call: RNode.Call, receiver: Any?, args: List<Any?>): Any? =
                throw InterpreterException("not used")
            override fun readComposableProperty(receiver: Any, propertyName: String): ComposablePropertyValue? =
                if (propertyName == "themed") ComposablePropertyValue("themed:$receiver") else null
        }
        assertEquals("themed:X", Interpreter(emptyMap(), seam).call(fn, listOf("X")))
    }

    @Test
    fun propertyWriteGoesThroughTheKotlinSetter() {
        // `h.value = "bye"` resolves to the Kotlin setter `setValue(x)` — the same path a `MutableState.value`
        // write takes (which is what invalidates the recompose scope under Compose). The block yields the
        // re-read value to prove the write landed.
        val span = SourceSpan(0, 0)
        val slot = SlotId(0)
        val ref = { RNode.Name(Binding.Param(slot, "h"), span) }
        val set = RNode.PropertySet(ref(), Binding.Property("value", null, false), RNode.Const("bye", null, span), span)
        val read = RNode.PropertyGet(ref(), Binding.Property("value", null, false), span)
        val fn = ResolvedFunction("f", listOf(RParam(slot, "h", null)), RNode.Block(listOf(set, read), isExpression = true, span), emptyList())
        val holder = Holder("hi")
        assertEquals("bye", Interpreter(emptyMap()).call(fn, listOf(holder)))
        assertEquals("bye", holder.value, "the setter must have mutated the holder")
    }

    @Test
    fun memberExtensionDispatchesOnTheScopeInstance() {
        // `Mod.scoped(5)` where `scoped` is a MEMBER extension declared inside `Scope` (like `RowScope.weight`):
        // it's an instance method on the scope `scoped(Mod, int)`, so MEMBER_EXTENSION must invoke it on the
        // scope with the extension receiver (`Mod`) as the first argument.
        val span = SourceSpan(0, 0)
        val scopeSlot = SlotId(0)
        val modSlot = SlotId(1)
        val callee = ResolvedCallable.Library(
            displayName = "scoped", ownerFqn = "ignored", methodName = "scoped",
            paramTypes = emptyList(), isStatic = false, isConstructor = false, isInline = false,
        )
        val call = RNode.Call(
            callee, DispatchKind.MEMBER_EXTENSION,
            receiver = RNode.Name(Binding.Param(modSlot, "m"), span),
            args = listOf(RArg(RNode.Const(5, null, span))),
            callSiteKey = CallSiteKey(0), source = span,
            dispatchReceiver = RNode.Name(Binding.Param(scopeSlot, "s"), span),
        )
        val fn = ResolvedFunction(
            "f", listOf(RParam(scopeSlot, "s", null), RParam(modSlot, "m", null)),
            RNode.Block(listOf(call), isExpression = true, span), emptyList(),
        )
        assertEquals("scoped:5", Interpreter(emptyMap()).call(fn, listOf(Scope(), Mod())))
    }

    @Test
    fun memberExtensionWithDefaultParamOnAnInterfaceScope() {
        // `RowScope.weight(weight, fill = true)` exactly: a member extension WITH a defaulted param, declared in
        // an INTERFACE scope. Omitting `fill` needs the `weighted$default` synthetic — which for an interface
        // member lives on the interface (or its DefaultImpls), NOT the runtime impl class. The dispatch must find
        // it there. The TodoScreen `Modifier.weight(1f)` case.
        val span = SourceSpan(0, 0)
        val scopeSlot = SlotId(0)
        val modSlot = SlotId(1)
        val callee = ResolvedCallable.Library(
            displayName = "weighted", ownerFqn = "ignored", methodName = "weighted",
            paramTypes = emptyList(), isStatic = false, isConstructor = false, isInline = false,
        )
        val call = RNode.Call(
            callee, DispatchKind.MEMBER_EXTENSION,
            receiver = RNode.Name(Binding.Param(modSlot, "m"), span),
            args = listOf(RArg(RNode.Const(5, null, span))), // omit the defaulted `fill`
            callSiteKey = CallSiteKey(0), source = span,
            dispatchReceiver = RNode.Name(Binding.Param(scopeSlot, "s"), span),
        )
        val fn = ResolvedFunction(
            "f", listOf(RParam(scopeSlot, "s", null), RParam(modSlot, "m", null)),
            RNode.Block(listOf(call), isExpression = true, span), emptyList(),
        )
        assertEquals("w=5 fill=true", Interpreter(emptyMap()).call(fn, listOf(ScopeImpl(), Mod())))
    }

    /** A Kotlin class with a mutable `value` property → `getValue()`/`setValue(x)` (a `MutableState` stand-in). */
    class Holder(var value: String)

    /** A scope holding a MEMBER extension on [Mod] (`fun Mod.scoped(...)`), mirroring `RowScope.weight`. */
    class Mod
    class Scope { fun Mod.scoped(n: Int): String = "scoped:$n" }

    /** An INTERFACE scope with a defaulted-param member extension, exactly like `RowScope.weight`. */
    interface ScopeIface { fun Mod.weighted(w: Int, fill: Boolean = true): String }
    class ScopeImpl : ScopeIface { override fun Mod.weighted(w: Int, fill: Boolean): String = "w=$w fill=$fill" }
}
