package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everyday Kotlin that the compiler accepts and the editor flagged, found by running a corpus of idioms
 * (stdlib, JDK, generics, sealed hierarchies, SAM conversions) through the checkers. Each group is one root
 * cause; the `...StillReported` tests hold the line on the real errors next to each fix.
 */
class KotlinIdiomResolutionTest {

    /** A parameter, local or property shadows a same-named top-level function for a bare call, including the
     *  same-file arity check, which read `f(x)` against a top-level `fun f()`. */
    @Test
    fun aFunctionTypedParameterShadowsASameFileFunction() {
        clean("fun f() = 0\nfun <T, R> g(x: T, f: (T) -> R): R = f(x)")
        clean("fun f(): Int = 0\nfun compose(f: (Int) -> Int, g: (Int) -> Int): (Int) -> Int = { x -> f(g(x)) }")
    }

    /** A receiver-typed value is called with the implicit `this` as its receiver (`b()` inside `W`). */
    @Test
    fun anExtensionFunctionTypedParameterTakesTheImplicitReceiver() {
        clean("class W { fun go(b: W.() -> Unit) { b() } }")
        clean("fun <T> T.applyTwice(f: T.() -> T): T = f().f()\nfun g(): Int = 1.applyTwice { this + 1 }")
    }

    /** A source extension on a type parameter is keyed under its bound (or `Any`), and its receiver's own type
     *  arguments bind from the actual receiver. */
    @Test
    fun sourceExtensionsOnTypeParametersResolve() {
        clean("fun <T> T.twice(): List<T> = listOf(this, this)\nfun g(): Int = 1.twice().size + \"a\".twice().size")
        clean("fun <T> T.self(): T = this\nfun g(): Int = \"ab\".self().length")
        clean("fun <T : CharSequence> T.len2(): Int = length\nfun g(): Int = \"ab\".len2()")
        clean("fun <T> List<T>.second(): T = this[1]\nfun g(l: List<String>): Int = l.second().length")
        clean("fun <K, V> Map<K, V>.firstKey(): K = keys.first()\nfun g(m: Map<String, Int>): Int = m.firstKey().length")
    }

    /** An extension declared on a nullable receiver may be called on a nullable value. */
    @Test
    fun nullableReceiverExtensionsAcceptNullableValues() {
        clean("fun String?.orEmptyLen(): Int = this?.length ?: 0\nfun g(s: String?): Int = s.orEmptyLen()")
        clean("fun <T> List<T>?.safeSize(): Int = this?.size ?: 0\nfun g(l: List<Int>?): Int = l.safeSize()")
        clean("fun <T> T?.orDefault(d: T): T = this ?: d\nfun g(x: Int?): Int = x.orDefault(3) + 1")
        clean("fun g(s: String?): Int = s.hashCode() + s.toString().length")
        clean("fun g(s: String?): Pair<String?, Int> = s.to(1)")
        clean("fun g(a: String?, b: String?): Boolean = a.equals(b, ignoreCase = true) && a.equals(b)")
    }

    @Test
    fun aNonNullReceiverOnANullableValueIsStillReported() {
        assertCodes("fun g(s: String?): Int = s.length", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        assertCodes("fun String.ext(): Int = 1\nfun g(s: String?): Int = s.ext()", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
        assertCodes("fun <T : Any> T.ext2(): Int = 1\nfun g(s: String?): Int = s.ext2()", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
    }

    /** A precondition still narrows when it carries a lazy message, and `require(x is T)` smart-casts `x`. */
    @Test
    fun preconditionsNarrowWithAMessageAndForIsChecks() {
        clean("fun h(s: String?): Int { checkNotNull(s) { \"x\" }; return s.length }")
        clean("fun h(s: String?): Int { requireNotNull(s) { \"s\" }; return s.length }")
        clean("fun f(x: Any): Int { require(x is String); return x.length }")
        clean("fun f(x: Any): Int { check(x is String) { \"no\" }; return x.length }")
    }

    /** An `object` subclass matched by value covers it, and a nested sealed class is covered by its own
     *  subclasses. */
    @Test
    fun whenExhaustivenessCountsObjectsAndNestedSealedClasses() {
        clean("sealed class R { object Loading : R(); class Ok(val v: Int) : R() }\nfun f(r: R): Int = when (r) { R.Loading -> 0; is R.Ok -> r.v }")
        clean("sealed class S\nobject L : S()\nclass O : S()\nfun f(r: S): Int = when (r) { L -> 0; is O -> 1 }")
        clean("sealed interface Q { data object A : Q; data object B : Q }\nfun f(q: Q): Int = when (q) { Q.A -> 0; Q.B -> 1 }")
        clean(
            "sealed class Ev { sealed class Ui : Ev() { object Click : Ui(); data class Scroll(val dy: Int) : Ui() }; data class Net(val code: Int) : Ev() }\n" +
                "fun f(e: Ev): Int = when (e) { Ev.Ui.Click -> 1; is Ev.Ui.Scroll -> e.dy; is Ev.Net -> e.code }"
        )
    }

    @Test
    fun aMissingSealedBranchIsStillReported() {
        assertCodes(
            "sealed class R { object Loading : R(); class Ok(val v: Int) : R(); class Err : R() }\nfun f(r: R): Int = when (r) { R.Loading -> 0; is R.Ok -> r.v }",
            KotlinDiagnosticCodes.WHEN_EXHAUSTIVE,
        )
        assertCodes(
            "sealed class Ev { sealed class Ui : Ev() { object Click : Ui(); class Scroll : Ui() }; class Net : Ev() }\n" +
                "fun f(e: Ev): Int = when (e) { Ev.Ui.Click -> 1; is Ev.Net -> 2 }",
            KotlinDiagnosticCodes.WHEN_EXHAUSTIVE,
        )
    }

    /** Interface delegation sees the plain constructor parameter it delegates to. */
    @Test
    fun interfaceDelegationSeesAConstructorParameter() {
        clean("interface Base { fun p(): String }\nclass D(b: Base) : Base by b")
    }

    /** A spread fills a vararg with the array's ELEMENTS, and never a single-element overload. */
    @Test
    fun aSpreadArgumentBindsTheElementType() {
        clean("fun g(vararg s: String): List<String> = listOf(*s)")
        clean("fun g(a: Array<String>): List<String> = listOf(*a) + listOf(\"x\", *a) + mutableListOf(*a)")
        clean("fun g(a: Array<String>): Array<String> = arrayOf(*a)")
        clean("fun g(a: IntArray): List<Int> = intArrayOf(*a).toList()")
    }

    /** A Java class Kotlin maps to a built-in, written in its Java spelling, reaches the class's statics. */
    @Test
    fun javaStaticsOnMappedTypesResolve() {
        cleanWithJdk("fun f(r: Int): String = Integer.toHexString(r) + Integer.parseInt(\"1\") + Character.isDigit('c')")
        cleanWithJdk("fun f(r: Long): String = java.lang.Long.toBinaryString(r) + java.lang.Integer.parseInt(\"1\")")
    }

    @Test
    fun aJavaStaticThroughTheKotlinNameIsStillReported() {
        assertCodes("fun f(): String = String.valueOf(1)", KotlinDiagnosticCodes.UNRESOLVED, jdk = true)
    }

    /** An extension on a concrete instantiation (`Iterable<Double>.max()`) does not apply to a receiver whose
     *  arguments contradict it, so the `Comparable` overload wins. */
    @Test
    fun receiverTypeArgumentsRuleOutAnExtensionOverload() {
        clean("data class V(val a: Int) : Comparable<V> { override fun compareTo(other: V) = a - other.a }\nfun f(vs: List<V>): V? = vs.maxOrNull()")
        clean("fun f(vs: List<String>): String? = vs.minOrNull()\nfun g(vs: List<String>): String = vs.max()")
        clean("fun f(xs: List<Int>): Int = xs.max() + (xs.maxOrNull() ?: 0)\nfun g(xs: List<Double>): Double = xs.max()")
    }

    /** A SAM constructor is typed from its lambda's result, and its lambda's parameters from the type the
     *  context expects. */
    @Test
    fun samConstructorsInferTheirTypeArguments() {
        cleanWithJdk("import java.util.concurrent.Callable\nfun f(): Int = Callable { 42 }.call()")
        cleanWithJdk("import java.util.concurrent.*\nfun f(): Int = Executors.newSingleThreadExecutor().submit(Callable { 42 }).get()")
        cleanWithJdk("fun g(): Comparator<String> = Comparator { a, b -> a.length - b.length }")
        cleanWithJdk("fun g(xs: MutableList<String>) { java.util.Collections.sort(xs, Comparator { a, b -> a.length - b.length }) }")
    }

    /** An explicit import of a type outranks the default-imported built-in of the same name. */
    @Test
    fun anExplicitTypeImportBeatsTheBuiltin() {
        cleanWithJdk("import java.util.function.Function\nfun f(): Int = Function<String, Int> { it.length }.apply(\"ab\")\nfun g(fn: Function<Int, Int>): Int = fn.apply(1)")
    }

    /** A class implementing the `Comparator` typealias is a `java.util.Comparator`. */
    @Test
    fun aClassImplementingAnAliasedInterfaceIsAssignable() {
        cleanWithJdk("class ByLen : Comparator<String> { override fun compare(a: String, b: String) = a.length - b.length }\nfun f(xs: List<String>): List<String> = xs.sortedWith(ByLen())")
        cleanWithJdk("object ByLen : Comparator<String> { override fun compare(a: String, b: String) = a.length - b.length }\nval c: Comparator<String> = ByLen")
    }

    /** `Char` arithmetic: `'a' + 1` is a `Char`, `c - 'a'` an `Int`. */
    @Test
    fun charArithmeticKeepsChar() {
        clean("fun g(c: Char): Int = (c - 'a') + ('a' + 1).code + (c - 1).code")
    }

    /** A callable reference picks the overload whose functional return its target's result fits. */
    @Test
    fun aCallableReferenceSelectsTheMatchingOverload() {
        clean("data class P(val age: Int)\nfun g(ps: List<P>): Int = ps.sumOf(P::age)")
        clean("fun len(s: String): Int = s.length\nfun g(ps: List<String>): Int = ps.sumOf(::len) + ps.sumOf(String::length)")
    }

    /** A source function's `contract { returns(true) implies (x != null) }` smart-casts at its call sites. */
    @Test
    fun aUserContractNarrowsItsArgument() {
        val isValid = "import kotlin.contracts.*\n@OptIn(ExperimentalContracts::class)\n" +
            "fun isValid(s: String?): Boolean { contract { returns(true) implies (s != null) }; return s != null }\n"
        clean(isValid + "fun f(s: String?): Int = if (isValid(s)) s.length else 0")
        clean(isValid + "fun f(s: String?): Int { if (!isValid(s)) return 0; return s.length }")
        clean(
            "import kotlin.contracts.*\n@OptIn(ExperimentalContracts::class)\n" +
                "fun String?.present(): Boolean { contract { returns(true) implies (this@present != null) }; return this != null }\n" +
                "fun f(s: String?): Int = if (s.present()) s.length else 0"
        )
        assertCodes(isValid + "fun f(s: String?): Int = if (!isValid(s)) s.length else 0", KotlinDiagnosticCodes.UNSAFE_NULLABLE)
    }

    /** Java accessor properties: Kotlin's name rule (`getID` -> `id`), a protected field does not hide the
     *  accessor pair, and a property read never binds a same-named method. */
    @Test
    fun javaAccessorPropertiesFollowKotlinsRules() {
        cleanWithJdk("fun g(c: java.net.URLConnection): String = c.url.toString() + java.util.TimeZone.getDefault().id")
        cleanWithJdk("import java.util.*\nfun f(): Long { val c = Calendar.getInstance(); c.time = Date(); return c.time.time }")
        cleanWithJdk("fun g(t: Thread): Int { t.priority = 5; t.isDaemon = true; return t.priority }")
    }

    /** A class name passed as a value is its companion object. */
    @Test
    fun aClassNameAsAValueIsItsCompanion() {
        val decl = "interface Factory<T> { fun create(): T }\nclass Widget { companion object : Factory<Widget> { override fun create() = Widget() } }\n" +
            "fun <T> build(f: Factory<T>): T = f.create()\n"
        clean(decl + "fun f(): Widget = build(Widget)\nval g: Factory<Widget> = Widget\nfun h(): Factory<Widget> { return Widget }")
    }

    /** A member inherited from a generic SOURCE supertype is bound by the arguments the subclass gives it, so a
     *  lambda filling its `S.() -> S` parameter has `S` as its receiver: the base-ViewModel `setState { copy() }`
     *  idiom, through one or two levels and with or without an explicit receiver. */
    @Test
    fun anInheritedReceiverLambdaUsesTheSupertypesArguments() {
        clean(
            "abstract class Base<S : Any>(initial: S) {\n" +
                "    private var s: S = initial\n" +
                "    protected fun setState(reduce: S.() -> S) { s = s.reduce() }\n" +
                "    protected val current get() = s\n}\n" +
                "data class St(val n: Int = 0)\n" +
                "class Impl : Base<St>(St()) {\n" +
                "    fun inc() = setState { copy(n = n + 1) }\n" +
                "    fun inc2() = this.setState { copy(n = n + current.n) }\n}"
        )
        clean(
            "abstract class B0<S>(var s: S) { fun mod(f: S.() -> Unit) = s.f() }\n" +
                "abstract class B1<X>(x: X) : B0<X>(x)\nclass Sb(var v: Int)\n" +
                "class Impl2 : B1<Sb>(Sb(1)) { fun g() = mod { v += 1 } }\nfun h(i: Impl2) = i.mod { v = 2 }"
        )
    }

    /** `s.f()` invokes a parameter, local or property of extension function type with `s` as its receiver,
     *  for a concrete, supertype, or bounded type-parameter receiver; the result is the function's. */
    @Test
    fun aFunctionTypedValueIsInvokedOnAnExplicitReceiver() {
        clean("fun g(s: String, f: String.() -> Int) = s.f()")
        clean("fun g(s: String, f: CharSequence.() -> Int) = s.f()")
        clean("fun <S : CharSequence> g(s: S, f: S.() -> Int) = s.f()")
        clean("class W<S : CharSequence>(var s: S) { fun a(f: S.() -> S) { s = s.f() } }")
        clean("class P(val f: String.(Int) -> Boolean) { fun g(): Boolean = \"a\".f(1) }")
        clean("fun h(s: String, f: String.(Int) -> List<Int>): Int = s.f(2).size")
    }

    @Test
    fun aFunctionTypedValueOnTheWrongReceiverIsStillReported() {
        assertCodes("fun h(s: String, f: String.(Int) -> Int): String = s.f(1)", KotlinDiagnosticCodes.TYPE_MISMATCH)
        assertCodes("fun h(s: String, f: Int.() -> Int) = s.f()", KotlinDiagnosticCodes.UNRESOLVED)
        assertCodes("fun k(s: String, g: (String) -> Int) = s.g()", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /** A project typealias is seen only where Kotlin's scope rules put it: one package's
     *  `typealias Stroke = List<Int>` must not turn another file's imported `Stroke` class into a `List`. */
    @Test
    fun aTypealiasIsSeenOnlyWhereItIsInScope() {
        val files = mapOf(
            "draw/Stroke.kt" to "package demo.draw\nclass Stroke(val width: Float)\n",
            "carve/Carving.kt" to "package demo.carve\ntypealias Stroke = List<Int>\n",
        )
        cleanAcrossFiles(files, "package demo\nimport demo.draw.Stroke\nfun f(): Float = Stroke(1f).width\nfun g(s: Stroke): Float = s.width\n")
        cleanAcrossFiles(files, "package demo.carve\nfun f(s: Stroke): Int = s.size\n")
        cleanAcrossFiles(files, "package demo\nimport demo.carve.*\nfun f(s: Stroke): Int = s.size\n")
    }

    /** An extension declared on a typealias receiver extends the alias's target, from any file that sees it,
     *  including one that imports a same-named class under another name. */
    @Test
    fun anExtensionOnATypealiasReceiverResolves() {
        val files = mapOf(
            "draw/Stroke.kt" to "package demo.draw\nclass Stroke(val width: Float)\n",
            "carve/Carving.kt" to "package demo.carve\ntypealias Stroke = List<Int>\n" +
                "fun Stroke.extendedWith(p: Int): Stroke = this + p\nfun Stroke.isClosed(): Boolean = size > 3\n",
        )
        cleanAcrossFiles(
            files,
            "package demo.carve\nimport demo.draw.Stroke as DrawStroke\n" +
                "fun f(s: Stroke, d: DrawStroke): Boolean = s.extendedWith(1).isClosed() && d.width > 0f\n" +
                "fun g(all: List<Stroke>): Int = all.count { it.isClosed() }\n",
        )
    }

    /** A scope interface's member extension (`RowScope`'s `fun Modifier.weight()`) is a member: an
     *  implementation overrides it, and leaving it out is the usual missing-implementation error. */
    @Test
    fun aMemberExtensionIsOverriddenLikeAnyMember() {
        clean("interface Scope { fun String.shout(): String }\nclass Impl : Scope { override fun String.shout(): String = uppercase() }")
        assertCodes("interface Scope { fun String.shout(): String }\nclass Impl : Scope", KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED)
        assertCodes("interface Scope { fun String.shout(): String }\nclass Impl : Scope { override fun String.whisper(): String = this }",
            KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED, KotlinDiagnosticCodes.NOTHING_TO_OVERRIDE)
    }

    /** `a[i] = v` is `a.set(i, v)`, so the value must fit the operator's value parameter, bound from the
     *  receiver (`MutableMap<String, Int>`) and from the indices (DataStore's `prefs[intKey] = …`). */
    @Test
    fun anIndexedAssignmentOfTheWrongTypeIsReported() {
        assertCodes("fun f() { val m = mutableMapOf<String, Int>(); m[\"a\"] = \"x\" }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        assertCodes("fun f() { val l = mutableListOf(1); l[0] = \"x\" }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        assertCodes("class Grid { operator fun set(i: Int, j: Int, v: String) {} }\nfun f(g: Grid) { g[1, 2] = 3 }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        val prefs = "class Key<T>(val n: String)\nclass Prefs { private val m = HashMap<String, Any?>()\n" +
            "    operator fun <T> set(k: Key<T>, v: T) { m[k.n] = v }\n    @Suppress(\"UNCHECKED_CAST\") operator fun <T> get(k: Key<T>): T? = m[k.n] as T? }\n" +
            "val COUNT = Key<Int>(\"c\")\n"
        assertCodes(prefs + "fun f(p: Prefs) { p[COUNT] = \"x\" }", KotlinDiagnosticCodes.TYPE_MISMATCH)
        clean(prefs + "fun f(p: Prefs): Int { p[COUNT] = (p[COUNT] ?: 0) + 1; return p[COUNT]!! }")
        clean(
            "fun f() { val m = mutableMapOf<String, Any?>(); m[\"a\"] = null; m[\"b\"] = 3; val l = mutableListOf(1L); l[0] = 2\n" +
                "    val a = arrayOf(\"x\"); a[0] = \"y\"; val ia = IntArray(2); ia[1] = 3; val n = mutableListOf<Number>(1); n[0] = 2.5\n" +
                "    val sb = StringBuilder(\"ab\"); sb[0] = 'c' }"
        )
    }

    private fun cleanAcrossFiles(others: Map<String, String>, code: String) {
        val errors = runBlocking {
            val src = tempProject(others + ("Use.kt" to code))
            val a = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath())))
            val doc = SnippetDoc(code, DiskFile(src.resolve("Use.kt")))
            a.incrementalParser.parseFull(doc)
            a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR }
        }
        assertTrue(errors.isEmpty(), "expected `$code` clean, got $errors")
    }

    private fun clean(code: String) = assertClean(code, jdk = false)
    private fun cleanWithJdk(code: String) = assertClean(code, jdk = true)

    private fun assertClean(code: String, jdk: Boolean) {
        val errors = errors(code, jdk)
        assertTrue(errors.isEmpty(), "expected `$code` clean, got $errors")
    }

    private fun assertCodes(code: String, vararg expected: String, jdk: Boolean = false) {
        val errors = errors(code, jdk)
        assertEquals(expected.toList(), errors.map { it.code }, "for `$code`: $errors")
    }

    private fun errors(code: String, jdk: Boolean): List<Diagnostic> = runBlocking {
        val text = "package demo\n$code\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val jars = listOfNotNull(stdlibJarPath(), if (jdk) jdkJar else null)
        val a = KotlinSourceAnalyzer(fakeContext(src, jars))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    companion object {
        private val jdkJar: Path? =
            TestJars.jdkBaseJar(Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
