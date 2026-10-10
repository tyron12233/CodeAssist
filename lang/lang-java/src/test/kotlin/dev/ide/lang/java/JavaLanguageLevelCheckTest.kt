package dev.ide.lang.java

import dev.ide.lang.dom.Severity
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaLanguageLevelCheckTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    /** The language-level messages for [src] analyzed at Java [level]. */
    private fun messages(level: Int, src: String): List<String> {
        val f = File(srcRoot, "com/foo/Use.java").apply { parentFile.mkdirs(); writeText(src) }
        val parsed = JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L, languageLevel = { level })
        val d = parsed.diagnostics.filter { it.code == JavaDiagnosticCodes.LANGUAGE_LEVEL }
        assertTrue(d.all { it.severity == Severity.ERROR })
        return d.map { it.message }
    }

    private val modern = """
        package com.foo;
        interface Shape permits Circle, Square {}
        sealed interface Sealed permits Circle {}
        record Circle(double r) implements Shape, Sealed {}
        final class Square implements Shape {}
        interface Helper { private int secret() { return 1; } }
        class Use {
            String m(Object o, int day) {
                var x = 1;
                java.util.function.BiFunction<Integer, Integer, Integer> add = (var a, var b) -> a + b;
                String block = ""${'"'}
                    hi
                    ""${'"'};
                int n = switch (day) { case 1 -> 10; default -> 0; };
                switch (day) { case 2 -> n++; default -> n--; }
                if (o instanceof String s) return s;
                if (o instanceof Circle(double r)) return "circle";
                return switch (o) { case null -> "null"; case Integer i -> "int"; default -> "other"; };
            }
        }
    """.trimIndent()

    @Test
    fun java8ReportsEveryNewerFeature() {
        val m = messages(8, modern)
        val expected = listOf(
            "Sealed classes are not supported at language level '8' (requires 17)",
            "Records are not supported at language level '8' (requires 16)",
            "Private interface methods are not supported at language level '8' (requires 9)",
            "Local variable type inference ('var') is not supported at language level '8' (requires 10)",
            "'var' in lambda parameters is not supported at language level '8' (requires 11)",
            "Text blocks are not supported at language level '8' (requires 15)",
            "Switch expressions are not supported at language level '8' (requires 14)",
            "Switch rules ('case ->') are not supported at language level '8' (requires 14)",
            "Patterns in 'instanceof' are not supported at language level '8' (requires 16)",
            "Record patterns are not supported at language level '8' (requires 21)",
            "'case null' is not supported at language level '8' (requires 21)",
            "Patterns in switch labels are not supported at language level '8' (requires 21)",
        )
        for (e in expected) assertTrue(m.any { it == e }, "missing: $e\ngot:\n${m.joinToString("\n")}")
    }

    @Test
    fun java11AllowsVarButNotRecords() {
        val m = messages(11, modern)
        assertTrue(m.none { "'var'" in it }, m.toString())
        assertTrue(m.none { "Private interface" in it }, m.toString())
        assertTrue(m.any { it.startsWith("Records are not supported at language level '11'") }, m.toString())
        assertTrue(m.any { it.startsWith("Text blocks") }, m.toString())
    }

    @Test
    fun java17ReportsOnlyJava21Features() {
        val m = messages(17, modern)
        assertEquals(
            setOf("Record patterns", "'case null'", "Patterns in switch labels"),
            m.map { it.substringBefore(" are not").substringBefore(" is not") }.toSet(),
            m.toString(),
        )
    }

    @Test
    fun java21AndUnsetReportNothing() {
        assertEquals(emptyList(), messages(21, modern))
        assertEquals(emptyList(), messages(Int.MAX_VALUE, modern))
    }

    @Test
    fun plainJava8CodeIsClean() {
        assertEquals(emptyList(), messages(8, """
            package com.foo;
            class Use {
                interface I { default int d() { return 1; } static int s() { return 2; } }
                int m(int day) {
                    Runnable r = () -> {};
                    java.util.List<String> l = new java.util.ArrayList<>();
                    String var = "a name, not the keyword";
                    switch (day) { case 1: return 1; default: return 0; }
                }
            }
        """.trimIndent()))
    }
}
