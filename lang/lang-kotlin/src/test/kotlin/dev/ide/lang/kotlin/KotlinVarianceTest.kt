package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Variance / projection misuse over the parse-only model, following the Kotlin spec's declaration-site and
 * use-site variance rules:
 *  - TYPE_VARIANCE_CONFLICT (`kt.varianceConflict`): an `out` type parameter in an `in`/invariant position, or
 *    an `in` one in an `out`/invariant position — computed by composing the position through nested generics
 *    (builtin + source variance), function types (syntactic), and use-site projections.
 *  - CONFLICTING_PROJECTION (`kt.conflictingProjection`, error) / REDUNDANT_PROJECTION (`kt.redundantProjection`,
 *    warning): a use-site `in`/`out` projection against the classifier's declaration-site variance.
 * Conservative throughout: an unknown-variance (classpath) classifier, a private member, a shadowing member
 * type parameter, `@UnsafeVariance`, and an invariant/star slot all back off, so nothing false-positives.
 */
class KotlinVarianceTest {
    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }
    private fun codes(fileName: String, code: String) = diagnose(fileName, code).mapNotNull { it.code }
    private val P = "package demo\n"

    // --- declaration-site variance conflict ---

    @Test fun outParamInInPositionIsFlagged() {
        assertTrue(codes("V1.kt", P + "interface S<out T> { fun consume(t: T) }").contains("kt.varianceConflict"), "out T in a parameter (in position)")
        assertTrue(codes("V2.kt", P + "interface S<out T> { var x: T }").contains("kt.varianceConflict"), "out T in a var (invariant position)")
    }

    @Test fun inParamInOutPositionIsFlagged() {
        assertTrue(codes("V3.kt", P + "interface S<in T> { fun produce(): T }").contains("kt.varianceConflict"), "in T in a return (out position)")
        assertTrue(codes("V4.kt", P + "interface S<in T> { val x: T }").contains("kt.varianceConflict"), "in T in a val (out position)")
    }

    @Test fun nestedGenericCompositionIsFlagged() {
        // MutableList is invariant → out T in an invariant slot is a conflict.
        assertTrue(codes("V5.kt", P + "interface S<out T> { fun get(): MutableList<T> }").contains("kt.varianceConflict"), "out T in MutableList (invariant)")
        // Comparable is `in T` → out T flips to an in position → conflict.
        assertTrue(codes("V6.kt", P + "interface S<out T> { fun cmp(): Comparable<T> }").contains("kt.varianceConflict"), "out T under Comparable's in-param")
        // A function type contributes its parameter at an in position (syntactic) → conflict.
        assertTrue(codes("V7.kt", P + "interface S<out T> { fun handler(): (T) -> Unit }").contains("kt.varianceConflict"), "out T as a function-type parameter")
        // A supertype's type argument is produced (out position) → Comparable flips it → conflict.
        assertTrue(codes("V8.kt", P + "interface S<out T> : Comparable<T>").contains("kt.varianceConflict"), "out T in a contravariant supertype")
    }

    @Test fun correctPositionsAreClean() {
        val ok = listOf(
            "interface S<out T> { fun produce(): T }",                // out in an out position
            "interface S<in T> { fun consume(t: T) }",                // in in an in position
            "interface S<out T> { fun items(): List<T> }",            // List is covariant → T stays out
            "interface S<in T> { fun accept(x: List<T>) }",           // List covariant, in param → T stays in
            "interface S<T> { fun consume(t: T); fun produce(): T }", // invariant param is unconstrained
            "class S<out T>(val x: T)",                               // a val ctor property is out
        )
        for ((i, o) in ok.withIndex()) {
            val c = codes("Vok$i.kt", P + o)
            assertTrue(!c.contains("kt.varianceConflict"), "`$o` must be clean; got $c")
        }
    }

    @Test fun exemptionsBackOff() {
        assertTrue(!codes("VE1.kt", P + "class S<out T> { private fun consume(t: T) {} }").contains("kt.varianceConflict"), "private member is exempt")
        assertTrue(!codes("VE2.kt", P + "interface S<out T> { fun <T> consume(t: T) }").contains("kt.varianceConflict"), "a member type parameter shadows the class's")
        assertTrue(!codes("VE3.kt", P + "interface S<out T> { fun consume(t: @UnsafeVariance T) }").contains("kt.varianceConflict"), "@UnsafeVariance suppresses the conflict")
    }

    @Test fun unknownClasspathVarianceBacksOff() {
        // AtomicReference is a classpath (Java) generic whose variance the parse-only model can't know → no flag.
        val c = codes("VU.kt", P + "import java.util.concurrent.atomic.AtomicReference\ninterface S<out T> { fun get(): AtomicReference<T> }")
        assertTrue(!c.contains("kt.varianceConflict"), "an unknown-variance classpath generic backs off; got $c")
    }

    // --- use-site projections ---

    @Test fun conflictingProjectionIsFlagged() {
        assertTrue(codes("VP1.kt", P + "fun f(x: List<in String>) {}").contains("kt.conflictingProjection"), "in projection on List's out param")
        assertTrue(codes("VP2.kt", P + "fun f(x: Comparable<out String>) {}").contains("kt.conflictingProjection"), "out projection on Comparable's in param")
        assertTrue(codes("VP3.kt", P + "interface MyProd<out E>\nfun f(x: MyProd<in String>) {}").contains("kt.conflictingProjection"), "in projection on a source out param")
    }

    @Test fun redundantProjectionIsWarned() {
        assertTrue(codes("VR1.kt", P + "fun f(x: List<out String>) {}").contains("kt.redundantProjection"), "out projection on List's out param")
        assertTrue(codes("VR2.kt", P + "fun f(x: Comparable<in String>) {}").contains("kt.redundantProjection"), "in projection on Comparable's in param")
        assertTrue(codes("VR3.kt", P + "interface MyProd<out E>\nfun f(x: MyProd<out String>) {}").contains("kt.redundantProjection"), "out projection on a source out param")
    }

    @Test fun meaningfulAndUnknownProjectionsAreClean() {
        val ok = listOf(
            "fun f(x: Array<out Any>) {}",          // Array is invariant → out is meaningful
            "fun f(x: MutableList<out String>) {}", // MutableList is invariant → out is meaningful
            "fun f(x: List<String>) {}",            // no projection
            "fun f(x: List<*>) {}",                 // star projection
        )
        for ((i, o) in ok.withIndex()) {
            val c = codes("VPok$i.kt", P + o)
            assertTrue(!c.contains("kt.conflictingProjection") && !c.contains("kt.redundantProjection"), "`$o` must be clean; got $c")
        }
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
