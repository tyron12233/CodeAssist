package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [ComposableAbi]'s default-parameter handling against the REAL Compose compiler: [RecordValues]
 * below is transformed by the Compose plugin (it processes test sources too), so its `$default`/`$changed`
 * synthetic parameters are exactly what `androidx.compose` emits. We reflectively invoke it through the ABI
 * bridge with only SOME arguments supplied and assert the composable observed its own defaults for the rest —
 * the on-device path for `Text("x")` (one of Material3 `Text`'s ~15 defaulted parameters) in miniature.
 */
class ComposableAbiDefaultsTest {

    @BeforeTest
    fun reset() {
        Capture.label = null; Capture.count = -1; Capture.flag = null; Capture.pendingClick = null
    }

    @Test
    fun cardOnClickBindsToANonNullProxyInvocableAfterComposition() {
        // The reported `Card(onClick = { … }) { content }` crash: tapping the card NPEs "Function0.invoke() on a
        // null object reference". onClick is a REQUIRED plain `() -> Unit` first param alongside a trailing
        // @Composable content lambda. It must bind to a non-null proxy that runs at TAP time (invoked here AFTER
        // the composition settles, exactly like a tap outside a composition pass).
        var ran = false
        val onClick = object : dev.ide.interp.InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? { ran = true; return null }
        }
        val content = object : dev.ide.interp.InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = null
        }
        val facade = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt"
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "CardLike", ownerFqn = facade, methodName = "CardLike",
            paramTypes = listOf(
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Function0"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Boolean"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Function0"),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            paramNames = listOf("onClick", "enabled", "content"),
        )
        // `CardLike(onClick = { … }) { content }` — onClick named (in-parens), content the trailing lambda.
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(
                dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const(null, null, span), "onClick", false, false),
                dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const(null, null, span), null, false, true),
            ),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(41), source = span,
        )
        val dispatcher = ComposeDispatcher()
        composeOnce {
            dispatcher.composer = currentComposer
            dispatcher.dispatch(call, receiver = null, args = listOf<Any?>(onClick, content))
        }
        assertEquals(true, Capture.pendingClick != null, "onClick must bind to a non-null proxy, not leave the Card's onClick null")
        Capture.pendingClick?.invoke() // the tap, outside the composition pass
        assertEquals(true, ran, "the interpreted onClick must run when the card is tapped")
    }

    @Test
    fun omittedParametersFallBackToTheirDefaults() {
        composeOnce {
            call(listOf<Any?>("supplied"))
        }
        assertEquals("supplied", Capture.label, "the supplied first arg should bind to parameter 0")
        assertEquals(42, Capture.count, "an omitted Int parameter should use its default (42)")
        assertEquals(true, Capture.flag, "an omitted Boolean parameter should use its default (true)")
    }

    @Test
    fun aSuppliedArgThatDoesNotFitItsParameterFallsBackToTheDefault() {
        // The field bug `ABI invoke mismatch for …Text-fLXpl1I: params=[…Modifier…] args=[…java.lang.Long…]`:
        // an upstream value lands on a typed parameter it can't fit (here a `String` on the `Int count` slot,
        // mirroring a value-class `long` on `Text`'s `Modifier` slot). Reflection would throw an argument-type
        // mismatch and unwind the whole composition; the ABI now drops the non-fitting arg so the parameter
        // takes its own default instead — the preview renders rather than failing.
        composeOnce {
            call(listOf<Any?>("supplied", "notAnInt"))
        }
        assertEquals("supplied", Capture.label, "the fitting first arg still binds to parameter 0")
        assertEquals(42, Capture.count, "the non-fitting `count` arg is dropped → its default (42) is used")
        assertEquals(true, Capture.flag, "the untouched `flag` keeps its default (true)")
    }

    @Test
    fun allParametersSuppliedSkipsDefaults() {
        composeOnce {
            call(listOf<Any?>("all", 7, false))
        }
        assertEquals("all", Capture.label)
        assertEquals(7, Capture.count)
        assertEquals(false, Capture.flag)
    }

    @Test
    fun wrongDeclaredParamCountStillResolvesViaRuntimeComposerIndex() {
        // The resolver's declaredParamCount can be wrong (it sees a different build of the library than the
        // one loaded at runtime). The ABI must derive the real param count from the runtime method's Composer
        // position, not trust the hint. Pass a bogus count and confirm it still binds + defaults correctly.
        composeOnce {
            val composer: Any = currentComposer
            ComposableAbi.startGroup(composer, KEY)
            try {
                ComposableAbi.call(
                    ownerFqn = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt",
                    method = "RecordValues",
                    originalArgs = listOf<Any?>("supplied"),
                    composer = composer,
                    declaredParamCount = 0, // deliberately wrong (real count is 3)
                )
            } finally {
                ComposableAbi.endGroup(composer)
            }
        }
        assertEquals("supplied", Capture.label)
        assertEquals(42, Capture.count, "omitted params still default even when the declared count is wrong")
        assertEquals(true, Capture.flag)
    }

    @Test
    fun trailingContentLambdaBindsToLastParamWhileOthersDefault() {
        // `Wrap { … }` (cf. `Column { … }`): the only supplied arg is the trailing content lambda, which must
        // bind to the LAST parameter; `tag` is omitted and defaults. The content lambda must actually run.
        var ran = false
        val lambda = object : dev.ide.interp.InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? { ran = true; return null }
        }
        composeOnce {
            val composer: Any = currentComposer
            ComposableAbi.startGroup(composer, KEY)
            try {
                ComposableAbi.call(
                    ownerFqn = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt",
                    method = "Wrap",
                    originalArgs = listOf<Any?>(lambda),
                    composer = composer,
                    declaredParamCount = 2,
                    lambdaProxy = ::contentProxy,
                )
            } finally {
                ComposableAbi.endGroup(composer)
            }
        }
        assertEquals("wtag", Capture.label, "the omitted `tag` parameter should use its default")
        assertEquals(true, ran, "the trailing content lambda should have been invoked by the composable")
    }

    /** Wrap an interpreted content lambda as a proxy of its transformed `@Composable () -> Unit` interface
     *  (a `Function2<Composer, Int, Unit>`); strips the trailing Composer/`$changed`. */
    private fun contentProxy(lambda: dev.ide.interp.InterpretedLambda, fi: Class<*>): Any =
        java.lang.reflect.Proxy.newProxyInstance(fi.classLoader, arrayOf(fi)) { _, m, _ ->
            if (m.name == "invoke") lambda.invoke(emptyList()) else null
        }

    @Test
    fun realTransformedComposableIsDetectedByMetadataDecode() {
        // The Material3 `Text` case in miniature: a REAL Compose-transformed top-level composable. After
        // transformation the method has a `Composer` parameter but NO `@Composable` annotation, so detection
        // must rely on the Composer-parameter descriptor. This decodes the actual compiled bytecode through
        // the resolver's metadata path and asserts `isComposable` is set.
        val facade = "dev/ide/interp/compose/ComposableAbiDefaultsTestKt.class"
        val bytes = javaClass.classLoader.getResourceAsStream(facade)!!.readBytes()
        val decoded = dev.ide.lang.kotlin.symbols.KotlinMetadata.decode(bytes, null)
            ?: error("facade should decode as Kotlin metadata")
        val recordValues = decoded.topLevel.first { it.name == "RecordValues" }
        assertEquals(true, recordValues.isComposable, "a transformed @Composable (Composer param) must be detected")
        assertEquals(false, decoded.topLevel.first { it.name == "plainTop" }.isComposable, "a plain fn must not be")
    }

    @Test
    fun isComposableCallDetectsTransformedComposableMethods() {
        val facade = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt"
        assertEquals(true, ComposableAbi.isComposableCall(facade, "RecordValues"), "a @Composable top-level fn has a Composer param")
        assertEquals(true, ComposableAbi.isComposableCall(facade, "Wrap"), "Wrap is @Composable")
        assertEquals(false, ComposableAbi.isComposableCall(facade, "plainTop"), "a plain fn has no Composer param")
        assertEquals(false, ComposableAbi.isComposableCall("does.not.Exist", "Whatever"), "an unloadable owner is not composable")
    }

    @Test
    fun misdetectedComposableStillRendersViaDispatcher() {
        // The real-world failure: the resolver decoded a binary composable with `isComposable = false` (stale
        // cache / metadata gap). Dispatched inside a composition, it must STILL render — via the reflective
        // `isComposableCall` cross-check, or the retry-through-the-ABI safety net — not fall through to a plain
        // static lookup (`no static RecordValues(1)`).
        val facade = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt"
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "RecordValues", ownerFqn = facade, methodName = "RecordValues",
            paramTypes = listOf(
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Int"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Boolean"),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = false, // <- mis-detected
        )
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(7), source = span,
        )
        val dispatcher = ComposeDispatcher()
        composeOnce {
            dispatcher.composer = currentComposer
            dispatcher.dispatch(call, receiver = null, args = listOf<Any?>("hi"))
        }
        assertEquals("hi", Capture.label, "the supplied arg should reach the composable even when mis-detected")
        assertEquals(42, Capture.count, "omitted params still default")
    }

    @Test
    fun composeDispatcherReordersNamedArguments() {
        // The end-to-end named-argument path: `RecordValues(flag = false, label = "named")` — supplied out of
        // declaration order AND omitting the middle `count`. The dispatcher evaluates args in source order, then
        // reorders them to declaration order (with an OmittedArg hole for `count`) before the ABI binds JVM
        // slots; `count` falls back to its default (42). This is the `Text(text = …, textAlign = …)` case.
        val facade = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt"
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        fun arg(name: String, v: Any?) =
            dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const(v, null, span), name)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "RecordValues", ownerFqn = facade, methodName = "RecordValues",
            paramTypes = listOf(
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Int"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Boolean"),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            paramNames = listOf("label", "count", "flag"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(arg("flag", false), arg("label", "named")),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(9), source = span,
        )
        val dispatcher = ComposeDispatcher()
        composeOnce {
            dispatcher.composer = currentComposer
            dispatcher.dispatch(call, receiver = null, args = listOf<Any?>(false, "named"))
        }
        assertEquals("named", Capture.label, "the named `label` arg should reach parameter 0")
        assertEquals(42, Capture.count, "the omitted middle `count` should use its default")
        assertEquals(false, Capture.flag, "the named `flag` arg should reach the last parameter")
    }

    @Test
    fun composableMemberCallOnAnObjectThreadsTheComposer() {
        // `CardDefaults.cardColors(containerColor = …)` in miniature: a @Composable MEMBER of an object. The
        // dispatcher must route it through the composer ABI (instance invoke on the receiver), not plain
        // reflection — which would fail with `no method describeColors(1)` (the method has a Composer param).
        // Out-of-order/omitted named args are bound by the reorder path, the object instance is the receiver.
        val facade = "dev.ide.interp.compose.Defaults"
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        fun arg(name: String, v: Any?) =
            dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const(v, null, span), name)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "describeColors", ownerFqn = facade, methodName = "describeColors",
            paramTypes = listOf(
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
            ),
            isStatic = false, isConstructor = false, isInline = false, isComposable = true,
            paramNames = listOf("container", "content"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.MEMBER, receiver = null,
            args = listOf(arg("content", "C")), // omit the leading `container` default
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(11), source = span,
        )
        val dispatcher = ComposeDispatcher()
        var result: Any? = null
        composeOnce {
            dispatcher.composer = currentComposer
            result = dispatcher.dispatch(call, receiver = Defaults, args = listOf<Any?>("C"))
        }
        assertEquals("box:C", result, "the member composable should bind `content` and default `container` to box")
    }

    @Test
    fun extensionComposableThreadsTheComposerWithReceiverAsFirstParam() {
        // The `RowScope.NavigationBarItem(selected = …) { … }` case: a @Composable EXTENSION on a scope. The
        // Compose plugin transforms it to a STATIC facade method whose FIRST parameter is the receiver
        // (`BarItem(FakeBarScope, boolean, String, int, Composer, …)`). The dispatcher must thread the composer
        // and prepend the scope receiver — NOT fall through to a plain static lookup (`no static BarItem(1)`).
        // Omitted value params (`label`/`count`) still default; the `$default` mask excludes the receiver.
        val facade = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt"
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "BarItem", ownerFqn = facade, methodName = "BarItem",
            paramTypes = listOf(
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Boolean"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Int"),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            paramNames = listOf("selected", "label", "count"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.EXTENSION, receiver = null,
            args = listOf(dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const(true, null, span))),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(13), source = span,
        )
        val dispatcher = ComposeDispatcher()
        composeOnce {
            dispatcher.composer = currentComposer
            dispatcher.dispatch(call, receiver = FakeBar, args = listOf<Any?>(true))
        }
        assertEquals(true, Capture.flag, "`selected` should bind past the prepended receiver")
        assertEquals("deflabel", Capture.label, "an omitted extension value param should use its default")
        assertEquals(99, Capture.count, "the default mask must exclude the receiver (else the wrong bit defaults)")
    }

    @Test
    fun unboxedValueClassArgIsBoxedForANullableValueClassParam() {
        // The `Text(textAlign = TextAlign.Center)` case: `TextAlign.Center` evaluates to the UNBOXED underlying
        // int, but `textAlign: TextAlign?` is nullable → its JVM type is the BOXED value class. The ABI must box
        // the int (via `box-impl`) before invoking, or reflection throws an argument-type mismatch that unwinds
        // through the composition and corrupts the slot table.
        composeOnce {
            val composer: Any = currentComposer
            ComposableAbi.startGroup(composer, KEY)
            try {
                ComposableAbi.call(
                    ownerFqn = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt",
                    method = "RecordTag",
                    originalArgs = listOf<Any?>(7), // the unboxed underlying value of Tag(7)
                    composer = composer,
                    declaredParamCount = 1,
                )
            } finally {
                ComposableAbi.endGroup(composer)
            }
        }
        assertEquals(7, Capture.count, "the unboxed value-class arg should be boxed into the nullable param")
    }

    @Test
    fun composablePropertyGetterIsReadWithAThreadedComposer() {
        // The `MaterialTheme.colorScheme` case: a REAL @Composable property whose getter the Compose plugin
        // transforms to `getPalette(Composer, int)`. The interpreter can't invoke it (no no-arg getter); the
        // dispatcher threads the live composer through it.
        val dispatcher = ComposeDispatcher()
        var value: Any? = null
        composeOnce {
            dispatcher.composer = currentComposer
            value = dispatcher.readComposableProperty(FakeTheme, "palette")?.value
        }
        assertEquals("themed", value, "the composable property getter should read through the threaded composer")
    }

    @Test
    fun plainPropertyIsNotHandledByTheComposableSeam() {
        // A plain (non-composable) property has a no-arg getter, so the composable seam declines it (returns
        // null) and the interpreter reads it directly.
        val dispatcher = ComposeDispatcher()
        composeOnce {
            dispatcher.composer = currentComposer
            assertEquals(null, dispatcher.readComposableProperty(Capture, "count"), "a plain property is not a composable getter")
        }
    }

    @Test
    fun previewRenderErrorIsCaughtAndReportedNotThrown() {
        // A preview whose body dispatches to a non-existent function: the interpreter throws mid-composition.
        // The renderer must balance the Compose group, swallow the throw, and invoke onError — not abort the
        // IDE's composition.
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "Bogus", ownerFqn = "does.not.ExistKt", methodName = "Bogus",
            paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false,
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(0), source = span,
        )
        val body = dev.ide.lang.kotlin.interp.RNode.Block(listOf(call), isExpression = false, source = span)
        val entry = dev.ide.lang.kotlin.interp.ResolvedFunction("Boom", emptyList(), body, emptyList())

        var reported: Throwable? = null
        val renderer = ComposePreviewRenderer()
        composeOnce {
            // Named arg, NOT a trailing lambda: `onPartialError` is Render's last parameter, so a trailing
            // lambda binds there instead — this top-level failure surfaces through `onError`.
            renderer.Render(entry, emptyMap(), onError = { reported = it }) // must not throw out of the composition
        }
        assertEquals(true, reported != null, "a runtime interpreter failure should be reported via onError")
    }

    @Test
    fun inParensLambdaArgBindsPositionallyNotToTheLastParam() {
        // The `Switch(checked = …, onCheckedChange = { … })` crash in miniature. `Toggle`'s shape mirrors it: a
        // function-typed parameter (`onChange`) followed by a defaulted parameter (`source`, cf. Switch's
        // `interactionSource`). The lambda is an IN-PARENS argument, so it binds to `onChange` (param 1) — NOT
        // remapped to the last `source` parameter. The old trailing-lambda heuristic misbound it to `source`,
        // leaving the lambda proxied into the wrong type (the real null-`interactions` ThumbNode crash) and
        // `onChange` defaulted to null. Driven through the dispatcher with NAMED args, exactly like the report.
        val facade = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt"
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val lambda = object : dev.ide.interp.InterpretedLambda {
            override val paramCount = 1
            override fun invoke(args: List<Any?>): Any? = null
        }
        // A named lambda argument written inside the parens (trailingLambda = false).
        fun arg(name: String, v: dev.ide.lang.kotlin.interp.RNode) = dev.ide.lang.kotlin.interp.RArg(v, name, false, false)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "Toggle", ownerFqn = facade, methodName = "Toggle",
            paramTypes = listOf(
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Boolean"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Function1"),
                dev.ide.lang.kotlin.symbols.KotlinType("dev.ide.interp.compose.ToggleSource"),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            paramNames = listOf("checked", "onChange", "source"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(arg("checked", dev.ide.lang.kotlin.interp.RNode.Const(true, null, span)), arg("onChange", dev.ide.lang.kotlin.interp.RNode.Const(null, null, span))),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(21), source = span,
        )
        val dispatcher = ComposeDispatcher()
        composeOnce {
            dispatcher.composer = currentComposer
            dispatcher.dispatch(call, receiver = null, args = listOf<Any?>(true, lambda))
        }
        assertEquals(true, Capture.flag, "`checked` should bind to parameter 0")
        assertEquals("nullsource", Capture.label, "`source` must default (null) — the lambda is `onChange`, not `source`")
    }

    @Test
    fun overloadSelectionUsesTheSameTrailingLambdaRuleAsBinding() {
        // The reported `OutlinedTextField` freeze: `OutlinedTextField(value = "", onValueChange = { … })` with
        // its `label`/`placeholder` removed. Two overloads share the `value: String` first parameter shape;
        // `Field` mirrors it — the intended overload has a NON-interface last parameter (`extra: String`, cf.
        // OutlinedTextField's `colors: TextFieldColors`), a sibling `Decoy` overload's last parameter is a
        // lambda. Named-arg reordering drops the omitted trailing params, leaving `["", λ]` with NO interior
        // holes. OVERLOAD SELECTION used to re-derive its own trailing-lambda rule (last arg is a lambda + no
        // holes ⇒ remap onto the last parameter) that BINDING did not, so it rejected the real overload (its
        // last param isn't an interface) and picked the `Decoy` — whose slots the args can't fill, dropping
        // `value` → a non-null `value` bound null → NPE that unwinds the preview. Selection must use the SAME
        // trailing-lambda decision as binding (here: an in-parens named lambda ⇒ NO remap ⇒ bind positionally).
        val facade = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt"
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val lambda = object : dev.ide.interp.InterpretedLambda {
            override val paramCount = 1
            override fun invoke(args: List<Any?>): Any? = null
        }
        fun arg(name: String, v: dev.ide.lang.kotlin.interp.RNode) = dev.ide.lang.kotlin.interp.RArg(v, name, false, false)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "Field", ownerFqn = facade, methodName = "Field",
            paramTypes = listOf(
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.Function1"),
                dev.ide.lang.kotlin.symbols.KotlinType("kotlin.String"),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            paramNames = listOf("value", "onValueChange", "extra"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(arg("value", dev.ide.lang.kotlin.interp.RNode.Const("hi", null, span)), arg("onValueChange", dev.ide.lang.kotlin.interp.RNode.Const(null, null, span))),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(23), source = span,
        )
        val dispatcher = ComposeDispatcher()
        composeOnce {
            dispatcher.composer = currentComposer
            dispatcher.dispatch(call, receiver = null, args = listOf<Any?>("hi", lambda))
        }
        assertEquals("field:hi", Capture.label, "the `value`-taking overload must be chosen and `value` bound (not dropped → null)")
    }

    @Test
    fun positionalInParensLambdaDoesNotRemapToLastParam() {
        // The same shape, purely positional through the ABI (`Toggle(true, { })` — the `{ }` is an in-parens
        // value argument, not a trailing lambda): with lastArgIsTrailingLambda = false it binds to `onChange`,
        // and `source` defaults.
        val lambda = object : dev.ide.interp.InterpretedLambda {
            override val paramCount = 1
            override fun invoke(args: List<Any?>): Any? = null
        }
        composeOnce {
            val composer: Any = currentComposer
            ComposableAbi.startGroup(composer, KEY)
            try {
                ComposableAbi.call(
                    ownerFqn = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt",
                    method = "Toggle",
                    originalArgs = listOf<Any?>(true, lambda),
                    composer = composer,
                    declaredParamCount = 3,
                    lambdaProxy = ::anyProxy,
                    lastArgIsTrailingLambda = false, // a lambda inside the parens, not a trailing lambda
                )
            } finally {
                ComposableAbi.endGroup(composer)
            }
        }
        assertEquals(true, Capture.flag, "`checked` binds to parameter 0")
        assertEquals("nullsource", Capture.label, "`source` defaults — the lambda bound to `onChange`")
    }

    @Test
    fun contentOnlyCallPicksTheContentOverloadNotAContentlessOneWithAnInterfaceParam() {
        // The `Box { … }` crash: `Box` has a content-taking overload AND a content-less `Box(modifier: Modifier)`.
        // `Modifier` is an INTERFACE (with several abstract methods), so the trailing content lambda used to be
        // considered a fit for the content-less overload's `modifier` param — which then won the fewest-params
        // tiebreak, binding the lambda onto `modifier`; the library then called a Modifier method on the lambda
        // proxy (→ null → NPE in `materializeModifier`). `BoxLike` mirrors the shape: [PseudoModifier] is a
        // non-functional interface. The content-only call must pick the CONTENT overload, run the content, and let
        // `modifier` default — never bind the lambda to the interface `modifier` slot.
        var ran = false
        val lambda = object : dev.ide.interp.InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? { ran = true; return null }
        }
        composeOnce {
            val composer: Any = currentComposer
            ComposableAbi.startGroup(composer, KEY)
            try {
                ComposableAbi.call(
                    ownerFqn = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt",
                    method = "BoxLike",
                    originalArgs = listOf<Any?>(lambda),
                    composer = composer,
                    declaredParamCount = 2, // resolver's content overload
                    lambdaProxy = ::contentProxy,
                )
            } finally {
                ComposableAbi.endGroup(composer)
            }
        }
        assertEquals(true, ran, "the content overload must be chosen and its content lambda invoked")
        assertEquals("default", Capture.label, "`modifier` must default (not be bound to the content lambda proxy)")
    }

    /** A general proxy: wrap an interpreted lambda as any single-method functional interface. */
    private fun anyProxy(lambda: dev.ide.interp.InterpretedLambda, fi: Class<*>): Any =
        java.lang.reflect.Proxy.newProxyInstance(fi.classLoader, arrayOf(fi)) { _, m, a ->
            if (m.name == "invoke") lambda.invoke(a?.toList() ?: emptyList()) else null
        }

    @Test
    fun constructorOmittingDefaultedValueClassParamsRoutesThroughTheInitDefaultSynthetic() {
        // The reported `SpanStyle(...)` crash, in miniature against REAL compiler-emitted bytecode: [Paint]'s
        // defaulted params include an inline VALUE CLASS (cf. SpanStyle's `color: Color`/`fontSize: TextUnit`,
        // unboxed to `long` in the JVM signature). `Paint(name = "custom")` omits `hue` and `size`; there is no
        // exact-arity constructor, so Kotlin's `<init>$default(long, int, String, int mask,
        // DefaultConstructorMarker)` synthetic fills the defaults. Driven through the real ComposeDispatcher,
        // which routes a CONSTRUCTOR to its reflective fallback (no composer needed — it isn't a composable call).
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "Paint", ownerFqn = Paint::class.java.name, methodName = "<init>",
            paramTypes = emptyList(), isStatic = false, isConstructor = true, isInline = false,
            descriptorPrecise = true, paramNames = listOf("hue", "size", "name"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.CONSTRUCTOR, receiver = null,
            args = listOf(dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const("custom", null, span), name = "name")),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(31), source = span,
        )
        val result = ComposeDispatcher().dispatch(call, receiver = null, args = listOf<Any?>("custom")) as Paint
        assertEquals(0xFF0000L, result.hue.v, "an omitted value-class param uses the compiler's default (unboxed long)")
        assertEquals(12, result.size, "an omitted Int param uses its default")
        assertEquals("custom", result.name, "the supplied named arg binds to its slot")
    }

    @Test
    fun constructorAcceptsAnUnboxedValueClassArgAndDefaultsAnOmittedMiddleParam() {
        // The `SpanStyle(color = Color.Red)` case: the supplied value-class arg arrives UNBOXED (a
        // java.lang.Long, as the interpreter represents value classes), which must fit the synthetic's `long`
        // real-param slot; the middle `size` is omitted and defaults — exercising an interior mask bit.
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        fun arg(name: String, v: Any?) =
            dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const(v, null, span), name)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "Paint", ownerFqn = Paint::class.java.name, methodName = "<init>",
            paramTypes = emptyList(), isStatic = false, isConstructor = true, isInline = false,
            descriptorPrecise = true, paramNames = listOf("hue", "size", "name"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.CONSTRUCTOR, receiver = null,
            args = listOf(arg("hue", 0x00FF00L), arg("name", "x")),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(33), source = span,
        )
        val result = ComposeDispatcher().dispatch(call, receiver = null, args = listOf<Any?>(0x00FF00L, "x")) as Paint
        assertEquals(0x00FF00L, result.hue.v, "an unboxed value-class arg fits the synthetic's long slot")
        assertEquals(12, result.size, "the omitted middle Int param defaults")
        assertEquals("x", result.name)
    }

    @Test
    fun constructorBoxesAnUnboxedValueClassArgForANullableValueClassParam() {
        // The `SpanStyle(fontStyle = FontStyle.Italic)` case: a NULLABLE value-class param (`tint: Hue?`) is
        // BOXED in the JVM signature, but the supplied value-class arg arrives UNBOXED (a java.lang.Long). The
        // synthetic-constructor path must box it (via the value class's static `box-impl`) before the invoke,
        // exactly as the composable ABI does for `Text(textAlign = …)`. `text` is omitted and defaults.
        val span = dev.ide.lang.kotlin.interp.SourceSpan(0, 0)
        val callee = dev.ide.lang.kotlin.interp.ResolvedCallable.Library(
            displayName = "Banner", ownerFqn = Banner::class.java.name, methodName = "<init>",
            paramTypes = emptyList(), isStatic = false, isConstructor = true, isInline = false,
            descriptorPrecise = true, paramNames = listOf("tint", "text"),
        )
        val call = dev.ide.lang.kotlin.interp.RNode.Call(
            callee, dev.ide.lang.kotlin.interp.DispatchKind.CONSTRUCTOR, receiver = null,
            args = listOf(dev.ide.lang.kotlin.interp.RArg(dev.ide.lang.kotlin.interp.RNode.Const(0x0000FFL, null, span), name = "tint")),
            callSiteKey = dev.ide.lang.kotlin.interp.CallSiteKey(35), source = span,
        )
        val result = ComposeDispatcher().dispatch(call, receiver = null, args = listOf<Any?>(0x0000FFL)) as Banner
        assertEquals(0x0000FFL, result.tint?.v, "the unboxed value-class arg is boxed into the nullable param")
        assertEquals("hi", result.text, "the omitted trailing param defaults")
    }

    // --- harness ---

    private val recomposers = ArrayList<Recomposer>()

    @AfterTest
    fun tearDown() = recomposers.forEach { it.cancel() }

    @Composable
    private fun call(args: List<Any?>) {
        val composer: Any = currentComposer
        ComposableAbi.startGroup(composer, KEY)
        try {
            ComposableAbi.call(
                ownerFqn = "dev.ide.interp.compose.ComposableAbiDefaultsTestKt",
                method = "RecordValues",
                originalArgs = args,
                composer = composer,
                declaredParamCount = 3,
            )
        } finally {
            ComposableAbi.endGroup(composer)
        }
    }

    /** Run [content] through one synchronous initial composition with a no-op applier (no UI). */
    private fun composeOnce(content: @Composable () -> Unit) {
        val recomposer = Recomposer(CoroutineScope(BroadcastFrameClock()).coroutineContext)
        recomposers += recomposer
        val composition = Composition(UnitApplier, recomposer)
        composition.setContent(content)
        composition.dispose()
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

    private companion object {
        const val KEY = 1234
    }
}

/** A real Compose-compiled composable with defaults; the ABI bridge fills the omitted ones via `$default`. */
@Composable
fun RecordValues(label: String = "default", count: Int = 42, flag: Boolean = true) {
    Capture.label = label
    Capture.count = count
    Capture.flag = flag
}

/** A composable with a trailing `@Composable` content lambda + a defaulted leading param (cf. `Column`). */
@Composable
fun Wrap(tag: String = "wtag", content: @Composable () -> Unit) {
    Capture.label = tag
    content()
}

/** A plain (non-composable) top-level function — no `Composer` parameter, for the negative detection case. */
fun plainTop(x: Int): Int = x

/** An inline value class (cf. `TextAlign`) — represented unboxed, but BOXED as a nullable parameter. */
@JvmInline
value class Tag(val v: Int)

/** A @Composable with a NULLABLE value-class parameter (cf. `Text(textAlign: TextAlign? = null)`): its JVM
 *  param type is the boxed `Tag`, so an unboxed underlying value must be boxed before the reflective invoke. */
@Composable
fun RecordTag(tag: Tag? = null) {
    Capture.count = tag?.v ?: -1
}

/** An object with a @Composable MEMBER (cf. `CardDefaults.cardColors`): the Compose plugin transforms it to an
 *  instance method `describeColors(String, String, Composer, int $changed, int $default)`. */
object Defaults {
    @Composable
    fun describeColors(container: String = "box", content: String = "text"): String = "$container:$content"
}

/** A scope (cf. `RowScope`) and a @Composable EXTENSION on it (cf. `RowScope.NavigationBarItem`). The Compose
 *  plugin transforms the extension to a STATIC facade method `BarItem(FakeBarScope $this, boolean, String, int,
 *  Composer, int $changed, int $default)` — the receiver is the first JVM parameter, not the invoke target. */
interface FakeBarScope
object FakeBar : FakeBarScope

@Composable
fun FakeBarScope.BarItem(selected: Boolean, label: String = "deflabel", count: Int = 99) {
    Capture.flag = selected
    Capture.label = label
    Capture.count = count
}

/** A REAL @Composable property (cf. `MaterialTheme.colorScheme`): the Compose plugin transforms the getter to
 *  `getPalette(Composer, int)`. `@ReadOnlyComposable` keeps it group-free so a bare read is well-formed. */
object FakeTheme {
    val palette: String
        @Composable @ReadOnlyComposable get() = "themed"
}

/** A marker (cf. `MutableInteractionSource`): a defaulted parameter that follows a function-typed one — the
 *  shape behind the `Switch(checked, onCheckedChange = { })` crash. */
interface ToggleSource

/** Mirrors `Switch`'s parameter shape: a function-typed `onChange` followed by a DEFAULTED `source`. An
 *  in-parens lambda must bind to `onChange`, leaving `source` to default — not be remapped onto `source`. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, source: ToggleSource? = null) {
    Capture.flag = checked
    Capture.label = if (source == null) "nullsource" else "hassource"
}

/** An inline value class backed by a `long` (cf. `Color`, whose underlying `ULong` is a `long` slot) — its
 *  constructor parameters appear UNBOXED in JVM signatures, so the `<init>$default` synthetic carries `long`,
 *  not `Hue`. */
@JvmInline
value class Hue(val v: Long)

/** A class whose all-defaulted constructor params include an inline value class — the `SpanStyle` shape for
 *  the synthetic-constructor path (a value-class `hue` unboxed to `long`, an `Int`, and a `String`). */
class Paint(val hue: Hue = Hue(0xFF0000L), val size: Int = 12, val name: String = "default")

/** A class with a NULLABLE value-class param (cf. `SpanStyle.fontStyle: FontStyle?`): nullability forces the
 *  JVM param to the BOXED `Hue`, so an unboxed supplied value must be boxed before the synthetic invoke. */
class Banner(val tint: Hue? = null, val text: String = "hi")

/** The `OutlinedTextField` overload shape in miniature: a required non-null `value: String` first parameter
 *  and a NON-interface LAST parameter (`extra`, cf. `colors: TextFieldColors`). The intended overload the
 *  reordered `Field(value = "hi", onValueChange = { })` call must resolve to. */
@Composable
fun Field(value: String, onValueChange: (String) -> Unit, extra: String = "def") {
    Capture.label = "field:$value"
}

/** A sibling `Field` overload whose LAST parameter is a lambda — the decoy a trailing-lambda remap in overload
 *  selection would wrongly pick (the intended overload's last param isn't an interface, so a remap rejects it).
 *  Binding never remaps here (an in-parens named lambda), so selection must not either. */
@Composable
fun Field(value: String, decoy: () -> Unit = {}) {
    Capture.label = "decoy"
}

/** A NON-functional interface (several abstract methods, cf. `androidx.compose.ui.Modifier`) — a lambda must
 *  NOT be considered a fit for a parameter of this type during overload selection. */
interface PseudoModifier {
    fun combine(other: PseudoModifier): PseudoModifier
    fun describe(): String
}

/** The default [PseudoModifier] (cf. `Modifier` the companion) an omitted `modifier` argument falls back to. */
object EmptyPseudoModifier : PseudoModifier {
    override fun combine(other: PseudoModifier): PseudoModifier = other
    override fun describe(): String = "default"
}

/** The `Box` shape: a content-taking overload whose `modifier` defaults, alongside a content-LESS
 *  `BoxLike(modifier)` whose only parameter is the non-functional interface [PseudoModifier]. A `BoxLike { … }`
 *  call (only a trailing content lambda) must resolve to THIS overload, not the content-less sibling. */
@Composable
fun BoxLike(modifier: PseudoModifier = EmptyPseudoModifier, content: @Composable () -> Unit) {
    Capture.label = modifier.describe()
    content()
}

/** The content-less `Box(modifier: Modifier)` sibling — the decoy the trailing content lambda must NOT bind to. */
@Composable
fun BoxLike(modifier: PseudoModifier) {
    Capture.label = "contentless:" + modifier.describe()
}

/** The `Card(onClick = …) { content }` shape: a REQUIRED `onClick: () -> Unit` (a plain, non-composable lambda)
 *  as the first parameter, with a trailing `@Composable` content lambda. Stores onClick so a test can invoke it
 *  AFTER composition (a tap fires outside a composition pass). */
@Composable
fun CardLike(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Capture.pendingClick = onClick
    content()
}

/** Top-level capture sink (the composable writes here; the test reads it). */
object Capture {
    var label: String? = null
    var count: Int = -1
    var flag: Boolean? = null
    var pendingClick: (() -> Unit)? = null
}
