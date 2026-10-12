package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `a.` on a wide Java type (`TextView`, with `View`'s members over 600) used to open on `x`, `y`, `z`, `id`,
 * `draw()`: the members sorted shortest name first, and the cap cut `visibility` and `setOnClickListener`
 * off entirely. Now the members code uses most come first, then the receiver's own members before its
 * superclass's, alphabetically, and a Java getter/setter its Kotlin property already stands for is not listed
 * twice. A "property" only a setter backs is not offered at all: Kotlin has none, so it would not compile.
 *
 * Needs `android.widget.TextView`: self-gates on a real android.jar, like [KotlinCollectionJvmMemberCompletionTest].
 */
class KotlinMemberRankingTest {

    private fun items(code: String): List<CompletionItem> {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping TextView member ranking")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items
    }

    private val onTextView = "fun f(a: android.widget.TextView) {\n  a.|\n}"

    @Test
    fun theMostUsedTextViewMembersComeFirst() {
        val top = items(onTextView).take(8).map { it.label }
        for (want in listOf("text", "setTextColor", "textSize", "setOnClickListener", "visibility")) {
            assertTrue(top.any { it == want || it.startsWith("$want(") }, "'$want' should be in the first 8; got $top")
        }
    }

    @Test
    fun aPropertyOnlyASetterBacksIsNotOffered() {
        // TextView has setTextColor but no getTextColor, and View has setEnabled beside isEnabled(): Kotlin has
        // no set-only properties, so `a.textColor = …` and `a.enabled = …` do not compile.
        val labels = items(onTextView).map { it.label }
        assertTrue("textColor" !in labels, "textColor has no getter; got ${labels.filter { it.startsWith("textC") }}")
        assertTrue("enabled" !in labels && "isEnabled" in labels, "the property for isEnabled() is `isEnabled`")
        assertTrue(labels.any { it.startsWith("setTextColor(") }, "the setter stays")
    }

    @Test
    fun accessorsAPropertyStandsForAreNotListedTwice() {
        val labels = items(onTextView).map { it.label }
        assertTrue("text" in labels, "the property is offered")
        assertTrue(labels.none { it.startsWith("getText(") }, "getText() duplicates `text`; got ${labels.filter { it.startsWith("getText") }}")
        val setters = labels.filter { it.startsWith("setText(") }
        assertTrue(setters.none { SINGLE_CHAR_SEQUENCE.matches(it) }, "setText(CharSequence) duplicates `text`; got $setters")
        // An overload the property can't express stays.
        assertTrue(setters.any { it.contains("int", ignoreCase = true) }, "setText(resid: Int) must stay; got $setters")
    }

    @Test
    fun spellingTheAccessorOutStillOffersIt() {
        val labels = items("fun f(a: android.widget.TextView) {\n  a.setTe|\n}").map { it.label }
        assertTrue(labels.any { it.startsWith("setText(") }, "a `set` prefix offers the setters; got $labels")
        val getters = items("fun f(a: android.widget.TextView) {\n  a.getTe|\n}").map { it.label }
        assertTrue(getters.any { it.startsWith("getText(") }, "a `get` prefix offers the getters; got $getters")
    }

    @Test
    fun theReceiversOwnMembersComeBeforeItsSuperclassesAlphabetically() {
        // Past the popular few, TextView's own members come before View's, each alphabetical.
        val rest = items(onTextView).filter { (it.relevance?.popularity ?: 0) == 0 && (it.relevance?.callableWeight ?: 0) == 0 }
        // TextView declares so many that View's long tail may fall past the cap entirely; what must not happen is
        // a View member interleaving with TextView's.
        val firstView = rest.indexOfFirst { it.container == "android.view.View" }
        val lastTextView = rest.indexOfLast { it.container == "android.widget.TextView" }
        assertTrue(lastTextView >= 0, "TextView's own members are offered")
        assertTrue(firstView < 0 || lastTextView < firstView, "TextView's members precede View's; got ${rest.take(10).map { it.label to it.container }}")
        val textViewNames = rest.filter { it.container == "android.widget.TextView" }.map { it.label.substringBefore('(') }
        assertEquals(textViewNames.sorted(), textViewNames, "TextView's own members are alphabetical")
    }

    companion object {
        private val SINGLE_CHAR_SEQUENCE = Regex("""setText\(\w+: CharSequence\??\)""")
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val androidJar: Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
            .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
            .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
            .maxByOrNull { it.parent.fileName.toString() }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOfNotNull(stdlibJarPath(), androidJar)))

        private fun sdkRoots() = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it) }.filter { Files.isDirectory(it) }
    }
}
