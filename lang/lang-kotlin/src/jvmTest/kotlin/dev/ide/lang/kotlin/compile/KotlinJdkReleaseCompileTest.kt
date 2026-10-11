package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On a desktop JDK the Kotlin build passes `-Xjdk-release`, so a module sees the JDK API of its own Java
 * release (what the editor's JDK API-level check reports) rather than the running JDK's.
 */
class KotlinJdkReleaseCompileTest {

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    private fun compile(jvmTarget: String): KotlinCompileResult {
        val dir = Files.createTempDirectory("jdk-release")
        val src = dir.resolve("src/Use.kt")
        Files.createDirectories(src.parent)
        Files.write(src, "package demo\nfun m(a: String?): String = java.util.Objects.requireNonNullElse(a, \"x\")\n".toByteArray())
        return KotlinJvmCompiler().compile(listOf(src), emptyList(), emptyList(), dir.resolve("out"), jvmTarget = jvmTarget)
    }

    @Test
    fun aJdkApiNewerThanTheReleaseDoesNotCompile() {
        val at8 = compile("8")
        assertFalse(at8.success, at8.toString())
        assertTrue(at8.diagnostics.any { "requireNonNullElse" in it.message }, at8.diagnostics.toString())
        val at11 = compile("11")
        assertTrue(at11.success, at11.toString())
    }
}
