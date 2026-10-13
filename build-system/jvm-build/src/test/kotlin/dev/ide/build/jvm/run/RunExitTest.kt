package dev.ide.build.jvm.run

import androidx.compose.ui.window.ComposeExitProbe
import dev.ide.build.InterpretRunRequest
import dev.ide.build.ProgramIo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A program's ways of ending the process end only its run, through the real interpreter rather than a hand-built
 * argument list. A hand-built list is how the Compose clamp shipped broken: a run passes a `boolean` as Int 0/1,
 * and the clamp only recognised a Boolean. A regression here does not fail politely: the exit takes the test
 * JVM with it.
 */
@Timeout(120, unit = TimeUnit.SECONDS)
class RunExitTest {

    @Test fun composeApplicationRunsWithItsProcessExitTurnedOff() {
        ComposeExitProbe.exitProcessOnExit = null
        val io = Recorder()
        val code = run("dev.ide.build.jvm.run.programs.ComposeApplicationKt", io)

        assertEquals(0, code, "the run failed:\n${io.text()}")
        assertTrue("composed" in io.text(), "the content must have run: ${io.text()}")
        assertEquals(false, ComposeExitProbe.exitProcessOnExit, "application { } must not exit the process")
    }

    @Test fun kotlinExitProcessBecomesTheRunsExitCode() {
        val io = Recorder()
        val code = run("dev.ide.build.jvm.run.programs.ExitProcessKt", io)

        assertEquals(7, code, io.text())
        assertTrue("before exit" in io.text(), io.text())
    }

    private fun run(mainClass: String, io: ProgramIo): Int = runBlocking {
        VmProgramInterpreter().run(InterpretRunRequest(listOf(programClasses()), mainClass), io)
    }

    /** The test's own Kotlin class output, which holds the compiled programs. */
    private fun programClasses(): Path =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .map { Path.of(it) }
            .first { it.endsWith(Path.of("classes", "kotlin", "test")) }

    private class Recorder : ProgramIo {
        private val out = StringBuilder()
        override val stdin: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun stdout(text: String) { synchronized(out) { out.append(text) } }
        fun text(): String = synchronized(out) { out.toString() }
    }
}
