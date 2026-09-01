package dev.ide.build.jvm.run

import dev.ide.build.engine.InterpretRunRequest
import dev.ide.build.engine.ProgramIo
import dev.ide.build.jvm.run.programs.ThreadPrograms
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a run does with an exception on a thread the PROGRAM started. Such a thread is a real host thread, so
 * anything escaping it reaches the process-wide uncaught handler (on Android the system killer), which took
 * `com.tyron.code:build` down with the user's program (an interpreted `thread { Thread.sleep(...) }` whose
 * sleep the teardown interrupted, surfacing as an `UndeclaredThrowableException` from the lambda's proxy).
 *
 * So every test here asserts, on top of the behaviour, that NOTHING reached the default handler: that is the
 * process-death path, and it is invisible in a test JVM, where the default handler only prints.
 */
@Timeout(60, unit = TimeUnit.SECONDS)
class ProgramThreadFailureTest {

    @Test fun anExceptionOnAProgramThreadIsReportedToTheConsoleAndTheRunSurvives() {
        val (code, out) = run("WorkerThrows")

        assertEquals(0, code, "main returned normally, so the run's exit code is 0 as it is on a real JVM")
        assertTrue("main finished" in out, "the program must keep running after its worker died: $out")
        assertTrue("""Exception in thread "worker"""" in out, "the worker's failure belongs in the console: $out")
        assertTrue("boom on a worker" in out, "with the exception the program actually threw: $out")
    }

    @Test fun theTeardownInterruptOfASleepingThreadIsNotReportedAsAProgramFailure() {
        // The reported crash. The daemon is still in `Thread.sleep` when the run ends and the group is
        // interrupted; that interrupt is the run stopping, not something the program did wrong.
        val (code, out) = run("SleepingDaemon")

        assertEquals(0, code)
        assertTrue("main finished" in out)
        assertTrue("Exception in thread" !in out, "the teardown's own interrupt is not a program failure: $out")
    }

    @Test fun systemExitOnAProgramThreadEndsTheWholeRunWithThatCode() {
        // As on a real JVM: exit(7) from a worker ends the program, including a `main` blocked in a long sleep.
        val (code, _) = run("WorkerExits")

        assertEquals(7, code)
    }

    // ---- harness -----------------------------------------------------------------------------------

    /** Interpret [program] (a nested class of [ThreadPrograms]) to completion, returning its exit code and
     *  everything it wrote to the console. Fails if any throwable reached the process-wide handler. */
    private fun run(program: String): Pair<Int, String> {
        val escaped = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> escaped += t }
        val io = Recorder()
        val code = try {
            runBlocking {
                VmProgramInterpreter().run(
                    InterpretRunRequest(listOf(programClasses()), "${ThreadPrograms::class.java.name}\$$program"),
                    io,
                )
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertTrue(escaped.isEmpty(), "a program thread's $escaped reached the process handler, which kills the IDE")
        return code to io.text()
    }

    /** The test's own class output, which holds the compiled programs. */
    private fun programClasses(): Path =
        Path.of(ThreadPrograms::class.java.protectionDomain.codeSource.location.toURI())

    private class Recorder : ProgramIo {
        private val out = StringBuilder()
        override val stdin: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun stdout(text: String) { synchronized(out) { out.append(text) } }
        fun text(): String = synchronized(out) { out.toString() }
    }
}
