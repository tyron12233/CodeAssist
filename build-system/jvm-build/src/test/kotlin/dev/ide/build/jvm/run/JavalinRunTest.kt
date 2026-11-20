package dev.ide.build.jvm.run

import dev.ide.build.InterpretRunRequest
import dev.ide.build.ProgramIo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A console run of a real web server: Javalin on Jetty, interpreted from its jars, serving one request.
 *
 * Reported from a phone: the run died in Jetty's class initialization with `illegal lookupClass: class
 * java.lang.reflect.Method`, because `MethodHandles.lookup()` was forwarded through reflection. A framework
 * like this is also the widest test the VM has of everything a library does at startup (service loading,
 * method handles, reflection over its own classes, threads, sockets), so it runs end to end here.
 */
@Timeout(180, unit = TimeUnit.SECONDS)
class JavalinRunTest {

    @Test fun javalinServesARequest() {
        val jars = (System.getProperty("javalin.run.classpath") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it).toPath() }.filter { Files.exists(it) }
        assumeTrue(jars.isNotEmpty(), "Javalin's classpath was not injected, skipping")
        val io = Recorder()

        val code = runBlocking {
            VmProgramInterpreter().run(
                InterpretRunRequest(listOf(programClasses()) + jars, "dev.ide.build.jvm.run.programs.JavalinHelloKt"),
                io,
            )
        }

        assertEquals(0, code, "the run failed:\n${io.text()}")
        assertTrue("GOT hello vm" in io.text(), "the server's answer must reach the console:\n${io.text()}")
    }

    private fun programClasses(): Path =
        File(JavalinRunTest::class.java.protectionDomain.codeSource.location.toURI()).toPath()

    private class Recorder : ProgramIo {
        private val out = StringBuilder()
        override val stdin: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun stdout(text: String) { synchronized(out) { out.append(text) } }
        fun text(): String = synchronized(out) { out.toString() }
    }
}
