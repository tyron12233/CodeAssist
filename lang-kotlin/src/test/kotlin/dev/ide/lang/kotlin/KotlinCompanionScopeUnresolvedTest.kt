package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The unresolved-bare-reference check used to back off from an ENTIRE class body as soon as the class declared
 * a `companion object` (its members are bare-accessible, and nothing modeled them). That silently swallowed
 * every missing-import error in the most common Android shape — a `class …ViewModel { … companion object { …
 * } }` — so an unimported `withContext`/`viewModelScope` was never reported. The back-off is now name-precise:
 * a name that actually resolves through a companion in scope (the class's own, an inherited one, or the
 * companion's own NAME) is fine; anything else is reported as Kotlin reports it.
 */
class KotlinCompanionScopeUnresolvedTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(code: String) =
        diagnose(code).filter { it.code == "kt.unresolved" }.map { it.message }

    @Test
    fun unresolvedCallInAClassWithACompanionIsFlagged() {
        val u = unresolved(
            "package demo\nclass Vm {\n  fun load() { totallyBogusCall(1) }\n" +
                "  companion object { const val TAG = \"vm\" }\n}"
        )
        assertTrue(u.any { it.contains("totallyBogusCall") }, "a companion must not suppress unrelated names; got $u")
    }

    @Test
    fun companionPropertyResolvesBare() {
        val u = unresolved(
            "package demo\nclass Vm {\n  fun tag() = TAG\n  companion object { const val TAG = \"vm\" }\n}"
        )
        assertTrue(u.none { it.contains("TAG") }, "a companion property must resolve bare; got $u")
    }

    @Test
    fun companionFunctionResolvesBare() {
        val u = unresolved(
            "package demo\nclass Vm {\n  fun make() = create()\n  companion object { fun create() = Vm() }\n}"
        )
        assertTrue(u.none { it.contains("create") }, "a companion function must resolve bare; got $u")
    }

    @Test
    fun namedCompanionResolvesByItsOwnName() {
        val u = unresolved(
            "package demo\nclass Vm {\n  fun make() = Factory.create()\n  companion object Factory { fun create() = Vm() }\n}"
        )
        assertTrue(u.none { it.contains("Factory") }, "a named companion must resolve by its name; got $u")
    }

    @Test
    fun outerCompanionResolvesFromANestedClass() {
        val u = unresolved(
            "package demo\nclass Outer {\n  companion object { const val TAG = \"o\" }\n" +
                "  class Nested { fun tag() = TAG }\n}"
        )
        assertTrue(u.none { it.contains("TAG") }, "an outer companion member must resolve in a nested class; got $u")
    }

    @Test
    fun unresolvableSupertypeStillBacksOff() {
        // The class's inherited scope (including any companion it inherits) can't be enumerated, so bare names
        // in its body are left alone — the supertype itself is what gets flagged.
        val u = unresolved(
            "package demo\nclass Vm : SomeUnknownBase() {\n  fun load() { totallyBogusCall(1) }\n}"
        )
        assertTrue(u.none { it.contains("totallyBogusCall") }, "an unresolvable supertype must back off; got $u")
    }

    private companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
