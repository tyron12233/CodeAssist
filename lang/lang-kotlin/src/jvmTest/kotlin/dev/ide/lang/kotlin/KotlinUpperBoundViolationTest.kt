package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `kt.upperBoundViolated` — Kotlin's UPPER_BOUND_VIOLATED for a type argument outside its parameter's bound.
 *
 * The check existed and fired for `Test<Int>` while staying silent on `Test<Number>`, because it proved
 * "definitely not a subtype" only for a hard-coded list of FINAL types. Finality is the wrong question: what
 * licenses the conclusion is whether the argument's supertype closure is completely KNOWN. `Number` is not
 * final and its hierarchy is known exactly; a half-indexed classpath type may be final and not known at all.
 *
 * Both halves are asserted here. The back-offs matter as much as the reports: a parse-only model that
 * false-positives on correct code is worse than one that stays quiet.
 */
class KotlinUpperBoundViolationTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun bound(fileName: String, code: String) =
        diagnose(fileName, code).firstOrNull { it.code == KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED }

    // --- reported ---

    @Test
    fun anOpenBuiltinArgumentOutsideTheBoundIsFlagged() {
        // The reported case: `Number` is not a `CharSequence`, and not being final never made that unclear.
        val d = bound("HelloNumber.kt", "package demo\nclass HelloNumber : Box<Number>()")
        assertNotNull(d, "Number violates `K : CharSequence`")
        assertTrue(d.message.contains("CharSequence"), "the message names the bound; got '${d.message}'")
    }

    @Test
    fun anyIsFlaggedToo() {
        assertNotNull(bound("HelloAny.kt", "package demo\nclass HelloAny : Box<Any>()"), "Any is not a CharSequence")
    }

    @Test
    fun aFinalArgumentIsStillFlagged() {
        assertNotNull(bound("HelloInt.kt", "package demo\nclass HelloInt : Box<Int>()"), "the case that already worked")
    }

    @Test
    fun aProjectClassOutsideTheBoundIsFlagged() {
        // A source class whose every declared supertype resolves: the closure is complete, so this is provable.
        assertNotNull(bound("HelloPlain.kt", "package demo\nclass HelloPlain : Box<Plain>()"), "Plain is not a CharSequence")
    }

    // --- silent, and must stay so ---

    @Test
    fun anArgumentWithinTheBoundIsSilent() {
        assertTrue(bound("OkString.kt", "package demo\nclass OkString : Box<String>()") == null, "String IS a CharSequence")
        assertTrue(
            bound("OkBuilder.kt", "package demo\nclass OkBuilder : Box<StringBuilder>()") == null,
            "StringBuilder IS a CharSequence",
        )
    }

    @Test
    fun aProjectClassThatDoesImplementTheBoundIsSilent() {
        assertTrue(bound("OkSeq.kt", "package demo\nclass OkSeq : Box<MySeq>()") == null, "MySeq implements CharSequence")
    }

    @Test
    fun anUnresolvableArgumentBacksOff() {
        // Nothing is known about `Mystery`, so nothing can be concluded about it.
        assertTrue(bound("Unknown.kt", "package demo\nclass Unknown : Box<Mystery>()") == null, "an unknown type backs off")
    }

    @Test
    fun aTypeParameterArgumentBacksOff() {
        // `T` could be anything the caller supplies; the bound is checked where the argument is supplied.
        assertTrue(
            bound("Passthrough.kt", "package demo\nclass Passthrough<T> { fun make(): Box<T>? = null }") == null,
            "a type-parameter argument backs off",
        )
    }

    @Test
    fun aStarProjectionBacksOff() {
        assertTrue(bound("Star.kt", "package demo\nfun read(b: Box<*>) = b") == null, "a star projection names no type")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Box.kt" to """
                    package demo
                    open class Box<K : CharSequence>
                    class Plain
                    class MySeq : CharSequence {
                        override val length: Int get() = 0
                        override fun get(index: Int): Char = ' '
                        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this
                    }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
