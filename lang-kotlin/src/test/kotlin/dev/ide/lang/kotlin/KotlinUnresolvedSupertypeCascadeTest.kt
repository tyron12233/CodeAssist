package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An object literal whose SUPERTYPE doesn't resolve reports ONE error, the unresolved reference. The
 * anonymous type's supertype closure is empty while the name is missing, so it looked assignable to nothing
 * and a second "Type mismatch: inferred type is <anonymous : ViewOutlineProvider> but ViewOutlineProvider
 * was expected" was reported over the WHOLE literal: an error whose message contradicts itself, underlining
 * every line of the object body, and sitting on top of the one finding that carries an Import fix.
 *
 * A real anonymous mismatch, where the supertype resolves and is simply unrelated to the expected type,
 * must still be reported.
 */
class KotlinUnresolvedSupertypeCascadeTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun assertOnlyUnresolved(code: String, name: String) {
        val diags = diagnose(code)
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains(name) },
            "the missing reference must still be reported; got $diags",
        )
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "an object literal on an unresolved supertype must not also be a type mismatch; got $diags",
        )
    }

    @Test fun argumentPositionReportsOnlyTheMissingReference() {
        // The reported shape: `addListener(object : AnimatorListenerAdapter() { … })` with the import missing.
        assertOnlyUnresolved(
            "package demo\nfun interface Cb { fun run() }\nfun take(c: Cb) { }\n" +
                "fun f() { take(object : UnknownAdapterXyz() { }) }\n",
            "UnknownAdapterXyz",
        )
    }

    @Test fun assignmentPositionReportsOnlyTheMissingReference() {
        assertOnlyUnresolved(
            "package demo\nclass Host { var slot: String = \"\" }\n" +
                "fun f(h: Host) { h.slot = object : UnknownProviderXyz() { } }\n",
            "UnknownProviderXyz",
        )
    }

    @Test fun initializerPositionReportsOnlyTheMissingReference() {
        assertOnlyUnresolved("package demo\nval x: String = object : UnknownXyz() { }\n", "UnknownXyz")
    }

    @Test fun aResolvableButUnrelatedSupertypeIsStillAMismatch() {
        val diags = diagnose(
            "package demo\nval x: String = object : Comparable<Int> {\n" +
                "    override fun compareTo(other: Int): Int = 0\n}\n",
        )
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" && it.message.contains("String") },
            "a resolvable supertype unrelated to the expected type is a real mismatch; got $diags",
        )
    }

    @Test fun aMatchingResolvableSupertypeIsClean() {
        val diags = diagnose(
            "package demo\nval x: Comparable<Int> = object : Comparable<Int> {\n" +
                "    override fun compareTo(other: Int): Int = 0\n}\n",
        )
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "the supertype IS the expected type; got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
