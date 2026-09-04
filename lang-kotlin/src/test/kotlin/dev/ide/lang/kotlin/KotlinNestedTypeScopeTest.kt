package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A NESTED type named by its SIMPLE name from inside the enclosing body — the scope
 * `KotlinSymbolService.resolveTypeName` reaches through its `enclosingClassFqn` parameter.
 *
 * That scope has two requirements, and both were unmet. (a) The parameter must actually be PASSED: most call
 * sites omitted it, so which paths saw a nested type was arbitrary — a nested enum's constant read
 * "Unresolved reference: A" even though `enumConstantsOf` knew both entries, and every declared-type position
 * (parameter, local, return, `is`, `as`) silently lost the type. (b) It must be tried in Kotlin's ORDER: the
 * step ran LAST, after same-package and star imports, so a nested declaration lost to a same-named one at
 * file level.
 *
 * Each nested case is paired with its TOP-LEVEL control and must produce the SAME verdict — proving the type
 * was resolved, not that a check merely went quiet.
 */
class KotlinNestedTypeScopeTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val srcDir = tempProject(mapOf("A.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("A.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(d: List<Diagnostic>) = d.filter { it.code == "kt.unresolved" }
    private fun mismatch(d: List<Diagnostic>) = d.filter { it.code == "kt.typeMismatch" }

    // --- a nested enum's constants ---------------------------------------------------------------

    @Test
    fun nestedEnumConstantResolves() {
        val inObject = diagnose("package p\nobject Api {\n enum class Lvl { A, B }\n val ONE: Lvl = Lvl.A\n}")
        assertTrue(unresolved(inObject).isEmpty(), "`Lvl.A` on an enum nested in an object must resolve; got ${unresolved(inObject)}")
        val inClass = diagnose("package p\nclass Api {\n enum class Lvl { A, B }\n val ONE: Lvl = Lvl.A\n}")
        assertTrue(unresolved(inClass).isEmpty(), "`Lvl.A` on an enum nested in a class must resolve; got ${unresolved(inClass)}")
        // From OUTSIDE, through the enclosing object as a qualifier — `Api` is a singleton, which used to
        // disqualify it as the receiver of a nested-type lookup.
        val qualified = diagnose("package p\nobject Api { enum class Lvl { A, B } }\nval ONE: Api.Lvl = Api.Lvl.A")
        assertTrue(unresolved(qualified).isEmpty(), "`Api.Lvl.A` must resolve through the object qualifier; got ${unresolved(qualified)}")
    }

    @Test
    fun nestedEnumConstantTypesAsTheEnum() {
        // Positive proof, not just silence: the constant's type is the enum.
        val d = diagnose("package p\nobject Api {\n enum class Lvl { A, B }\n val bad: Boolean = Lvl.A\n}")
        assertTrue(mismatch(d).isNotEmpty(), "`val bad: Boolean = Lvl.A` must mismatch — proving `Lvl.A` typed as Lvl; got $d")
    }

    @Test
    fun nestedEnumWhenIsCheckedForExhaustiveness() {
        // The subject is a PARAMETER typed by the nested enum, so this needs both halves: the parameter typed,
        // and the enum's constants enumerated from that type.
        val nested = diagnose("package p\nobject Api {\n enum class L { A, B }\n fun f(l: L): Int = when (l) { L.A -> 1 }\n}")
        assertTrue(
            nested.any { it.code == "kt.whenExhaustive" },
            "a `when` over a nested enum must be checked for exhaustiveness, as a top-level one is; got $nested",
        )
    }

    // --- Kotlin's innermost-out ORDER ------------------------------------------------------------

    @Test
    fun nestedObjectShadowsSameNamedTopLevelClass() {
        val d = diagnose("package p\nclass Cfg(val n: Int)\nclass Host {\n object Cfg\n fun f() = Cfg\n}")
        assertTrue(
            d.none { it.code == "kt.classifierAsValue" },
            "inside Host, `Cfg` is the nested OBJECT (a value), not the top-level class; got $d",
        )
    }

    @Test
    fun nestedClassShadowsSameNamedTopLevelClass() {
        // The top-level `T` has `a`/`b`, the nested one has `n`. Resolving the parameter to the top-level type
        // made `t.n` a false "unresolved"; against the nested type it is an Int, so the Boolean mismatches.
        val d = diagnose("package p\nclass T(val a: Int, val b: Int)\nobject Api {\n class T(val n: Int)\n fun f(t: T): Boolean = t.n\n}")
        assertTrue(unresolved(d).isEmpty(), "`t.n` must resolve on the NESTED T; got ${unresolved(d)}")
        assertTrue(mismatch(d).isNotEmpty(), "`t.n` is Int, so the Boolean return must mismatch; got $d")
    }

    @Test
    fun nestedClassShadowsSameNamedImport() {
        val d = diagnose("package p\nimport java.util.Locale\nclass Host {\n class Locale(val n: Int)\n fun f(l: Locale): Boolean = l.n\n}")
        assertTrue(unresolved(d).isEmpty(), "inside Host, `Locale` is the nested class, not the import; got ${unresolved(d)}")
        assertTrue(mismatch(d).isNotEmpty(), "the nested Locale's `n` is Int, so the Boolean return must mismatch; got $d")
    }

    @Test
    fun builtinSimpleTypeIsNotShadowedByTheEnclosingChain() {
        // The built-in step stays FIRST — it is intrinsic to the language, not file-level scope.
        val d = diagnose("package p\nclass Host {\n fun f(s: String): Boolean = s.length\n}")
        assertTrue(mismatch(d).isNotEmpty(), "`String` must still be kotlin.String, so `s.length` is Int; got $d")
    }

    // --- every declared-type position -----------------------------------------------------------

    /** A nested type used where a type is WRITTEN. Each snippet reads an `Int` member into a `Boolean`, so a
     *  resolved type produces `kt.typeMismatch` and a lost one produces nothing. */
    @Test
    fun nestedTypeResolvesInEveryDeclaredTypePosition() {
        val nested = mapOf(
            "parameter" to "object Api {\n class T(val n: Int)\n fun f(t: T): Boolean = t.n\n}",
            "local val" to "object Api {\n class T(val n: Int)\n fun f() { val t: T = T(1)\n val b: Boolean = t.n\n println(b) }\n}",
            "return type" to "object Api {\n class T(val n: Int)\n fun g(): T = T(1)\n val b: Boolean = g().n\n}",
            "is target" to "object Api {\n class T(val n: Int)\n fun f(a: Any) { if (a is T) { val b: Boolean = a.n\n println(b) } }\n}",
            "as target" to "object Api {\n class T(val n: Int)\n fun f(a: Any) { val b: Boolean = (a as T).n\n println(b) }\n}",
            "for-loop param" to "object Api {\n class T(val n: Int)\n fun f(l: List<T>) { for (t: T in l) { val b: Boolean = t.n\n println(b) } }\n}",
        )
        for ((position, src) in nested) {
            val d = diagnose("package p\n$src")
            assertTrue(mismatch(d).isNotEmpty(), "a nested type in $position position must type; got $d for `$src`")
        }
    }

    @Test
    fun nestedTypeMemberIsStillCheckedInDeclaredTypePositions() {
        // The flip side: a resolved nested type must still catch a genuinely missing member.
        val d = diagnose("package p\nobject Api {\n class T(val n: Int)\n fun f(t: T) { t.nope }\n}")
        assertTrue(
            unresolved(d).any { it.message.contains("nope") },
            "a missing member on a nested-typed parameter must be flagged; got $d",
        )
    }
}
