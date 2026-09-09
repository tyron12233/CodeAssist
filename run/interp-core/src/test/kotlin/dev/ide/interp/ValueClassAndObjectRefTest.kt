package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Object/companion references + inline-value-class name mangling, exercised against REAL Kotlin bytecode
 * (the fixtures below are compiled by the test compiler, so they get the actual `INSTANCE`/`Companion` fields
 * and mangled `name-<hash>` JVM names). This is the `Modifier` (companion ref) + `Color.Red` (value-class
 * companion property) + `Modifier.background(Color.Red)` (value-class-param call) machinery in miniature.
 */
class ValueClassAndObjectRefTest {

    private val span = SourceSpan(0, 0)
    private fun run(body: RNode): Any? =
        Interpreter(emptyMap()).call(ResolvedFunction("f", emptyList(), RNode.Block(listOf(body), false, span), emptyList()), emptyList())

    @Test
    fun bareObjectReferenceMaterializesTheSingleton() {
        val node = RNode.Name(Binding.ObjectRef("dev.ide.interp.Vault", "Vault"), span)
        assertEquals(Vault, run(node), "a bare `object` reference should yield its INSTANCE")
    }

    @Test
    fun valueClassPropertyGetterIsFoundDespiteMangling() {
        // `Vault.balance` (a `Cents` value-class property) compiles to a mangled getter `getBalance-<hash>`.
        val recv = RNode.Name(Binding.ObjectRef("dev.ide.interp.Vault", "Vault"), span)
        val get = RNode.PropertyGet(recv, Binding.Property("balance", null, backingField = false), span)
        assertEquals(100, run(get), "the mangled value-class getter should read through")
    }

    @Test
    fun valueClassParamCallIsFoundDespiteMangling() {
        // `spend(c: Cents): Int` compiles to a mangled static `spend-<hash>(int)`; called with the unboxed int.
        val callee = ResolvedCallable.Library(
            displayName = "spend", ownerFqn = "dev.ide.interp.ValueClassAndObjectRefTestKt", methodName = "spend",
            paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, descriptorPrecise = true,
        )
        val call = RNode.Call(callee, DispatchKind.TOP_LEVEL, receiver = null, args = listOf(arg(100)), callSiteKey = CallSiteKey(0), source = span)
        assertEquals(100, run(call), "a value-class-param call should resolve via the mangled name")
    }

    private fun arg(value: Any?) = dev.ide.lang.kotlin.interp.RArg(RNode.Const(value, null, span))

    @Test
    fun omittedDefaultParamUsesItsDefaultViaTheSynthetic() {
        // `greet("Bob")` omits `greeting = "Hi"` → dispatch finds `greet$default` and sets the mask bit.
        val callee = lib("greet")
        val call = RNode.Call(callee, DispatchKind.TOP_LEVEL, null, listOf(arg("Bob")), CallSiteKey(0), span)
        assertEquals("Hi Bob", run(call), "the omitted defaulted param should fall back to its default")
    }

    @Test
    fun omittedValueClassDefaultParamUsesItsDefault() {
        // `shade(5)` omits the value-class default `c = Cents(1)` → mangled `shade-<hash>$default`.
        val call = RNode.Call(lib("shade"), DispatchKind.TOP_LEVEL, null, listOf(arg(5)), CallSiteKey(0), span)
        assertEquals(6, run(call), "5 + default Cents(1).n == 6")
    }

    @Test
    fun extensionDefaultParamMaskExcludesTheReceiver() {
        // `cents.scaled()` omits `factor = 2`. For an EXTENSION the `$default` mask is numbered over VALUE
        // params (receiver excluded), so `factor` is bit 0 — not bit 1 (its JVM slot after the receiver).
        // Getting that wrong leaves `factor` at the 0 placeholder (→ 0) or NPEs an object param (cf. `shape`).
        val callee = ResolvedCallable.Library(
            displayName = "scaled", ownerFqn = "dev.ide.interp.ValueClassAndObjectRefTestKt", methodName = "scaled",
            paramTypes = emptyList(), isStatic = false, isConstructor = false, isInline = false, descriptorPrecise = true,
        )
        // receiver = Cents(5) (its unboxed int 5); no value args supplied → factor defaults to 2.
        val call = RNode.Call(callee, DispatchKind.EXTENSION, receiver = RNode.Const(5, null, span), args = emptyList(), callSiteKey = CallSiteKey(0), source = span)
        assertEquals(10, run(call), "5 * default factor 2 == 10 (mask bit for factor must be 0, not 1)")
    }

    @Test
    fun topLevelPropertyReadsThroughTheStaticFacadeGetter() {
        // `LocalTextStyle` in miniature: a top-level `val` reads through the STATIC `getTopGreeting()` on the
        // file facade, with NO receiver — the path that previously threw "top-level property read not supported".
        val get = RNode.PropertyGet(null, Binding.Property("topGreeting", facade, backingField = false), span)
        assertEquals("hello", run(get), "a top-level property should read through its static facade getter")
    }

    @Test
    fun extensionPropertyReadsThroughTheStaticFacadeGetter() {
        // `7.tripled` — an extension property compiles to a STATIC `getTripled(int)` on the file facade with
        // the receiver as its argument, NOT an instance `getTripled()` on `java.lang.Integer`. This is the
        // shape that broke `16.dp` (`DpKt.getDp(int)`).
        val recv = RNode.Const(7, null, span)
        val get = RNode.PropertyGet(recv, Binding.Property("tripled", facade, backingField = false, isExtension = true), span)
        assertEquals(21, run(get), "an extension property must dispatch to its static facade getter")
    }

    @Test
    fun valueClassExtensionPropertyGetterIsFoundDespiteMangling() {
        // `5.asCents` returns a `Cents` value class → the facade getter is mangled `getAsCents-<hash>(int)`,
        // returning the unboxed underlying int. This is the faithful `16.dp` (-> `Dp`) case.
        val recv = RNode.Const(5, null, span)
        val get = RNode.PropertyGet(recv, Binding.Property("asCents", facade, backingField = false, isExtension = true), span)
        assertEquals(5, run(get), "the mangled value-class extension getter should read through")
    }

    private val facade = "dev.ide.interp.ValueClassAndObjectRefTestKt"

    private fun lib(name: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = "dev.ide.interp.ValueClassAndObjectRefTestKt", methodName = name,
        paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, descriptorPrecise = true,
    )
}

/** A top-level property (cf. `LocalTextStyle`) → a static facade getter `getTopGreeting()`, no receiver. */
val topGreeting: String = "hello"

/** An extension property → a static facade getter `getTripled(int)` with the receiver as its argument. */
val Int.tripled: Int get() = this * 3

/** A value-class-returning extension property → a MANGLED static facade getter `getAsCents-<hash>(int)`. */
val Int.asCents: Cents get() = Cents(this)

@JvmInline
value class Cents(val n: Int)

@Target(AnnotationTarget.PROPERTY)
annotation class Tag

object Vault {
    // `@Tag` makes Kotlin emit a `getBalance-<hash>$annotations()` synthetic ALONGSIDE the real mangled
    // getter — exactly the `Color.Red` shape that broke name matching (the `$annotations` method returns void
    // → null). The getter must still resolve to the real one.
    @Tag
    val balance: Cents = Cents(100)
}

/** Top-level function taking an inline value class → mangled JVM name `spend-<hash>(int)`. */
fun spend(c: Cents): Int = c.n

/** A function with a defaulted param → `greet$default(String, String, int, Object)` synthetic. */
fun greet(name: String, greeting: String = "Hi"): String = "$greeting $name"

/** A function with a value-class-typed defaulted param → mangled + `$default` synthetic. */
fun shade(base: Int, c: Cents = Cents(1)): Int = base + c.n

/** An EXTENSION on a value class with a defaulted param — the `$default` mask must exclude the receiver. */
fun Cents.scaled(factor: Int = 2): Int = n * factor
