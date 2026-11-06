package dev.ide.lang.kotlin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Find Usages / Rename's symbol search ([KotlinEditorFeatures.usageTargetAt] + [KotlinEditorFeatures.usagesIn])
 * over a small multi-file project. A reference counts only when it resolves to the target's declaration, so a
 * same-named but different symbol is never reported, which is the whole point over a text search.
 */
class KotlinUsagesTest {

    private fun text(file: String): String = Files.readString(srcDir.resolve(file))

    private fun target(file: String, needle: String, nth: Int = 0, forRename: Boolean = false): KotlinUsageTarget? {
        val t = text(file)
        var at = -1
        repeat(nth + 1) { at = t.indexOf(needle, at + 1) }
        check(at >= 0) { "'$needle' not in $file" }
        return analyzer.usageTargetAt(DiskFile(srcDir.resolve(file)), t, at, ::readSource, includeOverloads = forRename)
    }

    /** Every usage of [target] project-wide, as `File.kt:<text from the usage to the end of its line>`. */
    private fun usages(target: KotlinUsageTarget): List<String> =
        FILES.keys.sorted().flatMap { name ->
            val t = text(name)
            analyzer.usagesIn(DiskFile(srcDir.resolve(name)), t, target).map { r ->
                val lineEnd = t.indexOf('\n', r.start).let { if (it < 0) t.length else it }
                "$name:${t.substring(r.start, lineEnd).trim()}"
            }
        }

    @Test
    fun findsATopLevelFunctionsCallsAndImportsAcrossFiles() {
        val t = assertNotNull(target("Util.kt", "greet"))
        assertEquals("function", t.kind)
        assertEquals(
            listOf("Use.kt:greet", "Use.kt:greet(\"a\")"),
            usages(t),
            "the import's last segment and the call; not the unrelated `greet` in Other.kt",
        )
    }

    @Test
    fun theSameTargetFromAReferenceAsFromTheDeclaration() {
        val fromRef = assertNotNull(target("Use.kt", "greet(\"a\")"))
        val fromDecl = assertNotNull(target("Util.kt", "greet"))
        assertEquals(fromDecl.declarations, fromRef.declarations)
        assertTrue(fromRef.renameable)
    }

    @Test
    fun aClassIsFoundInTypesAndConstructorCalls() {
        val t = assertNotNull(target("Shapes.kt", "Circle"))
        assertEquals("class", t.kind)
        assertEquals(srcDir.resolve("Shapes.kt").toString(), t.declarations.single().first)
        val found = usages(t)
        assertTrue("Use.kt:Circle(2.0)" in found, "the constructor call; got $found")
        assertTrue(found.any { it.startsWith("Use.kt:Circle =") }, "the type in `val c: Circle`; got $found")
    }

    @Test
    fun overridesMoveTogether() {
        val t = assertNotNull(target("Shapes.kt", "area"))
        assertEquals(2, t.declarations.size, "Shape.area and Circle.area; got ${t.declarations}")
        val found = usages(t)
        assertTrue(found.any { it.startsWith("Shapes.kt:area()") }, "the call through a Shape; got $found")
        assertTrue(found.any { it.startsWith("Use.kt:area()") }, "the call on a Circle; got $found")
    }

    @Test
    fun findUsagesTellsOverloadsApart() {
        val ofInt = assertNotNull(target("Util.kt", "pad"))
        assertEquals(1, ofInt.declarations.size, "Find Usages is about this overload only; got ${ofInt.declarations}")
        assertEquals(listOf("Pads.kt:pad(1)"), usages(ofInt), "the Int call, not the String one")
        val ofString = assertNotNull(target("Util.kt", "pad", nth = 1))
        assertEquals(listOf("Pads.kt:pad(\"s\")"), usages(ofString))
    }

    @Test
    fun renameMovesOverloadsTogether() {
        val t = assertNotNull(target("Util.kt", "pad", forRename = true))
        assertEquals(2, t.declarations.size, "both `pad` overloads, so a rename always leaves code that compiles; got ${t.declarations}")
        assertEquals(listOf("Pads.kt:pad(1)", "Pads.kt:pad(\"s\")"), usages(t))
    }

    @Test
    fun aCallResolvesToTheOverloadItBindsTo() {
        val fromStringCall = assertNotNull(target("Pads.kt", "pad(\"s\")"))
        val stringDecl = text("Util.kt").indexOf("pad(s: String)")
        assertEquals(stringDecl, fromStringCall.declarations.single().second.start, "the String overload, not the first `pad`")
    }

    @Test
    fun aLocalIsSearchedOnlyInItsBlock() {
        val t = assertNotNull(target("Use.kt", "count"))
        assertEquals("variable", t.kind)
        assertNotNull(t.scope)
        assertEquals(listOf("Use.kt:count + 1)"), usages(t), "only the use in its own function, not `count` elsewhere")
    }

    @Test
    fun aParameterIsSearchedOnlyInItsFunction() {
        val t = assertNotNull(target("Util.kt", "who"))
        assertEquals("parameter", t.kind)
        assertEquals(listOf("Util.kt:who\""), usages(t))
    }

    @Test
    fun aLibrarySymbolIsFindableButNotRenameable() {
        val t = assertNotNull(target("Use.kt", "println"))
        assertTrue(!t.renameable, "a library function is not renamed from here")
        assertTrue(usages(t).isNotEmpty())
    }

    @Test
    fun nothingAtWhitespace() {
        assertNull(target("Use.kt", "\n\n"))
    }

    companion object {
        private val FILES = mapOf(
            "Shapes.kt" to """
                package demo

                open class Shape {
                    open fun area(): Double = 0.0
                }

                class Circle(val r: Double) : Shape() {
                    override fun area(): Double = r * r
                }

                fun total(s: Shape) = s.area()
            """.trimIndent() + "\n",
            "Util.kt" to """
                package demo.util

                fun greet(who: String) = "hi ${'$'}who"

                fun pad(n: Int) = n
                fun pad(s: String) = s
            """.trimIndent() + "\n",
            "Use.kt" to """
                package demo

                import demo.util.greet

                fun use() {
                    val c: Circle = Circle(2.0)
                    c.area()
                    greet("a")
                    val count = 1
                    println(count + 1)
                }

                fun other() {
                    val count = 2
                    println(count)
                }
            """.trimIndent() + "\n",
            "Pads.kt" to """
                package demo.util

                fun usePads() {
                    pad(1)
                    pad("s")
                }
            """.trimIndent() + "\n",
            "Other.kt" to """
                package demo.other

                fun greet() = "unrelated"

                fun caller() = greet()
            """.trimIndent() + "\n",
        )

        val srcDir: Path = tempProject(FILES)
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))

        fun readSource(path: String): CharSequence? = runCatching { Files.readString(Paths.get(path)) }.getOrNull()
    }
}
