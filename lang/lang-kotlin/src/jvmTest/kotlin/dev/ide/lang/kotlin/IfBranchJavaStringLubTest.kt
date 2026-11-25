package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An `if`/`when` whose branches are a Kotlin `String` and a Java method's `String` (platform `java.lang.String`)
 * is a `String`, not their supertype `CharSequence`. Field report: `web.settings.userAgentString =
 * if (c) desktopUa else WebSettings.getDefaultUserAgent(ctx)` read "inferred type is CharSequence but String
 * was expected".
 */
class IfBranchJavaStringLubTest {

    private fun errors(code: String): List<String> {
        val src = tempProject(mapOf("Main.kt" to code))
        val jdk = TestJars.jdkBaseJar(Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdk)))
        val doc = SnippetDoc(code, DiskFile(src.resolve("Main.kt")))
        return runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR }.map { "${it.code}: ${it.message}" }
    }

    @Test
    fun ifAssignedToJavaSetterProperty() = assertEquals(emptyList(), errors("""
        package demo
        fun test(t: Thread, c: Boolean) {
            val ua = "Desktop"
            t.name =
                if (c) ua
                else Integer.toHexString(1)
        }
    """.trimIndent()))

    @Test
    fun ifAssignedToStringVal() = assertEquals(emptyList(), errors("""
        package demo
        fun test(c: Boolean) {
            val s: String = if (c) "a" else Integer.toHexString(1)
            val t: String = if (c) Integer.toHexString(1) else "a"
        }
    """.trimIndent()))

    @Test
    fun whenAssignedToStringVal() = assertEquals(emptyList(), errors("""
        package demo
        fun test(n: Int) {
            val s: String = when (n) { 0 -> "a"; 1 -> Integer.toHexString(1); else -> java.lang.String.valueOf(n) }
        }
    """.trimIndent()))

    @Test
    fun elvisAndTryJoinJavaString() = assertEquals(emptyList(), errors("""
        package demo
        fun test(x: String?) {
            val s: String = x ?: Integer.toHexString(1)
            val t: String = try { Integer.toHexString(1) } catch (e: Exception) { "x" }
        }
    """.trimIndent()))
}
