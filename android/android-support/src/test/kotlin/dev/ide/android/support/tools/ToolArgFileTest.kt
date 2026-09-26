package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The forked D8/R8/L8 launches pass the tool's arguments through a `@<file>` argument file ([ToolArgFile]),
 * so the launcher's command line stays a handful of words however many inputs a build has. Inline, a
 * dependency-heavy merge or minify overran the kernel's argument limit and the VM never started
 * (`error=7, Argument list too long`, issues #1236 and #1258).
 *
 * The launcher here is a shell script standing in for `dalvikvm64`: it records the argv it was started with and
 * copies the argument file it was pointed at, which is deleted as soon as the tool exits.
 */
class ToolArgFileTest {

    @Test
    fun writesOneArgumentPerLineAndKeepsSpaces() {
        withTempDir("toolargfile") { tmp ->
            val args = listOf("--output", "/data/user/0/app/files/My Project/build/dex", "--min-api", "26")
            val file = ToolArgFile.write(tmp, args)
            assertEquals(args, Files.readAllLines(file))
            assertEquals("@$file", ToolArgFile.reference(file))
        }
    }

    @Test
    fun rejectsAnArgumentWithALineBreak() {
        withTempDir("toolargfile") { tmp ->
            assertFailsWith<IllegalArgumentException> { ToolArgFile.write(tmp, listOf("--lib", "a\nb.jar")) }
        }
    }

    @Test
    fun deletesTheFileAfterTheToolRuns() {
        withTempDir("toolargfile") { tmp ->
            var seen: Path? = null
            ToolArgFile.withArgFile(tmp, listOf("--version")) { arg ->
                seen = Path.of(arg.removePrefix("@"))
                assertTrue(Files.isRegularFile(seen!!))
            }
            assertFalse(Files.exists(seen!!), "the argument file outlives the tool run")
        }
    }

    @Test
    fun forkedD8MergeOfManyInputsLaunchesWithAShortCommandLine() {
        withTempDir("toolargfile-d8") { tmp ->
            val launcher = fakeLauncher(tmp)
            val inputs = manyInputs(tmp.resolve("archives"), ".dex")
            val out = tmp.resolve("out")

            val r = D8Dexer(listOf(tmp.resolve("r8.dex.zip")), launcher, listOf("-Xmx1536m"), supportsGlobalSynthetics = false)
                .dex(inputs, tmp.resolve("android.jar"), 26, false, out, 2)

            assertTrue(r.success, "the launch failed: ${r.log}")
            val argv = Files.readAllLines(tmp.resolve("argv.txt"))
            assertEquals(listOf("-Xmx1536m", "-cp", tmp.resolve("r8.dex.zip").toString(), "com.android.tools.r8.D8"), argv.dropLast(1))
            assertTrue(argv.last().startsWith("@"), "the tool's arguments are not in an argument file: ${argv.last()}")
            val toolArgs = Files.readAllLines(tmp.resolve("argfile.txt"))
            assertEquals(inputs.map { it.toString() }, toolArgs.takeLast(inputs.size), "every input reaches D8, in order")
            assertTrue("--output" in toolArgs && out.toString() in toolArgs)
            assertFalse(Files.list(out).use { s -> s.anyMatch { it.fileName.toString().endsWith(".txt") } },
                "the argument file is left in the dex output dir")
        }
    }

    @Test
    fun forkedR8MinifyOfManyInputsLaunchesWithAShortCommandLine() {
        withTempDir("toolargfile-r8") { tmp ->
            val launcher = fakeLauncher(tmp)
            val programs = manyInputs(tmp.resolve("jars"), ".jar")
            val classpath = manyInputs(tmp.resolve("cp"), ".jar").take(200)
            val out = tmp.resolve("out")

            val r = R8Subprocess(listOf(tmp.resolve("r8.dex.zip")), launcher, listOf("-Xmx2048m")).shrink(
                ShrinkRequest(programs = programs, library = tmp.resolve("android.jar"), classpath = classpath, minApi = 26, outDir = out)
            )

            assertTrue(r.success, "the launch failed: ${r.log}")
            val argv = Files.readAllLines(tmp.resolve("argv.txt"))
            assertEquals(listOf("-Xmx2048m", "-cp", tmp.resolve("r8.dex.zip").toString(), "com.android.tools.r8.R8"), argv.dropLast(1))
            assertTrue(argv.last().startsWith("@"))
            val toolArgs = Files.readAllLines(tmp.resolve("argfile.txt"))
            assertEquals(programs.map { it.toString() }, toolArgs.takeLast(programs.size))
            classpath.forEach { assertTrue(it.toString() in toolArgs, "classpath entry $it did not reach R8") }
            assertTrue("--pg-conf" in toolArgs, "the pass-through keep rules are still passed")
        }
    }

    /**
     * 6000 existing inputs with long paths, about 1.3MB of argument text: past macOS's 1MB argument limit and
     * well into the range a phone's launch refused, so passing them inline would not launch at all here.
     */
    private fun manyInputs(dir: Path, ext: String): List<Path> {
        Files.createDirectories(dir)
        val deep = Files.createDirectories(dir.resolve("app/build/intermediates/dex-archives/debug/project/0123456789abcdef01234567"))
        return (0 until 6000).map { i ->
            deep.resolve("com_example_feature_module_${"%05d".format(i)}_SomeFairlyLongGeneratedClassName$ext")
                .also { Files.createFile(it) }
        }
    }

    /** A `dalvikvm64` stand-in that records its argv, one argument per line, and a copy of its `@` file. */
    private fun fakeLauncher(tmp: Path): Path {
        val script = tmp.resolve("fake-vm.sh")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |: > "${tmp.resolve("argv.txt")}"
            |for a in "${'$'}@"; do
            |  printf '%s\n' "${'$'}a" >> "${tmp.resolve("argv.txt")}"
            |  case "${'$'}a" in @*) cat "${'$'}{a#@}" > "${tmp.resolve("argfile.txt")}" ;; esac
            |done
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }
}
