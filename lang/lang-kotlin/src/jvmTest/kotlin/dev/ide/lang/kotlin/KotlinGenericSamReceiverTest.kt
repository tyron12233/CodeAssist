package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `fun interface` whose single abstract method is a member extension on the interface's OWN type parameter,
 * fixed by a subtype: Compose's `fun interface CustomStyle<in ScopeT> { fun ScopeT.applyStyle() }` and
 * `fun interface Style : CustomStyle<StyleScope>` (issue #1792).
 *
 * The lambda of `Style { … }` has `StyleScope` as its `this`, so `width(…)` resolves and completes, and an
 * override of the member keeps its receiver: `override fun StyleScope.applyStyle()`. The receiver used to read
 * as `kotlin.Any` from @Metadata (the class's type parameter was not looked up) and was lost entirely from
 * source, and override completion skipped member extensions altogether.
 */
class KotlinGenericSamReceiverTest {

    private val sourceApi = """
        package util
        interface CustomStyleScope
        interface StyleScope : CustomStyleScope { fun width(value: Int) }
        fun interface CustomStyle<in ScopeT : CustomStyleScope> { fun ScopeT.applyStyle() }
        fun interface Style : CustomStyle<StyleScope> {
            companion object : Style { override fun StyleScope.applyStyle() {} }
        }
        fun Style(vararg styles: Style): Style = styles.first()
        fun interface Plain { fun String.measure(): Int }
    """.trimIndent()

    @Test
    fun theSamLambdaTakesTheBoundReceiverFromSource() {
        assertEquals(emptyList(), sourceErrors("import util.*\nval s = Style { width(1) }\nval p = Plain { length }"))
    }

    @Test
    fun theSamLambdaTakesTheBoundReceiverFromALibrary() {
        assertEquals(emptyList(), libraryErrors("import $FIXTURES.*\nval s = Style { width(1) }"))
    }

    @Test
    fun theLambdaCompletesTheBoundReceiversMembers() {
        val labels = runBlocking {
            analyzer(src(), listOf(stdlibJarPath(), fixtureJar)).completeAtCaret(
                src(), "Use.kt", "package demo\nimport $FIXTURES.*\nval s = Style { wid| }",
            ).items.map { it.label }
        }
        assertTrue(labels.any { it.startsWith("width(") }, "`this` is StyleScope inside Style { }; got $labels")
    }

    @Test
    fun anOverrideStubKeepsTheReceiver() {
        assertTrue("override fun StyleScope.applyStyle()" in sourceStubs("class B : Style { |}"))
        assertTrue("override fun StyleScope.applyStyle()" in sourceStubs("class C : CustomStyle<StyleScope> { |}"))
        assertTrue("override fun String.measure(): Int" in sourceStubs("class P : Plain { |}"))
        val lib = runBlocking {
            analyzer(src(), listOf(stdlibJarPath(), fixtureJar)).completeAtCaret(
                src(), "Use.kt", "package demo\nimport $FIXTURES.*\nfun interface BoxStyle : CustomStyle<StyleScope> { |}",
            ).items.filter { it.detail == "override" }.map { it.label.removeSuffix(" {...}") }
        }
        assertTrue("override fun StyleScope.applyStyle()" in lib, "got $lib")
    }

    @Test
    fun implementMembersKeepsTheReceiver() {
        val code = "package demo\nimport util.*\nclass B : CustomStyle<StyleScope>\n"
        val src = tempProject(mapOf("Api.kt" to sourceApi, "Use.kt" to code))
        val a = analyzer(src, listOf(stdlibJarPath()))
        val doc = SnippetDoc(code, DiskFile(src.resolve("Use.kt")))
        runBlocking { a.incrementalParser.parseFull(doc) }
        val fix = a.implementMembersFix(doc.file, code.indexOf("B :"))
        val inserted = fix?.edits?.joinToString("") { it.newText }.orEmpty()
        assertTrue("override fun StyleScope.applyStyle()" in inserted, "got $inserted")
    }

    private fun sourceStubs(body: String): List<String> = runBlocking {
        val src = src()
        analyzer(src, listOf(stdlibJarPath())).completeAtCaret(src, "Use2.kt", "package demo\nimport util.*\n$body")
            .items.filter { it.detail == "override" }.map { it.label.removeSuffix(" {...}") }
    }

    private fun src(): Path = tempProject(mapOf("Api.kt" to sourceApi))

    private fun analyzer(src: Path, jars: List<Path>) = KotlinSourceAnalyzer(fakeContext(src, jars))

    private fun sourceErrors(code: String): List<Diagnostic> = errors(mapOf("Api.kt" to sourceApi), code, emptyList())

    private fun libraryErrors(code: String): List<Diagnostic> = errors(emptyMap(), code, listOf(fixtureJar))

    private fun errors(others: Map<String, String>, code: String, jars: List<Path>): List<Diagnostic> = runBlocking {
        val text = "package demo\n$code\n"
        val src = tempProject(others + ("Use.kt" to text))
        val a = analyzer(src, listOf(stdlibJarPath()) + jars)
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR }
    }

    companion object {
        private const val FIXTURES = "dev.ide.lang.kotlin.fixtures.samstyle"

        /** The fixture classes, compiled by this source set, packed as a library jar. */
        private val fixtureJar: Path by lazy {
            val dir = FIXTURES.replace('.', '/')
            TestJars.buildJar {
                for (name in listOf(
                    "CustomStyleScope", "StyleScope", "CustomStyle", "Style", "Style\$Companion", "StyleFixturesKt",
                )) {
                    val entry = "$dir/$name.class"
                    val bytes = KotlinGenericSamReceiverTest::class.java.classLoader.getResourceAsStream(entry)!!
                        .use { it.readBytes() }
                    entry(entry, bytes)
                }
            }
        }
    }
}
