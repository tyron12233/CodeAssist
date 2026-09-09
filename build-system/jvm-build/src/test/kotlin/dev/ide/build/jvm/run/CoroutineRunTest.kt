package dev.ide.build.jvm.run

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
 * A console run of a program that uses kotlinx.coroutines.
 *
 * `kotlin/` and `kotlinx/` are BRIDGED (`InterpretPolicy.DEFAULT`): the interpreter runs them as the real
 * classes the IDE itself runs on, not the versions on the project's classpath. That makes the IDE's own
 * coroutines version part of the contract for every console run, which is how the reported bug happened:
 * `runBlocking { }` compiled against kotlinx-coroutines 1.11.0 emits a call to `BuildersKt.runBlockingK$default`
 * (1.11.0 moved the Kotlin-facing builder behind a `@JvmName`), and the 1.10.2 the IDE shipped had no such
 * method, so the program died on its first line with a bare "no static method".
 *
 * Gradle compiles this program against the IDE's own coroutines, so the two can't drift apart HERE; what the
 * test pins down is the other half of the fix: that the call shape the bundled version emits actually runs
 * through the interpreter. Against 1.11.0 the compiled call site really is `runBlockingK$default` (the
 * reporter's symbol), and it now resolves and executes. A future bump that the VM cannot run fails here.
 */
@Timeout(120, unit = TimeUnit.SECONDS)
class CoroutineRunTest {

    @Test fun runBlockingProgramRunsAgainstTheBridgedCoroutines() {
        val io = Recorder()
        val code = runBlocking {
            VmProgramInterpreter().run(
                // Only the program's own classes: kotlin/ and kotlinx/ are bridged, so they need no classpath.
                InterpretRunRequest(listOf(programClasses()), "dev.ide.build.jvm.run.programs.HelloRunBlockingKt"),
                io,
            )
        }

        assertEquals(0, code, "the run failed:\n${io.text()}")
        assertTrue("Hello World" in io.text(), "the coroutine body must have run: ${io.text()}")
    }

    /**
     * An exception the PROGRAM declares, delivered through `Continuation.resumeWithException` inside
     * `suspendCoroutine`, must reach the `catch` that names it. The resume is synchronous, so the stdlib's real
     * `SafeContinuation.getOrThrow()` throws it back into the interpreted frame as the program class's generated
     * peer; matching the `catch` against the peer's own name found nothing, so the reported program printed no
     * "Caught" and died with an uncaught `…MyException_Peer3: just an exception`.
     */
    @Test fun anInterpretedExceptionResumedIntoASuspendPointIsCaught() {
        val io = Recorder()
        val code = runBlocking {
            VmProgramInterpreter().run(
                InterpretRunRequest(
                    listOf(programClasses()),
                    "dev.ide.build.jvm.run.programs.ResumeWithExceptionKt",
                ),
                io,
            )
        }

        assertEquals(0, code, "the run failed:\n${io.text()}")
        assertTrue("Caught" in io.text(), "the catch must have run: ${io.text()}")
    }

    /** The test's own Kotlin class output, which holds the compiled program. */
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
