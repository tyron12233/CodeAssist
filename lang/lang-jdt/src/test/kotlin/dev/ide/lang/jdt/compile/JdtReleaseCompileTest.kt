package dev.ide.lang.jdt.compile

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * With no boot library on a desktop JDK, the build compiles with `--release`, so the module sees the JDK API
 * of its own release (what the editor's JDK API-level check reports) rather than the running JDK's.
 */
class JdtReleaseCompileTest {

    private fun compile(level: String): JdtBatchCompiler.Result {
        val dir = Files.createTempDirectory("release")
        val src = dir.resolve("src/demo/Use.java")
        Files.createDirectories(src.parent)
        Files.write(src, "package demo;\npublic class Use { Object m() { return java.util.List.of(1); } }\n".toByteArray())
        return JdtBatchCompiler.compile(listOf(src), emptyList(), dir.resolve("out"), sourceLevel = level)
    }

    @Test
    fun aJdkApiNewerThanTheReleaseDoesNotCompile() {
        assertTrue(JdtBatchCompiler.releaseAvailable, "the test JDK ships lib/ct.sym")
        val at8 = compile("1.8")
        assertFalse(at8.success, at8.messages.joinToString("\n"))
        assertTrue(at8.diagnostics.any { it.isError && "of" in it.message }, at8.diagnostics.toString())
        val at11 = compile("11")
        assertTrue(at11.success, at11.messages.joinToString("\n"))
    }
}
