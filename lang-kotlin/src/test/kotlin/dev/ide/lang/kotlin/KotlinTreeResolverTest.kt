package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The resolver → interpreter contract (`docs/compose-interpreter.md`): supported constructs lower to the
 * right [RNode] shapes with **exact** callees + slot-identical bindings, and unsupported constructs become
 * [RNode.Unsupported] — never a wrong guess. This is the boundary that keeps the interpreter sound.
 */
class KotlinTreeResolverTest {

    private fun lower(code: String): ResolvedFunction {
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, DiskFile(srcDir.resolve("Use.kt")), 0)
        return assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction(), "first function should lower")
    }

    private fun ResolvedFunction.stmts(): List<RNode> = assertIs<RNode.Block>(body, "block body expected").statements

    @Test
    fun localsBindToSlotsAndUsesReferenceThem() {
        val fn = lower("package demo\nfun f() { val x = 1\n  val y = x }")
        val x = assertIs<RNode.LocalVar>(fn.stmts()[0])
        val y = assertIs<RNode.LocalVar>(fn.stmts()[1])
        assertIs<RNode.Const>(x.initializer)
        // `y = x` — the use must bind to the SAME slot the declaration introduced (no re-resolution).
        val use = assertIs<RNode.Name>(y.initializer)
        val local = assertIs<Binding.Local>(use.binding)
        assertEquals(x.slot, local.slot, "the use of x must reference x's slot")
        assertTrue(fn.isComplete, "a plain locals function should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun topLevelCallResolvesToExactSourceCallee() {
        val fn = lower("package demo\nfun f() { greet(\"hi\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals(DispatchKind.TOP_LEVEL, call.dispatch)
        val callee = assertIs<ResolvedCallable.Source>(call.callee)
        assertEquals("greet", callee.displayName)
        assertEquals(1, call.args.size)
        assertIs<RNode.Const>(call.args[0].value)
    }

    @Test
    fun memberCallResolvesWithReceiver() {
        val fn = lower("package demo\nfun f(g: Greeter) { g.hello() }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals(DispatchKind.MEMBER, call.dispatch)
        assertEquals("hello", call.callee.displayName)
        val recv = assertIs<RNode.Name>(call.receiver)
        assertIs<Binding.Param>(recv.binding)
    }

    @Test
    fun propertyReadLowersToPropertyGet() {
        val fn = lower("package demo\nfun f(g: Greeter) { g.title }")
        val get = assertIs<RNode.PropertyGet>(fn.stmts()[0])
        val prop = assertIs<Binding.Property>(get.binding)
        assertEquals("title", prop.name)
        assertEquals("demo.Greeter", prop.ownerFqn)
        assertIs<RNode.Name>(get.receiver)
    }

    @Test
    fun extensionPropertyReadCarriesItsFacadeAndIsExtensionFlag() {
        // `"abc".lastIndex` — a stdlib EXTENSION property (`CharSequence.lastIndex`) compiles to a static
        // getter on the `kotlin.text.StringsKt` facade, not an instance getter. The binding must record the
        // facade + the extension flag so the interpreter reflects `StringsKt.getLastIndex(CharSequence)`
        // rather than looking for a (non-existent) `getLastIndex()` on the receiver — the `16.dp` bug.
        val fn = lower("package demo\nfun f() { \"abc\".lastIndex }")
        val get = assertIs<RNode.PropertyGet>(fn.stmts()[0])
        val prop = assertIs<Binding.Property>(get.binding)
        assertEquals("lastIndex", prop.name)
        assertTrue(prop.isExtension, "lastIndex is an extension property")
        assertTrue(prop.ownerFqn?.endsWith("Kt") == true, "owner should be the …Kt facade; got ${prop.ownerFqn}")
    }

    @Test
    fun memberPropertyReadIsNotFlaggedAsExtension() {
        // A plain member property keeps the receiver type as its owner and is NOT an extension (instance getter).
        val fn = lower("package demo\nfun f(g: Greeter) { g.title }")
        val prop = assertIs<Binding.Property>(assertIs<RNode.PropertyGet>(fn.stmts()[0]).binding)
        assertFalse(prop.isExtension, "a member property must not be flagged as an extension")
        assertEquals("demo.Greeter", prop.ownerFqn)
    }

    @Test
    fun unimportedExtensionPropertyIsRejectedNotResolved() {
        // `demo.doubled` is an `Int` extension property. From another package WITHOUT importing it, `5.doubled`
        // does not compile, so it must NOT bind to a fabricated getter — the sound lowering is Unsupported.
        // This is the `16.dp`/`14.sp`-without-import bug: an extension was resolved regardless of scope.
        val fn = lower("package other\nfun f() { 5.doubled }")
        assertIs<RNode.Unsupported>(fn.stmts()[0], "an unimported extension property must not resolve")
        assertFalse(fn.isComplete)
    }

    @Test
    fun importedExtensionPropertyResolves() {
        // The SAME extension, now explicitly imported, resolves to its facade with the extension flag set.
        val fn = lower("package other\nimport demo.doubled\nfun f() { 5.doubled }")
        val get = assertIs<RNode.PropertyGet>(fn.stmts()[0])
        val prop = assertIs<Binding.Property>(get.binding)
        assertEquals("doubled", prop.name)
        assertTrue(prop.isExtension, "doubled is an extension property")
        assertTrue(fn.isComplete, "an imported extension should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun samePackageExtensionPropertyResolvesWithoutImport() {
        // An extension declared in the file's OWN package is in scope without an import.
        val fn = lower("package demo\nfun f() { 5.doubled }")
        val get = assertIs<RNode.PropertyGet>(fn.stmts()[0])
        assertTrue(assertIs<Binding.Property>(get.binding).isExtension)
        assertTrue(fn.isComplete, "a same-package extension should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun arithmeticOperatorDesugarsToCall() {
        val fn = lower("package demo\nfun f() { val a = 1 + 2 }")
        val a = assertIs<RNode.LocalVar>(fn.stmts()[0])
        val call = assertIs<RNode.Call>(a.initializer)
        assertEquals(DispatchKind.OPERATOR, call.dispatch)
        assertEquals("plus", call.callee.displayName)
        assertIs<RNode.Const>(call.receiver)
        assertEquals(1, call.args.size)
    }

    @Test
    fun assignmentToVarLowers() {
        val fn = lower("package demo\nfun f() { var x = 1\n  x = 2 }")
        val assign = assertIs<RNode.Assign>(fn.stmts()[1])
        val target = assertIs<RNode.Name>(assign.target)
        val local = assertIs<Binding.Local>(target.binding)
        assertTrue(local.mutable, "x is a var")
        assertIs<RNode.Const>(assign.value)
    }

    @Test
    fun ifAndReturnLower() {
        val fn = lower("package demo\nfun f(b: Boolean): Int { if (b) return 1\n  return 2 }")
        val iff = assertIs<RNode.If>(fn.stmts()[0])
        assertIs<RNode.Name>(iff.condition)
        assertIs<RNode.Return>(iff.then)
        assertEquals(null, iff.otherwise)
        assertIs<RNode.Return>(fn.stmts()[1])
    }

    @Test
    fun whenWithIsConditionLowers() {
        val fn = lower("package demo\nfun f(x: Any) { when (x) { is Int -> {}\n    else -> {} } }")
        assertTrue(fn.isComplete, "a when with an `is` condition now lowers (type-test branch)")
        assertIs<RNode.Block>(fn.stmts()[0], "the when desugars to a subject-temp + if/else chain")
    }

    @Test
    fun unsupportedConstructIsRejectedNotGuessed() {
        // A cast (`as`) is still outside the subset → Unsupported, not a guess.
        val fn = lower("package demo\nfun f(x: Any) { val y = x as Int }")
        assertFalse(fn.isComplete, "the function should report an incomplete lowering")
        assertTrue(fn.diagnostics.isNotEmpty(), "the gap should be recorded as a diagnostic")
    }

    @Test
    fun ambiguousOverloadIsRejectedNotGuessed() {
        // ov(Int) / ov(String) — called with an Any argument that disambiguates to neither → no guess.
        val fn = lower("package demo\nfun f(p: Any) { ov(p) }")
        val node = fn.stmts()[0]
        assertIs<RNode.Unsupported>(node, "an unresolvable overload must not be guessed")
        assertFalse(fn.isComplete)
    }

    @Test
    fun stringArgPicksStringOverloadOverOtherType() {
        // `Label(text: String)` vs `Label(text: Styled)` called with a string-concat arg: the concat infers
        // String (KtBinaryExpression inference) and only the String overload is applicable.
        val fn = lower("package demo\nfun f(name: String) { Label(\"Hi, \" + name + \"!\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("Label", call.callee.displayName)
        assertTrue(fn.isComplete, "the String overload should resolve cleanly; diags=${fn.diagnostics}")
    }

    @Test
    fun overloadShimsResolveToTheMostCompleteOverload() {
        // Two String-first overloads both applicable to `Note("x")` via defaults — the artifact of seeing
        // binary-compat shims (cf. Material3 `Text`). The code compiled, so it isn't truly ambiguous; the
        // resolver picks the most complete overload deterministically rather than failing.
        val fn = lower("package demo\nfun f() { Note(\"x\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("Note", call.callee.displayName)
        assertTrue(fn.isComplete, "an overload-shim tie should resolve, not fail; diags=${fn.diagnostics}")
    }

    @Test
    fun namedArgumentCallOmittingDefaultsResolvesAndCarriesNames() {
        // `panel(color = "red")` supplies one non-leading named arg and omits the defaulted `width`/`count`.
        // Two `panel` overloads exist; only the one declaring `color` can accept the call, so it must resolve
        // by NAME (positional arity + type matching can't — `"red"` doesn't fit `width: Int`). Before named-arg
        // awareness this lowered to Unsupported.
        val fn = lower("package demo\nfun f() { panel(color = \"red\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("panel", call.callee.displayName)
        assertEquals("color", call.args[0].name, "the named-argument name must be captured on the RArg")
        assertTrue(fn.isComplete, "a named-arg call omitting defaults should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun reorderedNamedArgumentsResolveToTheRightOverload() {
        // All params named but out of declaration order (`panel(count = …, width = …, color = …)`) — still the
        // first overload, picked by name coverage.
        val fn = lower("package demo\nfun f() { panel(count = 2, width = 1, color = \"red\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("panel", call.callee.displayName)
        assertTrue(fn.isComplete, "reordered named args should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun namedCallPicksTheSmallestOverloadNotTheClickableOne() {
        // `card(color = …)` — two overloads, the larger adds a REQUIRED leading `onClick`. The user named
        // `color` and omitted everything else, so the SMALLER (non-clickable) overload is meant; picking the
        // bigger one would pass null for the non-null `onClick` (the real Material3 `Card` NPE). The tie-break
        // for a named call prefers the smallest applicable overload.
        val fn = lower("package demo\nfun f() { card(color = \"red\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("card", call.callee.displayName)
        val lib = assertIs<ResolvedCallable.Source>(call.callee)
        assertEquals(2, lib.paramNames.size, "the 2-param `card` overload (no onClick) should be chosen")
        assertTrue(fn.isComplete, "diags=${fn.diagnostics}")
    }

    @Test
    fun sourceDataClassCopyResolves() {
        // A `data class`'s `copy(...)` is compiler-synthesized (not written in source), so the source index
        // wouldn't otherwise see it — `style.copy(fontSize = …)` lowered to `unresolved/ambiguous call`. The
        // index now synthesizes it from the primary constructor; the named-arg call must resolve to it.
        val fn = lower("package demo\nfun f(s: Style) { s.copy(fontSize = 18) }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals(DispatchKind.MEMBER, call.dispatch)
        assertEquals("copy", call.callee.displayName)
        assertEquals("fontSize", call.args[0].name)
        assertTrue(fn.isComplete, "source data-class copy should resolve; diags=${fn.diagnostics}")
    }

    @Test
    fun chainThroughGenericBinaryMemberResolves() {
        // The `LocalTextStyle.current.copy(…)` shape, but against BINARY generics (where substitution works,
        // unlike source `<T>` which is deferred): `Lazy<String>.value` must bind to `String` so `.uppercase()`
        // resolves on it. Guards the generic-member → call chain the Compose preview leans on.
        val fn = lower("package demo\nfun f(p: kotlin.Lazy<String>) { p.value.uppercase() }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("uppercase", call.callee.displayName)
        assertTrue(fn.isComplete, "a binary generic-member chain should lower; diags=${fn.diagnostics}")
    }

    @Test
    fun topLevelLibraryCallCarriesPreciseOwner() {
        // A stdlib top-level call resolves to a Library callee with its exact `…Kt` facade owner (what the
        // interpreter reflects into) — the descriptor-precision work.
        val fn = lower("package demo\nfun f() { println(\"x\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        val lib = assertIs<ResolvedCallable.Library>(call.callee)
        assertEquals("println", lib.displayName)
        assertTrue(lib.descriptorPrecise, "binary owner should be precise")
        assertTrue(lib.ownerFqn?.endsWith("Kt") == true, "owner should be a …Kt facade; got ${lib.ownerFqn}")
    }

    @Test
    fun composableSourceCallIsMarkedComposable() {
        // A `@Composable` source function (detected by annotation name) → its call carries isComposable.
        val composable = lower("package demo\nfun f() { Greeting() }")
        val call = assertIs<RNode.Call>(composable.stmts()[0])
        val callee = assertIs<ResolvedCallable.Source>(call.callee)
        assertTrue(callee.isComposable, "Greeting is @Composable")
        // A plain source call is not.
        val plain = lower("package demo\nfun f() { greet(\"x\") }")
        assertFalse(assertIs<ResolvedCallable.Source>(assertIs<RNode.Call>(plain.stmts()[0]).callee).isComposable)
    }

    @Test
    fun inlineStdlibCallIsMarkedInline() {
        // `TODO()` is an inline stdlib function → the metadata `isInline` flag flows to the callee.
        val fn = lower("package demo\nfun f() { TODO() }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        val callee = assertIs<ResolvedCallable.Library>(call.callee)
        assertEquals("TODO", callee.displayName)
        assertTrue(callee.isInline, "TODO() is inline")
    }

    @Test
    fun delegatedPropertyReadsThroughItsValue() {
        // `val s by lazy { … }` — a `.value`-convention delegate (`kotlin.Lazy`). The slot holds the delegate
        // (the `lazy { }` call); a use of `s` lowers to `delegate.value` so the interpreter reads the real getter.
        val fn = lower("package demo\nfun f() { val s by lazy { \"hi\" }\n  greet(s) }")
        val decl = assertIs<RNode.LocalVar>(fn.stmts()[0])
        assertIs<RNode.Call>(decl.initializer, "the slot holds the delegate object (the `lazy { }` call)")
        val call = assertIs<RNode.Call>(fn.stmts()[1])
        val read = assertIs<RNode.PropertyGet>(call.args[0].value, "a delegated-property read expands to `.value`")
        assertEquals("value", assertIs<Binding.Property>(read.binding).name)
        assertTrue(fn.isComplete, "a `.value` delegate should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun delegatedPropertyTypeDisambiguatesOverload() {
        // The reported `TextField(value = text, …)` shape: `text` is `by` a delegate, and two `field` overloads
        // differ only by the `value` type. The delegate's `.value` type (here `String`, via `Lazy<String>`) must
        // flow so the String overload is chosen — without it both overloads tie → "unresolved/ambiguous call".
        val fn = lower("package demo\nfun f() { val s by lazy { \"hi\" }\n  field(value = s, onChange = {}) }")
        val call = assertIs<RNode.Call>(fn.stmts()[1])
        assertEquals("field", call.callee.displayName)
        assertTrue(fn.isComplete, "the delegated `value` type should disambiguate the overload; diags=${fn.diagnostics}")
    }

    @Test
    fun delegatedVarWriteLowersToPropertySet() {
        // `var b by boxOf(…)` then `b = …` — the write goes through the delegate's `.value` setter (the
        // `MutableState.value = …` path the snapshot system observes for recomposition).
        val fn = lower("package demo\nfun f() { var b by boxOf(\"hi\")\n  b = \"x\" }")
        val set = assertIs<RNode.PropertySet>(fn.stmts()[1])
        assertEquals("value", assertIs<Binding.Property>(set.binding).name)
        assertIs<RNode.Name>(set.receiver, "the setter receiver is the delegate object in the slot")
        assertTrue(fn.isComplete, "a delegated `var` write should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun propertyIncrementLowersToReadModifyWrite() {
        // `count.value++` (the reported postfix-increment of a `MutableState`) → a PropertySet writing
        // `count.value + 1`. Previously a bare `KtPostfixExpression` → not interpretable.
        val fn = lower("package demo\nfun f(count: Box<Int>) { count.value++ }")
        val set = assertIs<RNode.PropertySet>(fn.stmts()[0])
        assertEquals("value", assertIs<Binding.Property>(set.binding).name)
        val bump = assertIs<RNode.Call>(set.value)
        assertEquals(DispatchKind.OPERATOR, bump.dispatch)
        assertEquals("plus", bump.callee.displayName)
        assertTrue(fn.isComplete, "a property `++` should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun directPropertyAssignmentLowersToPropertySet() {
        // `count.value = …` on a plain (non-delegated) `MutableState` receiver — also a PropertySet, not an
        // Assign-to-PropertyGet (which the interpreter rejects as an unsupported assignment target).
        val fn = lower("package demo\nfun f(count: Box<Int>) { count.value = 5 }")
        val set = assertIs<RNode.PropertySet>(fn.stmts()[0])
        assertEquals("value", assertIs<Binding.Property>(set.binding).name)
        assertTrue(fn.isComplete, "a direct property assignment should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun localIncrementLowersToAssign() {
        val fn = lower("package demo\nfun f() { var i = 0\n  i++ }")
        val assign = assertIs<RNode.Assign>(fn.stmts()[1])
        assertIs<RNode.Name>(assign.target)
        val bump = assertIs<RNode.Call>(assign.value)
        assertEquals("plus", bump.callee.displayName)
        assertTrue(fn.isComplete, "a local `++` should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun extensionCalledViaAContentLambdaImplicitReceiverResolves() {
        // `Listing { rows(3) { i -> } }` — `rows` is `fun ListScope.rows(...)`, called through the implicit
        // `this: ListScope` the content lambda introduces. Resolving it needs (a) the content parameter's
        // function type to be recognized as an EXTENSION function type, and (b) the trailing lambda to bind to
        // `Listing`'s LAST parameter (not arg-index 0) despite the defaulted leading `pad`. The Compose
        // `LazyColumn { itemsIndexed(...) {…} }` shape.
        val fn = lower("package demo\nfun f() { Listing { rows(3) { i -> } } }")
        assertTrue(fn.isComplete, "the scoped extension call should resolve; diags=${fn.diagnostics}")
        var rows = 0
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == "rows") rows++ }
        assertEquals(1, rows, "the `rows` extension call should lower to a Call")
    }

    @Test
    fun composableContentLambdaWithDefaultedLeadingParamLowers() {
        // `Column { greet("hi") }` — the trailing `@Composable () -> Unit` content lambda must bind to
        // `Column`'s LAST parameter despite the defaulted leading `pad`, and a no-receiver `@Composable () ->`
        // type must parse as a plain function type (the annotation must not swallow the `()` param list).
        val fn = lower("package demo\nfun f() { Column { greet(\"hi\") } }")
        assertTrue(fn.isComplete, "the composable content lambda should lower; diags=${fn.diagnostics}")
    }

    @Test
    fun memberExtensionResolvesThroughAnImplicitReceiver() {
        // `Stack { Mod.weighted(2) }` — `weighted` is a MEMBER-extension (`fun Mod.weighted()` declared inside
        // `RowScope`), so it resolves only because `RowScope` is the content lambda's implicit receiver. The
        // Compose `Row { Modifier.weight(1f) }` shape.
        val fn = lower("package demo\nfun f() { Stack { Mod.weighted(2) } }")
        assertTrue(fn.isComplete, "the member-extension call should resolve in scope; diags=${fn.diagnostics}")
        var weighted = 0
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == "weighted") weighted++ }
        assertEquals(1, weighted, "the `weighted` member-extension should lower to a Call")
    }

    @Test
    fun memberExtensionDoesNotLeakOutsideItsScope() {
        // The same `Mod.weighted(2)` OUTSIDE a `Stack { }` must NOT resolve — a member-extension is in scope
        // only when its declaring receiver (`RowScope`) is an implicit receiver. Soundness: never guessed.
        val fn = lower("package demo\nfun f() { Mod.weighted(2) }")
        assertFalse(fn.isComplete, "weighted must be unresolved without its RowScope receiver in scope")
    }

    @Test
    fun varargCallResolvesWithMoreArgsThanParams() {
        // `joinAll("a", "b", "c")` — three positional args bind to the single `vararg parts` parameter, so the
        // vararg overload is chosen over the no-arg one despite `argCount (3) != paramCount (1)`. The
        // `mutableStateListOf("Learn", "Build")` shape.
        val fn = lower("package demo\nfun f() { joinAll(\"a\", \"b\", \"c\") }")
        assertTrue(fn.isComplete, "the vararg call should resolve; diags=${fn.diagnostics}")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("joinAll", call.callee.displayName)
        assertEquals(3, call.args.size, "all three args are kept (packed into the vararg at interpret time)")
    }

    @Test
    fun supportedFunctionHasNoUnsupportedNodes() {
        val fn = lower("package demo\nfun f(g: Greeter) { val n = g.hello()\n  greet(n) }")
        assertTrue(fn.isComplete, "diags=${fn.diagnostics}")
        var unsupported = 0
        fn.body.walk { if (it is RNode.Unsupported) unsupported++ }
        assertEquals(0, unsupported, "no Unsupported nodes expected")
    }

    @Test
    fun indexedAccessLowersToAGetCall() {
        // `xs[0]` → a MEMBER call of the `get(index)` operator on the receiver, so the interpreter just invokes
        // `List.get(0)` reflectively. (The `KtArrayAccessExpression` was previously Unsupported.)
        val fn = lower("package demo\nfun f(xs: List<String>) { xs[0] }")
        val call = assertIs<RNode.Call>(fn.stmts()[0], "indexed access should lower to a get() call")
        assertEquals(DispatchKind.MEMBER, call.dispatch)
        assertEquals("get", call.callee.displayName)
        assertIs<RNode.Name>(call.receiver, "receiver is the array expression")
        assertEquals(1, call.args.size)
        assertIs<RNode.Const>(call.args[0].value, "the index argument")
        assertTrue(fn.isComplete, "indexed access should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun indexedAssignmentIsRejectedNotMisLowered() {
        // `xs[i] = v` (the `set` operator) isn't modeled — it must be Unsupported, not a bogus Assign over the
        // `get` node the LHS would otherwise lower to.
        val fn = lower("package demo\nfun f(xs: MutableList<String>) { xs[0] = \"a\" }")
        assertIs<RNode.Unsupported>(fn.stmts()[0], "indexed assignment must be Unsupported")
        assertFalse(fn.isComplete)
    }

    @Test
    fun hexAndBinaryAndSeparatorLiteralsLower() {
        // `Color(0xFFD32F2F)` — a 32-bit ARGB hex literal. It must parse (not "unparseable literal") and, since
        // it overflows Int, widen to a `Long` value (the `Color(Long)` argument). `0xFF388E3C` also exercises
        // the `E` hex digit that previously mis-routed it into the float/Double branch.
        fun constOf(expr: String): RNode.Const {
            val fn = lower("package demo\nfun f() { val x = $expr }")
            val v = assertIs<RNode.LocalVar>(fn.stmts()[0])
            assertTrue(fn.isComplete, "`$expr` should lower completely; diags=${fn.diagnostics}")
            return assertIs<RNode.Const>(v.initializer, "`$expr` should be a constant")
        }
        assertEquals(0xFFD32F2FL, constOf("0xFFD32F2F").value, "a 32-bit hex literal widens to Long")
        assertEquals(0xFF388E3CL, constOf("0xFF388E3C").value, "the `E` hex digit must not be read as a float exponent")
        assertEquals(255, constOf("0xFF").value, "a small hex literal stays Int")
        assertEquals(255L, constOf("0xFFL").value, "an explicit L suffix is Long")
        assertEquals(10, constOf("0b1010").value, "a binary literal parses")
        assertEquals(1000000, constOf("1_000_000").value, "digit separators are ignored")
    }

    @Test
    fun arithmeticResultTypeResolvesAChainedMemberCall() {
        // `(p * 100).toInt()` — the `*` result type must be inferred so `.toInt()` resolves its receiver. The
        // builtin `Float.times` members carry no return type in the model, so this relied on numeric-promotion
        // inference (the wider operand: `Float * Int` → `Float`). Without it the call was Unsupported
        // (`toInt` candidates=0). The `"${(progress * 100).toInt()}%"` shape.
        val fn = lower("package demo\nfun f(p: Float) { (p * 100).toInt() }")
        assertTrue(fn.isComplete, "a chained call on an arithmetic result should lower; diags=${fn.diagnostics}")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals("toInt", call.callee.displayName)
    }

    @Test
    fun unresolvedCapitalizedCallIsUnsupportedNotABogusConstructor() {
        // A capitalized call the resolver can't find AND that isn't a known/loadable type — a library function
        // missing from the index, e.g. the Compose composable `SuggestionChip` — must NOT be fabricated into a
        // reflective constructor. That produced a `SuggestionChip()` ctor that crashed the running composition
        // with "cannot load class". It's an honest Unsupported instead.
        val fn = lower("package demo\nfun f() { MysteryWidget(1) }")
        assertIs<RNode.Unsupported>(fn.stmts()[0], "an unresolved, non-type capitalized call must be Unsupported")
        assertFalse(fn.isComplete)
    }

    @Test
    fun thrownUnqualifiedExceptionStillBuildsAConstructor() {
        // `throw MysteryError("x")` — a throwable the resolver can't fully qualify is still constructed
        // reflectively (loaded via `java.lang` at run time); the `throw` context is the positive signal that
        // separates a constructed exception from a mis-guessed composable.
        val fn = lower("package demo\nfun f() { throw MysteryError(\"x\") }")
        val thr = assertIs<RNode.Throw>(fn.stmts()[0], "throw should lower")
        val ctor = assertIs<RNode.Call>(thr.value)
        assertEquals(DispatchKind.CONSTRUCTOR, ctor.dispatch, "a thrown unqualified name is still a constructor")
    }

    @Test
    fun ifExpressionArgumentDisambiguatesOverload() {
        // `Icon(if (c) Icons.Home else Icons.Settings)` — the if-expression infers to `Vec` (both branches are
        // `Vec`), which picks `Icon(Vec)` over `Icon(Paint)`. Without if/when type inference the arg was untyped
        // and the call stayed ambiguous → Unsupported.
        val fn = lower("package demo\nfun f(c: Boolean) { Icon(if (c) Icons.Home else Icons.Settings) }")
        val call = assertIs<RNode.Call>(fn.stmts()[0], "the Icon call should resolve, not stay ambiguous")
        assertEquals("Icon", call.callee.displayName)
        assertTrue(fn.isComplete, "the if-arg should disambiguate the overload; diags=${fn.diagnostics}")
    }

    @Test
    fun lambdaParameterTypeDisambiguatesAnArgOverload() {
        // `Scaff { inner -> w.pad(inner) }` — `inner` is typed `Pads` from `Scaff`'s `content: (Pads) -> Unit`
        // parameter, which picks `pad(Pads)` over `pad(Int)`. Mirrors the Scaffold `{ innerPadding -> … padding(innerPadding) }`
        // case: a content-lambda parameter's type must flow into overload selection at a call using it.
        val fn = lower("package demo\nfun f(w: Widget) { Scaff { inner -> w.pad(inner) } }")
        val outer = assertIs<RNode.Call>(fn.stmts()[0])
        val lambda = assertIs<RNode.Lambda>(outer.args.last().value)
        val pad = assertIs<RNode.Call>(assertIs<RNode.Block>(lambda.body).statements[0], "w.pad(inner) should resolve")
        assertEquals("pad", pad.callee.displayName)
        assertTrue(fn.isComplete, "the lambda-param type should disambiguate pad(); diags=${fn.diagnostics}")
    }

    @Test
    fun varargListOfResolvesAgainstTheRealStdlib() {
        // `listOf("a", "b")` against the real kotlin-stdlib jar: the generic vararg callable surfaces from
        // multiple sources / with differently-erased parameters, which previously left it as an unresolvable
        // tie. The duplicate-shape collapse picks it deterministically.
        val fn = lower("package demo\nfun f() { listOf(\"a\", \"b\") }")
        val call = assertIs<RNode.Call>(fn.stmts()[0], "listOf should resolve")
        assertEquals("listOf", call.callee.displayName)
        assertEquals(2, call.args.size)
        assertTrue(fn.isComplete, "vararg listOf should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun unknownNamedArgumentBlocksLoweringWithReason() {
        // A typo'd / wrong-version named argument must surface as a lowering diagnostic (so the preview reports
        // it) instead of the dispatcher silently falling back to positional binding and rendering wrong colors.
        val fn = lower("package demo\nfun f() { greet(bogus = \"x\") }")
        assertFalse(fn.isComplete, "an unknown named argument must block lowering; diags=${fn.diagnostics}")
        assertTrue(
            fn.diagnostics.any { it.reason.contains("Cannot find a parameter with this name: bogus") },
            "the diagnostic should name the bad parameter; got ${fn.diagnostics}",
        )
    }

    @Test
    fun validNamedArgumentDoesNotBlockLowering() {
        val fn = lower("package demo\nfun f() { greet(name = \"x\") }")
        assertTrue(fn.isComplete, "a valid named argument must lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun namedArgumentFromAnotherOverloadIsNotFlagged() {
        // `label` exists only on the SECOND `panel` overload; the check must union all overloads' parameter
        // names (mirroring the editor) so a parameter that belongs to any candidate is accepted.
        val fn = lower("package demo\nfun f() { panel(label = 1) }")
        assertTrue(
            fn.diagnostics.none { it.reason.contains("Cannot find a parameter") },
            "a parameter present on another overload must not be flagged; got ${fn.diagnostics}",
        )
    }

    @Test
    fun trailingLambdaCallPrefersFixedArityOverVararg() {
        // `hold { 1 }` must resolve to the fixed-arity `hold(calc)` — NOT `hold(vararg keys, calc)`, which only
        // matches by absorbing ZERO varargs and would leave `keys` unfilled (a null array at dispatch: exactly
        // the real `remember { }` vs `remember(vararg keys, calculation)` NPE on `keys.length`).
        val fn = lower("package demo\nfun f() { hold { 1 } }")
        val call = assertIs<RNode.Call>(fn.stmts()[0], "hold should resolve")
        val callee = assertIs<ResolvedCallable.Source>(call.callee)
        assertEquals(listOf("calc"), callee.paramNames, "the fixed-arity hold(calc) overload must win over the vararg")
        assertTrue(fn.isComplete, "hold { } should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun sourceInfixMemberCallResolvesToMemberDispatch() {
        // `c add 2` — `add` is a source `infix fun` member of `Calc`. An infix call is `c.add(2)`: a MEMBER
        // dispatch on the left operand with the right operand as the single argument.
        val fn = lower("package demo\nfun f(c: Calc) { c add 2 }")
        val call = assertIs<RNode.Call>(fn.stmts()[0], "an infix member call should lower to a Call")
        assertEquals(DispatchKind.MEMBER, call.dispatch)
        assertEquals("add", call.callee.displayName)
        assertIs<RNode.Name>(call.receiver, "the receiver is the left operand")
        assertEquals(1, call.args.size)
        assertIs<RNode.Const>(call.args[0].value, "the right operand is the single argument")
        assertTrue(fn.isComplete, "a source infix member should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun sourceInfixExtensionCallResolvesToExtensionDispatch() {
        // `g combinedWith h` — `combinedWith` is a same-package `infix fun Greeter.…` extension, in scope
        // without an import, so it resolves to an EXTENSION dispatch on the left operand.
        val fn = lower("package demo\nfun f(g: Greeter, h: Greeter) { g combinedWith h }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals(DispatchKind.EXTENSION, call.dispatch)
        assertEquals("combinedWith", call.callee.displayName)
        assertTrue(fn.isComplete, "a same-package infix extension should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun stdlibInfixToResolvesAsExtension() {
        // `1 to 2` — the stdlib `infix fun <A, B> A.to(that: B): Pair<A, B>` (package `kotlin`, default-imported)
        // lowers to an EXTENSION call against the real stdlib jar.
        val fn = lower("package demo\nfun f() { val p = 1 to 2 }")
        val decl = assertIs<RNode.LocalVar>(fn.stmts()[0])
        val call = assertIs<RNode.Call>(decl.initializer, "`1 to 2` should lower to a `to` call")
        assertEquals(DispatchKind.EXTENSION, call.dispatch)
        assertEquals("to", call.callee.displayName)
        assertTrue(fn.isComplete, "the stdlib `to` infix should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun infixResultTypeBindsBothTypeParametersForAChainedCall() {
        // `("" to 1).second.toLong()` — the `to` result must infer `Pair<String, Int>` (NOT `Pair<String, B>`
        // with the value type parameter left free), so `.second` types to `Int` and `.toLong()` resolves its
        // receiver. Guards binding the value parameter from the right operand, not only the receiver one.
        val fn = lower("package demo\nfun f() { (\"\" to 1).second.toLong() }")
        val call = assertIs<RNode.Call>(fn.stmts()[0], "the chained .toLong() should resolve")
        assertEquals("toLong", call.callee.displayName)
        assertTrue(fn.isComplete, "the infix result's value type parameter must bind; diags=${fn.diagnostics}")
    }

    @Test
    fun extensionFunctionCallOnAnExplicitReceiverResolves() {
        // `"".getSize()` — `getSize` is a top-level `fun String.getSize()` extension, NOT a member of String, so
        // it resolves only by consulting the extension index for the receiver type. (A `recv.foo()` call
        // previously looked at members only, leaving every extension call on an explicit receiver Unsupported.)
        val fn = lower("package demo\nfun f() { \"\".getSize() }")
        val call = assertIs<RNode.Call>(fn.stmts()[0], "an extension call on a receiver should resolve")
        assertEquals(DispatchKind.EXTENSION, call.dispatch)
        assertEquals("getSize", call.callee.displayName)
        assertIs<RNode.Const>(call.receiver, "the string literal is the extension receiver")
        assertTrue(fn.isComplete, "an in-scope extension call should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun extensionFunctionCallWithArgumentResolves() {
        // `g.shout(3)` — an extension with a value parameter: the receiver is the extension receiver, the value
        // argument the single arg.
        val fn = lower("package demo\nfun f(g: Greeter) { g.shout(3) }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals(DispatchKind.EXTENSION, call.dispatch)
        assertEquals("shout", call.callee.displayName)
        assertEquals(1, call.args.size)
        assertTrue(fn.isComplete, "diags=${fn.diagnostics}")
    }

    @Test
    fun unimportedExtensionFunctionCallIsUnsupportedNotResolved() {
        // `"".getSize()` from another package WITHOUT importing `demo.getSize` does not compile, so it must not
        // resolve to a fabricated callee — the sound lowering is Unsupported (the extension-scope rule, applied
        // to function calls the same way it already is to extension properties).
        val fn = lower("package other\nfun f() { \"\".getSize() }")
        assertIs<RNode.Unsupported>(fn.stmts()[0], "an unimported extension call must not resolve")
        assertFalse(fn.isComplete)
    }

    @Test
    fun importedExtensionFunctionCallResolves() {
        // The same extension, explicitly imported, resolves.
        val fn = lower("package other\nimport demo.getSize\nfun f() { \"\".getSize() }")
        val call = assertIs<RNode.Call>(fn.stmts()[0])
        assertEquals(DispatchKind.EXTENSION, call.dispatch)
        assertEquals("getSize", call.callee.displayName)
        assertTrue(fn.isComplete, "an imported extension call should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun callOmittingRequiredArgumentIsUnsupported() {
        // `Banner { }` supplies only the trailing `content` lambda and omits the REQUIRED `onClick` — invalid
        // Kotlin (no value passed for parameter 'onClick'). It must NOT lower to a runnable call (which would
        // pass null for onClick and "run" in the preview), but report the missing parameter.
        val fn = lower("package demo\nfun f() { Banner { } }")
        val node = assertIs<RNode.Unsupported>(fn.stmts()[0], "a call missing a required argument must be Unsupported")
        assertTrue(node.reason.contains("onClick"), "the reason should name the missing parameter; got ${node.reason}")
        assertFalse(fn.isComplete)
    }

    @Test
    fun callSupplyingRequiredArgumentLowers() {
        // Supplying `onClick` (the trailing lambda still binds to `content`, `label` defaults) lowers cleanly.
        val fn = lower("package demo\nfun f() { Banner(onClick = {}) { } }")
        assertIs<RNode.Call>(fn.stmts()[0])
        assertTrue(fn.isComplete, "supplying the required onClick should lower completely; diags=${fn.diagnostics}")
    }

    @Test
    fun callOmittingOnlyDefaultedArgumentLowers() {
        // Omitting `label` (which has a default) is valid — only a REQUIRED omission is rejected.
        val fn = lower("package demo\nfun f() { Banner(onClick = {}, content = {}) }")
        assertTrue(fn.isComplete, "omitting only a defaulted parameter should lower; diags=${fn.diagnostics}")
    }

    @Test
    fun unknownInfixFunctionIsUnsupportedNotGuessed() {
        // `g mystery h` — no `mystery` member or in-scope extension on `Greeter`. Soundness: never fabricated.
        val fn = lower("package demo\nfun f(g: Greeter, h: Greeter) { g mystery h }")
        assertIs<RNode.Unsupported>(fn.stmts()[0], "an unresolved infix function must be Unsupported")
        assertFalse(fn.isComplete)
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Api.kt" to """
                    package demo
                    class Greeter {
                        fun hello(): String = "hi"
                        val title: String = "t"
                    }
                    fun greet(name: String) {}
                    fun ov(x: Int) {}
                    fun ov(x: String) {}
                    @Composable fun Greeting() {}
                    class Styled
                    fun Label(text: String) {}
                    fun Label(text: Styled) {}
                    fun Note(text: String, a: Int = 0) {}
                    fun Note(text: String, b: Int = 0, c: Int = 0) {}
                    fun panel(width: Int = 0, color: String = "", count: Int = 0) {}
                    fun panel(width: Int = 0, label: Int = 0) {}
                    fun card(color: String = "", count: Int = 0) {}
                    fun card(onClick: Int, color: String = "", enabled: Boolean = true, count: Int = 0) {}
                    val Int.doubled: Int get() = this * 2
                    data class Style(val fontSize: Int = 12, val color: Int = 0)
                    interface Box<T> { var value: T }
                    fun <T> boxOf(v: T): Box<T> = TODO()
                    fun field(value: String, onChange: (String) -> Unit) {}
                    fun field(value: Int, onChange: (Int) -> Unit) {}
                    class Mod { companion object : Mod() }
                    class RowScope { fun Mod.weighted(w: Int): Mod = this }
                    fun Stack(pad: Int = 0, content: RowScope.() -> Unit) {}
                    class ListScope
                    fun Listing(pad: Int = 0, content: ListScope.() -> Unit) {}
                    fun ListScope.rows(count: Int, itemContent: (Int) -> Unit) {}
                    @Composable fun Column(pad: Int = 0, content: @Composable () -> Unit) {}
                    fun joinAll(vararg parts: String): String = ""
                    fun joinAll(): String = ""
                    class Vec
                    class Paint
                    fun Icon(v: Vec) {}
                    fun Icon(p: Paint) {}
                    object Icons {
                        val Home: Vec get() = Vec()
                        val Settings: Vec get() = Vec()
                    }
                    class Pads
                    class Widget {
                        fun pad(p: Pads): Widget = this
                        fun pad(n: Int): Widget = this
                    }
                    fun Scaff(top: Int = 0, content: (Pads) -> Unit) {}
                    fun <T> hold(calc: () -> T): T = calc()
                    fun <T> hold(vararg keys: Any?, calc: () -> T): T = calc()
                    class Calc { infix fun add(n: Int): Int = n }
                    infix fun Greeter.combinedWith(other: Greeter): String = ""
                    fun Banner(onClick: () -> Unit, label: String = "", content: () -> Unit) {}
                    fun String.getSize(): Int = length
                    fun Greeter.shout(volume: Int): String = ""
                """.trimIndent(),
            ),
        )
        val service: KotlinSymbolService = KotlinSymbolService(listOf(DiskFile(srcDir)), listOf(stdlibJarPath()))
    }
}
