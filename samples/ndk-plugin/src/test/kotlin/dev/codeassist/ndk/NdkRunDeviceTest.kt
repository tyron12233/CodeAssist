package dev.codeassist.ndk

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * Run on a device: a program compiled by the real toolchain, started the way [NdkRunTask] starts it (staged
 * by [NativeLaunch], through `libndkrun.so`, as `linker64 <file>` on Android 10+). Skipped off the device.
 *
 * What it pins is the behaviour a console needs and a plain pipe gets wrong: the prompt arrives BEFORE the
 * program reads, input is not echoed back, end-of-input reaches the program, and a crash says so.
 */
class NdkRunDeviceTest {

    private lateinit var lib: Path
    private lateinit var toolchain: Path
    private lateinit var work: Path

    @BeforeEach
    fun setUp() {
        val libDir = System.getProperty("ndk.lib")
        val tc = System.getProperty("ndk.toolchain")
        assumeTrue(libDir != null && tc != null && File(libDir, "libndkrun.so").isFile, "device only")
        lib = Paths.get(libDir!!)
        toolchain = Paths.get(tc!!)
        work = Files.createTempDirectory(Paths.get(System.getProperty("java.io.tmpdir")), "run-test")
    }

    @AfterEach
    fun tearDown() {
        if (::work.isInitialized) work.toFile().deleteRecursively()
    }

    /** Compile [source] with the bundled clang the way the build does, 16 KB-aligned. */
    private fun compile(name: String, source: String): Path {
        val src = work.resolve("$name.c").also { it.writeText(source) }
        val out = work.resolve("bin").resolve(name)
        Files.createDirectories(out.parent)
        val p = ProcessBuilder(
            lib.resolve("libllvmtools.so").toString(), "clang",
            "--target=${NdkToolchain.TARGET_TRIPLE}",
            "-resource-dir", toolchain.resolve("lib-clang").resolve(NdkToolchain.LLVM_MAJOR).toString(),
            "--sysroot", toolchain.resolve("sysroot").toString(),
            "-O1", src.toString(),
            "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
            "--ld-path=${lib.resolve("libld-gnu-lld.so")}",
            "-o", out.toString(),
        ).redirectErrorStream(true).apply { environment()["LD_LIBRARY_PATH"] = lib.toString() }.start()
        val log = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), "compile failed: $log")
        return out
    }

    private fun start(program: Path, launcher: Path? = lib.resolve("libndkrun.so")): Process {
        val launch = NativeLaunch.prepare(program, "test", launcher)
        return ProcessBuilder(launch.command).redirectErrorStream(true)
            .apply { environment().putAll(launch.environment) }.start()
    }

    /** Read until [text] has arrived, or fail after [ms]: what the user would see in the console by then. */
    private fun awaitOutput(stream: InputStream, seen: ByteArrayOutputStream, text: String, ms: Long = 5_000) {
        val deadline = System.currentTimeMillis() + ms
        val buffer = ByteArray(1024)
        while (!seen.toString().contains(text)) {
            assertTrue(System.currentTimeMillis() < deadline, "waited for '$text', saw: '$seen'")
            if (stream.available() > 0) {
                val n = stream.read(buffer)
                if (n < 0) break
                seen.write(buffer, 0, n)
            } else Thread.sleep(20)
        }
    }

    @Test
    fun aPromptWithoutANewlineShowsBeforeTheProgramReads() {
        val program = compile(
            "prompt",
            """
            #include <stdio.h>
            #include <unistd.h>
            int main(void) {
                char name[64];
                printf("tty=%d\n", isatty(1));
                printf("Name: ");
                if (scanf("%63s", name) == 1) printf("hello %s\n", name);
                if (scanf("%63s", name) != 1) printf("got EOF\n");
                return 3;
            }
            """.trimIndent(),
        )
        val p = start(program)
        val seen = ByteArrayOutputStream()
        // Nothing has been typed yet, and the prompt is already there.
        awaitOutput(p.inputStream, seen, "Name: ")
        assertTrue(seen.toString().contains("tty=1"), "the program runs on a terminal: $seen")

        p.outputStream.write("tyron\n".toByteArray()); p.outputStream.flush()
        awaitOutput(p.inputStream, seen, "hello tyron")
        p.outputStream.close() // the console's end-of-input
        awaitOutput(p.inputStream, seen, "got EOF")
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        assertEquals(3, p.exitValue())
        // Typed once, and shown once (in "hello tyron"): an echoing terminal would have printed it again.
        assertEquals(1, Regex("tyron").findAll(seen.toString()).count(), "the terminal must not echo the input: '$seen'")
    }

    @Test
    fun aCrashIsReportedWithItsSignal() {
        val program = compile("crash", "int main(void) { volatile int *p = 0; return *p; }")
        val p = start(program)
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        assertEquals(128 + 11, p.exitValue())
        assertTrue("Segmentation fault" in out, out)
    }

    @Test
    fun withoutTheLauncherItStillRunsOverPipes() {
        val program = compile("plain", "#include <stdio.h>\nint main(void) { printf(\"plain ok\\n\"); return 0; }")
        val p = start(program, launcher = null)
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
        assertTrue("plain ok" in out, out)
    }

    @Test
    fun stoppingTheLauncherStopsTheProgram() {
        // A name nothing else on the device has, because the check below looks processes up by it.
        val program = compile(
            "ndkspin${System.nanoTime()}",
            "#include <stdio.h>\n#include <unistd.h>\nint main(void) { printf(\"spinning\\n\"); fflush(stdout); for (;;) sleep(1); }",
        )
        val p = start(program)
        val seen = ByteArrayOutputStream()
        awaitOutput(p.inputStream, seen, "spinning")
        p.destroyForcibly()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        // PR_SET_PDEATHSIG: the program went with the launcher instead of running on as an orphan.
        val ps = ProcessBuilder("pgrep", "-f", program.fileName.toString()).start()
        val left = ps.inputStream.bufferedReader().readText().trim()
        ps.waitFor()
        assertEquals("", left, "the program outlived Stop")
    }
}
