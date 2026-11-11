package dev.ide.android.support.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon protocol against a stand-in written in shell: it prints `Ready`, reads one argument per line up to
 * an empty line, echoes them, prints `Error` first when the command is `fail`, and exits outright on `die`.
 */
class Aapt2DaemonTest {

    private fun fakeDaemon(): List<String>? {
        if (!Files.isExecutable(java.nio.file.Paths.get("/bin/sh"))) return null
        val script = Files.createTempFile("fake-aapt2", ".sh")
        Files.write(
            script,
            """
            echo Ready
            while true; do
              first=""
              while IFS= read -r line; do
                [ -z "${'$'}line" ] && break
                [ -z "${'$'}first" ] && first="${'$'}line"
                echo "arg:${'$'}line"
              done
              [ -z "${'$'}first" ] && exit 0
              [ "${'$'}first" = "die" ] && exit 3
              [ "${'$'}first" = "fail" ] && echo Error
              echo Done
            done
            """.trimIndent().toByteArray(),
        )
        return listOf("/bin/sh", script.toString())
    }

    @Test
    fun runsCommandsOnOneProcessAndReportsFailure() {
        val cmd = fakeDaemon() ?: return
        Aapt2Daemon(cmd).use { d ->
            val ok = d.run(listOf("compile", "--dir", "/some dir/res"))!!
            assertTrue(ok.success)
            assertEquals(listOf("arg:compile", "arg:--dir", "arg:/some dir/res"), ok.log)
            val bad = d.run(listOf("fail", "x"))!!
            assertFalse(bad.success)
            assertEquals(listOf("arg:fail", "arg:x"), bad.log)
            assertTrue(d.run(listOf("link"))!!.success, "the same process serves the next command")
        }
    }

    @Test
    fun aDaemonThatDiedIsRestartedForTheNextCommand() {
        val cmd = fakeDaemon() ?: return
        Aapt2Daemon(cmd).use { d ->
            assertTrue(d.run(listOf("compile"))!!.success)
            assertNull(d.run(listOf("die")), "a command that kills the daemon has no answer: the caller launches a process")
            assertTrue(d.run(listOf("compile"))!!.success, "the next command starts a new daemon")
        }
    }

    @Test
    fun whatCannotBeSentOrStartedFallsBack() {
        val cmd = fakeDaemon() ?: return
        Aapt2Daemon(cmd).use { d -> assertNull(d.run(listOf("compile", "two\nlines"))) }
        Aapt2Daemon(listOf("/nonexistent/aapt2", "daemon")).use { d -> assertNull(d.run(listOf("version"))) }
    }
}
