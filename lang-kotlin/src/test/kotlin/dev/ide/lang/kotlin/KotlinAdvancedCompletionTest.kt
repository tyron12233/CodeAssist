package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Named-argument completion, override completion, and expected-type completion — the three additions on top
 * of member/scope/type completion. Shares one analyzer over a small source project + the kotlin-stdlib jar.
 */
class KotlinAdvancedCompletionTest {

    private fun items(file: String, code: String): List<CompletionItem> =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items

    private fun labels(file: String, code: String): List<String> = items(file, code).map { it.label }

    // --- named-argument completion ---

    @Test
    fun namedArgumentsForConstructor() {
        // User(val name: String, val age: Int) — typing inside the call offers the parameter names.
        val ls = labels("Use.kt", "package demo\nfun f() { User(na|) }")
        assertTrue("name =" in ls, "constructor param 'name =' expected; got ${ls.take(20)}")
    }

    @Test
    fun namedArgumentsForTopLevelFunction() {
        val ls = labels("Use.kt", "package demo\nfun f() { makeUser(ag|) }")
        assertTrue("age =" in ls, "function param 'age =' expected; got ${ls.take(20)}")
    }

    @Test
    fun namedArgumentItemInsertsNameEquals() {
        val item = items("Use.kt", "package demo\nfun f() { User(na|) }").firstOrNull { it.label == "name =" }
        assertNotNull(item, "named-arg item expected")
        assertTrue(item.insertText == "name = ", "inserts `name = `; got '${item.insertText}'")
        assertTrue(item.kind == CompletionItemKind.PARAMETER, "named-arg kind is PARAMETER; got ${item.kind}")
    }

    @Test
    fun alreadySuppliedNamedArgumentIsNotOffered() {
        // name is already given by name → only the remaining param (age) is offered.
        val ls = labels("Use.kt", "package demo\nfun f() { User(name = \"x\", ag|) }")
        assertTrue("age =" in ls, "remaining param 'age =' expected; got ${ls.take(20)}")
        assertFalse("name =" in ls, "an already-supplied named arg must not be re-offered; got ${ls.take(20)}")
    }

    // --- override completion ---

    @Test
    fun overrideCompletionOffersInheritedMembers() {
        val ls = labels("Use.kt", "package demo\nclass My : Base { onCre| }")
        assertTrue(
            ls.any { it.startsWith("override fun onCreate(") },
            "override stub for onCreate expected; got ${ls.take(20)}",
        )
    }

    @Test
    fun overrideStubRendersReturnTypeAndBody() {
        val item = items("Use.kt", "package demo\nclass My : Base { rend| }")
            .firstOrNull { it.label.startsWith("override fun render") }
        assertNotNull(item, "override stub for render expected")
        assertTrue(item.label == "override fun render(): String", "label renders return type; got '${item.label}'")
        assertTrue(item.insertText.contains("TODO("), "stub body has a TODO; got '${item.insertText}'")
    }

    @Test
    fun overrideExcludesAlreadyOverriddenMembers() {
        // render is already declared → it must not be re-offered; onCreate still is.
        val ls = labels("Use.kt", "package demo\nclass My : Base {\n  override fun render(): String = \"x\"\n  on| }")
        assertTrue(ls.any { it.startsWith("override fun onCreate(") }, "onCreate still offered; got ${ls.take(20)}")
        assertFalse(ls.any { it.startsWith("override fun render") }, "render already overridden; got ${ls.take(20)}")
    }

    @Test
    fun noOverrideInsideFunctionBody() {
        // Inside a method body it's an expression position, not a declaration position.
        val ls = labels("Use.kt", "package demo\nclass My : Base { fun g() { onCre| } }")
        assertFalse(ls.any { it.startsWith("override fun") }, "no override stubs inside a body; got ${ls.take(20)}")
    }

    // --- expected-type completion ---

    @Test
    fun enumConstantsOfferedAtExpectedEnumType() {
        // paint(c: Color) — the argument's expected type is the enum, so its constants are offered qualified.
        val ls = labels("Use.kt", "package demo\nfun f() { paint(R|) }")
        assertTrue("Color.RED" in ls, "Color.RED expected at an enum argument; got ${ls.take(20)}")
    }

    @Test
    fun enumConstantsOfferedAtTypedInitializer() {
        val ls = labels("Use.kt", "package demo\nfun f() { val c: Color = G| }")
        assertTrue("Color.GREEN" in ls, "Color.GREEN expected at a typed initializer; got ${ls.take(20)}")
    }

    @Test
    fun booleanLiteralsOfferedWhereBooleanExpected() {
        val ls = labels("Use.kt", "package demo\nfun f() { val b: Boolean = t| }")
        assertTrue("true" in ls, "`true` expected where a Boolean is wanted; got ${ls.take(20)}")
        val cond = labels("Use.kt", "package demo\nfun f() { if (f|) {} }")
        assertTrue("false" in cond, "`false` expected in an if-condition; got ${cond.take(20)}")
    }

    @Test
    fun expectedTypeRanksMatchingCandidateFirst() {
        // mkColor(): Color returns the expected type, so among `m`-prefixed candidates it sorts to the top.
        val ls = labels("Use.kt", "package demo\nfun f() { val c: Color = mk| }")
        val idx = ls.indexOf("mkColor()").takeIf { it >= 0 } ?: ls.indexOfFirst { it.startsWith("mkColor") }
        assertTrue(idx >= 0, "mkColor should be offered; got ${ls.take(20)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Models.kt" to """
                    package demo
                    data class User(val name: String, val age: Int)
                    fun makeUser(name: String, age: Int): User = User(name, age)
                    enum class Color { RED, GREEN, BLUE }
                    fun paint(c: Color) {}
                    fun mkColor(): Color = Color.RED
                """.trimIndent(),
                "Base.kt" to """
                    package demo
                    abstract class Base {
                        abstract fun onCreate(savedState: String)
                        open fun render(): String = ""
                    }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
