package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Constructor-call argument TYPE checking ([KotlinSourceAnalyzer]'s `sameFileConstructorMismatch` /
 * `constructorCallMismatch`). A wrong-typed argument passed to a constructor must be flagged
 * `kt.typeMismatch` — including REFERENCE types, not just primitives — using the same assignability rule
 * as a declaration's `val a: T = …`. Legitimate subtype passing and fully-unknown hierarchies must NOT be
 * flagged (the parse-only model backs off when a type or its supertype chain isn't on the classpath).
 */
class KotlinConstructorArgTypeTest {

    private fun diagnose(code: String): List<Diagnostic> {
        // Write the file to disk so the source model (which scans source roots) sees the same-file classes —
        // the way ide-core has them indexed in production.
        val srcDir = tempProject(mapOf("A.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("A.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun primitiveArgMismatchIsFlagged() {
        // An Int where the constructor wants a String.
        val d = diagnose("package p\nclass Foo(val x: String)\nfun f() { Foo(123) }")
        assertTrue(
            d.any { it.code == "kt.typeMismatch" && it.message.contains("Int") && it.message.contains("String") },
            "Foo(123) for Foo(String) must be flagged; got $d",
        )
    }

    @Test
    fun referenceArgMismatchIsFlagged() {
        // An unrelated class passed where another is required — the case that previously went unreported.
        val d = diagnose(
            "package p\nclass Bar\nclass Baz\nclass Foo(val b: Bar)\nfun f() { Foo(Baz()) }",
        )
        assertTrue(
            d.any { it.code == "kt.typeMismatch" && it.message.contains("Baz") && it.message.contains("Bar") },
            "Foo(Baz()) for Foo(Bar) must be flagged as a type mismatch; got $d",
        )
    }

    @Test
    fun subtypeArgIsNotFlagged() {
        // Passing a subtype to a supertype parameter is valid — must NOT be flagged.
        val d = diagnose(
            "package p\nopen class Base\nclass Sub : Base()\nclass Foo(val b: Base)\nfun f() { Foo(Sub()) }",
        )
        assertTrue(
            d.none { it.code == "kt.typeMismatch" },
            "Foo(Sub()) where a Base is expected must NOT be flagged; got $d",
        )
    }

    @Test
    fun exactReferenceTypeIsNotFlagged() {
        val d = diagnose(
            "package p\nclass Bar\nclass Foo(val b: Bar)\nfun f() { Foo(Bar()) }",
        )
        assertTrue(
            d.none { it.code == "kt.typeMismatch" },
            "Foo(Bar()) where a Bar is expected must NOT be flagged; got $d",
        )
    }

    @Test
    fun unknownArgTypeBacksOff() {
        // The argument's type can't be resolved (an unknown call) — no confident mismatch, must NOT be flagged.
        val d = diagnose(
            "package p\nclass Foo(val x: String)\nfun f() { Foo(mystery()) }",
        )
        assertTrue(
            d.none { it.code == "kt.typeMismatch" },
            "an unresolved argument expression must not produce a type-mismatch; got $d",
        )
    }
}
