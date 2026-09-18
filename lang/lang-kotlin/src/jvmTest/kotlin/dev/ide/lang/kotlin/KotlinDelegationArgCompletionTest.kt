package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Named-argument completion inside a constructor-DELEGATION call — a supertype constructor call in a class
 * header (`class Sub : Base(<caret>)`), a `this(<caret>)` and a `super(<caret>)` secondary-constructor
 * delegation. These are distinct PSI from a plain `Foo(<caret>)` call, which the ordinary named-argument path
 * (KtCallExpression) misses; the target constructor's parameter names should still be offered.
 */
class KotlinDelegationArgCompletionTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }

    @Test
    fun supertypeConstructorCallOffersItsParameterNames() {
        val ls = labels("package demo\nclass Sub : Base(|)\n")
        assertTrue("id =" in ls, "the supertype constructor's parameter should be offered; got $ls")
        assertTrue("name =" in ls, "and every other parameter; got $ls")
    }

    @Test
    fun suppliedSupertypeParameterIsNotReoffered() {
        val ls = labels("package demo\nclass Sub : Base(id = 1, |)\n")
        assertTrue("name =" in ls, "the remaining parameter should be offered; got $ls")
        assertTrue("id =" !in ls, "an already-supplied parameter must not be re-offered; got $ls")
    }

    @Test
    fun thisDelegationOffersTheClassOwnConstructorParameters() {
        val ls = labels(
            "package demo\nclass C {\n  constructor(count: Int) {}\n  constructor() : this(|) {}\n}\n",
        )
        assertTrue("count =" in ls, "the delegated (`this`) constructor's parameter should be offered; got $ls")
    }

    @Test
    fun superDelegationOffersTheSuperclassConstructorParameters() {
        val ls = labels(
            "package demo\nclass Sub : Base {\n  constructor() : super(|) {}\n}\n",
        )
        assertTrue("id =" in ls, "the superclass constructor's parameter should be offered; got $ls")
        assertTrue("name =" in ls, "and every other superclass parameter; got $ls")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Base.kt" to "package demo\nopen class Base(val id: Int, val name: String)\n",
                // Written clean (no caret); the completion buffer supplies the delegation call + marker.
                "Use.kt" to "package demo\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
