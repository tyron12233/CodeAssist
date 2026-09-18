package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `fun <caret>` offers the RECEIVER types a declaration can be given (`fun String.shout()`, `val List<T>.second`)
 * — the only way to declare an extension, and previously the one identifier spot that offered nothing at all,
 * so the popup fell back to buffer-word guesses and there was no way to reach (or auto-import) a receiver type.
 *
 * The spot is still where a name is INVENTED, so the offer is gated: only a callable declaration (never a
 * class/object/typealias/parameter name), only while no receiver has been typed yet, never a LOCAL `val` (Kotlin
 * forbids a local extension property), and only for an empty or CAPITALIZED prefix — typing `fun render…` must
 * not start proposing types.
 */
class KotlinDeclarationReceiverCompletionTest {

    private fun items(code: String): List<CompletionItem> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items
    }

    private fun names(code: String): List<String> = items(code).mapNotNull { it.symbol?.name }

    @Test
    fun functionNameOffersReceiverTypes() {
        val n = names("package demo\nfun Str|")
        assertTrue("String" in n, "a capitalized prefix at a `fun` name is reaching for a receiver type; got $n")
    }

    /** An explicitly-invoked popup at `fun ` (no prefix yet) lists the receiver options. */
    @Test
    fun theEmptyPrefixOffersThemToo() {
        val n = names("package demo\nfun |")
        assertTrue("String" in n, "expected the type list at a bare `fun `; got ${n.take(20)}")
    }

    /** The invent-a-name case is untouched: a lowercase prefix proposes no types. */
    @Test
    fun aLowercasePrefixOffersNoTypes() {
        val n = names("package demo\nfun rend|")
        assertFalse("Renderer" in n, "a lowercase name being typed must not pull in types; got $n")
    }

    /** Once the receiver is there, the caret is on the function's own NAME — no types. */
    @Test
    fun aReceiverAlreadyTypedOffersNoTypes() {
        val n = names("package demo\nfun String.Na|")
        assertTrue(n.isEmpty(), "the receiver slot is already filled; got $n")
    }

    @Test
    fun aTopLevelPropertyNameOffersThemAndALocalOneDoesNot() {
        assertTrue("String" in names("package demo\nval Str| = 1"), "`val String.x` is a valid extension property")
        assertTrue(
            names("package demo\nfun f() { val Str| = 1 }").isEmpty(),
            "Kotlin forbids a LOCAL extension property, so nothing belongs there",
        )
    }

    @Test
    fun aClassOrObjectNameOffersNoTypes() {
        assertTrue(names("package demo\nclass Str|").isEmpty(), "a class name takes no receiver")
        assertTrue(names("package demo\nobject Str|").isEmpty(), "an object name takes no receiver")
    }

    @Test
    fun aParameterNameOffersNoTypes() {
        assertTrue(names("package demo\nfun f(Str|)").isEmpty(), "a parameter name takes no receiver")
    }

    /** Accepting an unimported project type as the receiver adds its import, exactly as a type reference does. */
    @Test
    fun anUnimportedProjectTypeCarriesItsImport() {
        val sample = items("package demo\nfun Sampl|").first { it.symbol?.name == "Sample" }
        assertTrue(
            sample.additionalEdits.singleOrNull()?.newText?.contains("import util.Sample") == true,
            "the receiver type must auto-import; got ${sample.additionalEdits.map { it.newText }}",
        )
    }

    /** The override stubs that already own this spot in a class body keep leading it. */
    @Test
    fun overrideStubsStillComeFirst() {
        val labels = items("package demo\nimport util.Named\nclass C : Named { fun | }").map { it.label }
        assertTrue(labels.isNotEmpty(), "expected offers in a class body")
        assertTrue(
            labels.first().startsWith("override fun describe"),
            "an overridable member must still be the first offer; got ${labels.take(5)}",
        )
        assertTrue(labels.any { it == "String" }, "…with the receiver types after it; got ${labels.take(5)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Util.kt" to """
                package util
                class Sample
                class Renderer
                interface Named { fun describe(): String }
                """.trimIndent(),
            ),
        )
    }
}
