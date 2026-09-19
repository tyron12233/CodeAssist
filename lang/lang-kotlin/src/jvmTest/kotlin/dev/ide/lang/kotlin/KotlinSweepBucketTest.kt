package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The false positives the repository sweep's remaining buckets turned out to be — each reproduced here in
 * ordinary, self-contained Kotlin, so none of them depends on what the sweep harness has on its classpath.
 *
 * Four buckets, three root causes, all of them shapes a normal project is made of: a sealed hierarchy whose
 * cases are named after built-ins, a `+=` into a collection a call returned, and the idiomatic null guard.
 */
class KotlinSweepBucketTest {

    private fun errors(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    /**
     * `Field.Number(…)` names `Field`'s nested case. Resolving only the SELECTOR sent the lookup to
     * `kotlin.Number`, which is abstract, so an ordinary sealed hierarchy was told it could not be built.
     */
    @Test
    fun aNestedCaseNamedAfterABuiltinCanBeConstructedThroughItsOwner() {
        val diags = errors(
            "NestedCase.kt",
            """
            package demo
            sealed interface Field {
                data class Number(val key: String, val value: Long) : Field
                data class Text(val key: String, val value: String) : Field
            }
            fun make(): Field = Field.Number("a", 1L)
            fun other(): Field = Field.Text("b", "c")
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "`Field.Number` is the nested case; got ${diags.map { it.message }}")
    }

    /** The built-in is still what an UNQUALIFIED, unshadowed use means. */
    @Test
    fun anUnshadowedBuiltinIsStillAbstract() {
        val diags = errors("StillAbstract.kt", "package demo\nfun f(): Any = Number()\n")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.ABSTRACT_INSTANTIATION },
            "`kotlin.Number` cannot be instantiated; got ${diags.map { it.message }}",
        )
    }

    /**
     * `a.b() += c` is `a.b().plusAssign(c)` whenever the returned type declares that operator, and only falls
     * back to `a.b() = a.b() + c` when it does not — so it needs no assignable target.
     */
    @Test
    fun aCompoundAssignmentIntoACallResultIsNotAnAssignment() {
        val diags = errors(
            "PlusAssign.kt",
            """
            package demo
            fun j(): Map<String, MutableSet<String>> {
                val out = HashMap<String, MutableSet<String>>()
                out.getOrPut("k") { HashSet() } += "v"
                return out
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "`+=` mutates the set the call returned; got ${diags.map { it.message }}")
    }

    /** A plain `=` to something unassignable is still an error. */
    @Test
    fun aPlainAssignmentToACallIsStillFlagged() {
        val diags = errors("BadAssign.kt", "package demo\nfun g(): Int {\n    f() = 1\n    return 0\n}\nfun f(): Int = 0\n")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.VARIABLE_EXPECTED },
            "`f() = 1` has no assignable target; got ${diags.map { it.message }}",
        )
    }

    /**
     * `isNullOrBlank` / `isNullOrEmpty` carry `returns(false) implies (this != null)`, and they are declared
     * ON a nullable receiver. Both halves matter: without the contract the line AFTER the guard was flagged,
     * and without the nullable receiver the guard LINE ITSELF was.
     */
    @Test
    fun theIdiomaticNullGuardIsNeitherUnsafeNorUseless() {
        val diags = errors(
            "Guard.kt",
            """
            package demo
            fun l(doc: String?) {
                if (doc.isNullOrBlank()) return
                doc.lineSequence().forEach { println(it) }
            }
            fun m(xs: List<String>?) {
                if (xs.isNullOrEmpty()) return
                println(xs.size)
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "got ${diags.map { it.message }}")
    }

    /** A genuinely unguarded nullable deref is still an error. */
    @Test
    fun anUnguardedNullableReceiverIsStillFlagged() {
        val diags = errors("Unguarded.kt", "package demo\nfun n(doc: String?): Int = doc.length\n")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.UNSAFE_NULLABLE },
            "`doc.length` with no guard is unsafe; got ${diags.map { it.message }}",
        )
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
